package id.nearyou.app.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityOptionsCompat
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Robolectric coverage of the Android [ImagePicker] actual (task 7.4). Two invariants the spec
 * pins for the picker seam:
 *
 *  1. **Cancelled selection → `null`** (spec § "Cancelled selection yields null"): the
 *     `PickVisualMedia` callback delivering a `null` `Uri` (the user dismissed the Photo Picker)
 *     resolves [ImagePickerRequestBridge.launch] to `null` — so [AndroidImagePicker.pick] yields
 *     `null` and no upload is attempted. Driven through the bridge (the Activity-result seam) with a
 *     no-op launcher, since the real Photo Picker can't run headless.
 *  2. **The ≤ 5 MB + image-content-type post-condition** (spec § "Returned image is within the size guard"):
 *     [AndroidImageCompressor] re-encodes a deliberately-huge synthetic `Bitmap` (whose raw ARGB
 *     footprint is far over 5 MB) down to a JPEG ≤ 5 MB with the `image/jpeg` MIME. The compressor is
 *     unit-tested in isolation (extracted from the actual precisely so the loop is testable without an
 *     Activity-result launcher).
 *
 * Robolectric provides real `Bitmap`/`Canvas`/JPEG-encode shadows, so the compression assertion is a
 * true behavioral check, not a stub.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AndroidImagePickerTest {
    // ---- 1. cancel → null (the Activity-result seam delivers a null Uri) ----

    @Test
    fun pick_returnsNull_whenLauncherCallbackDeliversNullUri() =
        runTest {
            val bridge = ImagePickerRequestBridge()
            bridge.launcher = NoOpPickLauncher()

            // launch() parks a CompletableDeferred and "opens" the (no-op) picker; resolve it with a
            // null Uri exactly as MainActivity's PickVisualMedia callback does on a cancel.
            val parked = async { bridge.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            advanceUntilIdle()
            bridge.onResult(null)

            assertNull(parked.await(), "a cancelled Photo Picker selection (null Uri) must resolve to null")
        }

    @Test
    fun launch_returnsNull_whenNoLauncherRegistered() =
        runTest {
            // No foreground Activity → no launcher → the bridge resolves to null immediately (no upload).
            val result = ImagePickerRequestBridge().launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            assertNull(result, "with no registered launcher the bridge must resolve to null")
        }

    // ---- 2. compression post-condition: ≤ 5 MB JPEG, image content-type mime ----

    @Test
    fun compressor_reEncodesAnOversizeBitmapToUnderFiveMb() {
        // A 5000×4000 ARGB_8888 bitmap is ~80 MB raw — well over the 5 MB cap, forcing the
        // downscale + quality step-down. Filled with high-entropy noise so JPEG can't trivially
        // crush it to nothing (a worst-case-ish payload for the size guard).
        val huge = noiseBitmap(width = 5000, height = 4000)

        val bytes = AndroidImageCompressor.compressToJpeg(huge)

        assertTrue(
            bytes.size <= AndroidImageCompressor.MAX_BYTES,
            "compressed JPEG must be ≤ 5 MB but was ${bytes.size} bytes",
        )
        assertTrue(bytes.isNotEmpty(), "compressed JPEG must not be empty")
        assertTrue(isJpegMagic(bytes), "output must be JPEG (FF D8 FF magic)")
        assertEquals("image/jpeg", AndroidImageCompressor.OUTPUT_MIME, "the output MIME must be an image/* type")
        assertTrue(AndroidImageCompressor.OUTPUT_MIME.startsWith("image/"), "the output MIME must be image/*")
    }

    @Test
    fun compressor_leavesASmallImageWellUnderTheCap() {
        // A small image is already under the cap — the loop returns on the first (highest-quality) encode.
        val small = noiseBitmap(width = 64, height = 64)
        val bytes = AndroidImageCompressor.compressToJpeg(small)
        assertTrue(bytes.size <= AndroidImageCompressor.MAX_BYTES, "a small image stays ≤ 5 MB")
        assertTrue(isJpegMagic(bytes), "output must be JPEG")
    }

    private fun noiseBitmap(
        width: Int,
        height: Int,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        val random = Random(42)
        // Paint a coarse grid of random-colored cells — cheap, but gives JPEG real high-frequency
        // content so it cannot compress a flat color down to a few KB.
        val cell = 16
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                paint.color = Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256))
                canvas.drawRect(
                    x.toFloat(),
                    y.toFloat(),
                    (x + cell).toFloat(),
                    (y + cell).toFloat(),
                    paint,
                )
                x += cell
            }
            y += cell
        }
        return bitmap
    }

    private fun isJpegMagic(bytes: ByteArray): Boolean =
        bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte()

    /** A no-op [ActivityResultLauncher] for the bridge handshake — `launch` does nothing (the test
     *  drives the result via [ImagePickerRequestBridge.onResult] directly). */
    private class NoOpPickLauncher : ActivityResultLauncher<PickVisualMediaRequest>() {
        override fun launch(
            input: PickVisualMediaRequest,
            options: ActivityOptionsCompat?,
        ) {
            // no-op: the picker UI cannot run headless; the test completes the deferred via onResult.
        }

        override fun unregister() = Unit

        override val contract: ActivityResultContract<PickVisualMediaRequest, *>
            get() = ActivityResultContracts.PickVisualMedia()
    }
}
