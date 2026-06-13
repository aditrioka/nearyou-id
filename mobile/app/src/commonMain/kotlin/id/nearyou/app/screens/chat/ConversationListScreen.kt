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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
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
import id.nearyou.app.chat.ConversationsFlow
import id.nearyou.app.ui.components.postDateLabel
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.chat_account_deleted
import id.nearyou.resources.generated.resources.chat_list_empty
import id.nearyou.resources.generated.resources.chat_list_loading
import id.nearyou.resources.generated.resources.chat_list_title
import id.nearyou.resources.generated.resources.cta_close
import id.nearyou.resources.generated.resources.cta_retry
import id.nearyou.resources.generated.resources.signin_error_network
import id.nearyou.resources.generated.resources.timeline_session_redirect
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/** Test tag on the scrollable surface (every state renders one so pull-to-refresh works from each). */
const val CONVERSATION_LIST_TAG: String = "conversationList"

/** Test tag on each conversation row — lets the screen test target the open-thread tap. */
const val CONVERSATION_ROW_TAG: String = "conversationRow"

/** Test tag on the back affordance. */
const val CONVERSATION_LIST_BACK_TAG: String = "conversationListBack"

/**
 * The conversation-list surface ([ConversationListRoute][id.nearyou.app.screens.routing.ConversationListRoute],
 * mockup frame 2) — opened from the Home brand app-bar "Pesan" action and overlaid on the section shell
 * via the ROOT back stack (design D3/D5). Injects [ConversationsFlow] and observes a route-scoped
 * [ConversationListViewModel] (the `isInitialLoad`/`isRefreshing` split, mirroring the timelines). A row
 * tap pushes [ChatThreadRoute][id.nearyou.app.screens.routing.ChatThreadRoute] via the hoisted
 * [onOpenThread] (carrying the row's PII-free partner display fields + the conversation id); the screen
 * holds NO back-stack reference (navigation-free). Every string via `stringResource`.
 */
@Composable
fun ConversationListScreen(
    onBack: () -> Unit,
    onOpenThread: (ConversationRow) -> Unit,
) {
    val flow = koinInject<ConversationsFlow>()
    val viewModel = viewModel { ConversationListViewModel(flow) }
    val outcome by viewModel.outcome.collectAsStateWithLifecycle()
    val isInitialLoad by viewModel.isInitialLoad.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()

    ConversationListContent(
        uiState = remember(outcome, isInitialLoad) { conversationListUiState(outcome, isInitialLoad) },
        isRefreshing = isRefreshing,
        onRefresh = viewModel::reload,
        onRetry = viewModel::reload,
        onBack = onBack,
        onOpenThread = onOpenThread,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationListContent(
    uiState: ConversationListUiState,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onOpenThread: (ConversationRow) -> Unit,
) {
    Scaffold(
        topBar = { ChatTopBar(title = stringResource(Res.string.chat_list_title), onBack = onBack) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            when (uiState) {
                ConversationListUiState.Loading -> CenteredState(stringResource(Res.string.chat_list_loading), spinner = true)
                ConversationListUiState.Empty -> CenteredState(stringResource(Res.string.chat_list_empty), spinner = false)
                ConversationListUiState.Error -> ErrorState(onRetry = onRetry)
                ConversationListUiState.SessionRedirect ->
                    CenteredState(stringResource(Res.string.timeline_session_redirect), spinner = false)
                is ConversationListUiState.Content -> ConversationRows(uiState.rows, onOpenThread)
            }
        }
    }
}

/** The back affordance — the list overlays the shell via the root stack (so "Tutup" = close it). */
@Composable
private fun ChatTopBar(
    title: String,
    onBack: () -> Unit,
) {
    // This screen owns its Scaffold (root-stack overlay), so the bar applies its own status-bar inset.
    Row(
        modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onBack, modifier = Modifier.testTag(CONVERSATION_LIST_BACK_TAG)) {
            Text(text = stringResource(Res.string.cta_close))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun ConversationRows(
    rows: List<ConversationRow>,
    onOpenThread: (ConversationRow) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag(CONVERSATION_LIST_TAG),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        items(items = rows, key = { it.conversationId }, contentType = { "conversation" }) { row ->
            ConversationRowItem(row = row, onOpen = { onOpenThread(row) })
            HorizontalDivider()
        }
    }
}

@Composable
private fun ConversationRowItem(
    row: ConversationRow,
    onOpen: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .testTag(CONVERSATION_ROW_TAG)
                .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        // The partner display name — already COALESCE-masked to chat_account_deleted server-side; an
        // empty value (a stale pre-change route) also renders the deleted placeholder.
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
}

@Composable
private fun CenteredState(
    message: String,
    spinner: Boolean,
) {
    LazyColumn(modifier = Modifier.fillMaxSize().testTag(CONVERSATION_LIST_TAG)) {
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
private fun ErrorState(onRetry: () -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize().testTag(CONVERSATION_LIST_TAG)) {
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
