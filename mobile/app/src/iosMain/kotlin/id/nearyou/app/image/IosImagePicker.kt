package id.nearyou.app.image

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.CoreGraphics.CGFloat
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSData
import platform.Foundation.NSItemProvider
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIApplication
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.darwin.NSObject
import kotlin.coroutines.resume

/**
 * iOS [ImagePicker] actual — `PHPickerViewController` (images-only, single selection) presented from
 * the key-window top view controller (mirroring [id.nearyou.app.auth.GoogleSignInClient]'s
 * `topViewController()`), then an ImageIO/`UIImage` downscale + JPEG re-encode to ≤ 5 MB.
 *
 * Main-confined (mirrors [id.nearyou.app.location.IosLocationPermissionController]): `PHPickerViewController`
 * presents UIKit UI and the delegate callback arrives on the main run loop. The compression
 * (`UIImageJPEGRepresentation` step-down) is CPU-bound, so it hops to [Dispatchers.Default] AFTER the
 * picker resolves. Only platform wiring lives here (docs/11 §2.5).
 *
 * `PHPickerViewController` needs NO Photo Library permission (the picker runs out-of-process) — so
 * [pick] never triggers an authorization prompt.
 */
@OptIn(ExperimentalForeignApi::class)
class IosImagePicker : ImagePicker {
    override suspend fun pick(): PickedImage? {
        val data = presentPickerAndLoadData() ?: return null
        // Compression is CPU-bound; run it off the main thread (the picker already resolved on Main).
        val jpeg =
            withContext(Dispatchers.Default) {
                IosImageCompressor.compressToJpeg(data)
            } ?: return null
        return PickedImage(bytes = jpeg.toByteArray(), mime = IosImageCompressor.OUTPUT_MIME)
    }

    /**
     * Present the picker on the main thread and suspend until the user picks one image (resolving to
     * its raw [NSData]) or cancels (resolving to `null`). The `PHPickerResult`'s `NSItemProvider` is
     * asked for the image data representation.
     */
    private suspend fun presentPickerAndLoadData(): NSData? =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val presenter = topViewController()
                if (presenter == null) {
                    if (cont.isActive) cont.resume(null)
                    return@suspendCancellableCoroutine
                }

                val configuration = PHPickerConfiguration()
                configuration.selectionLimit = 1
                configuration.filter = PHPickerFilter.imagesFilter()

                lateinit var delegate: PickerDelegate
                delegate =
                    PickerDelegate { itemProvider ->
                        retainedPickerDelegates.remove(delegate)
                        if (itemProvider == null) {
                            if (cont.isActive) cont.resume(null)
                            return@PickerDelegate
                        }
                        // loadDataRepresentation runs async on a private queue → resume from its completion.
                        itemProvider.loadDataRepresentationForTypeIdentifier(IMAGE_UTI) { loaded, _ ->
                            if (cont.isActive) cont.resume(loaded)
                        }
                    }
                retainedPickerDelegates.add(delegate)

                val picker = PHPickerViewController(configuration = configuration)
                picker.delegate = delegate
                cont.invokeOnCancellation {
                    retainedPickerDelegates.remove(delegate)
                }
                presenter.presentViewController(picker, animated = true, completion = null)
            }
        }

    private companion object {
        /** UTType identifier for "any image" — the public.image conformance the picker loads. */
        const val IMAGE_UTI: String = "public.image"
    }
}

/**
 * `PHPickerViewControllerDelegate` that dismisses the picker and forwards the first picked result's
 * `NSItemProvider` (or `null` on cancel / empty selection) to [onPicked]. Retained strongly via
 * [retainedPickerDelegates] because `PHPickerViewController.delegate` is `weak` (mirrors the
 * `IosLocationPermissionController` retained-delegate set).
 */
@OptIn(ExperimentalForeignApi::class)
private class PickerDelegate(
    private val onPicked: (NSItemProvider?) -> Unit,
) : NSObject(), PHPickerViewControllerDelegateProtocol {
    override fun picker(
        picker: PHPickerViewController,
        didFinishPicking: List<*>,
    ) {
        picker.dismissViewControllerAnimated(true, completion = null)
        @Suppress("UNCHECKED_CAST")
        val results = didFinishPicking as List<PHPickerResult>
        val provider = results.firstOrNull()?.itemProvider
        onPicked(provider)
    }
}

