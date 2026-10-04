package id.nearyou.app.screens.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import id.nearyou.app.chat.ChatFlow
import id.nearyou.app.chat.SendOutcome
import id.nearyou.app.chat.ViewerIdProvider
import id.nearyou.app.data.report.ReportSubmitter
import id.nearyou.app.infra.supabaserealtime.ChatRealtimeSubscriber
import id.nearyou.app.infra.supabaserealtime.EmbeddedPostSnapshot
import id.nearyou.app.notifications.NotificationPermissionController
import id.nearyou.app.notifications.NotificationPermissionStatus
import id.nearyou.app.notifications.NotificationPromptOneShot
import id.nearyou.app.screens.routing.ChatThreadRoute
import id.nearyou.app.ui.components.DailyCapUpsellDialog
import id.nearyou.app.ui.components.ListCenteredMessageState
import id.nearyou.app.ui.components.ListErrorState
import id.nearyou.app.ui.components.ListLoadingState
import id.nearyou.app.ui.components.ReportDialog
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.chat_account_deleted
import id.nearyou.resources.generated.resources.chat_cap_upsell
import id.nearyou.resources.generated.resources.chat_error_too_long
import id.nearyou.resources.generated.resources.chat_message_redacted
import id.nearyou.resources.generated.resources.chat_send
import id.nearyou.resources.generated.resources.chat_send_blocked
import id.nearyou.resources.generated.resources.chat_thread_input_placeholder
import id.nearyou.resources.generated.resources.cta_close
import id.nearyou.resources.generated.resources.cta_retry
import id.nearyou.resources.generated.resources.notif_permission_rationale
import id.nearyou.resources.generated.resources.notif_permission_rationale_confirm
import id.nearyou.resources.generated.resources.profile_report_action
import id.nearyou.resources.generated.resources.profile_report_rate_limited
import id.nearyou.resources.generated.resources.profile_report_success_toast
import id.nearyou.resources.generated.resources.report_title_chat_message
import id.nearyou.resources.generated.resources.signin_error_network
import id.nearyou.resources.generated.resources.timeline_loading
import id.nearyou.resources.generated.resources.timeline_session_redirect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
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

/** Test tag on the network-retry banner (a failed send; its retry re-sends the kept input). */
const val CHAT_THREAD_RETRY_BANNER_TAG: String = "chatThreadRetryBanner"

/** Test tag on the network-retry banner's retry control. */
const val CHAT_THREAD_RETRY_TAG: String = "chatThreadRetry"

/** Test tag on the over-limit (too-long) hint. */
const val CHAT_THREAD_TOO_LONG_TAG: String = "chatThreadTooLong"

/** Test tag on a redacted message bubble (asserts the placeholder, not an empty/null bubble). */
const val CHAT_THREAD_REDACTED_TAG: String = "chatThreadRedacted"

/** Test tag on the back affordance. */
const val CHAT_THREAD_BACK_TAG: String = "chatThreadBack"

/** Test tag on the long-press "Laporkan" menu item (mobile-chat-message-report). */
const val CHAT_MESSAGE_REPORT_ITEM_TAG: String = "chatMessageReportItem"

/** Test tag on the shared report dialog when opened from the chat thread. */
const val CHAT_REPORT_DIALOG_TAG: String = "chatReportDialog"

