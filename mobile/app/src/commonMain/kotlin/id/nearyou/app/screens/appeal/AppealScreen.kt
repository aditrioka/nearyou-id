package id.nearyou.app.screens.appeal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import id.nearyou.app.appeal.AppealFlow
import id.nearyou.app.appeal.AppealSession
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.appeal_approved_body
import id.nearyou.resources.generated.resources.appeal_approved_title
import id.nearyou.resources.generated.resources.appeal_back_cd
import id.nearyou.resources.generated.resources.appeal_char_counter
import id.nearyou.resources.generated.resources.appeal_error_invalid
import id.nearyou.resources.generated.resources.appeal_error_not_eligible
import id.nearyou.resources.generated.resources.appeal_error_rate_limited
import id.nearyou.resources.generated.resources.appeal_field_hint
import id.nearyou.resources.generated.resources.appeal_intro
import id.nearyou.resources.generated.resources.appeal_pending_body
import id.nearyou.resources.generated.resources.appeal_pending_title
import id.nearyou.resources.generated.resources.appeal_rejected_body
import id.nearyou.resources.generated.resources.appeal_rejected_reason
import id.nearyou.resources.generated.resources.appeal_rejected_title
import id.nearyou.resources.generated.resources.appeal_submit
import id.nearyou.resources.generated.resources.appeal_submitting
import id.nearyou.resources.generated.resources.appeal_title
import id.nearyou.resources.generated.resources.cta_retry
import id.nearyou.resources.generated.resources.ic_nav_back
import id.nearyou.resources.generated.resources.signin_error_network
import id.nearyou.resources.generated.resources.timeline_loading
import id.nearyou.resources.generated.resources.timeline_session_redirect
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

const val APPEAL_FIELD_TAG: String = "appealField"
const val APPEAL_SUBMIT_TAG: String = "appealSubmit"
const val APPEAL_BACK_TAG: String = "appealBack"
const val APPEAL_STATUS_TAG: String = "appealStatus"
const val APPEAL_RETRY_TAG: String = "appealRetry"
const val APPEAL_RESIGNIN_TAG: String = "appealReSignIn"

/**
 * The ban/suspension **Ajukan Banding** surface (`mobile-appeal`) — reached from the banned/suspended
 * session state. Injects [AppealFlow] + [AppealSession] and observes a route-scoped [AppealViewModel] that
 * reads the limited appeal token from the holder, loads the caller's own appeal status on entry, and gates
 * the submit. Navigation-free: back invokes [onBack]; a no-token / `401` / approved state invokes
 * [onReSignIn] (the caller routes to sign-in to re-mint the token / pick up the lifted ban). All copy via
 * `stringResource`; renders under `NearYouTheme`.
 */
@Composable
fun AppealScreen(
    onBack: () -> Unit,
    onReSignIn: () -> Unit = {},
) {
    val flow = koinInject<AppealFlow>()
    val session = koinInject<AppealSession>()
    val viewModel = viewModel { AppealViewModel(flow, session) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(topBar = { AppealTopBar(onBack = onBack) }) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val status = uiState.status) {
                AppealStatus.Loading -> CenteredLoading()
                is AppealStatus.Form ->
                    FormSurface(
                        appealText = uiState.appealText,
                        charCount = uiState.charCount,
                        charLimit = uiState.charLimit,
                        submitEnabled = uiState.submitEnabled,
                        error = status.error,
                        onTextChange = viewModel::onTextChange,
                        onSubmit = viewModel::onSubmit,
                    )
                AppealStatus.Submitting -> CenteredLoading(stringResource(Res.string.appeal_submitting))
                is AppealStatus.Pending ->
                    CenteredOutcome(
                        title = stringResource(Res.string.appeal_pending_title),
                        body = stringResource(Res.string.appeal_pending_body),
                    )
                is AppealStatus.Decided ->
                    DecidedSurface(status = status, onReSignIn = onReSignIn)
                AppealStatus.SessionRedirect ->
                    CenteredMessageWithAction(
                        message = stringResource(Res.string.timeline_session_redirect),
                        actionLabel = null,
                        onAction = onReSignIn,
                    )
                AppealStatus.LoadError ->
                    CenteredMessageWithAction(
                        message = stringResource(Res.string.signin_error_network),
                        actionLabel = stringResource(Res.string.cta_retry),
                        tag = APPEAL_RETRY_TAG,
                        onAction = viewModel::onRetryLoad,
                    )
            }
        }
    }
}

