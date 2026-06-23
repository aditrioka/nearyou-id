package id.nearyou.app.screens.post

import id.nearyou.app.image.ImageUploadOutcome

/**
 * Which upload-failure message (if any) the composer shows for a non-`Success` [ImageUploadOutcome].
 * Deliberately an enum of static message keys (mirrors [PostCreationBanner]) — it can NEVER carry the
 * image bytes, the coordinate, or any PII into the UI. The Compose layer maps each member to a
 * `stringResource`. `Success` has no member here (it surfaces a thumbnail preview, not a message).
 *
 * Each member is the distinct UI state the spec requires for EACH `ImageUploadOutcome` (the gating
 * `PremiumRequired` is handled by the proactive Premium gate before the picker, but the reactive 403
 * backstop — a Free/expired viewer who reaches upload anyway — also maps here so no outcome is silent).
 */
enum class ImageUploadError {
    /** `ImageUploadOutcome.PremiumRequired` — upload is Premium-only (reactive 403 backstop). */
    PREMIUM_REQUIRED,

    /** `ImageUploadOutcome.FeatureDisabled` — the backend `image_upload_enabled` flag is off. */
    FEATURE_DISABLED,

    /** `ImageUploadOutcome.QuotaExceeded` — the daily upload quota is reached. */
    QUOTA_EXCEEDED,

    /** `ImageUploadOutcome.Throttled` — too-frequent uploads; short cool-down. */
    THROTTLED,

    /** `ImageUploadOutcome.ModerationRejected` — Safe-Search rejected the image. */
    MODERATION_REJECTED,

    /** `ImageUploadOutcome.TooLarge` — the bytes exceed the 5 MB server cap. */
    TOO_LARGE,

    /** `ImageUploadOutcome.Unavailable` — Vision / Cloudflare Images unconfigured or down. */
    UNAVAILABLE,

    /** `ImageUploadOutcome.Network` — transport-level failure. */
    NETWORK,
}

/**
 * The composer's image-attachment sub-state, projected from the picked/upload lifecycle. Compose-free and
 * PII-safe-by-construction: [Attached] carries only the opaque server [Attached.imageId] +
 * [Attached.deliveryUrl] (a public URL the thumbnail loads — never the raw bytes, never a coordinate),
 * and the error variant carries only a static [ImageUploadError] key.
 *
 * - [None] — no image picked (the text-only path); the attach affordance is active.
 * - [Uploading] — an image was picked and the upload is in flight; the "Posting" CTA is disabled.
 * - [Attached] — the upload succeeded; the composer shows a thumbnail preview + a remove affordance, and
 *   "Posting" passes [Attached.imageId] into the create-post request.
 * - [Failed] — the upload resolved to a non-`Success` outcome; the mapped [ImageUploadError] message is
 *   shown and NO post referencing the image is submitted (the attachment is cleared).
 */
sealed interface ImageAttachUiState {
    data object None : ImageAttachUiState

    data object Uploading : ImageAttachUiState

    data class Attached(val imageId: String, val deliveryUrl: String) : ImageAttachUiState

    data class Failed(val error: ImageUploadError) : ImageAttachUiState
}

/**
 * Maps a terminal [ImageUploadOutcome] to the composer's image sub-state. `Success` becomes [Attached]
 * (preview + the id "Posting" attaches); every other outcome becomes [Failed] with its distinct
 * [ImageUploadError] key. Exhaustive over [ImageUploadOutcome] — no generic fallthrough (the sealed
 * outcome guarantees the `when` is exhaustive without an `else`), so a new backend outcome surfaces as a
 * compile error here rather than a silent miss.
 */
fun ImageUploadOutcome.toAttachState(): ImageAttachUiState =
    when (this) {
        is ImageUploadOutcome.Success -> ImageAttachUiState.Attached(imageId = imageId, deliveryUrl = deliveryUrl)
        ImageUploadOutcome.PremiumRequired -> ImageAttachUiState.Failed(ImageUploadError.PREMIUM_REQUIRED)
        ImageUploadOutcome.FeatureDisabled -> ImageAttachUiState.Failed(ImageUploadError.FEATURE_DISABLED)
        ImageUploadOutcome.QuotaExceeded -> ImageAttachUiState.Failed(ImageUploadError.QUOTA_EXCEEDED)
        ImageUploadOutcome.Throttled -> ImageAttachUiState.Failed(ImageUploadError.THROTTLED)
        ImageUploadOutcome.ModerationRejected -> ImageAttachUiState.Failed(ImageUploadError.MODERATION_REJECTED)
        ImageUploadOutcome.TooLarge -> ImageAttachUiState.Failed(ImageUploadError.TOO_LARGE)
        ImageUploadOutcome.Unavailable -> ImageAttachUiState.Failed(ImageUploadError.UNAVAILABLE)
        ImageUploadOutcome.Network -> ImageAttachUiState.Failed(ImageUploadError.NETWORK)
    }
