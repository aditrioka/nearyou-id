package id.nearyou.app.screens.timeline

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import id.nearyou.app.auth.SelfUserIdProvider
import id.nearyou.app.data.like.LikeFlow
import id.nearyou.app.data.report.ReportReasonCategory
import id.nearyou.app.data.report.ReportSubmitter
import id.nearyou.app.location.LocationConsentModal
import id.nearyou.app.location.LocationGateUiState
import id.nearyou.app.location.LocationPermissionController
import id.nearyou.app.profile.ProfileFlow
import id.nearyou.app.timeline.NearbyTimelineFlow
import id.nearyou.app.timeline.NearbyTimelineOutcome
import id.nearyou.app.ui.ads.rememberTimelineAds
import id.nearyou.app.ui.components.DailyCapUpsellDialog
import id.nearyou.app.ui.components.ListCenteredMessageState
import id.nearyou.app.ui.components.ListErrorState
import id.nearyou.app.ui.components.ListLoadingState
import id.nearyou.app.ui.components.ListScrollableState
import id.nearyou.app.ui.components.PostCardModel
import id.nearyou.app.ui.components.PostFeedList
import id.nearyou.app.ui.components.RadiusPremiumUpsellDialog
import id.nearyou.app.ui.timeline.TimelineReportMessage
import id.nearyou.app.ui.timeline.TimelineReportOverlay
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.cta_see_global
import id.nearyou.resources.generated.resources.location_open_settings
import id.nearyou.resources.generated.resources.nearby_location_denied
import id.nearyou.resources.generated.resources.post_detail_likes_cap_upsell
import id.nearyou.resources.generated.resources.radius_endpoint_farthest
import id.nearyou.resources.generated.resources.radius_endpoint_nearest
import id.nearyou.resources.generated.resources.radius_value_km
import id.nearyou.resources.generated.resources.timeline_empty_nearby
import id.nearyou.resources.generated.resources.timeline_limit_hard
import id.nearyou.resources.generated.resources.timeline_limit_soft
import id.nearyou.resources.generated.resources.timeline_loading
import id.nearyou.resources.generated.resources.timeline_session_redirect
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import kotlin.math.roundToInt

/** Test tag on the scrollable surface — lets the screen test target a pull-to-refresh swipe AND lets
 *  the tab host detect "the Nearby feed is on screen" from ANY state (the redundant header is gone).
 *  Every screen state (loading skeleton / empty / error / rate-limit / content) renders its scrollable
 *  with this tag so the pull-to-refresh gesture is recognized from each (mobile-design-system §
 *  "Canonical list loading and refresh pattern"). */
const val NEARBY_TIMELINE_LIST_TAG: String = "nearbyTimelineList"

/** Test tag on each post card — lets the screen test target the open-detail tap. */
const val NEARBY_POST_CARD_TAG: String = "nearbyPostCard"

/** Test tag on the radius slider (mobile-nearby-radius-slider) — lets the screen test drag/assert it. */
const val NEARBY_RADIUS_SLIDER_TAG: String = "nearbyRadiusSlider"

/** Test tag on the timeline report dialog (timeline-card-report-kebab). */
const val NEARBY_REPORT_DIALOG_TAG: String = "nearbyReportDialog"

