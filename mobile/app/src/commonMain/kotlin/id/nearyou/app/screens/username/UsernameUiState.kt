package id.nearyou.app.screens.username

import id.nearyou.app.ui.components.capCountdownMinutes
import id.nearyou.app.username.UsernameChangeOutcome
import id.nearyou.app.username.UsernameCheckOutcome
import id.nearyou.app.username.UsernameFormatGuard
import kotlin.math.ceil

/** The inline status line shown under the field while editing (the docs/03 §119–125 availability/format
 *  feedback). Derived from the LOCAL format guard + the network probe outcome — never PII. */
enum class AvailabilityHint {
    /** Empty candidate, or unchanged from the current handle → no status line. */
    None,

    /** Fails the local format guard (length / charset / consecutive dots) → the inline format message. */
    FormatInvalid,

    /** The probe reported the candidate available. */
    Available,

    /** The probe reported the candidate unavailable (the single generic message — the probe carries no reason). */
    Unavailable,

    /** The probe is exhausted (3/day) / not-yet-run / failed → "akan dicek saat kamu simpan". */
    ProbeDeferred,
}

/**
 * The mutually-exclusive top-level mode of the Ganti Username surface. [Editing] is the normal surface
 * (field + inline [AvailabilityHint]); the change-attempt error modes (Unavailable / Moderated /
 * CooldownActive / RateLimited / Error) render the prominent inline message after a failed submit and
 * clear on the next edit; [PremiumGate] / [Disabled] / [SessionRedirect] are full-screen takeovers;
 * [Resolving] is the brief on-entry window before the self-`isPremium` read resolves.
 */
sealed interface UsernameStatus {
    data object Resolving : UsernameStatus

    data class Editing(val hint: AvailabilityHint) : UsernameStatus

    data object Submitting : UsernameStatus

    data object PremiumGate : UsernameStatus

    /** 409 `username_unavailable` (reserved / on release hold / taken / lost race — one generic message). */
    data object Unavailable : UsernameStatus

    /** 422 `username_rejected` (the profanity / UU-ITE moderation soft flag). */
    data object Moderated : UsernameStatus

    /** 429 `cooldown_active` — the day-countdown (whole days, rounded up, floored to 1). */
    data class CooldownActive(val days: Int) : UsernameStatus

    /** 429 `rate_limited` — the minute-countdown (the shared `capCountdownMinutes`, floored to 1). */
    data class RateLimited(val minutes: Int) : UsernameStatus

    data object Disabled : UsernameStatus

    data object SessionRedirect : UsernameStatus

    /** 400 `invalid_request` / 5xx / network — the retryable connectivity message. */
    data object Error : UsernameStatus
}

/**
 * The Compose-free projection of the Ganti Username screen state, mirroring `searchUiState(...)`. A pure,
 * deterministic function of the VM's internal fields (no wall-clock — countdowns derive from the
 * `Retry-After` integers). The success one-shot ([changed]) is a field cleared via the VM's
 * `onChangedShown()`; the confirm dialog ([confirmVisible]) is plain UI state.
 */
data class UsernameUiState(
    val currentUsername: String = "",
    val candidate: String = "",
    val status: UsernameStatus = UsernameStatus.Resolving,
    val submitEnabled: Boolean = false,
    val confirmVisible: Boolean = false,
    val changed: Boolean = false,
)

private const val SECONDS_PER_DAY = 86_400.0

/** Whole days, rounded up, floored to 1 (never a flash-clear) — the cooldown copy's `%1$d hari`. */
fun cooldownDays(retryAfterSeconds: Long): Int = maxOf(1, ceil(retryAfterSeconds.toDouble() / SECONDS_PER_DAY).toInt())

/**
 * Maps the VM's internal fields to the screen state. Priority: terminal takeovers (session / kill switch
 * / premium gate) → on-entry resolving → submitting → post-submit change error → editing. The
 * [changeOutcome] / [checkOutcome] are the latest results (null = none yet); [isPremiumKnown] is the
 * on-entry self-read (null = resolving, false = not Premium, true = Premium).
 */
