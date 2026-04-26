package id.nearyou.app.health

import id.nearyou.app.common.clientIp
import id.nearyou.app.core.domain.health.PostgresProbe
import id.nearyou.app.core.domain.health.ProbeError
import id.nearyou.app.core.domain.health.ProbeResult
import id.nearyou.app.core.domain.health.RedisProbe
import id.nearyou.app.core.domain.health.SupabaseRealtimeProbe
import id.nearyou.app.core.domain.ratelimit.RateLimiter
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import org.koin.ktor.ext.inject
import org.slf4j.LoggerFactory
import java.time.Duration

private val logger = LoggerFactory.getLogger("HealthRoutes")

const val POSTGRES_PROBE_TIMEOUT_MS: Long = 500L
const val REDIS_PROBE_TIMEOUT_MS: Long = 200L
const val SUPABASE_PROBE_TIMEOUT_MS: Long = 500L
const val READY_OUTER_CAP_MS: Long = 2000L
const val HEALTH_RATE_LIMIT_CAPACITY: Int = 60
val HEALTH_RATE_LIMIT_WINDOW: Duration = Duration.ofSeconds(60)

private const val POSTGRES_CHECK_NAME = "postgres"
private const val REDIS_CHECK_NAME = "redis"
private const val SUPABASE_CHECK_NAME = "supabase_realtime"

private val PROBE_USER_AGENT_BYPASS = Regex("^(GoogleHC|kube-probe)/")

@Serializable
data class HealthCheck(
    val name: String,
    val ok: Boolean,
    val latencyMs: Long,
    val error: String? = null,
)

@Serializable
data class HealthReadyResponse(
    val status: String,
    val checks: List<HealthCheck>,
)

fun Application.healthRoutes() {
    val postgresProbe by inject<PostgresProbe>()
    val redisProbe by inject<RedisProbe>()
    val supabaseProbe by inject<SupabaseRealtimeProbe>()
    val rateLimiter by inject<RateLimiter>()

    routing {
        installHealthRoutes(postgresProbe, redisProbe, supabaseProbe, rateLimiter)
    }
}

/**
 * Direct route installation for tests that wire dependencies explicitly without
 * going through Koin. Production callers use [healthRoutes] which pulls
 * dependencies from the application Koin module.
 */
fun Routing.installHealthRoutes(
    postgresProbe: PostgresProbe,
    redisProbe: RedisProbe,
    supabaseProbe: SupabaseRealtimeProbe,
    rateLimiter: RateLimiter,
) {
    healthLiveRoute(rateLimiter)
    healthReadyRoute(postgresProbe, redisProbe, supabaseProbe, rateLimiter)
}

private fun Routing.healthLiveRoute(rateLimiter: RateLimiter) {
    get("/health/live") {
        if (!checkRateLimit(rateLimiter)) return@get
        // Pure process-alive signal. No probes, no I/O.
        call.respond(HttpStatusCode.OK, "OK")
    }
}

private fun Routing.healthReadyRoute(
    postgresProbe: PostgresProbe,
    redisProbe: RedisProbe,
    supabaseProbe: SupabaseRealtimeProbe,
    rateLimiter: RateLimiter,
) {
    get("/health/ready") {
        if (!checkRateLimit(rateLimiter)) return@get

        val checks = runReadyProbes(postgresProbe, redisProbe, supabaseProbe)
        val allOk = checks.all { it.ok }
        val response =
            HealthReadyResponse(
                status = if (allOk) "ready" else "degraded",
                checks = checks,
            )
        if (allOk) {
            call.respond(HttpStatusCode.OK, response)
        } else {
            call.respond(HttpStatusCode.ServiceUnavailable, response)
        }
    }
}

/**
 * Runs the three dependency probes in parallel via `coroutineScope { ... awaitAll() }`,
 * with an outer 2-second cap as defense-in-depth (any probe that ignores its own
 * cooperative timeout — e.g., a non-cancellable JDBC blocking call — is bounded
 * by the outer cap). Returns the checks in deterministic declaration order
 * regardless of completion order: postgres, redis, supabase_realtime.
 */
private suspend fun runReadyProbes(
    postgresProbe: PostgresProbe,
    redisProbe: RedisProbe,
    supabaseProbe: SupabaseRealtimeProbe,
): List<HealthCheck> {
    val outcomes =
        withTimeoutOrNull(READY_OUTER_CAP_MS) {
            coroutineScope {
                listOf(
                    async { postgresProbe.probe(Duration.ofMillis(POSTGRES_PROBE_TIMEOUT_MS)).toCheck(POSTGRES_CHECK_NAME) },
                    async { redisProbe.ping(Duration.ofMillis(REDIS_PROBE_TIMEOUT_MS)).toCheck(REDIS_CHECK_NAME) },
                    async { supabaseProbe.ping(Duration.ofMillis(SUPABASE_PROBE_TIMEOUT_MS)).toCheck(SUPABASE_CHECK_NAME) },
                ).awaitAll()
            }
        }
    return if (outcomes != null) {
        outcomes
    } else {
        // Outer cap fired. Mark all probes timed-out — `withTimeoutOrNull`
        // cancelled their scope; we don't know which (if any) had completed.
        listOf(
            HealthCheck(POSTGRES_CHECK_NAME, ok = false, latencyMs = READY_OUTER_CAP_MS, error = ProbeError.TIMEOUT),
            HealthCheck(REDIS_CHECK_NAME, ok = false, latencyMs = READY_OUTER_CAP_MS, error = ProbeError.TIMEOUT),
            HealthCheck(SUPABASE_CHECK_NAME, ok = false, latencyMs = READY_OUTER_CAP_MS, error = ProbeError.TIMEOUT),
        )
    }
}

private fun ProbeResult.toCheck(name: String): HealthCheck = HealthCheck(name = name, ok = ok, latencyMs = latencyMs, error = error)

/**
 * Apply the per-IP anti-scrape rate limit. Bypasses Cloud Run native probe
 * traffic identified by `User-Agent: ^(GoogleHC|kube-probe)/`. On `RateLimited`,
 * responds 429 + `Retry-After` and returns false (caller MUST `return@get`).
 */
private suspend fun io.ktor.server.routing.RoutingContext.checkRateLimit(rateLimiter: RateLimiter): Boolean {
    if (isProbeUserAgent(call)) return true
    val key = "{scope:health}:{ip:${call.clientIp}}"
    val outcome =
        rateLimiter.tryAcquireByKey(
            key = key,
            capacity = HEALTH_RATE_LIMIT_CAPACITY,
            ttl = HEALTH_RATE_LIMIT_WINDOW,
        )
    return when (outcome) {
        is RateLimiter.Outcome.Allowed -> true
        is RateLimiter.Outcome.RateLimited -> {
            call.response.header(HttpHeaders.RetryAfter, outcome.retryAfterSeconds.toString())
            call.respond(HttpStatusCode.TooManyRequests, "Too Many Requests")
            false
        }
    }
}

private fun isProbeUserAgent(call: ApplicationCall): Boolean {
    val ua = call.request.headers[HttpHeaders.UserAgent] ?: return false
    return PROBE_USER_AGENT_BYPASS.containsMatchIn(ua)
}
