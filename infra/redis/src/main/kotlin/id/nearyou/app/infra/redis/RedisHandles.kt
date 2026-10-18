package id.nearyou.app.infra.redis

import id.nearyou.app.infra.otel.OtelInstrumentation
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.resource.ClientResources
import io.opentelemetry.api.OpenTelemetry

/**
 * All three Redis consumers built on ONE shared [RedisClient] (one Netty
 * event-loop group, one connection budget against Upstash). This is the only
 * public factory: the former per-consumer `*FromUrl` factories each built a
 * private client and had no caller left (2026-06-10 audit: Application.kt
 * previously built three clients per instance).
 */
class RedisHandles internal constructor(client: RedisClient) {
    val rateLimiter: RedisRateLimiter = RedisRateLimiter(client)
    val probe: LettuceRedisProbe = LettuceRedisProbe(client)
    val stringCache: LettuceRedisStringCache = LettuceRedisStringCache(client)
}

/**
 * Builds the shared-client [RedisHandles] from a Redis URL. Encapsulates the
 * `RedisClient.create(...)` call so callers in `:backend:ktor` don't need to
 * import the Lettuce SDK directly (preserves the "no vendor SDK outside
 * `:infra:*`" invariant).
 *
 * The optional [openTelemetry] parameter lets `:backend:ktor` enable Lettuce
 * OTel tracing by passing an OTel instance (typically `null` is fine because
 * `:infra:otel`'s [OtelInstrumentation.lettuceClientResources] internally
 * uses the global slot via [io.opentelemetry.api.GlobalOpenTelemetry.get]).
 * When [tracingEnabled] is false (e.g., in tests that don't bootstrap OTel),
 * the bare Lettuce client without tracing is used.
 *
 * The URL is parsed via [RedisURI.create] so the password lives in
 * `RedisURI.password` (not the URI string) — Lettuce's OTel telemetry layer
 * sees a sanitized connection string by default. ([OtelInstrumentation.sanitizeRedisUri]
 * exists as a helper for any future code path that must log a URI string; it is
 * deliberately NOT in this construction path, which never logs the URI.)
 *
 * The returned handles share one [RedisClient]; `Application.kt` builds them once
 * per process, and the underlying connection is closed when the JVM exits.
 */
fun redisHandlesFromUrl(
    url: String,
    tracingEnabled: Boolean = true,
    openTelemetry: OpenTelemetry? = null,
): RedisHandles = RedisHandles(buildClient(url, tracingEnabled, openTelemetry))

private val COMMAND_TIMEOUT: java.time.Duration = java.time.Duration.ofSeconds(2)

private fun buildClient(
    url: String,
    tracingEnabled: Boolean,
    openTelemetry: OpenTelemetry?,
): RedisClient {
    // RedisURI.create extracts the password out of the URI string into
    // RedisURI.password, so Lettuce's telemetry/logging see a sanitized
    // connection string while the connection still authenticates.
    val redisUri =
        RedisURI.create(url).apply {
            // Default command timeout is 60 s — a black-holed Redis would pin
            // /health/ready (and every limiter call) for a minute. All real ops
            // here are sub-millisecond; 2 s is generous, and the rate limiters'
            // fail-soft catch handles the RedisCommandTimeoutException.
            timeout = COMMAND_TIMEOUT
        }
    return if (tracingEnabled) {
        val resources: ClientResources =
            if (openTelemetry != null) {
                OtelInstrumentation.lettuceClientResources(openTelemetry)
            } else {
                OtelInstrumentation.lettuceClientResources()
            }
        RedisClient.create(resources, redisUri)
    } else {
        RedisClient.create(redisUri)
    }
}
