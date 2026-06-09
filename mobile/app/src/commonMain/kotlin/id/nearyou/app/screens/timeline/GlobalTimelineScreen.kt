package id.nearyou.app.screens.timeline

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import id.nearyou.app.timeline.GlobalTimelineFlow
import id.nearyou.app.timeline.GlobalTimelineOutcome
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.cta_retry
import id.nearyou.resources.generated.resources.ic_post_like
import id.nearyou.resources.generated.resources.ic_post_like_filled
import id.nearyou.resources.generated.resources.ic_post_location
import id.nearyou.resources.generated.resources.ic_post_reply
import id.nearyou.resources.generated.resources.ic_post_time
import id.nearyou.resources.generated.resources.signin_error_network
import id.nearyou.resources.generated.resources.timeline_limit_hard
import id.nearyou.resources.generated.resources.timeline_limit_soft
import id.nearyou.resources.generated.resources.timeline_loading
import id.nearyou.resources.generated.resources.timeline_session_redirect
import id.nearyou.resources.theme.locationPin
import org.jetbrains.compose.resources.painterResource
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
fun GlobalTimelineScreen(onOpenPost: (GlobalTimelinePost) -> Unit = {}) {
    val flow = koinInject<GlobalTimelineFlow>()
    val viewModel = viewModel { GlobalTimelineViewModel(flow) }
    val outcome by viewModel.outcome.collectAsState()
    val isInitialLoad by viewModel.isInitialLoad.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()

    GlobalTimelineContent(
        // Initial load → Loading skeleton; a retained Loaded outcome during a refresh → Content (the
        // list stays mounted). The refresh spinner is conveyed by isRefreshing, NOT by this projection.
        uiState = globalTimelineUiState(outcome, isInitialLoad),
        isRefreshing = isRefreshing,
        // Both pull-to-refresh and the error-retry control re-fetch page 1 via the VM (shared reload
        // path). `next_cursor` is retained on Loaded but NOT consumed for load-more (deferred alongside
        // mobile-nearby-timeline-infinite-scroll, extended to cover Global).
        onRefresh = viewModel::reload,
        onRetry = viewModel::reload,
        onOpenPost = onOpenPost,
    )
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
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onOpenPost: (GlobalTimelinePost) -> Unit,
) {
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        when (uiState) {
            // Loading AND Empty both render the loading skeleton (Global is effectively never empty —
            // spec § "Screen state mapping": Empty reuses the timeline_loading skeleton).
            GlobalTimelineUiState.Loading, GlobalTimelineUiState.Empty -> LoadingState()
            GlobalTimelineUiState.HardLimit -> CenteredMessageState(stringResource(Res.string.timeline_limit_hard))
            GlobalTimelineUiState.Error -> ErrorState(onRetry = onRetry)
            // Terminal 401 → neutral redirect placeholder: the redirect copy with NO retry control and
            // NOT signin_error_network (the SessionInvalidator re-route whisks the user to SignInScreen).
            GlobalTimelineUiState.SessionRedirect ->
                CenteredMessageState(stringResource(Res.string.timeline_session_redirect))
            is GlobalTimelineUiState.Content -> PostList(posts = uiState.posts, onOpenPost = onOpenPost)
            is GlobalTimelineUiState.SoftLimit ->
                PostList(
                    posts = uiState.posts,
                    onOpenPost = onOpenPost,
                    banner = stringResource(Res.string.timeline_limit_soft),
                )
        }
    }
}

/**
 * A non-`Content` screen state rendered inside a single-item `LazyColumn` (tagged
 * [GLOBAL_TIMELINE_LIST_TAG]) so the `PullToRefreshBox` recognizes the pull gesture from it. The single
 * item fills the viewport and centers [content].
 */
@Composable
private fun GlobalScrollableState(content: @Composable () -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize().testTag(GLOBAL_TIMELINE_LIST_TAG)) {
        item {
            Box(modifier = Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                content()
            }
        }
    }
}

@Composable
private fun LoadingState() {
    GlobalScrollableState {
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

@Composable
private fun CenteredMessageState(message: String) {
    GlobalScrollableState {
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
    GlobalScrollableState {
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
    posts: List<GlobalTimelinePost>,
    onOpenPost: (GlobalTimelinePost) -> Unit,
    banner: String? = null,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag(GLOBAL_TIMELINE_LIST_TAG),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        if (banner != null) {
            item {
                SoftLimitBanner(text = banner)
            }
        }
        items(items = posts, key = { it.id }) { post ->
            GlobalPostCard(post = post, onOpenPost = onOpenPost)
        }
    }
}

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

/**
 * Read-only Global post card (design D10, X-style content-forward layout): `content` (bodyLarge), a
 * metadata row (Material location icon + `city_name` when non-empty + a Material time/clock icon + the
 * post date) and a read-only counts row (a Material like icon — filled when liked, outlined otherwise —
 * + a Material reply icon + `reply_count`). The brand-tinted placeholder dots are replaced by Material
 * affordance icons. Renders **no distance** (Global has no spatial filter), NO `author_user_id`, and NO
 * raw `latitude`/`longitude` (those fields are not even present on [GlobalTimelinePost]). The affordance
 * icons are decorative → `contentDescription = null`. Built entirely from `NearYouTheme` tokens.
 */
@Composable
private fun GlobalPostCard(
    post: GlobalTimelinePost,
    onOpenPost: (GlobalTimelinePost) -> Unit,
) {
    // The whole card is the open-detail tap (the hoisted onOpenPost carries the PII-free post up to the
    // tab host's root-stack PostDetailRoute push, with distanceM = null). No inline like/reply control.
    OutlinedCard(
        onClick = { onOpenPost(post) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).testTag(GLOBAL_POST_CARD_TAG),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = post.content,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(Res.drawable.ic_post_location),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.locationPin,
                    modifier = Modifier.size(16.dp),
                )
                if (post.cityName.isNotEmpty()) {
                    Text(
                        text = post.cityName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    painter = painterResource(Res.drawable.ic_post_time),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    // The post date (ISO date portion). No distance is shown — Global has no spatial
                    // filter. Richer relative formatting ("2j lalu") is the same deferred refinement as
                    // Nearby (needs a localized unit-string set + an injected clock).
                    text = postDateLabel(post.createdAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Like-state affordance: a filled brand-tinted heart when the viewer liked it, else a
                // muted outlined heart. Decorative (the row conveys read-only counts) → no literal.
                Icon(
                    painter =
                        painterResource(
                            if (post.likedByViewer) Res.drawable.ic_post_like_filled else Res.drawable.ic_post_like,
                        ),
                    contentDescription = null,
                    tint =
                        if (post.likedByViewer) {
                            MaterialTheme.colorScheme.locationPin
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    modifier = Modifier.size(16.dp),
                )
                Icon(
                    painter = painterResource(Res.drawable.ic_post_reply),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = post.replyCount.toString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** ISO-8601 `createdAt` → its date portion ("2026-05-31"). Pure + deterministic (no wall clock). */
private fun postDateLabel(createdAt: String): String = createdAt.substringBefore('T')
