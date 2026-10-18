package id.nearyou.app.infra.r2

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
