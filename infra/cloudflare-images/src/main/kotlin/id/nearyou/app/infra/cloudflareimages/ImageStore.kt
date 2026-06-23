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

    /**
     * The single source of truth for an image's public delivery URL — the exact 4-segment
     * `<deliveryBaseUrl>/<accountHash>/<imageId>/public` shape. Used by BOTH the upload path
     * ([CloudflareImageStore.upload]) and the read-path surfacing (`:backend:ktor` timeline /
     * single-post / post-detail DTOs) so write and read never drift (`image-attached-posts`).
     * The exact path segments (a possible `/cdn-cgi/imagedelivery/` prefix per docs/02 §6
     * Pre-Phase-1 verification) are confirmed at launch while the feature is flag-dark; whatever
     * that shape becomes, both paths inherit it from this one builder.
     */
    fun deliveryUrl(imageId: String): String = "${deliveryBaseUrl.trimEnd('/')}/$accountHash/$imageId/public"
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
