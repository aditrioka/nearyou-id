package id.nearyou.resources.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Shared `runComposeUiTest` assertion bodies for the [LocalNearYouColors] +
 * `ColorScheme.*` extension contract, invoked from BOTH platform test classes:
 *
 *  - iOS: [ColorSchemeExtensionsTest] in `commonTest` (runs on `iosSimulatorArm64Test`).
 *  - Android: `ColorSchemeExtensionsAndroidTest` in `androidUnitTest` under Robolectric.
 *
 * `runComposeUiTest` reads `Build.FINGERPRINT` on Android, which is `null` in a
 * plain JVM unit test → NPE. Robolectric provides a real `Build` so the Android
 * variant works; iOS needs no such shim. The logic lives here once so neither
 * platform copy drifts. (`@RunWith(RobolectricTestRunner)` cannot live in
 * `commonTest` — it is also an iOS/native target — which is why the Android
 * coverage is a separate `androidUnitTest` class rather than the exclude being
 * removed from `commonTest`.)
 */
@OptIn(ExperimentalTestApi::class)
object ColorSchemeExtensionsAssertions {
    fun localNearYouColorsThrowsWhenReadOutsideProvider() =
        runComposeUiTest {
            val error =
                assertFailsWith<IllegalStateException> {
                    setContent {
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

    fun localNearYouColorsResolvesLightWhenProvided() =
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalNearYouColors provides NearYouColors.light) {
                    val resolved = LocalNearYouColors.current
                    assertEquals(NearYouColors.light, resolved)
                }
            }
        }

    fun localNearYouColorsResolvesDarkWhenProvided() =
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalNearYouColors provides NearYouColors.dark) {
                    val resolved = LocalNearYouColors.current
                    assertEquals(NearYouColors.dark, resolved)
                }
            }
        }

    fun locationPinExtensionResolvesViaCompositionLocal() =
        runComposeUiTest {
            setContent {
                MaterialTheme(colorScheme = NearYouColorScheme.light) {
                    CompositionLocalProvider(LocalNearYouColors provides NearYouColors.light) {
                        val pin = MaterialTheme.colorScheme.locationPin
                        assertEquals(NearYouColors.light.locationPin, pin)
                    }
                }
            }
        }

    fun premiumBadgeExtensionResolvesViaCompositionLocal() =
        runComposeUiTest {
            setContent {
                MaterialTheme(colorScheme = NearYouColorScheme.dark) {
                    CompositionLocalProvider(LocalNearYouColors provides NearYouColors.dark) {
                        val badge = MaterialTheme.colorScheme.premiumBadge
                        assertEquals(NearYouColors.dark.premiumBadge, badge)
                    }
                }
            }
        }

    fun extensionsThemeAwareLightVsDark() =
        runComposeUiTest {
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
