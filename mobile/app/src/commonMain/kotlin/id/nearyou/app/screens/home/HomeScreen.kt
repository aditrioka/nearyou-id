package id.nearyou.app.screens.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import cafe.adriel.voyager.core.screen.Screen
import id.nearyou.app.screens.timeline.NearbyTimelineScreen

/**
 * Home host. Repurposed from the Mobile #1 wizard placeholder (logo + title + version) to a thin
 * host whose `Content()` renders [NearbyTimelineScreen] — the first product surface
 * (mobile-nearby-timeline-screen). A future tab-bar change makes this the Nearby/Following/Global
 * host (aligned with `docs/02-Product.md` "Nearby and Following are home").
 *
 * `RootRouterScreen` still routes the authenticated path to `HomeScreen`, so the `mobile-auth-signin`
 * § "RootRouterScreen routes based on token presence" requirement is UNCHANGED (zero cross-spec churn).
 * The `home_placeholder_title` / `home_placeholder_version` strings are no longer rendered (retained
 * in the catalog, consistent with `signin_error_no_account`'s retention). [NearbyTimelineScreen] holds
 * no navigation dependency, so it is embedded directly here.
 */
class HomeScreen : Screen {
    @Composable
    override fun Content() {
        val nearby = remember { NearbyTimelineScreen() }
        nearby.Content()
    }
}
