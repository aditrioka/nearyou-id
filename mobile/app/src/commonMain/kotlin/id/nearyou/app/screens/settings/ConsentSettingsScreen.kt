package id.nearyou.app.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import id.nearyou.app.consent.ConsentFlow
import id.nearyou.app.consent.ConsentOutcome
import id.nearyou.app.data.consent.ConsentSnapshotStore
import id.nearyou.app.diagnostics.CrashReportingController
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.consent_ads_desc
import id.nearyou.resources.generated.resources.consent_ads_label
import id.nearyou.resources.generated.resources.consent_analytics_desc
import id.nearyou.resources.generated.resources.consent_analytics_label
import id.nearyou.resources.generated.resources.consent_crash_desc
import id.nearyou.resources.generated.resources.consent_crash_label
import id.nearyou.resources.generated.resources.consent_error_retryable
import id.nearyou.resources.generated.resources.consent_explainer
import id.nearyou.resources.generated.resources.consent_title
import id.nearyou.resources.generated.resources.loading
import id.nearyou.resources.generated.resources.settings_save
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/**
 * The Settings › Privasi › "Privasi & data" sub-surface (`mobile-settings` § "Consent settings"). A
 * pushed root-stack overlay owning its own Scaffold + back bar. Renders the three analytics-consent
 * toggles (REUSING the `consent_*` strings) seeded by a `ConsentSettingsRoute`-scoped
 * [ConsentSettingsViewModel] (from the device-local snapshot or the V2 defaults) and submits via the
 * reused [ConsentFlow] (`PATCH /api/v1/user/consent`). A terminal `401` routes to sign-in via
 * [onTokenInvalid]; a `5xx`/IO/`400` shows a retryable in-screen banner. No token/sub/body is logged
 * (PII discipline, reusing the `mobile-analytics-consent` posture). All copy via `:shared:resources`.
 */
@Composable
fun ConsentSettingsScreen(
    onBack: () -> Unit,
    onTokenInvalid: () -> Unit,
) {
    val flow = koinInject<ConsentFlow>()
    val snapshotStore = koinInject<ConsentSnapshotStore>()
    val crashController = koinInject<CrashReportingController>()
    val viewModel =
        viewModel {
            ConsentSettingsViewModel(flow, snapshotStore, onCrashConsentChanged = crashController::applyConsent)
        }

    val analytics by viewModel.analytics.collectAsStateWithLifecycle()
    val crash by viewModel.crash.collectAsStateWithLifecycle()
    val ads by viewModel.ads.collectAsStateWithLifecycle()
    val outcome by viewModel.outcome.collectAsStateWithLifecycle()
    val inFlight by viewModel.inFlight.collectAsStateWithLifecycle()

    // Terminal 401 → route to sign-in (the just-verified token failed; rare).
    LaunchedEffect(outcome) {
        if (outcome == ConsentOutcome.TokenInvalid) onTokenInvalid()
    }

    val showRetryableBanner = outcome == ConsentOutcome.RetryableError
    val ctaText = if (inFlight) stringResource(Res.string.loading) else stringResource(Res.string.settings_save)

    Scaffold(
        topBar = { SettingsTopBar(title = stringResource(Res.string.consent_title), onBack = onBack) },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(MaterialTheme.colorScheme.background)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            Text(
                text = stringResource(Res.string.consent_explainer),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            ConsentSettingsToggleRow(
                label = stringResource(Res.string.consent_analytics_label),
                description = stringResource(Res.string.consent_analytics_desc),
                checked = analytics,
                onCheckedChange = viewModel::setAnalytics,
                enabled = !inFlight,
            )
            ConsentSettingsToggleRow(
                label = stringResource(Res.string.consent_crash_label),
                description = stringResource(Res.string.consent_crash_desc),
                checked = crash,
                onCheckedChange = viewModel::setCrash,
                enabled = !inFlight,
            )
            ConsentSettingsToggleRow(
                label = stringResource(Res.string.consent_ads_label),
                description = stringResource(Res.string.consent_ads_desc),
                checked = ads,
                onCheckedChange = viewModel::setAds,
                enabled = !inFlight,
            )

            if (showRetryableBanner) {
                Text(
                    text = stringResource(Res.string.consent_error_retryable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }

            Button(
                onClick = { if (!inFlight) viewModel.save() },
                enabled = !inFlight,
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
            ) {
                Text(text = ctaText)
            }
        }
    }
}

@Composable
private fun ConsentSettingsToggleRow(
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
                color = MaterialTheme.colorScheme.onSurface,
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
