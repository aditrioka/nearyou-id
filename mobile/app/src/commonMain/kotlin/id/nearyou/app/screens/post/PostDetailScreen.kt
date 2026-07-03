package id.nearyou.app.screens.post

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import id.nearyou.app.auth.SelfUserIdProvider
import id.nearyou.app.data.block.BlockSubmitter
import id.nearyou.app.data.report.ReportSubmitter
import id.nearyou.app.post.LikeCountOutcome
import id.nearyou.app.post.LikeOutcome
import id.nearyou.app.post.PostDetailFlow
import id.nearyou.app.post.PostEditFlow
import id.nearyou.app.post.PostRefreshOutcome
import id.nearyou.app.post.ReplyPostOutcome
import id.nearyou.app.screens.routing.PostDetailRoute
import id.nearyou.app.ui.components.BlockConfirmDialog
import id.nearyou.app.ui.components.LetterAvatar
import id.nearyou.app.ui.components.LoadMoreFooter
import id.nearyou.app.ui.components.LoadMoreOnScrollEnd
import id.nearyou.app.ui.components.ReportDialog
import id.nearyou.app.ui.components.postDateLabel
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.chat_share_to_chat_action
import id.nearyou.resources.generated.resources.cta_close
import id.nearyou.resources.generated.resources.cta_edit_post
import id.nearyou.resources.generated.resources.cta_reply
import id.nearyou.resources.generated.resources.cta_retry
import id.nearyou.resources.generated.resources.ic_more_vert
import id.nearyou.resources.generated.resources.ic_post_like
import id.nearyou.resources.generated.resources.ic_post_like_filled
import id.nearyou.resources.generated.resources.ic_post_reply
import id.nearyou.resources.generated.resources.post_card_handle
import id.nearyou.resources.generated.resources.post_detail_like_count
import id.nearyou.resources.generated.resources.post_detail_likes_cap_upsell
import id.nearyou.resources.generated.resources.post_detail_post_gone
import id.nearyou.resources.generated.resources.post_detail_posted_from
import id.nearyou.resources.generated.resources.post_detail_posted_from_no_city
import id.nearyou.resources.generated.resources.post_detail_replies_empty
import id.nearyou.resources.generated.resources.post_detail_reply_cap_upsell
import id.nearyou.resources.generated.resources.post_detail_reply_counter
import id.nearyou.resources.generated.resources.post_detail_reply_placeholder
import id.nearyou.resources.generated.resources.post_detail_reset_hours
import id.nearyou.resources.generated.resources.post_edit_edited_label
import id.nearyou.resources.generated.resources.post_image_alt
import id.nearyou.resources.generated.resources.profile_action_failed
import id.nearyou.resources.generated.resources.profile_actions_menu_description
import id.nearyou.resources.generated.resources.profile_block_action
import id.nearyou.resources.generated.resources.profile_block_rate_limited
import id.nearyou.resources.generated.resources.profile_block_success_toast
import id.nearyou.resources.generated.resources.profile_report_action
import id.nearyou.resources.generated.resources.profile_report_rate_limited
import id.nearyou.resources.generated.resources.profile_report_success_toast
import id.nearyou.resources.generated.resources.report_title_post
import id.nearyou.resources.generated.resources.report_title_reply
import id.nearyou.resources.generated.resources.signin_error_network
import id.nearyou.resources.generated.resources.timeline_loading
import id.nearyou.resources.theme.locationPin
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import kotlin.time.Clock
import kotlin.time.Instant

/** Test tag on the clickable like control (the toggle target). */
const val POST_DETAIL_LIKE_TOGGLE_TAG: String = "postDetailLikeToggle"

/** Test tag on the like-state indicator when LIKED (asserts the optimistic flip). */
const val POST_DETAIL_LIKE_LIKED_TAG: String = "postDetailLikeLiked"

/** Test tag on the like-state indicator when NOT liked (asserts the revert). */
const val POST_DETAIL_LIKE_NOT_LIKED_TAG: String = "postDetailLikeNotLiked"

/** Test tag on the displayed reply-count node (asserts the local +1 on a 201). */
const val POST_DETAIL_REPLY_COUNT_TAG: String = "postDetailReplyCount"

/** Test tag on the multiline reply field — lets the screen test target it for text input. */
const val POST_DETAIL_REPLY_FIELD_TAG: String = "postDetailReplyField"

/** Test tag on the back/close affordance. */
const val POST_DETAIL_BACK_TAG: String = "postDetailBack"

/** Test tag on the replies-error retry control. */
const val POST_DETAIL_REPLIES_RETRY_TAG: String = "postDetailRepliesRetry"

/** Test tag on the Edit affordance (shown for the viewer's own post within the 30-min window). */
const val POST_DETAIL_EDIT_TAG: String = "postDetailEdit"

/** Test tag on the "Diedit" label (opens the "Riwayat edit" history overlay). */
const val POST_DETAIL_EDITED_LABEL_TAG: String = "postDetailEditedLabel"

