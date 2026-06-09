package id.nearyou.app.screens.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import id.nearyou.app.screens.timeline.FollowingPlaceholderScreen
import id.nearyou.app.screens.timeline.GlobalTimelinePost
import id.nearyou.app.screens.timeline.GlobalTimelineScreen
import id.nearyou.app.screens.timeline.NearbyTimelinePost
import id.nearyou.app.screens.timeline.NearbyTimelineScreen
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.cta_post
import id.nearyou.resources.generated.resources.ic_action_compose
import id.nearyou.resources.generated.resources.tab_following
import id.nearyou.resources.generated.resources.tab_global
import id.nearyou.resources.generated.resources.tab_nearby
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.material3.Tab as Material3Tab

/** Test tag on the feed `HorizontalPager` — lets the screen test target a horizontal swipe gesture. */
const val HOME_FEED_PAGER_TAG: String = "homeFeedPager"

/**
 * The **Home section's content** (`mobile-home-tab-host`) — the Nearby/Following/Global feed tab host,
 * hosted by the app section shell ([AppShellScreen][id.nearyou.app.screens.shell.AppShellScreen]) as the
 * Home section. The body is a Material 3 `PrimaryTabRow` of the three **text-only** feed tabs over a
 * **swipeable [HorizontalPager]** of the three feed pages (design D2/D10): Nearby → [NearbyTimelineScreen],
 * Following → [FollowingPlaceholderScreen], Global → [GlobalTimelineScreen]. The tabs carry NO icon and NO
 * brand dot — just the `stringResource` label under the M3 underline indicator (matching the operator's
 * X / Niche-style text-tab references); the selected/unselected label colors are the `Tab` defaults
 * (selected = primary, unselected = `onSurfaceVariant`), always visible (D5).
 *
 * `HomeScreen` declares NO `Scaffold` of its own (design D1): the app section shell owns the single
 * inset-owning `Scaffold`, so `HomeScreen` renders the tab row + pager + FAB under the shell's already-
 * consumed padding. The composer FAB is an **icon-only** `FloatingActionButton` (the `+` glyph, no
 * visible label; `contentDescription = cta_post`) rendered in this inset-free body's bottom-end overlay
 * (`Box`), NOT in a nested `Scaffold` — and because it is part of [HomeScreen] it shows only on the Home
 * section (never Notifikasi / Profil).
 *
 * Pager↔tab-row sync (design D2): `PrimaryTabRow(selectedTabIndex = pagerState.currentPage)`; tapping a
 * tab animates the pager to that page (`animateScrollToPage`); a settled swipe writes the page back into
 * the `@Serializable` [Tab] held in `rememberSaveable` — the **durable selection** (iOS-safe; default
 * [Tab.Nearby]). There is **no** per-tab `NavDisplay` and **no** new tab-root `NavKey`: each feed page
 * composes directly under the shell's `HomeRoute` `NavEntry`, so its `viewModel { }` resolves to the
 * `HomeRoute` store and the feed state survives feed swipes/tab taps, section switches, AND the composer
 * round-trip with no re-fetch (design D1/D2/D3). Per-tab back stacks are deferred (follow-up issue
 * #189, `mobile-home-tab-host-per-tab-backstacks`).
 *
 * Two callbacks are hoisted (both wired by the shell + `appEntryProvider` call site to root-stack pushes):
 * - [onOpenComposer] — the single composer FAB shared across all three feed pages → `add(PostCreationRoute)`.
 * - [onOpenPost] — the feed card-tap callback (`mobile-post-detail-screen`, #159): the Nearby + Global pages
 *   invoke it with the tapped card's PII-free fields (as a [PostDetailTarget]) → `add(PostDetailRoute(...))`.
 *   The Following page (a deferred placeholder with no cards) wires no `onOpenPost`.
 *
 * The Nearby page's empty-state "lihat Global" CTA animates the pager to the Global page (host-level
 * state, not a back-stack reference — `NearbyTimelineScreen` stays navigation-free).
 */
