package id.nearyou.app.screens.timeline

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import id.nearyou.app.data.like.LikeFlow
import id.nearyou.app.location.LocationConsentModal
import id.nearyou.app.location.LocationGate
import id.nearyou.app.location.LocationGateUiState
import id.nearyou.app.location.LocationPermissionController
import id.nearyou.app.timeline.NearbyTimelineFlow
import id.nearyou.app.timeline.NearbyTimelineOutcome
import id.nearyou.app.ui.components.DailyCapUpsellDialog
import id.nearyou.app.ui.components.PostCard
import id.nearyou.app.ui.components.PostCardModel
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.cta_retry
import id.nearyou.resources.generated.resources.cta_see_global
import id.nearyou.resources.generated.resources.location_open_settings
import id.nearyou.resources.generated.resources.nearby_location_denied
import id.nearyou.resources.generated.resources.post_detail_likes_cap_upsell
import id.nearyou.resources.generated.resources.signin_error_network
import id.nearyou.resources.generated.resources.timeline_empty_nearby
import id.nearyou.resources.generated.resources.timeline_limit_hard
import id.nearyou.resources.generated.resources.timeline_limit_soft
import id.nearyou.resources.generated.resources.timeline_loading
import id.nearyou.resources.generated.resources.timeline_session_redirect
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/** Test tag on the scrollable surface — lets the screen test target a pull-to-refresh swipe AND lets
 *  the tab host detect "the Nearby feed is on screen" from ANY state (the redundant header is gone).
 *  Every screen state (loading skeleton / empty / error / rate-limit / content) renders its scrollable
 *  with this tag so the pull-to-refresh gesture is recognized from each (mobile-design-system §
 *  "Canonical list loading and refresh pattern"). */
const val NEARBY_TIMELINE_LIST_TAG: String = "nearbyTimelineList"

/** Test tag on each post card — lets the screen test target the open-detail tap. */
const val NEARBY_POST_CARD_TAG: String = "nearbyPostCard"

/**
 * The first product surface — the authenticated Nearby feed, gated on a granted location permission
 * (`mobile-location-permission-flow`). Injects [LocationPermissionController] and drives a
 * [LocationGate] that, on entry, projects the OS permission status to one of: prompt the UU-PDP
 * consent rationale (→ OS prompt), proceed to the fetch, or show the denial fallback. The granted
 * branch ([NearbyFeed]) observes a `HomeRoute`-scoped [NearbyTimelineViewModel] that holds the
 * [NearbyTimelineOutcome] + the split `isInitialLoad`/`isRefreshing` flags and (re)loads page 1
 * (pull-to-refresh + error-retry both re-fetch). Hoisting that load state into a NavEntry-scoped
 * ViewModel is what makes returning from the composer (or swiping away and back) reuse the loaded feed
 * instead of re-fetching (design Decision 5). Renders the six fetch states (loading / content / empty
 * / error / rate-limit-hard / rate-limit-soft) per the screen-state-mapping spec, all copy via
 * `stringResource` (zero literals), under `NearYouTheme` tokens.
 *
 * The screen is **inset-free**: it declares NO `Scaffold` and NO `TopAppBar` — the app section shell
 * owns the single inset-owning `Scaffold` (mobile-design-system § "The app shell owns a single Scaffold
 * and window insets"); this screen renders its pull-to-refresh list filling the space under the shell's
 * padding. The redundant `timeline_nearby_title` "Post dari lokasi ini" header is removed (design D6);
 * the location disambiguation it carried moves to the one-time onboarding hint (docs amendment).
 *
 * The gate is a **pre-fetch** screen state, orthogonal to the six fetch-outcome states:
 * `NearbyTimelineRepository`'s status→outcome mapping is unchanged (granted-but-no-fix reuses the
 * existing retryable error state, no new outcome member).
 *
 * Holds NO navigation dependency (no back-stack reference, no FAB), so the tab host can render
 * `NearbyTimelineScreen(...)` directly as the Nearby pager page (`mobile-post-creation` §
 * "NearbyTimelineScreen remains navigation-free"). The empty state renders the "lihat Global" CTA,
 * which invokes the hoisted [onSeeGlobal] lambda — a host-level **tab-state** callback (the tab host
 * wires it to select the Global tab), NOT a back-stack reference. [onOpenPost] is the hoisted card-tap
 * callback carrying the tapped card's PII-free [NearbyTimelinePost] (no `author_user_id`, no
 * coordinates); the tab host maps it to a root-stack `PostDetailRoute` push. Both default to a no-op so
 * direct (non-tab-host) composition stays valid.
 */
