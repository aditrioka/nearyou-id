package id.nearyou.resources.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests the [LocalNearYouColors] CompositionLocal + `ColorScheme.*` extension
 * properties contract.
 *
 * The most load-bearing assertion is the **negative test**: accessing
 * `MaterialTheme.colorScheme.locationPin` outside a `NearYouTheme { ... }`
 * (i.e., without a `LocalNearYouColors` provider in the composition tree)
 * MUST throw `IllegalStateException` with the documented error message —
 * per `design.md` Decision 10's contract. A silent default would mask
 * missing-theme bugs at composition sites that legitimately need the brand
 * accents.
 *
 * Uses `runComposeUiTest` from Compose Multiplatform 1.10+ — no JUnit4
 * dependency needed.
 */
@OptIn(ExperimentalTestApi::class)
class ColorSchemeExtensionsTest {

    @Test
    fun localNearYouColors_throwsWhenReadOutsideProvider() = runComposeUiTest {
        val error = assertFailsWith<IllegalStateException> {
            setContent {
                // No CompositionLocalProvider for LocalNearYouColors here →
                // reading .current invokes the default factory which throws.
                @Suppress("UNUSED_EXPRESSION")
                LocalNearYouColors.current
            }
            waitForIdle()
        }
        assertTrue(
            error.message?.contains("NearYouTheme not applied") == true,
            "Expected error message to mention 'NearYouTheme not applied', got: ${error.message}",
        )
    }

    @Test
    fun localNearYouColors_resolvesLightWhenProvided() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalNearYouColors provides NearYouColors.light) {
                val resolved = LocalNearYouColors.current
                assertEquals(NearYouColors.light, resolved)
            }
        }
    }

    @Test
    fun localNearYouColors_resolvesDarkWhenProvided() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalNearYouColors provides NearYouColors.dark) {
                val resolved = LocalNearYouColors.current
                assertEquals(NearYouColors.dark, resolved)
            }
        }
    }

    @Test
    fun colorScheme_locationPinExtension_resolvesViaCompositionLocal() = runComposeUiTest {
        setContent {
            MaterialTheme(colorScheme = NearYouColorScheme.light) {
                CompositionLocalProvider(LocalNearYouColors provides NearYouColors.light) {
                    // Reading the extension property MUST resolve via LocalNearYouColors.current,
                    // not produce a stale or fabricated default.
                    val pin = MaterialTheme.colorScheme.locationPin
                    assertEquals(NearYouColors.light.locationPin, pin)
                }
            }
        }
    }

    @Test
    fun colorScheme_premiumBadgeExtension_resolvesViaCompositionLocal() = runComposeUiTest {
        setContent {
            MaterialTheme(colorScheme = NearYouColorScheme.dark) {
                CompositionLocalProvider(LocalNearYouColors provides NearYouColors.dark) {
                    val badge = MaterialTheme.colorScheme.premiumBadge
                    assertEquals(NearYouColors.dark.premiumBadge, badge)
                }
            }
        }
    }

    @Test
    fun colorScheme_extensions_themeAwareLightVsDark() = runComposeUiTest {
        // Verifies the same extension property resolves to different values in
        // light vs dark scope (the whole point of the CompositionLocal wiring).
        setContent {
            CompositionLocalProvider(LocalNearYouColors provides NearYouColors.light) {
                val lightSuccess = MaterialTheme.colorScheme.success
                assertEquals(NearYouColors.light.success, lightSuccess)
            }
            CompositionLocalProvider(LocalNearYouColors provides NearYouColors.dark) {
                val darkSuccess = MaterialTheme.colorScheme.success
                assertEquals(NearYouColors.dark.success, darkSuccess)
            }
        }
    }
}
