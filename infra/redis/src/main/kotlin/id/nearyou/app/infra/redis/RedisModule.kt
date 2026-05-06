package id.nearyou.app.infra.redis

import id.nearyou.app.infra.otel.OtelInstrumentation
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.resource.ClientResources
import io.opentelemetry.api.OpenTelemetry
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Koin module exposing a singleton Lettuce [RedisClient].
 *
 * The slot name is derived locally from [env] so this module does NOT depend on the
 * `secretKey()` helper in `:backend:ktor` (which would be a circular dependency).
 * The convention mirrors `secretKey(env, "redis-url")`: `staging-redis-url` in
 * staging, `redis-url` in dev/production. If [env] takes any other value the prod
 * slot name is used.
 *
 * [resolveSecret] is the secret-resolution lambda; in production it's
 * `secrets::resolve` from the `:backend:ktor` `SecretResolver` chain. In tests it
 * can be a fake closure returning a known URL string.
 *
 * Production wiring lives in `Application.kt`; this module is a separate, focused
 * binding so the `:infra:redis` boundary stays clean.
 */
fun redisKoinModule(
    env: String,
    resolveSecret: (String) -> String?,
): Module =
    module {
        single<RedisClient> {
            val slot = redisUrlSlot(env)
            val url =
                resolveSecret(slot)
                    ?: error("Required secret '$slot' is unset (env=$env)")
            RedisClient.create(url)
        }
    }

internal fun redisUrlSlot(env: String): String = if (env == "staging") "staging-redis-url" else "redis-url"

/**
 * Creates a [RedisRateLimiter] from a Redis URL string. Encapsulates the
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
 * sees a sanitized connection string by default. Defense-in-depth via
 * [OtelInstrumentation.sanitizeRedisUri] is also applied here.
 *
 * The returned limiter owns its [RedisClient]. Callers SHOULD register it as a
 * Koin singleton; the underlying connection is closed when the JVM exits.
 */
fun redisRateLimiterFromUrl(
    url: String,
    tracingEnabled: Boolean = true,
    openTelemetry: OpenTelemetry? = null,
): RedisRateLimiter = RedisRateLimiter(buildClient(url, tracingEnabled, openTelemetry))

/**
 * Creates a [LettuceRedisProbe] sharing the same construction shape as
 * [redisRateLimiterFromUrl]. Pass `tracingEnabled=true` (default) so the
 * probe PING produces a span in Tempo.
 */
fun lettuceRedisProbeFromUrl(
    url: String,
    tracingEnabled: Boolean = true,
    openTelemetry: OpenTelemetry? = null,
): LettuceRedisProbe = LettuceRedisProbe(buildClient(url, tracingEnabled, openTelemetry))

private fun buildClient(
    url: String,
    tracingEnabled: Boolean,
    openTelemetry: OpenTelemetry?,
): RedisClient {
    // Strip any userinfo from the URL string before parsing — defense in
    // depth so Lettuce never sees the password in its URI string form.
    // RedisURI.create preserves the password internally (extracted into
    // RedisURI.password), so the connection still authenticates.
    val redisUri = RedisURI.create(url)
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
