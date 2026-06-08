package id.nearyou.app.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import id.nearyou.app.screens.timeline.FollowingPlaceholderScreen
import id.nearyou.app.screens.timeline.GlobalTimelinePost
import id.nearyou.app.screens.timeline.GlobalTimelineScreen
import id.nearyou.app.screens.timeline.NearbyTimelinePost
import id.nearyou.app.screens.timeline.NearbyTimelineScreen
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.cta_post
import id.nearyou.resources.generated.resources.tab_following
import id.nearyou.resources.generated.resources.tab_following_icon_description
import id.nearyou.resources.generated.resources.tab_global
import id.nearyou.resources.generated.resources.tab_global_icon_description
import id.nearyou.resources.generated.resources.tab_nearby
import id.nearyou.resources.generated.resources.tab_nearby_icon_description
import id.nearyou.resources.theme.locationPin
import org.jetbrains.compose.resources.stringResource
import androidx.compose.material3.Tab as Material3Tab

/**
 * The **Home section's content** (`mobile-home-tab-host`) — the Nearby/Following/Global feed tab host,
 * now hosted by the app section shell ([AppShellScreen][id.nearyou.app.screens.shell.AppShellScreen]) as
 * the Home section. A `Scaffold` whose body is a Material 3 `PrimaryTabRow` of the three feed tabs over
 * the selected tab's screen, rendered **directly** via `when(selectedTab)` (design D1): Nearby →
 * [NearbyTimelineScreen], Following → [FollowingPlaceholderScreen], Global → [GlobalTimelineScreen]. The
 * bottom `NavigationBar` is **gone** — the bottom bar is now the section shell (Home / Notifikasi /
 * Profil); the feeds are a top tab row inside Home (design D3).
 *
 * `selectedTab` is a `@Serializable` [Tab] enum in `rememberSaveable` (iOS-safe; default [Tab.Nearby] —
 * `design.md` D5). There is **no** per-tab `NavDisplay` and **no** new tab-root `NavKey`: each tab screen
 * composes directly under the shell's `HomeRoute` `NavEntry`, so its `viewModel { }` resolves to the
 * `HomeRoute` store and the feed state survives feed-tab switches, section switches, AND the composer
 * round-trip with no re-fetch (design D1/D2/D3). Per-tab back stacks are deferred (`FOLLOW_UPS.md`
 * `mobile-home-tab-host-per-tab-backstacks`).
 *
 * Two callbacks are hoisted (both wired by the shell + `appEntryProvider` call site to root-stack pushes,
 * above the shell, since neither this screen nor the shell holds a back-stack reference):
 * - [onOpenComposer] — the single composer FAB shared across all three feed tabs (Home-section level), →
 *   `add(PostCreationRoute)`. Because the FAB is part of [HomeScreen], it shows only on the Home section.
 * - [onOpenPost] — the feed card-tap callback (`mobile-post-detail-screen`, #159): the Nearby + Global tab
 *   content invoke it with the tapped card's PII-free fields (as a [PostDetailTarget]) → `add(PostDetailRoute(...))`.
 *   The Following tab (a deferred placeholder with no cards) wires no `onOpenPost`. Absorbed through the
 *   shell per design D9 (the `appEntryProvider` call site moved from `HomeScreen` to `AppShellScreen`,
 *   which forwards `onOpenPost` to this Home-section `HomeScreen`).
 *
 * The Nearby tab's empty-state "lihat Global" CTA is wired to select the Global tab (host-level tab state,
 * not a back-stack reference — `NearbyTimelineScreen` stays navigation-free).
 */
@Composable
fun HomeScreen(
    onOpenComposer: () -> Unit,
    onOpenPost: (PostDetailTarget) -> Unit = {},
) {
    var selectedTab by rememberSaveable { mutableStateOf(Tab.Nearby) }
    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onOpenComposer) {
                Text(text = stringResource(Res.string.cta_post))
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(selectedTabIndex = selectedTab.ordinal) {
                HomeFeedTab(
                    selected = selectedTab == Tab.Nearby,
                    onSelect = { selectedTab = Tab.Nearby },
                    label = stringResource(Res.string.tab_nearby),
                    iconDescription = stringResource(Res.string.tab_nearby_icon_description),
                )
                HomeFeedTab(
                    selected = selectedTab == Tab.Following,
                    onSelect = { selectedTab = Tab.Following },
                    label = stringResource(Res.string.tab_following),
                    iconDescription = stringResource(Res.string.tab_following_icon_description),
                )
                HomeFeedTab(
                    selected = selectedTab == Tab.Global,
                    onSelect = { selectedTab = Tab.Global },
                    label = stringResource(Res.string.tab_global),
                    iconDescription = stringResource(Res.string.tab_global_icon_description),
                )
            }
            Box(modifier = Modifier.fillMaxSize()) {
                when (selectedTab) {
                    // Each tab screen composes DIRECTLY under the shell's HomeRoute NavEntry (no per-tab
                    // NavDisplay), so each feed's viewModel { } resolves to the HomeRoute store (design
                    // D1/D2) — switching feed tabs OR bottom-nav sections and returning does not re-fetch.
                    // The Nearby + Global cards hoist onOpenPost (mapped card → PostDetailTarget); Following
                    // (a placeholder with no cards) wires none.
                    Tab.Nearby ->
                        NearbyTimelineScreen(
                            onSeeGlobal = { selectedTab = Tab.Global },
                            onOpenPost = { post -> onOpenPost(post.toTarget()) },
                        )
                    Tab.Following -> FollowingPlaceholderScreen()
                    Tab.Global -> GlobalTimelineScreen(onOpenPost = { post -> onOpenPost(post.toTarget()) })
                }
            }
        }
    }
}

/**
 * One top feed-tab. The icon is a brand-tinted dot (coral [locationPin] when selected, muted otherwise) —
 * there is no material-icons dependency on the classpath, matching the post-card affordance idiom —
 * carrying its `contentDescription` via `stringResource`. The label is always shown (it carries the feed's
 * meaning).
 */
@Composable
private fun HomeFeedTab(
    selected: Boolean,
    onSelect: () -> Unit,
    label: String,
    iconDescription: String,
) {
    Material3Tab(
        selected = selected,
        onClick = onSelect,
        text = { Text(text = label) },
        icon = {
            Box(
                modifier =
                    Modifier
                        .size(12.dp)
                        .semantics { contentDescription = iconDescription }
                        .background(
                            color =
                                if (selected) {
                                    MaterialTheme.colorScheme.locationPin
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            shape = CircleShape,
                        ),
            )
        },
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
