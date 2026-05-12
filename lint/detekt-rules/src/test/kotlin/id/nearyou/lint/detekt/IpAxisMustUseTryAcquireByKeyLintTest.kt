package id.nearyou.lint.detekt

import io.gitlab.arturbosch.detekt.test.lint
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

class IpAxisMustUseTryAcquireByKeyLintTest : StringSpec({

    val rule = IpAxisMustUseTryAcquireByKeyRule()

    /**
     * Write a synthetic file under a controlled physical path so the rule's
     * `/src/test/` path-substring allowlist sees the simulated location.
     * `pathSegments` is appended under a temp root, so
     * `pathSegments = listOf("src", "test", "kotlin")` produces a file under
     * `<root>/src/test/kotlin/<name>.kt` — the `/src/test/` substring then matches.
     * Mirrors the `OtelForbiddenAttributeLintTest` helper.
     */
    fun writeKtFile(
        fileName: String,
        code: String,
        pathSegments: List<String> = emptyList(),
    ): Path {
        val root = Files.createTempDirectory("detekt-ip-axis-method-")
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
    // Scenario 1 — Positive — literal IP-axis key
    // ============================================================

    "positive: tryAcquire with literal IP-axis key fires + message recommends tryAcquireByKey" {
        val code =
            """
            package id.nearyou.app.feature

            import java.time.Duration
            import java.util.UUID

            class T {
                fun fire(userId: UUID, ttl: Duration) {
                    RateLimiter.tryAcquire(userId, "{scope:health}:{ip:abc123def4567890}", 10, ttl)
                }
            }
            """.trimIndent()
        val findings = rule.lint(code)
        findings shouldHaveSize 1
        findings[0].message shouldContain "tryAcquireByKey"
    }

    // ============================================================
    // Scenario 2 — Positive — IP-derived UUID (the regression vector)
    // ============================================================

    "positive: tryAcquire with IP-derived UUID + IP-axis key fires" {
        // The regression vector that motivates this change: the IP value is properly
        // hashed (passes OtelForbiddenAttributeRule), the userId UUID is derived from
        // the IP rather than the literal sentinel (passes the structured-log scenario),
        // but the call still bypasses the architectural intent.
        val code =
            """
            package id.nearyou.app.feature

            import java.time.Duration
            import java.util.UUID

            class T {
                fun fire(ip: ByteArray, ttl: Duration) {
                    RateLimiter.tryAcquire(
                        UUID.nameUUIDFromBytes(ip),
                        "{scope:foo}:{ip:abc123def4567890}",
                        10,
                        ttl,
                    )
                }
            }
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    // ============================================================
    // Scenario 3 — Positive — literal sentinel UUID + IP-axis key
    // ============================================================

    "positive: tryAcquire with literal sentinel UUID + IP-axis key fires" {
        // The compile-time complement to the runtime-only structured-log scenario:
        // the rule fires mechanically without inspecting the userId value — the gate
        // is the key-argument shape alone.
        val code =
            """
            package id.nearyou.app.feature

            import java.time.Duration
            import java.util.UUID

            class T {
                fun fire(ttl: Duration) {
                    RateLimiter.tryAcquire(
                        UUID.fromString("00000000-0000-0000-0000-000000000000"),
                        "{scope:foo}:{ip:abc123def4567890}",
                        10,
                        ttl,
                    )
                }
            }
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    // ============================================================
    // Scenario 4 — Positive — template-string IP value (canonical production shape)
    // ============================================================

    "positive: tryAcquire with template-string IP value fires" {
        // The canonical production value shape — `{ip:$hashedIp}` simple-name template.
        // The rule is method-choice, not value-shape: the `{ip:` axis-key shape is
        // statically present in the literal regardless of the value's runtime resolution.
        val code =
            """
            package id.nearyou.app.feature

            import java.time.Duration
            import java.util.UUID

            class T {
                fun fire(userId: UUID, hashedIp: String, ttl: Duration) {
                    RateLimiter.tryAcquire(userId, "{scope:foo}:{ip:${'$'}hashedIp}", 10, ttl)
                }
            }
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    // ============================================================
    // Scenario 5 — Positive — named-argument syntax
    // ============================================================

    "positive: tryAcquire with named-argument IP-axis key fires" {
        // The named-argument fallback resolves `key = ...` identically to the positional
        // form — regression locks the dual-resolution path.
        val code =
            """
            package id.nearyou.app.feature

            import java.time.Duration
            import java.util.UUID

            class T {
                fun fire(u: UUID, t: Duration) {
                    RateLimiter.tryAcquire(
                        userId = u,
                        key = "{scope:foo}:{ip:abc123def4567890}",
                        capacity = 10,
                        ttl = t,
                    )
                }
            }
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    // ============================================================
    // Scenario 6 — Negative — tryAcquireByKey with IP-axis key (correct method)
    // ============================================================

    "negative: tryAcquireByKey with IP-axis key passes" {
        val code =
            """
            package id.nearyou.app.feature

            import java.time.Duration

            class T {
                fun fire(ttl: Duration) {
                    RateLimiter.tryAcquireByKey("{scope:health}:{ip:abc123def4567890}", 60, ttl)
                }
            }
            """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    // ============================================================
    // Scenario 7 — Negative — user-axis key (no `ip:` segment)
    // ============================================================

    "negative: tryAcquire with user-axis key passes" {
        val code =
            """
            package id.nearyou.app.feature

            import java.time.Duration
            import java.util.UUID

            class T {
                fun fire(userId: UUID, ttl: Duration) {
                    RateLimiter.tryAcquire(userId, "{scope:rate_like_day}:{user:${'$'}userId}", 10, ttl)
                }
            }
            """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    // ============================================================
    // Scenario 8 — Negative — non-IP non-user axis (rule scope narrow to `ip:` only)
    // ============================================================

    "negative: tryAcquire with geocell-axis key passes (scope intentionally narrow)" {
        // Per design.md § Decision 3 the rule's regex is intentionally narrow to `ip:` —
        // other non-`user:` axes (`geocell:`, `fingerprint:`, `global:`) are NOT detected
        // until a second axis ships a non-trivial `tryAcquireByKey` call site (rule of three).
        val code =
            """
            package id.nearyou.app.feature

            import java.time.Duration
            import java.util.UUID

            class T {
                fun fire(userId: UUID, ttl: Duration) {
                    RateLimiter.tryAcquire(userId, "{scope:foo}:{geocell:abc}", 10, ttl)
                }
            }
            """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    // ============================================================
    // Scenario 9 — Negative — Semaphore short-name collision
    // ============================================================

    "negative: Semaphore.tryAcquire short-name collision passes (argument-shape gate rejects)" {
        // The argument-shape gate (`as? KtStringTemplateExpression` on the second arg)
        // eliminates the false-positive surface: `Semaphore.tryAcquire(int, TimeUnit)`
        // has a non-String second positional, so the cast returns null and the rule
        // short-circuits before the regex check.
        val code =
            """
            package id.nearyou.app.feature

            import java.util.concurrent.Semaphore
            import java.util.concurrent.TimeUnit

            class T {
                fun acquireLock(semaphore: Semaphore): Boolean = semaphore.tryAcquire(1, TimeUnit.SECONDS)
            }
            """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    // ============================================================
    // Scenario 10 — Allowlist — test-source path suppresses
    // ============================================================

    "allowlist: test-source path suppresses (fixture identical to scenario 1)" {
        val code =
            """
            package id.nearyou.app.feature

            import java.time.Duration
            import java.util.UUID

            class T {
                fun fire(userId: UUID, ttl: Duration) {
                    RateLimiter.tryAcquire(userId, "{scope:health}:{ip:abc123def4567890}", 10, ttl)
                }
            }
            """.trimIndent()
        val path =
            writeKtFile(
                "RedisRateLimiterTelemetryTest.kt",
                code,
                listOf("infra", "redis", "src", "test", "kotlin"),
            )
        try {
            rule.lint(path).shouldBeEmpty()
        } finally {
            cleanupDir(path.parent.parent.parent.parent.parent.parent)
        }
    }

    // ============================================================
    // Scenario 11 — Composition with OtelForbiddenAttributeRule (two independent findings)
    // ============================================================

    "composition: tryAcquire + raw IPv4 fires IpAxisRule (1) AND OtelForbiddenAttributeRule (1), no cross-suppression" {
        // Fixture line triggers both rules independently:
        //   - IpAxisMustUseTryAcquireByKeyRule: fires on the wrong-method choice for {ip:...}
        //   - OtelForbiddenAttributeRule IP-axis Mode B: fires on the raw IPv4 dotted-quad
        //     in the {ip:<value>} value position (not 16-hex, not a template placeholder).
        val otelRule = OtelForbiddenAttributeRule()
        val code =
            """
            package id.nearyou.app.feature

            import java.time.Duration
            import java.util.UUID

            class T {
                fun fire(userId: UUID, ttl: Duration) {
                    RateLimiter.tryAcquire(userId, "{scope:foo}:{ip:1.2.3.4}", 10, ttl)
                }
            }
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
        otelRule.lint(code) shouldHaveSize 1
    }

    // ============================================================
    // Scenario 13 — Positive — block-form template `${...}` fires
    // ============================================================

    "positive: block-form template `${'$'}{...}` IP value fires (method-choice not value-shape)" {
        // Locks the rule's method-choice-not-value-shape semantic: the helper
        // `IpHasher.hash(clientIp)` produces a canonical 16-hex value that PASSES
        // `OtelForbiddenAttributeRule` Mode B (per its design clause 3, block-form
        // templates pass). This rule fires because the method choice is wrong
        // regardless of whether the value resolves correctly at runtime.
        val code =
            """
            package id.nearyou.app.feature

            import java.time.Duration
            import java.util.UUID

            class T {
                fun fire(userId: UUID, clientIp: String, ttl: Duration) {
                    RateLimiter.tryAcquire(
                        userId,
                        "{scope:foo}:{ip:${'$'}{IpHasher.hash(clientIp)}}",
                        10,
                        ttl,
                    )
                }
            }
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    // ============================================================
    // Scenario 14 — Positive — triple-quoted string fires
    // ============================================================

    "positive: triple-quoted string with IP-axis key fires" {
        // Confirms the implementation handles `removeSurrounding("\"\"\"")` for
        // triple-quoted KtStringTemplateExpression flat-content extraction. The
        // strip-triple-quotes-first / strip-single-quotes-second pattern mirrors
        // RedisHashTagRule + OtelForbiddenAttributeRule.
        val code =
            """
            package id.nearyou.app.feature

            import java.time.Duration
            import java.util.UUID

            class T {
                fun fire(userId: UUID, ttl: Duration) {
                    RateLimiter.tryAcquire(userId, ${"\"\"\""}{scope:foo}:{ip:abc123def4567890}${"\"\"\""}, 10, ttl)
                }
            }
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    // ============================================================
    // Scenario 15 — Positive — chained-call shape (canonical production form)
    // ============================================================

    "positive: chained-call shape `rateLimiter.tryAcquire(...)` fires" {
        // The canonical production form is `rateLimiter.tryAcquire(...)` — a property
        // receiver wrapped in a `KtDotQualifiedExpression`. The inner `KtCallExpression`
        // still has `calleeExpression = KtSimpleNameExpression("tryAcquire")`, so the
        // rule's callee check resolves correctly through the dot-receiver shape.
        val code =
            """
            package id.nearyou.app.feature

            import java.time.Duration
            import java.util.UUID

            class T {
                fun fire(rateLimiter: RateLimiter, userId: UUID, ttl: Duration) {
                    rateLimiter.tryAcquire(userId, "{scope:foo}:{ip:abc123def4567890}", 10, ttl)
                }
            }
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
    }

    // ============================================================
    // Scenario 16 — 3-rule composition (three independent findings)
    // ============================================================

    "composition: tryAcquire + legacy `rate:` prefix + raw IPv4 fires all THREE rules independently" {
        // Fixture line violates THREE invariants:
        //   - IpAxisMustUseTryAcquireByKeyRule: wrong method for {ip:...}
        //   - OtelForbiddenAttributeRule IP-axis Mode B: raw IPv4 in {ip:<value>}
        //   - RedisHashTagRule: legacy `rate:` prefix (not the strict
        //     {scope:NAME}:{AXIS:VAL} hash-tag form)
        // No cross-suppression — all three rules fire independently.
        val otelRule = OtelForbiddenAttributeRule()
        val hashTagRule = RedisHashTagRule()
        val code =
            """
            package id.nearyou.app.feature

            import java.time.Duration
            import java.util.UUID

            class T {
                fun fire(userId: UUID, ttl: Duration) {
                    RateLimiter.tryAcquire(userId, "rate:health:{ip:1.2.3.4}", 10, ttl)
                }
            }
            """.trimIndent()
        rule.lint(code) shouldHaveSize 1
        otelRule.lint(code) shouldHaveSize 1
        hashTagRule.lint(code) shouldHaveSize 1
    }

    // ============================================================
    // Scenario 17 — Path allowlist non-allowlisted companion fires
    // ============================================================

    "path allowlist: file under /backend/ktor/src/main/ (non-allowlisted) DOES fire" {
        // Pairs with scenario 10's allowlisted-suppresses to lock both sides of the
        // allowlist behaviour. Without this companion, a regression where the allowlist
        // always returned true would still pass the test suite.
        val code =
            """
            package id.nearyou.app.feature

            import java.time.Duration
            import java.util.UUID

            class T {
                fun fire(userId: UUID, ttl: Duration) {
                    RateLimiter.tryAcquire(userId, "{scope:health}:{ip:abc123def4567890}", 10, ttl)
                }
            }
            """.trimIndent()
        val path =
            writeKtFile(
                "SomeService.kt",
                code,
                listOf("backend", "ktor", "src", "main", "kotlin"),
            )
        try {
            rule.lint(path) shouldHaveSize 1
        } finally {
            cleanupDir(path.parent.parent.parent.parent.parent.parent)
        }
    }

    // ============================================================
    // Synthetic-file harness — package-FQN fallback covers
    // ============================================================

    "synthetic-file harness: package id.nearyou.test.fixtures.* treated as allowlisted" {
        // The synthetic `lint(String)` overload gives the file no real virtualFilePath
        // (it lands at "Test.kt" with no `/src/test/` substring). The package-FQN
        // fallback catches this — package starting with `id.nearyou.test.fixtures.` is
        // allowlisted (mirrors the canonical RedisHashTagRule / RateLimitTtlRule /
        // BlockExclusionJoinRule pattern).
        val code =
            """
            package id.nearyou.test.fixtures.iprule

            import java.time.Duration
            import java.util.UUID

            class T {
                fun fire(userId: UUID, ttl: Duration) {
                    RateLimiter.tryAcquire(userId, "{scope:health}:{ip:abc123def4567890}", 10, ttl)
                }
            }
            """.trimIndent()
        rule.lint(code).shouldBeEmpty()
    }

    // ============================================================
    // Scenario 12 — NearYouRuleSetProvider registration (co-located in this file)
    // ============================================================

    "rule registered in NearYouRuleSetProvider" {
        // Mirror the precedent at OtelForbiddenAttributeLintTest.kt:807-812
        // (and earlier RedisHashTagRuleTest.kt:244 + RateLimitTtlRuleTest.kt:242).
        // `Config.empty` is a property — no parens.
        val provider = NearYouRuleSetProvider()
        val ruleSet = provider.instance(io.gitlab.arturbosch.detekt.api.Config.empty)
        val rules = ruleSet.rules.map { it::class.simpleName }
        rules.contains("IpAxisMustUseTryAcquireByKeyRule") shouldBe true
    }
})

private fun cleanupDir(dir: Path) {
    if (!Files.exists(dir)) return
    Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
}
