package id.nearyou.app.infra.r2

import kotlin.time.Duration

/**
 * Vendor-neutral object-storage port (design D1 of `account-data-export`).
 *
 * This is the **portable contract**: no AWS-SDK-for-Kotlin type (`aws.sdk.kotlin.*`,
 * `aws.smithy.kotlin.*`) appears in any signature here, so `:backend:ktor` depends on
 * this interface alone — the single Cloudflare-R2-backed implementation ([R2ObjectStore])
 * and the AWS SDK stay fenced inside `:infra:r2` (the no-vendor-SDK-outside-`:infra:*`
 * invariant). Swapping R2 for any other S3-compatible store touches only this module.
 *
 * [isConfigured] reports whether the R2 credentials are present. An unconfigured
 * environment fails soft (the `NoOpImageModerator` / `NoOpImageStore` precedent): the
 * factory binds [NoOpObjectStore], application boot does not fail, and the consuming
 * worker degrades gracefully (mapping the unconfigured outcome to a soft `failed`,
 * never a crash). Callers MAY pre-gate on [isConfigured], OR rely on the fail-soft
 * behavior directly: on [NoOpObjectStore] [put] / [delete] no-op and [presignedGetUrl]
 * throws [ObjectStoreUnconfiguredException] (the sole consumer takes the latter path —
 * it catches the exception and maps it to a soft `failed`, no pre-gate).
 */
interface ObjectStore {
    /** Whether the R2 credentials are present (real store) vs. fail-soft no-op. */
    fun isConfigured(): Boolean

    /** Uploads [bytes] under [key] with the given [contentType]. No-ops on the unconfigured no-op store. */
    suspend fun put(
        key: String,
        bytes: ByteArray,
        contentType: String,
    )

    /**
     * Returns a time-limited, signed GET URL (a bearer capability) for [key], valid for
     * [ttl]. R2 supports a presigned-URL expiry of 1s–7days. On the no-op store this
     * throws [ObjectStoreUnconfiguredException] (there is no URL to hand back).
     */
    suspend fun presignedGetUrl(
        key: String,
        ttl: Duration,
    ): String

    /** Deletes the object at [key] (no-op when unconfigured). */
    suspend fun delete(key: String)
}

/**
 * Surfaced by [NoOpObjectStore.presignedGetUrl] when R2 is unconfigured, so the consuming
 * worker maps it to a soft `failed` outcome instead of an unhandled crash at boot. A
 * defined, catchable type — distinct from a generic [IllegalStateException] — so callers
 * can distinguish "storage unconfigured" from other failures.
 */
class ObjectStoreUnconfiguredException(
    message: String = "ObjectStore is not configured — gate on isConfigured()",
) : RuntimeException(message)
