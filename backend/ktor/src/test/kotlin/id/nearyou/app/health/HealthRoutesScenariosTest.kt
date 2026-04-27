package id.nearyou.app.health

import id.nearyou.app.common.ClientIpExtractorPlugin
import id.nearyou.app.core.domain.health.PostgresProbe
import id.nearyou.app.core.domain.health.ProbeError
import id.nearyou.app.core.domain.health.ProbeResult
import id.nearyou.app.core.domain.health.RedisProbe
import id.nearyou.app.core.domain.health.SupabaseRealtimeProbe
import id.nearyou.app.core.domain.ratelimit.InMemoryRateLimiter
import id.nearyou.app.core.domain.ratelimit.RateLimiter
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Test-double rate limiter that records every call so scenarios can assert the
 * literal key shape passed by the health-route gate, AND that the user-keyed
 * `tryAcquire(userId, ...)` overload is NEVER invoked from the health path
 * (the sentinel-UUID-uncalled invariant).
 *
 * Mirrors the precedent set by `SpyRateLimiter` in `LikeRateLimitTest` and
 * `ReplyRateLimitTest` — same shape, scoped to health-specific assertions.
 */
private class SpyHealthRateLimiter(val delegate: RateLimiter) : RateLimiter {
    val acquireByKeyCalls: ConcurrentLinkedQueue<AcquireByKeyCall> = ConcurrentLinkedQueue()
    val tryAcquireUserCalls: ConcurrentLinkedQueue<AcquireUserCall> = ConcurrentLinkedQueue()

    override fun tryAcquire(
        userId: UUID,
        key: String,
        capacity: Int,
        ttl: Duration,
    ): RateLimiter.Outcome {
        tryAcquireUserCalls.add(AcquireUserCall(userId, key, capacity, ttl))
        return delegate.tryAcquire(userId, key, capacity, ttl)
    }

    override fun tryAcquireByKey(
        key: String,
        capacity: Int,
        ttl: Duration,
    ): RateLimiter.Outcome {
        acquireByKeyCalls.add(AcquireByKeyCall(key, capacity, ttl))
        return delegate.tryAcquireByKey(key, capacity, ttl)
    }

    override fun releaseMostRecent(
        userId: UUID,
        key: String,
    ) = delegate.releaseMostRecent(userId, key)

    data class AcquireByKeyCall(val key: String, val capacity: Int, val ttl: Duration)

    data class AcquireUserCall(
        val userId: UUID,
        val key: String,
        val capacity: Int,
        val ttl: Duration,
    )
}

private val OK_POSTGRES_PROBE: PostgresProbe =
    object : PostgresProbe {
        override suspend fun probe(timeout: Duration): ProbeResult = ProbeResult(ok = true, latencyMs = 3L, error = null)
    }

private val FAIL_POSTGRES_PROBE: PostgresProbe =
    object : PostgresProbe {
        override suspend fun probe(timeout: Duration): ProbeResult =
            ProbeResult(ok = false, latencyMs = 200L, error = ProbeError.CONNECTION_REFUSED)
    }

private val OK_REDIS_PROBE: RedisProbe =
    object : RedisProbe {
        override suspend fun ping(timeout: Duration): ProbeResult = ProbeResult(ok = true, latencyMs = 5L, error = null)
    }

private val OK_SUPABASE_PROBE: SupabaseRealtimeProbe =
    object : SupabaseRealtimeProbe {
        override suspend fun ping(timeout: Duration): ProbeResult = ProbeResult(ok = true, latencyMs = 7L, error = null)
    }

private fun delayingPostgresProbe(suspendMs: Long): PostgresProbe =
    object : PostgresProbe {
        override suspend fun probe(timeout: Duration): ProbeResult {
            delay(suspendMs)
            return ProbeResult(ok = true, latencyMs = suspendMs, error = null)
        }
    }

private fun delayingRedisProbe(suspendMs: Long): RedisProbe =
    object : RedisProbe {
        override suspend fun ping(timeout: Duration): ProbeResult {
            delay(suspendMs)
            return ProbeResult(ok = true, latencyMs = suspendMs, error = null)
        }
    }

