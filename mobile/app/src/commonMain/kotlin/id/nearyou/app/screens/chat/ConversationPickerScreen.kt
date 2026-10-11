package id.nearyou.app.screens.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import id.nearyou.app.chat.ChatFlow
import id.nearyou.app.chat.ConversationsFlow
import id.nearyou.app.ui.components.DailyCapUpsellDialog
import id.nearyou.app.ui.components.ListCenteredMessageState
import id.nearyou.app.ui.components.ListErrorState
import id.nearyou.app.ui.components.ListLoadingState
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.chat_cap_upsell
import id.nearyou.resources.generated.resources.chat_list_loading
import id.nearyou.resources.generated.resources.chat_picker_empty
import id.nearyou.resources.generated.resources.chat_picker_title
import id.nearyou.resources.generated.resources.chat_share_blocked
import id.nearyou.resources.generated.resources.chat_share_failed
import id.nearyou.resources.generated.resources.cta_close
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
 * navigates to the thread ([onSelectConversation]), a `403` surfaces a blocked snackbar, a `429` (the
 * Free 50/day chat cap — a share is a chat send) the shared frame-18 cap dialog whose "Aktifkan Premium"
 * invokes [onActivatePremium] (the host pushes `PaywallRoute(CHAT_CAP)`), and any other failure a retry
 * snackbar — with NO thread navigation. Every string via `stringResource`.
 */
@Composable
fun ConversationPickerScreen(
    postId: String,
    onBack: () -> Unit,
    onSelectConversation: (conversationId: String, partnerUsername: String, partnerDisplayName: String) -> Unit,
    onActivatePremium: () -> Unit = {},
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
            // Left SET while the cap dialog below shows; its dismiss / CTA clears it.
            is ChatShareResult.RateLimited, null -> Unit
        }
    }

    (shareResult as? ChatShareResult.RateLimited)?.let { rateLimited ->
        DailyCapUpsellDialog(
            retryAfterSeconds = rateLimited.retryAfterSeconds,
            body = { countdown -> stringResource(Res.string.chat_cap_upsell, countdown) },
            onDismiss = viewModel::clearShareResult,
            onActivatePremium = {
                viewModel.clearShareResult()
                onActivatePremium()
            },
        )
    }

    Scaffold(
        topBar = { PickerTopBar(onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            // The non-Content states are the shared list-state kit (docs/11 § 2.1).
            when (val state = uiState) {
                ConversationListUiState.Loading ->
                    ListLoadingState(message = stringResource(Res.string.chat_list_loading), testTag = CONVERSATION_PICKER_TAG)
                ConversationListUiState.Empty ->
                    ListCenteredMessageState(message = stringResource(Res.string.chat_picker_empty), testTag = CONVERSATION_PICKER_TAG)
                ConversationListUiState.Error -> ListErrorState(onRetry = viewModel::reload, testTag = CONVERSATION_PICKER_TAG)
                ConversationListUiState.SessionRedirect ->
                    ListCenteredMessageState(
                        message = stringResource(Res.string.timeline_session_redirect),
                        testTag = CONVERSATION_PICKER_TAG,
                    )
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
        contentPadding = PaddingValues(top = 8.dp),
    ) {
        items(items = rows, key = { it.conversationId }, contentType = { "conversation" }) { row ->
            // The conversation-list row (frame 2), so the picker shows the same partner identity.
            ConversationRowItem(row = row, rowTag = CONVERSATION_PICKER_ROW_TAG, onOpen = { onPick(row) })
        }
    }
}
