package id.nearyou.app.appeal

/**
 * Domain seam for the ban/suspension appeal surface (`content-moderation-appeal`). The ViewModel depends
 * on this interface (not the concrete [AppealRepository]) so a fake drives the screen tests without an
 * HTTP client. Each HTTP **status** maps to exactly one outcome (the `SearchRepository`/`UsernameRepository`
 * precedent — no generic "load failed" fallthrough).
 */
interface AppealFlow {
    suspend fun submit(
        appealText: String,
        appealToken: String,
    ): AppealSubmitOutcome

    suspend fun status(appealToken: String): AppealStatusOutcome
}

/** Outcome of `POST /api/v1/appeals`. */
sealed interface AppealSubmitOutcome {
    /** `201` — the appeal was accepted and is now `pending`. */
    data class Submitted(val appealId: String) : AppealSubmitOutcome

    /** `409 appeal_already_pending` — the user already has a pending appeal. */
    data object AlreadyPending : AppealSubmitOutcome

    /** `409 no_actionable_moderation` — there is no moderation action to appeal (state changed since sign-in). */
    data object NotEligible : AppealSubmitOutcome

    /** `429` — the daily submission cap was hit. */
    data class RateLimited(val retryAfterSeconds: Long) : AppealSubmitOutcome

    /** `400` content_empty / content_too_long — the appeal text failed the length guard. */
    data object InvalidText : AppealSubmitOutcome

    /** `401` — the appeal token is stale/revoked; the caller must sign in again to get a fresh one. */
    data object SessionExpired : AppealSubmitOutcome

    /** Transport failure or `5xx` — retryable. */
    data object TransportError : AppealSubmitOutcome
}

/** Outcome of `GET /api/v1/appeals` (own-status read). */
sealed interface AppealStatusOutcome {
    /** `has_appeal = false` — the user has never submitted an appeal. */
    data object None : AppealStatusOutcome

    /** A pending appeal awaiting review. */
    data class Pending(val actionType: String, val createdAt: String?) : AppealStatusOutcome

    /** A decided appeal — [approved] true = approved (ban lifted), false = rejected. */
    data class Decided(
        val approved: Boolean,
        val actionType: String,
        val decisionReason: String?,
        val reviewedAt: String?,
    ) : AppealStatusOutcome

    /** `401` — the appeal token is stale/revoked. */
    data object SessionExpired : AppealStatusOutcome

    /** Transport failure or `5xx`. */
    data object TransportError : AppealStatusOutcome
}
