package id.nearyou.app.screens.auth

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import id.nearyou.app.auth.AuthFlow
import id.nearyou.app.auth.SignUpOutcome
import id.nearyou.app.screens.routing.PendingSignupIdentity
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.age_gate_dob_label
import id.nearyou.resources.generated.resources.age_gate_dob_picker_cta
import id.nearyou.resources.generated.resources.age_gate_explainer
import id.nearyou.resources.generated.resources.age_gate_title
import id.nearyou.resources.generated.resources.age_gate_under18_blocked
import id.nearyou.resources.generated.resources.app_name
import id.nearyou.resources.generated.resources.cta_cancel
import id.nearyou.resources.generated.resources.cta_continue
import id.nearyou.resources.generated.resources.cta_create_account
import id.nearyou.resources.generated.resources.cta_retry
import id.nearyou.resources.generated.resources.logo_brand_dark
import id.nearyou.resources.generated.resources.logo_brand_light
import id.nearyou.resources.generated.resources.signin_error_network
import id.nearyou.resources.generated.resources.signin_error_token_invalid
import id.nearyou.resources.generated.resources.signup_error_account_exists
import id.nearyou.resources.generated.resources.signup_loading
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/**
 * Mobile #4 signup-new-user surface ([AgeGateRoute][id.nearyou.app.screens.routing.AgeGateRoute]),
 * reached when sign-in reports no existing account (404 → `SignInScreen` sets [PendingSignupIdentity]
 * and appends `AgeGateRoute`). Renders the brand logo + age title + explainer + a date-of-birth field
 * (Material 3 `DatePicker`) + a "Buat akun" CTA, all theme-aware and consistent with
 * `SignInScreen`/`HomeScreen`.
 *
 * **Identity (design Decision 4):** the verified Google `id_token` is read from the in-memory
 * [PendingSignupIdentity] holder via a **non-clearing** [PendingSignupIdentity.peek] (so a network
 * retry can resubmit the same identity) — NOT from an `AgeGateRoute` field (which would serialize it
 * on iOS). It is reused for the `/signup` call, NEVER re-fetched on entry (no second Google sheet)
 * and NEVER rendered into any UI string.
 *
 * **Process-death guard (`mobile-age-gate` § "Restored back stack on AgeGateRoute with absent
 * identity"):** a restored back stack can land on `AgeGateRoute` with the in-memory holder empty
 * (it does not survive process death). On entry with an absent identity the screen emits a one-shot
 * re-route to `SignInRoute` (via [onExitToSignIn]) and does NOT render the signup form.
 *
 * **Holder lifecycle:** [PendingSignupIdentity.clear] is called on every **terminal** exit — Success
 * (→ Home via [onSignedUp]), AccountExists (→ SignIn via [onExitToSignIn]), and the absent-identity
 * re-route (→ SignIn) — but NOT on a retryable in-screen error (network/5xx), where the user may
 * resubmit with the same identity.
 *
 * The DOB picker is NOT constrained to 18+ (design D1): an under-18 date MUST be selectable and
 * submittable so the server can populate the anti-DOB-shopping blocklist; client validation is
 * format/sanity only (not in the future), via [isDobSubmittable] against the injected [today].
 *
 * Every user-facing string is sourced via `stringResource(Res.string.*)` — no literals.
 */
