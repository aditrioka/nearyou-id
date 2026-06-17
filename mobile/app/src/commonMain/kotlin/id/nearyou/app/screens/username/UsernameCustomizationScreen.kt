package id.nearyou.app.screens.username

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import id.nearyou.app.auth.SelfUserIdProvider
import id.nearyou.app.profile.ProfileFlow
import id.nearyou.app.username.UsernameFlow
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.cta_activate_premium
import id.nearyou.resources.generated.resources.cta_retry
import id.nearyou.resources.generated.resources.ic_nav_back
import id.nearyou.resources.generated.resources.signin_error_network
import id.nearyou.resources.generated.resources.timeline_loading
import id.nearyou.resources.generated.resources.timeline_session_redirect
import id.nearyou.resources.generated.resources.username_available
import id.nearyou.resources.generated.resources.username_back_cd
import id.nearyou.resources.generated.resources.username_confirm_body
import id.nearyou.resources.generated.resources.username_confirm_dismiss
import id.nearyou.resources.generated.resources.username_confirm_primary
import id.nearyou.resources.generated.resources.username_cooldown_countdown
import id.nearyou.resources.generated.resources.username_current_label
import id.nearyou.resources.generated.resources.username_disabled
import id.nearyou.resources.generated.resources.username_error_format
import id.nearyou.resources.generated.resources.username_error_moderated
import id.nearyou.resources.generated.resources.username_field_hint
import id.nearyou.resources.generated.resources.username_premium_gate_body
import id.nearyou.resources.generated.resources.username_probe_deferred
import id.nearyou.resources.generated.resources.username_rate_limited
import id.nearyou.resources.generated.resources.username_submit
import id.nearyou.resources.generated.resources.username_success_toast
import id.nearyou.resources.generated.resources.username_title
import id.nearyou.resources.generated.resources.username_unavailable_generic
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

const val USERNAME_FIELD_TAG: String = "usernameField"
const val USERNAME_SUBMIT_TAG: String = "usernameSubmit"
const val USERNAME_BACK_TAG: String = "usernameBack"
const val USERNAME_CONFIRM_TAG: String = "usernameConfirm"
const val USERNAME_DISMISS_TAG: String = "usernameDismiss"
const val USERNAME_GATE_CTA_TAG: String = "usernameGateCta"
const val USERNAME_RETRY_TAG: String = "usernameRetry"
const val USERNAME_STATUS_TAG: String = "usernameStatus"

/**
 * The Premium **Ganti Username** surface (`mobile-premium-username`) — reached from the Settings AKUN
 * "Ganti username" row (pushed unconditionally onto the ROOT back stack, overlaying the section bar, the
 * `SearchScreen` precedent). Injects [UsernameFlow] + [ProfileFlow] + [SelfUserIdProvider] and observes a
 * route-scoped [UsernameCustomizationViewModel] that resolves the caller's Premium status on entry (the
 * screen owns the gate, design D2), runs the live local format validation + budget-aware probe, and gates
 * the change behind a confirmation modal.
 *
 * Navigation-free: back invokes the hoisted [onBack], a successful change invokes [onChanged] (the
 * `appEntryProvider` pops the route), and the Free-tier gate CTA invokes [onActivatePremium] (the call
 * site pushes `PaywallRoute`, introduced by #309 — until then a documented no-op). All copy via
 * `stringResource`; renders under `NearYouTheme` (light/dark).
 */
