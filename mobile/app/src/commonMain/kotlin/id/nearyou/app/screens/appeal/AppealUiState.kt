package id.nearyou.app.screens.appeal

/** Max appeal-text length (code points), mirroring the backend `appeal.text` guard + the V31 CHECK. */
const val APPEAL_TEXT_LIMIT: Int = 1000

/**
 * The mutually-exclusive top-level mode of the appeal surface (the `UsernameStatus` precedent). [Loading]
 * is the brief on-entry own-status read; [Form] is the editor (text field + char counter), carrying an
 * optional inline [error] after a failed submit; [Submitting] is the in-flight submit; [Pending] /
 * [Decided] are the read-only outcome surfaces; [SessionRedirect] is the no-token / `401` takeover (the
 * appeal token is one-shot — re-sign-in re-mints it); [LoadError] is the retryable on-entry read failure.
 */
sealed interface AppealStatus {
    data object Loading : AppealStatus

    /** The submission editor. [error] is the inline message after a failed submit (null = clean form). */
    data class Form(val error: AppealFormError? = null) : AppealStatus

    data object Submitting : AppealStatus

    /** An appeal is awaiting review. [actionType] is the appealed action ('suspension'/'permanent_ban'),
     *  null right after a fresh submit (the screen shows generic "sedang ditinjau"). */
    data class Pending(val actionType: String?) : AppealStatus

    /** A decided appeal — [approved] true = ban lifted, false = rejected (with optional [decisionReason]). */
    data class Decided(val approved: Boolean, val decisionReason: String?) : AppealStatus

    /** No appeal token (process death / not banned) or a `401` — the screen routes back to sign-in. */
    data object SessionRedirect : AppealStatus

    /** The on-entry own-status read failed (transport / 5xx) — retryable. */
    data object LoadError : AppealStatus
}

/** The inline message rendered on the [AppealStatus.Form] after a failed submit. */
sealed interface AppealFormError {
    /** `409 no_actionable_moderation` — nothing to appeal (state changed since sign-in). */
    data object NotEligible : AppealFormError

    /** `429` — the daily submission cap (minute-countdown, floored to 1). */
    data class RateLimited(val minutes: Int) : AppealFormError

    /** `400` content_empty / content_too_long. */
    data object InvalidText : AppealFormError

    /** Transport / 5xx — the retryable connectivity message. */
    data object Transport : AppealFormError
}

/**
 * The Compose-free projection of the appeal screen state (the `UsernameUiState` precedent). A pure,
 * deterministic value the ViewModel exposes via one `StateFlow`. [submitEnabled] is derived (non-blank,
 * within the limit, on the editor); [charCount] drives the counter.
 */
data class AppealUiState(
    val status: AppealStatus = AppealStatus.Loading,
    val appealText: String = "",
    val charCount: Int = 0,
    val charLimit: Int = APPEAL_TEXT_LIMIT,
    val submitEnabled: Boolean = false,
)
