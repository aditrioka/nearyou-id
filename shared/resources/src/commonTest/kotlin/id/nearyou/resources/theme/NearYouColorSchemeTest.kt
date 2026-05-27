package id.nearyou.resources.theme

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Full-table regression for [NearYouColorScheme] light + dark + [NearYouColors]
 * accent / status / link palettes. Catches drift if a future change touches
 * the palette without updating `design.md` Decision 3.
 *
 * Pure-Kotlin assertions — no Compose UI test runner required.
 */
class NearYouColorSchemeTest {
    // === LIGHT scheme — Material 3 30+ standard roles ===

    @Test
    fun light_primary_isBrandBlue() = assertEquals(Color(0xFF1E4FD6), NearYouColorScheme.light.primary)

    @Test
    fun light_onPrimary_isWhite() = assertEquals(Color(0xFFFFFFFF), NearYouColorScheme.light.onPrimary)

    @Test
    fun light_primaryContainer() = assertEquals(Color(0xFFE8EEFB), NearYouColorScheme.light.primaryContainer)

    @Test
    fun light_onPrimaryContainer() = assertEquals(Color(0xFF1740B8), NearYouColorScheme.light.onPrimaryContainer)

    @Test
    fun light_inversePrimary() = assertEquals(Color(0xFF8AAEF8), NearYouColorScheme.light.inversePrimary)

    @Test
    fun light_secondary_isNeutralNotCoral() {
        // Per design.md Decision 2: secondary MUST be neutral surfaceVariant, NOT
        // coral #FF7A5C (which is reserved as ColorScheme.locationPin extension).
        assertEquals(Color(0xFFEEF0F4), NearYouColorScheme.light.secondary)
    }

    @Test
    fun light_onSecondary() = assertEquals(Color(0xFF3E4557), NearYouColorScheme.light.onSecondary)

    @Test
    fun light_secondaryContainer() = assertEquals(Color(0xFFF5F6F8), NearYouColorScheme.light.secondaryContainer)

    @Test
    fun light_onSecondaryContainer() = assertEquals(Color(0xFF0E1220), NearYouColorScheme.light.onSecondaryContainer)

    @Test
    fun light_tertiary_isNeutralNotAmber() {
        // Per design.md Decision 2: tertiary MUST be neutral, NOT amber #F4B740
        // (reserved as ColorScheme.premiumBadge extension).
        assertEquals(Color(0xFFE8EAEF), NearYouColorScheme.light.tertiary)
    }

    @Test
    fun light_onTertiary() = assertEquals(Color(0xFF0E1220), NearYouColorScheme.light.onTertiary)

    @Test
    fun light_tertiaryContainer() = assertEquals(Color(0xFFF7F8FA), NearYouColorScheme.light.tertiaryContainer)

    @Test
    fun light_onTertiaryContainer() = assertEquals(Color(0xFF0E1220), NearYouColorScheme.light.onTertiaryContainer)

    @Test
    fun light_background_aliasOfSurface() {
        assertEquals(Color(0xFFFFFFFF), NearYouColorScheme.light.background)
        assertEquals(NearYouColorScheme.light.surface, NearYouColorScheme.light.background)
    }

    @Test
    fun light_onBackground_aliasOfOnSurface() {
        assertEquals(Color(0xFF0E1220), NearYouColorScheme.light.onBackground)
        assertEquals(NearYouColorScheme.light.onSurface, NearYouColorScheme.light.onBackground)
    }

    @Test
    fun light_surface() = assertEquals(Color(0xFFFFFFFF), NearYouColorScheme.light.surface)

    @Test
    fun light_onSurface() = assertEquals(Color(0xFF0E1220), NearYouColorScheme.light.onSurface)

    @Test
    fun light_surfaceVariant() = assertEquals(Color(0xFFEEF0F4), NearYouColorScheme.light.surfaceVariant)

    @Test
    fun light_onSurfaceVariant() = assertEquals(Color(0xFF3E4557), NearYouColorScheme.light.onSurfaceVariant)

