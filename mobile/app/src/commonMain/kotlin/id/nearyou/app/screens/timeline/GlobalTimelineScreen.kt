package id.nearyou.app.screens.timeline

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import id.nearyou.app.data.like.LikeFlow
import id.nearyou.app.timeline.GlobalTimelineFlow
import id.nearyou.app.timeline.GlobalTimelineOutcome
import id.nearyou.app.ui.components.DailyCapUpsellDialog
import id.nearyou.app.ui.components.ListCenteredMessageState
import id.nearyou.app.ui.components.ListErrorState
import id.nearyou.app.ui.components.ListLoadingState
import id.nearyou.app.ui.components.PostCardModel
import id.nearyou.app.ui.components.PostFeedList
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.post_detail_likes_cap_upsell
import id.nearyou.resources.generated.resources.timeline_limit_hard
import id.nearyou.resources.generated.resources.timeline_limit_soft
import id.nearyou.resources.generated.resources.timeline_loading
import id.nearyou.resources.generated.resources.timeline_session_redirect
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/** Test tag on the scrollable surface — lets the screen test target a pull-to-refresh swipe AND lets
 *  the tab host detect "the Global feed is on screen" from ANY state (the redundant header is gone).
 *  Every screen state renders its scrollable with this tag so the pull-to-refresh gesture is recognized
 *  from each (mobile-design-system § "Canonical list loading and refresh pattern"). */
const val GLOBAL_TIMELINE_LIST_TAG: String = "globalTimelineList"

/** Test tag on each post card — lets the screen test target the open-detail tap. */
const val GLOBAL_POST_CARD_TAG: String = "globalPostCard"

/**
 * The Global feed — the all-Indonesia chronological feed and the onboarding entry-point surface
 * (`docs/02-Product.md` § Global Timeline). Hosted as the **Global pager page** of the
 * `mobile-home-tab-host` tab host; navigation-free (it holds no back-stack reference). Injects
 * [GlobalTimelineFlow] and observes a `HomeRoute`-scoped [GlobalTimelineViewModel] that holds the
 * [GlobalTimelineOutcome] + the split `isInitialLoad`/`isRefreshing` flags and (re)loads page 1
 * (pull-to-refresh + error-retry both re-fetch). Because the screen composes directly under `HomeRoute`
 * (design D1/D2), the `viewModel { }` resolves to the `HomeRoute` store and the loaded feed survives
 * swipes/tab switches + the composer round-trip with no re-fetch.
 *
 * The screen is **inset-free**: it declares NO `Scaffold` and NO `TopAppBar` — the app section shell
 * owns the single inset-owning `Scaffold` (mobile-design-system § "The app shell owns a single Scaffold
 * and window insets"). The redundant `timeline_global_title` "Seluruh Indonesia" header is removed (the
 * Global tab label already identifies the surface).
 *
 * Unlike Nearby, Global has **no location gate** (no spatial filter) and renders **no distance**. The
 * six fetch states (loading / content / empty / error / rate-limit-hard / rate-limit-soft) follow the
 * screen-state-mapping spec, all copy via `stringResource` (zero literals), under `NearYouTheme`
 * tokens. The Empty state reuses the loading skeleton + `timeline_loading` copy (Global is effectively
 * never empty).
 *
 * [onOpenPost] is the hoisted card-tap callback carrying the tapped card's PII-free [GlobalTimelinePost]
 * (no `author_user_id`, no coordinates, no distance — structurally absent from the projection); the tab
 * host maps it to a root-stack `PostDetailRoute` push (with `distanceM = null`, since Global has no
 * spatial filter). A host-level callback, NOT a back-stack reference, so the screen stays navigation-free.
 */
