package id.nearyou.app.screens.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import id.nearyou.app.chat.ChatFlow
import id.nearyou.app.chat.SendOutcome
import id.nearyou.app.chat.ViewerIdProvider
import id.nearyou.app.infra.supabaserealtime.ChatRealtimeSubscriber
import id.nearyou.app.notifications.NotificationPermissionController
import id.nearyou.app.notifications.NotificationPermissionStatus
import id.nearyou.app.notifications.NotificationPromptOneShot
import id.nearyou.app.screens.routing.ChatThreadRoute
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.chat_account_deleted
import id.nearyou.resources.generated.resources.chat_message_redacted
import id.nearyou.resources.generated.resources.chat_send
import id.nearyou.resources.generated.resources.chat_send_blocked
import id.nearyou.resources.generated.resources.chat_thread_input_placeholder
import id.nearyou.resources.generated.resources.cta_close
import id.nearyou.resources.generated.resources.cta_retry
import id.nearyou.resources.generated.resources.notif_permission_rationale
import id.nearyou.resources.generated.resources.signin_error_network
import id.nearyou.resources.generated.resources.timeline_loading
import id.nearyou.resources.generated.resources.timeline_session_redirect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/** Test tag on the message list (every state renders one). */
const val CHAT_THREAD_LIST_TAG: String = "chatThreadList"

/** Test tag on the input field. */
const val CHAT_THREAD_INPUT_TAG: String = "chatThreadInput"

/** Test tag on the send control. */
const val CHAT_THREAD_SEND_TAG: String = "chatThreadSend"

/** Test tag on the send-blocked banner. */
const val CHAT_THREAD_BLOCKED_BANNER_TAG: String = "chatThreadBlockedBanner"

/** Test tag on a redacted message bubble (asserts the placeholder, not an empty/null bubble). */
const val CHAT_THREAD_REDACTED_TAG: String = "chatThreadRedacted"

/** Test tag on the back affordance. */
const val CHAT_THREAD_BACK_TAG: String = "chatThreadBack"

/**
 * The chat-thread surface ([ChatThreadRoute], mockup frame 5) — overlaid on the section shell via the
 * ROOT back stack. Injects [ChatFlow] + [ChatRealtimeSubscriber] + [ViewerIdProvider] and observes a
 * route-scoped [ChatThreadViewModel] that owns the REST+optimistic+realtime id-deduped merge (design
 * D4). Renders, all under `NearYouTheme` with every string via `stringResource`:
 *  - a top bar with the partner display identity from the [route] (empty → `chat_account_deleted`);
 *  - the message list (own-vs-other alignment; a redacted message → the neutral `chat_message_redacted`
 *    bubble, never an empty/`null` one; realtime/optimistic rows animate in; load-older on scroll-up);
 *  - a bottom input bar (`chat_thread_input_placeholder` + send; the 2000-code-point client guard
 *    disables send on empty/whitespace/over-limit BEFORE any request);
 *  - a send-blocked banner (`chat_send_blocked`) on a `Blocked` send; a network-retry hint otherwise;
 *  - the initial-load skeleton vs a usable thread (no double indicator).
 *
 * On the first successful send (per-process one-shot via [NotificationPromptOneShot]) it shows the
 * `notif_permission_rationale` rationale then the platform notification-permission prompt (task 9.4),
 * independent of FCM registration. The screen is navigation-free (the back affordance invokes [onBack]).
 */
@Composable
fun ChatThreadScreen(
    route: ChatThreadRoute,
    onBack: () -> Unit,
) {
    val flow = koinInject<ChatFlow>()
    val subscriber = koinInject<ChatRealtimeSubscriber>()
    val viewerIdProvider = koinInject<ViewerIdProvider>()
    val notificationController = koinInject<NotificationPermissionController>()
    val notificationOneShot = koinInject<NotificationPromptOneShot>()

    val viewModel =
        viewModel { ChatThreadViewModel(route.conversationId, flow, subscriber, viewerIdProvider) }
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val historyOutcome by viewModel.historyOutcome.collectAsStateWithLifecycle()
    val isInitialLoad by viewModel.isInitialLoad.collectAsStateWithLifecycle()
    val sendOutcome by viewModel.sendOutcome.collectAsStateWithLifecycle()
    val sendInFlight by viewModel.sendInFlight.collectAsStateWithLifecycle()

    var input by rememberSaveable { mutableStateOf("") }
    var showNotifRationale by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // First-send notification-permission prompt (task 9.4): on a successful send, if the per-process
    // one-shot is unclaimed AND the OS status is NOT_DETERMINED, show the rationale → then the prompt.
    // Also clears the input on a successful send.
    LaunchedEffect(sendOutcome) {
        if (sendOutcome is SendOutcome.Sent) {
            input = ""
            if (notificationOneShot.claim() &&
                notificationController.status() == NotificationPermissionStatus.NOT_DETERMINED
            ) {
                showNotifRationale = true
            }
        }
    }

    if (showNotifRationale) {
        NotificationRationaleDialog(
            onConfirm = {
                showNotifRationale = false
                scope.launch { notificationController.request() }
            },
            onDismiss = { showNotifRationale = false },
        )
    }

    ChatThreadContent(
        partnerDisplayName = route.partnerDisplayName.ifBlank { stringResource(Res.string.chat_account_deleted) },
        uiState = remember(historyOutcome, isInitialLoad, rows) { chatThreadUiState(historyOutcome, isInitialLoad, rows) },
        sendBar = remember(sendOutcome, sendInFlight) { sendBarState(sendOutcome, sendInFlight) },
        input = input,
        onInputChange = { input = it },
        canSend = canSubmitChat(input) && !sendInFlight,
        onSend = { viewModel.send(input) },
        onRetryHistory = viewModel::retry,
        onLoadOlder = viewModel::loadOlder,
        onBack = onBack,
    )
}

