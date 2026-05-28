package id.nearyou.app.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalFontFamilyResolver
import id.nearyou.resources.theme.LocalNearYouColors
import id.nearyou.resources.theme.NearYouColorScheme
import id.nearyou.resources.theme.NearYouColors
import id.nearyou.resources.theme.brandFontFamily
import id.nearyou.resources.theme.nearYouTypography

/**
 * `NearYouTheme` — brand theme root for `:mobile:app`.
 *
 * Font load resilience: uses the canonical JetBrains CMP pattern of
 * `FontFamilyResolver.preload(...)` inside a `LaunchedEffect`. `preload()` is
 * suspend, so `try`/`catch` is legal in its coroutine scope (unlike inside an
 * `@Composable` where Compose's compiler invariant forbids exception-catching
 * around composable calls). Failure path: state flips to `brandFontFailed`,
 * recomposition uses vanilla `Typography()` (platform sans-serif), text still
 * renders. Successful preload path: state flips to `brandFontReady`,
 * recomposition uses `nearYouTypography(brandFamily)` with Plus Jakarta Sans.
 * During the brief preload window (very short on Android + iOS — bundled .ttf
 * loads synchronously from the framework binary), the initial render uses
 * vanilla `Typography()` to avoid blocking the first frame.
 *
 * Documented JetBrains pattern per [CMP Resources usage docs](https://kotlinlang.org/docs/multiplatform/compose-multiplatform-resources-usage.html)
 * + community precedent in JetBrains issues [#4111](https://github.com/JetBrains/compose-multiplatform/issues/4111),
 * [#3472](https://github.com/JetBrains/compose-multiplatform/issues/3472),
 * [#4387](https://github.com/JetBrains/compose-multiplatform/issues/4387)
 * — real runtime `MissingResourceException` / `IllegalStateException` from
 * `FontFamilyResolver` IS a documented failure mode for bundled-font CMP
 * Resources, NOT eliminated by build-time codegen validation.
 */
@Composable
fun NearYouTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) NearYouColorScheme.dark else NearYouColorScheme.light
    val nearYouColors = if (darkTheme) NearYouColors.dark else NearYouColors.light

    val resolver = LocalFontFamilyResolver.current
    val brandFamily = brandFontFamily()
    var brandFontReady by remember { mutableStateOf(false) }
    var brandFontFailed by remember { mutableStateOf(false) }
    LaunchedEffect(brandFamily) {
        try {
            resolver.preload(brandFamily)
            brandFontReady = true
        } catch (_: Throwable) {
            brandFontFailed = true
        }
    }

    val typography: Typography =
        if (brandFontReady && !brandFontFailed) {
            nearYouTypography(brandFamily)
        } else {
            Typography()
        }

    CompositionLocalProvider(LocalNearYouColors provides nearYouColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            content = content,
        )
    }
}