    @Test
    fun light_surfaceTint_aliasOfPrimary() {
        assertEquals(Color(0xFF1E4FD6), NearYouColorScheme.light.surfaceTint)
        assertEquals(NearYouColorScheme.light.primary, NearYouColorScheme.light.surfaceTint)
    }

    @Test
    fun light_inverseSurface() = assertEquals(Color(0xFF1B2234), NearYouColorScheme.light.inverseSurface)

    @Test
    fun light_inverseOnSurface() = assertEquals(Color(0xFFFFFFFF), NearYouColorScheme.light.inverseOnSurface)

    @Test
    fun light_error() = assertEquals(Color(0xFFE4443B), NearYouColorScheme.light.error)

    @Test
    fun light_onError() = assertEquals(Color(0xFFFFFFFF), NearYouColorScheme.light.onError)

    @Test
    fun light_errorContainer() = assertEquals(Color(0xFFFDEAEA), NearYouColorScheme.light.errorContainer)

    @Test
    fun light_onErrorContainer() = assertEquals(Color(0xFFB8342C), NearYouColorScheme.light.onErrorContainer)

    @Test
    fun light_outline_meetsM3ContrastGuideline() {
        // Per design.md Decision 9: #79747E (M3 default) passes WCAG 4.5:1
        // against #FFFFFF surface, satisfying M3's 3:1 outline requirement.
        // Palette author's #D9DDE5 (1.36:1, fails) is on outlineVariant instead.
        // Earlier proposal value #9CA3AF (2.54:1, also fails) is rejected.
        assertEquals(Color(0xFF79747E), NearYouColorScheme.light.outline)
    }

    @Test
    fun light_outlineVariant() = assertEquals(Color(0xFFE8EAEF), NearYouColorScheme.light.outlineVariant)

    @Test
    fun light_scrim_isCorrectlyEncoded() {
        // 0x8F alpha ≈ 56% over the #0E1220 onSurface base color.
        assertEquals(Color(0x8F0E1220), NearYouColorScheme.light.scrim)
    }

    @Test
    fun light_surfaceBright() = assertEquals(Color(0xFFFFFFFF), NearYouColorScheme.light.surfaceBright)

    @Test
    fun light_surfaceDim() = assertEquals(Color(0xFFDCDFE5), NearYouColorScheme.light.surfaceDim)

    @Test
    fun light_surfaceContainerLowest() = assertEquals(Color(0xFFFFFFFF), NearYouColorScheme.light.surfaceContainerLowest)

    @Test
    fun light_surfaceContainerLow() = assertEquals(Color(0xFFF7F8FA), NearYouColorScheme.light.surfaceContainerLow)

    @Test
    fun light_surfaceContainer() = assertEquals(Color(0xFFF5F6F8), NearYouColorScheme.light.surfaceContainer)

    @Test
    fun light_surfaceContainerHigh() = assertEquals(Color(0xFFEEF0F4), NearYouColorScheme.light.surfaceContainerHigh)

    @Test
    fun light_surfaceContainerHighest() = assertEquals(Color(0xFFE8EAEF), NearYouColorScheme.light.surfaceContainerHighest)

    // === DARK scheme — derived from primary via HCT tonal stops per Decision 3 ===

    @Test
    fun dark_primary() = assertEquals(Color(0xFFB3C5FF), NearYouColorScheme.dark.primary)

    @Test
    fun dark_onPrimary() = assertEquals(Color(0xFF002C7B), NearYouColorScheme.dark.onPrimary)

    @Test
    fun dark_primaryContainer() = assertEquals(Color(0xFF003DAB), NearYouColorScheme.dark.primaryContainer)

    @Test
    fun dark_onPrimaryContainer() = assertEquals(Color(0xFFDBE1FF), NearYouColorScheme.dark.onPrimaryContainer)

    @Test
    fun dark_inversePrimary_rollsBackToLightPrimary() {
        assertEquals(Color(0xFF1E4FD6), NearYouColorScheme.dark.inversePrimary)
        assertEquals(NearYouColorScheme.light.primary, NearYouColorScheme.dark.inversePrimary)
    }

    @Test
    fun dark_secondary() = assertEquals(Color(0xFF44464F), NearYouColorScheme.dark.secondary)

