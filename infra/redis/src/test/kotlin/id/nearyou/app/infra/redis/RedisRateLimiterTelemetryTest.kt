package id.nearyou.app.infra.redis

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import id.nearyou.app.core.domain.ratelimit.RateLimiter
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.lettuce.core.RedisClient
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.UUID

/**
 * Telemetry-shape regression test for [RedisRateLimiter].
 *
 * Spec contract (per `rate-limit-infrastructure` requirements
 * "Redis-backed admit emits structured log on every Redis-evaluated outcome"
 * and "Test coverage — Redis-backed admit telemetry success-path"):
 *
 *  - `tryAcquire(userId, ...)` log entries MUST include a `user_id=<uuid>` field.
 *  - `tryAcquireByKey(key, ...)` log entries MUST include `key=<key>` AND
 *    MUST NOT include a `user_id` field. Sentinel UUIDs like
 *    `00000000-0000-0000-0000-000000000000` MUST NOT appear.
 *  - On admits that successfully invoke the Lua script (success or rate-limited),
 *    a DEBUG `event=rate_limit_check key={} outcome={} ...` entry MUST be emitted.
 *  - On the fail-soft early-return path (`sync()` returns null), the new log
 *    MUST NOT fire — the existing `event=redis_connect_failed` WARN log carries
 *    the operator signal.
 *  - Source-level structural conditional MUST sit inside `if (telemetryUserId != null) { ... } else { ... }`
 *    branches — a copy-paste-error refactor that emits both lines unconditionally
 *    would silently leak `user_id=` and is forbidden by the source-level structural
 *    test.
 *
 * Test mechanism: success-path scenarios connect to the CI `redis:7-alpine`
 * service container (or `REDIS_URL`) via the same reachability-probe-and-skip
 * pattern as `RedisRateLimiterIntegrationTest`. Fail-soft scenarios use the
 * unreachable URL `redis://127.0.0.1:1`. Source-level scenarios read the file
 * directly with the standard working-directory fallback.
 *
 * Backfilled per the `rate-limit-infrastructure-success-path-log` change
 * (closes the `rate-limit-infrastructure-success-path-key-log-drift` follow-up
 * surfaced by PR #74 §6.5 pre-archive smoke verification).
 */