@Composable
fun NearbyTimelineScreen(
    onSeeGlobal: () -> Unit = {},
    onOpenPost: (NearbyTimelinePost) -> Unit = {},
    onOpenPostReply: (NearbyTimelinePost) -> Unit = {},
    onOpenProfile: (authorUserId: String) -> Unit = {},
) {
    val controller = koinInject<LocationPermissionController>()
    val gate = remember { LocationGate(controller) }
    val gateState by gate.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // Re-query the OS permission on every foreground entry (ON_RESUME), not just first composition,
    // so returning from the OS Settings screen (via "Buka Pengaturan") immediately reflects a
    // newly-granted permission — without a cold restart. refresh() NEVER fires the OS prompt, so a
    // prior denial does not re-nag the rationale on every Nearby visit (the "Buka Pengaturan" CTA is
    // the only re-entry path) — spec § "A prior denial does not re-show the rationale on every Nearby visit".
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        scope.launch { gate.refresh() }
    }

    when (gateState) {
        LocationGateUiState.Loading -> LocationGateSpinner()
        LocationGateUiState.Rationale -> {
            // Neutral backdrop while the consent rationale modal is up; accepting fires the OS
            // prompt, declining drops to the denial fallback (no OS prompt forced).
            LocationGateSpinner()
            LocationConsentModal(
                onAccept = { scope.launch { gate.onRationaleAccepted() } },
                onDecline = { gate.onRationaleDeclined() },
            )
        }
        LocationGateUiState.Denied -> LocationDeniedState(onOpenSettings = { controller.openAppSettings() })
        LocationGateUiState.Granted ->
            NearbyFeed(
                onSeeGlobal = onSeeGlobal,
                onOpenPost = onOpenPost,
                onOpenPostReply = onOpenPostReply,
                onOpenProfile = onOpenProfile,
            )
    }
}

/**
 * The granted-permission Nearby feed: the fetch path, with its load state hoisted into a
 * `HomeRoute`-scoped [NearbyTimelineViewModel] (resolved via `viewModel { … }` under the
 * `rememberViewModelStoreNavEntryDecorator`). The VM exposes `isInitialLoad` (drives the skeleton)
 * separately from `isRefreshing` (drives only the `PullToRefreshBox` indicator), so a refresh keeps the
 * content list mounted (design D3). Hoisting the state off the composition is the reload-on-return fix
 * (mobile-nav-swap-to-navigation3 Decision 5): the VM survives `HomeRoute` going off-screen while the
 * composer is on top OR while another feed page is shown, so popping/swiping back reuses the loaded
 * feed. A coordinate-acquisition failure still maps to the EXISTING retryable error state in the VM —
 * NO new [NearbyTimelineOutcome] member.
 */
