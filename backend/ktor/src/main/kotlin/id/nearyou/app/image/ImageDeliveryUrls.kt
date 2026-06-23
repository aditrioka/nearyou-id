package id.nearyou.app.image

import id.nearyou.app.infra.cloudflareimages.CloudflareImagesConfig

/**
 * Read-path image delivery-URL builder (`image-attached-posts`). Maps a raw `posts.image_id` to its
 * public Cloudflare delivery URL via the SAME [CloudflareImagesConfig.deliveryUrl] builder the upload
 * path uses, so the write and read paths can never emit a divergent URL shape (round-1 review B1).
 *
 * Returns `null` for a text-only post (`null` imageId) OR an unconfigured/incomplete environment
 * (no `accountHash`) — fail-soft: a missing config yields no URL rather than a broken one. In practice
 * a post only carries an `image_id` when the upload env was configured, so a non-null id normally maps
 * to a real URL; the null-on-unconfigured branch only guards a misconfigured read replica.
 */
fun interface ImageDeliveryUrls {
    /** Public delivery URL for [imageId], or `null` when [imageId] is null or the env is unconfigured. */
    fun forImage(imageId: String?): String?
}

/** Builds an [ImageDeliveryUrls] bound to [config]; an incomplete/unconfigured config yields null URLs. */
fun imageDeliveryUrls(config: CloudflareImagesConfig?): ImageDeliveryUrls =
    ImageDeliveryUrls { imageId ->
        if (imageId != null && config != null && config.isComplete()) config.deliveryUrl(imageId) else null
    }