@Composable
fun GlobalTimelineScreen(
    onOpenPost: (GlobalTimelinePost) -> Unit = {},
    onOpenPostReply: (GlobalTimelinePost) -> Unit = {},
    onOpenProfile: (authorUserId: String) -> Unit = {},
    onActivatePremium: () -> Unit = {},
) {
    val flow = koinInject<GlobalTimelineFlow>()
    // The extracted cross-surface like seam (mobile-inline-post-actions D1) — the SAME
    // PostDetailRepository singleton post-detail and Nearby use.
    val likeFlow = koinInject<LikeFlow>()
    val viewModel = viewModel { GlobalTimelineViewModel(flow, likeFlow) }
    val outcome by viewModel.outcome.collectAsStateWithLifecycle()
    val isInitialLoad by viewModel.isInitialLoad.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val likeCapRetryAfterSeconds by viewModel.likeCapRetryAfterSeconds.collectAsStateWithLifecycle()
    val isLoadingMore by viewModel.isLoadingMore.collectAsStateWithLifecycle()
    val loadMoreError by viewModel.loadMoreError.collectAsStateWithLifecycle()

    GlobalTimelineContent(
        // Initial load → Loading skeleton; a retained Loaded outcome during a refresh → Content (the
        // list stays mounted). The refresh spinner is conveyed by isRefreshing, NOT by this projection.
        uiState = remember(outcome, isInitialLoad) { globalTimelineUiState(outcome, isInitialLoad) },
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
        onOpenPost = onOpenPost,
        // Inline like (shared controller in the VM) + the reply shortcut (hoisted to the tab host,
        // which pushes PostDetailRoute(focusReplyComposer = true)).
        onToggleLike = { post -> viewModel.toggleLike(post.id, post.likedByViewer) },
        onReplyShortcut = onOpenPostReply,
        // Identity tap → author profile: resolve the author UUID from the VM's raw DTO outcome (never on
        // the PII-free card model) and hand it to the hoisted onOpenProfile (mobile-profile).
        onOpenProfile = { post -> viewModel.authorUserIdForPost(post.id)?.let(onOpenProfile) },
    )

    // The Free like-cap dialog (mobile-cap-upsell-dialog, frame 18) — same one-shot wiring as Nearby;
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
 * Inset-free stateless render of the Global surface: a `PullToRefreshBox` filling the space under the
 * shell's padding (NO `Scaffold`, NO `TopAppBar`). It shows one of the six states; every non-`Content`
 * state is rendered inside a scrollable tagged [GLOBAL_TIMELINE_LIST_TAG] so the pull-to-refresh gesture
 * is recognized from each. [isRefreshing] drives only the pull-to-refresh indicator (kept separate from
 * the initial-load skeleton so the two never overlap).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GlobalTimelineContent(
    uiState: GlobalTimelineUiState,
    isRefreshing: Boolean,
    isLoadingMore: Boolean,
    loadMoreError: Boolean,
    onLoadMore: () -> Unit,
    onRetryLoadMore: () -> Unit,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onOpenPost: (GlobalTimelinePost) -> Unit,
    onToggleLike: (GlobalTimelinePost) -> Unit,
    onReplyShortcut: (GlobalTimelinePost) -> Unit,
    onOpenProfile: (GlobalTimelinePost) -> Unit,
) {
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        when (uiState) {
            // Loading AND Empty both render the loading skeleton (Global is effectively never empty —
            // spec § "Screen state mapping": Empty reuses the timeline_loading skeleton).
            GlobalTimelineUiState.Loading, GlobalTimelineUiState.Empty ->
                ListLoadingState(
                    message = stringResource(Res.string.timeline_loading),
                    testTag = GLOBAL_TIMELINE_LIST_TAG,
                    showSkeleton = true,
                )
            GlobalTimelineUiState.HardLimit ->
                ListCenteredMessageState(
                    message = stringResource(Res.string.timeline_limit_hard),
                    testTag = GLOBAL_TIMELINE_LIST_TAG,
                )
            GlobalTimelineUiState.Error -> ListErrorState(onRetry = onRetry, testTag = GLOBAL_TIMELINE_LIST_TAG)
            // Terminal 401 → neutral redirect placeholder: the redirect copy with NO retry control and
            // NOT signin_error_network (the SessionInvalidator re-route whisks the user to SignInScreen).
            GlobalTimelineUiState.SessionRedirect ->
                ListCenteredMessageState(
                    message = stringResource(Res.string.timeline_session_redirect),
                    testTag = GLOBAL_TIMELINE_LIST_TAG,
                )
            is GlobalTimelineUiState.Content ->
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
                    listTag = GLOBAL_TIMELINE_LIST_TAG,
                    cardTag = GLOBAL_POST_CARD_TAG,
                )
            is GlobalTimelineUiState.SoftLimit ->
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
                    listTag = GLOBAL_TIMELINE_LIST_TAG,
                    cardTag = GLOBAL_POST_CARD_TAG,
                    banner = stringResource(Res.string.timeline_limit_soft),
                )
        }
    }
}

/** Global → shared-card model: identity + content + city; `distanceM = null` (no spatial filter). */
private fun GlobalTimelinePost.toCardModel(): PostCardModel =
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
