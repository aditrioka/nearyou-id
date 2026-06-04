package id.nearyou.app.post

/**
 * Every `submit()` maps to EXACTLY one member — keyed on the permission gate, the coordinate
 * acquisition, and then the HTTP **status + `error.code`** — with no generic "submit failed"
 * wildcard fallthrough (design D3, mirroring `NearbyTimelineOutcome`).
 *
 * `401` is deliberately absent: it is delegated to the shipped `Auth` plugin (terminal 401 → store
 * cleared → re-route to sign-in), NEVER mapped here. A backend `Verdict.Flag` (UU-ITE soft flag) is a
 * normal `201` and surfaces as [Success] (the row was created and silently queued — not an error).
 * No member carries the coordinate or any PII.
 */
sealed interface PostCreationOutcome {
    /** HTTP 201 — the post was created; carries the parsed post [id] (not author PII). */
    data class Success(val id: String) : PostCreationOutcome

    /** 400 `content_empty` — the server's NFKC-normalized + trimmed content was empty. */
    data object ContentEmpty : PostCreationOutcome

    /** 400 `content_too_long` — the server's NFKC code-point length exceeded 280. */
    data object ContentTooLong : PostCreationOutcome

    /** 400 `location_out_of_bounds` — the device coordinate fell outside the Indonesia envelope. */
    data object LocationOutOfBounds : PostCreationOutcome

    /** 400 `content_moderated_profanity` — Layer-1/Layer-3 moderation rejected the content
     *  (generic, keyword-free copy; the matched keyword is never surfaced). */
    data object ContentRejected : PostCreationOutcome

    /**
     * No usable device location: permission denied / not-granted-after-prompt (no `current()`, no
     * POST) OR granted-but-no-fix (`LocationUnavailableException`, no POST). The UI offers a "Buka
     * Pengaturan" deep link. Carries no coordinate (there is none).
     */
    data object LocationUnavailable : PostCreationOutcome

    /** HTTP 5xx, transport/IO failure, or any unenumerated status — retryable. */
    data object NetworkError : PostCreationOutcome

    /** An unknown/absent 400 `error.code` (e.g. `invalid_json`) — retryable, logged via the
     *  coordinate-free diagnostic sink (NOT a silent no-op, NOT a crash). */
    data object Error : PostCreationOutcome
}