/**
 * The chat-thread surface ([ChatThreadRoute], mockup frame 5) — overlaid on the section shell via the
 * ROOT back stack. Injects [ChatFlow] + [ChatRealtimeSubscriber] + [ViewerIdProvider] and observes a
 * route-scoped [ChatThreadViewModel] that owns the REST+optimistic+realtime id-deduped merge (design
 * D4). Renders, all under `NearYouTheme` with every string via `stringResource`:
 *  - a top bar with the partner display identity from the [route] (empty → `chat_account_deleted`);
 *  - the message list (own-vs-other alignment; a redacted message → the neutral `chat_message_redacted`
 *    bubble, never an empty/`null` one; realtime/optimistic rows animate in; load-older on scroll-up);
 *  - a bottom input bar (`chat_thread_input_placeholder` + send; the 2000-code-point client guard
 *    disables send on empty/whitespace/over-limit BEFORE any request, and an over-limit input shows the
 *    `chat_error_too_long` hint);
 *  - a send-blocked banner (`chat_send_blocked`) on a `Blocked` send; a network-retry banner
 *    (`signin_error_network` + `cta_retry`, re-sending the kept input) on a failed send;
 *  - the shared frame-18 cap dialog (`chat_cap_upsell` + a live countdown) on a `RateLimited` send — the
 *    Free 50/day cap. The bubble drops (the message was not accepted) but the typed input is kept; the
 *    "Aktifkan Premium" CTA invokes [onActivatePremium] (the host pushes `PaywallRoute(CHAT_CAP)`);
 *  - the initial-load skeleton vs pull-to-refresh over the retained thread (no double indicator).
 *
 * A root-stack overlay, it owns ONE `Scaffold` (top bar / input bar / snackbar) so the bars sit on the
 * theme surface and inherit its content color in light AND dark (#488).
 *
 * On the first successful send (per-install one-shot via [NotificationPromptOneShot]) it shows the
 * `notif_permission_rationale` rationale then the platform notification-permission prompt (task 9.4),
 * independent of FCM registration. The screen is navigation-free (the back affordance invokes [onBack]).
 */
