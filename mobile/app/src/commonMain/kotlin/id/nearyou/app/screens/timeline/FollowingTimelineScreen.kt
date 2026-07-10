package id.nearyou.app.screens.timeline

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import id.nearyou.app.auth.SelfUserIdProvider
import id.nearyou.app.data.like.LikeFlow
import id.nearyou.app.data.report.ReportReasonCategory
import id.nearyou.app.data.report.ReportSubmitter
import id.nearyou.app.timeline.FollowingTimelineFlow
import id.nearyou.app.timeline.FollowingTimelineOutcome
import id.nearyou.app.ui.ads.rememberTimelineAds
import id.nearyou.app.ui.timeline.TimelineReportMessage
import id.nearyou.app.ui.timeline.TimelineReportOverlay
import id.nearyou.app.ui.components.DailyCapUpsellDialog
import id.nearyou.app.ui.components.ListCenteredMessageState
import id.nearyou.app.ui.components.ListErrorState
import id.nearyou.app.ui.components.ListLoadingState
import id.nearyou.app.ui.components.ListScrollableState
import id.nearyou.app.ui.components.PostCardModel
import id.nearyou.app.ui.components.PostFeedList
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.cta_see_global
import id.nearyou.resources.generated.resources.post_detail_likes_cap_upsell
import id.nearyou.resources.generated.resources.timeline_following_placeholder
import id.nearyou.resources.generated.resources.timeline_limit_hard
import id.nearyou.resources.generated.resources.timeline_limit_soft
import id.nearyou.resources.generated.resources.timeline_loading
import id.nearyou.resources.generated.resources.timeline_session_redirect
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/** Test tag on the scrollable surface — lets the screen test target a pull-to-refresh swipe AND lets
 *  the tab host detect "the Following feed is on screen" from ANY state. Every screen state renders its
 *  scrollable with this tag so the pull-to-refresh gesture is recognized from each (mobile-design-system
 *  § "Canonical list loading and refresh pattern"). */
const val FOLLOWING_TIMELINE_LIST_TAG: String = "followingTimelineList"

/** Test tag on each post card — lets the screen test target the open-detail tap. */
const val FOLLOWING_POST_CARD_TAG: String = "followingPostCard"

/** Test tag on the timeline report dialog (timeline-card-report-kebab). */
const val FOLLOWING_REPORT_DIALOG_TAG: String = "followingReportDialog"

/**
 * The Following feed — posts from users the viewer follows (`docs/02-Product.md` § Following Timeline),
 * chronological, no spatial filter. Hosted as the **Following pager page** of the `mobile-home-tab-host`
 * tab host; navigation-free (it holds no back-stack reference). Replaces the retired
 * `FollowingPlaceholderScreen`. Injects [FollowingTimelineFlow] and observes a `HomeRoute`-scoped
 * [FollowingTimelineViewModel] that holds the [FollowingTimelineOutcome] + the split
 * `isInitialLoad`/`isRefreshing` flags and (re)loads page 1 (pull-to-refresh + error-retry both
 * re-fetch). Because the screen composes directly under `HomeRoute` (design D1/D2), the `viewModel { }`
 * resolves to the `HomeRoute` store and the loaded feed survives swipes/tab switches + section switches
 * + the composer round-trip with no re-fetch.
 *
 * The screen is **inset-free**: it declares NO `Scaffold` and NO `TopAppBar` — the app section shell
 * owns the single inset-owning `Scaffold` (mobile-design-system § "The app shell owns a single Scaffold
 * and window insets"). No redundant in-screen header (the Following tab label already identifies it).
 *
 * Like Global, Following has **no location gate** (no spatial filter) and renders **no distance**. The
 * six fetch states follow the screen-state-mapping spec, all copy via `stringResource` (zero literals),
 * under `NearYouTheme` tokens. **The one divergence from Global:** the Empty state is a DIRECTIVE state
 * — the `timeline_following_placeholder` copy + a "Lihat Global" CTA — NOT the loading skeleton
 * Global-empty reuses (Following-empty is a real, expected state per `docs/03-UX-Design.md` § Empty
 * State "Following empty → direct user to Nearby/Global"); it mirrors the Nearby sparse-area empty.
 *
 * Three callbacks are hoisted (the tab host maps them to host-level actions; the screen stays
 * navigation-free): [onSeeGlobal] — the empty-state CTA → the tab host selects the Global tab;
 * [onOpenPost] — the card-tap → root-stack `PostDetailRoute` push (with `distanceM = null`, no spatial
 * filter); [onOpenPostReply] — the reply shortcut → `PostDetailRoute(focusReplyComposer = true)`.
 */
