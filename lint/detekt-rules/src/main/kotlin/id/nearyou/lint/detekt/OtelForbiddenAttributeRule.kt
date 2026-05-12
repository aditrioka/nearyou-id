package id.nearyou.lint.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtAnnotated
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType

/**
 * Forbids Kotlin string literals that smuggle PII / secret-shaped values into OTel span
 * attributes or Lettuce-instrumented Redis-EVALSHA `db.statement` spans.
 *
 * Sibling to [`ForbiddenAttributeStripper`](../../../../../../../../infra/otel/src/main/kotlin/id/nearyou/app/infra/otel/ForbiddenAttributeStripper.kt)
 * (the runtime SpanExporter decorator). The runtime stripper handles auto-instrumentation
 * attrs the developer did NOT write (the 11-entry `FORBIDDEN_KEYS` Set — 8 OTel peer-identity
 * semconv keys + 3 user-id typo-defensive variants). This rule handles the COMPLEMENTARY
 * compile-time check on the developer-written half: manual `Span.setAttribute(...)`,
 * `withSpan(name, mapOf(...))`, `AttributesBuilder.put(...)`, and any literal containing
 * `{ip:<non-canonical>}`. Together they form a three-layer defense-in-depth: runtime
 * stripping at SDK export + this commit-time lint + integration-test sentinel-string
 * regression scenarios at staging.
 *
 * ## Two enforcement modes
 *
 * **Mode A — Tier 1 + Tier 2 anywhere.** Fire on any Kotlin string literal whose unquoted
 * source text either (a) exactly equals one of 21 forbidden-attribute keys (Tier 1) OR
 * (b) matches one of 4 high-confidence sensitive-value regex patterns (Tier 2). No
 * call-site context check — mirrors `RawXForwardedForRule` / `CoordinateJitterRule`.
 *
 * - **Tier 1 Group A** (10): `FORBIDDEN_KEYS` mirror minus `"user_id"` — `client.address`,
 *   `client.port`, `http.client_ip`, `network.peer.address`, `network.peer.port`,
 *   `net.peer.ip`, `net.peer.port`, `net.sock.peer.addr`, `user_uuid`, `user.uuid`.
 *   `"user_id"` is INTENTIONALLY EXCLUDED — see "user_id carve-out" below.
 * - **Tier 1 Group B** (8): symmetric typo-defensive underscore variants of Group A's
 *   HTTP / network semconv keys — `client_address`, `client_port`, `http_client_ip`,
 *   `network_peer_address`, `network_peer_port`, `net_peer_ip`, `net_peer_port`,
 *   `net_sock_peer_addr`.
 * - **Tier 1 Group C** (3): JWT-claim attribute keys forbidden by canonical spec —
 *   `jwt.sub`, `jwt.aud`, `jwt.iss`.
 * - **Tier 2** (4 regex patterns): PEM private-key marker; JWT three-segment shape;
 *   Redis URI with userinfo (password); JWKS RSA-key JSON shape.
 *
 * **Mode B — IP-axis value-shape anywhere with NO call-site-context restriction.** Fire
 * on any Kotlin string literal containing `{ip:<value>}` where `<value>` is neither
 * (a) exactly 16 lowercase hex chars — the canonical `IpHasher.hash` output, nor
 * (b) a Kotlin template-string placeholder (`$<identifier>` OR `${<expression>}`).
 *
 * The IP-axis check is NOT scoped to `tryAcquireByKey(...)` call-context. Rationale: the
 * canonical production call site at
 * [`HealthRoutes.kt:166-170`](../../../../../../../../backend/ktor/src/main/kotlin/id/nearyou/app/health/HealthRoutes.kt)
 * hoists the literal `val key = "{scope:health}:{ip:$hashedIp}"` BEFORE passing it to
 * `tryAcquireByKey`. The PSI parent of the literal is `KtProperty`, NOT
 * `KtCallExpression(tryAcquireByKey)`. A parent-walk that requires `tryAcquireByKey` as
 * the immediate enclosing call would produce ZERO findings against the real codebase,
 * defeating the rule's purpose. Firing on any `{ip:<value>}` literal anywhere is the
 * correct enforcement boundary — the path-based allowlist (below) handles test fixtures
 * that legitimately need raw inputs.
 *
 * ## Path allowlist
 *
 * The rule does NOT fire when the containing file is on the allowlist:
 *
 *  - Any path containing `/src/test/` — broad test-fixture allowlist (mirrors
 *    `RedisHashTagRule` precedent). Verified surfaces: `infra/redis/src/test/`,
 *    `core/domain/src/test/`, `backend/ktor/src/test/`, `infra/otel/src/test/`.
 *  - `/infra/otel/src/main/` — `ForbiddenAttributeStripper` enumerates the keys as DATA.
 *  - `/lint/detekt-rules/src/main/` — this rule itself enumerates keys + regex as DATA.
 *  - Synthetic-file-harness fallback: package FQN starting with `id.nearyou.lint.detekt.`
 *    (detekt-test's `lint(String)` overload gives synthetic files no real
 *    `virtualFilePath`).
 *
 * ## Annotation bypass
 *
 * `@AllowForbiddenSpanAttribute("<reason>")` on the enclosing function, class, or property
 * suppresses the rule for any literal in that declaration. The reason MUST be `isNotBlank()`
 * (mirror of `RedisHashTagRule`'s `@AllowRawRedisKey` enforcement; empty / whitespace-only
 * reasons are silently bypass-ish and rejected). Single non-blank char ("x") passes — the
 * rule's job is to require a reason exists, not to assess its quality.
 *
 * ## `user_id` carve-out (Group A omission)
 *
 * `"user_id"` is part of the runtime `ForbiddenAttributeStripper.FORBIDDEN_KEYS` Set but
 * is INTENTIONALLY EXCLUDED from this rule's Tier 1 Group A. The string `"user_id"`
 * appears in ~12 production paths today as SQL column names (`rs.getObject("user_id",
 * UUID::class.java)`), `@SerialName` JSON keys, and Ktor route parameters
 * (`call.parameters["user_id"]`) across `:backend:ktor` chat / follow / block routes and
 * JDBC repositories. These uses are semantically unrelated to OTel attribute writes; a
 * lint exact-match on `"user_id"` would produce ~12 false positives with no canonical
 * fix. The runtime stripper continues to handle emitted `"user_id"` attributes
 * defensively at export, AND the integration-test sentinel scenario "No raw user_id
 * appears in any span" covers value-side leakage. The carve-out is documented at the
 * canonical spec § "Tier 1 — forbidden-attribute-key literals" / design.md § Decision 3.
 * A deferred follow-up (`otel-attribute-rule-psi-context-restricted-mode-a`) could
 * PSI-restrict Mode A enforcement to setAttribute-like call contexts and allow
 * re-introducing `"user_id"` to Tier 1.
 *
 * The defensive variants `"user_uuid"` and `"user.uuid"` stay in Tier 1 — they have
 * zero current source-text appearances AND the dot-shaped form is OTel-attribute-specific
 * (never used as SQL column name; SQL/JSON convention uses underscore).
 *
 * ## Why dynamic-key construction is NOT in scope
 *
 * `Span.setAttribute("network." + "peer.address", value)` or
 * `Span.setAttribute(KEY_PREFIX + "address", value)` would not match a literal regex.
 * The rule scans `KtStringTemplateExpression`s; concatenation chains evaluating to a
 * forbidden key are invisible. Mitigation: dynamic key construction is itself a
 * code-review smell and would be flagged in PR review; the runtime stripper covers this
 * path as a backstop.
 *
 * ## Why value-aware user-id alias detection is deferred
 *
 * Generic-named keys (`principal`, `actor`, `subject`, `owner`) are forbidden by the
 * canonical spec WHEN their value is a raw user UUID, but key-name-exact-match alone
 * would block legitimate auth-domain code. Deferred to follow-up
 * `otel-attribute-rule-value-aware-userid-aliases` which would chase value resolution
 * to UUID-shaped literals. Out of scope for this rule.
 *
 * Composition with sibling rules: orthogonal. A literal can fire `CoordinateJitterRule`
 * (raw `actual_location`) AND this rule (Tier 1 key) independently; `RedisHashTagRule`
 * (legacy `rate:` prefix) and this rule's Mode B (raw IP) compose independently too.
 *
 * See the `observability-otel-foundation` capability spec ADDED requirements
 * (`OtelForbiddenAttributeRule fences forbidden span-attribute writes`, `Allowlist for
 * OtelForbiddenAttributeRule`, `Detekt test coverage for OtelForbiddenAttributeRule`) and
 * the `rate-limit-infrastructure` ADDED requirement (`OtelForbiddenAttributeRule fences
 * raw IP literal in {ip:<value>} rate-limit-key segments`) for the authoritative
 * invariants + scenarios.
 */
