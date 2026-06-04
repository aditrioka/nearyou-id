package id.nearyou.app.screens.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import id.nearyou.app.screens.post.PostCreationScreen
import id.nearyou.app.screens.timeline.NearbyTimelineScreen
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.cta_post
import org.jetbrains.compose.resources.stringResource

/**
 * Home host. A thin host whose body renders [NearbyTimelineScreen] — the first product surface
 * (mobile-nearby-timeline-screen) — wrapped in a `Scaffold` that adds the post-composer FAB
 * (mobile-post-creation-screen). A future tab-bar change makes this the Nearby/Following/Global host
 * (aligned with `docs/02-Product.md` "Nearby and Following are home"); the FAB belongs at this
 * home level because it is conceptually one composer affordance across all three future tabs (design
 * D6), which is why it is hosted here and NOT inside `NearbyTimelineScreen` (kept delta-free, so the
 * `mobile-nearby-timeline` capability gains no change).
 *
 * `RootRouterScreen` still routes the authenticated path to `HomeScreen`, so the `mobile-auth-signin`
 * § "RootRouterScreen routes based on token presence" requirement is UNCHANGED.
 *
 * The FAB navigates via [LocalNavigator.current] (nullable): in production `HomeScreen` is always
 * composed within `App`'s `Navigator`, so the push resolves; the nullable form keeps the existing
 * bare-composition host-delegation test (`NearbyTimelineScreenTest.homeScreen_hostsNearbyTimeline…`,
 * which composes `HomeScreen().Content()` without a navigator) rendering without a throw.
 */
class HomeScreen : Screen {
    @Composable
    override fun Content() {
        val nearby = remember { NearbyTimelineScreen() }
        val navigator = LocalNavigator.current
        Scaffold(
            floatingActionButton = {
                ExtendedFloatingActionButton(onClick = { navigator?.push(PostCreationScreen()) }) {
                    Text(text = stringResource(Res.string.cta_post))
                }
            },
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                nearby.Content()
            }
        }
    }
}