@Composable
private fun NearbyFeed(
    onSeeGlobal: () -> Unit,
    onOpenPost: (NearbyTimelinePost) -> Unit,
    onOpenPostReply: (NearbyTimelinePost) -> Unit,
    onOpenProfile: (authorUserId: String) -> Unit,
) {
    val flow = koinInject<NearbyTimelineFlow>()
    // The extracted cross-surface like seam (mobile-inline-post-actions D1) — the SAME
    // PostDetailRepository singleton post-detail uses; resolved here and handed to the VM's
    // shared InlineLikeController instance.
    val likeFlow = koinInject<LikeFlow>()
    val viewModel = viewModel { NearbyTimelineViewModel(flow, likeFlow) }
    val outcome by viewModel.outcome.collectAsStateWithLifecycle()
    val isInitialLoad by viewModel.isInitialLoad.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val likeCapRetryAfterSeconds by viewModel.likeCapRetryAfterSeconds.collectAsStateWithLifecycle()

    NearbyTimelineContent(
        // Initial load → Loading skeleton; a retained Loaded outcome during a refresh → Content (the
        // list stays mounted). The refresh spinner is conveyed by isRefreshing, NOT by this projection.
        uiState = remember(outcome, isInitialLoad) { nearbyTimelineUiState(outcome, isInitialLoad) },
        isRefreshing = isRefreshing,
        // Both pull-to-refresh and the error-retry control re-fetch page 1 via the VM (shared reload
        // path). `next_cursor` is retained on Loaded but NOT consumed for load-more (deferred to
        // mobile-nearby-timeline-infinite-scroll).
        onRefresh = viewModel::reload,
        onRetry = viewModel::reload,
        // The empty-state "lihat Global" CTA — a host-level tab-switch callback (the tab host selects
        // the Global tab), NOT a back-stack reference, so the screen stays navigation-free.
        onSeeGlobal = onSeeGlobal,
        onOpenPost = onOpenPost,
        // Inline like: the tapped card's CURRENT likedByViewer drives the direction (both directions
        // valid — false → POST, true → DELETE). The optimistic flip + outcome handling live in the
        // shared controller inside the VM.
        onToggleLike = { post -> viewModel.toggleLike(post.id, post.likedByViewer) },
        // Reply shortcut: hoisted to the tab host, which pushes PostDetailRoute(focusReplyComposer = true).
        onReplyShortcut = onOpenPostReply,
        // Identity tap → author profile: resolve the author UUID from the VM's raw DTO outcome (never on
        // the PII-free card model) and hand it to the hoisted onOpenProfile (mobile-profile).
        onOpenProfile = { post -> viewModel.authorUserIdForPost(post.id)?.let(onOpenProfile) },
    )

    // The Free like-cap dialog (mobile-cap-upsell-dialog, frame 18): shown while the one-shot cap
    // state is non-null. The body is the verbatim docs/03:187 modal copy formatted with the live
    // ticking countdown. The Premium CTA is the v1 dismiss-only placeholder — the paywall destination
    // is the deferred requirement tracked by issue #235.
    likeCapRetryAfterSeconds?.let { retryAfterSeconds ->
        DailyCapUpsellDialog(
            retryAfterSeconds = retryAfterSeconds,
            body = { countdown -> stringResource(Res.string.post_detail_likes_cap_upsell, countdown) },
            onDismiss = viewModel::onLikeCapDialogDismissed,
            onActivatePremium = viewModel::onLikeCapDialogDismissed,
        )
    }
}

/** Neutral centered spinner shown while the OS permission status is being queried (or behind the modal). */
@Composable
private fun LocationGateSpinner() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/**
 * Pre-fetch location-permission-denied state: the "Aktifkan lokasi…" copy + a "Buka Pengaturan" CTA
 * that deep-links to the OS app-settings screen. No posts are fetched in this state (spec § "Nearby
 * feed is gated on location permission with a denial fallback").
 */
@Composable
private fun LocationDeniedState(onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(Res.string.nearby_location_denied),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onOpenSettings, modifier = Modifier.padding(top = 16.dp)) {
            Text(text = stringResource(Res.string.location_open_settings))
        }
    }
}