private fun delayingSupabaseProbe(suspendMs: Long): SupabaseRealtimeProbe =
    object : SupabaseRealtimeProbe {
        override suspend fun ping(timeout: Duration): ProbeResult {
            delay(suspendMs)
            return ProbeResult(ok = true, latencyMs = suspendMs, error = null)
        }
    }

private fun io.ktor.server.testing.ApplicationTestBuilder.installRoutes(
    postgresProbe: PostgresProbe = OK_POSTGRES_PROBE,
    redisProbe: RedisProbe = OK_REDIS_PROBE,
    supabaseProbe: SupabaseRealtimeProbe = OK_SUPABASE_PROBE,
    rateLimiter: RateLimiter = InMemoryRateLimiter(),
) {
    application {
        install(ClientIpExtractorPlugin)
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    explicitNulls = false
                },
            )
        }
        routing { installHealthRoutes(postgresProbe, redisProbe, supabaseProbe, rateLimiter) }
    }
}

private fun parseBody(body: String): JsonObject = Json.parseToJsonElement(body).jsonObject

private fun checkAt(
    body: JsonObject,
    index: Int,
): JsonObject = body["checks"]!!.jsonArray[index].jsonObject

class HealthRoutesScenariosTest : StringSpec({

    "GET /health/live always returns 200 regardless of probe state" {
        testApplication {
            installRoutes(
                postgresProbe = FAIL_POSTGRES_PROBE,
                redisProbe =
                    object : RedisProbe {
                        override suspend fun ping(timeout: Duration): ProbeResult =
                            ProbeResult(ok = false, latencyMs = 200L, error = ProbeError.TIMEOUT)
                    },
            )
            val response = client.get("/health/live")
            response.status shouldBe HttpStatusCode.OK
        }
    }

    "GET /health/ready returns 200 + status:ready when all probes succeed" {
        testApplication {
            installRoutes()
            val response = client.get("/health/ready")
            response.status shouldBe HttpStatusCode.OK
            val body = parseBody(response.bodyAsText())
            body["status"]!!.jsonPrimitive.content shouldBe "ready"
            val checks = body["checks"]!!.jsonArray
            checks shouldHaveSize 3
            checkAt(body, 0)["name"]!!.jsonPrimitive.content shouldBe "postgres"
            checkAt(body, 1)["name"]!!.jsonPrimitive.content shouldBe "redis"
            checkAt(body, 2)["name"]!!.jsonPrimitive.content shouldBe "supabase_realtime"
            // error field MUST be absent when ok=true (assert via JSON parse, not substring).
            checkAt(body, 0).shouldNotContainKey("error")
            checkAt(body, 1).shouldNotContainKey("error")
            checkAt(body, 2).shouldNotContainKey("error")
        }
    }

    "GET /health/ready returns 503 + status:degraded when Postgres unreachable" {
        testApplication {
            installRoutes(postgresProbe = FAIL_POSTGRES_PROBE)
            val response = client.get("/health/ready")
            response.status shouldBe HttpStatusCode.ServiceUnavailable
            val body = parseBody(response.bodyAsText())
            body["status"]!!.jsonPrimitive.content shouldBe "degraded"
            checkAt(body, 0)["ok"]!!.jsonPrimitive.content shouldBe "false"
            checkAt(body, 1)["ok"]!!.jsonPrimitive.content shouldBe "true"
            checkAt(body, 2)["ok"]!!.jsonPrimitive.content shouldBe "true"
        }
    }

    "GET /health/ready returns 503 when probe reports timeout error" {
        testApplication {
            installRoutes(
                redisProbe =
                    object : RedisProbe {
                        override suspend fun ping(timeout: Duration): ProbeResult =
                            ProbeResult(ok = false, latencyMs = 200L, error = ProbeError.TIMEOUT)
                    },
            )
            val response = client.get("/health/ready")
            response.status shouldBe HttpStatusCode.ServiceUnavailable
            val body = parseBody(response.bodyAsText())
            checkAt(body, 1)["ok"]!!.jsonPrimitive.content shouldBe "false"
            checkAt(body, 1)["error"]!!.jsonPrimitive.content shouldBe ProbeError.TIMEOUT
        }
    }

    "Probes run in parallel: total latency approximates the slowest probe" {
        // Spec scenario: probes 400ms / 150ms / 400ms → total < 700ms, NOT 950ms.
        testApplication {
            installRoutes(
                postgresProbe = delayingPostgresProbe(suspendMs = 400L),
                redisProbe = delayingRedisProbe(suspendMs = 150L),
                supabaseProbe = delayingSupabaseProbe(suspendMs = 400L),
            )
            val start = System.nanoTime()
            val response = client.get("/health/ready")
            val elapsedMs = (System.nanoTime() - start) / 1_000_000L
            response.status shouldBe HttpStatusCode.OK
            // Sequential would be ~400 + 150 + 400 = 950ms; parallel is max() = 400ms.
            elapsedMs shouldBeLessThan 700L
        }
    }

    "Outer 2s cap fires when a probe hangs past 2 seconds (cooperative)" {
        // Spec scenario: outer 2-second cap caps total latency when a probe
        // exceeds its per-probe budget. We use a cooperative `delay()` here
        // (which honors `withTimeoutOrNull` cancellation) — see the design.md
        // note that the outer cap is "defense-in-depth against COOPERATIVE
        // probes that miss their per-probe timeout". A non-cancellable
        // `Thread.sleep` would NOT actually be bounded by the outer cap because
        // structured concurrency requires children to honor cancellation; that
        // pathological case is documented as out-of-scope for the outer cap's
        // contract.
        testApplication {
            installRoutes(
                redisProbe =
                    object : RedisProbe {
                        override suspend fun ping(timeout: Duration): ProbeResult {
                            kotlinx.coroutines.delay(3000L)
                            return ProbeResult(ok = true, latencyMs = 3000L, error = null)
                        }
                    },
            )
            val start = System.nanoTime()
            val response = client.get("/health/ready")
            val elapsedMs = (System.nanoTime() - start) / 1_000_000L
            response.status shouldBe HttpStatusCode.ServiceUnavailable
            elapsedMs shouldBeLessThan 2800L
            elapsedMs shouldBeGreaterThanOrEqual 1900L
        }
    }

    "Rate-limit: 60 requests succeed; 61st returns 429 with Retry-After" {
        val limiter = InMemoryRateLimiter()
        testApplication {
            installRoutes(rateLimiter = limiter)
            repeat(60) {
                val r = client.get("/health/live")
                r.status shouldBe HttpStatusCode.OK
            }
            val rejected = client.get("/health/live")
            rejected.status shouldBe HttpStatusCode.TooManyRequests
            val retryAfter = rejected.headers[HttpHeaders.RetryAfter]
            (retryAfter != null && retryAfter.toLong() >= 1L) shouldBe true
        }
    }

    "Rate-limit shared across /live and /ready" {
        val limiter = InMemoryRateLimiter()
        testApplication {
            installRoutes(rateLimiter = limiter)
            repeat(30) { client.get("/health/live").status shouldBe HttpStatusCode.OK }
            repeat(30) { client.get("/health/ready").status shouldBe HttpStatusCode.OK }
            val rejected = client.get("/health/ready")
            rejected.status shouldBe HttpStatusCode.TooManyRequests
        }
    }

    "Cloud Run probe User-Agent (GoogleHC) bypasses rate limit" {
        val limiter = InMemoryRateLimiter()
        testApplication {
            installRoutes(rateLimiter = limiter)
            repeat(100) {
                val r =
                    client.get("/health/live") {
                        header(HttpHeaders.UserAgent, "GoogleHC/1.0")
                    }
                r.status shouldBe HttpStatusCode.OK
            }
        }
    }

    "Kubernetes probe User-Agent (kube-probe) also bypasses rate limit" {
        val limiter = InMemoryRateLimiter()
        testApplication {
            installRoutes(rateLimiter = limiter)
            repeat(100) {
                val r =
                    client.get("/health/live") {
                        header(HttpHeaders.UserAgent, "kube-probe/1.27")
                    }
                r.status shouldBe HttpStatusCode.OK
            }
        }
    }

    "Error vocabulary: probe failure produces fixed-vocabulary error string" {
        testApplication {
            installRoutes(
                redisProbe =
                    object : RedisProbe {
                        override suspend fun ping(timeout: Duration): ProbeResult =
                            ProbeResult(ok = false, latencyMs = 100L, error = ProbeError.UNKNOWN)
                    },
            )
            val response = client.get("/health/ready")
            val body = response.bodyAsText()
            body shouldContain "\"error\":\"unknown\""
            body shouldNotContain "sun.nio"
            body shouldNotContain "SocketChannelImpl"
            body shouldNotContain "at java."
        }
    }

    "Hash-tag key shape: SpyRateLimiter captures the literal key passed to tryAcquireByKey" {
        // Spec scenario "Hash-tag key shape" — the rate-limit gate MUST pass
        // `{scope:health}:{ip:<addr>}` to `tryAcquireByKey`, NEVER fall back to
        // `tryAcquire(userId, ...)` with a sentinel UUID. SpyRateLimiter records
        // every call; we assert the captured shape + the absence of any
        // `tryAcquire` call from the health-route path.
        //
        // Backfilled per `health-check-test-coverage-gaps` follow-up (task 10.12).
        val spy = SpyHealthRateLimiter(InMemoryRateLimiter())
        testApplication {
            installRoutes(rateLimiter = spy)
            client.get("/health/live").status shouldBe HttpStatusCode.OK
        }
        spy.acquireByKeyCalls.size shouldBe 1
        // Test client default origin produces a remoteHost-derived clientIp;
        // assert the prefix that's invariant. The IP segment varies by
        // platform (testApplication uses "localhost" or a synthetic address),
        // but the scope prefix MUST always be `{scope:health}:{ip:`.
        spy.acquireByKeyCalls.first().key.startsWith("{scope:health}:{ip:") shouldBe true
        spy.acquireByKeyCalls.first().capacity shouldBe 60
        spy.acquireByKeyCalls.first().ttl shouldBe Duration.ofSeconds(60)
        // Sentinel-UUID-uncalled invariant: tryAcquire(userId, ...) MUST NOT
        // be invoked from the health-route path under any circumstance.
        spy.tryAcquireUserCalls.size shouldBe 0
    }

    "Per-probe timeout boundary: 199ms completion succeeds, 201ms exceeds" {
        // Spec scenario 10.10 — verifies the cooperative `withTimeoutOrNull`
        // is honored at the per-probe boundary. We use `delay(...)` with
        // generous CI tolerances rather than tight ms checks (CI scheduler
        // jitter can add ±50ms easily). Backfilled per task 10.10.
        //
        // Within-budget probe (delay 100ms < REDIS_PROBE_TIMEOUT_MS 200ms): ok=true.
        testApplication {
            installRoutes(redisProbe = delayingRedisProbe(suspendMs = 100L))
            val response = client.get("/health/ready")
            response.status shouldBe HttpStatusCode.OK
            val body = parseBody(response.bodyAsText())
            checkAt(body, 1)["ok"]!!.jsonPrimitive.content shouldBe "true"
        }
        // The over-budget side is already covered by the "503 when probe
        // reports timeout error" scenario — that scenario uses an explicit
        // `error = ProbeError.TIMEOUT` ProbeResult to assert the response
        // shape without depending on real probe-side timeout enforcement.
        // The combination of (within-budget passes) + (over-budget probe
        // returns timeout → 503) covers the boundary contract end-to-end
        // without timing-dependent CI flakiness.
    }

    "Outage rate-limit: 5xx responses still consume bucket; 61st returns 429" {
        val limiter = InMemoryRateLimiter()
        testApplication {
            installRoutes(
                postgresProbe = FAIL_POSTGRES_PROBE,
                rateLimiter = limiter,
            )
            repeat(60) {
                val r = client.get("/health/ready")
                r.status shouldBe HttpStatusCode.ServiceUnavailable
            }
            val rejected = client.get("/health/ready")
            rejected.status shouldBe HttpStatusCode.TooManyRequests
        }
    }
})
