package id.nearyou.app.screens.auth

import id.nearyou.app.auth.SignUpOutcome
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

/** Which string the create-account CTA shows. The Compose layer maps each to a `stringResource`. */
enum class AgeGateCtaLabel {
    /** `cta_create_account` — initial + after a non-retryable terminal outcome. */
    CREATE,

    /** `cta_retry` — after a [SignUpOutcome.RetryableError]. */
    RETRY,

    /** `signup_loading` — while a signup is in flight. */
    LOADING,
}

/** Which banner (if any) is shown. Deliberately an enum of static message keys — it can NEVER
 *  carry the Google `email` / `displayName` PII into the UI (design D7, mirroring
 *  `SignInErrorBanner`). */
enum class AgeGateBanner {
    /** `age_gate_under18_blocked` — the generic `403 user_blocked` copy (under-18 / rejected). */
    BLOCKED,

    /** `signup_error_account_exists` — `409 user_exists` (shown transiently before routing to sign-in). */
    ACCOUNT_EXISTS,

    /** `signin_error_token_invalid` — terminal `invalid_id_token` after one refresh+retry. */
    TOKEN_INVALID,

    /** `signin_error_network` — `5xx` / `503` / IO / `400` retryable error. */
    NETWORK,
}

/** Pure, Compose-free projection of the age-gate screen UI state, mirroring `SignInUiState`. */
data class AgeGateUiState(
    val ctaLabel: AgeGateCtaLabel,
    val ctaEnabled: Boolean,
    val banner: AgeGateBanner?,
)

/**
 * Maps the current [SignUpOutcome] (null = fresh / never-submitted) + whether a submittable DOB is
 * selected + the in-flight flag to the screen's UI state. Exhaustive over [SignUpOutcome] — no
 * generic fallthrough (design D8), and the banner is an enum of static keys so it cannot leak PII.
 *
 * - in-flight ⇒ LOADING label, disabled, no banner.
 * - [SignUpOutcome.RetryableError] ⇒ RETRY label, banner NETWORK, enabled iff a DOB is submittable.
 * - [SignUpOutcome.Blocked] / [SignUpOutcome.AccountExists] / [SignUpOutcome.InvalidIdToken] ⇒
 *   CREATE label, the matching banner, enabled iff a DOB is submittable (the client never
 *   hard-blocks — D1; the user may re-pick and re-submit).
 * - [SignUpOutcome.Success] / [SignUpOutcome.Cancelled] / null ⇒ CREATE label, no banner, enabled
 *   iff a DOB is submittable.
 */
fun ageGateUiState(
    outcome: SignUpOutcome?,
    dobSubmittable: Boolean,
    inFlight: Boolean,
): AgeGateUiState {
    if (inFlight) {
        return AgeGateUiState(ctaLabel = AgeGateCtaLabel.LOADING, ctaEnabled = false, banner = null)
    }
    val banner =
        when (outcome) {
            SignUpOutcome.Blocked -> AgeGateBanner.BLOCKED
            SignUpOutcome.AccountExists -> AgeGateBanner.ACCOUNT_EXISTS
            SignUpOutcome.InvalidIdToken -> AgeGateBanner.TOKEN_INVALID
            SignUpOutcome.RetryableError -> AgeGateBanner.NETWORK
            SignUpOutcome.Success, SignUpOutcome.Cancelled, null -> null
        }
    val label = if (outcome == SignUpOutcome.RetryableError) AgeGateCtaLabel.RETRY else AgeGateCtaLabel.CREATE
    return AgeGateUiState(ctaLabel = label, ctaEnabled = dobSubmittable, banner = banner)
}

/**
 * Converts a Material 3 `DatePicker` selection (UTC-midnight epoch millis from
 * `DatePickerState.selectedDateMillis`) to a calendar [LocalDate]. The picker reports its
 * selection at UTC midnight, so the date is read in [TimeZone.UTC] (NOT the system zone) to avoid
 * an off-by-one-day shift.
 */
fun dobFromUtcMillis(utcMillis: Long): LocalDate = Instant.fromEpochMilliseconds(utcMillis).toLocalDateTime(TimeZone.UTC).date

/**
 * Client-side DOB validation — format/sanity ONLY (design D1): a DOB is submittable iff it is
 * non-null and NOT in the future relative to [today]. The client deliberately does NOT apply an
 * 18+ gate: an under-18 DOB MUST reach `POST /api/v1/auth/signup` so the server can write the
 * `rejected_identifiers` blocklist row. Both `today − 18y` (exactly 18) and `today − 18y + 1 day`
 * (one day under) return `true` here — the 18+ boundary is the server's call, never the client's.
 *
 * [today] is injected (rather than read from a wall clock inside this function) so the under-18,
 * exactly-18-boundary, and future-date checks are deterministically testable.
 */
fun isDobSubmittable(
    dob: LocalDate?,
    today: LocalDate,
): Boolean = dob != null && dob <= today

/** "Today" in the system time zone — the production default for the [AgeGateScreen] DOB validation. */
fun systemToday(): LocalDate = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
