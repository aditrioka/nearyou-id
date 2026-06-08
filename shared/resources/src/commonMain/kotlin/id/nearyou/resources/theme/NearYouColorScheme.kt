package id.nearyou.resources.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Brand `ColorScheme` instances for `NearYouTheme`.
 *
 * Generated from the brand seed `#1E4FD6` through the Material Theme Builder
 * tonal-palette pipeline (default "tonal spot" scheme, Color Match OFF), so
 * every role sits on its M3 tonal scale and is contrast-safe by construction.
 * Two documented exceptions (see change `mobile-m3-conformant-color-scheme`
 * design.md Decisions 2 + 4):
 * - Light `primary` (and its aliases `surfaceTint` + dark `inversePrimary`) is
 *   PINNED to the exact brand `#1E4FD6` for identity — it is ≈ tone 40 and clears
 *   6.7:1 against white `onPrimary`, so the pin is contrast-safe, not off-scale.
 * - `scrim` keeps the app's translucent `#8F0E1220` (not the pipeline's opaque
 *   `#000000`) and light `outline` keeps the WCAG-safe `#79747E`.
 *
 * Unlike the previous hand-picked palette, `secondary` and `tertiary` are now
 * GENUINE M3 accents drawn from their tonal palettes (light `secondary = #595D72`),
 * NOT neutralized near-white surfaceVariant tones — so default M3 widgets that
 * consume those roles (e.g. the bottom-nav selected state) are readable by
 * construction. The reserved-purpose coral/amber brand accents still live on
 * `ColorScheme.locationPin` / `ColorScheme.premiumBadge` extension properties
 * (see [NearYouColors] + [ColorSchemeExtensions]), never on `secondary`/`tertiary`.
 * Full 30-role light + dark table: design.md Decision 3.
 */
object NearYouColorScheme {
    val light: ColorScheme =
        lightColorScheme(
            primary = Color(0xFF1E4FD6),
            onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFDCE1FF),
            onPrimaryContainer = Color(0xFF354479),
            inversePrimary = Color(0xFFB7C4FF),
            secondary = Color(0xFF595D72),
            onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = Color(0xFFDEE1F9),
            onSecondaryContainer = Color(0xFF424659),
            tertiary = Color(0xFF75546F),
            onTertiary = Color(0xFFFFFFFF),
            tertiaryContainer = Color(0xFFFFD7F5),
            onTertiaryContainer = Color(0xFF5B3D57),
            background = Color(0xFFFAF8FF),
            onBackground = Color(0xFF1A1B21),
            surface = Color(0xFFFAF8FF),
            onSurface = Color(0xFF1A1B21),
            surfaceVariant = Color(0xFFE2E1EC),
            onSurfaceVariant = Color(0xFF45464F),
            surfaceTint = Color(0xFF1E4FD6),
            inverseSurface = Color(0xFF2F3036),
            inverseOnSurface = Color(0xFFF1F0F7),
            error = Color(0xFFBA1A1A),
            onError = Color(0xFFFFFFFF),
            errorContainer = Color(0xFFFFDAD6),
            onErrorContainer = Color(0xFF93000A),
            outline = Color(0xFF79747E),
            outlineVariant = Color(0xFFC4C5D7),
            scrim = Color(0x8F0E1220),
            surfaceBright = Color(0xFFFAF8FF),
            surfaceDim = Color(0xFFDAD9E0),
            surfaceContainerLowest = Color(0xFFFFFFFF),
            surfaceContainerLow = Color(0xFFF4F2FA),
            surfaceContainer = Color(0xFFEFEDF4),
            surfaceContainerHigh = Color(0xFFE9E7EF),
            surfaceContainerHighest = Color(0xFFE3E1E9),
        )

    val dark: ColorScheme =
        darkColorScheme(
            primary = Color(0xFFB7C4FF),
            onPrimary = Color(0xFF002780),
            primaryContainer = Color(0xFF354479),
            onPrimaryContainer = Color(0xFFDCE1FF),
            inversePrimary = Color(0xFF1E4FD6),
            secondary = Color(0xFFC2C5DD),
            onSecondary = Color(0xFF2B3042),
            secondaryContainer = Color(0xFF424659),
            onSecondaryContainer = Color(0xFFDEE1F9),
            tertiary = Color(0xFFE3BADA),
            onTertiary = Color(0xFF43273F),
            tertiaryContainer = Color(0xFF5B3D57),
            onTertiaryContainer = Color(0xFFFFD7F5),
            background = Color(0xFF121318),
            onBackground = Color(0xFFE3E1E9),
            surface = Color(0xFF121318),
            onSurface = Color(0xFFE3E1E9),
            surfaceVariant = Color(0xFF45464F),
            onSurfaceVariant = Color(0xFFC6C5D0),
            surfaceTint = Color(0xFFB7C4FF),
            inverseSurface = Color(0xFFE3E1E9),
            inverseOnSurface = Color(0xFF2F3036),
            error = Color(0xFFFFB4AB),
            onError = Color(0xFF690005),
            errorContainer = Color(0xFF93000A),
            onErrorContainer = Color(0xFFFFDAD6),
            outline = Color(0xFF8E90A0),
            outlineVariant = Color(0xFF45464F),
            scrim = Color(0x8F0E1220),
            surfaceBright = Color(0xFF38393F),
            surfaceDim = Color(0xFF121318),
            surfaceContainerLowest = Color(0xFF0D0E13),
            surfaceContainerLow = Color(0xFF1A1B21),
            surfaceContainer = Color(0xFF1E1F25),
            surfaceContainerHigh = Color(0xFF292A2F),
            surfaceContainerHighest = Color(0xFF34343A),
        )
}