/** Strong references to in-flight picker delegates (PHPickerViewController keeps its delegate weak). */
@OptIn(ExperimentalForeignApi::class)
private val retainedPickerDelegates = mutableSetOf<PickerDelegate>()

/**
 * Walk from the key window's root view controller down the presented-VC chain to the topmost
 * presenter (sibling of [id.nearyou.app.auth.GoogleSignInClient]'s `topViewController`).
 */
@OptIn(ExperimentalForeignApi::class)
private fun topViewController(): UIViewController? {
    val windows = UIApplication.sharedApplication.windows
    val keyWindow =
        windows.firstOrNull { (it as? UIWindow)?.isKeyWindow() == true } as? UIWindow
            ?: windows.firstOrNull() as? UIWindow
    var top = keyWindow?.rootViewController
    while (top?.presentedViewController != null) {
        top = top.presentedViewController
    }
    return top
}

/** Copy an [NSData]'s bytes into a Kotlin [ByteArray]. */
@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val length = this.length.toInt()
    if (length == 0) return ByteArray(0)
    return this.bytes!!.reinterpret<ByteVar>().readBytes(length)
}

/**
 * Pure ImageIO/`UIImage` JPEG compressor for the iOS [ImagePicker] actual — downscale (longest edge ≤
 * [MAX_EDGE_PX]) then a quality step-down to ≤ [MAX_BYTES]. Separated from the picker so the transform
 * is reasoned about independently (it is exercised by the simulator link/test gate, not Robolectric).
 */
@OptIn(ExperimentalForeignApi::class)
internal object IosImageCompressor {
    /** Strictly below the backend's 5 MB cap so the server guard is never the first line of defense. */
    const val MAX_BYTES: Long = 5L * 1024L * 1024L
    const val MAX_EDGE_PX: Double = 2048.0
    const val OUTPUT_MIME: String = "image/jpeg"

    private const val QUALITY_START: Double = 0.9
    private const val QUALITY_FLOOR: Double = 0.4
    private const val QUALITY_STEP: Double = 0.1
    private const val QUALITY_EPSILON: Double = 1e-6

    /**
     * Decode [data] to a `UIImage`, downscale so its longest edge is ≤ [MAX_EDGE_PX], then encode JPEG
     * at descending quality (0.9 → 0.4) until ≤ [MAX_BYTES]; if the floor quality still exceeds the cap,
     * halve the dimensions and retry. Returns the encoded [NSData] (≤ cap), or `null` if the bytes are
     * not a decodable image.
     */
    fun compressToJpeg(data: NSData): NSData? {
        val decoded = UIImage.imageWithData(data) ?: return null
        var working = downscaleToMaxEdge(decoded)
        while (true) {
            var quality = QUALITY_START
            while (quality >= QUALITY_FLOOR - QUALITY_EPSILON) {
                val encoded = UIImageJPEGRepresentation(working, quality)
                if (encoded != null && encoded.length.toLong() <= MAX_BYTES) return encoded
                quality -= QUALITY_STEP
            }
            val halved = halve(working) ?: return UIImageJPEGRepresentation(working, QUALITY_FLOOR)
            working = halved
        }
    }

    private fun downscaleToMaxEdge(image: UIImage): UIImage {
        val (width, height) =
            image.size.useContents { width to height }
        val longest = maxOf(width, height)
        if (longest <= MAX_EDGE_PX) return image
        val scale = MAX_EDGE_PX / longest
        return scaleTo(image, width * scale, height * scale) ?: image
    }

    private fun halve(image: UIImage): UIImage? {
        val (width, height) =
            image.size.useContents { width to height }
        if (width <= 1.0 && height <= 1.0) return null
        return scaleTo(image, (width / 2.0).coerceAtLeast(1.0), (height / 2.0).coerceAtLeast(1.0))
    }

    /** Redraw [image] into a [targetWidth]×[targetHeight] bitmap context at scale 1 (so pixel size,
     *  not point size, is bounded). */
    private fun scaleTo(
        image: UIImage,
        targetWidth: CGFloat,
        targetHeight: CGFloat,
    ): UIImage? {
        val size = CGSizeMake(targetWidth, targetHeight)
        UIGraphicsBeginImageContextWithOptions(size, opaque = false, scale = 1.0)
        image.drawInRect(CGRectMake(0.0, 0.0, targetWidth, targetHeight))
        val scaled = UIGraphicsGetImageFromCurrentImageContext()
        UIGraphicsEndImageContext()
        return scaled
    }
}
