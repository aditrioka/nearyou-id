package id.nearyou.app.image

import android.content.Context
import android.graphics.BitmapFactory
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android [ImagePicker] actual — the Android Photo Picker (`ActivityResultContracts.PickVisualMedia`,
 * image-only) routed through the [ImagePickerRequestBridge] Activity-result seam (set by
 * `MainActivity`), then a JPEG re-encode to ≤ 5 MB via [AndroidImageCompressor].
 *
 * The Photo Picker requires NO storage permission. Only platform wiring lives here (the picker call +
 * the decode/compress hop) — the compression algorithm itself is the testable
 * [AndroidImageCompressor]; no other business logic (docs/11 §2.5).
 */
class AndroidImagePicker(
    private val context: Context,
    private val bridge: ImagePickerRequestBridge,
) : ImagePicker {
    override suspend fun pick(): PickedImage? {
        // Image-only Photo Picker (single selection — PickVisualMedia is single by contract).
        val request =
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        val uri = bridge.launch(request) ?: return null

        // Decode + compress off the main thread (the picker callback resumes on Main): reading the
        // content stream and the Bitmap re-encode are blocking + allocation-heavy.
        return withContext(Dispatchers.IO) {
            val bitmap =
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream)
                } ?: return@withContext null
            val bytes = AndroidImageCompressor.compressToJpeg(bitmap)
            PickedImage(bytes = bytes, mime = AndroidImageCompressor.OUTPUT_MIME)
        }
    }
}