fun usernameUiState(
    currentUsername: String,
    candidate: String,
    isPremiumKnown: Boolean?,
    checkOutcome: UsernameCheckOutcome?,
    changeOutcome: UsernameChangeOutcome?,
    isSubmitting: Boolean,
    confirmVisible: Boolean,
    changed: Boolean,
): UsernameUiState {
    val status = computeStatus(currentUsername, candidate, isPremiumKnown, checkOutcome, changeOutcome, isSubmitting)
    val submitEnabled =
        status is UsernameStatus.Editing && UsernameFormatGuard.isValid(candidate) && candidate != currentUsername
    return UsernameUiState(
        currentUsername = currentUsername,
        candidate = candidate,
        status = status,
        submitEnabled = submitEnabled,
        confirmVisible = confirmVisible,
        changed = changed,
    )
}

private fun computeStatus(
    currentUsername: String,
    candidate: String,
    isPremiumKnown: Boolean?,
    checkOutcome: UsernameCheckOutcome?,
    changeOutcome: UsernameChangeOutcome?,
    isSubmitting: Boolean,
): UsernameStatus {
    // 1. Terminal takeovers (highest priority), from either endpoint.
    if (changeOutcome == UsernameChangeOutcome.SessionExpired || checkOutcome == UsernameCheckOutcome.CheckSessionExpired) {
        return UsernameStatus.SessionRedirect
    }
    if (changeOutcome == UsernameChangeOutcome.Disabled || checkOutcome == UsernameCheckOutcome.CheckDisabled) {
        return UsernameStatus.Disabled
    }
    if (changeOutcome == UsernameChangeOutcome.PremiumGate ||
        checkOutcome == UsernameCheckOutcome.CheckPremiumGate ||
        isPremiumKnown == false
    ) {
        return UsernameStatus.PremiumGate
    }
    // 2. On-entry resolution still pending.
    if (isPremiumKnown == null) return UsernameStatus.Resolving
    // 3. A submit is in flight.
    if (isSubmitting) return UsernameStatus.Submitting
    // 4. A completed (non-takeover) change attempt's inline result.
    when (changeOutcome) {
        UsernameChangeOutcome.Unavailable -> return UsernameStatus.Unavailable
        UsernameChangeOutcome.Moderated -> return UsernameStatus.Moderated
        is UsernameChangeOutcome.CooldownActive -> return UsernameStatus.CooldownActive(cooldownDays(changeOutcome.retryAfterSeconds))
        is UsernameChangeOutcome.RateLimited -> return UsernameStatus.RateLimited(capCountdownMinutes(changeOutcome.retryAfterSeconds))
        UsernameChangeOutcome.Error, UsernameChangeOutcome.NetworkError -> return UsernameStatus.Error
        else -> Unit // Success → fall through to Editing (the screen pops on `changed`).
    }
    // 5. The editing surface with its inline availability/format hint.
    return UsernameStatus.Editing(hint = editingHint(currentUsername, candidate, checkOutcome))
}

private fun editingHint(
    currentUsername: String,
    candidate: String,
    checkOutcome: UsernameCheckOutcome?,
): AvailabilityHint =
    when {
        candidate.isEmpty() || candidate == currentUsername -> AvailabilityHint.None
        !UsernameFormatGuard.isValid(candidate) -> AvailabilityHint.FormatInvalid
        else ->
            when (checkOutcome) {
                is UsernameCheckOutcome.Available ->
                    if (checkOutcome.isAvailable) AvailabilityHint.Available else AvailabilityHint.Unavailable
                UsernameCheckOutcome.CheckInvalidFormat -> AvailabilityHint.FormatInvalid
                // Exhausted / network / not-yet-run → checked-at-save (non-blocking).
                else -> AvailabilityHint.ProbeDeferred
            }
    }
