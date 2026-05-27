package id.nearyou.resources.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import dev.icerock.moko.resources.compose.asFont
import id.nearyou.resources.MR

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
 * If `MR.fonts.plus_jakarta_sans.regular.asFont()` returns null at runtime
 * (Moko Resources font loading failure — rare; the .ttf is bundled, not
 * network-fetched), we fall back to `Typography()` defaults, which the
 * platform renders in its sans-serif (per `design.md` Decision 5's
 * defensive fallback contract).
 */
@Composable
fun nearYouTypography(): Typography {
    val brandFont = MR.fonts.plus_jakarta_sans.asFont()
    if (brandFont == null) {
        // Defensive fallback per design.md Decision 5: missing font → platform
        // sans-serif via vanilla Material 3 Typography().
        return Typography()
    }
    val family = FontFamily(brandFont)
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
