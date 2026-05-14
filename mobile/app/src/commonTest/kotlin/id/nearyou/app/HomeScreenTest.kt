package id.nearyou.app

import id.nearyou.app.screens.home.HomeScreen
import kotlin.test.Test
import kotlin.test.assertTrue

class HomeScreenTest {
    @Test
    fun homeScreen_canBeInstantiated() {
        // Smoke check: the Voyager Screen subclass instantiates without throwing.
        // Composing the @Composable Content() body requires a Compose UI test runner
        // (deferred to follow-up `mobile-theme-light-dark-direct-test` per design
        // Decision 7); for kotlin.test-only scope the instantiation check is the
        // tightest assertion we can make without that runner.
        val screen = HomeScreen()
        assertTrue(screen.key.isNotEmpty(), "Voyager Screen should derive a non-empty key")
    }

    @Test
    fun homeScreen_canBeInstantiatedTwice() {
        // Activity-recreation + Compose-preview double-init safety: two sequential
        // instantiations must not interfere (each Screen has its own key).
        val first = HomeScreen()
        val second = HomeScreen()
        // Each Voyager Screen instance has its own key by default; both should be
        // non-empty (we accept that the keys may or may not be equal depending on
        // Voyager's key-derivation strategy — the assertion is just that the second
        // instantiation does not throw).
        assertTrue(first.key.isNotEmpty())
        assertTrue(second.key.isNotEmpty())
    }
}