/** Test tag on the attached-image node (`image-attached-posts`) — present only when the route carries a
 *  non-null `imageUrl`, so a test can assert the image renders when supplied and is absent when null. */
const val POST_DETAIL_IMAGE_TAG: String = "postDetailImage"

/** Test tag on the post-header report affordance (shown only for a non-authored post). */
const val POST_DETAIL_REPORT_POST_TAG: String = "postDetailReportPost"

/** Test tag on the "Bagikan ke chat" overflow item (chat-embedded-posts). */
const val POST_DETAIL_SHARE_TO_CHAT_TAG: String = "postDetailShareToChat"

/** Test tag on a reply row's report affordance (present on every reply, ungated by authorship). */
const val POST_DETAIL_REPORT_REPLY_TAG: String = "postDetailReportReply"

/** Test tag on the shared report dialog when opened from the post-detail surface. */
const val POST_DETAIL_REPORT_DIALOG_TAG: String = "postDetailReportDialog"

/** Test tag on the post-header block menu item (shown only for a non-authored post whose freshness
 *  read resolved an `authorUserId` — mobile-block-from-content). */
const val POST_DETAIL_BLOCK_POST_TAG: String = "postDetailBlockPost"

/** Test tag on a reply row's block menu item (another user's reply with a wire identity only). */
const val POST_DETAIL_BLOCK_REPLY_TAG: String = "postDetailBlockReply"

/** Test tag on the shared block confirmation dialog when opened from the post-detail surface. */
const val POST_DETAIL_BLOCK_DIALOG_TAG: String = "postDetailBlockDialog"

/**
 * The post-detail surface ([PostDetailRoute]) — "everything you do on a single post" — opened by tapping
 * a feed card and overlaid on the tab bar via the ROOT back stack (design D1). Renders, all under
 * `NearYouTheme` with every string via `stringResource` (zero literals):
 *  - the post **header** ([route].content + a "Diposting dari {city}, {date}" line; the empty-`cityName`
 *    convention `""` renders without the city fragment via `post_detail_posted_from_no_city`);
 *  - a **like control** — initial state from [PostDetailRoute.likedByViewer]; the tap flips optimistically
 *    (+/- the count when available) and reverts on a non-`Liked`/`Unliked` outcome; a 429 surfaces the
 *    like-cap upsell; the numeric count comes from `likeCount()` and degrades gracefully when unavailable;
 *  - a **replies list** (loading / empty / error states; reply cards render content + the `created_at`
 *    treatment ONLY — no `author_id`);
 *  - a **reply composer** (placeholder + a live `N/280` Unicode-code-point counter + a "Balas" CTA disabled
 *    while empty / over-limit / in-flight; a 201 appends the returned reply locally + bumps the count with
 *    NO list re-fetch; a 429 surfaces the reply-cap upsell; an `InvalidContent`/network/post-gone failure
 *    shows the generic retryable banner).
 *
 * The screen holds NO back-stack reference (design Decision 6): its back affordance invokes the hoisted
 * [onBack] and its Edit affordance the hoisted [onEditPost]. The header content is freshened by a
 * `single-post-read` GET on each resume (`mobile-post-editing`) — which also drives the "Diedit" label
 * (`editedAt`) and the Edit affordance gate (`isAuthor`); a freshness failure degrades silently to the
 * [route] payload. The replies + like state use the like + reply sub-resources. PII discipline: no
 * `author_id` and no coordinate is rendered (the refresh exposes only a boolean `isAuthor`, never the
 * author UUID; the [route] carries no coordinate, and [ReplyUi] drops `authorId`); this screen never
 * `println`s/logs.
 */