class OtelForbiddenAttributeRule(config: Config = Config.empty) : Rule(config) {
    override val issue: Issue =
        Issue(
            id = RULE_ID,
            severity = Severity.Defect,
            description =
                "Forbidden span-attribute key or sensitive-value pattern detected in Kotlin " +
                    "string literal. Tier 1 attribute keys (HTTP / network peer semconv, " +
                    "user-id typos, JWT claims) and Tier 2 sensitive-value patterns " +
                    "(PEM private key, JWT shape, Redis URI with userinfo, JWKS RSA) MUST NOT " +
                    "appear on spans. Use `UserIdHasher.hash(...)` / `IpHasher.hash(...)` " +
                    "consumption; IP-axis Redis keys MUST use canonical 16-hex hash or Kotlin " +
                    "template interpolation. To bypass, annotate the declaration " +
                    "`@AllowForbiddenSpanAttribute(\"<non-empty reason>\")`. See the " +
                    "`observability-otel-foundation` capability spec.",
            debt = Debt.TEN_MINS,
        )

    override fun visitStringTemplateExpression(expression: KtStringTemplateExpression) {
        super.visitStringTemplateExpression(expression)

        val file: KtFile = expression.containingKtFile
        if (isAllowedPath(file)) return
        if (expression.isInsideAllowedAnnotation()) return

        val unquoted =
            expression.text
                .removeSurrounding("\"\"\"")
                .removeSurrounding("\"")

        val firesTier1 = unquoted in TIER_1_FORBIDDEN_KEYS
        val firesTier2 = TIER_2_PATTERNS.any { it.containsMatchIn(unquoted) }
        val firesIpAxis = IP_AXIS_PATTERN.containsMatchIn(unquoted)
        if (!firesTier1 && !firesTier2 && !firesIpAxis) return

        report(
            CodeSmell(
                issue,
                Entity.from(expression),
                issue.description,
            ),
        )
    }

