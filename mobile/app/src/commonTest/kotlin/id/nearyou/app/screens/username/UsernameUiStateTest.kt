package id.nearyou.app.screens.username

import id.nearyou.app.username.UsernameChangeOutcome
import id.nearyou.app.username.UsernameCheckOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Coverage of the pure [usernameUiState] projection: each outcome maps deterministically to its
 * [UsernameStatus], the editing inline hint follows the format guard + probe, the countdown floors are
 * applied (non-positive → 1 day / 1 minute), and `submitEnabled` is gated on format-validity + a real change.
 */
class UsernameUiStateTest {
    private fun project(
        currentUsername: String = "oldname",
        candidate: String = "newname",
        isPremiumKnown: Boolean? = true,
        checkOutcome: UsernameCheckOutcome? = null,
        changeOutcome: UsernameChangeOutcome? = null,
        isSubmitting: Boolean = false,
        confirmVisible: Boolean = false,
        changed: Boolean = false,
    ): UsernameUiState =
        usernameUiState(
            currentUsername = currentUsername,
            candidate = candidate,
            isPremiumKnown = isPremiumKnown,
            checkOutcome = checkOutcome,
            changeOutcome = changeOutcome,
            isSubmitting = isSubmitting,
            confirmVisible = confirmVisible,
            changed = changed,
        )

    @Test
    fun `on-entry resolution and gate`() {
        assertEquals(UsernameStatus.Resolving, project(isPremiumKnown = null).status)
        assertEquals(UsernameStatus.PremiumGate, project(isPremiumKnown = false).status)
        assertEquals(UsernameStatus.PremiumGate, project(changeOutcome = UsernameChangeOutcome.PremiumGate).status)
        assertEquals(UsernameStatus.PremiumGate, project(checkOutcome = UsernameCheckOutcome.CheckPremiumGate).status)
    }

    @Test
    fun `editing hint follows format then probe`() {
        assertEquals(UsernameStatus.Editing(AvailabilityHint.FormatInvalid), project(candidate = "ab").status)
        assertEquals(
            UsernameStatus.Editing(AvailabilityHint.Available),
            project(checkOutcome = UsernameCheckOutcome.Available(true)).status,
        )
        assertEquals(
            UsernameStatus.Editing(AvailabilityHint.Unavailable),
            project(checkOutcome = UsernameCheckOutcome.Available(false)).status,
        )
        assertEquals(
            UsernameStatus.Editing(AvailabilityHint.ProbeDeferred),
            project(checkOutcome = UsernameCheckOutcome.ProbeExhausted).status,
        )
        assertEquals(UsernameStatus.Editing(AvailabilityHint.ProbeDeferred), project(checkOutcome = null).status)
        // Empty / unchanged candidate → no inline status.
        assertEquals(UsernameStatus.Editing(AvailabilityHint.None), project(candidate = "").status)
        assertEquals(UsernameStatus.Editing(AvailabilityHint.None), project(candidate = "oldname").status)
    }

    @Test
    fun `submitting overrides a stale change outcome`() {
        assertEquals(UsernameStatus.Submitting, project(isSubmitting = true).status)
    }

    @Test
    fun `post-submit change errors map to their statuses`() {
        assertEquals(UsernameStatus.Unavailable, project(changeOutcome = UsernameChangeOutcome.Unavailable).status)
        assertEquals(UsernameStatus.Moderated, project(changeOutcome = UsernameChangeOutcome.Moderated).status)
        assertEquals(UsernameStatus.Error, project(changeOutcome = UsernameChangeOutcome.Error).status)
        assertEquals(UsernameStatus.Error, project(changeOutcome = UsernameChangeOutcome.NetworkError).status)
        assertEquals(UsernameStatus.SessionRedirect, project(changeOutcome = UsernameChangeOutcome.SessionExpired).status)
        assertEquals(UsernameStatus.Disabled, project(changeOutcome = UsernameChangeOutcome.Disabled).status)
    }

    @Test
    fun `cooldown and rate-limit render the countdowns`() {
        assertEquals(
            UsernameStatus.CooldownActive(14),
            project(changeOutcome = UsernameChangeOutcome.CooldownActive(1_209_600L)).status,
            "1209600s = 14 days",
        )
        assertEquals(
            UsernameStatus.RateLimited(30),
            project(changeOutcome = UsernameChangeOutcome.RateLimited(1_800L)).status,
            "1800s = 30 minutes",
        )
    }

    @Test
    fun `a non-positive countdown floors to one day and one minute`() {
        assertEquals(UsernameStatus.CooldownActive(1), project(changeOutcome = UsernameChangeOutcome.CooldownActive(0L)).status)
        assertEquals(UsernameStatus.RateLimited(1), project(changeOutcome = UsernameChangeOutcome.RateLimited(0L)).status)
    }

    @Test
    fun `submit is enabled only for a format-valid changed candidate while editing`() {
        assertTrue(project(candidate = "newname", currentUsername = "oldname").submitEnabled)
        assertFalse(project(candidate = "ab").submitEnabled, "format-invalid")
        assertFalse(project(candidate = "oldname", currentUsername = "oldname").submitEnabled, "unchanged")
        assertFalse(project(isSubmitting = true).submitEnabled, "not in the Editing status")
        assertFalse(project(changeOutcome = UsernameChangeOutcome.Unavailable).submitEnabled, "not in the Editing status")
    }

    @Test
    fun `the changed one-shot propagates`() {
        assertTrue(project(changed = true).changed)
        assertFalse(project().changed)
    }
}
