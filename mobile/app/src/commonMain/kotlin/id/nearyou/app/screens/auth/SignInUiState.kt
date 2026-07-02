package id.nearyou.app.screens.auth

import id.nearyou.app.auth.SignInOutcome

/** Which string the CTA shows. The Compose layer maps each to a `stringResource`. */
enum class SignInCtaLabel {
    /** `cta_signin_google` — initial + after a non-network terminal state. */
    GOOGLE,

    /** `cta_retry` — after a NetworkError. */
    RETRY,

    /** `signin_loading` — while a sign-in is in flight. */
    LOADING,
}

/** Which error banner (if any) is shown. Deliberately an enum of static message keys — it
 *  can NEVER carry the Google `email` / `displayName` PII into the UI (spec § "No error-state
 *  UI renders Google email or displayName").
 *
 *  Note (Mobile #4): there is no `NO_ACCOUNT` banner — `404 user_not_found` now navigates to
 *  `AgeGateScreen` instead of showing a banner (the `signin_error_no_account` copy is retired
 *  from this path per the `mobile-auth-signin` MODIFIED routing). */
enum class SignInErrorBanner {
    /** `signin_error_banned` — a PERMANENT ban (403 `account_banned`, `suspended_until` null): the
     *  "Hubungi support" copy, no appeal entry (appeal-sign-in-ban-distinction). */
    BANNED,

    /** `signin_error_suspended` — a 7-day SUSPENSION (403 `account_banned`, non-null `suspended_until`):
     *  the suspension copy that pairs with the "Ajukan banding" appeal entry. */
    SUSPENDED,
    NETWORK,
    TOKEN_INVALID,

    /** `signin_error_rate_limited` — 429 from the auth-endpoint limiter (auth-endpoint-rate-limits). */
    RATE_LIMITED,
}

/** Pure, Compose-free projection of the sign-in screen UI state. Encodes Decision 7's
 *  result→state table so it can be unit-tested without a Compose UI runner. */
data class SignInUiState(
    val ctaLabel: SignInCtaLabel,
    val ctaEnabled: Boolean,
    val errorBanner: SignInErrorBanner?,
)

/**
 * Maps the current [SignInOutcome] (null = fresh / never-attempted) + the in-flight flag to
 * the screen's UI state, per `design.md` Decision 7.
 *
 * - in-flight ⇒ LOADING label, disabled, no banner.
 * - [SignInOutcome.NetworkError] ⇒ RETRY label, enabled, NETWORK banner.
 * - [SignInOutcome.Banned] ⇒ GOOGLE label, **disabled** (tap-rejected); SUSPENDED banner when
 *   `suspendedUntil` is non-null (a suspension), else BANNED banner (a permanent ban).
 * - [SignInOutcome.InvalidIdToken] ⇒ GOOGLE label, enabled, TOKEN_INVALID banner.
 * - [SignInOutcome.NoAccount] (Mobile #4: navigates to `AgeGateScreen`) / [SignInOutcome.Success] /
 *   [SignInOutcome.Cancelled] / null ⇒ GOOGLE label, enabled, no banner.
 */
fun signInUiState(
    outcome: SignInOutcome?,
    inFlight: Boolean,
): SignInUiState {
    if (inFlight) {
        return SignInUiState(ctaLabel = SignInCtaLabel.LOADING, ctaEnabled = false, errorBanner = null)
    }
    return when (outcome) {
        is SignInOutcome.Banned ->
            // appeal-sign-in-ban-distinction: a non-null suspended_until ⇒ suspension (SUSPENDED copy
            // pairing with the appeal entry); null ⇒ permanent ban (BANNED "Hubungi support" copy). Both
            // disable the CTA (tap-rejected) to prevent a retry.
            SignInUiState(
                SignInCtaLabel.GOOGLE,
                ctaEnabled = false,
                errorBanner = if (outcome.suspendedUntil != null) SignInErrorBanner.SUSPENDED else SignInErrorBanner.BANNED,
            )
        SignInOutcome.InvalidIdToken ->
            SignInUiState(SignInCtaLabel.GOOGLE, ctaEnabled = true, errorBanner = SignInErrorBanner.TOKEN_INVALID)
        SignInOutcome.NetworkError ->
            SignInUiState(SignInCtaLabel.RETRY, ctaEnabled = true, errorBanner = SignInErrorBanner.NETWORK)
        // 429 (auth-endpoint-rate-limits): RETRY label + enabled + a dedicated banner telling the
        // user to try again shortly — a defined state, not the generic NETWORK error.
        is SignInOutcome.RateLimited ->
            SignInUiState(SignInCtaLabel.RETRY, ctaEnabled = true, errorBanner = SignInErrorBanner.RATE_LIMITED)
        // NoAccount is a transient navigation trigger (→ AgeGateScreen), like Success — no banner
        // (the signin_error_no_account copy is retired from the 404 path). Cancelled / null are the
        // initial CTA-visible state.
        is SignInOutcome.NoAccount,
        SignInOutcome.Success,
        SignInOutcome.Cancelled,
        null,
        ->
            SignInUiState(SignInCtaLabel.GOOGLE, ctaEnabled = true, errorBanner = null)
    }
}