@Composable
fun ChatThreadScreen(
    route: ChatThreadRoute,
    onBack: () -> Unit,
    // chat-embedded-posts: tapping a (live) shared-post context card navigates to that post's detail.
    // The host builds the PostDetailRoute from the snapshot's display fields (the same nav-arg pattern
    // a feed-card tap uses); a deleted-source card is not tappable so this never fires for it.
    onOpenSharedPost: (postId: String, snapshot: EmbeddedPostSnapshot) -> Unit = { _, _ -> },
    // cap-upsell-parity: the chat cap dialog's "Aktifkan Premium" — the host pushes PaywallRoute(CHAT_CAP).
    onActivatePremium: () -> Unit = {},
) {
    val flow = koinInject<ChatFlow>()
    val subscriber = koinInject<ChatRealtimeSubscriber>()
    val viewerIdProvider = koinInject<ViewerIdProvider>()
    val reportSubmitter = koinInject<ReportSubmitter>()
    val notificationController = koinInject<NotificationPermissionController>()
    val notificationOneShot = koinInject<NotificationPromptOneShot>()

    val viewModel =
        viewModel {
            ChatThreadViewModel(route.conversationId, flow, subscriber, viewerIdProvider, reportSubmitter)
        }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val sendOutcome by viewModel.sendOutcome.collectAsStateWithLifecycle()
    val sendInFlight by viewModel.sendInFlight.collectAsStateWithLifecycle()
    val reportTargetMessageId by viewModel.reportTargetMessageId.collectAsStateWithLifecycle()
    val reportMessage by viewModel.reportMessage.collectAsStateWithLifecycle()

    var input by rememberSaveable { mutableStateOf("") }
    var showNotifRationale by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // mobile-chat-message-report: one-shot report-result snackbar (mirrors the post-detail posture). The
    // text is resolved at composition (a @Composable call); the effect shows it once then clears the
    // one-shot so it does not re-fire on recomposition / config change.
    val reportMessageText = reportMessage?.let { stringResource(it.resource()) }
    LaunchedEffect(reportMessage) {
        if (reportMessageText != null) {
            snackbarHostState.showSnackbar(reportMessageText)
            viewModel.onReportMessageShown()
        }
    }

    // First-send notification-permission prompt (task 9.4): on a successful send, if the per-install
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
        uiState = uiState,
        isRefreshing = isRefreshing,
        sendBar = remember(sendOutcome, sendInFlight, input) { sendBarState(sendOutcome, sendInFlight, input) },
        input = input,
        onInputChange = { input = it },
        canSend = canSubmitChat(input) && !sendInFlight,
        onSend = { viewModel.send(input) },
        onRefresh = viewModel::retry,
        onLoadOlder = viewModel::loadOlder,
        onReportMessage = viewModel::onReportMessageClicked,
        onOpenSharedPost = onOpenSharedPost,
        onBack = onBack,
        snackbarHostState = snackbarHostState,
    )

    // cap-upsell-parity: the Free 50/day cap (frame 18). Shown while the one-shot sendOutcome is RateLimited;
    // every dismissal path clears it via clearSendOutcome(). The input was never cleared (only Sent clears
    // it), so the user keeps the text they typed.
    (sendOutcome as? SendOutcome.RateLimited)?.let { rateLimited ->
        DailyCapUpsellDialog(
            retryAfterSeconds = rateLimited.retryAfterSeconds,
            body = { countdown -> stringResource(Res.string.chat_cap_upsell, countdown) },
            onDismiss = viewModel::clearSendOutcome,
            onActivatePremium = {
                viewModel.clearSendOutcome()
                onActivatePremium()
            },
        )
    }

    // mobile-chat-message-report: the shared report dialog, opened by the long-press "Laporkan" on a
    // received message. Submission goes through the shared ReportSubmitter (target_type = chat_message).
    if (reportTargetMessageId != null) {
        ReportDialog(
            title = Res.string.report_title_chat_message,
            onSubmit = viewModel::onReportSubmitted,
            onDismiss = viewModel::onReportDialogDismissed,
            testTag = CHAT_REPORT_DIALOG_TAG,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatThreadContent(
    partnerDisplayName: String,
    uiState: ChatThreadUiState,
    isRefreshing: Boolean,
    sendBar: SendBarState,
    input: String,
    onInputChange: (String) -> Unit,
    canSend: Boolean,
    onSend: () -> Unit,
    onRefresh: () -> Unit,
    onLoadOlder: () -> Unit,
    onReportMessage: (String) -> Unit,
    onOpenSharedPost: (postId: String, snapshot: EmbeddedPostSnapshot) -> Unit,
    onBack: () -> Unit,
    snackbarHostState: SnackbarHostState,
) {
    // The single Scaffold of this root-stack overlay (mobile-design-system): its surface gives the bars the
    // theme background + content color (a bare Column left the title black-on-black in dark mode, #488).
    Scaffold(
        topBar = { ChatThreadTopBar(partnerDisplayName = partnerDisplayName, onBack = onBack) },
        bottomBar = {
            // Bottom-bar insets: keep the send-state row + input above the nav bar AND the keyboard.
            Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding()) {
                when (sendBar) {
                    SendBarState.Blocked -> SendBlockedBanner()
                    SendBarState.NetworkRetry -> SendRetryBanner(canRetry = canSend, onRetry = onSend)
                    SendBarState.TooLong -> TooLongHint()
                    SendBarState.Idle, SendBarState.Sending -> Unit
                }
                ChatInputBar(
                    input = input,
                    onInputChange = onInputChange,
                    canSend = canSend,
                    sending = sendBar == SendBarState.Sending,
                    onSend = onSend,
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        // Pull-to-refresh (and the error retry) resync the newest page over the RETAINED thread; the
        // non-Content states render inside a scrollable so the pull works from them too.
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            when (uiState) {
                ChatThreadUiState.Loading ->
                    ListLoadingState(message = stringResource(Res.string.timeline_loading), testTag = CHAT_THREAD_LIST_TAG)
                ChatThreadUiState.Error -> ListErrorState(onRetry = onRefresh, testTag = CHAT_THREAD_LIST_TAG)
                ChatThreadUiState.SessionRedirect ->
                    ListCenteredMessageState(
                        message = stringResource(Res.string.timeline_session_redirect),
                        testTag = CHAT_THREAD_LIST_TAG,
                    )
                is ChatThreadUiState.Content -> MessageList(uiState.rows, onLoadOlder, onReportMessage, onOpenSharedPost)
            }
        }
    }
}

@Composable
private fun ChatThreadTopBar(
    partnerDisplayName: String,
    onBack: () -> Unit,
) {
    // The overlay owns its Scaffold, so the custom bar applies its own status-bar inset (06-#4).
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
    onReportMessage: (String) -> Unit,
    onOpenSharedPost: (postId: String, snapshot: EmbeddedPostSnapshot) -> Unit,
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
            MessageBubble(
                row = row,
                onReport = onReportMessage,
                onOpenSharedPost = onOpenSharedPost,
                modifier = Modifier.animateItem(),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    row: ChatMessageRow,
    onReport: (String) -> Unit,
    onOpenSharedPost: (postId: String, snapshot: EmbeddedPostSnapshot) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val alignment = if (row.isOwn) Alignment.CenterEnd else Alignment.CenterStart
    val bubbleColor =
        if (row.isOwn) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val onBubbleColor =
        if (row.isOwn) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = alignment) {
        Surface(
            color = if (row.isRedacted) MaterialTheme.colorScheme.surfaceVariant else bubbleColor,
            shape = RoundedCornerShape(16.dp),
            // Long-press opens the "Laporkan" menu — ONLY for a reportable (other-party, non-redacted)
            // message. The gate is `row.isReportable`; `senderId` never reaches this row (PII discipline).
            modifier =
                Modifier.widthIn(max = 280.dp).then(
                    if (row.isReportable) {
                        Modifier.combinedClickable(
                            onClick = {},
                            onLongClick = { menuExpanded = true },
                        )
                    } else {
                        Modifier
                    },
                ),
        ) {
            if (row.isRedacted) {
                // Redaction precedence (design D9): the placeholder replaces BOTH the text and the
                // context card. The row projection already nulled the embed fields when redacted.
                Text(
                    text = stringResource(Res.string.chat_message_redacted),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).testTag(CHAT_THREAD_REDACTED_TAG),
                )
            } else {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    if (!row.content.isNullOrEmpty()) {
                        Text(
                            text = row.content,
                            style = MaterialTheme.typography.bodyMedium,
                            color = onBubbleColor,
                        )
                    }
                    // chat-embedded-posts: the shared-post context card (embed-only or content+embed).
                    // embeddedPostId == null + a present snapshot = the source post was hard-deleted
                    // ("post telah dihapus", not tappable). Tapping a live card navigates to its detail.
                    row.embeddedPostSnapshot?.let { snapshot ->
                        EmbeddedPostCard(
                            snapshot = snapshot,
                            isDeleted = row.embeddedPostId == null,
                            // DEFERRED live source: pass the anchor as the live id (no banner) until a
                            // per-card live-edit fetch is wired (the editedSinceShared gate is the spec'd
                            // comparison; only the live signal is deferred — see the deferred requirement).
                            editedSinceShared = editedSinceShared(row.embeddedPostEditId, row.embeddedPostEditId),
                            onTap = { row.embeddedPostId?.let { id -> onOpenSharedPost(id, snapshot) } },
                            modifier = if (row.content.isNullOrEmpty()) Modifier else Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }
        if (row.isReportable) {
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.profile_report_action)) },
                    onClick = {
                        menuExpanded = false
                        onReport(row.id)
                    },
                    modifier = Modifier.testTag(CHAT_MESSAGE_REPORT_ITEM_TAG),
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

/** A failed send (transport / 5xx): the connectivity copy + a retry that re-sends the kept input. */
@Composable
private fun SendRetryBanner(
    canRetry: Boolean,
    onRetry: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth().testTag(CHAT_THREAD_RETRY_BANNER_TAG),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.signin_error_network),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
            )
            TextButton(onClick = onRetry, enabled = canRetry, modifier = Modifier.testTag(CHAT_THREAD_RETRY_TAG)) {
                Text(text = stringResource(Res.string.cta_retry))
            }
        }
    }
}

/** The over-limit input hint (> 2000 code points) — send is already disabled; this says why. */
@Composable
private fun TooLongHint() {
    Text(
        text = stringResource(Res.string.chat_error_too_long),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag(CHAT_THREAD_TOO_LONG_TAG),
    )
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
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = onInputChange,
            placeholder = { Text(text = stringResource(Res.string.chat_thread_input_placeholder)) },
            // Bounded: a long draft scrolls inside the field instead of growing the bottom bar over the thread.
            maxLines = 5,
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
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(text = stringResource(Res.string.notif_permission_rationale_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(text = stringResource(Res.string.cta_close)) } },
    )
}

/** Maps the one-shot [ChatReportMessage] to its `:shared:resources` string. Submitted AND Duplicate share
 *  [profile_report_success_toast] (anti-enumeration, design D4); rate-limit + failure reuse existing copy. */
private fun ChatReportMessage.resource(): StringResource =
    when (this) {
        ChatReportMessage.SUCCESS -> Res.string.profile_report_success_toast
        ChatReportMessage.RATE_LIMITED -> Res.string.profile_report_rate_limited
        ChatReportMessage.FAILED -> Res.string.signin_error_network
    }