@Composable
fun UsernameCustomizationScreen(
    onBack: () -> Unit,
    onChanged: () -> Unit = {},
    onActivatePremium: () -> Unit = {},
) {
    val flow = koinInject<UsernameFlow>()
    val profileFlow = koinInject<ProfileFlow>()
    val selfUserIdProvider = koinInject<SelfUserIdProvider>()
    val viewModel = viewModel { UsernameCustomizationViewModel(flow, profileFlow, selfUserIdProvider) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val successMessage = stringResource(Res.string.username_success_toast)
    // Success one-shot (docs/11 §2.2): show the toast, then pop to Settings, then clear the flag.
    LaunchedEffect(uiState.changed) {
        if (uiState.changed) {
            snackbarHostState.showSnackbar(successMessage)
            onChanged()
            viewModel.onChangedShown()
        }
    }

    Scaffold(
        topBar = { UsernameTopBar(onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val status = uiState.status) {
                UsernameStatus.Resolving -> CenteredLoading()
                UsernameStatus.PremiumGate -> PremiumGate(onActivatePremium = onActivatePremium)
                UsernameStatus.Disabled -> CenteredMessage(stringResource(Res.string.username_disabled))
                UsernameStatus.SessionRedirect -> CenteredMessage(stringResource(Res.string.timeline_session_redirect))
                else ->
                    EditSurface(
                        currentUsername = uiState.currentUsername,
                        candidate = uiState.candidate,
                        status = status,
                        submitEnabled = uiState.submitEnabled,
                        onCandidateChange = viewModel::onCandidateChange,
                        onSubmit = viewModel::onSubmitClick,
                        onRetry = viewModel::onRetry,
                    )
            }
        }
    }

    if (uiState.confirmVisible) {
        ConfirmDialog(
            oldUsername = uiState.currentUsername,
            newUsername = uiState.candidate,
            onConfirm = viewModel::onConfirmChange,
            onDismiss = viewModel::onDismissConfirm,
        )
    }
}

@Composable
private fun UsernameTopBar(onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(onClick = onBack, modifier = Modifier.testTag(USERNAME_BACK_TAG)) {
            Icon(
                painter = painterResource(Res.drawable.ic_nav_back),
                contentDescription = stringResource(Res.string.username_back_cd),
            )
        }
        Text(text = stringResource(Res.string.username_title), style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun EditSurface(
    currentUsername: String,
    candidate: String,
    status: UsernameStatus,
    submitEnabled: Boolean,
    onCandidateChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onRetry: () -> Unit,
) {
    val isSubmitting = status is UsernameStatus.Submitting
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (currentUsername.isNotEmpty()) {
            Text(
                text = stringResource(Res.string.username_current_label, currentUsername),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedTextField(
            value = candidate,
            onValueChange = onCandidateChange,
            modifier = Modifier.fillMaxWidth().testTag(USERNAME_FIELD_TAG),
            singleLine = true,
            enabled = !isSubmitting,
            placeholder = { Text(text = stringResource(Res.string.username_field_hint)) },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Done),
            isError = status is UsernameStatus.Editing && status.hint == AvailabilityHint.FormatInvalid,
        )
        StatusLine(status)
        if (isSubmitting) {
            Box(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (status is UsernameStatus.Error) {
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().testTag(USERNAME_RETRY_TAG)) {
                Text(text = stringResource(Res.string.cta_retry))
            }
        } else {
            Button(
                onClick = onSubmit,
                enabled = submitEnabled,
                modifier = Modifier.fillMaxWidth().testTag(USERNAME_SUBMIT_TAG),
            ) {
                Text(text = stringResource(Res.string.username_submit))
            }
        }
    }
}

/** The inline status line under the field — the format / availability / error message per [status]. */
@Composable
private fun StatusLine(status: UsernameStatus) {
    val (text, isError) =
        when (status) {
            is UsernameStatus.Editing ->
                when (status.hint) {
                    AvailabilityHint.None -> null to false
                    AvailabilityHint.FormatInvalid -> stringResource(Res.string.username_error_format) to true
                    AvailabilityHint.Available -> stringResource(Res.string.username_available) to false
                    AvailabilityHint.Unavailable -> stringResource(Res.string.username_unavailable_generic) to true
                    AvailabilityHint.ProbeDeferred -> stringResource(Res.string.username_probe_deferred) to false
                }
            UsernameStatus.Unavailable -> stringResource(Res.string.username_unavailable_generic) to true
            UsernameStatus.Moderated -> stringResource(Res.string.username_error_moderated) to true
            is UsernameStatus.CooldownActive -> stringResource(Res.string.username_cooldown_countdown, status.days) to true
            is UsernameStatus.RateLimited -> stringResource(Res.string.username_rate_limited, status.minutes) to true
            UsernameStatus.Error -> stringResource(Res.string.signin_error_network) to true
            else -> null to false
        }
    if (text != null) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(USERNAME_STATUS_TAG),
        )
    }
}

@Composable
private fun ConfirmDialog(
    oldUsername: String,
    newUsername: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(text = stringResource(Res.string.username_confirm_body, oldUsername, newUsername)) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag(USERNAME_CONFIRM_TAG)) {
                Text(text = stringResource(Res.string.username_confirm_primary))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(USERNAME_DISMISS_TAG)) {
                Text(text = stringResource(Res.string.username_confirm_dismiss))
            }
        },
    )
}

@Composable
private fun PremiumGate(onActivatePremium: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(Res.string.username_premium_gate_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Button(
                onClick = onActivatePremium,
                modifier = Modifier.padding(top = 16.dp).testTag(USERNAME_GATE_CTA_TAG),
            ) {
                Text(text = stringResource(Res.string.cta_activate_premium))
            }
        }
    }
}

@Composable
private fun CenteredLoading() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text(
                text = stringResource(Res.string.timeline_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

@Composable
private fun CenteredMessage(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(24.dp),
        )
    }
}
