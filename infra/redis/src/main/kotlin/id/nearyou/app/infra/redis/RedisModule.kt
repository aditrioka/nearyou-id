package id.nearyou.app.infra.redis

import io.lettuce.core.RedisClient
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
