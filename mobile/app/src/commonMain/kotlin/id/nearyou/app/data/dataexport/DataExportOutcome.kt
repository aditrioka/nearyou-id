package id.nearyou.app.data.dataexport

/**
 * Terminal outcome of [DataExportFlow.requestExport]. Status-driven (mirrors `AccountDeletionOutcome`) —
 * keyed on the HTTP status / transport-failure type, NEVER on a parsed `error.code`. Exhaustive with no
 * generic fallthrough: 2xx→[Requested], `401`→[Terminal401], `5xx`/IO/`4xx`→[RetryableError]. No variant
 * carries PII — the UI maps each to a static `Res.string.*` key. The export is single-active server-side,
 * so a successful request always resolves to an in-progress state on the client (see [DataExportStatusOutcome]).
 */
sealed interface DataExportRequestOutcome {
    /** Any 2xx (the route returns `202`) — the export was enqueued (or the existing single-active was returned). */
    data object Requested : DataExportRequestOutcome

    /** `401` — the access token failed verification. Terminal: route to sign-in. */
    data object Terminal401 : DataExportRequestOutcome

    /** `5xx` / IO / `4xx` — a retryable error (the screen shows a non-trapping error, no optimistic stick). */
    data object RetryableError : DataExportRequestOutcome
}

/**
 * The caller's export status, projected from `GET /api/v1/account/export`. Drives the row subtitle + the
 * ready banner. Exhaustive over the shipped wire vocabulary with no generic fallthrough: `none`→[None];
 * `pending`/`processing`→[InProgress] (single-active, not re-issuable); `ready`→[Ready] (the deadline +
 * the freshly-signed URL — surfaced as an in-app download affordance); `expired`→[Expired];
 * `failed`→[Failed]. A terminal `401` surfaces [Terminal401] (route to sign-in); a transient failure (or
 * an unknown future status, or a malformed `ready` row missing its URL/deadline) surfaces [Unavailable]
 * (the screen surfaces a non-trapping error and stays functional — a status-read failure must NOT trap
 * the otherwise-usable Settings screen).
 */
sealed interface DataExportStatusOutcome {
    /** `none` — the caller has never requested an export; only the request affordance is offered (no banner). */
    data object None : DataExportStatusOutcome

    /** `pending` / `processing` — a non-blocking in-progress state; the request is NOT re-issuable. */
    data object InProgress : DataExportStatusOutcome

    /** `ready` — a banner referencing [downloadExpiresAt] with an affordance to open the signed [downloadUrl]. */
    data class Ready(val downloadExpiresAt: String, val downloadUrl: String) : DataExportStatusOutcome

    /** `expired` — the download window lapsed; a fresh request is allowed (no longer single-active-locked). */
    data object Expired : DataExportStatusOutcome

    /** `failed` — the export run failed; a fresh request is allowed (no longer single-active-locked). */
    data object Failed : DataExportStatusOutcome

    /** `401` — token failed verification. Terminal: route to sign-in. */
    data object Terminal401 : DataExportStatusOutcome

    /** `5xx` / IO / `4xx` / unknown status — the status could not be read; surface a non-trapping error. */
    data object Unavailable : DataExportStatusOutcome
}
