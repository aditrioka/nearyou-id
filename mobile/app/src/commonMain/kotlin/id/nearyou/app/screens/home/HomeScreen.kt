package id.nearyou.app.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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
import id.nearyou.app.screens.timeline.GlobalTimelineScreen
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

/**
 * Home host ([HomeRoute][id.nearyou.app.screens.routing.HomeRoute]) — the **Nearby/Following/Global
 * tab host** (`mobile-home-tab-host`). A `Scaffold` whose bottom bar is a Material 3 `NavigationBar`
 * (three destinations) + the home-level composer FAB, and whose body renders the selected tab's
 * screen **directly** via `when(selectedTab)` (design D1): Nearby → [NearbyTimelineScreen], Following
 * → [FollowingPlaceholderScreen], Global → [GlobalTimelineScreen].
 *
 * `selectedTab` is a `@Serializable` [Tab] enum in `rememberSaveable` (iOS-safe; default [Tab.Nearby]
 * — `design.md` D5). There is **no** per-tab `NavDisplay` and **no** new tab-root `NavKey`: each tab
 * screen composes directly under the `HomeRoute` `NavEntry`, so its `viewModel { }` resolves to the
 * `HomeRoute` store and the feed state survives tab switches + the composer round-trip with no
 * re-fetch (design D1/D2). Per-tab back stacks are deferred (`FOLLOW_UPS.md`
 * `mobile-home-tab-host-per-tab-backstacks`).
 *
 * The FAB lives at this home level — **one** composer affordance shared across all three tabs (design
 * D3/D6) — and invokes [onOpenComposer], wired by
 * [appEntryProvider][id.nearyou.app.screens.routing.appEntryProvider] to
 * `backStack.add(PostCreationRoute)` on the **root** back stack (above `HomeRoute`), so the composer
 * overlays the entire surface including the tab bar. `RootRouterScreen` still routes the authenticated
 * path to `HomeRoute`, so the `mobile-auth-signin` routing **target** is UNCHANGED.
 *
 * The Nearby tab's empty-state "lihat Global" CTA is wired to select the Global tab (host-level tab
 * state, not a back-stack reference — `NearbyTimelineScreen` stays navigation-free).
 */
@Composable
fun HomeScreen(onOpenComposer: () -> Unit) {
    var selectedTab by rememberSaveable { mutableStateOf(Tab.Nearby) }
    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onOpenComposer) {
                Text(text = stringResource(Res.string.cta_post))
            }
        },
        bottomBar = {
            NavigationBar {
                HomeTabItem(
                    selected = selectedTab == Tab.Nearby,
                    onSelect = { selectedTab = Tab.Nearby },
                    label = stringResource(Res.string.tab_nearby),
                    iconDescription = stringResource(Res.string.tab_nearby_icon_description),
                )
                HomeTabItem(
                    selected = selectedTab == Tab.Following,
                    onSelect = { selectedTab = Tab.Following },
                    label = stringResource(Res.string.tab_following),
                    iconDescription = stringResource(Res.string.tab_following_icon_description),
                )
                HomeTabItem(
                    selected = selectedTab == Tab.Global,
                    onSelect = { selectedTab = Tab.Global },
                    label = stringResource(Res.string.tab_global),
                    iconDescription = stringResource(Res.string.tab_global_icon_description),
                )
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (selectedTab) {
                // Each tab screen composes DIRECTLY under HomeRoute (no per-tab NavDisplay), so each
                // feed's viewModel { } resolves to the HomeRoute store (design D1/D2) — switching away
                // and back does not re-fetch.
                Tab.Nearby -> NearbyTimelineScreen(onSeeGlobal = { selectedTab = Tab.Global })
                Tab.Following -> FollowingPlaceholderScreen()
                Tab.Global -> GlobalTimelineScreen()
            }
        }
    }
}

/**
 * One bottom-nav destination. The icon is a brand-tinted dot (coral [locationPin] when selected, muted
 * otherwise) — there is no material-icons dependency on the classpath, matching the post-card
 * affordance idiom — carrying its `contentDescription` via `stringResource`. The label is always shown
 * (it carries the destination's meaning). A `RowScope` extension because `NavigationBarItem` is one.
 */
@Composable
private fun RowScope.HomeTabItem(
    selected: Boolean,
    onSelect: () -> Unit,
    label: String,
    iconDescription: String,
) {
    NavigationBarItem(
        selected = selected,
        onClick = onSelect,
        icon = {
            Box(
                modifier =
                    Modifier
                        .size(24.dp)
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
        label = { Text(text = label) },
    )
}
