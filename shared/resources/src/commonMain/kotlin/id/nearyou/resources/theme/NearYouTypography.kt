package id.nearyou.resources.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.plus_jakarta_sans
import org.jetbrains.compose.resources.Font

/**
 * Brand typography for `NearYouTheme` — Plus Jakarta Sans variable font
 * (OFL-licensed, Tokotype, designed for Pemprov DKI Jakarta) applied to all
 * 13 Material 3 type roles. Per `design.md` Decision 5.
 *
 * Implementation note: Compose Multiplatform's `FontFamily` accepts a sequence
 * of platform-resolved `Font` instances; system fallback is handled per-platform
 * by the underlying text-shaping engine when a glyph isn't found. The variable
 * font's weight axis (200–800) is exercised by Material 3's standard type-scale
 * weights (`displayLarge` 400, `labelSmall` 500, etc.) — the axis interpolation
 * is the renderer's job, not ours.
 *
 * Defensive fallback removed in the Moko→CMP swap: CMP Resources' `Font(...)`
 * is `@Composable` and returns non-null. Wrapping it in `runCatching` / `try`
 * is forbidden by Compose's "no exception catching around @Composable calls"
 * invariant. The .ttf is bundled into `composeResources/font/` and resolved
 * at build time by the Compose Resources Gradle plugin — runtime-missing-font
 * is a "framework bug" class of failure that should crash the composition
 * rather than silently degrade to a fallback typeface (which would mask a
 * build/packaging regression). Per amended spec scenario "NearYouTypography
 * defensively guards against font-load failure" — defensive responsibility
 * now lives at the resource-bundling layer, not in this function.
 */
@Composable
fun nearYouTypography(): Typography {
    val family = FontFamily(Font(Res.font.plus_jakarta_sans))
    val base = Typography()
    return Typography(
        displayLarge = base.displayLarge.copy(fontFamily = family),
        displayMedium = base.displayMedium.copy(fontFamily = family),
        displaySmall = base.displaySmall.copy(fontFamily = family),
        headlineLarge = base.headlineLarge.copy(fontFamily = family),
        headlineMedium = base.headlineMedium.copy(fontFamily = family),
        headlineSmall = base.headlineSmall.copy(fontFamily = family),
        titleLarge = base.titleLarge.copy(fontFamily = family),
        titleMedium = base.titleMedium.copy(fontFamily = family),
        titleSmall = base.titleSmall.copy(fontFamily = family),
        bodyLarge = base.bodyLarge.copy(fontFamily = family),
        bodyMedium = base.bodyMedium.copy(fontFamily = family),
        bodySmall = base.bodySmall.copy(fontFamily = family),
        labelLarge = base.labelLarge.copy(fontFamily = family),
        labelMedium = base.labelMedium.copy(fontFamily = family),
        labelSmall = base.labelSmall.copy(fontFamily = family),
    )
}
