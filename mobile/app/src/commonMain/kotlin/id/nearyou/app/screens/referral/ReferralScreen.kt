package id.nearyou.app.screens.referral

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import id.nearyou.app.referral.ReferralRepository
import id.nearyou.app.screens.settings.SettingsCenteredMessage
import id.nearyou.app.screens.settings.SettingsTopBar
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.cta_retry
import id.nearyou.resources.generated.resources.error_generic
import id.nearyou.resources.generated.resources.loading
import id.nearyou.resources.generated.resources.referral_code_copied
import id.nearyou.resources.generated.resources.referral_code_heading
import id.nearyou.resources.generated.resources.referral_copy_action
import id.nearyou.resources.generated.resources.referral_explainer
import id.nearyou.resources.generated.resources.referral_progress
import id.nearyou.resources.generated.resources.referral_progress_heading
import id.nearyou.resources.generated.resources.referral_reward_unlocked
import id.nearyou.resources.generated.resources.referral_title
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/**
 * The Settings › "Undang teman" referral sub-surface (`mobile-referral`). A pushed root-stack overlay
 * owning its own Scaffold + back bar (the `ConsentSettingsScreen` / `BlockedUsersScreen` Settings
 * sub-surface idiom — no dedicated mockup frame exists; built from the `mobile-design-system`
 * substrate). Displays the user's shareable invite code with a copy-to-clipboard action
 * ([LocalClipboardManager]), the "X dari 5" inviter-reward progress, a distinct reward-unlocked state,
 * and explanatory copy. All copy via `:shared:resources`, under `NearYouTheme`.
 *
 * The surface is **open to all tiers** (design D3) — it carries no paywall/upsell. The displayed values
 * are the read endpoint's reported server state; the screen computes no reward and grants nothing.
 */
@Composable
fun ReferralScreen(onBack: () -> Unit) {
    val repository = koinInject<ReferralRepository>()
    val viewModel = viewModel { ReferralViewModel(repository) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    val codeCopiedText = stringResource(Res.string.referral_code_copied)

    Scaffold(
        topBar = { SettingsTopBar(title = stringResource(Res.string.referral_title), onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
            when (val state = uiState) {
                ReferralUiState.Loading -> SettingsCenteredMessage(stringResource(Res.string.loading))
                ReferralUiState.Error -> {
                    SettingsCenteredMessage(stringResource(Res.string.error_generic))
                    Button(
                        onClick = { viewModel.retry() },
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    ) {
                        Text(text = stringResource(Res.string.cta_retry))
                    }
                }
                is ReferralUiState.Loaded ->
                    ReferralLoadedContent(
                        state = state,
                        onCopy = {
                            clipboardManager.setText(AnnotatedString(state.inviteCode))
                            scope.launch { snackbarHostState.showSnackbar(codeCopiedText) }
                        },
                    )
            }
        }
    }
}

/** The loaded body: explainer, the invite code + copy action, progress, and the reward-unlocked state. */
@Composable
private fun ReferralLoadedContent(
    state: ReferralUiState.Loaded,
    onCopy: () -> Unit,
) {
    Text(
        text = stringResource(Res.string.referral_explainer),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Text(
        text = stringResource(Res.string.referral_code_heading),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
    )
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
    ) {
        Text(
            text = state.inviteCode,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
        )
    }
    Button(
        onClick = onCopy,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    ) {
        Text(text = stringResource(Res.string.referral_copy_action))
    }

    Text(
        text = stringResource(Res.string.referral_progress_heading),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.fillMaxWidth().padding(top = 28.dp),
    )
    Text(
        text = stringResource(Res.string.referral_progress, state.grantedReferrals, state.milestone),
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 4.dp),
    )

    if (state.inviterRewardClaimed) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = MaterialTheme.shapes.medium,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(Res.string.referral_reward_unlocked),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}
