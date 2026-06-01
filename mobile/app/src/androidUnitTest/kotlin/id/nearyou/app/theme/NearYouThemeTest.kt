package id.nearyou.app.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import id.nearyou.resources.theme.NearYouColorScheme
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Theme-resolution coverage for [NearYouTheme] — the `mobile-app-scaffold` spec
 * Requirement "Material 3 theme follows system preference" scenarios "Light mode
 * applies brand light color scheme" + "Dark mode applies brand dark color
 * scheme". Closes the `mobile-theme-light-dark-direct-test` FOLLOW_UPS entry,
 * which deferred these from manual visual smoke to an automated assertion once
 * the Compose UI test runner was wired (it is, since Mobile #3).
 *
 * `NearYouTheme` takes an explicit `darkTheme: Boolean` param (defaulting to
 * `isSystemInDarkTheme()`), so each mode is forced deterministically — no
 * `@Config(qualifiers = "night")` night-mode gymnastics. The captured
 * `MaterialTheme.colorScheme` is asserted to be the brand scheme from
 * `:shared:resources` (NOT a vanilla Material 3 `lightColorScheme()` /
 * `darkColorScheme()`), per the spec's explicit `primary` values.
 *
 * Robolectric's `runComposeUiTest` host activity is debug-only, so this class is
 * excluded from the Release variant in `build.gradle.kts` (alongside the other
 * `*ScreenTest` classes).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalTestApi::class)
class NearYouThemeTest {
    @Test
    fun lightMode_resolvesBrandLightColorScheme() {
        var captured: ColorScheme? = null
        runComposeUiTest {
            setContent {
                NearYouTheme(darkTheme = false) { captured = MaterialTheme.colorScheme }
            }
            waitForIdle()
        }

        val scheme = requireNotNull(captured) { "colorScheme was never captured" }
        // Brand light scheme from :shared:resources (NOT vanilla Material 3).
        assertEquals(NearYouColorScheme.light.primary, scheme.primary)
        // Spec's explicit light primary value.
        assertEquals(Color(0xFF1E4FD6), scheme.primary)
    }

    @Test
    fun darkMode_resolvesBrandDarkColorScheme() {
        var captured: ColorScheme? = null
        runComposeUiTest {
            setContent {
                NearYouTheme(darkTheme = true) { captured = MaterialTheme.colorScheme }
            }
            waitForIdle()
        }

        val scheme = requireNotNull(captured) { "colorScheme was never captured" }
        // Brand dark scheme from :shared:resources (NOT vanilla Material 3).
        assertEquals(NearYouColorScheme.dark.primary, scheme.primary)
        // Spec's explicit dark primary value (mechanically-derived per
        // shared-resources-moko-bootstrap design Decision 3).
        assertEquals(Color(0xFFB3C5FF), scheme.primary)
        // Sanity: the two modes genuinely resolve different schemes (the
        // `darkTheme` param is actually switching, not a no-op).
        assertNotEquals(NearYouColorScheme.light.primary, scheme.primary)
    }
}
