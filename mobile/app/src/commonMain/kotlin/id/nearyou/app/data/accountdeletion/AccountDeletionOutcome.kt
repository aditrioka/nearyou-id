package id.nearyou.app.data.accountdeletion

/**
 * Terminal outcome of [AccountDeletionFlow.requestDeletion]. Status-driven (mirrors `BlockedUsersOutcome`
 * / `ConsentOutcome`) — keyed on the HTTP status / transport-failure type, NEVER on a parsed
 * `error.code`. Exhaustive with no generic fallthrough: `200`→[Scheduled], `401`→[Terminal401],
 * `5xx`/IO/`400`→[RetryableError]. No variant carries PII — the UI maps each to a static `Res.string.*`
 * key; [Scheduled] carries only the public restore-by instant (already echoed to this client).
 */
sealed interface AccountDeletionOutcome {
    /** `200 OK` — the deletion was scheduled; [scheduledHardDeleteAt] is the restore-by instant (ISO-8601). */
    data class Scheduled(val scheduledHardDeleteAt: String) : AccountDeletionOutcome

    /** `401` — the access token failed verification. Terminal: route to sign-in. */
    data object Terminal401 : AccountDeletionOutcome

    /** `5xx` / IO / `400` — a retryable error (reuses `signin_error_network`). */
    data object RetryableError : AccountDeletionOutcome
}

/**
 * The viewer's pending-deletion status, projected from `GET /api/v1/account/deletion-request`. Drives the
 * non-blocking restore banner: [NotPending] hides it; [Pending] shows it with the restore-by instant.
 * A terminal `401` surfaces [Terminal401] (route to sign-in); a transient failure surfaces [Unavailable]
 * (the banner simply does not render — a status-read failure must NOT trap the otherwise-functional
 * Settings screen).
 */
sealed interface DeletionStatusOutcome {
    /** `200` with no in-grace deletion — no banner. */
    data object NotPending : DeletionStatusOutcome

    /** `200` with a pending deletion — render the banner referencing [scheduledHardDeleteAt] (ISO-8601). */
    data class Pending(val scheduledHardDeleteAt: String) : DeletionStatusOutcome

    /** `401` — token failed verification. Terminal: route to sign-in. */
    data object Terminal401 : DeletionStatusOutcome

    /** `5xx` / IO / `400` — the status could not be read; the banner does not render (non-trapping). */
    data object Unavailable : DeletionStatusOutcome
}

/**
 * Terminal outcome of [AccountDeletionFlow.cancelDeletion] ("Batalkan" → restore). On [RetryableError]
 * the screen KEEPS the banner and surfaces a non-trapping error — it does NOT optimistically clear a
 * banner whose cancel did not succeed (`mobile-settings` § restore banner). A `401` routes to sign-in.
 */
sealed interface DeletionCancelOutcome {
    /** Any 2xx — the deletion is cancelled; the banner clears. */
    data object Success : DeletionCancelOutcome

    /** `401` — token failed verification. Terminal: route to sign-in. */
    data object Terminal401 : DeletionCancelOutcome

    /** `5xx` / IO / `400` / `409` — the banner stays; surface a non-trapping error (no optimistic clear). */
    data object RetryableError : DeletionCancelOutcome
}
