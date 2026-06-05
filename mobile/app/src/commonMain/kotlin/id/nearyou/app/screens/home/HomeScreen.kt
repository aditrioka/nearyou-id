package id.nearyou.app.screens.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import id.nearyou.app.screens.timeline.NearbyTimelineScreen
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.cta_post
import org.jetbrains.compose.resources.stringResource

/**
 * Home host ([HomeRoute][id.nearyou.app.screens.routing.HomeRoute]). A thin host whose body renders
 * [NearbyTimelineScreen] — the first product surface (mobile-nearby-timeline-screen) — wrapped in a
 * `Scaffold` that adds the post-composer FAB (mobile-post-creation-screen). A future tab-bar change
 * makes this the Nearby/Following/Global host (aligned with `docs/02-Product.md` "Nearby and
 * Following are home"); the FAB belongs at this home level because it is conceptually one composer
 * affordance across all three future tabs (design D6), which is why it is hosted here and NOT inside
 * `NearbyTimelineScreen` (kept navigation-free, so the `mobile-nearby-timeline` capability gains no
 * change).
 *
 * `RootRouterScreen` still routes the authenticated path to `HomeRoute`, so the `mobile-auth-signin`
 * § "RootRouterScreen routes based on token presence" requirement is UNCHANGED.
 *
 * The FAB invokes [onOpenComposer], wired by
 * [appEntryProvider][id.nearyou.app.screens.routing.appEntryProvider] to `backStack.add(PostCreationRoute)`
 * (the Nav3 equivalent of a push). Hoisting the navigation into a lambda keeps `HomeScreen` directly
 * testable with a recording callback (no nav host required).
 */
@Composable
fun HomeScreen(onOpenComposer: () -> Unit) {
    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onOpenComposer) {
                Text(text = stringResource(Res.string.cta_post))
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            NearbyTimelineScreen()
        }
    }
}