class RedisRateLimiterTelemetryTest : StringSpec(
    spec@{
        val unreachableUrl = "redis://127.0.0.1:1"

        // Snapshot the test classpath's effective level BEFORE any scenario runs.
        // The cross-test pollution scenario at the end of this spec verifies
        // teardown restored to this observed baseline (not a hardcoded INFO/TRACE,
        // which varies across Gradle vs IDE invocations and across `:backend:ktor`
        // vs `:infra:redis` test classpath logback config). This is the
        // canonical defense against a captureDebugAndWarnings teardown that
        // silently swallows a setLevel exception.
        val initialEffectiveLevel =
            (LoggerFactory.getLogger(RedisRateLimiter::class.java) as Logger).effectiveLevel
        val initialConfiguredLevel =
            (LoggerFactory.getLogger(RedisRateLimiter::class.java) as Logger).level

        // -----------------------------------------------------------------------
        // Logger appender helpers — exception-safe teardown so a failed test
        // does not leak DEBUG into subsequent tests in the same JVM.
        // -----------------------------------------------------------------------

        fun captureWarnings(block: () -> Unit): List<String> {
            val logger = LoggerFactory.getLogger(RedisRateLimiter::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>()
            appender.start()
            logger.addAppender(appender)
            try {
                block()
            } finally {
                logger.detachAppender(appender)
                appender.stop()
            }
            return appender.list
                .filter { it.level == Level.WARN }
                .map { it.formattedMessage }
        }

        fun captureDebugAndWarnings(block: () -> Unit): List<String> {
            val logger = LoggerFactory.getLogger(RedisRateLimiter::class.java) as Logger
            val originalLevel = logger.level
            val appender = ListAppender<ILoggingEvent>()
            appender.start()
            logger.addAppender(appender)
            logger.level = Level.DEBUG
            try {
                block()
            } finally {
                // Exception-safe restore: nested try/catch so teardown errors
                // don't mask the original test failure.
                try {
                    logger.level = originalLevel
                } catch (_: Exception) {
                    // best-effort
                }
                try {
                    logger.detachAppender(appender)
                } catch (_: Exception) {
                    // best-effort
                }
                appender.stop()
            }
            return appender.list
                .filter { it.level == Level.DEBUG || it.level == Level.WARN }
                .map { it.formattedMessage }
        }

        fun captureAtInfoLevel(block: () -> Unit): List<String> {
            val logger = LoggerFactory.getLogger(RedisRateLimiter::class.java) as Logger
            val originalLevel = logger.level
            val appender = ListAppender<ILoggingEvent>()
            appender.start()
            logger.addAppender(appender)
            logger.level = Level.INFO
            try {
                block()
            } finally {
                try {
                    logger.level = originalLevel
                } catch (_: Exception) {
                    // best-effort
                }
                try {
                    logger.detachAppender(appender)
                } catch (_: Exception) {
                    // best-effort
                }
                appender.stop()
            }
            return appender.list.map { it.formattedMessage }
        }

        // -----------------------------------------------------------------------
        // Existing fail-soft scenarios (preserved)
        // -----------------------------------------------------------------------

        "tryAcquire fail-soft log includes the connect-failure event" {
            val client = RedisClient.create(unreachableUrl)
            try {
                val limiter = RedisRateLimiter(client)
                val warnings =
                    captureWarnings {
                        limiter.tryAcquire(
                            userId = UUID.randomUUID(),
                            key = "{scope:test}:{user:abc}",
                            capacity = 10,
                            ttl = Duration.ofSeconds(60),
                        )
                    }
                // Connect-level failure log emitted exactly once.
                val connectFailures = warnings.filter { it.contains("event=redis_connect_failed") }
                connectFailures.size shouldBe 1
                connectFailures.first().contains("fail_soft=true") shouldBe true
            } finally {
                client.shutdown()
            }
        }

        "tryAcquireByKey fail-soft log does NOT include user_id field" {
            val client = RedisClient.create(unreachableUrl)
            try {
                val limiter = RedisRateLimiter(client)
                val warnings =
                    captureWarnings {
                        limiter.tryAcquireByKey(
                            key = "{scope:health}:{ip:1.2.3.4}",
                            capacity = 60,
                            ttl = Duration.ofSeconds(60),
                        )
                    }
                // Anti-info-leak invariant: NO log line emitted from this
                // call should contain `user_id=` (key-axis call has no userId
                // by construction). Sentinel UUIDs MUST NOT appear either.
                warnings.forEach { line ->
                    line.contains("user_id=") shouldBe false
                    line.contains("00000000-0000-0000-0000-000000000000") shouldBe false
                }
            } finally {
                client.shutdown()
            }
        }

        "RedisRateLimiter source has admit-time log conditional on telemetryUserId" {
            // Structural regression gate: the production source MUST contain
            // exactly one `if (telemetryUserId != null)` branch + two distinct
            // log statements (one with `user_id={}` and one without). A
            // future refactor that extracts a shared logger helper and drops
            // the conditional would silently re-introduce user_id leakage on
            // the key-axis path. This test catches that drift at CI time.
            val sourcePath =
                "src/main/kotlin/id/nearyou/app/infra/redis/RedisRateLimiter.kt"
            val source =
                java.io.File(sourcePath)
                    .takeIf { it.exists() }
                    ?.readText()
                    ?: java.io.File("../../$sourcePath").readText()
            // Two distinct admit-time log formats present (failure path):
            val withUserIdFmt =
                """event=redis_acquire_failed user_id={} key={} reason={} message={} fail_soft=true"""
            val keyOnlyFmt =
                """event=redis_acquire_failed key={} reason={} message={} fail_soft=true"""
            source.contains(withUserIdFmt) shouldBe true
            source.contains(keyOnlyFmt) shouldBe true
            // The conditional that routes between them MUST exist.
            (
                source.contains("if (telemetryUserId != null)") ||
                    source.contains("if (telemetryUserId !=null)")
            ) shouldBe true
        }

        // -----------------------------------------------------------------------
        // NEW source-level structural test for success-path log shape (extension
        // of the existing structural test above). Uses regex assertions to
        // catch both consolidated-single-format-string AND copy-paste-stacked
        // refactor patterns that runtime appender tests can miss.
        // -----------------------------------------------------------------------

        "RedisRateLimiter source has success-path log conditional with else-branch placement (regex)" {
            val sourcePath =
                "src/main/kotlin/id/nearyou/app/infra/redis/RedisRateLimiter.kt"
            val source =
                java.io.File(sourcePath)
                    .takeIf { it.exists() }
                    ?.readText()
                    ?: java.io.File("../../$sourcePath").readText()

            // Success-path allowed branch: enforce if/else placement of the
            // user-axis `user_id={}` format string vs the key-axis key-only
            // format string. A copy-paste-error refactor that stacks both
            // lines outside the conditional would FAIL this regex match.
            val successAllowedRegex =
                Regex(
                    """if \(telemetryUserId !=\s*null\) \{[\s\S]*?""" +
                        """event=rate_limit_check user_id=\{\} key=\{\} outcome=allowed""" +
                        """[\s\S]*?\} else \{[\s\S]*?""" +
                        """event=rate_limit_check key=\{\} outcome=allowed[\s\S]*?\}""",
                )
            successAllowedRegex.containsMatchIn(source) shouldBe true

            // Rate-limited branch: same structural enforcement for the
            // outcome=rate_limited retry_after_seconds={} variant.
            val successRateLimitedRegex =
                Regex(
                    """if \(telemetryUserId !=\s*null\) \{[\s\S]*?""" +
                        """event=rate_limit_check user_id=\{\} key=\{\} outcome=rate_limited """ +
                        """retry_after_seconds=\{\}[\s\S]*?\} else \{[\s\S]*?""" +
                        """event=rate_limit_check key=\{\} outcome=rate_limited """ +
                        """retry_after_seconds=\{\}[\s\S]*?\}""",
                )
            successRateLimitedRegex.containsMatchIn(source) shouldBe true

            // Direct-substring sanity checks (cheap defense-in-depth on top of regex).
            source.contains("event=rate_limit_check user_id={} key={} outcome=allowed remaining={}") shouldBe true
            source.contains("event=rate_limit_check key={} outcome=allowed remaining={}") shouldBe true
            source.contains(
                "event=rate_limit_check user_id={} key={} outcome=rate_limited retry_after_seconds={}",
            ) shouldBe true
            source.contains(
                "event=rate_limit_check key={} outcome=rate_limited retry_after_seconds={}",
            ) shouldBe true
        }

        // -----------------------------------------------------------------------
        // Fail-soft early-return non-emission scenario (uses unreachable URL).
        // The new `event=rate_limit_check` log MUST NOT fire on the early-return
        // path; the existing `event=redis_connect_failed` WARN is the operator
        // signal for fail-soft.
        // -----------------------------------------------------------------------

        "Fail-soft early-return path emits no rate_limit_check log" {
            val client = RedisClient.create(unreachableUrl)
            try {
                val limiter = RedisRateLimiter(client)
                val captured =
                    captureDebugAndWarnings {
                        val outcome =
                            limiter.tryAcquireByKey(
                                key = "{scope:test_fail_soft}:{ip:abc123def4567890}",
                                capacity = 60,
                                ttl = Duration.ofSeconds(60),
                            )
                        // Synthetic fail-soft outcome equals capacity.
                        outcome shouldBe RateLimiter.Outcome.Allowed(remaining = 60)
                    }
                // Negative-emission contract: the new log MUST NOT fire on the
                // early-return path.
                captured.none { it.contains("event=rate_limit_check") } shouldBe true
                // The existing fail-soft WARN log fires.
                captured.any { it.contains("event=redis_connect_failed") } shouldBe true
            } finally {
                client.shutdown()
            }
        }

        // -----------------------------------------------------------------------
        // Real-Redis success-path scenarios — reachability-probe-and-skip
        // pattern mirrors RedisRateLimiterIntegrationTest. If Redis is
        // unreachable, these scenarios are simply not registered with Kotest.
        // -----------------------------------------------------------------------

        val redisUrl = System.getenv("REDIS_URL")?.takeIf { it.isNotBlank() } ?: "redis://localhost:6379"
        val redisReachable =
            try {
                RedisClient.create(redisUrl).use { probeClient ->
                    probeClient.connect().use { conn ->
                        conn.sync().ping()
                    }
                }
                true
            } catch (_: Exception) {
                false
            }

        if (redisReachable) {
            val client = RedisClient.create(redisUrl)
            afterSpec {
                client.shutdown()
            }

            fun freshKey(scope: String): String {
                val unique = UUID.randomUUID().toString().substring(0, 8)
                return "{scope:$scope}:{test:$unique}"
            }

            "Success-path admit emits rate_limit_check log for tryAcquire with user_id" {
                RedisRateLimiter(client).use { limiter ->
                    val u = UUID.randomUUID()
                    val key = freshKey("rate_test_day")
                    val captured =
                        captureDebugAndWarnings {
                            val outcome =
                                limiter.tryAcquire(
                                    userId = u,
                                    key = key,
                                    capacity = 10,
                                    ttl = Duration.ofSeconds(60),
                                )
                            outcome shouldBe RateLimiter.Outcome.Allowed(remaining = 9)
                        }
                    val rateLimitChecks = captured.filter { it.contains("event=rate_limit_check") }
                    rateLimitChecks.size shouldBe 1
                    val event = rateLimitChecks.first()
                    event.contains("user_id=$u") shouldBe true
                    event.contains("key=$key") shouldBe true
                    event.contains("outcome=allowed") shouldBe true
                    event.contains("remaining=9") shouldBe true
                    // No failure-path noise from this admit.
                    captured.none { it.contains("event=redis_acquire_failed") } shouldBe true
                    captured.none { it.contains("event=redis_connect_failed") } shouldBe true
                }
            }

            "Success-path admit emits rate_limit_check log for tryAcquireByKey WITHOUT user_id" {
                RedisRateLimiter(client).use { limiter ->
                    val key = freshKey("test_health")
                    val captured =
                        captureDebugAndWarnings {
                            val outcome =
                                limiter.tryAcquireByKey(
                                    key = key,
                                    capacity = 60,
                                    ttl = Duration.ofSeconds(60),
                                )
                            outcome shouldBe RateLimiter.Outcome.Allowed(remaining = 59)
                        }
                    val rateLimitChecks = captured.filter { it.contains("event=rate_limit_check") }
                    rateLimitChecks.size shouldBe 1
                    val event = rateLimitChecks.first()
                    event.contains("key=$key") shouldBe true
                    event.contains("outcome=allowed") shouldBe true
                    event.contains("remaining=59") shouldBe true
                    // Strong user_id-absence check (forEach shape mirrors the
                    // existing fail-soft test) — every captured DEBUG event from
                    // this admit MUST omit user_id=, not just the success-path one.
                    captured.forEach { line ->
                        line.contains("user_id=") shouldBe false
                        line.contains("00000000-0000-0000-0000-000000000000") shouldBe false
                    }
                }
            }

            "User-axis rate-limited admit emits rate_limit_check with retry_after_seconds" {
                RedisRateLimiter(client).use { limiter ->
                    val u = UUID.randomUUID()
                    val key = freshKey("rate_test_day")
                    val capturedFromBoth =
                        captureDebugAndWarnings {
                            val first =
                                limiter.tryAcquire(
                                    userId = u,
                                    key = key,
                                    capacity = 1,
                                    ttl = Duration.ofSeconds(60),
                                )
                            first shouldBe RateLimiter.Outcome.Allowed(remaining = 0)
                            val second =
                                limiter.tryAcquire(
                                    userId = u,
                                    key = key,
                                    capacity = 1,
                                    ttl = Duration.ofSeconds(60),
                                )
                            second shouldNotBe RateLimiter.Outcome.Allowed(remaining = 0)
                            (second is RateLimiter.Outcome.RateLimited) shouldBe true
                            val rateLimited = second as RateLimiter.Outcome.RateLimited
                            // Capture the returned value so we can byte-for-byte compare with the log.
                            rateLimited.retryAfterSeconds
                        }
                    val rateLimitChecks = capturedFromBoth.filter { it.contains("event=rate_limit_check") }
                    rateLimitChecks.size shouldBe 2
                    val rateLimitedEvent =
                        rateLimitChecks.first { it.contains("outcome=rate_limited") }
                    rateLimitedEvent.contains("user_id=$u") shouldBe true
                    rateLimitedEvent.contains("key=$key") shouldBe true
                    // retry_after_seconds field present and >= 1.
                    val retryAfterMatch = Regex("retry_after_seconds=(\\d+)").find(rateLimitedEvent)
                    (retryAfterMatch != null) shouldBe true
                    val retryAfter = retryAfterMatch!!.groupValues[1].toLong()
                    (retryAfter >= 1L) shouldBe true
                }
            }

            "Key-axis rate-limited admit emits rate_limit_check WITHOUT user_id with retry_after_seconds" {
                RedisRateLimiter(client).use { limiter ->
                    val key = freshKey("test_health_saturation")
                    val captured =
                        captureDebugAndWarnings {
                            val first =
                                limiter.tryAcquireByKey(
                                    key = key,
                                    capacity = 1,
                                    ttl = Duration.ofSeconds(60),
                                )
                            first shouldBe RateLimiter.Outcome.Allowed(remaining = 0)
                            val second =
                                limiter.tryAcquireByKey(
                                    key = key,
                                    capacity = 1,
                                    ttl = Duration.ofSeconds(60),
                                )
                            (second is RateLimiter.Outcome.RateLimited) shouldBe true
                        }
                    val rateLimitChecks = captured.filter { it.contains("event=rate_limit_check") }
                    rateLimitChecks.size shouldBe 2
                    val rateLimitedEvent =
                        rateLimitChecks.first { it.contains("outcome=rate_limited") }
                    rateLimitedEvent.contains("key=$key") shouldBe true
                    rateLimitedEvent.contains("outcome=rate_limited") shouldBe true
                    val retryAfterMatch = Regex("retry_after_seconds=(\\d+)").find(rateLimitedEvent)
                    (retryAfterMatch != null) shouldBe true
                    val retryAfter = retryAfterMatch!!.groupValues[1].toLong()
                    (retryAfter >= 1L) shouldBe true
                    // Strong user_id-absence check across all captured events from this admit.
                    captured.forEach { line ->
                        line.contains("user_id=") shouldBe false
                        line.contains("00000000-0000-0000-0000-000000000000") shouldBe false
                    }
                }
            }

            "DEBUG-filtered emission contract — log is opt-in, never load-bearing" {
                RedisRateLimiter(client).use { limiter ->
                    val key = freshKey("test_debug_filtered")
                    val captured =
                        captureAtInfoLevel {
                            // Logger level pinned to INFO — DEBUG entries are filtered
                            // out by the appender. The admit MUST still succeed.
                            val outcome =
                                limiter.tryAcquireByKey(
                                    key = key,
                                    capacity = 10,
                                    ttl = Duration.ofSeconds(60),
                                )
                            outcome shouldBe RateLimiter.Outcome.Allowed(remaining = 9)
                        }
                    // No DEBUG entries captured (the log is opt-in via DEBUG enablement;
                    // the runtime contract is unchanged regardless of log level).
                    captured.none { it.contains("event=rate_limit_check") } shouldBe true
                }
            }
        }

        // -----------------------------------------------------------------------
        // Cross-test pollution scenario — MUST run LAST (per spec scenario
        // "Cross-test pollution prevented via effectiveLevel comparison").
        // Verifies that all preceding scenarios' captureDebugAndWarnings /
        // captureAtInfoLevel teardowns restored the original level cleanly.
        // -----------------------------------------------------------------------

        "Cross-test pollution prevented via effectiveLevel comparison" {
            val logger = LoggerFactory.getLogger(RedisRateLimiter::class.java) as Logger
            // `effectiveLevel` always resolves via the Logback inheritance
            // chain — `level` may be null for inherited-from-root loggers, so
            // `level == null` is NOT a passing condition. Compare against the
            // baseline captured at spec init (BEFORE any DEBUG-bumping
            // scenario ran). This catches a captureDebugAndWarnings /
            // captureAtInfoLevel teardown that silently swallowed a setLevel
            // exception — both effective AND configured levels MUST match
            // the original snapshot.
            logger.effectiveLevel shouldBe initialEffectiveLevel
            logger.level shouldBe initialConfiguredLevel
        }
    },
)
