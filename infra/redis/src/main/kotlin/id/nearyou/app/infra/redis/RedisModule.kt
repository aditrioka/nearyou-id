package id.nearyou.app.infra.redis

import io.lettuce.core.ClientOptions
import io.lettuce.core.RedisClient
import io.lettuce.core.protocol.ProtocolVersion
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Build the Lettuce [ClientOptions] this project uses for ALL Redis connections.
 *
 * Pinned to [ProtocolVersion.RESP2] to skip the `HELLO` handshake. Upstash
 * (the staging + production backend) silently drops the connection mid-handshake
 * when Lettuce's default `HELLO 3 AUTH default <pwd>` arrives — observed on
 * staging rev `nearyou-backend-staging-00034-lbw` (2026-04-25). `redis-cli` PINGs
 * succeed without `-2`/`-3` flags (legacy `AUTH` command, no HELLO) but fail
 * identically with either flag forced. Lettuce defaults to RESP3 + HELLO, so it
 * hits the failure path on every connect attempt → fail-soft path keeps
 * rate-limit checks open, defeating the rate limit entirely.
 *
 * Pinning RESP2 makes Lettuce send legacy `AUTH default <pwd>` instead of
 * HELLO + AUTH, matching what `redis-cli` does and what Upstash accepts.
 *
 * If a future Upstash account / Redis backend supports HELLO cleanly this can
 * be lifted; the integration test in `RedisRateLimiterIntegrationTest` runs
 * against `redis:7-alpine` standalone which accepts both protocol versions, so
 * the pin is invisible there.
 */
internal fun lettuceClientOptions(): ClientOptions =
    ClientOptions
        .builder()
        .protocolVersion(ProtocolVersion.RESP2)
        .build()

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
            RedisClient.create(url).apply { options = lettuceClientOptions() }
        }
    }

internal fun redisUrlSlot(env: String): String = if (env == "staging") "staging-redis-url" else "redis-url"

/**
 * Creates a [RedisRateLimiter] from a Redis URL string. Encapsulates the
 * `RedisClient.create(url)` call so callers in `:backend:ktor` don't need to
 * import the Lettuce SDK directly (preserves the "no vendor SDK outside
 * `:infra:*`" invariant).
 *
 * The returned limiter owns its [RedisClient]. Callers SHOULD register it as a
 * Koin singleton; the underlying connection is closed when the JVM exits.
 */
fun redisRateLimiterFromUrl(url: String): RedisRateLimiter =
    RedisRateLimiter(
        RedisClient.create(url).apply { options = lettuceClientOptions() },
    )
