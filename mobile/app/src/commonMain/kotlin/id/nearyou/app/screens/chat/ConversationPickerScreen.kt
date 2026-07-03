package id.nearyou.app.screens.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import id.nearyou.app.chat.ChatFlow
import id.nearyou.app.chat.ConversationsFlow
import id.nearyou.app.ui.components.postDateLabel
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.chat_account_deleted
import id.nearyou.resources.generated.resources.chat_list_loading
import id.nearyou.resources.generated.resources.chat_picker_empty
import id.nearyou.resources.generated.resources.chat_picker_title
import id.nearyou.resources.generated.resources.chat_share_blocked
import id.nearyou.resources.generated.resources.chat_share_failed
import id.nearyou.resources.generated.resources.cta_close
import id.nearyou.resources.generated.resources.cta_retry
import id.nearyou.resources.generated.resources.signin_error_network
import id.nearyou.resources.generated.resources.timeline_session_redirect
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/** Test tag on the picker's scrollable surface. */
const val CONVERSATION_PICKER_TAG: String = "conversationPicker"

/** Test tag on each pickable conversation row. */
const val CONVERSATION_PICKER_ROW_TAG: String = "conversationPickerRow"

/** Test tag on the picker back affordance. */
const val CONVERSATION_PICKER_BACK_TAG: String = "conversationPickerBack"

/**
 * The share-a-post-to-chat conversation picker ([ConversationPickerRoute][id.nearyou.app.screens.routing.ConversationPickerRoute],
 * `mobile-chat-embedded-posts`) — opened by the post-detail "Bagikan ke chat" action and overlaid on the
 * shell via the ROOT back stack. Lists the user's existing conversations (the shipped [ConversationsFlow])
 * and, on a pick, sends the post embed via [ConversationPickerViewModel.shareTo]; a successful send
 * navigates to the thread ([onSelectConversation]), a `403` surfaces a blocked snackbar, and any other
 * failure a retry snackbar — with NO thread navigation. Every string via `stringResource`.
 */
@Composable
fun ConversationPickerScreen(
    postId: String,
    onBack: () -> Unit,
    onSelectConversation: (conversationId: String, partnerUsername: String, partnerDisplayName: String) -> Unit,
) {
    val conversationsFlow = koinInject<ConversationsFlow>()
    val chatFlow = koinInject<ChatFlow>()
    val viewModel = viewModel { ConversationPickerViewModel(postId, conversationsFlow, chatFlow) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val shareResult by viewModel.shareResult.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val blockedText = stringResource(Res.string.chat_share_blocked)
    val failedText = stringResource(Res.string.chat_share_failed)
    LaunchedEffect(shareResult) {
        when (val result = shareResult) {
            is ChatShareResult.Navigate -> {
                viewModel.clearShareResult()
                onSelectConversation(result.conversationId, result.partnerUsername, result.partnerDisplayName)
            }
            ChatShareResult.Blocked -> {
                viewModel.clearShareResult()
                snackbarHostState.showSnackbar(blockedText)
            }
            ChatShareResult.Failed -> {
                viewModel.clearShareResult()
                snackbarHostState.showSnackbar(failedText)
            }
            null -> Unit
        }
    }

    Scaffold(
        topBar = { PickerTopBar(onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val state = uiState) {
                ConversationListUiState.Loading ->
                    PickerCenteredState(stringResource(Res.string.chat_list_loading), spinner = true)
                ConversationListUiState.Empty ->
                    PickerCenteredState(stringResource(Res.string.chat_picker_empty), spinner = false)
                ConversationListUiState.Error -> PickerErrorState(onRetry = viewModel::reload)
                ConversationListUiState.SessionRedirect ->
                    PickerCenteredState(stringResource(Res.string.timeline_session_redirect), spinner = false)
                is ConversationListUiState.Content ->
                    PickerRows(rows = state.rows, onPick = viewModel::shareTo)
            }
        }
    }
}

@Composable
private fun PickerTopBar(onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onBack, modifier = Modifier.testTag(CONVERSATION_PICKER_BACK_TAG)) {
            Text(text = stringResource(Res.string.cta_close))
        }
        Text(
            text = stringResource(Res.string.chat_picker_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun PickerRows(
    rows: List<ConversationRow>,
    onPick: (ConversationRow) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag(CONVERSATION_PICKER_TAG),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        items(items = rows, key = { it.conversationId }, contentType = { "conversation" }) { row ->
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = { onPick(row) })
                        .testTag(CONVERSATION_PICKER_ROW_TAG)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(
                    text = row.partnerDisplayName.ifBlank { stringResource(Res.string.chat_account_deleted) },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                row.lastMessageAtIso?.let { iso ->
                    Text(
                        text = postDateLabel(iso),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun PickerCenteredState(
    message: String,
    spinner: Boolean,
) {
    LazyColumn(modifier = Modifier.fillMaxSize().testTag(CONVERSATION_PICKER_TAG)) {
        item {
            Box(modifier = Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (spinner) CircularProgressIndicator()
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PickerErrorState(onRetry: () -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize().testTag(CONVERSATION_PICKER_TAG)) {
        item {
            Box(modifier = Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(Res.string.signin_error_network),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
                        Text(text = stringResource(Res.string.cta_retry))
                    }
                }
            }
        }
    }
}