    private fun isAllowedPath(file: KtFile): Boolean {
        val normalized = file.virtualFilePath.replace('\\', '/')
        if ("/src/test/" in normalized) return true
        if ("/infra/otel/src/main/" in normalized) return true
        if ("/lint/detekt-rules/src/main/" in normalized) return true
        val pkg = file.packageFqName.asString()
        if (pkg == "id.nearyou.lint.detekt" || pkg.startsWith("id.nearyou.lint.detekt.")) return true
        return false
    }

    private fun KtStringTemplateExpression.isInsideAllowedAnnotation(): Boolean {
        var ancestor: KtAnnotated? = getParentOfType<KtAnnotated>(strict = true)
        while (ancestor != null) {
            for (entry in ancestor.annotationEntries) {
                if (entry.shortName?.asString() != ALLOW_ANNOTATION_SHORT) continue
                val reasonArg = entry.valueArguments.firstOrNull()?.getArgumentExpression()
                val reasonText = reasonArg?.text ?: continue
                val unwrapped =
                    reasonText
                        .removeSurrounding("\"\"\"")
                        .removeSurrounding("\"")
                // Evaluate the common whitespace escape sequences (`\t`, `\n`, `\r`) before
                // the isBlank() check so a source-text reason of `"\t"` (which is two source
                // chars — `\` then `t`) collapses to a single tab and is correctly rejected
                // as blank. Mirrors `RedisHashTagRule`'s `isNotBlank()` precedent but with
                // escape-sequence awareness per spec § "Empty-reason / whitespace-only-reason
                // annotation still fires" item 10.
                val evaluated =
                    unwrapped
                        .replace("\\t", "\t")
                        .replace("\\n", "\n")
                        .replace("\\r", "\r")
                if (evaluated.isNotBlank()) return true
            }
            ancestor = ancestor.getParentOfType<KtAnnotated>(strict = true)
        }
        return false
    }

