package id.nearyou.resources.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Brand `ColorScheme` instances for `NearYouTheme`.
 *
 * Light scheme: claude.ai/design output anchored at primary `#1E4FD6`.
 * Dark scheme: mechanically derived from primary via HCT tonal stops
 * (Material Theme Builder algorithm). Per `design.md` Decision 3.
 *
 * Important non-obvious mappings (see Decisions 2 + 9):
 * - `secondary` and `tertiary` are NEUTRAL (surfaceVariant family), NOT
 *   coral/amber. Brand accents live on `ColorScheme.locationPin` and
 *   `ColorScheme.premiumBadge` extension properties.
 * - `outline` is the M3 default `#79747E` (passes WCAG 4.5:1 against white
 *   surface), NOT the palette author's `#D9DDE5` (1.36:1, fails) — preserved
 *   as `outlineVariant`.
 */
object NearYouColorScheme {
    val light: ColorScheme =
        lightColorScheme(
            primary = Color(0xFF1E4FD6),
            onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFE8EEFB),
            onPrimaryContainer = Color(0xFF1740B8),
            inversePrimary = Color(0xFF8AAEF8),
            secondary = Color(0xFFEEF0F4),
            onSecondary = Color(0xFF3E4557),
            secondaryContainer = Color(0xFFF5F6F8),
            onSecondaryContainer = Color(0xFF0E1220),
            tertiary = Color(0xFFE8EAEF),
            onTertiary = Color(0xFF0E1220),
            tertiaryContainer = Color(0xFFF7F8FA),
            onTertiaryContainer = Color(0xFF0E1220),
            background = Color(0xFFFFFFFF),
            onBackground = Color(0xFF0E1220),
            surface = Color(0xFFFFFFFF),
            onSurface = Color(0xFF0E1220),
            surfaceVariant = Color(0xFFEEF0F4),
            onSurfaceVariant = Color(0xFF3E4557),
            surfaceTint = Color(0xFF1E4FD6),
            inverseSurface = Color(0xFF1B2234),
            inverseOnSurface = Color(0xFFFFFFFF),
            error = Color(0xFFE4443B),
            onError = Color(0xFFFFFFFF),
            errorContainer = Color(0xFFFDEAEA),
            onErrorContainer = Color(0xFFB8342C),
            outline = Color(0xFF79747E),
            outlineVariant = Color(0xFFE8EAEF),
            scrim = Color(0x8F0E1220),
            surfaceBright = Color(0xFFFFFFFF),
            surfaceDim = Color(0xFFDCDFE5),
            surfaceContainerLowest = Color(0xFFFFFFFF),
            surfaceContainerLow = Color(0xFFF7F8FA),
            surfaceContainer = Color(0xFFF5F6F8),
            surfaceContainerHigh = Color(0xFFEEF0F4),
            surfaceContainerHighest = Color(0xFFE8EAEF),
        )

    val dark: ColorScheme =
        darkColorScheme(
            primary = Color(0xFFB3C5FF),
            onPrimary = Color(0xFF002C7B),
            primaryContainer = Color(0xFF003DAB),
            onPrimaryContainer = Color(0xFFDBE1FF),
            inversePrimary = Color(0xFF1E4FD6),
            secondary = Color(0xFF44464F),
            onSecondary = Color(0xFFC4C6D0),
            secondaryContainer = Color(0xFF1D2024),
            onSecondaryContainer = Color(0xFFE2E2E9),
            tertiary = Color(0xFF32353A),
            onTertiary = Color(0xFFC4C6D0),
            tertiaryContainer = Color(0xFF272A2F),
            onTertiaryContainer = Color(0xFFE2E2E9),
            background = Color(0xFF111318),
            onBackground = Color(0xFFE2E2E9),
            surface = Color(0xFF111318),
            onSurface = Color(0xFFE2E2E9),
            surfaceVariant = Color(0xFF44464F),
            onSurfaceVariant = Color(0xFFC4C6D0),
            surfaceTint = Color(0xFFB3C5FF),
            inverseSurface = Color(0xFFE2E2E9),
            inverseOnSurface = Color(0xFF2F3036),
            error = Color(0xFFFFB4AB),
            onError = Color(0xFF690005),
            errorContainer = Color(0xFF93000A),
            onErrorContainer = Color(0xFFFFDAD6),
            outline = Color(0xFF938F99),
            outlineVariant = Color(0xFF44464F),
            scrim = Color(0x8F0E1220),
            surfaceBright = Color(0xFF37393E),
            surfaceDim = Color(0xFF111318),
            surfaceContainerLowest = Color(0xFF0C0E13),
            surfaceContainerLow = Color(0xFF191C20),
            surfaceContainer = Color(0xFF1D2024),
            surfaceContainerHigh = Color(0xFF272A2F),
            surfaceContainerHighest = Color(0xFF32353A),
        )
}