@Composable
fun FollowingTimelineScreen(
    onSeeGlobal: () -> Unit = {},
    onOpenPost: (FollowingTimelinePost) -> Unit = {},
    onOpenPostReply: (FollowingTimelinePost) -> Unit = {},
    onOpenProfile: (authorUserId: String) -> Unit = {},
    onActivatePremium: () -> Unit = {},
) {
    val flow = koinInject<FollowingTimelineFlow>()
    // The extracted cross-surface like seam (mobile-inline-post-actions D1) — the SAME
    // PostDetailRepository singleton post-detail / Nearby / Global use.
    val likeFlow = koinInject<LikeFlow>()
    // timeline-card-report-kebab: the shared report seam (the SAME ReportSubmitter singleton the
    // profile/post-detail/chat surfaces use) + the self-id seam for the kebab's authorship gate.
    val reportSubmitter = koinInject<ReportSubmitter>()
    val selfUserIdProvider = koinInject<SelfUserIdProvider>()
    val viewModel = viewModel { FollowingTimelineViewModel(flow, likeFlow, reportSubmitter, selfUserIdProvider) }
    // The single screen state — the followingTimelineUiState projection now lives in the VM (docs/11 §2.2),
    // collected here instead of re-derived in the composable.
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val likeCapRetryAfterSeconds by viewModel.likeCapRetryAfterSeconds.collectAsStateWithLifecycle()
    val isLoadingMore by viewModel.isLoadingMore.collectAsStateWithLifecycle()
    val loadMoreError by viewModel.loadMoreError.collectAsStateWithLifecycle()
    // timeline-card-report-kebab: the collected self id feeds reportActionFor so the kebabs appear once
    // the id resolves (compose-state read → the cards recompose); the dialog/message one-shots below.
    val selfUserId by viewModel.selfUserId.collectAsStateWithLifecycle()
    val reportingPostId by viewModel.reportingPostId.collectAsStateWithLifecycle()
    val reportMessage by viewModel.reportMessage.collectAsStateWithLifecycle()

    FollowingTimelineContent(
        // Initial load → Loading skeleton; a retained Loaded outcome during a refresh → Content (the
        // list stays mounted). The refresh spinner is conveyed by isRefreshing, NOT by this state.
        uiState = uiState,
        isRefreshing = isRefreshing,
        // Load-more (infinite scroll): the footer flags + the scroll-end/retry callbacks. The shared
        // LoadMoreController appends pages into the retained Loaded outcome (cursor-only — no anchor).
        isLoadingMore = isLoadingMore,
        loadMoreError = loadMoreError,
        onLoadMore = viewModel::onLoadMore,
        onRetryLoadMore = viewModel::onRetryLoadMore,
        // Both pull-to-refresh and the error-retry control re-fetch page 1 via the VM (shared reload path).
        onRefresh = viewModel::reload,
        onRetry = viewModel::reload,
        // The empty-state "lihat Global" CTA — a host-level tab-switch callback (the tab host selects
        // the Global tab), NOT a back-stack reference, so the screen stays navigation-free.
        onSeeGlobal = onSeeGlobal,
        onOpenPost = onOpenPost,
        // Inline like (shared controller in the VM) + the reply shortcut (hoisted to the tab host,
        // which pushes PostDetailRoute(focusReplyComposer = true)).
        onToggleLike = { post -> viewModel.toggleLike(post.id, post.likedByViewer) },
        onReplyShortcut = onOpenPostReply,
        // Identity tap → author profile: resolve the author UUID from the VM's raw DTO outcome (never on
        // the PII-free card model) and hand it to the hoisted onOpenProfile (mobile-profile).
        onOpenProfile = { post -> viewModel.authorUserIdForPost(post.id)?.let(onOpenProfile) },
        // timeline-card-report-kebab: the per-item kebab action (null = own post / unresolved self id →
        // no kebab) + the dialog/message one-shot wiring for the shared overlay.
        reportActionOf = { post -> viewModel.reportActionFor(post.id, selfUserId) },
        reportingPostId = reportingPostId,
        reportMessage = reportMessage,
        onReportSubmit = viewModel::onReportSubmitted,
        onReportDismiss = viewModel::onReportDialogDismissed,
        onReportMessageShown = viewModel::onReportMessageShown,
    )

    // The Free like-cap dialog (mobile-cap-upsell-dialog, frame 18) — same one-shot wiring as Nearby/Global;
    // the Premium CTA dismisses AND pushes PaywallRoute(LIKE_CAP) via the host (mobile-paywall-screen #235).
    likeCapRetryAfterSeconds?.let { retryAfterSeconds ->
        DailyCapUpsellDialog(
            retryAfterSeconds = retryAfterSeconds,
            body = { countdown -> stringResource(Res.string.post_detail_likes_cap_upsell, countdown) },
            onDismiss = viewModel::onLikeCapDialogDismissed,
            // mobile-paywall-screen (#235): dismiss the dialog AND push PaywallRoute(LIKE_CAP) via the host.
            onActivatePremium = {
                viewModel.onLikeCapDialogDismissed()
                onActivatePremium()
            },
        )
    }
}

