package id.nearyou.app.auth

/**
 * The terminal outcomes of `AuthRepository.signUpWithGoogle(...)` (the Mobile #4 signup-new-user
 * flow). Every observed `/signup` result maps to EXACTLY one member, keyed on the HTTP **status**
 * (and transport-failure type) — NOT on a parsed `error.code` (design D2/D8). `/signup` emits a
 * FLAT `{"error":"user_blocked",...}` body for `403` and a NESTED `{"error":{"code":...}}` body
 * for `400/401/409/503`; a single nested-shape parser cannot decode both, so the mapping is
 * status-driven and the envelope is informational only. There is NO generic "signup failed"
 * fallthrough — `5xx`/`503`/IO/`400`/`GoogleSignInResult.Failed` all converge onto [RetryableError].
 *
 * None of these members carries the Google `email`/`displayName` PII — the UI layer maps each to
 * a static `Res.string.*` key (see [id.nearyou.app.screens.auth.ageGateUiState]), so a signup
 * banner can never surface the identity payload (design D7, mirroring `SignInErrorBanner`).
 */
sealed interface SignUpOutcome {
    /** `201 Created` — tokens persisted via `SecureTokenStore.write`; route to `HomeScreen`. */
    data object Success : SignUpOutcome

    /** `403 user_blocked` — under-18 OR already-rejected identifier (byte-identical bodies). One
     *  generic `age_gate_under18_blocked` message; NO token write, NO navigation away. */
    data object Blocked : SignUpOutcome

    /** `409 user_exists` — the verified identity already has a `users` row; route to `SignInScreen`. */
    data object AccountExists : SignUpOutcome

    /** `401 invalid_id_token` surviving one Google-token refresh+retry — terminal token-invalid state. */
    data object InvalidIdToken : SignUpOutcome

    /** `5xx` / `503 username_generation_failed` / network-IO / `400 invalid_request` /
     *  `GoogleSignInResult.Failed` — a retryable error (`signin_error_network` + `cta_retry`). */
    data object RetryableError : SignUpOutcome

    /** A concurrent/duplicate invocation rejected by the in-flight guard (design D8). No `/signup`
     *  call, no token write, no UI change — mirrors `SignInOutcome.Cancelled`. */
    data object Cancelled : SignUpOutcome
}
