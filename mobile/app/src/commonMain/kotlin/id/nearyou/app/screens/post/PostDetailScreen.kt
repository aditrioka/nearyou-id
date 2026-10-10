package id.nearyou.app.screens.post

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import id.nearyou.app.auth.SelfUserIdProvider
import id.nearyou.app.data.block.BlockSubmitter
import id.nearyou.app.data.report.ReportSubmitter
import id.nearyou.app.post.LikeOutcome
import id.nearyou.app.post.PostDetailFlow
import id.nearyou.app.post.PostEditFlow
import id.nearyou.app.post.ReplyPostOutcome
import id.nearyou.app.screens.routing.PaywallEntry
import id.nearyou.app.screens.routing.PostDetailRoute
import id.nearyou.app.ui.components.BlockConfirmDialog
import id.nearyou.app.ui.components.DailyCapUpsellDialog
import id.nearyou.app.ui.components.LoadMoreFooter
import id.nearyou.app.ui.components.LoadMoreOnScrollEnd
import id.nearyou.app.ui.components.ReportDialog
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.post_detail_likes_cap_upsell
import id.nearyou.resources.generated.resources.post_detail_reply_cap_upsell
import id.nearyou.resources.generated.resources.post_detail_reply_delete_failed
import id.nearyou.resources.generated.resources.profile_action_failed
import id.nearyou.resources.generated.resources.profile_block_rate_limited
import id.nearyou.resources.generated.resources.profile_block_success_toast
import id.nearyou.resources.generated.resources.profile_report_rate_limited
import id.nearyou.resources.generated.resources.profile_report_success_toast
import id.nearyou.resources.generated.resources.report_title_post
import id.nearyou.resources.generated.resources.report_title_reply
import id.nearyou.resources.generated.resources.signin_error_network
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The post-detail surface ([PostDetailRoute]) — "everything you do on a single post" — opened by tapping a
 * feed card / a post notification and overlaid on the tab bar via the ROOT back stack. Laid out per mockup
 * frame 7 · "Detail postingan + balasan" (post-detail-vm-reply-delete-restyle) in ONE `Scaffold`: the top app
 * bar ([PostDetailTopBar]), one `LazyColumn` (the [PostHeader], the [PostActionRow], the [RepliesSubhead], the
 * replies states / [ReplyRow]s, the load-more footer), and the [ReplyComposer] bottom bar. All copy via
 * `stringResource` under `NearYouTheme`.
 *
 * This composable is the wiring only: every network operation, in-flight guard and one-shot lives in the
 * entry-scoped [PostDetailViewModel] (docs/11 § 2.2) and is read from its single `uiState`; nothing launches
 * from a composition scope. The screen keeps only UI element state: the saveable reply draft (cleared on the
 * VM's `replyPosted` one-shot), the consume-once autofocus marker, and the edit-history overlay flag. It holds
 * NO back-stack reference — back, Edit, share, profile and paywall are hoisted lambdas. PII discipline: no
 * `author_id` and no coordinate is rendered; this screen never logs.
 */
@Composable
fun PostDetailScreen(
    route: PostDetailRoute,
    onBack: () -> Unit,
    onEditPost: (postId: String, content: String) -> Unit = { _, _ -> },
    // chat-embedded-posts: the "Bagikan ke chat" action opens the conversation picker for this post.
    onShareToChat: (postId: String) -> Unit = {},
    // post-detail-tap-to-profile: identity taps (header + reply rows) push ProfileRoute(userId) via the host.
    onOpenProfile: (userId: String) -> Unit = {},
    // cap-upsell-parity: the like / reply cap dialogs' "Aktifkan Premium" — the screen names the entry
    // (LIKE_CAP / REPLY_CAP); the host pushes PaywallRoute(entry).
    onActivatePremium: (PaywallEntry) -> Unit = {},
) {
    val flow = koinInject<PostDetailFlow>()
    val editFlow = koinInject<PostEditFlow>()
    val selfUserIdProvider = koinInject<SelfUserIdProvider>()
    val reportSubmitter = koinInject<ReportSubmitter>()
    val blockSubmitter = koinInject<BlockSubmitter>()
    val viewModel =
        viewModel { PostDetailViewModel(route, flow, editFlow, selfUserIdProvider, reportSubmitter, blockSubmitter) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // mobile-post-editing: a freshness re-read on each resume (first open AND the return from the editor —
    // the docs/11 § 2.3 "silent ON_RESUME re-read in the screen's VM"). A failure degrades silently.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshPost() }

    // The reply draft is UI element state, saveable: the reply-cap CTA pushes the paywall (this entry leaves
    // composition) and the draft must survive that round-trip, a config change, and process death.
    var replyContent by rememberSaveable { mutableStateOf("") }
    var historyOpen by remember { mutableStateOf(false) }

    // The Edit affordance: the viewer's OWN post (server-authoritative `isAuthor`) within the 30-minute window
    // (a client hint from `createdAt`; a clock-skew boundary is caught by the backend 409).
    val createdAtMillis =
        remember(route.createdAtIso) { runCatching { Instant.parse(route.createdAtIso).toEpochMilliseconds() }.getOrNull() }
    val editEligible =
        uiState.isAuthor &&
            createdAtMillis != null &&
            isWithinEditWindow(createdAtMillis, Clock.System.now().toEpochMilliseconds())

    // Reply-shortcut autofocus (mobile-inline-post-actions): focus the composer exactly ONCE when the route
    // carries focusReplyComposer = true. The consumed marker is SAVEABLE, so recomposition, a manual focus
    // clear, AND a config-change / process-death restore never re-fire it.
    val replyFocusRequester = remember { FocusRequester() }
    var replyAutofocusConsumed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (route.focusReplyComposer && !replyAutofocusConsumed) {
            replyAutofocusConsumed = true
            // Wait one frame: a first-composition requestFocus can race the focus system's attachment pass.
            withFrameNanos {}
            replyFocusRequester.requestFocus()
        }
    }

    // One-shots (docs/11 § 2.2 — state cleared by callback, never a bus).
    LaunchedEffect(uiState.replyPosted) {
        if (uiState.replyPosted) {
            replyContent = ""
            viewModel.onReplyPostedShown()
        }
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val reportMessageText = uiState.reportMessage?.let { stringResource(it.resource()) }
    LaunchedEffect(reportMessageText) {
        if (reportMessageText != null) {
            snackbarHostState.showSnackbar(reportMessageText)
            viewModel.onReportMessageShown()
        }
    }
    val blockMessageText = uiState.blockMessage?.let { stringResource(it.resource()) }
    LaunchedEffect(blockMessageText) {
        if (blockMessageText != null) {
            snackbarHostState.showSnackbar(blockMessageText)
            viewModel.onBlockMessageShown()
        }
    }
    val deleteFailedText = stringResource(Res.string.post_detail_reply_delete_failed)
    LaunchedEffect(uiState.deleteFailed) {
        if (uiState.deleteFailed) {
            snackbarHostState.showSnackbar(deleteFailedText)
            viewModel.onDeleteMessageShown()
        }
    }
    // A confirmed POST block pops this screen (the just-blocked post 404s on any re-read). One-shot, cleared
    // after the pop so a restored composition never re-pops.
    LaunchedEffect(uiState.blockPopBack) {
        if (uiState.blockPopBack) {
            viewModel.onBlockPoppedBack()
            onBack()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                PostDetailTopBar(
                    onBack = onBack,
                    editEligible = editEligible,
                    onEdit = { onEditPost(route.postId, uiState.content) },
                    // mobile-content-report: the post report item shows only for a non-authored post.
                    reportEligible = !uiState.isAuthor,
                    onReportPost = viewModel::onReportPostClicked,
                    // mobile-block-from-content: a NON-authored post + the freshness-read author UUID + a payload
                    // username; any missing → the item is simply absent (graceful degradation).
                    onBlockPost =
                        if (uiState.authorUserId != null && !uiState.isAuthor && route.authorUsername.isNotEmpty()) {
                            viewModel::onBlockPostClicked
                        } else {
                            null
                        },
                    blockUsername = route.authorUsername,
                    onShareToChat = { onShareToChat(route.postId) },
                )
            },
            bottomBar = {
                ReplyComposer(
                    content = replyContent,
                    onContentChange = { replyContent = it },
                    inFlight = uiState.replyInFlight,
                    banner = replyBanner(uiState.replyOutcome),
                    onSubmit = { viewModel.onSubmitReply(replyContent) },
                    focusRequester = replyFocusRequester,
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { padding ->
            PostDetailContent(
                route = route,
                uiState = uiState,
                viewModel = viewModel,
                onOpenHistory = { historyOpen = true },
                onShareToChat = { onShareToChat(route.postId) },
                onOpenProfile = onOpenProfile,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
            // The dialogs are hosted inside the Scaffold content (each is its own Dialog window, so z-order is
            // unaffected) — NOT as Scaffold siblings, which triggered an infinite measure loop in the LazyColumn.
            PostDetailDialogs(uiState = uiState, viewModel = viewModel, onActivatePremium = onActivatePremium)
        }
        // mobile-post-editing: the screen-local "Riwayat edit" overlay (NOT a NavKey) over the detail.
        if (historyOpen) {
            EditHistorySheet(postId = route.postId, onDismiss = { historyOpen = false })
        }
    }
}

/** The one scrolling column: header, action row, "N balasan", the replies states / rows, the load-more footer.
 *  Every item carries a stable `key` + `contentType` (docs/11 § 2.4). */
@Composable
private fun PostDetailContent(
    route: PostDetailRoute,
    uiState: PostDetailUiState,
    viewModel: PostDetailViewModel,
    onOpenHistory: () -> Unit,
    onShareToChat: () -> Unit,
    onOpenProfile: (userId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    // Replies load-more: the LoadMoreController guards make an eager fire a no-op; the end-relative threshold
    // keys off the list tail (the footer), AFTER the header / action-row / subhead items.
    LoadMoreOnScrollEnd(listState = listState, onLoadMore = viewModel::onLoadMore)
    LazyColumn(state = listState, modifier = modifier) {
        item(key = "header", contentType = "header") {
            PostHeader(
                content = uiState.content,
                cityName = route.cityName,
                createdAtIso = route.createdAtIso,
                editedAtIso = uiState.editedAtIso,
                onEditedLabelClick = onOpenHistory,
                authorUsername = route.authorUsername,
                authorDisplayName = route.authorDisplayName,
                imageUrl = route.imageUrl,
                // post-detail-tap-to-profile: tappable iff the freshness read resolved the author UUID.
                onOpenProfile = uiState.authorUserId?.let { id -> { onOpenProfile(id) } },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item(key = "actions", contentType = "actions") {
            PostActionRow(
                liked = uiState.liked,
                likeCount = uiState.likeCount,
                replyCount = uiState.replyCount,
                likeInFlight = uiState.likeInFlight,
                banner = likeBanner(uiState.likeOutcome),
                onToggleLike = viewModel::onToggleLike,
                onShare = onShareToChat,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item(key = "subhead", contentType = "subhead") { RepliesSubhead(replyCount = uiState.replyCount) }
        when (val replies = uiState.replies) {
            RepliesUiState.Loading -> item(key = "repliesLoading", contentType = "state") { RepliesLoading() }
            RepliesUiState.Empty -> item(key = "repliesEmpty", contentType = "state") { RepliesEmpty() }
            RepliesUiState.Error ->
                item(key = "repliesError", contentType = "state") { RepliesError(onRetry = viewModel::reloadReplies) }
            is RepliesUiState.Content ->
                items(items = replies.replies, key = { it.id }, contentType = { "reply" }) { reply ->
                    ReplyRow(
                        reply = reply,
                        onReport = { viewModel.onReportReplyClicked(reply.id) },
                        // mobile-block-from-content: another user's reply (fails CLOSED until the session id
                        // resolves) with a non-blank wire username; the backend 400 cannot_block_self stays
                        // the belt-and-suspenders.
                        onBlock =
                            reply.authorUsername
                                ?.takeIf { it.isNotBlank() && uiState.selfResolved && !reply.isOwn }
                                ?.let { username -> { viewModel.onBlockReplyClicked(reply.id, reply.authorId, username) } },
                        // #497: the viewer's own reply only (isOwn is fail-closed on an unresolved session).
                        onDelete = if (reply.isOwn) ({ viewModel.onDeleteReplyClicked(reply.id) }) else null,
                        onOpenProfile = { onOpenProfile(reply.authorId) },
                    )
                }
        }
        // Spinner while a page loads, non-destructive retry on error, nothing at end / during the initial load.
        item(key = "loadMoreFooter", contentType = "footer") {
            LoadMoreFooter(
                isLoadingMore = uiState.isLoadingMore,
                loadMoreError = uiState.loadMoreError,
                onRetry = viewModel::onRetryLoadMore,
            )
        }
    }
}

/** The surface's dialogs: report (post vs reply title), block, own-reply delete, and the like / reply caps
 *  (the shared frame-18 dialog with a live countdown — every dismissal clears the outcome; the reply draft is
 *  untouched, only a 201 clears it). */
@Composable
private fun PostDetailDialogs(
    uiState: PostDetailUiState,
    viewModel: PostDetailViewModel,
    onActivatePremium: (PaywallEntry) -> Unit,
) {
    uiState.reportTarget?.let { target ->
        ReportDialog(
            title = target.titleResource(),
            testTag = POST_DETAIL_REPORT_DIALOG_TAG,
            onSubmit = viewModel::onReportSubmitted,
            onDismiss = viewModel::onReportDialogDismissed,
        )
    }
    uiState.blockTarget?.let { target ->
        BlockConfirmDialog(
            username = target.username,
            onConfirm = viewModel::onBlockConfirmed,
            onDismiss = viewModel::onBlockDialogDismissed,
            testTag = POST_DETAIL_BLOCK_DIALOG_TAG,
        )
    }
    if (uiState.deleteTarget != null) {
        DeleteReplyDialog(onConfirm = viewModel::onDeleteReplyConfirmed, onDismiss = viewModel::onDeleteReplyDialogDismissed)
    }
    (uiState.likeOutcome as? LikeOutcome.RateLimited)?.let { rateLimited ->
        DailyCapUpsellDialog(
            retryAfterSeconds = rateLimited.retryAfterSeconds,
            body = { countdown -> stringResource(Res.string.post_detail_likes_cap_upsell, countdown) },
            onDismiss = viewModel::onLikeCapDismissed,
            onActivatePremium = {
                viewModel.onLikeCapDismissed()
                onActivatePremium(PaywallEntry.LIKE_CAP)
            },
        )
    }
    (uiState.replyOutcome as? ReplyPostOutcome.RateLimited)?.let { rateLimited ->
        DailyCapUpsellDialog(
            retryAfterSeconds = rateLimited.retryAfterSeconds,
            body = { countdown -> stringResource(Res.string.post_detail_reply_cap_upsell, countdown) },
            onDismiss = viewModel::onReplyCapDismissed,
            onActivatePremium = {
                viewModel.onReplyCapDismissed()
                onActivatePremium(PaywallEntry.REPLY_CAP)
            },
        )
    }
}

/** The report-dialog title per target: a post vs a reply (mobile-content-report). */
private fun ReportTarget.titleResource(): StringResource =
    when (this) {
        ReportTarget.Post -> Res.string.report_title_post
        is ReportTarget.Reply -> Res.string.report_title_reply
    }

/** Maps the one-shot [PostDetailReportMessage] to its `:shared:resources` string. Submitted AND Duplicate
 *  share [profile_report_success_toast] (anti-enumeration); rate-limit + failure reuse existing copy. */
private fun PostDetailReportMessage.resource(): StringResource =
    when (this) {
        PostDetailReportMessage.SUCCESS -> Res.string.profile_report_success_toast
        PostDetailReportMessage.RATE_LIMITED -> Res.string.profile_report_rate_limited
        PostDetailReportMessage.FAILED -> Res.string.signin_error_network
    }

/** Maps the one-shot [PostDetailBlockMessage] to its `:shared:resources` string — the SAME copy the profile
 *  block surfaces (mobile-block-from-content D4: success toast / rate-limit / action-failed). */
private fun PostDetailBlockMessage.resource(): StringResource =
    when (this) {
        PostDetailBlockMessage.SUCCESS -> Res.string.profile_block_success_toast
        PostDetailBlockMessage.RATE_LIMITED -> Res.string.profile_block_rate_limited
        PostDetailBlockMessage.FAILED -> Res.string.profile_action_failed
    }
