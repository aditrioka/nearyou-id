package id.nearyou.app.image

import android.graphics.Bitmap
import android.graphics.Canvas
import java.io.ByteArrayOutputStream

/**
 * Pure, framework-only JPEG compressor for the Android [ImagePicker] actual — extracted from
 * [AndroidImagePicker] so the ≤ 5 MB post-condition is unit-testable in isolation (Robolectric can
 * drive a synthetic `Bitmap` without an Activity-result launcher; task 7.4). Holds NO picker /
 * Activity / Uri concern — only the `Bitmap` → bounded-JPEG transform.
 *
 * The guarantee (spec § "Returned image is within the size guard"): the returned bytes are ALWAYS
 * ≤ [MAX_BYTES]. Termination is structural: a quality step-down loop runs first; if the floor quality
 * still exceeds the cap, the bitmap is halved and the loop re-runs, and a halving sequence reaches a
 * 1×1 bitmap (whose JPEG is a few hundred bytes, far under 5 MB) in a bounded number of steps.
 */
internal object AndroidImageCompressor {
    /** The client-side ceiling — strictly below the backend's 5 MB guard so the server cap is never
     *  the first line of defense (spec). 5 MiB. */
    const val MAX_BYTES: Int = 5 * 1024 * 1024

    /** Longest-edge cap applied up front so a 100-MP photo is downscaled before the first encode
     *  (bounds peak memory + makes the first JPEG attempt land near the cap, not gigabytes over). */
    const val MAX_EDGE_PX: Int = 2048

    private const val QUALITY_START: Int = 90
    private const val QUALITY_FLOOR: Int = 40
    private const val QUALITY_STEP: Int = 10

    /** The output MIME — always JPEG (the re-encode format); an image content type on the backend allowlist. */
    const val OUTPUT_MIME: String = "image/jpeg"

    /**
     * Compress [source] to a JPEG whose size is ≤ [MAX_BYTES]. The source is first downscaled so its
     * longest edge is ≤ [MAX_EDGE_PX]; then a quality step-down (90 → 40 by 10) is attempted; if the
     * floor quality is still over the cap, the bitmap is halved and the whole step-down retried until
     * it fits. Returns the encoded bytes (never larger than the cap).
     */
    fun compressToJpeg(source: Bitmap): ByteArray {
        var working = downscaleToMaxEdge(source)
        while (true) {
            var quality = QUALITY_START
            while (quality >= QUALITY_FLOOR) {
                val bytes = encode(working, quality)
                if (bytes.size <= MAX_BYTES) return bytes
                quality -= QUALITY_STEP
            }
            // Floor quality still over the cap → halve the dimensions and retry the quality ladder.
            val halved = halve(working)
            // Defensive: a 1×1 bitmap can't shrink further; encode at the floor and return (its JPEG
            // is a few hundred bytes — structurally under the cap, so this is unreachable in practice).
            if (halved.width == working.width && halved.height == working.height) {
                return encode(working, QUALITY_FLOOR)
            }
            working = halved
        }
    }

    private fun encode(
        bitmap: Bitmap,
        quality: Int,
    ): ByteArray =
        ByteArrayOutputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            stream.toByteArray()
        }

    /** Scale [bitmap] down (preserving aspect ratio) so its longest edge is ≤ [MAX_EDGE_PX]; returns
     *  the source unchanged when it is already within bounds. */
    private fun downscaleToMaxEdge(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= MAX_EDGE_PX) return bitmap
        val scale = MAX_EDGE_PX.toFloat() / longest.toFloat()
        val targetWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return scaleTo(bitmap, targetWidth, targetHeight)
    }

    private fun halve(bitmap: Bitmap): Bitmap {
        val targetWidth = (bitmap.width / 2).coerceAtLeast(1)
        val targetHeight = (bitmap.height / 2).coerceAtLeast(1)
        return scaleTo(bitmap, targetWidth, targetHeight)
    }

    /** Render [bitmap] into a new ARGB_8888 bitmap of [width]×[height] via a Canvas. (Avoids
     *  `Bitmap.createScaledBitmap`, whose `filter` path is not implemented in Robolectric's shadow.) */
    private fun scaleTo(
        bitmap: Bitmap,
        width: Int,
        height: Int,
    ): Bitmap {
        val scaled = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(scaled)
        val srcRect = android.graphics.Rect(0, 0, bitmap.width, bitmap.height)
        val dstRect = android.graphics.Rect(0, 0, width, height)
        canvas.drawBitmap(bitmap, srcRect, dstRect, null)
        return scaled
    }
}
