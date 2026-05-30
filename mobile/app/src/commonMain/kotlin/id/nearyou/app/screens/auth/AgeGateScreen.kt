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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import id.nearyou.app.auth.AuthFlow
import id.nearyou.app.auth.SignUpOutcome
import id.nearyou.app.screens.home.HomeScreen
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
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/**
 * Mobile #4 signup-new-user surface, reached when sign-in reports no existing account
 * (`SignInOutcome.NoAccount` → `SignInScreen` pushes this screen). Renders the brand logo + age
 * title + explainer + a date-of-birth field (Material 3 `DatePicker`) + a "Buat akun" CTA, all
 * theme-aware and consistent with `SignInScreen`/`HomeScreen`.
 *
 * The verified Google [idToken] is carried in from the sign-in `404` (design D3) — reused for the
 * `/signup` call, NEVER re-fetched on entry (no second Google sheet) and NEVER rendered into any
 * UI string. The Google `email`/`displayName` are deliberately NOT passed to this screen (PII
 * discipline, design D7) — only the `id_token` is needed for the request body.
 *
 * The DOB picker is NOT constrained to 18+ (design D1): an under-18 date MUST be selectable and
 * submittable so the server can populate the anti-DOB-shopping blocklist; client validation is
 * format/sanity only (not in the future), via [isDobSubmittable] against the injected [today].
 *
 * Every user-facing string is sourced via `stringResource(Res.string.*)` — no literals.
 */
class AgeGateScreen(
    private val idToken: String,
    private val today: LocalDate = systemToday(),
) : Screen {
    @Composable
    override fun Content() {
        val authFlow = koinInject<AuthFlow>()
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()

        var selectedDobMillis by remember { mutableStateOf<Long?>(null) }
        var showPicker by remember { mutableStateOf(false) }
        var outcome by remember { mutableStateOf<SignUpOutcome?>(null) }
        var inFlight by remember { mutableStateOf(false) }

        val selectedDob: LocalDate? = selectedDobMillis?.let { dobFromUtcMillis(it) }
        val uiState =
            ageGateUiState(
                outcome = outcome,
                dobSubmittable = isDobSubmittable(selectedDob, today),
                inFlight = inFlight,
            )

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
        val dobFieldText = selectedDob?.toString() ?: stringResource(Res.string.age_gate_dob_picker_cta)

        // Navigate from an effect (never mutate the navigator during composition):
        //  - Success → replace the stack with Home (same terminus as sign-in).
        //  - AccountExists (409) → route back to SignInScreen.
        // Blocked / InvalidIdToken / RetryableError keep the user on this screen to read the banner.
        LaunchedEffect(outcome) {
            when (outcome) {
                SignUpOutcome.Success -> navigator.replaceAll(HomeScreen())
                SignUpOutcome.AccountExists -> navigator.replaceAll(SignInScreen())
                else -> Unit
            }
        }

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
                modifier = Modifier.size(120.dp),
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
                onClick = { showPicker = true },
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
                    val dob = selectedDob ?: return@Button
                    scope.launch {
                        inFlight = true
                        try {
                            outcome = authFlow.signUpWithGoogle(idToken, dob)
                        } finally {
                            // Reset even if the launch job is cancelled mid-call (config change /
                            // screen disposal) so the CTA never sticks on "loading".
                            inFlight = false
                        }
                    }
                },
                enabled = uiState.ctaEnabled,
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
            ) {
                Text(text = ctaText)
            }
        }

        if (showPicker) {
            DobPickerDialog(
                initialMillis = selectedDobMillis,
                today = today,
                onConfirm = { picked ->
                    if (picked != null) selectedDobMillis = picked
                    showPicker = false
                },
                onDismiss = { showPicker = false },
            )
        }
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
