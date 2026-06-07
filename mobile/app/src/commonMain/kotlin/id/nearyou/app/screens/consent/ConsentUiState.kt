package id.nearyou.app.screens.consent

import id.nearyou.app.consent.ConsentOutcome

/** Which string the primary CTA shows. The Compose layer maps each to a `stringResource`. */
enum class ConsentCtaLabel {
    /** `consent_cta_continue` — initial + after a non-loading state. */
    CONTINUE,

    /** `cta_retry` — after a [ConsentOutcome.RetryableError]. */
    RETRY,

    /** `loading` — while a submit is in flight. */
    LOADING,
}

/** Which banner (if any) is shown. An enum of static message keys — it can NEVER carry PII. */
enum class ConsentBanner {
    /** `consent_error_retryable` — `5xx` / `503` / IO / `400` retryable error. */
    RETRYABLE,

    /** `signin_error_token_invalid` — terminal `401` (the just-minted token failed verification). */
    TOKEN_INVALID,
}

/** Pure, Compose-free projection of the consent screen UI state, mirroring `AgeGateUiState`. */
data class ConsentUiState(
    val ctaLabel: ConsentCtaLabel,
    val ctaEnabled: Boolean,
    val banner: ConsentBanner?,
    val showSkip: Boolean,
)

/**
 * Maps the current [ConsentOutcome] (null = fresh / never-submitted) + the in-flight flag to the
 * screen's UI state. Exhaustive over [ConsentOutcome] — no generic fallthrough (design D5), and the
 * banner is an enum of static keys so it cannot leak PII.
 *
 * - in-flight ⇒ LOADING label, disabled, no banner, no skip.
 * - [ConsentOutcome.RetryableError] ⇒ RETRY label, banner RETRYABLE, enabled, **showSkip = true**
 *   (the non-trapping proceed-anyway affordance appears ONLY after a failed submit).
 * - [ConsentOutcome.TokenInvalid] ⇒ CONTINUE label, banner TOKEN_INVALID, enabled, no skip.
 * - [ConsentOutcome.Success] / [ConsentOutcome.Cancelled] / null ⇒ CONTINUE label, no banner,
 *   enabled, **no skip** (the happy path shows a single CTA; consent never reads as optional).
 *
 * The CTA is always enabled when not in flight: the three toggles are always in a valid boolean
 * state, so (unlike the age gate's DOB gate) there is nothing to disable on.
 */
fun consentUiState(
    outcome: ConsentOutcome?,
    inFlight: Boolean,
): ConsentUiState {
    if (inFlight) {
        return ConsentUiState(
            ctaLabel = ConsentCtaLabel.LOADING,
            ctaEnabled = false,
            banner = null,
            showSkip = false,
        )
    }
    val banner =
        when (outcome) {
            ConsentOutcome.RetryableError -> ConsentBanner.RETRYABLE
            ConsentOutcome.TokenInvalid -> ConsentBanner.TOKEN_INVALID
            ConsentOutcome.Success, ConsentOutcome.Cancelled, null -> null
        }
    val label =
        if (outcome == ConsentOutcome.RetryableError) ConsentCtaLabel.RETRY else ConsentCtaLabel.CONTINUE
    val showSkip = outcome == ConsentOutcome.RetryableError
    return ConsentUiState(ctaLabel = label, ctaEnabled = true, banner = banner, showSkip = showSkip)
}