/**
 * Inset-free stateless render of the Following surface: a `PullToRefreshBox` filling the space under the
 * shell's padding (NO `Scaffold`, NO `TopAppBar`). It shows one of the six states; every non-`Content`
 * state is rendered inside a scrollable tagged [FOLLOWING_TIMELINE_LIST_TAG] so the pull-to-refresh
 * gesture is recognized from each. [isRefreshing] drives only the pull-to-refresh indicator (kept
 * separate from the initial-load skeleton so the two never overlap).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FollowingTimelineContent(
    uiState: FollowingTimelineUiState,
    isRefreshing: Boolean,
    isLoadingMore: Boolean,
    loadMoreError: Boolean,
    onLoadMore: () -> Unit,
    onRetryLoadMore: () -> Unit,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onSeeGlobal: () -> Unit,
    onOpenPost: (FollowingTimelinePost) -> Unit,
    onToggleLike: (FollowingTimelinePost) -> Unit,
    onReplyShortcut: (FollowingTimelinePost) -> Unit,
    onOpenProfile: (FollowingTimelinePost) -> Unit,
    reportActionOf: (FollowingTimelinePost) -> (() -> Unit)?,
    reportingPostId: String?,
    reportMessage: TimelineReportMessage?,
    onReportSubmit: (ReportReasonCategory, String?) -> Unit,
    onReportDismiss: () -> Unit,
    onReportMessageShown: () -> Unit,
) {
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        // mobile-admob-ads-foundation — shared ad controller (config fetch + UMP gate once/session) → the
        // placement frequency (null = no ads) + native-ad slot.
        val timelineAds = rememberTimelineAds()
        when (uiState) {
            FollowingTimelineUiState.Loading ->
                ListLoadingState(
                    message = stringResource(Res.string.timeline_loading),
                    testTag = FOLLOWING_TIMELINE_LIST_TAG,
                    showSkeleton = true,
                )
            // The DIVERGENCE from Global: a directive empty (placeholder copy + "Lihat Global" CTA), NOT
            // the loading skeleton — Following-empty is a real expected state (docs/03 § Empty State).
            FollowingTimelineUiState.Empty -> FollowingEmptyState(onSeeGlobal = onSeeGlobal)
            FollowingTimelineUiState.HardLimit ->
                ListCenteredMessageState(
                    message = stringResource(Res.string.timeline_limit_hard),
                    testTag = FOLLOWING_TIMELINE_LIST_TAG,
                )
            FollowingTimelineUiState.Error -> ListErrorState(onRetry = onRetry, testTag = FOLLOWING_TIMELINE_LIST_TAG)
            // Terminal 401 → neutral redirect placeholder: the redirect copy with NO retry control and
            // NOT signin_error_network (the SessionInvalidator re-route whisks the user to SignInScreen).
            FollowingTimelineUiState.SessionRedirect ->
                ListCenteredMessageState(
                    message = stringResource(Res.string.timeline_session_redirect),
                    testTag = FOLLOWING_TIMELINE_LIST_TAG,
                )
            is FollowingTimelineUiState.Content ->
                PostFeedList(
                    posts = uiState.posts,
                    keyOf = { it.id },
                    cardModelOf = { it.toCardModel() },
                    isLoadingMore = isLoadingMore,
                    loadMoreError = loadMoreError,
                    onLoadMore = onLoadMore,
                    onRetryLoadMore = onRetryLoadMore,
                    onOpenPost = onOpenPost,
                    onToggleLike = onToggleLike,
                    onReplyShortcut = onReplyShortcut,
                    onOpenProfile = onOpenProfile,
                    listTag = FOLLOWING_TIMELINE_LIST_TAG,
                    cardTag = FOLLOWING_POST_CARD_TAG,
                    adFrequency = timelineAds.frequency,
                    adSlot = { timelineAds.Slot(it) },
                    reportActionOf = reportActionOf,
                )
            is FollowingTimelineUiState.SoftLimit ->
                PostFeedList(
                    posts = uiState.posts,
                    keyOf = { it.id },
                    cardModelOf = { it.toCardModel() },
                    isLoadingMore = isLoadingMore,
                    loadMoreError = loadMoreError,
                    onLoadMore = onLoadMore,
                    onRetryLoadMore = onRetryLoadMore,
                    onOpenPost = onOpenPost,
                    onToggleLike = onToggleLike,
                    onReplyShortcut = onReplyShortcut,
                    onOpenProfile = onOpenProfile,
                    listTag = FOLLOWING_TIMELINE_LIST_TAG,
                    cardTag = FOLLOWING_POST_CARD_TAG,
                    banner = stringResource(Res.string.timeline_limit_soft),
                    adFrequency = timelineAds.frequency,
                    adSlot = { timelineAds.Slot(it) },
                    reportActionOf = reportActionOf,
                )
        }
        // timeline-card-report-kebab: the shared report dialog + one-shot result snackbar
        // (ui/timeline/TimelineReportOverlay, design D5) — mounted once inside the PullToRefreshBox
        // (a BoxScope) so the snackbar bottom-aligns over the feed.
        TimelineReportOverlay(
            reportingPostId = reportingPostId,
            reportMessage = reportMessage,
            onSubmit = onReportSubmit,
            onDismiss = onReportDismiss,
            onMessageShown = onReportMessageShown,
            dialogTestTag = FOLLOWING_REPORT_DIALOG_TAG,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/**
 * The directive empty state: the `timeline_following_placeholder` copy ("Kamu belum mengikuti siapa
 * pun…") plus a "Lihat Global" CTA that invokes the hoisted [onSeeGlobal] (the tab host selects the
 * Global tab). Rendered inside a scrollable so pull-to-refresh works from the empty state too. Mirrors
 * the Nearby sparse-area empty (`docs/03-UX-Design.md` § Empty State "Following empty → direct user to
 * Nearby/Global").
 */
@Composable
private fun FollowingEmptyState(onSeeGlobal: () -> Unit) {
    ListScrollableState(testTag = FOLLOWING_TIMELINE_LIST_TAG) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(Res.string.timeline_following_placeholder),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onSeeGlobal, modifier = Modifier.padding(top = 16.dp)) {
                Text(text = stringResource(Res.string.cta_see_global))
            }
        }
    }
}

/** Following → shared-card model: identity + content + city; `distanceM = null` (no spatial filter). */
private fun FollowingTimelinePost.toCardModel(): PostCardModel =
    PostCardModel(
        id = id,
        authorUsername = authorUsername,
        authorDisplayName = authorDisplayName,
        content = content,
        cityName = cityName,
        distanceM = null,
        createdAt = createdAt,
        likedByViewer = likedByViewer,
        replyCount = replyCount,
        imageUrl = imageUrl,
    )
