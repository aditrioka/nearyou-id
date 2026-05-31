package id.nearyou.app

import id.nearyou.app.screens.home.HomeScreen
import kotlin.test.Test
import kotlin.test.assertTrue

class HomeScreenTest {
    @Test
    fun homeScreen_canBeInstantiated() {
        // Smoke check: the Voyager Screen subclass instantiates without throwing.
        // Composing the @Composable Content() body requires a Compose UI test runner
        // (deferred to follow-up `mobile-theme-light-dark-direct-test` per Mobile #1
        // design Decision 7).
        val screen = HomeScreen()
        assertTrue(screen.key.isNotEmpty(), "Voyager Screen should derive a non-empty key")
    }

    @Test
    fun homeScreen_canBeInstantiatedTwice() {
        // Activity-recreation + Compose-preview double-init safety: two sequential
        // instantiations must not interfere (each Screen has its own key).
        val first = HomeScreen()
        val second = HomeScreen()
        assertTrue(first.key.isNotEmpty())
        assertTrue(second.key.isNotEmpty())
    }

    // NOTE: As of `mobile-nearby-timeline-screen` (Mobile #5), `HomeScreen` is repurposed from the
    // wizard placeholder to a thin host that delegates to `NearbyTimelineScreen`; it no longer renders
    // `home_placeholder_title` / `home_placeholder_version` (those strings are retained in the catalog
    // but unreferenced by `HomeScreen`). The render-level host-delegation assertion (composes
    // `HomeScreen().Content()` → `timeline_nearby_title` present, `home_placeholder_title` absent) lives
    // in `NearbyTimelineScreenTest.homeScreen_hostsNearbyTimeline_notThePlaceholder` (Robolectric CMP UI
    // runner). These two instantiation smoke checks remain valid (the Voyager `Screen` subclass still
    // instantiates with a stable key).
}