    companion object {
        const val RULE_ID: String = "OtelForbiddenAttributeRule"
        const val ALLOW_ANNOTATION_SHORT: String = "AllowForbiddenSpanAttribute"

        /**
         * Tier 1 Group A — `ForbiddenAttributeStripper.FORBIDDEN_KEYS` mirror MINUS the
         * `"user_id"` carve-out (10 keys).
         *
         * The synchronization-guard test in `OtelForbiddenAttributeLintTest` asserts the
         * relationship `TIER_1_GROUP_A.containsAll(FORBIDDEN_KEYS - {"user_id"})` so this
         * set stays in sync with `ForbiddenAttributeStripper.kt:89-108` minus the
         * documented carve-out.
         *
         * If `FORBIDDEN_KEYS` gains a new entry: decide whether (a) the new key belongs
         * in Tier 1 Group A (add it here AND update the test's hardcoded snapshot) OR
         * (b) the new key is a similar carve-out (update the test's carve-out set with
         * a documented rationale). See KDoc § "user_id carve-out" for the precedent.
         */
        val TIER_1_GROUP_A: Set<String> =
            setOf(
                // HTTP client-identity semconv (same name across old + new) — 3 keys.
                "client.address",
                "client.port",
                "http.client_ip",
                // Peer/network — new semconv (OTel Java 2.x) — 2 keys.
                "network.peer.address",
                "network.peer.port",
                // Peer/network — old semconv (kept for backward-compat) — 3 keys.
                "net.peer.ip",
                "net.peer.port",
                "net.sock.peer.addr",
                // User-id typo-defensive variants (carve-out applied — `user_id` NOT here) — 2 keys.
                "user_uuid",
                "user.uuid",
            )

        /**
         * Tier 1 Group B — symmetric typo-defensive underscore variants of Group A's
         * HTTP / network semconv keys (8 keys). The runtime stripper does NOT enumerate
         * these — they're lint-only coverage for "developer types `client_address` with
         * underscore instead of `client.address` with dot, bypassing the typo guard".
         */
        val TIER_1_GROUP_B: Set<String> =
            setOf(
                "client_address",
                "client_port",
                "http_client_ip",
                "network_peer_address",
                "network_peer_port",
                "net_peer_ip",
                "net_peer_port",
                "net_sock_peer_addr",
            )

        /**
         * Tier 1 Group C — JWT-claim attribute keys (3 keys). Per canonical spec §
         * "Forbidden span attributes" bullet 5, raw JWT claims (`sub`, `aud`, `iss`)
         * MUST NEVER appear as span attribute keys; the sanctioned anonymization shape
         * for token correlation is the truncated SHA-256 from `internal-endpoint-auth/spec.md:18`.
         */
        val TIER_1_GROUP_C: Set<String> =
            setOf(
                "jwt.sub",
                "jwt.aud",
                "jwt.iss",
            )

        private val TIER_1_FORBIDDEN_KEYS: Set<String> =
            TIER_1_GROUP_A + TIER_1_GROUP_B + TIER_1_GROUP_C

        /**
         * Tier 2 — sensitive-value regex patterns (4 patterns). Each is a high-confidence
         * marker; false-positive risk is non-zero but the patterns are specific enough to
         * avoid triggering on unrelated text. Broader patterns (OAuth `client_secret`,
         * raw refresh tokens, plaintext passwords) deferred to follow-up
         * `otel-attribute-rule-opaque-secrets` per design.md § "Explicitly deferred
         * follow-ups".
         */
        private val TIER_2_PATTERNS: List<Regex> =
            listOf(
                // PEM private-key marker (RSA / EC / Ed25519): -----BEGIN <KIND> PRIVATE KEY-----.
                // The `[A-Z ]+` between BEGIN and PRIVATE KEY allows for "RSA " / "EC " /
                // "ED25519 " / "" (some PEM headers omit the key-type label). PUBLIC keys
                // pass — the pattern requires PRIVATE KEY explicitly.
                Regex("""-{5}BEGIN [A-Z ]*PRIVATE KEY-{5}"""),
                // JWT three-segment shape: base64url header + "." + base64url payload + "."
                // (signature segment present but not matched — the two-period anchor is
                // sufficient to identify a JWT). Both segments must start with `eyJ`
                // (base64url-encoded `{"...` JSON header start). The 10+ length floor on
                // each segment avoids matching coincidental two-period base64 fragments.
                Regex("""eyJ[A-Za-z0-9_\-]{10,}\.eyJ[A-Za-z0-9_\-]{10,}\."""),
                // Redis URI with explicit userinfo (password embedded). Pattern:
                // redis://USER:PASS@HOST. The `[^@/]+@` anchor requires an @ before any /,
                // so `redis://host:6379/0` (no userinfo) does NOT match.
                Regex("""redis://[^:]+:[^@/]+@"""),
                // JWKS RSA-key JSON shape: "kty":"RSA" followed by "n":. The `\s*,?\s*`
                // between keys allows reordered JSON (`"kty":"RSA","n":` or with whitespace).
                // Specific enough to avoid false-positives on legitimate JSON-with-`kty`
                // in unrelated contexts.
                Regex(""""kty"\s*:\s*"RSA"\s*,?\s*"n"\s*:"""),
            )

        /**
         * Mode B — IP-axis value-shape pattern. Fires on `{ip:<value>}` where `<value>`
         * is NEITHER (a) exactly 16 lowercase hex chars (canonical `IpHasher.hash` output)
         * NOR (b) the start of a Kotlin template placeholder (`$identifier` OR
         * `${expression}` — both begin with `$`). The two negative lookaheads handle
         * the two passing cases; `[^}]*\}` consumes the rest.
         *
         * Passes (no fire):
         *  - `{ip:abcdef0123456789}` — canonical 16-hex
         *  - `{ip:$hashedIp}` — simple-name template (canonical production shape)
         *  - `{ip:${IpHasher.hash(clientIp)}}` — block-form template
         *
         * Fires:
         *  - `{ip:1.2.3.4}` — raw IPv4 dotted-quad
         *  - `{ip:[2001:db8::1]}` — raw IPv6
         *  - `{ip:abcdef012345678}` — 15 hex (one short)
         *  - `{ip:abcdef01234567890}` — 17 hex (one over)
         *  - `{ip:ABCDEF0123456789}` — uppercase 16 hex (canonical is lowercase)
         */
        private val IP_AXIS_PATTERN: Regex =
            Regex("""\{ip:(?![0-9a-f]{16}\})(?!\$)[^}]*\}""")
    }
}
