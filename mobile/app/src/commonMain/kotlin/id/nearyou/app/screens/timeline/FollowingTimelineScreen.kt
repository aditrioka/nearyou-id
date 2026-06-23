package id.nearyou.app.screens.timeline

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import id.nearyou.app.data.like.LikeFlow
import id.nearyou.app.timeline.FollowingTimelineFlow
import id.nearyou.app.timeline.FollowingTimelineOutcome
import id.nearyou.app.ui.components.DailyCapUpsellDialog
import id.nearyou.app.ui.components.LoadMoreFooter
import id.nearyou.app.ui.components.LoadMoreOnScrollEnd
import id.nearyou.app.ui.components.PostCard
import id.nearyou.app.ui.components.PostCardModel
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.cta_retry
import id.nearyou.resources.generated.resources.cta_see_global
import id.nearyou.resources.generated.resources.post_detail_likes_cap_upsell
import id.nearyou.resources.generated.resources.signin_error_network
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
    val viewModel = viewModel { FollowingTimelineViewModel(flow, likeFlow) }
    val outcome by viewModel.outcome.collectAsStateWithLifecycle()
    val isInitialLoad by viewModel.isInitialLoad.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val likeCapRetryAfterSeconds by viewModel.likeCapRetryAfterSeconds.collectAsStateWithLifecycle()
    val isLoadingMore by viewModel.isLoadingMore.collectAsStateWithLifecycle()
    val loadMoreError by viewModel.loadMoreError.collectAsStateWithLifecycle()

    FollowingTimelineContent(
        // Initial load → Loading skeleton; a retained Loaded outcome during a refresh → Content (the
        // list stays mounted). The refresh spinner is conveyed by isRefreshing, NOT by this projection.
        uiState = remember(outcome, isInitialLoad) { followingTimelineUiState(outcome, isInitialLoad) },
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
) {
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        when (uiState) {
            FollowingTimelineUiState.Loading -> LoadingState()
            // The DIVERGENCE from Global: a directive empty (placeholder copy + "Lihat Global" CTA), NOT
            // the loading skeleton — Following-empty is a real expected state (docs/03 § Empty State).
            FollowingTimelineUiState.Empty -> FollowingEmptyState(onSeeGlobal = onSeeGlobal)
            FollowingTimelineUiState.HardLimit -> CenteredMessageState(stringResource(Res.string.timeline_limit_hard))
            FollowingTimelineUiState.Error -> ErrorState(onRetry = onRetry)
            // Terminal 401 → neutral redirect placeholder: the redirect copy with NO retry control and
            // NOT signin_error_network (the SessionInvalidator re-route whisks the user to SignInScreen).
            FollowingTimelineUiState.SessionRedirect ->
                CenteredMessageState(stringResource(Res.string.timeline_session_redirect))
            is FollowingTimelineUiState.Content ->
                PostList(
                    posts = uiState.posts,
                    isLoadingMore = isLoadingMore,
                    loadMoreError = loadMoreError,
                    onLoadMore = onLoadMore,
                    onRetryLoadMore = onRetryLoadMore,
                    onOpenPost = onOpenPost,
                    onToggleLike = onToggleLike,
                    onReplyShortcut = onReplyShortcut,
                    onOpenProfile = onOpenProfile,
                )
            is FollowingTimelineUiState.SoftLimit ->
                PostList(
                    posts = uiState.posts,
                    isLoadingMore = isLoadingMore,
                    loadMoreError = loadMoreError,
                    onLoadMore = onLoadMore,
                    onRetryLoadMore = onRetryLoadMore,
                    onOpenPost = onOpenPost,
                    onToggleLike = onToggleLike,
                    onReplyShortcut = onReplyShortcut,
                    onOpenProfile = onOpenProfile,
                    banner = stringResource(Res.string.timeline_limit_soft),
                )
        }
    }
}

/**
 * A non-`Content` screen state rendered inside a single-item `LazyColumn` (tagged
 * [FOLLOWING_TIMELINE_LIST_TAG]) so the `PullToRefreshBox` recognizes the pull gesture from it. The
 * single item fills the viewport and centers [content].
 */
@Composable
private fun FollowingScrollableState(content: @Composable () -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize().testTag(FOLLOWING_TIMELINE_LIST_TAG)) {
        item {
            Box(modifier = Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                content()
            }
        }
    }
}

@Composable
private fun LoadingState() {
    FollowingScrollableState {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text(
                text = stringResource(Res.string.timeline_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )
            // Skeleton placeholder cards (no content) to signal a list is loading.
            repeat(3) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(72.dp),
                    content = {},
                )
            }
        }
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
    FollowingScrollableState {
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

@Composable
private fun CenteredMessageState(message: String) {
    FollowingScrollableState {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(24.dp),
        )
    }
}

@Composable
private fun ErrorState(onRetry: () -> Unit) {
    FollowingScrollableState {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
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

@Composable
private fun PostList(
    posts: List<FollowingTimelinePost>,
    isLoadingMore: Boolean,
    loadMoreError: Boolean,
    onLoadMore: () -> Unit,
    onRetryLoadMore: () -> Unit,
    onOpenPost: (FollowingTimelinePost) -> Unit,
    onToggleLike: (FollowingTimelinePost) -> Unit,
    onReplyShortcut: (FollowingTimelinePost) -> Unit,
    onOpenProfile: (FollowingTimelinePost) -> Unit,
    banner: String? = null,
) {
    val listState = rememberLazyListState()
    LoadMoreOnScrollEnd(listState = listState, onLoadMore = onLoadMore)
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().testTag(FOLLOWING_TIMELINE_LIST_TAG),
        // Bottom clearance for the shell's overlaid composer FAB (56dp + 16 margin + breathing room) so
        // the last card's like/reply row never sits under it at scroll end.
        contentPadding = PaddingValues(top = 8.dp, bottom = 88.dp),
    ) {
        if (banner != null) {
            item {
                SoftLimitBanner(text = banner)
            }
        }
        items(items = posts, key = { it.id }, contentType = { "post" }) { post ->
            // The ONE shared card (ui/components, mobile-post-card) — distanceM = null on this surface
            // (Following has no spatial filter, no distance is rendered). The whole card is the
            // open-detail tap; the action row's like/reply affordances are the only other targets
            // (mobile-inline-post-actions). All callbacks carry the PII-free post.
            PostCard(
                model = post.toCardModel(),
                onOpen = { onOpenPost(post) },
                onToggleLike = { onToggleLike(post) },
                onReplyShortcut = { onReplyShortcut(post) },
                onOpenProfile = { onOpenProfile(post) },
                modifier = Modifier.testTag(FOLLOWING_POST_CARD_TAG),
            )
        }
        // Load-more footer: spinner while a page loads, non-destructive retry on error, nothing at end
        // (mobile-design-system § "Canonical list load-more pattern"). The scroll-end detector above
        // drives onLoadMore; the LoadMoreController's guards make an eager trigger on a short list a no-op.
        item(key = "loadMoreFooter", contentType = "footer") {
            LoadMoreFooter(
                isLoadingMore = isLoadingMore,
                loadMoreError = loadMoreError,
                onRetry = onRetryLoadMore,
            )
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

@Composable
private fun SoftLimitBanner(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(12.dp),
        )
    }
}
