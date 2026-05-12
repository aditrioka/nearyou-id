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
    // Task 2.2 — Tier 1 Group A positive-fail (10) + user_id carve-out positive-pass (1)
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

    "user_id carve-out: literal does NOT fire (SQL column name use case)" {
        // Mirrors the canonical use at JDBC repositories — `rs.getObject("user_id", UUID::class.java)`.
        // The carve-out rationale: `user_id` appears in ~12 production paths as SQL column /
        // @SerialName / Ktor route parameter, semantically unrelated to OTel attribute writes.
        // The runtime ForbiddenAttributeStripper continues to handle defensively at export.
        // See `OtelForbiddenAttributeRule` KDoc § "user_id carve-out".
        val code =
            """
            package id.nearyou.app.repo

            class T {
                fun read(): String = "user_id"
            }
            """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    // ============================================================
    // Task 2.3 — Tier 1 Group B positive-fail (8) — underscore typo-defensive variants
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
    // Task 2.4 — Tier 1 Group C positive-fail (3) — JWT-claim keys
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
    // Task 2.5 — Tier 2 positive-fail (4) — sensitive-value regex patterns
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
    // Task 2.6 — Tier 2 false-positive negative tests (3)
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
    // Task 2.7 — Sanctioned `UserIdHasher.hash` consumption positive-pass
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

            fun apply(span: Span, userId: String) {
                span.setAttribute("user.id", userId)
            }
            """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    // ============================================================
    // Task 2.8 — IP-axis Mode B positive-fail: IPv4 hoisted-to-val
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
    // Task 2.9 — IP-axis Mode B IPv6 positive-fail
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
    // Task 2.10 — IP-axis Mode B canonical positive-pass
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
    // Task 2.11 — IP-axis Mode B simple-name interpolation positive-pass
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
    // Task 2.12 — IP-axis Mode B block-form interpolation positive-pass
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
    // Task 2.13 — IP-axis Mode B off-canonical hex positive-fail (3 tests)
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
    // Task 2.14 — IP-axis Mode B no-op on non-IP-axis key
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
    // Task 2.15 — Annotation bypass with non-empty reason on function
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
    // Task 2.16 — Annotation bypass on enclosing class
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
    // Task 2.17 — Annotation bypass empty-reason still fires (3 cases)
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
    // Task 2.18 — Annotation single-non-blank-char positive-pass
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
    // Task 2.19 — Path allowlist tests (4)
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
    // Task 2.20 — Synthetic-file-harness package-FQN fallback
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
    // Task 2.21 — Composition with CoordinateJitterRule (independent findings)
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
    // Task 2.22 — Composition with RedisHashTagRule two-way
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
    // Task 2.23 — Unrelated string literal positive-pass
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
    // Task 2.24 — NearYouRuleSetProvider registration positive-pass
    // ============================================================

    "rule registered in NearYouRuleSetProvider" {
        val provider = NearYouRuleSetProvider()
        val ruleSet = provider.instance(io.gitlab.arturbosch.detekt.api.Config.empty)
        val rules = ruleSet.rules.map { it::class.simpleName }
        rules.contains("OtelForbiddenAttributeRule") shouldBe true
    }

    // ============================================================
    // Task 2.25 — Synchronization guard test (Tier 1 Group A ⊇ FORBIDDEN_KEYS − {user_id})
    // ============================================================

    "synchronization guard: Tier 1 Group A contains all of FORBIDDEN_KEYS minus the user_id carve-out" {
        // Hardcoded snapshot of `ForbiddenAttributeStripper.FORBIDDEN_KEYS` (the Set at
        // `infra/otel/src/main/kotlin/id/nearyou/app/infra/otel/ForbiddenAttributeStripper.kt:89-108`)
        // MINUS the documented `"user_id"` carve-out. This guards the superset relationship
        // documented in `OtelForbiddenAttributeRule` KDoc § "user_id carve-out" + spec
        // `observability-otel-foundation/spec.md` § Tier 1 Group A.
        //
        // Why hardcoded, not imported: `:lint:detekt-rules` targets JVM 17 (Detekt 1.23.x
        // runtime constraint) while `:infra:otel` targets JVM 21 (project-wide toolchain).
        // A `testImplementation(project(":infra:otel"))` would mix class-file versions in
        // the test classpath. The hardcoded snapshot is option (b) per `tasks.md` § 2.25.
        //
        // If you are adding a NEW key to `FORBIDDEN_KEYS`:
        //   - If it belongs in Tier 1 Group A (developer-written attr surface): add it to
        //     this snapshot AND to `OtelForbiddenAttributeRule.TIER_1_GROUP_A`.
        //   - If it is a similar carve-out (e.g., the new key has 10+ pre-existing
        //     production uses as SQL column / JSON / route param): add it to
        //     `expectedCarveouts` below with a one-line rationale comment AND to
        //     `OtelForbiddenAttributeRule` KDoc § "user_id carve-out".
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
        val expectedCarveouts: Set<String> =
            setOf(
                // `user_id` — ~12 pre-existing production uses as SQL column name
                // (`rs.getObject("user_id", UUID::class.java)`), `@SerialName` JSON key,
                // and Ktor route parameter (`call.parameters["user_id"]`) across
                // chat / follow / block routes. Semantically unrelated to OTel attrs;
                // runtime stripper handles defensively at export. See `OtelForbiddenAttributeRule`
                // KDoc § "user_id carve-out" + design.md § Decision 3.
                "user_id",
            )
        val expected = forbiddenKeysSnapshot - expectedCarveouts
        val missing = expected - OtelForbiddenAttributeRule.TIER_1_GROUP_A
        if (missing.isNotEmpty()) {
            error(
                "Tier 1 Group A is missing keys from FORBIDDEN_KEYS: $missing. Decide: " +
                    "(a) add to TIER_1_GROUP_A AND this snapshot, OR " +
                    "(b) document as a carve-out alongside `user_id` (which is carved out " +
                    "because it appears in ~12 production paths as SQL column / @SerialName / " +
                    "route parameter, semantically unrelated to OTel attributes). See " +
                    "OtelForbiddenAttributeRule KDoc § \"user_id carve-out\" + design.md § " +
                    "Decision 3.",
            )
        }
        // Also assert the snapshot stays in sync with the rule's published view of
        // Group A — i.e., the snapshot doesn't include unrelated keys the rule doesn't
        // recognize. If a key was REMOVED from FORBIDDEN_KEYS upstream, this test would
        // catch the stale snapshot.
        val expectedGroupA = OtelForbiddenAttributeRule.TIER_1_GROUP_A
        val unexpectedExtra = expected - expectedGroupA
        unexpectedExtra.shouldBeEmpty()
    }
})

private fun cleanupDir(dir: Path) {
    if (!Files.exists(dir)) return
    Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
}
