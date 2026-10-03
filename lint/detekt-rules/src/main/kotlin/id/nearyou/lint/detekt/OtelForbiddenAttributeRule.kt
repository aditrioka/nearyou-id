package id.nearyou.lint.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtAnnotated
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtPostfixExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtValueArgumentList
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
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
 * ## Three enforcement modes
 *
 * **Mode A — Tier 1 + Tier 2 anywhere.** Fire on any Kotlin string literal whose unquoted
 * source text either (a) exactly equals one of 21 forbidden-attribute keys (Tier 1) OR
 * (b) matches one of 11 high-confidence sensitive-value regex patterns (Tier 2). No
 * call-site context check — mirrors `RawXForwardedForRule` / `CoordinateJitterRule`.
 *
 * - **Tier 1 Group A** (10): `FORBIDDEN_KEYS` entries enforced anywhere — `client.address`,
 *   `client.port`, `http.client_ip`, `network.peer.address`, `network.peer.port`,
 *   `net.peer.ip`, `net.peer.port`, `net.sock.peer.addr`, `user_uuid`, `user.uuid`.
 *   The 11th entry, `"user_id"`, is enforced by the attribute-key-position mode below.
 * - **Tier 1 Group B** (8): symmetric typo-defensive underscore variants of Group A's
 *   HTTP / network semconv keys — `client_address`, `client_port`, `http_client_ip`,
 *   `network_peer_address`, `network_peer_port`, `net_peer_ip`, `net_peer_port`,
 *   `net_sock_peer_addr`.
 * - **Tier 1 Group C** (3): JWT-claim attribute keys forbidden by canonical spec —
 *   `jwt.sub`, `jwt.aud`, `jwt.iss`.
 * - **Tier 2** (11 regex patterns): PEM private-key marker; JWT three-segment shape;
 *   credentialed `redis://` / `rediss://` URI; JWKS RSA-key JSON shape; vendor-prefixed
 *   opaque secrets (Google OAuth client secret / access / refresh token, Supabase
 *   `sb_secret_`, Grafana Cloud `glc_`, OpenAI `sk-proj-`, RevenueCat `sk_`).
 *
 * **Mode A — attribute-key position (PSI-context-restricted).** Key-name checks too noisy
 * to run anywhere fire only when the literal is an OTel attribute key: a `setAttribute`
 * key, an `AttributeKey.stringKey(...)`-family argument, a `put` key on a
 * builder-evidenced `AttributesBuilder`, or a `mapOf` entry key reaching `withSpan`'s
 * `attributes` (directly or via a same-file `val`). Keys are tokenized on `.`/`_`/`-` +
 * camelCase, so `latency_ms` is NOT a location key and `userCoords` IS. Four checks:
 *  1. `"user_id"` (any value) — its ~30 SQL-column / `@SerialName` / route-param /
 *     `buildJsonObject` literals are not attribute keys, so they stay silent.
 *  2. User-identity alias tokens (`user`, `enduser`, `principal`, `actor`, `subject`,
 *     `owner`, `account`) — fire only when the value is a raw identifier: UUID literal,
 *     `UUID.randomUUID()`-family call, a same-file `UUID`-typed (or V1/V2/V4-initialized)
 *     name, or the domain naming convention (`userId` / `*UserId` / `user.id`). `"system"`
 *     and `UserIdHasher.hash(...)` pass.
 *  3. Location tokens (`location`, `lat`, `lng`, `lon`, `coord`, `geohash`, `wkt`, …) —
 *     except the exact key `display_location` (the HMAC-fuzzed post coordinate, sanctioned).
 *  4. Credential tokens (`password`, `secret`, `bearer`, `authorization`, `apikey`,
 *     `cookie`, or the pairs `refresh/access/id token`, `api/private key`, `service role`).
 *  5. Any key whose value is a raw JWT / OIDC claim (`.sub`, `payload.subject`, …).
 * A key literal hoisted into a same-file `const val` / `val` is checked at the reference
 * that uses it as a key.
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
 * ## Out of reach (syntactic PSI, no type resolution)
 *
 * Dynamic key construction (`"network." + "peer.address"`), attribute maps built in
 * another file / via `buildMap { }` / composed with `+`, an unqualified `put` inside
 * `Attributes.builder().apply { }`, key constants declared in another file, the value of a
 * standalone hoisted `AttributeKey` (no paired value — key checks only), and
 * user-referencing keys outside the alias token set (`author_id`, …) are invisible to this
 * rule. Same-file name resolution (builder variables, hoisted maps / keys, `UUID`-typed
 * names) is by name, not lexical scope. Dynamic keys are a code-review smell; the runtime
 * stripper + sentinel scenarios + review backstop the rest.
 *
 * Composition with sibling rules: orthogonal. A literal can fire `CoordinateJitterRule`
 * (raw `actual_location`) AND this rule (Tier 1 key, or the location check on a
 * `setAttribute("actual_location", …)` key) independently; `RedisHashTagRule` (legacy
 * `rate:` prefix) and this rule's Mode B (raw IP) compose independently too. A literal is
 * reported at most once by this rule however many checks match it.
 *
 * See the `observability-otel-foundation` capability spec requirements
 * (`OtelForbiddenAttributeRule fences forbidden span-attribute writes`,
 * `OtelForbiddenAttributeRule checks attribute-key positions`, `Allowlist for
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
                    "user-id typos, JWT claims), attribute keys for `user_id`, location " +
                    "(except `display_location`), credentials, a raw JWT claim value, or a user-identity alias carrying a raw " +
                    "identifier, and Tier 2 sensitive-value patterns (PEM private key, JWT shape, " +
                    "credentialed Redis URI, JWKS RSA, vendor-prefixed secret) MUST NOT " +
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
        if (!firesTier1 && !firesTier2 && !firesIpAxis && !firesAsAttributeKey(expression, unquoted)) return

        report(
            CodeSmell(
                issue,
                Entity.from(expression),
                issue.description,
            ),
        )
    }

    /**
     * Attribute-key-position checks (spec § "`OtelForbiddenAttributeRule` checks
     * attribute-key positions"). Fires only when [literal] is an OTel attribute key —
     * see [attributeKeySite] — so the same text elsewhere (SQL column, `@SerialName`,
     * route param, JSON builder key) stays silent.
     */
    private fun firesAsAttributeKey(
        literal: KtStringTemplateExpression,
        key: String,
    ): Boolean {
        val site = attributeKeySite(literal) ?: return false
        if (key in CONTEXT_RESTRICTED_KEYS) return true
        val tokens = tokenize(key)
        if (tokens != SANCTIONED_LOCATION_KEY && tokens.any { it in LOCATION_TOKENS }) return true
        if (tokens.any { it in CREDENTIAL_TOKENS } || tokens.zipWithNext().any { it in CREDENTIAL_TOKEN_PAIRS }) return true
        val value = site.value ?: return false
        if (isRawClaim(value)) return true
        return tokens.any { it in USER_IDENTITY_ALIAS_TOKENS } && isRawIdentifier(value)
    }

    /** An attribute-key position plus its paired value expression (null when the shape has none in reach). */
    private class AttributeKeySite(val value: KtExpression?)

    /**
     * The literal's own attribute-key position, or — for `const val K = "user_id"` — the
     * position of a same-file reference to `K` used as a key.
     */
    private fun attributeKeySite(literal: KtStringTemplateExpression): AttributeKeySite? = keySite(literal) ?: hoistedKeySite(literal)

    private fun hoistedKeySite(literal: KtStringTemplateExpression): AttributeKeySite? {
        val property = literal.parent as? KtProperty ?: return null
        if (property.initializer != literal) return null
        val name = property.name ?: return null
        return literal.containingKtFile
            .collectDescendantsOfType<KtNameReferenceExpression> { it.getReferencedName() == name }
            .firstNotNullOfOrNull { ref ->
                keySite((ref.parent as? KtQualifiedExpression)?.takeIf { it.selectorExpression == ref } ?: ref)
            }
    }

    /**
     * Returns a site when [key] is in one of the four attribute-key positions, else null:
     *  - P1 `setAttribute(key, value)` key argument (named `key`, else unnamed position 0);
     *  - P2 `AttributeKey.stringKey(...)`-family argument (value = the sibling value when the
     *    factory call is itself the key of `setAttribute` / `put` / `Attributes.of`);
     *  - P3 `put(key, value)` on a builder-evidenced `AttributesBuilder` receiver (a generic
     *    `Map.put` or `buildJsonObject { put(...) }` is NOT builder-evidenced);
     *  - P4 `mapOf(key to value)` / `mapOf(Pair(key, value))` entry whose map is `withSpan`'s
     *    `attributes` argument, directly or via a same-file `val`.
     *
     * ponytail: syntactic only (project detekt has no type resolution). Untraced: maps built
     * in another file, via `buildMap { }`, or composed with `+`; unqualified `put` inside
     * `Attributes.builder().apply { }`; key constants declared in another file; dynamic key
     * concatenation. The runtime `ForbiddenAttributeStripper` + sentinel scenarios backstop those.
     */
    private fun keySite(key: KtExpression): AttributeKeySite? {
        val parent = key.parent
        if (parent is KtBinaryExpression && parent.operationReference.text == "to" && parent.left == key) {
            return if (isWithSpanAttributeMap(parent.parent?.parent?.parent)) AttributeKeySite(parent.right) else null
        }
        val arg = parent as? KtValueArgument ?: return null
        val call = (arg.parent as? KtValueArgumentList)?.parent as? KtCallExpression ?: return null
        val args = call.valueArguments
        return when (call.calleeExpression?.text) {
            "setAttribute" ->
                if (namedOrPositional(call, "key", 0) == arg) {
                    AttributeKeySite(namedOrPositional(call, "value", 1)?.getArgumentExpression())
                } else {
                    null
                }
            in ATTRIBUTE_KEY_FACTORIES -> {
                val receiver = call.receiver()
                if (args.firstOrNull() != arg || (receiver != null && !receiver.text.endsWith("AttributeKey"))) return null
                AttributeKeySite(enclosingWriteValue(call))
            }
            "put" ->
                if (args.size == 2 && args[0] == arg && isBuilderEvidenced(call)) {
                    AttributeKeySite(args[1].getArgumentExpression())
                } else {
                    null
                }
            "Pair" ->
                if (args.size == 2 && args[0] == arg && isWithSpanAttributeMap(call.parent?.parent?.parent)) {
                    AttributeKeySite(args[1].getArgumentExpression())
                } else {
                    null
                }
            else -> null
        }
    }

    /**
     * The value paired with an `AttributeKey` factory call used as a key: argument 1 of
     * `setAttribute(key, v)` / `put(key, v)`, or the next argument of `Attributes.of(k1, v1, …)`.
     */
    private fun enclosingWriteValue(factoryCall: KtCallExpression): KtExpression? {
        val keyExpr: KtExpression =
            (factoryCall.parent as? KtQualifiedExpression)?.takeIf { it.selectorExpression == factoryCall } ?: factoryCall
        val outerArg = keyExpr.parent as? KtValueArgument ?: return null
        val outerCall = (outerArg.parent as? KtValueArgumentList)?.parent as? KtCallExpression ?: return null
        val outerArgs = outerCall.valueArguments
        val index = outerArgs.indexOf(outerArg)
        val valueIndex =
            when (outerCall.calleeExpression?.text) {
                "setAttribute", "put" -> if (index == 0) 1 else return null
                "of" -> if (outerCall.receiver()?.text?.endsWith("Attributes") == true && index % 2 == 0) index + 1 else return null
                else -> return null
            }
        return outerArgs.getOrNull(valueIndex)?.getArgumentExpression()
    }

    private fun isBuilderEvidenced(putCall: KtCallExpression): Boolean {
        val receiver = putCall.receiver() ?: return false
        if (BUILDER_CHAIN_MARKERS.any { it in receiver.text }) return true
        val name = (receiver as? KtNameReferenceExpression)?.getReferencedName() ?: return false
        return sameFileDeclarations(putCall, name).any { decl ->
            decl.typeReference?.text?.removeSuffix("?") in ATTRIBUTES_BUILDER_TYPES ||
                ((decl as? KtProperty)?.initializer?.text?.contains("Attributes.builder()") == true)
        }
    }

    /** True when [candidate] is a map-builder call that reaches a `withSpan(...)` `attributes` argument. */
    private fun isWithSpanAttributeMap(candidate: Any?): Boolean {
        val mapCall = candidate as? KtCallExpression ?: return false
        if (mapCall.calleeExpression?.text !in MAP_BUILDERS) return false
        val asArg = mapCall.parent as? KtValueArgument
        if (asArg != null) {
            val call = (asArg.parent as? KtValueArgumentList)?.parent as? KtCallExpression ?: return false
            return call.calleeExpression?.text == "withSpan" && namedOrPositional(call, "attributes", 1) == asArg
        }
        // Hoisted: `val attrs = mapOf(...)` + `withSpan("op", attrs)` in the same file.
        val property = mapCall.parent as? KtProperty ?: return false
        if (property.initializer != mapCall) return false
        val name = property.name ?: return false
        return mapCall.containingKtFile
            .collectDescendantsOfType<KtCallExpression> { it.calleeExpression?.text == "withSpan" }
            .any { call ->
                val attrs = namedOrPositional(call, "attributes", 1)?.getArgumentExpression()
                (attrs as? KtNameReferenceExpression)?.getReferencedName() == name
            }
    }

    /**
     * Raw JWT / OIDC claim evidence (any attribute key): a `.sub` / `sub` value, or `.subject`
     * read off a token payload (`payload.subject`, `decoded.subject`, `claims.subject`). Bare
     * `.subject` is NOT evidence — `email.subject` / `template.subject` are ordinary text.
     */
    private fun isRawClaim(value: KtExpression): Boolean {
        val e = peel(value)
        val name = e.terminalName() ?: return false
        if (name == "sub") return true
        return name == "subject" && e is KtQualifiedExpression && e.receiverExpression.terminalName() in CLAIM_RECEIVERS
    }

    /**
     * Raw-identifier evidence on a peeled value (spec V1–V4): UUID-shaped literal,
     * `UUID.randomUUID()` / `fromString` / `nameUUIDFromBytes`, a name declared `UUID` (or
     * initialized from V1/V2/V4) in the same file, or the domain naming convention
     * (`userId` / `*UserId` / `user.id`). Hashed values (`UserIdHasher.hash(...)`) match none.
     */
    private fun isRawIdentifier(value: KtExpression): Boolean {
        if (isUuidLiteralOrFactory(value)) return true
        val e = peel(value)
        return hasIdentifierName(e) || (e is KtNameReferenceExpression && isDeclaredUuid(e))
    }

    /** V1 / V2 only — deliberately non-recursive so V3's initializer check can't loop on `val a = b; val b = a`. */
    private fun isUuidLiteralOrFactory(value: KtExpression): Boolean =
        when (val e = peel(value)) {
            is KtStringTemplateExpression -> !e.hasInterpolation() && UUID_PATTERN.matches(e.text.removeSurrounding("\""))
            is KtQualifiedExpression -> isUuidFactoryCall(e)
            else -> false
        }

    /** Strips `(...)`, `!!`, a trailing no-arg `.toString()`, and a single-entry `"$x"` / `"${x}"` template. */
    private fun peel(value: KtExpression): KtExpression {
        var e = value
        while (true) {
            e =
                when {
                    e is KtParenthesizedExpression -> e.expression ?: return e
                    e is KtPostfixExpression && e.operationReference.text == "!!" -> e.baseExpression ?: return e
                    e is KtQualifiedExpression && e.selectorExpression.isNoArgCall("toString") -> e.receiverExpression
                    e is KtStringTemplateExpression && e.entries.size == 1 && e.entries[0].expression != null ->
                        e.entries[0].expression!!
                    else -> return e
                }
        }
    }

    private fun isUuidFactoryCall(e: KtQualifiedExpression): Boolean {
        val selector = e.selectorExpression as? KtCallExpression ?: return false
        return e.receiverExpression.text in UUID_TYPES && selector.calleeExpression?.text in UUID_FACTORIES
    }

    /**
     * V4 — `userId`, `*UserId`, or `<x>.id` where `x` is `user` / `*User`. A property / name
     * reference only: a call selector (`getUserId()`) is not a terminal name.
     */
    private fun hasIdentifierName(e: KtExpression): Boolean {
        val name = e.terminalName() ?: return false
        if (name == "userId" || name.endsWith("UserId")) return true
        if (name != "id" || e !is KtQualifiedExpression) return false
        val owner = peel(e.receiverExpression).terminalName() ?: return false
        return owner == "user" || owner.endsWith("User")
    }

    /**
     * V3 — a same-file parameter / property with this name is typed `UUID`, or initialized
     * from V1 / V2 / V4 (non-recursive — an initializer that is itself a bare name is not
     * chased). ponytail: resolves by name across the file, not by lexical scope; a same-name,
     * differently-typed declaration could over-match (bounded by the alias-key +
     * attribute-key-position preconditions).
     */
    private fun isDeclaredUuid(ref: KtNameReferenceExpression): Boolean =
        sameFileDeclarations(ref, ref.getReferencedName()).any { decl ->
            decl.typeReference?.text?.removeSuffix("?") in UUID_TYPES ||
                ((decl as? KtProperty)?.initializer?.let { isUuidLiteralOrFactory(it) || hasIdentifierName(peel(it)) } == true)
        }

    private fun sameFileDeclarations(
        anchor: KtElement,
        name: String,
    ): List<KtCallableDeclaration> = anchor.containingKtFile.collectDescendantsOfType { it.name == name }

    private fun namedOrPositional(
        call: KtCallExpression,
        name: String,
        index: Int,
    ): KtValueArgument? {
        val args = call.valueArguments
        args.firstOrNull { it.getArgumentName()?.asName?.asString() == name }?.let { return it }
        return args.getOrNull(index)?.takeIf { it.getArgumentName() == null }
    }

    private fun KtCallExpression.receiver(): KtExpression? =
        (parent as? KtQualifiedExpression)?.takeIf { it.selectorExpression == this }?.receiverExpression

    private fun KtExpression?.isNoArgCall(name: String): Boolean =
        this is KtCallExpression && calleeExpression?.text == name && valueArguments.isEmpty()

    private fun KtExpression.terminalName(): String? =
        when (this) {
            is KtNameReferenceExpression -> getReferencedName()
            is KtQualifiedExpression -> (selectorExpression as? KtNameReferenceExpression)?.getReferencedName()
            else -> null
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
         * Tier 1 Group A — `ForbiddenAttributeStripper.FORBIDDEN_KEYS` entries enforced
         * ANYWHERE (10 keys). The remaining entry, `"user_id"`, is enforced in
         * attribute-key position only — see [CONTEXT_RESTRICTED_KEYS].
         *
         * The synchronization-guard test in `OtelForbiddenAttributeLintTest` asserts
         * `TIER_1_GROUP_A + CONTEXT_RESTRICTED_KEYS ⊇ FORBIDDEN_KEYS` with zero carve-outs.
         * If `FORBIDDEN_KEYS` gains a new entry, add it to one of the two sets (and the
         * test's snapshot): here when the key has no non-OTel uses, otherwise to
         * [CONTEXT_RESTRICTED_KEYS].
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
                // User-id typo-defensive variants (`user_id` lives in CONTEXT_RESTRICTED_KEYS) — 2 keys.
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
         * `FORBIDDEN_KEYS` entries enforced in attribute-key position ONLY. `"user_id"` has
         * ~30 non-OTel production uses (SQL column, `@SerialName`, route param,
         * `buildJsonObject` key) that anywhere-matching would flag.
         */
        val CONTEXT_RESTRICTED_KEYS: Set<String> = setOf("user_id")

        /** Alias tokens: an attribute key carrying one fires only with a raw-identifier value. */
        private val USER_IDENTITY_ALIAS_TOKENS: Set<String> =
            setOf("user", "enduser", "principal", "actor", "subject", "owner", "account")

        /**
         * The one sanctioned location key — `display_location` (any separator / casing), the
         * HMAC-fuzzed post coordinate. EXACT key only: `display_lat` (could carry a raw viewer
         * coordinate) and `display_actual_location` still fire.
         */
        private val SANCTIONED_LOCATION_KEY: List<String> = listOf("display", "location")

        private val LOCATION_TOKENS: Set<String> =
            setOf(
                "location", "locations", "geolocation",
                "lat", "lats", "latitude", "latitudes",
                "lng", "lon", "longitude", "longitudes", "latlng", "latlon", "latlong",
                "coord", "coords", "coordinate", "coordinates",
                "geohash", "geom", "geometry", "geography", "wkt",
            )

        private val CREDENTIAL_TOKENS: Set<String> =
            setOf("password", "passwd", "secret", "secrets", "bearer", "authorization", "apikey", "cookie")

        private val CREDENTIAL_TOKEN_PAIRS: Set<Pair<String, String>> =
            setOf(
                "refresh" to "token",
                "access" to "token",
                "id" to "token",
                "api" to "key",
                "private" to "key",
                "service" to "role",
            )

        /** Receivers whose `.subject` is a raw JWT / OIDC claim (bare `.subject` is not). */
        private val CLAIM_RECEIVERS: Set<String> = setOf("payload", "decoded", "claims", "jwt")

        private val ATTRIBUTE_KEY_FACTORIES: Set<String> =
            setOf(
                "stringKey",
                "booleanKey",
                "longKey",
                "doubleKey",
                "stringArrayKey",
                "booleanArrayKey",
                "longArrayKey",
                "doubleArrayKey",
            )

        private val MAP_BUILDERS: Set<String> = setOf("mapOf", "mutableMapOf", "hashMapOf", "linkedMapOf")
        private val BUILDER_CHAIN_MARKERS: List<String> = listOf("Attributes.builder()", ".toBuilder()")
        private val ATTRIBUTES_BUILDER_TYPES: Set<String> =
            setOf("AttributesBuilder", "io.opentelemetry.api.common.AttributesBuilder")

        private val UUID_TYPES: Set<String> = setOf("UUID", "java.util.UUID")
        private val UUID_FACTORIES: Set<String> = setOf("randomUUID", "fromString", "nameUUIDFromBytes")
        private val UUID_PATTERN: Regex =
            Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""")

        private val CAMEL_BOUNDARY = Regex("([a-z0-9])([A-Z])")
        private val KEY_SEPARATORS = Regex("""[._\-\s]+""")

        /** `geo.userLat` → `[geo, user, lat]`: split on separators + camelCase, lowercase. */
        private fun tokenize(key: String): List<String> =
            key.replace(CAMEL_BOUNDARY, "$1 $2")
                .split(KEY_SEPARATORS)
                .filter { it.isNotEmpty() }
                .map { it.lowercase() }

        /**
         * Tier 2 — sensitive-value regex patterns (11). Each is a high-confidence marker:
         * a structural shape (PEM / JWT / JWKS / credentialed Redis URI) or a vendor prefix
         * plus a long token-alphabet body for the secrets this backend actually holds.
         * Prefix-less opaque secrets (project-issued refresh tokens, webhook / HMAC / AES
         * secrets) have no recognizable value shape — the attribute-key credential check
         * covers the keys that would carry them; the spec routes their values to the
         * runtime / sentinel / review layers.
         */
        private val TIER_2_PATTERNS: List<Regex> =
            listOf(
                // PEM private-key marker (RSA / EC / Ed25519 / PKCS#8): BEGIN <KIND> PRIVATE KEY.
                // The `[A-Z ]*` between BEGIN and PRIVATE KEY allows for "RSA " / "EC " /
                // "ED25519 " / "" (some PEM headers omit the key-type label). PUBLIC keys
                // pass — the pattern requires PRIVATE KEY explicitly.
                Regex("""-{5}BEGIN [A-Z ]*PRIVATE KEY-{5}"""),
                // JWT three-segment shape: base64url header + "." + base64url payload + "."
                // (signature segment present but not matched — the two-period anchor is
                // sufficient to identify a JWT). Both segments must start with `eyJ`
                // (base64url-encoded `{"...` JSON header start). The 10+ length floor on
                // each segment avoids matching coincidental two-period base64 fragments.
                Regex("""eyJ[A-Za-z0-9_\-]{10,}\.eyJ[A-Za-z0-9_\-]{10,}\."""),
                // Redis URI with embedded credentials: `redis://` or TLS `rediss://` (Upstash),
                // with or without a username (`redis://:<pw>@`). The `[^@/\s]+@` anchor needs an
                // `@` before any `/`, so `redis://host:6379/0` (no userinfo) does NOT match.
                Regex("""rediss?://[^:/@\s]*:[^@/\s]+@"""),
                // JWKS RSA-key JSON shape: "kty":"RSA" followed by "n":. The `\s*,?\s*`
                // between keys allows reordered JSON (`"kty":"RSA","n":` or with whitespace).
                // Specific enough to avoid false-positives on legitimate JSON-with-`kty`
                // in unrelated contexts.
                Regex(""""kty"\s*:\s*"RSA"\s*,?\s*"n"\s*:"""),
                // Vendor-prefixed opaque secrets (prefix + 20/24+ token chars, so bare prefixes
                // and prose don't match), covering the vendor secret formats in this stack's
                // footprint. Google OAuth client secret / access token / refresh token.
                Regex("""GOCSPX-[A-Za-z0-9_\-]{20,}"""),
                Regex("""ya29\.[A-Za-z0-9_\-]{20,}"""),
                Regex("""(?<![A-Za-z0-9/])1//[A-Za-z0-9_\-]{20,}"""),
                // Supabase secret API key (`supabase-service-role-key`, new key format).
                Regex("""sb_secret_[A-Za-z0-9_\-]{20,}"""),
                // Grafana Cloud access-policy token in raw form (the stored `otel-grafana-otlp-token`
                // slot is base64 Basic-auth-wrapped, so it is prefix-less — see the spec).
                Regex("""glc_[A-Za-z0-9+/=_\-]{20,}"""),
                // OpenAI API key (`openai-api-key`): typed prefix, or the `T3BlbkFJ` marker
                // (base64 "OpenAI") that legacy and project keys embed.
                Regex("""sk-(?:(?:proj|svcacct|admin)-[A-Za-z0-9_\-]{20,}|[A-Za-z0-9_\-]{8,}T3BlbkFJ[A-Za-z0-9_\-]{8,})"""),
                // RevenueCat secret API key (`revenuecat-secret-api-key`), Stripe-style shape.
                Regex("""(?<![A-Za-z0-9])sk_(?:live_|test_)?[A-Za-z0-9]{24,}"""),
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
