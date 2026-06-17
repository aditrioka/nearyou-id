package id.nearyou.app.screens.post

/**
 * Pure, Compose-free projection of the edit editor (the `mobile-post-editing` editor), mirroring
 * [postCreationUiState]'s gate: [charCount] is the number of **Unicode code points** in `content` (a
 * 280-emoji edit counts 280, not 560 — reuses [String.codePointLength]); [overLimit] iff over
 * [MAX_POST_CONTENT_CODE_POINTS]; [submitEnabled] iff `content` has ≥1 non-blank code point AND ≤280 code
 * points AND not in-flight — so the client NEVER submits empty / over-limit content (the server re-guards
 * authoritatively). The one-shot signals are nullable/false-by-default fields consumed + cleared by the
 * ViewModel's `onXxx` callbacks (NO `Channel`/`SharedFlow` event bus — design § 2.2). No PII, no wall-clock.
 */
data class EditPostUiState(
    val content: String,
    val charCount: Int,
    val overLimit: Boolean,
    val submitEnabled: Boolean,
    val isSubmitting: Boolean,
    /** One-shot: `403 premium_required` → show the "Aktifkan Premium" upsell (reactive gate, design D2). */
    val showPremiumUpsell: Boolean = false,
    /** One-shot: a non-premium failure → an inline banner message (the field text is preserved). */
    val error: EditPostError? = null,
    /** One-shot: `200` success → the caller returns to post-detail carrying this updated content. */
    val editedContent: String? = null,
)

/** The edit window: 30 minutes from the post's creation. The backend's window is authoritative; this client
 *  check only hides the Edit affordance for clearly-stale posts (a clock-skew boundary case is caught by the
 *  `409 edit_window_expired` on submit). */
const val EDIT_WINDOW_MILLIS: Long = 30L * 60L * 1000L

/** Pure: is [nowEpochMillis] within [EDIT_WINDOW_MILLIS] after [createdAtEpochMillis] (and not before it)? */
fun isWithinEditWindow(
    createdAtEpochMillis: Long,
    nowEpochMillis: Long,
): Boolean = (nowEpochMillis - createdAtEpochMillis) in 0..EDIT_WINDOW_MILLIS

/** The distinct non-premium edit failures, each → one inline Bahasa Indonesia message on the editor. */
enum class EditPostError {
    /** `409 edit_window_expired` — the 30-minute window has passed. */
    WINDOW_EXPIRED,

    /** `400 no_changes` — the edit is identical to the current content. */
    NO_CHANGES,

    /** `400 content_moderated_*` — the edit was rejected by moderation. */
    CONTENT_MODERATED,

    /** `409 edit_conflict` — the retryable temporal collision ("Coba lagi sebentar."). */
    CONFLICT,

    /** `429` — the per-user edit rate limit. */
    RATE_LIMITED,

    /** `404` — the post is gone / invisible. */
    NOT_FOUND,

    /** `5xx` / transport — retryable. */
    NETWORK,
}

/**
 * Projects the editor [content] + transient signals into the [EditPostUiState] gate. Deterministic over
 * its inputs (no platform/wall-clock dependency) — the unit-testable core of the editor's submit-enable.
 */
fun editPostUiState(
    content: String,
    isSubmitting: Boolean,
    showPremiumUpsell: Boolean = false,
    error: EditPostError? = null,
    editedContent: String? = null,
): EditPostUiState {
    val charCount = content.codePointLength()
    val overLimit = charCount > MAX_POST_CONTENT_CODE_POINTS
    val withinLength = content.isNotBlank() && charCount <= MAX_POST_CONTENT_CODE_POINTS
    return EditPostUiState(
        content = content,
        charCount = charCount,
        overLimit = overLimit,
        submitEnabled = withinLength && !isSubmitting,
        isSubmitting = isSubmitting,
        showPremiumUpsell = showPremiumUpsell,
        error = error,
        editedContent = editedContent,
    )
}
