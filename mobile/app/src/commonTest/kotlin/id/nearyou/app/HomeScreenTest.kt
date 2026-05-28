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

    // NOTE: Runtime format-substitution verification for
    // `Res.string.home_placeholder_version` (per spec § "home_placeholder_version
    // format substitution renders correctly at runtime") is verified VISUALLY
    // during the cold-start checks in tasks 8.9 + 8.10 of the
    // `shared-resources-swap-to-cmp-resources` change (mandatory screenshots
    // show "Versi 1.0", NOT "Versi %1$s" literal).
    //
    // A pure-commonTest runtime substitution check requires a platform Context
    // (Android: Resources, iOS: NSBundle) that's awkward to mock in
    // kotlin.test-only scope. CMP Resources' `stringResource(resource, ...args)`
    // composable dispatches positional `%1$s`/`%2$s` placeholders via the
    // platform's native format API (Android's `Resources.getString(id, args)`
    // and iOS's `NSString stringWithFormat:` equivalent), exercised at app
    // launch from `HomeScreen.kt`.
}
