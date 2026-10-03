package id.nearyou.lint.detekt

import io.gitlab.arturbosch.detekt.test.lint
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

class OtelForbiddenAttributeLintTest : StringSpec({

    val rule = OtelForbiddenAttributeRule()

    /**
     * Write a synthetic file under a controlled physical path so the rule's path-substring
     * allowlist sees the simulated location. `pathSegments` is appended under a temp root,
     * so `pathSegments = listOf("src", "test", "kotlin")` produces a file under
     * `<root>/src/test/kotlin/<name>.kt` — the `/src/test/` substring then matches the
     * allowlist.
     */
    fun writeKtFile(
        fileName: String,
        code: String,
        pathSegments: List<String> = emptyList(),
    ): Path {
        val root = Files.createTempDirectory("detekt-otel-attr-")
        val dir =
            if (pathSegments.isEmpty()) {
                root
            } else {
                val nested = pathSegments.fold(root) { acc, seg -> acc.resolve(seg) }
                Files.createDirectories(nested)
                nested
            }
        val path = dir.resolve(fileName)
        path.writeText(code)
        return path
    }

    // ============================================================
    // Item 1 — Tier 1 Group A positive-fail (10) + the user_id pair (non-AKP passes / AKP fires)
    // ============================================================

    "Tier 1 Group A: client.address literal fires" {
        val code =
            """
            package id.nearyou.app.feature

            class T {
                fun setAttr(): String = "client.address"
            }
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    "Tier 1 Group A: client.port literal fires" {
        val code =
            """
            package id.nearyou.app.feature

            val k = "client.port"
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    "Tier 1 Group A: http.client_ip literal fires" {
        val code =
            """
            package id.nearyou.app.feature

            val k = "http.client_ip"
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    "Tier 1 Group A: network.peer.address literal fires" {
        val code =
            """
            package id.nearyou.app.feature

            val k = "network.peer.address"
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    "Tier 1 Group A: network.peer.port literal fires" {
        val code =
            """
            package id.nearyou.app.feature

            val k = "network.peer.port"
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    "Tier 1 Group A: net.peer.ip literal fires" {
        val code =
            """
            package id.nearyou.app.feature

            val k = "net.peer.ip"
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    "Tier 1 Group A: net.peer.port literal fires" {
        val code =
            """
            package id.nearyou.app.feature

            val k = "net.peer.port"
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    "Tier 1 Group A: net.sock.peer.addr literal fires" {
        val code =
            """
            package id.nearyou.app.feature

            val k = "net.sock.peer.addr"
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    "Tier 1 Group A: user_uuid literal fires" {
        val code =
            """
            package id.nearyou.app.feature

            val k = "user_uuid"
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    "Tier 1 Group A: user.uuid literal fires" {
        val code =
            """
            package id.nearyou.app.feature

            val k = "user.uuid"
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    "user_id outside an attribute-key position does NOT fire (bare / SQL column literal)" {
        // `user_id` has ~30 non-OTel production uses (SQL column, @SerialName, route param,
        // buildJsonObject key); it is enforced in attribute-key position only.
        val code =
            """
            package id.nearyou.app.repo

            class T {
                fun read(): String = "user_id"
                fun row(rs: java.sql.ResultSet) = rs.getObject("user_id", java.util.UUID::class.java)
            }
            """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    "user_id as a setAttribute key DOES fire (attribute-key position)" {
        val code =
            """
            package id.nearyou.app.feature

            fun apply(span: Span, v: String) {
                span.setAttribute("user_id", v)
            }
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    // ============================================================
    // Item 2 — Tier 1 Group B positive-fail (8) — underscore typo-defensive variants
    // ============================================================

    "Tier 1 Group B: client_address (underscore variant) fires" {
        val code =
            """
            package id.nearyou.app.feature

            val k = "client_address"
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    "Tier 1 Group B: client_port (underscore variant) fires" {
        val code =
            """
            package id.nearyou.app.feature

            val k = "client_port"
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    "Tier 1 Group B: http_client_ip (underscore variant) fires" {
        val code =
            """
            package id.nearyou.app.feature

            val k = "http_client_ip"
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    "Tier 1 Group B: network_peer_address (underscore variant) fires" {
        val code =
            """
            package id.nearyou.app.feature

            val k = "network_peer_address"
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    "Tier 1 Group B: network_peer_port (underscore variant) fires" {
        val code =
            """
            package id.nearyou.app.feature

            val k = "network_peer_port"
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    "Tier 1 Group B: net_peer_ip (underscore variant) fires" {
        val code =
            """
            package id.nearyou.app.feature

            val k = "net_peer_ip"
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    "Tier 1 Group B: net_peer_port (underscore variant) fires" {
        val code =
            """
            package id.nearyou.app.feature

            val k = "net_peer_port"
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    "Tier 1 Group B: net_sock_peer_addr (underscore variant) fires" {
        val code =
            """
            package id.nearyou.app.feature

            val k = "net_sock_peer_addr"
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    // ============================================================
    // Item 3 — Tier 1 Group C positive-fail (3) — JWT-claim keys
    // ============================================================

    "Tier 1 Group C: jwt.sub literal fires" {
        val code =
            """
            package id.nearyou.app.auth

            val k = "jwt.sub"
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    "Tier 1 Group C: jwt.aud literal fires" {
        val code =
            """
            package id.nearyou.app.auth

            val k = "jwt.aud"
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    "Tier 1 Group C: jwt.iss literal fires" {
        val code =
            """
            package id.nearyou.app.auth

            val k = "jwt.iss"
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    // ============================================================
    // Item 5 (original 4 patterns) — Tier 2 positive-fail; the new patterns are data-driven below
    // ============================================================

    "Tier 2: PEM RSA private-key marker fires" {
        val code =
            """
            package id.nearyou.app.feature

            val pem = "-----BEGIN RSA PRIVATE KEY-----"
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    "Tier 2: JWT three-segment shape fires" {
        // Illustrative JWT shape: header.payload.signature with each segment base64url-encoded
        // and starting `eyJ...` (the prefix of base64url-encoded `{"...` JSON header).
        val code =
            """
            package id.nearyou.app.feature

            val jwt = "eyJhbGciOiJSUzI1NiI.eyJzdWIiOiJ4eHgi.signature"
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    "Tier 2: Redis URI with userinfo (password) fires" {
        val code =
            """
            package id.nearyou.app.feature

            val uri = "redis://default:my-redis-password@redis.example:6379/0"
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    "Tier 2: JWKS RSA-key JSON shape fires" {
        val code =
            """
            package id.nearyou.app.feature

            val jwks = ${'"'}${'"'}${'"'}{"kty":"RSA","n":"modulus","e":"AQAB"}${'"'}${'"'}${'"'}
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    // ============================================================
    // Item 6 (original 3 near-misses) — the new near-misses are data-driven below
    // ============================================================

    "Tier 2 negative: single-segment eyJfoo (not JWT-shaped) does NOT fire" {
        val code =
            """
            package id.nearyou.app.feature

            val s = "eyJfoo"
            """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    "Tier 2 negative: redis URI without userinfo does NOT fire" {
        val code =
            """
            package id.nearyou.app.feature

            val uri = "redis://host:6379/0"
            """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    "Tier 2 negative: PEM PUBLIC key marker (not PRIVATE) does NOT fire" {
        val code =
            """
            package id.nearyou.app.feature

            val pem = "-----BEGIN PUBLIC KEY-----"
            """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    // ============================================================
    // Item 7 — Sanctioned `UserIdHasher.hash` consumption positive-pass
    // ============================================================

    "sanctioned UserIdHasher.hash consumption: setAttribute(\"user.id\", hashed) does NOT fire" {
        // The literal `"user.id"` is NOT in any Tier 1 group; it is the SANCTIONED key
        // paired with `UserIdHasher.hash(...)` consumption per `AuthPlugin.kt:115`.
        // Tier 1 Group A catches typo variants `user_uuid` / `user.uuid`, never the
        // canonical `user.id`.
        val code =
            """
            package id.nearyou.app.auth

            class Span { fun setAttribute(k: String, v: String) {} }

            fun apply(span: Span, userId: java.util.UUID) {
                span.setAttribute("user.id", UserIdHasher.hash(userId))
            }
            """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    // ============================================================
    // Mode B (rate-limit-infrastructure spec) — IP-axis Mode B positive-fail: IPv4 hoisted-to-val
    // ============================================================

    "Mode B IP-axis: raw IPv4 in val-hoisted literal fires (canonical hoist shape)" {
        // This is the canonical "literal hoisted to val, never passed to tryAcquireByKey"
        // shape — the rule MUST fire regardless of call-site context (see design.md §
        // Decision 5).
        val code =
            """
            package id.nearyou.app.feature

            val k = "{scope:health}:{ip:1.2.3.4}"
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    // ============================================================
    // Mode B (rate-limit-infrastructure spec) — IP-axis Mode B IPv6 positive-fail
    // ============================================================

    "Mode B IP-axis: raw IPv6 literal fires" {
        val code =
            """
            package id.nearyou.app.feature

            val k = "{scope:health}:{ip:[2001:db8::1]}"
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    // ============================================================
    // Mode B (rate-limit-infrastructure spec) — IP-axis Mode B canonical positive-pass
    // ============================================================

    "Mode B IP-axis: canonical 16-hex lowercase passes" {
        val code =
            """
            package id.nearyou.app.feature

            val k = "{scope:health}:{ip:abcdef0123456789}"
            """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    // ============================================================
    // Mode B (rate-limit-infrastructure spec) — IP-axis Mode B simple-name interpolation positive-pass
    // ============================================================

    "Mode B IP-axis: simple-name template interpolation passes (canonical production shape)" {
        // Mirrors HealthRoutes.kt:167 — `val key = "{scope:health}:{ip:${'$'}hashedIp}"`.
        val code =
            """
            package id.nearyou.app.feature

            class T {
                fun k(hashedIp: String): String = "{scope:health}:{ip:${'$'}hashedIp}"
            }
            """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    // ============================================================
    // Mode B (rate-limit-infrastructure spec) — IP-axis Mode B block-form interpolation positive-pass
    // ============================================================

    "Mode B IP-axis: block-form template interpolation passes" {
        val code =
            """
            package id.nearyou.app.feature

            class IpHasher { companion object { fun hash(ip: String): String = "x" } }

            class T {
                fun k(clientIp: String): String =
                    "{scope:health}:{ip:${'$'}{IpHasher.hash(clientIp)}}"
            }
            """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    // ============================================================
    // Mode B (rate-limit-infrastructure spec) — IP-axis Mode B off-canonical hex positive-fail (3 tests)
    // ============================================================

    "Mode B IP-axis: 15-hex value (one short) fires" {
        val code =
            """
            package id.nearyou.app.feature

            val k = "{scope:health}:{ip:abcdef012345678}"
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    "Mode B IP-axis: 17-hex value (one over) fires" {
        val code =
            """
            package id.nearyou.app.feature

            val k = "{scope:health}:{ip:abcdef01234567890}"
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    "Mode B IP-axis: uppercase 16-hex value fires (canonical is lowercase)" {
        val code =
            """
            package id.nearyou.app.feature

            val k = "{scope:health}:{ip:ABCDEF0123456789}"
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    // ============================================================
    // Mode B (rate-limit-infrastructure spec) — IP-axis Mode B no-op on non-IP-axis key
    // ============================================================

    "Mode B IP-axis: non-IP-axis key (no {ip:...} segment) does NOT fire on IP-axis check" {
        // This literal IS structurally a malformed RedisHashTagRule case (block-form
        // interpolation breaks the strict regex), but the IP-axis check has no {ip:...}
        // segment to match, so OtelForbiddenAttributeRule does NOT fire.
        val code =
            """
            package id.nearyou.app.feature

            class T {
                fun k(userId: String): String = "{scope:rate_like_day}:{user:${'$'}userId}"
            }
            """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    // ============================================================
    // Item 9 — Annotation bypass with non-empty reason on function
    // ============================================================

    "annotation bypass: @AllowForbiddenSpanAttribute on function with non-empty reason suppresses" {
        val code =
            """
            package id.nearyou.app.admin

            annotation class AllowForbiddenSpanAttribute(val reason: String)

            class T {
                @AllowForbiddenSpanAttribute("admin span exempt — design Decision N")
                fun k(): String = "client.address"
            }
            """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    // ============================================================
    // Item 9 — Annotation bypass on enclosing class
    // ============================================================

    "annotation bypass: @AllowForbiddenSpanAttribute on enclosing class suppresses nested function" {
        val code =
            """
            package id.nearyou.app.admin

            annotation class AllowForbiddenSpanAttribute(val reason: String)

            @AllowForbiddenSpanAttribute("escape hatch for admin telemetry")
            class T {
                fun k(): String = "net.peer.ip"
            }
            """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    // ============================================================
    // Item 10 — Annotation bypass empty-reason still fires (3 cases)
    // ============================================================

    "annotation bypass: empty-string reason still fires (isNotBlank() rejection)" {
        val code =
            """
            package id.nearyou.app.feature

            annotation class AllowForbiddenSpanAttribute(val reason: String)

            class T {
                @AllowForbiddenSpanAttribute("")
                fun k(): String = "client.address"
            }
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    "annotation bypass: whitespace-only reason still fires (isNotBlank() rejection)" {
        val code =
            """
            package id.nearyou.app.feature

            annotation class AllowForbiddenSpanAttribute(val reason: String)

            class T {
                @AllowForbiddenSpanAttribute("   ")
                fun k(): String = "client.address"
            }
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    "annotation bypass: tab+newline reason still fires (isNotBlank() rejection)" {
        val code =
            """
            package id.nearyou.app.feature

            annotation class AllowForbiddenSpanAttribute(val reason: String)

            class T {
                @AllowForbiddenSpanAttribute("\t\n")
                fun k(): String = "client.address"
            }
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    // ============================================================
    // Item 9 — Annotation single-non-blank-char positive-pass
    // ============================================================

    "annotation bypass: single non-blank char reason passes (rule requires reason exists, not its quality)" {
        val code =
            """
            package id.nearyou.app.feature

            annotation class AllowForbiddenSpanAttribute(val reason: String)

            class T {
                @AllowForbiddenSpanAttribute("x")
                fun k(): String = "client.address"
            }
            """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    // ============================================================
    // Items 4/8 — Path allowlist tests (4)
    // ============================================================

    "path allowlist: file under /src/test/ does NOT fire on Tier 1 / Tier 2 / IP-axis literals" {
        // Pack all three tier patterns into one fixture so we lock the test-path allowlist
        // for the full enforcement surface at once.
        val code =
            """
            package id.nearyou.app.something

            val tier1 = "client.address"
            val tier2 = "-----BEGIN RSA PRIVATE KEY-----"
            val ipAxis = "{scope:health}:{ip:1.2.3.4}"
            """.trimIndent()
        val path = writeKtFile("LimiterFixture.kt", code, listOf("src", "test", "kotlin"))
        try {
            rule.lint(path).shouldBeEmpty()
        } finally {
            cleanupDir(path.parent.parent.parent.parent)
        }
    }

    "path allowlist: file under /infra/otel/src/main/ does NOT fire (rule's runtime sibling)" {
        val code =
            """
            package id.nearyou.app.infra.otel

            val keys = setOf("client.address", "net.peer.ip", "network.peer.address")
            """.trimIndent()
        val path = writeKtFile("ForbiddenAttributeStripper.kt", code, listOf("infra", "otel", "src", "main", "kotlin"))
        try {
            rule.lint(path).shouldBeEmpty()
        } finally {
            cleanupDir(path.parent.parent.parent.parent.parent.parent)
        }
    }

    "path allowlist: file under /lint/detekt-rules/src/main/ does NOT fire (rule itself enumerates)" {
        val code =
            """
            package id.nearyou.lint.detekt

            val patterns = listOf("client.address", "net.peer.ip")
            """.trimIndent()
        val path =
            writeKtFile(
                "Sample.kt",
                code,
                listOf("lint", "detekt-rules", "src", "main", "kotlin"),
            )
        try {
            rule.lint(path).shouldBeEmpty()
        } finally {
            cleanupDir(path.parent.parent.parent.parent.parent.parent)
        }
    }

    "path allowlist: file under /backend/ktor/src/main/ (non-allowlisted) DOES fire" {
        val code =
            """
            package id.nearyou.app.something

            val k = "client.address"
            """.trimIndent()
        val path = writeKtFile("Routes.kt", code, listOf("backend", "ktor", "src", "main", "kotlin"))
        try {
            rule.lint(path) shouldHaveSize 1
        } finally {
            cleanupDir(path.parent.parent.parent.parent.parent.parent)
        }
    }

    // ============================================================
    // Item 12 — Synthetic-file-harness package-FQN fallback
    // ============================================================

    "synthetic-file harness: package id.nearyou.lint.detekt.* treated as allowlisted" {
        // The synthetic `lint(String)` overload gives the file no real virtualFilePath
        // (it lands at "Test.kt"). The package-FQN fallback catches this — package
        // starting with `id.nearyou.lint.detekt.` is allowlisted (used by the rule's own
        // test fixtures that intentionally include forbidden patterns).
        val code =
            """
            package id.nearyou.lint.detekt.fixtures

            class Allowed {
                fun k(): String = "client.address"
            }
            """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    // ============================================================
    // Item 13 — Composition with CoordinateJitterRule (independent findings)
    // ============================================================

    "composition: fixture with actual_location + client.address fires exactly 1 finding per rule (no cross-suppression)" {
        val coordRule = CoordinateJitterRule()
        val code =
            """
            package id.nearyou.app.feature

            class T {
                fun coord(): String = "SELECT actual_location FROM posts"
                fun attr(): String = "client.address"
            }
            """.trimIndent()
        coordRule.lint(code) shouldHaveSize 1
        rule.lint(code) shouldHaveSize 1
    }

    // ============================================================
    // Item 13 — Composition with RedisHashTagRule two-way
    // ============================================================

    "composition with RedisHashTagRule: legacy non-hash-tagged key (no {ip:}) fires only RedisHashTagRule" {
        val hashRule = RedisHashTagRule()
        val code =
            """
            package id.nearyou.app.feature

            class T {
                fun k(userId: String): String = "rate:user:${'$'}userId"
            }
            """.trimIndent()
        hashRule.lint(code) shouldHaveSize 1
        rule.lint(code).shouldBeEmpty()
    }

    "composition with RedisHashTagRule: legacy prefix AND raw IP fires BOTH rules independently" {
        val hashRule = RedisHashTagRule()
        val code =
            """
            package id.nearyou.app.feature

            class T {
                fun k(): String = "rate:health:{ip:1.2.3.4}"
            }
            """.trimIndent()
        hashRule.lint(code) shouldHaveSize 1
        rule.lint(code) shouldHaveSize 1
    }

    // ============================================================
    // Item 14 — Unrelated string literal positive-pass
    // ============================================================

    "unrelated literal: \"Processing request\" does NOT fire" {
        val code =
            """
            package id.nearyou.app.feature

            val msg = "Processing request"
            """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    "unrelated literal: SQL INSERT statement does NOT fire" {
        val code =
            """
            package id.nearyou.app.feature

            val q = "INSERT INTO posts (id, content) VALUES (?, ?)"
            """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    "unrelated literal: SQL SELECT with parameterized WHERE does NOT fire" {
        val code =
            """
            package id.nearyou.app.feature

            val q = "SELECT * FROM users WHERE id = ?"
            """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    // ============================================================
    // Item 15 — NearYouRuleSetProvider registration positive-pass
    // ============================================================

    "rule registered in NearYouRuleSetProvider" {
        val provider = NearYouRuleSetProvider()
        val ruleSet = provider.instance(io.gitlab.arturbosch.detekt.api.Config.empty)
        val rules = ruleSet.rules.map { it::class.simpleName }
        rules.contains("OtelForbiddenAttributeRule") shouldBe true
    }

    // ============================================================
    // Item 11 — Synchronization guard (Group A ∪ CONTEXT_RESTRICTED_KEYS ⊇ FORBIDDEN_KEYS, zero carve-outs)
    // ============================================================

    "synchronization guard: Group A + attribute-key-position keys cover FORBIDDEN_KEYS with zero carve-outs" {
        // Hardcoded snapshot of `ForbiddenAttributeStripper.FORBIDDEN_KEYS` (the Set in
        // `infra/otel/src/main/kotlin/id/nearyou/app/infra/otel/ForbiddenAttributeStripper.kt`).
        // Every entry must be enforced by one of the rule's two key modes — anywhere
        // (`TIER_1_GROUP_A`) or attribute-key position (`CONTEXT_RESTRICTED_KEYS`). Spec
        // `observability-otel-foundation` § "Detekt test coverage" item 11.
        //
        // Why hardcoded, not imported: `:lint:detekt-rules` targets JVM 17 (Detekt 1.23.x
        // runtime constraint) while `:infra:otel` targets JVM 21 (project-wide toolchain).
        // A `testImplementation(project(":infra:otel"))` would mix class-file versions in
        // the test classpath. The hardcoded snapshot is option (b) per `tasks.md` § 2.25.
        //
        // If you are adding a NEW key to `FORBIDDEN_KEYS`, add it to this snapshot AND to
        // either `TIER_1_GROUP_A` (no non-OTel uses) or `CONTEXT_RESTRICTED_KEYS` (the key
        // also appears as SQL column / JSON / route param, like `user_id`).
        val forbiddenKeysSnapshot: Set<String> =
            setOf(
                "client.address",
                "client.port",
                "http.client_ip",
                "network.peer.address",
                "network.peer.port",
                "net.peer.ip",
                "net.peer.port",
                "net.sock.peer.addr",
                "user_id",
                "user_uuid",
                "user.uuid",
            )
        val enforced = OtelForbiddenAttributeRule.TIER_1_GROUP_A + OtelForbiddenAttributeRule.CONTEXT_RESTRICTED_KEYS
        val missing = forbiddenKeysSnapshot - enforced
        if (missing.isNotEmpty()) {
            error(
                "FORBIDDEN_KEYS entries enforced by neither key mode: $missing. Add each to " +
                    "TIER_1_GROUP_A (enforced anywhere) or CONTEXT_RESTRICTED_KEYS (enforced in " +
                    "attribute-key position only — for keys that also appear as SQL column / " +
                    "@SerialName / route param, like user_id). There are no carve-outs.",
            )
        }
        // The two modes don't overlap, so every enforced key is reachable from the snapshot —
        // a key REMOVED upstream from FORBIDDEN_KEYS surfaces here as a stale rule entry.
        (OtelForbiddenAttributeRule.TIER_1_GROUP_A intersect OtelForbiddenAttributeRule.CONTEXT_RESTRICTED_KEYS).shouldBeEmpty()
        (enforced - forbiddenKeysSnapshot).shouldBeEmpty()
    }

    // ============================================================
    // otel-attribute-rule-spec-parity — spec § "Detekt test coverage" items 5/6/8/9/13/16–21
    // ============================================================

    /** Non-allowlisted synthetic fixture: [body] under `package id.nearyou.app.feature`. */
    fun fixture(body: String): String = "package id.nearyou.app.feature\n\n$body\n"

    // Item 5 — Tier 2 positive-fail. Secret-shaped values are assembled at RUNTIME and
    // interpolated into the fixture source, so this test file never contains a token a
    // secret scanner (GitHub push protection) would match, while the rule still sees ONE
    // complete literal. A `"prefix" + "body"` concatenation INSIDE the fixture would be two
    // short literals and silently never fire — don't do that.
    val tokenBody = "a1B2c3D4".repeat(5) // 40 token-alphabet chars
    listOf(
        "PEM label-less PKCS#8 marker" to "-----BEGIN " + "PRIVATE KEY-----",
        "rediss:// with user + password" to "rediss://" + "default:" + "pw" + tokenBody.take(10) + "@cache.example:6379",
        "redis:// user-less password form" to "redis://" + ":" + "pw" + tokenBody.take(10) + "@cache.example:6379",
        "Google OAuth client secret" to "GOCSPX-" + tokenBody.take(28),
        "Google access token" to "ya29." + tokenBody,
        "Google metadata-server access token (ya29.c.)" to "ya29." + "c." + tokenBody,
        "postgresql:// with user + password" to "postgresql://" + "app:" + "pw" + tokenBody.take(10) + "@db.example:5432/nearyou",
        "Google refresh token" to "1//0g" + tokenBody,
        "Supabase secret key" to "sb_secret_" + tokenBody.take(32),
        "Grafana Cloud token" to "glc_" + tokenBody,
        "OpenAI project key" to "sk-proj-" + tokenBody,
        "OpenAI key with T3BlbkFJ marker" to "sk-" + tokenBody.take(20) + "T3BlbkFJ" + tokenBody.take(20),
        "RevenueCat secret key" to "sk_" + tokenBody.take(32),
    ).forEach { (label, secret) ->
        "Tier 2: $label fires" {
            rule.lint(fixture("val s = \"$secret\"")) shouldHaveSize 1
        }
    }

    // Item 6 — Tier 2 near-misses (bare / short prefixes, look-alike words, no-userinfo URIs).
    listOf(
        "GOCSPX-",
        "ya29.short",
        "sb_secret_",
        "https://host/1//",
        // `/`-preceded `1//` exercises the lookbehind
        "https://host/1//" + tokenBody,
        "risk_assessment_threshold_value_x",
        // An alnum-preceded `sk_` + full body exercises the `sk_` lookbehind.
        "disk_" + tokenBody.take(32),
        "jdbc:postgresql://db.internal:5432/nearyou",
        "re_threshold",
        "sk-learn",
        "sk_" + "short",
        "rediss://host:6380/0",
    ).forEach { nearMiss ->
        "Tier 2 near-miss: \"$nearMiss\" does NOT fire" {
            rule.lint(fixture("val s = \"$nearMiss\"")).shouldBeEmpty()
        }
    }

    // Item 16 — attribute-key-position shape matrix: "user_id" fires in every AKP.
    listOf(
        "P1 setAttribute named key" to """fun f(span: Span, v: String) { span.setAttribute(key = "user_id", value = v) }""",
        "P1 setAttribute positional key + named value" to """fun f(span: Span, v: String) { span.setAttribute("user_id", value = v) }""",
        "P2 AttributeKey.stringKey" to """val k = AttributeKey.stringKey("user_id")""",
        "P2 unqualified stringKey (static import)" to """val k = stringKey("user_id")""",
        "P3 fluent Attributes.builder().put" to """val a = Attributes.builder().put("user_id", "x").build()""",
        "P3 toBuilder().put" to """fun f(base: Attributes) = base.toBuilder().put("user_id", "x").build()""",
        "P3 AttributesBuilder-typed variable" to """fun f(b: AttributesBuilder) { b.put("user_id", "x") }""",
        "P3 Attributes.builder()-initialized variable" to "fun f() {\n    val b = Attributes.builder()\n    b.put(\"user_id\", \"x\")\n}",
        "P4 withSpan positional mapOf" to """fun f(v: String) = withSpan("op", mapOf("user_id" to v)) { }""",
        "P4 withSpan named attributes" to """fun f(v: String) = withSpan(name = "op", attributes = mapOf("user_id" to v)) { }""",
        "P4 withSpan Pair entry" to """fun f(v: String) = withSpan("op", mapOf(Pair("user_id", v))) { }""",
        "P4 withSpan mutableMapOf" to """fun f(v: String) = withSpan("op", mutableMapOf("user_id" to v)) { }""",
        "P4 withSpan hashMapOf" to """fun f(v: String) = withSpan("op", hashMapOf("user_id" to v)) { }""",
        "P4 withSpan linkedMapOf" to """fun f(v: String) = withSpan("op", linkedMapOf("user_id" to v)) { }""",
        "P4 hoisted same-file val" to "fun f(v: String) {\n    val attrs = mapOf(\"user_id\" to v)\n    withSpan(\"op\", attrs) { }\n}",
        "hoisted key const used as setAttribute key" to
            "const val K = \"user_id\"\nfun f(span: Span, v: String) { span.setAttribute(K, v) }",
        "hoisted key const used via AttributeKey" to "private const val K = \"user_id\"\nval k = AttributeKey.stringKey(K)",
        "hoisted key const via a qualified selector" to
            "object Keys { const val K = \"user_id\" }\nfun f(span: Span, v: String) { span.setAttribute(Keys.K, v) }",
        "uppercase USER_ID key" to """fun f(span: Span, v: String) { span.setAttribute("USER_ID", v) }""",
    ).forEach { (label, body) ->
        "AKP $label: \"user_id\" fires" {
            rule.lint(fixture(body)) shouldHaveSize 1
        }
    }

    // Item 17 — "user_id" outside any AKP: the real production shapes stay silent.
    listOf(
        "@SerialName" to
            """@kotlinx.serialization.Serializable data class D(@kotlinx.serialization.SerialName("user_id") val userId: String)""",
        "route parameter" to """fun f(call: ApplicationCall) = call.parameters["user_id"]""",
        "buildJsonObject put" to """val o = buildJsonObject { put("user_id", JsonPrimitive("x")) }""",
        "MutableMap.put" to """fun f(id: String) { mutableMapOf<String, Any>().put("user_id", id) }""",
        "Ktor call.attributes.put (not builder-evidenced)" to
            """fun f(call: ApplicationCall, id: String) { call.attributes.put("user_id", id) }""",
        "unqualified attributes.put (not builder-evidenced)" to """fun f(id: String) { attributes.put("user_id", id) }""",
        "mapOf passed to a non-withSpan call" to """fun f(call: C, id: String) = call.respond(mapOf("user_id" to id))""",
        "P2 factory on a non-AttributeKey receiver" to """val k = prefs.stringKey("user_id")""",
        "mapOf not passed to withSpan" to
            "fun f(id: String) {\n    val body = mapOf(\"user_id\" to id)\n    respond(body)\n    withSpan(\"op\", other) { }\n}",
        "key const used only in SQL" to "const val COL = \"user_id\"\nval sql = \"SELECT \" + COL + \" FROM t\"",
        "production AttributesBuilder.put with safe keys" to
            "fun f(code: String) {\n    val attrsBuilder = Attributes.builder()\n    attrsBuilder.put(\"error_code\", code)\n" +
            "    val e = Attributes.builder().put(\"event\", \"x\").put(\"error.type\", \"y\")\n}",
    ).forEach { (label, body) ->
        "non-AKP $label does NOT fire" {
            rule.lint(fixture(body)).shouldBeEmpty()
        }
    }

    // Item 18 — user-identity aliases fire only with a raw-identifier value (V1–V4).
    listOf(
        "V1 UUID literal on owner" to """fun f(span: Span) { span.setAttribute("owner", "550e8400-e29b-41d4-a716-446655440000") }""",
        "V2 UUID.randomUUID on principal" to """fun f(span: Span) { span.setAttribute("principal", UUID.randomUUID().toString()) }""",
        "V2 UUID.fromString on actor" to
            """fun f(span: Span, raw: String) { span.setAttribute("actor", UUID.fromString(raw).toString()) }""",
        "V2 java.util.UUID.nameUUIDFromBytes on owner" to
            """fun f(span: Span, b: ByteArray) { span.setAttribute("owner", java.util.UUID.nameUUIDFromBytes(b).toString()) }""",
        "V3 UUID-typed parameter on subject" to """fun f(span: Span, id: UUID) { span.setAttribute("subject", id.toString()) }""",
        "V3 nullable UUID property via template" to
            "class T(private val ref: java.util.UUID?) {\n    fun f(span: Span) { span.setAttribute(\"owner\", \"${'$'}ref\") }\n}",
        "V3 V2-initialized local" to
            "fun f(span: Span) {\n    val ownerRef = UUID.randomUUID()\n    span.setAttribute(\"owner\", ownerRef.toString())\n}",
        "V3 V4-initialized local" to
            "fun f(span: Span, principal: P) {\n    val viewer = principal.userId\n" +
            "    span.setAttribute(\"principal\", viewer.toString())\n}",
        "V4 *UserId on principal" to
            """fun f(span: Span, call: C) { span.setAttribute("principal", call.principal.targetUserId.toString()) }""",
        "V4 user.id on user.id key" to """fun f(span: Span, user: U) { span.setAttribute("user.id", user.id.toString()) }""",
        "V4 userId on user.id key (spec code-review-blocker shape)" to
            """fun f(span: Span, userId: String) { span.setAttribute("user.id", userId.toString()) }""",
        "V4 !! + currentUser.id on enduser.id" to
            """fun f(span: Span, currentUser: U?) { span.setAttribute("enduser.id", currentUser!!.id.toString()) }""",
        "V4 userId in withSpan map on account key" to """fun f(userId: String) = withSpan("op", mapOf("account" to userId)) { }""",
        "elvis-peeled *UserId on principal" to
            """fun f(span: Span, principal: P?) { span.setAttribute("principal", principal?.userId?.toString() ?: "anon") }""",
        "V2 kotlin Uuid.random on owner" to """fun f(span: Span) { span.setAttribute("owner", Uuid.random().toString()) }""",
        "V2 kotlin.uuid.Uuid.parse on subject" to
            """fun f(span: Span, raw: String) { span.setAttribute("subject", kotlin.uuid.Uuid.parse(raw).toString()) }""",
        "V3 kotlin Uuid-typed parameter on owner" to """fun f(span: Span, ref: Uuid) { span.setAttribute("owner", ref.toString()) }""",
        "V2 toKotlinUuid-peeled factory on actor" to
            """fun f(span: Span) { span.setAttribute("actor", UUID.randomUUID().toKotlinUuid().toString()) }""",
        "V2 UuidV7.next + toJavaUuid on actor" to
            """fun f(span: Span) { span.setAttribute("actor", UuidV7.next().toJavaUuid().toString()) }""",
        "V1 triple-quoted UUID literal on owner" to
            "fun f(span: Span) { span.setAttribute(\"owner\", \"\"\"550e8400-e29b-41d4-a716-446655440000\"\"\") }",
        "P2 nested in setAttribute takes the sibling value" to
            """fun f(span: Span, userId: String) { span.setAttribute(AttributeKey.stringKey("principal"), userId) }""",
        "P2 inside Attributes.of takes the paired value" to
            """fun f(userId: String) = Attributes.of(AttributeKey.stringKey("actor"), userId)""",
    ).forEach { (label, body) ->
        "alias $label fires" {
            rule.lint(fixture(body)) shouldHaveSize 1
        }
    }
    listOf(
        "role string on principal" to """fun f(span: Span) { span.setAttribute("principal", "system") }""",
        "String-typed non-convention name on actor" to
            """fun f(span: Span, actorUsername: String) { span.setAttribute("actor", actorUsername) }""",
        "ServiceAccountIdHasher.hash on service.account.id" to
            """fun f(span: Span, claims: C) { span.setAttribute("service.account.id", ServiceAccountIdHasher.hash(claims.sub)) }""",
        "UUID on a non-alias key (conversation_id)" to
            """fun f(id: UUID) = withSpan("chat.realtime.publish", mapOf("conversation_id" to id.toString())) { }""",
        "user_agent.original with UA text" to """fun f(span: Span, ua: String) { span.setAttribute("user_agent.original", ua) }""",
        "email.subject with text" to """fun f(span: Span, mail: M) { span.setAttribute("email.subject", mail.subject) }""",
        "call selector is not a V4 terminal name" to """fun f(span: Span, p: P) { span.setAttribute("principal", p.getUserId()) }""",
        // Documented limit: a hoisted `AttributeKey` val has no paired value in reach.
        "hoisted AttributeKey val used with a raw id (documented limit)" to
            "val K = AttributeKey.stringKey(\"principal\")\nfun f(span: Span, userId: String) { span.setAttribute(K, userId) }",
        "already-hashed local named *UserId" to
            "fun f(span: Span, id: UUID) {\n    val hashedUserId = UserIdHasher.hash(id)\n" +
            "    span.setAttribute(\"user.id\", hashedUserId)\n}",
        "same-name FUNCTION returning UUID is not V3 evidence" to
            "fun ownerRef(): UUID = UUID.randomUUID()\nfun f(span: Span, ownerRef: String) { span.setAttribute(\"owner\", ownerRef) }",
    ).forEach { (label, body) ->
        "alias $label does NOT fire" {
            rule.lint(fixture(body)).shouldBeEmpty()
        }
    }

    // Item 19 — raw JWT / OIDC claim value under ANY key (spec check 5).
    listOf(
        "claims.sub on service.account.id" to """fun f(span: Span, claims: C) { span.setAttribute("service.account.id", claims.sub) }""",
        "claims.sub in withSpan map on actor" to """fun f(claims: C) = withSpan("op", mapOf("actor" to claims.sub)) { }""",
        "payload.subject on a neutral key" to
            """fun f(span: Span, credential: Cr) { span.setAttribute("auth.identity", credential.payload.subject) }""",
        "decoded.subject on a neutral key" to """fun f(span: Span, decoded: D) { span.setAttribute("oidc.caller", decoded.subject) }""",
        "claims.sub on a neutral key" to """fun f(span: Span, claims: C) { span.setAttribute("auth.identity", claims.sub) }""",
        "!!-peeled decoded!!.subject" to """fun f(span: Span, decoded: D?) { span.setAttribute("oidc.caller", decoded!!.subject) }""",
    ).forEach { (label, body) ->
        "raw claim $label fires" {
            rule.lint(fixture(body)) shouldHaveSize 1
        }
    }

    // Item 20 — location key tokens; only the exact `display_location` key is sanctioned.
    listOf(
        "geo.lat", "actual_location", "userCoords", "latitude", "lng", "coordinates", "geohash", "userGPSLocation",
        "display_lat", "display_actual_location",
    ).forEach { key ->
        "location key \"$key\" fires" {
            rule.lint(fixture("""fun f(span: Span, v: String) { span.setAttribute("$key", v) }""")) shouldHaveSize 1
        }
    }
    listOf("display_location", "displayLocation", "latency_ms", "cloud.platform", "memory.allocation", "coordinator")
        .forEach { key ->
            "location look-alike / sanctioned key \"$key\" does NOT fire" {
                rule.lint(fixture("""fun f(span: Span, v: String) { span.setAttribute("$key", v) }""")).shouldBeEmpty()
            }
        }
    "location words outside an attribute-key position do NOT fire" {
        val body =
            "val q = \"SELECT ST_Y(display_location::geometry) AS lat FROM visible_posts\"\n" +
                "fun f(call: ApplicationCall) = call.parameters[\"latitude\"]\n" +
                "val export = mapOf(\"actual_lat\" to 1.0)"
        rule.lint(fixture(body)).shouldBeEmpty()
    }

    // Item 21 — credential key tokens.
    listOf(
        "client_secret", "http.request.header.authorization", "refreshToken", "db.password", "IDToken", "JWTSecret",
        "supabase.service_role_key", "api_key", "http.request.header.cookie", "id_token", "private_key",
    ).forEach { key ->
        "credential key \"$key\" fires" {
            rule.lint(fixture("""fun f(span: Span, v: String) { span.setAttribute("$key", v) }""")) shouldHaveSize 1
        }
    }
    listOf("gen_ai.usage.input_tokens", "credential.type").forEach { key ->
        "credential look-alike key \"$key\" does NOT fire" {
            rule.lint(fixture("""fun f(span: Span, v: String) { span.setAttribute("$key", v) }""")).shouldBeEmpty()
        }
    }

    // Item 22 — one literal, several matching checks → exactly one finding.
    "a key matching credential AND location checks reports once" {
        rule.lint(fixture("""fun f(span: Span, v: String) { span.setAttribute("secret_location", v) }""")) shouldHaveSize 1
    }

    // Item 13 — composition with CoordinateJitterRule on an attribute key.
    "composition: setAttribute(\"actual_location\", v) → 1 Otel finding + 1 CoordinateJitterRule finding" {
        val code = fixture("""fun f(span: Span, v: String) { span.setAttribute("actual_location", v) }""")
        rule.lint(code) shouldHaveSize 1
        CoordinateJitterRule().lint(code) shouldHaveSize 1
    }

    // Location + Tier 1 keys through the P4 `withSpan` map position (spec scenarios).
    "location key in a withSpan map fires" {
        rule.lint(fixture("""fun f(c: String) = withSpan("op", mapOf("userCoords" to c)) { }""")) shouldHaveSize 1
    }
    "Tier 1 key in a withSpan map fires once (anywhere + attribute-key checks, single report)" {
        val body = """fun f(clientAddr: String) = withSpan("foo", mapOf("network.peer.address" to clientAddr)) { }"""
        rule.lint(fixture(body)) shouldHaveSize 1
    }

    // Item 23 — spec check 6: raw client IP value under any key; the hashed form passes.
    "raw call.clientIp value fires under a neutral key" {
        val body = """fun f(span: Span, call: ApplicationCall) { span.setAttribute("net.client", call.clientIp) }"""
        rule.lint(fixture(body)) shouldHaveSize 1
    }
    "IpHasher.hash(call.clientIp) passes" {
        val body = """fun f(span: Span, call: ApplicationCall) { span.setAttribute("client.hash", IpHasher.hash(call.clientIp)) }"""
        rule.lint(fixture(body)).shouldBeEmpty()
    }

    // Items 18/24 — hoisted key constants: EVERY use is value-checked; an annotated use site is sanctioned.
    "hoisted alias key fires when a LATER use carries a raw identifier" {
        val body =
            "const val K = \"principal\"\n" +
                "fun a(span: Span) { span.setAttribute(K, \"system\") }\n" +
                "fun b(span: Span, id: UUID) { span.setAttribute(K, id.toString()) }"
        rule.lint(fixture(body)) shouldHaveSize 1
    }
    "hoisted user_id key used only inside an annotated function does NOT fire" {
        val body =
            "annotation class AllowForbiddenSpanAttribute(val reason: String)\n" +
                "const val K = \"user_id\"\n" +
                "@AllowForbiddenSpanAttribute(\"legacy trace\")\n" +
                "fun f(span: Span, v: String) { span.setAttribute(K, v) }"
        rule.lint(fixture(body)).shouldBeEmpty()
    }
    "hoisted user_id key still fires when another use is NOT annotated" {
        val body =
            "annotation class AllowForbiddenSpanAttribute(val reason: String)\n" +
                "const val K = \"user_id\"\n" +
                "@AllowForbiddenSpanAttribute(\"legacy trace\")\n" +
                "fun f(span: Span, v: String) { span.setAttribute(K, v) }\n" +
                "fun g(span: Span, v: String) { span.setAttribute(K, v) }"
        rule.lint(fixture(body)) shouldHaveSize 1
    }

    // Items 8/9 — allowlisted paths + annotation bypass also suppress attribute-key findings.
    listOf(
        listOf("src", "test", "kotlin"),
        listOf("infra", "otel", "src", "main", "kotlin"),
        listOf("lint", "detekt-rules", "src", "main", "kotlin"),
    ).forEach { segments ->
        "path allowlist /${segments.dropLast(1).joinToString("/")}/ suppresses an attribute-key finding" {
            val code =
                """
                package id.nearyou.app.something

                fun f(span: Span, v: String) { span.setAttribute("user_id", v) }
                """.trimIndent()
            val path = writeKtFile("AkpFixture.kt", code, segments)
            try {
                rule.lint(path).shouldBeEmpty()
            } finally {
                var root = path
                repeat(segments.size + 1) { root = root.parent }
                cleanupDir(root)
            }
        }
    }
    "annotation bypass suppresses an attribute-key finding" {
        val body =
            "annotation class AllowForbiddenSpanAttribute(val reason: String)\n" +
                "@AllowForbiddenSpanAttribute(\"legacy admin trace\")\n" +
                "fun f(span: Span, v: String) { span.setAttribute(\"user_id\", v) }"
        rule.lint(fixture(body)).shouldBeEmpty()
    }
})

private fun cleanupDir(dir: Path) {
    if (!Files.exists(dir)) return
    Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
}
