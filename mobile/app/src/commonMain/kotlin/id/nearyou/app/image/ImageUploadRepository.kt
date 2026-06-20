package id.nearyou.app.image

/**
 * The terminal result of an image upload, surfaced to the ViewModel. A `sealed interface` whose members
 * are EXACTLY the nine below — there is NO generic fallthrough, so a `when` over it is exhaustive without
 * an `else` (the spec's "every outcome is a declared sealed member" scenario). Each maps a distinct
 * backend status + `error.code` (`ImageRoutes.kt`) so the composer can render distinct copy.
 */
sealed interface ImageUploadOutcome {
    /** `201` — the upload stored. [imageId] is passed into the subsequent create-post request;
     *  [deliveryUrl] is the opaque public URL for the in-composer thumbnail preview. */
    data class Success(val imageId: String, val deliveryUrl: String) : ImageUploadOutcome

    /** `403 premium_required` — image upload is a Premium feature; the viewer is on Free. */
    data object PremiumRequired : ImageUploadOutcome

    /** `403 image_upload_disabled` — the backend `image_upload_enabled` flag is off ("not available yet"). */
    data object FeatureDisabled : ImageUploadOutcome

    /** `429 image_upload_quota_exceeded` — the daily upload quota is reached. */
    data object QuotaExceeded : ImageUploadOutcome

    /** `429 image_upload_throttled` — too-frequent uploads; a short cool-down before retrying. */
    data object Throttled : ImageUploadOutcome

    /** `422 image_rejected` — Safe-Search moderation rejected the image content. */
    data object ModerationRejected : ImageUploadOutcome

    /** `413 image_too_large` — the bytes exceed the 5 MB server cap (the client compresses first, so
     *  this is a defense-in-depth terminal, not the first line of defense). */
    data object TooLarge : ImageUploadOutcome

    /** `503 image_upload_unavailable` (Vision / Cloudflare Images unconfigured or down) OR any other
     *  unexpected non-2xx — a SAFE, retryable terminal "try again later" state, never a silent success. */
    data object Unavailable : ImageUploadOutcome

    /** Transport-level failure (IOException, timeout, host unreachable). */
    data object Network : ImageUploadOutcome
}

/**
 * Orchestrates an image upload: call `POST /api/v1/images` → map the shipped backend's HTTP **status +
 * `error.code`** envelope (`ImageRoutes.kt`) to exactly one [ImageUploadOutcome]. There is no generic
 * "upload failed" fallthrough. ViewModels consume THIS repository, never [ImageUploadApiClient] directly
 * (docs/11 §2.6). The Bearer + terminal 401 are owned by the shipped `Auth` plugin upstream — this
 * repository MUST NOT reimplement token refresh / re-route.
 *
 * The two `403`s (`image_upload_disabled` vs `premium_required`) and the two `429`s
 * (`image_upload_throttled` vs `image_upload_quota_exceeded`) are disambiguated by `error.code`, NOT
 * status alone (mirrors the `error.code` disambiguation in [id.nearyou.app.post.CreatePostRepository]).
 *
 * Privacy discipline: the image bytes are NEVER logged; [diagnosticLog] carries only status + the server
 * `error.code` enum (no bytes, no coordinate).
 */
class ImageUploadRepository(
    private val apiClient: ImageUploadApiClient,
    // Diagnostic sink for non-user-facing error detail (status + error code only — NEVER the image
    // bytes). Wired to the real coordinate-free DiagnosticSink in Koin; no-op default for tests.
    private val diagnosticLog: (status: Int, errorCode: String?) -> Unit = { _, _ -> },
) {
    suspend fun upload(
        bytes: ByteArray,
        mime: String,
    ): ImageUploadOutcome =
        when (val result = apiClient.upload(bytes = bytes, mime = mime)) {
            is ImageUploadApiResult.Success ->
                ImageUploadOutcome.Success(
                    imageId = result.body.imageId,
                    deliveryUrl = result.body.deliveryUrl,
                )

            is ImageUploadApiResult.NetworkError -> ImageUploadOutcome.Network
            is ImageUploadApiResult.HttpError -> mapHttpError(result)
        }

    /**
     * Maps a non-2xx to exactly one outcome. The two `403`s and two `429`s split on `error.code`. A
     * `503 image_upload_unavailable` AND any other unexpected non-2xx (e.g. the client-prevented
     * `415 unsupported_image_type` / `400 no_image`, unreachable given the picker's image-MIME + file-part
     * guarantees) both map to the SAFE retryable [ImageUploadOutcome.Unavailable] — never a silent
     * success — with a logged diagnostic for the genuinely-unexpected codes. `401` never reaches here:
     * the shipped `Auth` plugin consumes it upstream (terminal 401 → re-route to sign-in).
     */
    private fun mapHttpError(error: ImageUploadApiResult.HttpError): ImageUploadOutcome =
        when (error.errorCode) {
            "image_upload_disabled" -> ImageUploadOutcome.FeatureDisabled
            "premium_required" -> ImageUploadOutcome.PremiumRequired
            "image_upload_throttled" -> ImageUploadOutcome.Throttled
            "image_upload_quota_exceeded" -> ImageUploadOutcome.QuotaExceeded
            "image_too_large" -> ImageUploadOutcome.TooLarge
            "image_rejected" -> ImageUploadOutcome.ModerationRejected
            "image_upload_unavailable" -> ImageUploadOutcome.Unavailable
            else -> {
                // An unrecognized / absent code on a non-2xx (incl. the picker-prevented 415/400) →
                // the DEFINED retryable terminal, with a diagnostic so it is observable (NOT silent).
                diagnosticLog(error.status, error.errorCode)
                ImageUploadOutcome.Unavailable
            }
        }
}