    @Test
    fun dark_onSecondary() = assertEquals(Color(0xFFC4C6D0), NearYouColorScheme.dark.onSecondary)

    @Test
    fun dark_tertiary() = assertEquals(Color(0xFF32353A), NearYouColorScheme.dark.tertiary)

    @Test
    fun dark_background_aliasOfSurface() {
        assertEquals(Color(0xFF111318), NearYouColorScheme.dark.background)
        assertEquals(NearYouColorScheme.dark.surface, NearYouColorScheme.dark.background)
    }

    @Test
    fun dark_surface() = assertEquals(Color(0xFF111318), NearYouColorScheme.dark.surface)

    @Test
    fun dark_onSurface() = assertEquals(Color(0xFFE2E2E9), NearYouColorScheme.dark.onSurface)

    @Test
    fun dark_outline() = assertEquals(Color(0xFF938F99), NearYouColorScheme.dark.outline)

    @Test
    fun dark_outlineVariant() = assertEquals(Color(0xFF44464F), NearYouColorScheme.dark.outlineVariant)

    @Test
    fun dark_scrim_matchesLightScrim() {
        // Scrim is intentionally identical between schemes (the modal overlay
        // color is theme-independent — same 56%-opacity ink on both backgrounds).
        assertEquals(Color(0x8F0E1220), NearYouColorScheme.dark.scrim)
    }

    @Test
    fun dark_surfaceBright() = assertEquals(Color(0xFF37393E), NearYouColorScheme.dark.surfaceBright)

    @Test
    fun dark_surfaceDim() = assertEquals(Color(0xFF111318), NearYouColorScheme.dark.surfaceDim)

    @Test
    fun dark_surfaceContainerLowest() = assertEquals(Color(0xFF0C0E13), NearYouColorScheme.dark.surfaceContainerLowest)

    @Test
    fun dark_surfaceContainerHighest() = assertEquals(Color(0xFF32353A), NearYouColorScheme.dark.surfaceContainerHighest)

    @Test
    fun dark_error() = assertEquals(Color(0xFFFFB4AB), NearYouColorScheme.dark.error)

    @Test
    fun dark_errorContainer() = assertEquals(Color(0xFF93000A), NearYouColorScheme.dark.errorContainer)

    // === NearYouColors extension palette — light + dark ===

    @Test
    fun nearYouColors_light_locationPin_isCoral() {
        assertEquals(Color(0xFFFF7A5C), NearYouColors.light.locationPin)
    }

    @Test
    fun nearYouColors_light_premiumBadge_isAmber() {
        assertEquals(Color(0xFFF4B740), NearYouColors.light.premiumBadge)
    }

    @Test
    fun nearYouColors_light_success() = assertEquals(Color(0xFF1F9D55), NearYouColors.light.success)

    @Test
    fun nearYouColors_light_warning() = assertEquals(Color(0xFFE49317), NearYouColors.light.warning)

    @Test
    fun nearYouColors_light_link_aliasesOnPrimaryContainer() {
        assertEquals(Color(0xFF1740B8), NearYouColors.light.link)
        // Link is explicitly the same value as M3 onPrimaryContainer — palette
        // author's intent, surfaced as a distinct extension for future-proofing.
        assertEquals(NearYouColorScheme.light.onPrimaryContainer, NearYouColors.light.link)
    }

    @Test
    fun nearYouColors_dark_locationPin() = assertEquals(Color(0xFFFFB59E), NearYouColors.dark.locationPin)

    @Test
    fun nearYouColors_dark_premiumBadge() = assertEquals(Color(0xFFE8B941), NearYouColors.dark.premiumBadge)

    @Test
    fun nearYouColors_dark_success() = assertEquals(Color(0xFF7DDB9C), NearYouColors.dark.success)

    @Test
    fun nearYouColors_dark_warning() = assertEquals(Color(0xFFFFB874), NearYouColors.dark.warning)

    @Test
    fun nearYouColors_dark_link_matchesDarkPrimary() {
        assertEquals(Color(0xFFB3C5FF), NearYouColors.dark.link)
        assertEquals(NearYouColorScheme.dark.primary, NearYouColors.dark.link)
    }
}