@Composable
private fun AppealTopBar(onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(onClick = onBack, modifier = Modifier.testTag(APPEAL_BACK_TAG)) {
            Icon(
                painter = painterResource(Res.drawable.ic_nav_back),
                contentDescription = stringResource(Res.string.appeal_back_cd),
            )
        }
        Text(text = stringResource(Res.string.appeal_title), style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun FormSurface(
    appealText: String,
    charCount: Int,
    charLimit: Int,
    submitEnabled: Boolean,
    error: AppealFormError?,
    onTextChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(Res.string.appeal_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = appealText,
            onValueChange = onTextChange,
            modifier = Modifier.fillMaxWidth().testTag(APPEAL_FIELD_TAG),
            placeholder = { Text(text = stringResource(Res.string.appeal_field_hint)) },
            isError = error == AppealFormError.InvalidText,
        )
        Text(
            text = stringResource(Res.string.appeal_char_counter, charCount, charLimit),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (error != null) {
            Text(
                text = errorText(error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag(APPEAL_STATUS_TAG),
            )
        }
        Button(
            onClick = onSubmit,
            enabled = submitEnabled,
            modifier = Modifier.fillMaxWidth().testTag(APPEAL_SUBMIT_TAG),
        ) {
            Text(text = stringResource(Res.string.appeal_submit))
        }
    }
}

@Composable
private fun errorText(error: AppealFormError): String =
    when (error) {
        AppealFormError.NotEligible -> stringResource(Res.string.appeal_error_not_eligible)
        is AppealFormError.RateLimited -> stringResource(Res.string.appeal_error_rate_limited, error.minutes)
        AppealFormError.InvalidText -> stringResource(Res.string.appeal_error_invalid)
        AppealFormError.Transport -> stringResource(Res.string.signin_error_network)
    }

@Composable
private fun DecidedSurface(
    status: AppealStatus.Decided,
    onReSignIn: () -> Unit,
) {
    if (status.approved) {
        CenteredMessageWithAction(
            title = stringResource(Res.string.appeal_approved_title),
            message = stringResource(Res.string.appeal_approved_body),
            actionLabel = stringResource(Res.string.appeal_approved_title),
            tag = APPEAL_RESIGNIN_TAG,
            onAction = onReSignIn,
        )
    } else {
        val body =
            buildString {
                append(stringResource(Res.string.appeal_rejected_body))
                status.decisionReason?.let {
                    append("\n\n")
                    append(stringResource(Res.string.appeal_rejected_reason, it))
                }
            }
        CenteredOutcome(title = stringResource(Res.string.appeal_rejected_title), body = body)
    }
}

@Composable
private fun CenteredLoading(message: String = stringResource(Res.string.timeline_loading)) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

@Composable
private fun CenteredOutcome(
    title: String,
    body: String,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag(APPEAL_STATUS_TAG),
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun CenteredMessageWithAction(
    message: String,
    actionLabel: String?,
    onAction: () -> Unit,
    title: String? = null,
    tag: String? = null,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = if (title != null) 8.dp else 0.dp),
            )
            if (actionLabel != null) {
                Button(
                    onClick = onAction,
                    modifier = Modifier.padding(top = 16.dp).let { if (tag != null) it.testTag(tag) else it },
                ) {
                    Text(text = actionLabel)
                }
            }
        }
    }
}
