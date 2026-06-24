package id.nearyou.app.screens.consent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import id.nearyou.app.consent.ConsentFlow
import id.nearyou.app.consent.ConsentOutcome
import id.nearyou.app.data.consent.ConsentSnapshotStore
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.consent_ads_desc
import id.nearyou.resources.generated.resources.consent_ads_label
import id.nearyou.resources.generated.resources.consent_analytics_desc
import id.nearyou.resources.generated.resources.consent_analytics_label
import id.nearyou.resources.generated.resources.consent_crash_desc
import id.nearyou.resources.generated.resources.consent_crash_label
import id.nearyou.resources.generated.resources.consent_cta_continue
import id.nearyou.resources.generated.resources.consent_error_retryable
import id.nearyou.resources.generated.resources.consent_explainer
import id.nearyou.resources.generated.resources.consent_skip
import id.nearyou.resources.generated.resources.consent_title
import id.nearyou.resources.generated.resources.cta_retry
import id.nearyou.resources.generated.resources.loading
import id.nearyou.resources.generated.resources.signin_error_token_invalid
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/**
 * The UU-PDP Analytics & Tracking Consent onboarding surface (the `mobile-analytics-consent`
 * capability), reached after age-gate signup success ([ConsentRoute][id.nearyou.app.screens.routing.ConsentRoute],
 * which REPLACES `AgeGateRoute` so back-press cannot re-enter the age gate). Renders the title +
 * explainer + three Material 3 `Switch` rows (Analytics / Crash Reporting / Ads Personalization) +
 * a "Simpan & lanjutkan" CTA, all theme-aware and consistent with `AgeGateScreen`.
 *
 * **Defaults (design D3):** the toggles start at the V2 column default the just-created account
 * already holds — analytics OFF, crash ON, ads OFF — hardcoded via the [initialAnalytics] /
 * [initialCrash] / [initialAdsPersonalization] params (injectable for testability). The screen
 * issues NO GET on entry (the values are known, not fetched).
 *
 * **Non-trapping failure (design D4):** on a [ConsentOutcome.RetryableError] the screen shows a
 * retryable banner + the `consent_skip` proceed-to-Home affordance; on the happy path (before any
 * failed submit) the skip is NOT shown, so consent does not read as optional.
 *
 * **PII discipline:** every string flows through `stringResource(Res.string.*)`; the screen never
 * logs or renders any token / identity payload.
 */
@Composable
fun ConsentScreen(
    onDone: () -> Unit,
    initialAnalytics: Boolean = false,
    initialCrash: Boolean = true,
    initialAdsPersonalization: Boolean = false,
) {
    val consentFlow = koinInject<ConsentFlow>()
    val snapshotStore = koinInject<ConsentSnapshotStore>()

    // Entry-scoped state holder (docs/11 §2.2): the three toggles + outcome + in-flight flag now live in
    // the ViewModel, so the toggle selections survive a configuration change and the PATCH (on
    // viewModelScope) is not cancelled by recomposition. The submit-200 snapshot write happens in the VM
    // BEFORE the `done` one-shot (reusing the handleConsentTerminalOutcome decision).
    val viewModel =
        viewModel {
            ConsentViewModel(consentFlow, snapshotStore, initialAnalytics, initialCrash, initialAdsPersonalization)
        }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val ctaText =
        when (uiState.ctaLabel) {
            ConsentCtaLabel.CONTINUE -> stringResource(Res.string.consent_cta_continue)
            ConsentCtaLabel.RETRY -> stringResource(Res.string.cta_retry)
            ConsentCtaLabel.LOADING -> stringResource(Res.string.loading)
        }

    val bannerText: String? =
        uiState.banner?.let { banner ->
            when (banner) {
                ConsentBanner.RETRYABLE -> stringResource(Res.string.consent_error_retryable)
                ConsentBanner.TOKEN_INVALID -> stringResource(Res.string.signin_error_token_invalid)
            }
        }

    // Navigate from an effect (never mutate the back stack during composition). The VM raises `done` on a
    // submit-200 (via the reused handleConsentTerminalOutcome seam); we route Home and consume the one-shot.
    LaunchedEffect(uiState.done) {
        if (uiState.done) {
            onDone()
            viewModel.onDoneHandled()
        }
    }

    Column(
        modifier =
            Modifier
                .background(MaterialTheme.colorScheme.background)
                .safeContentPadding()
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(Res.string.consent_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(Res.string.consent_explainer),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )

        // The toggles are disabled while a submit is in flight. For the consent screen
        // `consentUiState` makes `ctaEnabled == !inFlight` exactly, so it is the faithful disable signal.
        ConsentToggleRow(
            label = stringResource(Res.string.consent_analytics_label),
            description = stringResource(Res.string.consent_analytics_desc),
            checked = uiState.analytics,
            onCheckedChange = viewModel::onAnalyticsChange,
            enabled = uiState.ctaEnabled,
        )
        ConsentToggleRow(
            label = stringResource(Res.string.consent_crash_label),
            description = stringResource(Res.string.consent_crash_desc),
            checked = uiState.crash,
            onCheckedChange = viewModel::onCrashChange,
            enabled = uiState.ctaEnabled,
        )
        ConsentToggleRow(
            label = stringResource(Res.string.consent_ads_label),
            description = stringResource(Res.string.consent_ads_desc),
            checked = uiState.ads,
            onCheckedChange = viewModel::onAdsChange,
            enabled = uiState.ctaEnabled,
        )

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
                // Defense-in-depth: reject taps when the CTA is logically disabled (in-flight),
                // even if a synthetic click slips past `enabled`.
                if (!uiState.ctaEnabled) return@Button
                viewModel.onContinueClick()
            },
            enabled = uiState.ctaEnabled,
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
        ) {
            Text(text = ctaText)
        }

        // Non-trapping proceed-to-Home affordance — shown ONLY after a failed submit (design D4).
        if (uiState.showSkip) {
            TextButton(
                onClick = onDone,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text(text = stringResource(Res.string.consent_skip))
            }
        }
    }
}

@Composable
private fun ConsentToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/**
 * The terminal-exit decision for [ConsentScreen], extracted as a pure seam so the route-on-success
 * contract is unit-testable without driving the Compose tree:
 *
 *  - [ConsentOutcome.Success] → [onDone] (→ HomeRoute).
 *  - [ConsentOutcome.RetryableError] / [ConsentOutcome.TokenInvalid] / [ConsentOutcome.Cancelled] /
 *    `null` → no-op: the user stays on the screen (retry / read the banner; the post-failure skip is
 *    a separate explicit user action, not an automatic navigation).
 */
internal fun handleConsentTerminalOutcome(
    outcome: ConsentOutcome?,
    onDone: () -> Unit,
) {
    when (outcome) {
        ConsentOutcome.Success -> onDone()
        else -> Unit
    }
}
