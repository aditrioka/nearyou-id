package id.nearyou.app.infra.redis

import id.nearyou.app.infra.otel.IpHasher
import id.nearyou.app.infra.otel.OtelInstrumentation
import id.nearyou.app.infra.otel.testing.SpanRecorder
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import java.time.Duration
import java.util.UUID

/**
 * Anti-PII regression for the IP-axis rate-limit Lua-key shape.
 *
 * Spec contract (per `observability-otel-foundation` MODIFIED scenario
 * "No raw client IP appears in Lua key on EVALSHA span"): after the
 * `rate-limit-ip-hashing` change, the Lettuce `EVALSHA` span's
 * `db.statement` attribute MUST carry the hashed `{ip:[0-9a-f]{16}}` form
 * — never the raw IPv4 dotted-quad.
 *
 * Mechanism:
 *  1. Build an [OpenTelemetry][io.opentelemetry.api.OpenTelemetry] backed
 *     by [SpanRecorder.newPipeline] so spans land in-memory.
 *  2. Wire that into Lettuce via [OtelInstrumentation.lettuceClientResources]
 *     — the same helper production uses, so this test exercises the
 *     real instrumentation path.
 *  3. Run a real `tryAcquireByKey` against a [RedisRateLimiter] with the
 *     canonical /health/{live,ready} key shape `{scope:health}:{ip:<hashed>}`
 *     (where `<hashed>` = `IpHasher.hash("1.2.3.4")`).
 *  4. Inspect every captured span's `db.statement`: assert at least one
 *     carries the hashed segment AND no span anywhere carries the literal
 *     `"1.2.3.4"`.
 *
 * Tagged `@Tags("database")` so unit-only test runs (`-Dkotest.tags='!database'`)
 * skip it. Mirrors [RedisRateLimiterIntegrationTest] reachability-probe
 * pattern: silently skips if `REDIS_URL` (defaulting to
 * `redis://localhost:6379`) is unreachable.
 *
 * Closes the BLOCKING coverage gap from round-1 review (B1) of the
 * `rate-limit-ip-hashing` proposal — without this test, a regression that
 * re-introduces the raw IP in the Lua key would only be caught at staging
 * Tempo verification (Section 6 of `tasks.md`).
 */
@Tags("database")
class LettuceSpanCaptureTest : StringSpec(
    spec@{
        val redisUrl = System.getenv("REDIS_URL")?.takeIf { it.isNotBlank() } ?: "redis://localhost:6379"

        // Reachability probe (mirrors RedisRateLimiterIntegrationTest).
        val redisReachable =
            try {
                RedisClient.create(redisUrl).use { probeClient ->
                    probeClient.connect().use { conn -> conn.sync().ping() }
                }
                true
            } catch (_: Exception) {
                false
            }
        if (!redisReachable) return@spec

        "EVALSHA span db.statement carries hashed IP, never raw" {
            val (otel, recorder) = SpanRecorder.newPipeline()
            val resources = OtelInstrumentation.lettuceClientResources(otel)
            val client = RedisClient.create(resources, RedisURI.create(redisUrl))
            try {
                val rawIp = "1.2.3.4"
                val hashedIp = IpHasher.hash(rawIp)
                // Unique-per-test bucket suffix so cross-run state doesn't
                // confuse the assertion. The hashed IP segment is what we're
                // verifying — the test-bucket suffix lives in a different
                // hash-tag segment so it can't pollute the IP literal scan.
                val testKey =
                    "{scope:health_ip_hashing_test}:{ip:$hashedIp}:{nonce:${UUID.randomUUID()}}"

                RedisRateLimiter(client).use { limiter ->
                    // Two calls to ensure both EVALSHA fast-path and the
                    // EVAL fallback (on first-call NOSCRIPT) get exercised.
                    limiter.tryAcquireByKey(testKey, capacity = 60, ttl = Duration.ofSeconds(60))
                    limiter.tryAcquireByKey(testKey, capacity = 60, ttl = Duration.ofSeconds(60))
                }

                val statements =
                    recorder.recordedSpans()
                        .mapNotNull { span ->
                            span.attributes.asMap().entries
                                .firstOrNull { it.key.key == "db.statement" }
                                ?.value
                                ?.toString()
                        }

                // Sanity: at least one Redis span emitted with a non-null
                // db.statement (otherwise the assertions below are vacuous).
                (statements.isNotEmpty()) shouldBe true

                // Anti-PII invariant: NO captured statement carries the raw
                // dotted-quad. Substring match is conservative (catches it
                // regardless of how Lettuce serializes the EVAL/EVALSHA call).
                statements.any { it.contains(rawIp) } shouldBe false

                // Positive shape check: at least one statement carries the
                // hashed IP segment (proves the key passed through to Lettuce
                // was the post-hash form, not just that the raw form was
                // stripped at the OTel exporter layer).
                statements.any { it.contains("{ip:$hashedIp}") } shouldBe true
            } finally {
                client.shutdown()
            }
        }
    },
)