@Composable
fun AgeGateScreen(
    onSignedUp: () -> Unit,
    onExitToSignIn: () -> Unit,
    today: LocalDate = systemToday(),
) {
    val authFlow = koinInject<AuthFlow>()
    val pendingSignupIdentity = koinInject<PendingSignupIdentity>()

    // Entry-scoped state holder (docs/11 §2.2): the picked DOB / picker visibility / outcome / in-flight
    // flag now live in the ViewModel, so the picked DOB survives a configuration change and the signup
    // call (on viewModelScope) is not cancelled by recomposition. The absent-identity process-death guard
    // is resolved synchronously in the VM's initial state (it reads the Koin-single holder before the first
    // composition), surfaced as `identityAbsent` + a SignIn re-route one-shot — so the form never renders.
    val viewModel = viewModel { AgeGateViewModel(authFlow, pendingSignupIdentity, today) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Consume the one-shot navigation (never mutate the back stack during composition). Covers BOTH the
    // terminal signup outcomes (Home/SignIn, via the reused handleAgeGateTerminalOutcome seam) and the
    // absent-identity re-route (SignIn) raised in the VM's initial state.
    LaunchedEffect(uiState.navigation) {
        when (uiState.navigation) {
            AgeGateNavTarget.Home -> onSignedUp()
            AgeGateNavTarget.SignIn -> onExitToSignIn()
            null -> Unit
        }
        if (uiState.navigation != null) viewModel.onNavigationHandled()
    }

    // Absent identity (process death / restored back stack): the VM has already cleared the holder and
    // raised the SignIn re-route above — render no signup form (gated on uiState, never a LaunchedEffect).
    if (uiState.identityAbsent) return

    val logo =
        if (isSystemInDarkTheme()) Res.drawable.logo_brand_dark else Res.drawable.logo_brand_light

    val ctaText =
        when (uiState.ctaLabel) {
            AgeGateCtaLabel.CREATE -> stringResource(Res.string.cta_create_account)
            AgeGateCtaLabel.RETRY -> stringResource(Res.string.cta_retry)
            AgeGateCtaLabel.LOADING -> stringResource(Res.string.signup_loading)
        }

    val bannerText: String? =
        uiState.banner?.let { banner ->
            when (banner) {
                AgeGateBanner.BLOCKED -> stringResource(Res.string.age_gate_under18_blocked)
                AgeGateBanner.ACCOUNT_EXISTS -> stringResource(Res.string.signup_error_account_exists)
                AgeGateBanner.TOKEN_INVALID -> stringResource(Res.string.signin_error_token_invalid)
                AgeGateBanner.NETWORK -> stringResource(Res.string.signin_error_network)
            }
        }

    // The DOB field shows the picked ISO date (YYYY-MM-DD) or the picker affordance copy.
    val dobFieldText = uiState.selectedDob?.toString() ?: stringResource(Res.string.age_gate_dob_picker_cta)

    Column(
        modifier =
            Modifier
                .background(MaterialTheme.colorScheme.background)
                .safeContentPadding()
                .fillMaxSize()
                .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            painter = painterResource(logo),
            contentDescription = stringResource(Res.string.app_name),
            modifier = Modifier.size(96.dp),
        )
        Text(
            text = stringResource(Res.string.age_gate_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 24.dp),
        )
        Text(
            text = stringResource(Res.string.age_gate_explainer),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            text = stringResource(Res.string.age_gate_dob_label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
        )
        OutlinedButton(
            onClick = { viewModel.onShowPicker(true) },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            Text(text = dobFieldText)
        }
        if (bannerText != null) {
            Text(
                text = bannerText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        Button(
            onClick = {
                // Defense-in-depth: reject taps when the CTA is logically disabled (no
                // submittable DOB / in-flight), even if a synthetic click slips past `enabled`.
                if (!uiState.ctaEnabled) return@Button
                viewModel.onCreateAccountClick()
            },
            enabled = uiState.ctaEnabled,
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
        ) {
            Text(text = ctaText)
        }
    }

    if (uiState.showPicker) {
        DobPickerDialog(
            initialMillis = uiState.selectedDobMillis,
            today = today,
            onConfirm = { picked -> viewModel.onDobPicked(picked) },
            onDismiss = { viewModel.onShowPicker(false) },
        )
    }
}

/**
 * The terminal-exit decision for [AgeGateScreen], extracted as a pure seam so the holder-lifecycle
 * contract (design Decision 4 + `mobile-age-gate` § "The pending identity is cleared on every terminal
 * exit but survives a retryable error") is unit-testable without driving the Material 3 DOB picker:
 *
 *  - [SignUpOutcome.Success] → `clear()` the in-memory identity + [onSignedUp] (→ HomeRoute).
 *  - [SignUpOutcome.AccountExists] (409) → `clear()` + [onExitToSignIn] (→ SignInRoute).
 *  - [SignUpOutcome.Blocked] / [SignUpOutcome.InvalidIdToken] / [SignUpOutcome.RetryableError] /
 *    [SignUpOutcome.Cancelled] / `null` → no-op: the user stays on the screen to read the banner, and
 *    the holder is NOT cleared (a retryable error may be resubmitted with the same identity).
 */
internal fun handleAgeGateTerminalOutcome(
    outcome: SignUpOutcome?,
    pendingSignupIdentity: PendingSignupIdentity,
    onSignedUp: () -> Unit,
    onExitToSignIn: () -> Unit,
) {
    when (outcome) {
        SignUpOutcome.Success -> {
            pendingSignupIdentity.clear()
            onSignedUp()
        }
        SignUpOutcome.AccountExists -> {
            pendingSignupIdentity.clear()
            onExitToSignIn()
        }
        else -> Unit
    }
}

/**
 * Material 3 DOB `DatePicker` in a dialog. `selectableDates` rejects future dates (format/sanity
 * per design D1) but does NOT constrain to 18+ — under-18 dates remain selectable. The `yearRange`
 * ends at [today]'s year for the same reason (no future years; all ages 0+ allowed).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DobPickerDialog(
    initialMillis: Long?,
    today: LocalDate,
    onConfirm: (Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    val state =
        rememberDatePickerState(
            initialSelectedDateMillis = initialMillis,
            yearRange = MIN_DOB_YEAR..today.year,
            selectableDates = NonFutureSelectableDates(today),
        )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(state.selectedDateMillis) }) {
                Text(text = stringResource(Res.string.cta_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(Res.string.cta_cancel))
            }
        },
    ) {
        DatePicker(state = state)
    }
}

/** Earliest selectable birth year — generous lower bound; the gate is the server, not this range. */
private const val MIN_DOB_YEAR = 1900

/**
 * Allows any date up to and including [today]; rejects future dates. Deliberately does NOT exclude
 * under-18 dates (design D1) — only future dates are format/sanity-invalid.
 */
@OptIn(ExperimentalMaterial3Api::class)
private class NonFutureSelectableDates(private val today: LocalDate) : SelectableDates {
    override fun isSelectableDate(utcTimeMillis: Long): Boolean = dobFromUtcMillis(utcTimeMillis) <= today

    override fun isSelectableYear(year: Int): Boolean = year <= today.year
}