/**
 * The first product surface — the authenticated Nearby feed, gated on a granted location permission
 * (`mobile-location-permission-flow`). Injects [LocationPermissionController] into a `HomeRoute`-scoped
 * [LocationGateViewModel] (hosting the Compose-free `LocationGate` orchestrator) that, on entry, projects
 * the OS permission status to one of: prompt the UU-PDP consent rationale (→ OS prompt), proceed to the
 * fetch, or show the denial fallback. Entry-scoping the gate (audit 05-#12) is what stops it rebuilding —
 * resetting to `Loading` + re-querying the OS — on every swipe back to Nearby; it now survives in the same
 * store as the feed VM. The granted
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
    onActivatePremium: () -> Unit = {},
) {
    val controller = koinInject<LocationPermissionController>()
    // HomeRoute-scoped (audit 05-#12): resolved against the same NavEntry store as the feed VM, so the gate
    // state survives the Nearby page going off-screen instead of rebuilding (Loading + OS re-query) on every
    // swipe back. The VM owns the Compose-free LocationGate orchestrator and the viewModelScope launches.
    val gateViewModel = viewModel { LocationGateViewModel(controller) }
    val gateState by gateViewModel.uiState.collectAsStateWithLifecycle()

    // Re-query the OS permission on every foreground entry (ON_RESUME), not just first composition,
    // so returning from the OS Settings screen (via "Buka Pengaturan") immediately reflects a
    // newly-granted permission — without a cold restart. refresh() NEVER fires the OS prompt, so a
    // prior denial does not re-nag the rationale on every Nearby visit (the "Buka Pengaturan" CTA is
    // the only re-entry path) — spec § "A prior denial does not re-show the rationale on every Nearby visit".
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        gateViewModel.refresh()
    }

    when (gateState) {
        LocationGateUiState.Loading -> LocationGateSpinner()
        LocationGateUiState.Rationale -> {
            // Neutral backdrop while the consent rationale modal is up; accepting fires the OS
            // prompt, declining drops to the denial fallback (no OS prompt forced).
            LocationGateSpinner()
            LocationConsentModal(
                onAccept = gateViewModel::onRationaleAccepted,
                onDecline = gateViewModel::onRationaleDeclined,
            )
        }
        LocationGateUiState.Denied -> LocationDeniedState(onOpenSettings = gateViewModel::openAppSettings)
        LocationGateUiState.Granted ->
            NearbyFeed(
                onSeeGlobal = onSeeGlobal,
                onOpenPost = onOpenPost,
                onOpenPostReply = onOpenPostReply,
                onOpenProfile = onOpenProfile,
                onActivatePremium = onActivatePremium,
            )
    }
}

/**
 * The granted-permission Nearby feed: the fetch path, with its load state hoisted into a
 * `HomeRoute`-scoped [NearbyTimelineViewModel] (resolved via `viewModel { … }` under the
 * `rememberViewModelStoreNavEntryDecorator`). The VM exposes one `uiState` (the projection; `Loading`
 * until the first outcome) separately from `isRefreshing` (drives only the `PullToRefreshBox` indicator),
 * so a refresh keeps the content list mounted (design D3). Hoisting the state off the composition is the
 * reload-on-return fix (mobile-nav-swap-to-navigation3 Decision 5): the VM survives `HomeRoute` going
 * off-screen while the
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
    onActivatePremium: () -> Unit,
) {
    val flow = koinInject<NearbyTimelineFlow>()
    // The extracted cross-surface like seam (mobile-inline-post-actions D1) — the SAME
    // PostDetailRepository singleton post-detail uses; resolved here and handed to the VM's
    // shared InlineLikeController instance.
    val likeFlow = koinInject<LikeFlow>()
    // mobile-nearby-radius-slider: the self-profile read seam for the on-entry Premium gate (the same
    // ProfileFlow + SelfUserIdProvider UsernameCustomizationViewModel uses for its isPremium resolution).
    val profileFlow = koinInject<ProfileFlow>()
    val selfUserIdProvider = koinInject<SelfUserIdProvider>()
    // timeline-card-report-kebab: the shared report seam (the SAME ReportSubmitter singleton the
    // profile/post-detail/chat surfaces use).
    val reportSubmitter = koinInject<ReportSubmitter>()
    val viewModel =
        viewModel { NearbyTimelineViewModel(flow, likeFlow, profileFlow, selfUserIdProvider, reportSubmitter) }
    // The single screen state — the nearbyTimelineUiState projection now lives in the VM (docs/11 §2.2),
    // collected here instead of re-derived in the composable.
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val likeCapRetryAfterSeconds by viewModel.likeCapRetryAfterSeconds.collectAsStateWithLifecycle()
    val isLoadingMore by viewModel.isLoadingMore.collectAsStateWithLifecycle()
    val loadMoreError by viewModel.loadMoreError.collectAsStateWithLifecycle()
    // mobile-nearby-radius-slider: the selected radius drives the slider thumb; the upsell one-shot drives
    // both the snap-back re-sync (in the selector) and the Premium upsell dialog (below).
    val selectedRadiusM by viewModel.selectedRadiusM.collectAsStateWithLifecycle()
    val radiusUpsell by viewModel.radiusUpsell.collectAsStateWithLifecycle()
    // timeline-card-report-kebab: the collected self id feeds reportActionFor so the kebabs appear once
    // the id resolves (compose-state read → the cards recompose); the dialog/message one-shots below.
    val selfUserId by viewModel.selfUserId.collectAsStateWithLifecycle()
    val reportingPostId by viewModel.reportingPostId.collectAsStateWithLifecycle()
    val reportMessage by viewModel.reportMessage.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        // The 4-position radius control above the feed (Premium-gated). Free sliding bounces back to 20 km
        // + raises the upsell; Premium picks any of 10/20/50/100 km, re-fetching page 1 at the new radius.
        NearbyRadiusSelector(
            selectedRadiusM = selectedRadiusM,
            radiusUpsell = radiusUpsell,
            onSelect = viewModel::selectRadius,
        )
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            NearbyTimelineContent(
                // Initial load → Loading skeleton; a retained Loaded outcome during a refresh → Content (the
                // list stays mounted). The refresh spinner is conveyed by isRefreshing, NOT by this state.
                uiState = uiState,
                isRefreshing = isRefreshing,
                // Load-more (infinite scroll): the footer flags + the scroll-end/retry callbacks. The shared
                // LoadMoreController appends pages into the retained Loaded outcome, reusing the page-1 anchor.
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
                // Inline like: the tapped card's CURRENT likedByViewer drives the direction (both directions
                // valid — false → POST, true → DELETE). The optimistic flip + outcome handling live in the
                // shared controller inside the VM.
                onToggleLike = { post -> viewModel.toggleLike(post.id, post.likedByViewer) },
                // Reply shortcut: hoisted to the tab host, which pushes PostDetailRoute(focusReplyComposer = true).
                onReplyShortcut = onOpenPostReply,
                // Identity tap → author profile: resolve the author UUID from the VM's raw DTO outcome (never on
                // the PII-free card model) and hand it to the hoisted onOpenProfile (mobile-profile).
                onOpenProfile = { post -> viewModel.authorUserIdForPost(post.id)?.let(onOpenProfile) },
                // timeline-card-report-kebab: the per-item kebab action (null = own post / unresolved self
                // id → no kebab) + the dialog/message one-shot wiring for the shared overlay.
                reportActionOf = { post -> viewModel.reportActionFor(post.id, selfUserId) },
                reportingPostId = reportingPostId,
                reportMessage = reportMessage,
                onReportSubmit = viewModel::onReportSubmitted,
                onReportDismiss = viewModel::onReportDialogDismissed,
                onReportMessageShown = viewModel::onReportMessageShown,
            )
        }
    }

    // The Free like-cap dialog (mobile-cap-upsell-dialog, frame 18): shown while the one-shot cap
    // state is non-null. The body is the verbatim docs/03:187 modal copy formatted with the live
    // ticking countdown. The Premium CTA dismisses the dialog AND pushes the paywall via the host
    // (mobile-paywall-screen #235); the dialog component itself stays navigation-free.
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

    // mobile-nearby-radius-slider: the Premium radius upsell (one-shot). Raised when a Free viewer drags
    // to a Premium-only radius (the slider has already snapped back to 20 km) OR on a `radius_premium_only`
    // 403 backstop. "Aktifkan Premium" pushes the paywall via the host; both CTAs clear the one-shot.
    if (radiusUpsell) {
        RadiusPremiumUpsellDialog(
            onDismiss = viewModel::onRadiusUpsellShown,
            onActivatePremium = {
                viewModel.onRadiusUpsellShown()
                onActivatePremium()
            },
        )
    }
}

/**
 * The 4-position Nearby radius slider (`mobile-nearby-radius-slider`): an M3 [Slider] over
 * [NEARBY_RADIUS_POSITIONS_M] with a centered live km value above a slider flanked by "Terdekat" /
 * "Terjauh" endpoint labels (mockup screens frame 12), the value updating live as the thumb drags.
 * The thumb is driven by the VM's [selectedRadiusM]; on release [onSelect] commits the snapped position.
 * A Free viewer's drag is rejected by the VM (radius stays 20 km + [radiusUpsell] fires), so the thumb
 * bounces back — the [LaunchedEffect] re-syncs it to the permitted position whenever the selection OR the
 * upsell signal changes (a rejected attempt doesn't move [selectedRadiusM], so [radiusUpsell] is the
 * second re-sync trigger). All copy via `stringResource`.
 */