/**
 * Inset-free stateless render of the Nearby surface: a `PullToRefreshBox` filling the space under the
 * shell's padding (NO `Scaffold`, NO `TopAppBar`). It shows one of the six states; every non-`Content`
 * state is rendered inside a scrollable tagged [NEARBY_TIMELINE_LIST_TAG], so the pull-to-refresh
 * gesture is recognized from each of them too (mobile-design-system § "Canonical list loading and
 * refresh pattern"). [isRefreshing] drives only the pull-to-refresh indicator (the refresh-of-content
 * state), kept separate from the initial-load skeleton so the two indicators never overlap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NearbyTimelineContent(
    uiState: NearbyTimelineUiState,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onSeeGlobal: () -> Unit,
    onOpenPost: (NearbyTimelinePost) -> Unit,
    onToggleLike: (NearbyTimelinePost) -> Unit,
    onReplyShortcut: (NearbyTimelinePost) -> Unit,
    onOpenProfile: (NearbyTimelinePost) -> Unit,
) {
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        when (uiState) {
            NearbyTimelineUiState.Loading -> LoadingState()
            NearbyTimelineUiState.Empty -> NearbyEmptyState(onSeeGlobal = onSeeGlobal)
            NearbyTimelineUiState.HardLimit -> CenteredMessageState(stringResource(Res.string.timeline_limit_hard))
            NearbyTimelineUiState.Error -> ErrorState(onRetry = onRetry)
            // Terminal 401 → neutral redirect placeholder: the redirect copy with NO retry control and
            // NOT signin_error_network (the SessionInvalidator re-route whisks the user to SignInScreen).
            NearbyTimelineUiState.SessionRedirect ->
                CenteredMessageState(stringResource(Res.string.timeline_session_redirect))
            is NearbyTimelineUiState.Content ->
                PostList(
                    posts = uiState.posts,
                    onOpenPost = onOpenPost,
                    onToggleLike = onToggleLike,
                    onReplyShortcut = onReplyShortcut,
                    onOpenProfile = onOpenProfile,
                )
            is NearbyTimelineUiState.SoftLimit ->
                PostList(
                    posts = uiState.posts,
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
 * [NEARBY_TIMELINE_LIST_TAG]) so the `PullToRefreshBox` recognizes the pull gesture from it (a
 * `PullToRefreshBox` requires a scrollable child). The single item fills the viewport and centers
 * [content].
 */
@Composable
private fun NearbyScrollableState(content: @Composable () -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize().testTag(NEARBY_TIMELINE_LIST_TAG)) {
        item {
            Box(modifier = Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                content()
            }
        }
    }
}

@Composable
private fun LoadingState() {
    NearbyScrollableState {
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
    NearbyScrollableState {
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
    NearbyScrollableState {
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

/**
 * The sparse-area empty state: the `timeline_empty_nearby` copy ("Area kamu belum ramai…") plus a
 * "lihat Global" CTA that invokes the hoisted [onSeeGlobal] (the tab host selects the Global tab).
 * Rendered inside a scrollable so pull-to-refresh works from the empty state too
 * (`mobile-nearby-timeline` § "Pull-to-refresh works from the empty / error state").
 */
@Composable
private fun NearbyEmptyState(onSeeGlobal: () -> Unit) {
    NearbyScrollableState {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(Res.string.timeline_empty_nearby),
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
private fun PostList(
    posts: List<NearbyTimelinePost>,
    onOpenPost: (NearbyTimelinePost) -> Unit,
    onToggleLike: (NearbyTimelinePost) -> Unit,
    onReplyShortcut: (NearbyTimelinePost) -> Unit,
    onOpenProfile: (NearbyTimelinePost) -> Unit,
    banner: String? = null,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag(NEARBY_TIMELINE_LIST_TAG),
        // Bottom clearance for the shell's overlaid composer FAB (56dp + 16 margin + breathing
        // room) so the last card's like/reply row never sits under it at scroll end
        // (2026-06-10 audit, 06 low).
        contentPadding = PaddingValues(top = 8.dp, bottom = 88.dp),
    ) {
        if (banner != null) {
            item {
                SoftLimitBanner(text = banner)
            }
        }
        items(items = posts, key = { it.id }, contentType = { "post" }) { post ->
            // The ONE shared card (ui/components, mobile-post-card) — the whole card is the
            // open-detail tap; the action row's like/reply affordances are the only other targets
            // (mobile-inline-post-actions). All callbacks carry the PII-free post (display identity
            // included, never the author UUID / coordinates).
            PostCard(
                model = post.toCardModel(),
                onOpen = { onOpenPost(post) },
                onToggleLike = { onToggleLike(post) },
                onReplyShortcut = { onReplyShortcut(post) },
                onOpenProfile = { onOpenProfile(post) },
                modifier = Modifier.testTag(NEARBY_POST_CARD_TAG),
            )
        }
    }
}

/** Nearby → shared-card model: identity + content + city + the server distance (rendered via the
 *  shared `DistanceRenderer` inside the card). */
private fun NearbyTimelinePost.toCardModel(): PostCardModel =
    PostCardModel(
        id = id,
        authorUsername = authorUsername,
        authorDisplayName = authorDisplayName,
        content = content,
        cityName = cityName,
        distanceM = distanceM,
        createdAt = createdAt,
        likedByViewer = likedByViewer,
        replyCount = replyCount,
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
