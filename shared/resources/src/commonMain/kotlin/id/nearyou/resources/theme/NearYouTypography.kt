package id.nearyou.resources.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.plus_jakarta_sans
import org.jetbrains.compose.resources.Font

/**
 * Brand `FontFamily` for `NearYouTheme` — Plus Jakarta Sans variable font
 * (OFL-licensed, Tokotype, designed for Pemprov DKI Jakarta) bundled via
 * Compose Multiplatform Resources at `composeResources/font/plus_jakarta_sans.ttf`.
 *
 * Exposed as a separate `@Composable` so the consumer (`NearYouTheme`) can pass it
 * to `FontFamilyResolver.preload(...)` inside a `LaunchedEffect` for canonical
 * defensive font loading per [JetBrains CMP Resources usage docs](https://kotlinlang.org/docs/multiplatform/compose-multiplatform-resources-usage.html)
 * + the documented JetBrains pattern for handling font-load failures
 * (issues [#4111](https://github.com/JetBrains/compose-multiplatform/issues/4111),
 * [#3472](https://github.com/JetBrains/compose-multiplatform/issues/3472),
 * [#4387](https://github.com/JetBrains/compose-multiplatform/issues/4387)).
 * `preload()` is suspend → exception-catching is legal in its coroutine scope,
 * unlike inside an `@Composable` (where Compose's compiler invariant forbids
 * `runCatching` / `try` / `catch` around composable calls).
 */
@Composable
fun brandFontFamily(): FontFamily = FontFamily(Font(Res.font.plus_jakarta_sans))

/**
 * Brand typography for `NearYouTheme` — applies the supplied `brandFamily` to all
 * 13 Material 3 type roles (`displayLarge` through `labelSmall`). Per `design.md`
 * Decision 5 (amended post-apply-phase-discovery to use the canonical CMP
 * preload pattern).
 *
 * Pure transformation: takes a `FontFamily`, returns a `Typography`. The caller
 * (`NearYouTheme`) is responsible for ensuring the family is preload-validated
 * before passing it in — if the font load fails, the caller falls back to
 * vanilla `Typography()` and never invokes this function with the failed family.
 */
@Composable
fun nearYouTypography(brandFamily: FontFamily): Typography {
    val base = Typography()
    return Typography(
        displayLarge = base.displayLarge.copy(fontFamily = brandFamily),
        displayMedium = base.displayMedium.copy(fontFamily = brandFamily),
        displaySmall = base.displaySmall.copy(fontFamily = brandFamily),
        headlineLarge = base.headlineLarge.copy(fontFamily = brandFamily),
        headlineMedium = base.headlineMedium.copy(fontFamily = brandFamily),
        headlineSmall = base.headlineSmall.copy(fontFamily = brandFamily),
        titleLarge = base.titleLarge.copy(fontFamily = brandFamily),
        titleMedium = base.titleMedium.copy(fontFamily = brandFamily),
        titleSmall = base.titleSmall.copy(fontFamily = brandFamily),
        bodyLarge = base.bodyLarge.copy(fontFamily = brandFamily),
        bodyMedium = base.bodyMedium.copy(fontFamily = brandFamily),
        bodySmall = base.bodySmall.copy(fontFamily = brandFamily),
        labelLarge = base.labelLarge.copy(fontFamily = brandFamily),
        labelMedium = base.labelMedium.copy(fontFamily = brandFamily),
        labelSmall = base.labelSmall.copy(fontFamily = brandFamily),
    )
}