@Composable
private fun NearbyRadiusSelector(
    selectedRadiusM: Int,
    radiusUpsell: Boolean,
    onSelect: (Int) -> Unit,
) {
    val positions = NEARBY_RADIUS_POSITIONS_M
    val selectedIndex = positions.indexOf(selectedRadiusM).coerceAtLeast(0)
    var sliderPosition by remember { mutableFloatStateOf(selectedIndex.toFloat()) }
    LaunchedEffect(selectedRadiusM, radiusUpsell) {
        sliderPosition = positions.indexOf(selectedRadiusM).coerceAtLeast(0).toFloat()
    }
    val displayKm = positions[sliderPosition.roundToInt().coerceIn(0, positions.lastIndex)] / 1000
    // Mockup (screens frame 12, .radius-bar `padding:8px 16px 0`): the current value sits above a slider
    // flanked by "Terdekat" / "Terjauh" endpoint labels — NOT a left-aligned header + right-aligned value.
    Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, end = 16.dp)) {
        Text(
            text = stringResource(Res.string.radius_value_km, displayKm),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(Res.string.radius_endpoint_nearest),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = sliderPosition,
                onValueChange = { sliderPosition = it },
                onValueChangeFinished = {
                    onSelect(positions[sliderPosition.roundToInt().coerceIn(0, positions.lastIndex)])
                },
                valueRange = 0f..positions.lastIndex.toFloat(),
                steps = positions.size - 2,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp).testTag(NEARBY_RADIUS_SLIDER_TAG),
            )
            Text(
                text = stringResource(Res.string.radius_endpoint_farthest),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
    isLoadingMore: Boolean,
    loadMoreError: Boolean,
    onLoadMore: () -> Unit,
    onRetryLoadMore: () -> Unit,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onSeeGlobal: () -> Unit,
    onOpenPost: (NearbyTimelinePost) -> Unit,
    onToggleLike: (NearbyTimelinePost) -> Unit,
    onReplyShortcut: (NearbyTimelinePost) -> Unit,
    onOpenProfile: (NearbyTimelinePost) -> Unit,
    reportActionOf: (NearbyTimelinePost) -> (() -> Unit)?,
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
        // mobile-admob-ads-foundation — resolves the shared ad controller, runs the once-per-session config
        // fetch + UMP gate, and exposes the placement frequency (null = no ads) + the native-ad slot.
        val timelineAds = rememberTimelineAds()
        when (uiState) {
            NearbyTimelineUiState.Loading ->
                ListLoadingState(
                    message = stringResource(Res.string.timeline_loading),
                    testTag = NEARBY_TIMELINE_LIST_TAG,
                    showSkeleton = true,
                )
            NearbyTimelineUiState.Empty -> NearbyEmptyState(onSeeGlobal = onSeeGlobal)
            NearbyTimelineUiState.HardLimit ->
                ListCenteredMessageState(
                    message = stringResource(Res.string.timeline_limit_hard),
                    testTag = NEARBY_TIMELINE_LIST_TAG,
                )
            NearbyTimelineUiState.Error -> ListErrorState(onRetry = onRetry, testTag = NEARBY_TIMELINE_LIST_TAG)
            // Terminal 401 → neutral redirect placeholder: the redirect copy with NO retry control and
            // NOT signin_error_network (the SessionInvalidator re-route whisks the user to SignInScreen).
            NearbyTimelineUiState.SessionRedirect ->
                ListCenteredMessageState(
                    message = stringResource(Res.string.timeline_session_redirect),
                    testTag = NEARBY_TIMELINE_LIST_TAG,
                )
            is NearbyTimelineUiState.Content ->
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
                    listTag = NEARBY_TIMELINE_LIST_TAG,
                    cardTag = NEARBY_POST_CARD_TAG,
                    adFrequency = timelineAds.frequency,
                    adSlot = { timelineAds.Slot(it) },
                    reportActionOf = reportActionOf,
                )
            is NearbyTimelineUiState.SoftLimit ->
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
                    listTag = NEARBY_TIMELINE_LIST_TAG,
                    cardTag = NEARBY_POST_CARD_TAG,
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
            dialogTestTag = NEARBY_REPORT_DIALOG_TAG,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
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
    ListScrollableState(testTag = NEARBY_TIMELINE_LIST_TAG) {
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
        imageUrl = imageUrl,
    )