@Composable
fun HomeScreen(
    onOpenComposer: () -> Unit,
    onOpenPost: (PostDetailTarget) -> Unit = {},
) {
    // The durable selection (iOS-safe @Serializable enum) — kept in sync with the settled pager page.
    var selectedTab by rememberSaveable { mutableStateOf(Tab.Nearby) }
    val pagerState = rememberPagerState(initialPage = selectedTab.ordinal) { Tab.entries.size }
    val scope = rememberCoroutineScope()

    // Settled-swipe write-back: once a swipe (or a tab-tap animation) settles, persist the page into the
    // durable @Serializable Tab (design D2). The PrimaryTabRow + each `selected` follow pagerState.currentPage.
    LaunchedEffect(pagerState.settledPage) {
        selectedTab = Tab.entries[pagerState.settledPage]
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = pagerState.currentPage) {
                HomeFeedTab(
                    selected = pagerState.currentPage == Tab.Nearby.ordinal,
                    onSelect = { scope.launch { pagerState.animateScrollToPage(Tab.Nearby.ordinal) } },
                    label = stringResource(Res.string.tab_nearby),
                )
                HomeFeedTab(
                    selected = pagerState.currentPage == Tab.Following.ordinal,
                    onSelect = { scope.launch { pagerState.animateScrollToPage(Tab.Following.ordinal) } },
                    label = stringResource(Res.string.tab_following),
                )
                HomeFeedTab(
                    selected = pagerState.currentPage == Tab.Global.ordinal,
                    onSelect = { scope.launch { pagerState.animateScrollToPage(Tab.Global.ordinal) } },
                    label = stringResource(Res.string.tab_global),
                )
            }
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize().testTag(HOME_FEED_PAGER_TAG),
            ) { page ->
                // Each feed page composes DIRECTLY under the shell's HomeRoute NavEntry (no per-tab
                // NavDisplay), so each feed's viewModel { } resolves to the HomeRoute store (design D1/D2)
                // — swiping/tapping between feeds OR switching bottom-nav sections and returning does not
                // re-fetch. The Nearby + Global cards hoist onOpenPost (mapped card → PostDetailTarget);
                // Following (a placeholder with no cards) wires none.
                when (Tab.entries[page]) {
                    Tab.Nearby ->
                        NearbyTimelineScreen(
                            onSeeGlobal = { scope.launch { pagerState.animateScrollToPage(Tab.Global.ordinal) } },
                            onOpenPost = { post -> onOpenPost(post.toTarget()) },
                        )
                    Tab.Following -> FollowingPlaceholderScreen()
                    Tab.Global -> GlobalTimelineScreen(onOpenPost = { post -> onOpenPost(post.toTarget()) })
                }
            }
        }
        // Icon-only composer FAB (design D10 / mobile-post-creation): the `+` glyph, NO visible text label
        // (a FloatingActionButton, not an ExtendedFloatingActionButton), `contentDescription = cta_post`.
        // Rendered in this inset-free body's bottom-end overlay (NOT a nested Scaffold).
        FloatingActionButton(
            onClick = onOpenComposer,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) {
            Icon(
                painter = painterResource(Res.drawable.ic_action_compose),
                contentDescription = stringResource(Res.string.cta_post),
            )
        }
    }
}

/**
 * One top feed-tab — **text-only** (design D10): just the `stringResource` label under the
 * `PrimaryTabRow` underline indicator, NO icon and NO brand dot (mobile-design-system § "Feed tabs are
 * text-only with an underline indicator"). The label color uses the `Tab` defaults (selected = primary,
 * unselected = `onSurfaceVariant`), always visible in both states (D5).
 */
@Composable
private fun HomeFeedTab(
    selected: Boolean,
    onSelect: () -> Unit,
    label: String,
) {
    Material3Tab(
        selected = selected,
        onClick = onSelect,
        text = { Text(text = label) },
    )
}

/**
 * The non-PII display fields a tapped feed card carries up to the tab host's hoisted [HomeScreen]
 * `onOpenPost`, which the `appEntryProvider` call site (via
 * [AppShellScreen][id.nearyou.app.screens.shell.AppShellScreen]) turns into a root-stack `PostDetailRoute`
 * push. Deliberately distinct from `PostDetailRoute` (a routing-layer `NavKey`) so the feed screens + the
 * tab host stay free of the routing types — mirroring how the cards already carry PII-free
 * [NearbyTimelinePost] / [GlobalTimelinePost] rather than the wire DTOs. Carries NO `latitude`/`longitude`:
 * raw coordinates must never reach the serialized back stack. [distanceM] is `null` for a Global card (no
 * spatial filter).
 */
data class PostDetailTarget(
    val postId: String,
    val content: String,
    val cityName: String,
    val distanceM: Double?,
    val createdAtIso: String,
    val likedByViewer: Boolean,
    val replyCount: Int,
)

private fun NearbyTimelinePost.toTarget(): PostDetailTarget =
    PostDetailTarget(
        postId = id,
        content = content,
        cityName = cityName,
        distanceM = distanceM,
        createdAtIso = createdAt,
        likedByViewer = likedByViewer,
        replyCount = replyCount,
    )

private fun GlobalTimelinePost.toTarget(): PostDetailTarget =
    PostDetailTarget(
        postId = id,
        content = content,
        cityName = cityName,
        // Global has no spatial filter → no distance on the detail header.
        distanceM = null,
        createdAtIso = createdAt,
        likedByViewer = likedByViewer,
        replyCount = replyCount,
    )
