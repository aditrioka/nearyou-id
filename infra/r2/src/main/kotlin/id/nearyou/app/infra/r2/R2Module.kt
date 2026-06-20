package id.nearyou.app.infra.r2

import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Returns the real R2-backed [ObjectStore] when [config] is complete, else the fail-soft
 * [NoOpObjectStore]. The vendor-neutral seam: callers depend on [ObjectStore] only.
 *
 * Mirrors the `:infra:cloudflare-images` `imageStore(config)` factory shape — production
 * wiring in `Application.kt` builds the [R2Config] from the `SecretResolver` chain and
 * passes it here; an incomplete config (any blank credential) yields the no-op so boot
 * never fails on un-provisioned R2.
 */
fun objectStore(config: R2Config?): ObjectStore =
    if (config == null || !config.isComplete()) {
        NoOpObjectStore
    } else {
        R2ObjectStore(config)
    }

/**
 * Koin module exposing a singleton [ObjectStore], bound to [R2ObjectStore] when the R2
 * secret slots are present, else [NoOpObjectStore] (the fail-soft, boot-safe default).
 *
 * Like `:infra:redis`'s `redisKoinModule`, the slot prefix is derived locally from [env]
 * (via [R2Config.fromSecrets] → [r2SecretSlot]) so this module does NOT depend on the
 * `secretKey()` helper in `:backend:ktor` (which would be a circular dependency).
 * [resolveSecret] is the secret-resolution lambda; production passes `secrets::resolve`
 * from the `:backend:ktor` `SecretResolver` chain, tests pass a fake closure.
 *
 * Production may instead wire `objectStore(...)` inline in `Application.kt` (the
 * cloudflare-images precedent); this module is offered for the same focused-binding DI
 * style as `redisKoinModule`.
 */
fun objectStoreModule(
    env: String,
    resolveSecret: (String) -> String?,
): Module =
    module {
        single<ObjectStore> {
            objectStore(R2Config.fromSecrets(env, resolveSecret))
        }
    }
