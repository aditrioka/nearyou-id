package id.nearyou.resources.theme

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test

/**
 * Android-side runner for the [LocalNearYouColors] + `ColorScheme.*` extension
 * contract, closing the prior asymmetry where these `runComposeUiTest` cases ran
 * only on iOS sim. Robolectric supplies a real `Build` (so `runComposeUiTest`'s
 * `Build.FINGERPRINT` read does not NPE on the JVM lane — the crash the
 * `commonTest` variant is excluded for). Assertion bodies are shared verbatim
 * via [ColorSchemeExtensionsAssertions]; this class only supplies the Robolectric
 * runner that `commonTest` cannot declare.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ColorSchemeExtensionsAndroidTest {
    @Test
    fun localNearYouColors_throwsWhenReadOutsideProvider() =
        ColorSchemeExtensionsAssertions.localNearYouColorsThrowsWhenReadOutsideProvider()

    @Test
    fun localNearYouColors_resolvesLightWhenProvided() = ColorSchemeExtensionsAssertions.localNearYouColorsResolvesLightWhenProvided()

    @Test
    fun localNearYouColors_resolvesDarkWhenProvided() = ColorSchemeExtensionsAssertions.localNearYouColorsResolvesDarkWhenProvided()

    @Test
    fun colorScheme_locationPinExtension_resolvesViaCompositionLocal() =
        ColorSchemeExtensionsAssertions.locationPinExtensionResolvesViaCompositionLocal()

    @Test
    fun colorScheme_premiumBadgeExtension_resolvesViaCompositionLocal() =
        ColorSchemeExtensionsAssertions.premiumBadgeExtensionResolvesViaCompositionLocal()

    @Test
    fun colorScheme_extensions_themeAwareLightVsDark() = ColorSchemeExtensionsAssertions.extensionsThemeAwareLightVsDark()
}