@Composable
private fun ChatThreadContent(
    partnerDisplayName: String,
    uiState: ChatThreadUiState,
    sendBar: SendBarState,
    input: String,
    onInputChange: (String) -> Unit,
    canSend: Boolean,
    onSend: () -> Unit,
    onRetryHistory: () -> Unit,
    onLoadOlder: () -> Unit,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        ChatThreadTopBar(partnerDisplayName = partnerDisplayName, onBack = onBack)
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (uiState) {
                ChatThreadUiState.Loading -> CenteredThreadState(stringResource(Res.string.timeline_loading), spinner = true)
                ChatThreadUiState.Error -> ThreadErrorState(onRetry = onRetryHistory)
                ChatThreadUiState.SessionRedirect ->
                    CenteredThreadState(stringResource(Res.string.timeline_session_redirect), spinner = false)
                is ChatThreadUiState.Content -> MessageList(uiState.rows, onLoadOlder)
            }
        }
        if (sendBar == SendBarState.Blocked) {
            SendBlockedBanner()
        }
        ChatInputBar(
            input = input,
            onInputChange = onInputChange,
            canSend = canSend,
            sending = sendBar == SendBarState.Sending,
            onSend = onSend,
        )
    }
}

@Composable
private fun ChatThreadTopBar(
    partnerDisplayName: String,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onBack, modifier = Modifier.testTag(CHAT_THREAD_BACK_TAG)) {
            Text(text = stringResource(Res.string.cta_close))
        }
        Text(
            text = partnerDisplayName,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun MessageList(
    rows: List<ChatMessageRow>,
    onLoadOlder: () -> Unit,
) {
    val listState = rememberLazyListState()
    // Load-older when the user scrolls to the very top (index 0 visible).
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { if (it == 0) onLoadOlder() }
    }
    // Keep the newest message in view as the list grows (sends / inbound).
    LaunchedEffect(rows.size) {
        if (rows.isNotEmpty()) listState.animateScrollToItem(rows.lastIndex)
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().testTag(CHAT_THREAD_LIST_TAG),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(items = rows, key = { it.id }, contentType = { "message" }) { row ->
            // Realtime/optimistic rows animate in (mobile-design-system motion).
            MessageBubble(row = row, modifier = Modifier.animateItem())
        }
    }
}

@Composable
private fun MessageBubble(
    row: ChatMessageRow,
    modifier: Modifier = Modifier,
) {
    val alignment = if (row.isOwn) Alignment.CenterEnd else Alignment.CenterStart
    val bubbleColor =
        if (row.isOwn) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val onBubbleColor =
        if (row.isOwn) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = alignment) {
        Surface(
            color = if (row.isRedacted) MaterialTheme.colorScheme.surfaceVariant else bubbleColor,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = 280.dp),
        ) {
            if (row.isRedacted) {
                Text(
                    text = stringResource(Res.string.chat_message_redacted),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).testTag(CHAT_THREAD_REDACTED_TAG),
                )
            } else {
                Text(
                    text = row.content.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = onBubbleColor,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun SendBlockedBanner() {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(Res.string.chat_send_blocked),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag(CHAT_THREAD_BLOCKED_BANNER_TAG),
        )
    }
}

@Composable
private fun ChatInputBar(
    input: String,
    onInputChange: (String) -> Unit,
    canSend: Boolean,
    sending: Boolean,
    onSend: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = onInputChange,
            placeholder = { Text(text = stringResource(Res.string.chat_thread_input_placeholder)) },
            modifier = Modifier.weight(1f).testTag(CHAT_THREAD_INPUT_TAG),
        )
        Button(
            onClick = onSend,
            enabled = canSend && !sending,
            modifier = Modifier.padding(start = 8.dp).testTag(CHAT_THREAD_SEND_TAG),
        ) {
            Text(text = stringResource(Res.string.chat_send))
        }
    }
}

@Composable
private fun NotificationRationaleDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(text = stringResource(Res.string.notif_permission_rationale)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(text = stringResource(Res.string.chat_send)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(text = stringResource(Res.string.cta_close)) } },
    )
}

@Composable
private fun CenteredThreadState(
    message: String,
    spinner: Boolean,
) {
    Box(modifier = Modifier.fillMaxSize().testTag(CHAT_THREAD_LIST_TAG), contentAlignment = Alignment.Center) {
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

@Composable
private fun ThreadErrorState(onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().testTag(CHAT_THREAD_LIST_TAG), contentAlignment = Alignment.Center) {
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
