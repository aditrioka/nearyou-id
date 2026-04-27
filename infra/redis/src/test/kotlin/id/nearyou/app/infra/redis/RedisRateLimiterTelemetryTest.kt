package id.nearyou.app.infra.redis

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.lettuce.core.RedisClient
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.UUID

/**
 * Telemetry-shape regression test for [RedisRateLimiter].
 *
 * Spec contract (per `rate-limit-infrastructure` MODIFIED scenario
 * "tryAcquireByKey omits userId from telemetry"):
 *
 *  - `tryAcquire(userId, ...)` log entries MUST include a `user_id=<uuid>` field.
 *  - `tryAcquireByKey(key, ...)` log entries MUST include `key=<key>` AND
 *    MUST NOT include a `user_id` field. Sentinel UUIDs like
 *    `00000000-0000-0000-0000-000000000000` MUST NOT appear in the logged
 *    output.
 *
 * Test mechanism: an unreachable Redis URL forces `sync()` to fail-soft
 * (returns null) → admit() returns `Allowed` early without exercising the
 * EVALSHA failure path. The connect-failure log is `event=redis_connect_failed`
 * (no key, no user_id). This test does NOT exercise the admit-time log line
 * directly — that requires a connected Redis whose EVALSHA fails, which is
 * hard to trigger reliably in a unit test.
 *
 * Instead, this test verifies the **structural property** of the connect-
 * failure log AND the source-level shape of the admit-time log statements
 * (via direct inspection of the recorded log events when fail-soft fires).
 *
 * Backfilled per `health-check-test-coverage-gaps` follow-up (task 3.10 of
 * archived `health-check-endpoints` change).
 */
class RedisRateLimiterTelemetryTest : StringSpec(
    {
        val unreachableUrl = "redis://127.0.0.1:1"

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
            // Two distinct admit-time log formats present:
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
    },
)
