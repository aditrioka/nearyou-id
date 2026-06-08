package id.nearyou.app.consent

/**
 * Terminal outcomes of [ConsentFlow.submitConsent]. Status-driven (design D5) — keyed on the HTTP
 * status / transport-failure type, NEVER on a parsed `error.code`. Exhaustive with no generic
 * "consent failed" fallthrough: `200`→[Success], `401`→[TokenInvalid], `5xx`/`503`/IO/`400`→
 * [RetryableError]. None carries any PII — the UI layer maps each to a static `Res.string.*` key
 * (see [id.nearyou.app.screens.consent.consentUiState]).
 */
sealed interface ConsentOutcome {
    /** `200 OK` — consent persisted; route to `HomeScreen`. */
    data object Success : ConsentOutcome

    /** `401` — the (just-minted) access token failed verification. Terminal token-invalid banner,
     *  no route (rare edge — the token was minted seconds earlier at signup). */
    data object TokenInvalid : ConsentOutcome

    /** `5xx` / `503` / network-IO / `400` — a retryable error (`consent_error_retryable` + `cta_retry`,
     *  and the post-failure `consent_skip` proceed-anyway affordance). */
    data object RetryableError : ConsentOutcome

    /** A concurrent/duplicate invocation rejected by the in-flight guard. No PATCH, no UI change. */
    data object Cancelled : ConsentOutcome
}