@Composable
fun PostDetailScreen(
    route: PostDetailRoute,
    onBack: () -> Unit,
    onEditPost: (postId: String, content: String) -> Unit = { _, _ -> },
    // chat-embedded-posts: the "Bagikan ke chat" action opens the conversation picker for this post.
    onShareToChat: (postId: String) -> Unit = {},
) {
    val flow = koinInject<PostDetailFlow>()
    val editFlow = koinInject<PostEditFlow>()
    val reportSubmitter = koinInject<ReportSubmitter>()
    val blockSubmitter = koinInject<BlockSubmitter>()
    val selfUserIdProvider = koinInject<SelfUserIdProvider>()
    val scope = rememberCoroutineScope()

    // Like state: initial liked from the nav arg; count + last outcome (for the cap upsell) are live.
    var liked by remember { mutableStateOf(route.likedByViewer) }
    var likeCount by remember { mutableStateOf<Long?>(null) }
    var likeInFlight by remember { mutableStateOf(false) }
    var likeOutcome by remember { mutableStateOf<LikeOutcome?>(null) }

    // Replies list + cursor paging + the header reply count are held in PostDetailViewModel (design D5,
    // mirrors the timeline-VM migration #167) so the loaded replies + load-more state survive
    // recomposition + config change. The like + composer state stay composition-local (noted follow-up).
    val viewModel =
        viewModel { PostDetailViewModel(flow, route.postId, route.replyCount, reportSubmitter, blockSubmitter) }
    val repliesOutcome by viewModel.repliesOutcome.collectAsStateWithLifecycle()
    val repliesInFlight by viewModel.repliesInFlight.collectAsStateWithLifecycle()
    val replyCount by viewModel.replyCount.collectAsStateWithLifecycle()
    val isLoadingMore by viewModel.isLoadingMore.collectAsStateWithLifecycle()
    val loadMoreError by viewModel.loadMoreError.collectAsStateWithLifecycle()
    // mobile-content-report: the report dialog target + the one-shot result message.
    val reportTarget by viewModel.reportTarget.collectAsStateWithLifecycle()
    val reportMessage by viewModel.reportMessage.collectAsStateWithLifecycle()
    // mobile-block-from-content: the block dialog target, the one-shot result, and the post-block pop.
    val blockTarget by viewModel.blockTarget.collectAsStateWithLifecycle()
    val blockMessage by viewModel.blockMessage.collectAsStateWithLifecycle()
    val blockPopBack by viewModel.blockPopBack.collectAsStateWithLifecycle()
    // The session user id for the reply self-block gate (SelfUserIdProvider decodes the token's sub;
    // null while resolving / on a malformed token — the block item is simply absent, never a crash).
    var selfUserId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { selfUserId = selfUserIdProvider.selfUserId() }

    // Composer state.
    var replyContent by remember { mutableStateOf("") }
    var replyInFlight by remember { mutableStateOf(false) }
    var replyOutcome by remember { mutableStateOf<ReplyPostOutcome?>(null) }

    // mobile-post-editing: a single-post-read refresh on each resume (first open AND the return from the
    // edit screen — the revealed entry goes RESUMED) freshens the displayed content + reads `editedAt` (the
    // "Diedit" label) + `isAuthor` (the edit-affordance gate). A failure degrades silently (Unavailable):
    // the header keeps its nav-payload content and the label/affordance stay hidden.
    var displayedContent by remember { mutableStateOf(route.content) }
    var editedAtIso by remember { mutableStateOf<String?>(null) }
    var isAuthor by remember { mutableStateOf(false) }
    // mobile-block-from-content: the post author's UUID from the SAME freshness read (design D1) —
    // never rendered/logged; solely the post-header block target. Null (read failed / older backend)
    // simply hides the block affordance, the same graceful dependence as the Edit affordance.
    var authorUserId by remember { mutableStateOf<String?>(null) }
    var historyOpen by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        scope.launch {
            when (val refresh = editFlow.refreshPost(route.postId)) {
                is PostRefreshOutcome.Loaded -> {
                    displayedContent = refresh.content
                    editedAtIso = refresh.editedAt
                    isAuthor = refresh.isAuthor
                    authorUserId = refresh.authorUserId
                }
                PostRefreshOutcome.Unavailable -> Unit
            }
        }
    }
    // The Edit affordance shows for the viewer's OWN post (server-authoritative `isAuthor`) AND within the
    // 30-minute window (a client hint from `createdAt`; a clock-skew boundary is caught by the backend 409).
    val createdAtMillis =
        remember(route.createdAtIso) {
            runCatching { Instant.parse(route.createdAtIso).toEpochMilliseconds() }.getOrNull()
        }
    val editEligible =
        isAuthor &&
            createdAtMillis != null &&
            isWithinEditWindow(createdAtMillis, Clock.System.now().toEpochMilliseconds())

    // The replies first-page load + retry + load-more live in the ViewModel (loads once on construction).
    // The like count is fetched once on entry (no single-post GET); degrades to null when unavailable.
    LaunchedEffect(route.postId) {
        likeCount =
            when (val countOutcome = flow.likeCount(route.postId)) {
                is LikeCountOutcome.Available -> countOutcome.count
                LikeCountOutcome.Unavailable -> null
            }
    }

    val reloadReplies: () -> Unit = viewModel::reloadReplies

    // Reply-shortcut autofocus (mobile-inline-post-actions § "Reply composer autofocuses on
    // reply-shortcut entry"): when the route carries focusReplyComposer = true, focus the composer
    // (IME up) exactly ONCE on the entry's first composition. The consumed marker is SAVEABLE state
    // (rememberSaveable under the entry's saveable-state holder), so recomposition, a manual focus
    // clear, AND a config-change/process-death restore never re-fire it. Whole-card opens
    // (focusReplyComposer = false) keep today's no-autofocus behavior.
    val replyFocusRequester = remember { FocusRequester() }
    var replyAutofocusConsumed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (route.focusReplyComposer && !replyAutofocusConsumed) {
            replyAutofocusConsumed = true
            // Wait one frame before requesting: a first-composition requestFocus can race the focus
            // system's attachment pass and silently no-op (the field stays unfocused).
            withFrameNanos {}
            replyFocusRequester.requestFocus()
        }
    }

    val onToggleLike: () -> Unit = {
        if (!likeInFlight) {
            val wasLiked = liked
            // Capture the exact pre-tap count so a failure restores it DIRECTLY (not via an inverse
            // delta) — the count fetch may resolve between the optimistic flip and the failure, so a
            // delta-revert could drift off-by-one from a base the optimistic step never adjusted.
            val priorCount = likeCount
            // Optimistic flip (+/- the count when shown); clear any prior cap/error banner.
            liked = !wasLiked
            likeCount = likeCount?.let { if (wasLiked) it - 1 else it + 1 }
            likeOutcome = null
            // Claimed synchronously — see onSubmitReply (05-#10 double-tap window).
            likeInFlight = true
            scope.launch {
                try {
                    when (val outcome = flow.toggleLike(route.postId, currentlyLiked = wasLiked)) {
                        LikeOutcome.Liked, LikeOutcome.Unliked -> Unit // happy path: keep the optimistic flip
                        is LikeOutcome.RateLimited, LikeOutcome.PostGone, LikeOutcome.NetworkError -> {
                            // Revert to the exact pre-tap state; surface the outcome's banner.
                            liked = wasLiked
                            likeCount = priorCount
                            likeOutcome = outcome
                        }
                    }
                } finally {
                    likeInFlight = false
                }
            }
        }
    }

    val onSubmitReply: () -> Unit = {
        if (replyComposerUiState(replyContent, replyInFlight).submitEnabled) {
            // Claim the in-flight slot SYNCHRONOUSLY: setting it inside the launch
            // left a same-frame double-tap window where both taps passed the
            // submitEnabled guard and double-POSTed the reply (05-#10).
            replyInFlight = true
            scope.launch {
                try {
                    when (val outcome = flow.postReply(route.postId, replyContent)) {
                        is ReplyPostOutcome.Success -> {
                            // Prepend the returned reply + bump the header count via the VM; NO list
                            // re-fetch (if replies never loaded, the VM re-fetches page 1 instead). The
                            // list renders newest-first, so the fresh reply lands at the top of page 1 and
                            // any appended later pages are undisturbed.
                            viewModel.onReplyPosted(outcome.reply)
                            replyContent = ""
                            replyOutcome = null
                        }
                        is ReplyPostOutcome.RateLimited,
                        ReplyPostOutcome.PostGone,
                        ReplyPostOutcome.InvalidContent,
                        ReplyPostOutcome.NetworkError,
                        -> replyOutcome = outcome
                    }
                } finally {
                    replyInFlight = false
                }
            }
        }
    }

    // mobile-content-report: the one-shot report-result message → a snackbar (resolved + cleared once).
    val snackbarHostState = remember { SnackbarHostState() }
    val reportMessageText = reportMessage?.let { stringResource(it.resource()) }
    LaunchedEffect(reportMessage) {
        if (reportMessageText != null) {
            snackbarHostState.showSnackbar(reportMessageText)
            viewModel.onReportMessageShown()
        }
    }
    // mobile-block-from-content: the one-shot block-result message → the same snackbar host.
    val blockMessageText = blockMessage?.let { stringResource(it.resource()) }
    LaunchedEffect(blockMessage) {
        if (blockMessageText != null) {
            snackbarHostState.showSnackbar(blockMessageText)
            viewModel.onBlockMessageShown()
        }
    }
    // mobile-block-from-content: a confirmed POST block pops this screen off the root back stack (the
    // just-blocked post 404s on any re-read — the profile navigate-back rationale). One-shot, cleared
    // after the pop so a restored composition never re-pops.
    LaunchedEffect(blockPopBack) {
        if (blockPopBack) {
            viewModel.onBlockPoppedBack()
            onBack()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                BackBar(
                    onBack = onBack,
                    editEligible = editEligible,
                    onEdit = { onEditPost(route.postId, displayedContent) },
                    // mobile-content-report: the post-header report affordance shows only for a
                    // non-authored post (server-authoritative `isAuthor`, the same gate as Edit).
                    reportEligible = !isAuthor,
                    onReportPost = viewModel::onReportPostClicked,
                    // mobile-block-from-content: the post-header block item needs a NON-authored post
                    // (the kebab itself now shows on own posts too, for share) + the freshness-read
                    // authorUserId (the block target) + a payload username (the canonical copy);
                    // any missing → the item is simply absent (graceful degradation, design D1).
                    onBlockPost =
                        authorUserId
                            ?.takeIf { !isAuthor && route.authorUsername.isNotEmpty() }
                            ?.let { target -> { viewModel.onBlockPostClicked(target, route.authorUsername) } },
                    blockUsername = route.authorUsername,
                    // chat-embedded-posts: "Bagikan ke chat" is available for ANY visible post (own or
                    // other's) — sharing relays the snapshot the sender can see.
                    onShareToChat = { onShareToChat(route.postId) },
                )
            },
            bottomBar = {
                ReplyComposer(
                    content = replyContent,
                    onContentChange = { replyContent = it },
                    inFlight = replyInFlight,
                    banner = replyBanner(replyOutcome),
                    onSubmit = onSubmitReply,
                    focusRequester = replyFocusRequester,
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { padding ->
            // Everything scrolls in one LazyColumn (header + like row + the replies states/list + the
            // load-more footer); the reply composer is the fixed bottom bar. The replies retry calls the VM,
            // so the retry control works even though its item is replaced by the Loading item on re-load.
            val listState = rememberLazyListState()
            // Replies load-more scroll-end trigger: the LoadMoreController guards make an eager fire (short
            // list / not-yet-loaded / end-reached / during the initial load) a no-op, and the end-relative
            // threshold keys off the list tail (the footer), AFTER the post header + like-row items.
            LoadMoreOnScrollEnd(listState = listState, onLoadMore = viewModel::onLoadMore)
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    PostHeader(
                        content = displayedContent,
                        cityName = route.cityName,
                        createdAtIso = route.createdAtIso,
                        editedAtIso = editedAtIso,
                        onEditedLabelClick = { historyOpen = true },
                        authorUsername = route.authorUsername,
                        authorDisplayName = route.authorDisplayName,
                        imageUrl = route.imageUrl,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    LikeRow(
                        liked = liked,
                        likeCount = likeCount,
                        replyCount = replyCount,
                        likeInFlight = likeInFlight,
                        banner = likeBanner(likeOutcome),
                        onToggleLike = onToggleLike,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                when (val repliesState = repliesUiState(repliesOutcome, repliesInFlight)) {
                    RepliesUiState.Loading -> item { RepliesLoading() }
                    RepliesUiState.Empty -> item { RepliesEmpty() }
                    RepliesUiState.Error -> item { RepliesError(onRetry = reloadReplies) }
                    is RepliesUiState.Content ->
                        items(
                            items = repliesState.replies,
                            key = { it.id },
                            contentType = { "reply" },
                        ) { reply ->
                            // mobile-content-report: each reply row carries a report affordance targeting
                            // the reply id ONLY (ungated by authorship). mobile-block-from-content adds the
                            // block item — gated on the SelfUserIdProvider self-comparison (never your own
                            // reply) AND a non-blank wire username (older-backend graceful absence); the
                            // backend's 400 cannot_block_self stays the belt-and-suspenders.
                            ReplyCard(
                                reply = reply,
                                onReport = { viewModel.onReportReplyClicked(reply.id) },
                                onBlock =
                                    reply.authorUsername
                                        ?.takeIf { it.isNotBlank() && reply.authorId != selfUserId }
                                        ?.let { username ->
                                            { viewModel.onBlockReplyClicked(reply.id, reply.authorId, username) }
                                        },
                            )
                        }
                }
                // Replies load-more footer: spinner while a page loads, non-destructive retry on error,
                // nothing at end / during the initial load (mobile-design-system § load-more pattern).
                item(key = "loadMoreFooter", contentType = "footer") {
                    LoadMoreFooter(
                        isLoadingMore = isLoadingMore,
                        loadMoreError = loadMoreError,
                        onRetry = viewModel::onRetryLoadMore,
                    )
                }
            }
            // mobile-content-report: the shared report dialog over the detail — the title differs by target
            // (post vs reply); submission + outcome→message mapping live in the VM. NO author identity is
            // passed (the reply target carries only the reply id). Hosted inside the Scaffold content (the
            // AlertDialog is a Dialog window, so z-order is unaffected) — NOT as a sibling of the Scaffold,
            // which triggered an infinite measure loop in the LazyColumn host.
            reportTarget?.let { target ->
                ReportDialog(
                    title = target.titleResource(),
                    testTag = POST_DETAIL_REPORT_DIALOG_TAG,
                    onSubmit = viewModel::onReportSubmitted,
                    onDismiss = viewModel::onReportDialogDismissed,
                )
            }
            // mobile-block-from-content: the shared block confirmation dialog (canonical docs/03 copy)
            // over the detail — one dialog serves the post-header AND reply-row targets; the target
            // UUID stays in the VM (only the public @username is rendered). Hosted inside the Scaffold
            // content for the same measure-loop reason as the report dialog above.
            blockTarget?.let { target ->
                BlockConfirmDialog(
                    username = target.username,
                    onConfirm = viewModel::onBlockConfirmed,
                    onDismiss = viewModel::onBlockDialogDismissed,
                    testTag = POST_DETAIL_BLOCK_DIALOG_TAG,
                )
            }
        }
        // mobile-post-editing: the screen-local "Riwayat edit" overlay (NOT a NavKey) over the detail.
        if (historyOpen) {
            EditHistorySheet(postId = route.postId, onDismiss = { historyOpen = false })
        }
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

/** Maps the one-shot [PostDetailBlockMessage] to its `:shared:resources` string — the SAME copy the
 *  profile block surfaces (mobile-block-from-content D4: success toast / rate-limit / action-failed). */
private fun PostDetailBlockMessage.resource(): StringResource =
    when (this) {
        PostDetailBlockMessage.SUCCESS -> Res.string.profile_block_success_toast
        PostDetailBlockMessage.RATE_LIMITED -> Res.string.profile_block_rate_limited
        PostDetailBlockMessage.FAILED -> Res.string.profile_action_failed
    }

/** The back/close affordance (the detail overlays the feed via the root stack, so "Tutup" = close it).
 *  Invokes the hoisted [onBack] — the screen holds no back-stack reference. The trailing slot carries the
 *  mobile-post-editing Edit affordance (own post within the window) OR the mobile-content-report report
 *  affordance (a non-authored post). The two are mutually exclusive on authorship (a non-author is never
 *  edit-eligible), so they never co-occur. */
@Composable
private fun BackBar(
    onBack: () -> Unit,
    editEligible: Boolean,
    onEdit: () -> Unit,
    reportEligible: Boolean,
    onReportPost: () -> Unit,
    onBlockPost: (() -> Unit)?,
    blockUsername: String,
    onShareToChat: () -> Unit,
) {
    // This screen owns its Scaffold (root-stack overlay), so its custom bars must
    // apply their own system-bar insets — a bare Row in the topBar slot rendered
    // under the status bar (2026-06-10 audit, finding 06-#4).
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onBack, modifier = Modifier.testTag(POST_DETAIL_BACK_TAG)) {
            Text(text = stringResource(Res.string.cta_close))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            // mobile-post-editing: the Edit affordance — own post within the 30-min window. Reactively
            // gated: tapping opens the editor; a Free user hits the 403 → "Aktifkan Premium" upsell there.
            if (editEligible) {
                TextButton(onClick = onEdit, modifier = Modifier.testTag(POST_DETAIL_EDIT_TAG)) {
                    Text(text = stringResource(Res.string.cta_edit_post))
                }
            }
            // The post-header overflow kebab: "Bagikan ke chat" (any visible post — chat-embedded-posts)
            // plus "Blokir @{username}" (non-authored post with a resolved block target —
            // mobile-block-from-content) plus "Laporkan" for a non-authored post (mobile-content-report).
            // Always present so the share entry point is reachable for own posts too.
            PostActionsMenu(
                onShareToChat = onShareToChat,
                reportEligible = reportEligible,
                onReportPost = onReportPost,
                onBlockPost = onBlockPost,
                blockUsername = blockUsername,
            )
        }
    }
}

/** The post-header overflow kebab — "Bagikan ke chat" (always — chat-embedded-posts) + an optional
 *  "Blokir @{username}" item (mobile-block-from-content — present iff [onBlockPost] is non-null, i.e. a
 *  non-authored post whose freshness read resolved an `authorUserId` and whose payload carries a
 *  username; the block-above-report order mirrors the profile `ProfileActionsMenu`) + "Laporkan"
 *  (non-authored post — mobile-content-report). [blockUsername] is the author's public handle for the
 *  block item label (display identity only — never the UUID). */
@Composable
private fun PostActionsMenu(
    onShareToChat: () -> Unit,
    reportEligible: Boolean,
    onReportPost: () -> Unit,
    onBlockPost: (() -> Unit)?,
    blockUsername: String,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.testTag(POST_DETAIL_REPORT_POST_TAG),
        ) {
            Icon(
                painter = painterResource(Res.drawable.ic_more_vert),
                contentDescription = stringResource(Res.string.profile_actions_menu_description),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (onBlockPost != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.profile_block_action, blockUsername)) },
                    onClick = {
                        expanded = false
                        onBlockPost()
                    },
                    modifier = Modifier.testTag(POST_DETAIL_BLOCK_POST_TAG),
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.chat_share_to_chat_action)) },
                onClick = {
                    expanded = false
                    onShareToChat()
                },
                modifier = Modifier.testTag(POST_DETAIL_SHARE_TO_CHAT_TAG),
            )
            if (reportEligible) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.profile_report_action)) },
                    onClick = {
                        expanded = false
                        onReportPost()
                    },
                )
            }
        }
    }
}

