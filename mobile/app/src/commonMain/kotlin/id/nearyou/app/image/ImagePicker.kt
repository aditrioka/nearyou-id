package id.nearyou.app.image

/**
 * An image chosen from the OS photo picker, already **downscaled + recompressed client-side to ≤ 5 MB**
 * with an image content type (e.g. `image/jpeg`) accepted by the backend allowlist (so the server-side
 * 5 MB guard is never the first line of defense). [mime] is the part content-type the [ImageUploadApiClient] stamps
 * on the multipart `file` part; [bytes] are the compressed JPEG bytes.
 *
 * `equals`/`hashCode` are overridden because a `data class` with a `ByteArray` member generates
 * identity-based array equality — misleading for a value type. Two `PickedImage`s are equal iff their
 * MIME and byte contents match (structural), mirroring the repo's value-type convention; the bytes are
 * NEVER logged (a `toString` is deliberately NOT customized to print them).
 */
class PickedImage(
    val bytes: ByteArray,
    val mime: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PickedImage) return false
        return mime == other.mime && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = 31 * mime.hashCode() + bytes.contentHashCode()
}

/**
 * The commonMain seam for the OS image-picker ceremony, with Android / iOS actuals (mirroring the
 * `mobile-location-permission-flow` [id.nearyou.app.location.LocationPermissionController] testability
 * idiom — production binds a platform actual in each `platformModule`; a fake drives commonTest).
 *
 * Platform-free by contract: this interface references NO platform picker / image API
 * (`ActivityResultContracts.PickVisualMedia`, `PHPickerViewController`, `Bitmap`, `UIImage`) —
 * `docs/11` §2.5 (commonMain `interface` + per-platform actuals; `expect class` is reserved for
 * top-level funs). The androidMain / iosMain actuals hold ONLY the platform call + the compression,
 * no business logic.
 *
 * An `interface`, NOT an `expect class` (spec § "A commonMain ImagePicker seam returns a compressed
 * image with per-platform actuals").
 */
interface ImagePicker {
    /**
     * Presents the OS photo picker and suspends until the user chooses an image or dismisses the
     * picker. Returns the chosen image **already compressed to ≤ 5 MB** with an image [PickedImage.mime],
     * or `null` when the user cancels (no upload is then attempted). The Premium gate + the UU-PDP
     * upsell are decided by the composer BEFORE this is invoked — this is the OS-picker + compression
     * step only.
     *
     * The Android Photo Picker / iOS `PHPickerViewController` both require NO storage/library
     * read permission, so [pick] never triggers a runtime-permission prompt.
     */
    suspend fun pick(): PickedImage?
}
