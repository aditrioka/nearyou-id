package id.nearyou.app.infra.cloudflareimages

import io.ktor.client.engine.HttpClientEngine

/** A stored image: the Cloudflare image id (also persisted to `posts.image_id`) + its delivery URL. */
data class StoredImage(
    val imageId: String,
    val deliveryUrl: String,
)

/**
 * Cloudflare Images config. `apiToken` is secret (`secretKey(env, "cloudflare-images-api-token")`);
 * `accountId` + `accountHash` are non-sensitive (dashboard-visible, like slot names per the
 * public-repo posture); `deliveryBaseUrl` is the `img` custom subdomain (Open Decision #32).
 */
data class CloudflareImagesConfig(
    val apiToken: String,
    val accountId: String,
    val accountHash: String,
    val deliveryBaseUrl: String,
) {
    fun isComplete(): Boolean =
        apiToken.isNotBlank() && accountId.isNotBlank() &&
            accountHash.isNotBlank() && deliveryBaseUrl.isNotBlank()
}

/**
 * Image storage port. The single vendor implementation ([CloudflareImageStore]) lives in
 * this module; `:backend:ktor` depends only on this interface (no Cloudflare/Ktor-client
 * symbol leaks — Cloudflare has no official JVM SDK, so the impl uses the Ktor client).
 *
 * [isConfigured] reports whether the Cloudflare credentials are present; an unconfigured
 * environment fails soft (the FCM / RevenueCat precedent) — the caller checks [isConfigured]
 * and returns 503 rather than calling [upload].
 */
interface ImageStore {
    fun isConfigured(): Boolean

    /** Server-side upload of the moderated bytes to Cloudflare Images. Gate on [isConfigured] first. */
    suspend fun upload(
        bytes: ByteArray,
        contentType: String,
        fileName: String,
    ): StoredImage
}

/** Fail-soft binding when Cloudflare Images is unconfigured. */
object NoOpImageStore : ImageStore {
    override fun isConfigured(): Boolean = false

    override suspend fun upload(
        bytes: ByteArray,
        contentType: String,
        fileName: String,
    ): StoredImage = throw IllegalStateException("ImageStore is not configured — gate on isConfigured()")
}

/**
 * Factory. Returns the real Cloudflare-backed store when [config] is complete, else the
 * fail-soft [NoOpImageStore]. [engine] is injectable for tests (MockEngine); production passes null.
 */
fun imageStore(
    config: CloudflareImagesConfig?,
    engine: HttpClientEngine? = null,
): ImageStore =
    if (config == null || !config.isComplete()) {
        NoOpImageStore
    } else {
        CloudflareImageStore(config, engine)
    }