/** The post header: the author display-identity row (mobile-timeline-card-redesign — the shared
 *  `LetterAvatar` + display name + @handle, the SAME treatments as the `ui/components` card so they
 *  cannot drift; omitted gracefully when the payload identity is empty, e.g. a back stack serialized
 *  before the fields existed) + content + a "Diposting dari {city}, {date}" line (empty `cityName` →
 *  the no-city variant, no dangling comma). Built solely from the route payload — no `author_id`
 *  (UUID), no coordinate. The identity is NOT a tap target (no profile screen yet — issue #196). */
@Composable
private fun PostHeader(
    content: String,
    cityName: String,
    createdAtIso: String,
    editedAtIso: String?,
    onEditedLabelClick: () -> Unit,
    authorUsername: String,
    authorDisplayName: String,
    imageUrl: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        if (authorUsername.isNotEmpty() || authorDisplayName.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LetterAvatar(
                    displayName = authorDisplayName,
                    username = authorUsername,
                )
                Column {
                    if (authorDisplayName.isNotEmpty()) {
                        Text(
                            text = authorDisplayName,
                            // Bold per the shared card's identity treatment (cannot drift).
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (authorUsername.isNotEmpty()) {
                        Text(
                            text = stringResource(Res.string.post_card_handle, authorUsername),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        Text(
            text = content,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        // image-attached-posts: the attached image BELOW the content (same Coil AsyncImage pattern as the
        // shared card), rendered ONLY when the route payload carries a non-null imageUrl — supplied by the
        // tapped feed card, so detail needs no by-id re-fetch (design D4/D6). Loads on on-screen render
        // (no preload) and fails gracefully to nothing (no error chrome); the alt text is resource-backed.
        if (imageUrl != null) {
            AsyncImage(
                model = imageUrl,
                contentDescription = stringResource(Res.string.post_image_alt),
                contentScale = ContentScale.FillWidth,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .testTag(POST_DETAIL_IMAGE_TAG),
            )
        }
        val postedFrom =
            if (cityName.isEmpty()) {
                stringResource(Res.string.post_detail_posted_from_no_city, postDateLabel(createdAtIso))
            } else {
                stringResource(Res.string.post_detail_posted_from, cityName, postDateLabel(createdAtIso))
            }
        Text(
            text = postedFrom,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        // mobile-post-editing: the "Diedit [tanggal]" label — shown iff the single-post-read projection
        // reports an edit (editedAt present); tapping opens the "Riwayat edit" history overlay.
        if (editedAtIso != null) {
            Text(
                text = stringResource(Res.string.post_edit_edited_label, postDateLabel(editedAtIso)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier =
                    Modifier
                        .padding(top = 4.dp)
                        .clickable(onClick = onEditedLabelClick)
                        .testTag(POST_DETAIL_EDITED_LABEL_TAG),
            )
        }
    }
}

/**
 * The like control row: a clickable brand-tinted dot (filled coral when liked, muted otherwise — the
 * interactive analogue of the read-only card dot) + the "{n} suka" count when available + a read-only
 * reply-count indicator. The like-state dot carries a state-specific testTag so the optimistic flip /
 * revert is assertable. A 429/error banner (if any) renders below.
 */
@Composable
private fun LikeRow(
    liked: Boolean,
    likeCount: Long?,
    replyCount: Int,
    likeInFlight: Boolean,
    banner: PostDetailBanner?,
    onToggleLike: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Material heart icon (the feed-card idiom) — the brand-tinted placeholder
            // dot contradicted the mobile-design-system icon migration the feed cards
            // already shipped (2026-06-10 audit, finding 06-#2).
            Box(
                modifier =
                    Modifier
                        .testTag(POST_DETAIL_LIKE_TOGGLE_TAG)
                        .clickable(enabled = !likeInFlight, onClick = onToggleLike)
                        .padding(4.dp),
            ) {
                Icon(
                    painter =
                        painterResource(
                            if (liked) Res.drawable.ic_post_like_filled else Res.drawable.ic_post_like,
                        ),
                    contentDescription = stringResource(Res.string.post_detail_like_count, likeCount ?: 0),
                    tint =
                        if (liked) {
                            MaterialTheme.colorScheme.locationPin
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    modifier =
                        Modifier
                            .size(20.dp)
                            .testTag(if (liked) POST_DETAIL_LIKE_LIKED_TAG else POST_DETAIL_LIKE_NOT_LIKED_TAG),
                )
            }
            if (likeCount != null) {
                Text(
                    text = stringResource(Res.string.post_detail_like_count, likeCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Read-only reply-count indicator (muted reply icon + the live count) — mirrors the feed
            // card's counts row; the count is the nav arg + the local +1 on a successful reply.
            Icon(
                painter = painterResource(Res.drawable.ic_post_reply),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = replyCount.toString(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(POST_DETAIL_REPLY_COUNT_TAG),
            )
        }
        if (banner != null) {
            BannerText(banner = banner, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

/** A single reply card — the author **display identity** row (mobile-block-from-content D7, mockup
 *  frame 7: the shared [LetterAvatar] + display name, the same treatments as the post header; omitted
 *  gracefully when the wire identity is absent — an older-backend body) + `content` + the `created_at`
 *  date treatment + a trailing overflow. The `author_id` UUID is NEVER rendered. A viewer's-own
 *  auto-hidden reply renders identically (the flag is parsed but not surfaced in v1). [onReport] opens
 *  the shared report dialog for THIS reply (reply id only); [onBlock] (nullable — absent on the viewer's
 *  own reply or without a wire identity) opens the shared block dialog targeting the reply author. */
@Composable
private fun ReplyCard(
    reply: ReplyUi,
    onReport: () -> Unit,
    onBlock: (() -> Unit)?,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                val displayName = reply.authorDisplayName.orEmpty()
                val username = reply.authorUsername.orEmpty()
                if (displayName.isNotEmpty() || username.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 6.dp),
                    ) {
                        LetterAvatar(displayName = displayName, username = username)
                        Text(
                            // The mockup-frame-7 reply identity shows the display name; fall back to the
                            // @handle when a display name is absent (the header's graceful treatment).
                            text = displayName.ifEmpty { stringResource(Res.string.post_card_handle, username) },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text(
                    text = reply.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = postDateLabel(reply.createdAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            ReplyActionsMenu(
                onReport = onReport,
                onBlock = onBlock,
                blockUsername = reply.authorUsername.orEmpty(),
            )
        }
    }
}

/** A reply row's overflow — a kebab opening an optional "Blokir @{username}" item
 *  (mobile-block-from-content; present iff [onBlock] is non-null) above the "Laporkan" item (present on
 *  EVERY reply, ungated by authorship — mobile-content-report). Mirrors the post-header kebab order. */
@Composable
private fun ReplyActionsMenu(
    onReport: () -> Unit,
    onBlock: (() -> Unit)?,
    blockUsername: String,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.testTag(POST_DETAIL_REPORT_REPLY_TAG),
        ) {
            Icon(
                painter = painterResource(Res.drawable.ic_more_vert),
                contentDescription = stringResource(Res.string.profile_actions_menu_description),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (onBlock != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.profile_block_action, blockUsername)) },
                    onClick = {
                        expanded = false
                        onBlock()
                    },
                    modifier = Modifier.testTag(POST_DETAIL_BLOCK_REPLY_TAG),
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.profile_report_action)) },
                onClick = {
                    expanded = false
                    onReport()
                },
            )
        }
    }
}

@Composable
private fun RepliesLoading() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text(
            text = stringResource(Res.string.timeline_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun RepliesEmpty() {
    Text(
        text = stringResource(Res.string.post_detail_replies_empty),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
    )
}

@Composable
private fun RepliesError(onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(Res.string.signin_error_network),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        OutlinedButton(onClick = onRetry, modifier = Modifier.padding(top = 12.dp).testTag(POST_DETAIL_REPLIES_RETRY_TAG)) {
            Text(text = stringResource(Res.string.cta_retry))
        }
    }
}

/**
 * The reply composer (bottom bar): a multiline field + a live `N/280` code-point counter + the "Balas"
 * CTA (disabled while empty / over-limit / in-flight, so the client never submits invalid content). A
 * 429/error [banner] (if any) renders above the CTA — for the generic [PostDetailBanner.Network] case the
 * Balas CTA itself is the retry (the field text is preserved on failure).
 */
@Composable
private fun ReplyComposer(
    content: String,
    onContentChange: (String) -> Unit,
    inFlight: Boolean,
    banner: PostDetailBanner?,
    onSubmit: () -> Unit,
    focusRequester: FocusRequester,
) {
    val composer = replyComposerUiState(content, inFlight)
    // Bottom-bar insets: keep the composer above the nav bar AND the keyboard —
    // a bare Column in the bottomBar slot was occluded by both (06-#4).
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = content,
            onValueChange = onContentChange,
            placeholder = { Text(text = stringResource(Res.string.post_detail_reply_placeholder)) },
            enabled = !inFlight,
            minLines = 2,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .testTag(POST_DETAIL_REPLY_FIELD_TAG),
        )
        Text(
            text = stringResource(Res.string.post_detail_reply_counter, composer.charCount),
            style = MaterialTheme.typography.labelMedium,
            color = if (composer.overLimit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.fillMaxWidth(),
        )
        if (banner != null) {
            BannerText(banner = banner)
        }
        Button(onClick = onSubmit, enabled = composer.submitEnabled, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(Res.string.cta_reply))
        }
    }
}

/** Renders a [PostDetailBanner] message — the cap upsells (with the coarse reset-hours countdown filling
 *  the `%1$s`), the terminal post-gone copy, or the generic retryable network copy. Always via
 *  `stringResource` (no literals). */
@Composable
private fun BannerText(
    banner: PostDetailBanner,
    modifier: Modifier = Modifier,
) {
    val message =
        when (banner) {
            is PostDetailBanner.LikeCap ->
                stringResource(
                    Res.string.post_detail_likes_cap_upsell,
                    stringResource(Res.string.post_detail_reset_hours, banner.resetHours),
                )
            is PostDetailBanner.ReplyCap ->
                stringResource(
                    Res.string.post_detail_reply_cap_upsell,
                    stringResource(Res.string.post_detail_reset_hours, banner.resetHours),
                )
            PostDetailBanner.PostGone -> stringResource(Res.string.post_detail_post_gone)
            PostDetailBanner.Network -> stringResource(Res.string.signin_error_network)
        }
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = modifier.fillMaxWidth(),
    )
}

/** ISO-8601 `created_at` → its date portion ("2026-06-06"). Pure + deterministic (no wall clock) —
 *  the same treatment the feed cards use; richer relative formatting is the deferred follow-up. */
