package id.nearyou.app.screens.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.Modifier
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
import id.nearyou.resources.generated.resources.chat_cap_upsell
import id.nearyou.resources.generated.resources.cta_close
import id.nearyou.resources.generated.resources.notif_permission_rationale
import id.nearyou.resources.generated.resources.notif_permission_rationale_confirm
import id.nearyou.resources.generated.resources.profile_report_rate_limited
import id.nearyou.resources.generated.resources.profile_report_success_toast
import id.nearyou.resources.generated.resources.report_title_chat_message
import id.nearyou.resources.generated.resources.signin_error_network
import id.nearyou.resources.generated.resources.timeline_loading
import id.nearyou.resources.generated.resources.timeline_session_redirect
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import kotlin.time.Clock

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

/** Test tag on the partner header's Premium badge. */
const val CHAT_THREAD_PREMIUM_BADGE_TAG: String = "chatThreadPremiumBadge"

/** Test tag on a day chip ("Hari ini" / "Kemarin" / the date). */
const val CHAT_THREAD_DAY_CHIP_TAG: String = "chatThreadDayChip"

/** Test tag on a bubble's send time. */
const val CHAT_THREAD_MESSAGE_TIME_TAG: String = "chatThreadMessageTime"

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
    // The day chips' "today". A screen test pins it so "Hari ini" / "Kemarin" cannot flip at local midnight.
    clock: Clock = Clock.System,
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
    // Saveable: the one-shot below is already claimed, so a config change must not drop an open rationale.
    var showNotifRationale by rememberSaveable { mutableStateOf(false) }
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

    // First-send notification-permission prompt (task 9.4): on a successful send, if the OS status is
    // NOT_DETERMINED AND the per-install one-shot is unclaimed, show the rationale → then the prompt.
    // Also clears the input on a successful send. The Sent outcome is then consumed (docs/11 §2.2) so a
    // recreated composition does not replay it — wiping a newer draft or re-claiming the one-shot. The
    // clear runs LAST: it changes this effect's key, so earlier would cancel the suspending checks.
    LaunchedEffect(sendOutcome) {
        if (sendOutcome is SendOutcome.Sent) {
            input = ""
            if (notificationController.status() == NotificationPermissionStatus.NOT_DETERMINED &&
                notificationOneShot.claim()
            ) {
                showNotifRationale = true
            }
            viewModel.clearSendOutcome()
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
        partnerDisplayName = route.partnerDisplayName,
        partnerUsername = route.partnerUsername,
        partnerIsPremium = route.partnerIsPremium,
        uiState = uiState,
        clock = clock,
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
    partnerUsername: String,
    partnerIsPremium: Boolean,
    uiState: ChatThreadUiState,
    clock: Clock,
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
        topBar = {
            ChatThreadTopBar(
                partnerDisplayName = partnerDisplayName,
                partnerUsername = partnerUsername,
                partnerIsPremium = partnerIsPremium,
                onBack = onBack,
            )
        },
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
                is ChatThreadUiState.Content -> ChatMessageList(uiState.rows, clock, onLoadOlder, onReportMessage, onOpenSharedPost)
            }
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
