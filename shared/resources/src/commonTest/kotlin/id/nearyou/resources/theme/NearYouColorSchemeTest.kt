package id.nearyou.resources.theme

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Full-table regression for [NearYouColorScheme] light + dark + [NearYouColors]
 * accent / status / link palettes. Catches drift if a future change touches
 * the palette without updating the `mobile-m3-conformant-color-scheme` design.md
 * Decision 3 table.
 *
 * Pure-Kotlin assertions — no Compose UI test runner required.
 */
class NearYouColorSchemeTest {
    // === LIGHT scheme — Material 3 30+ standard roles (design.md Decision 3) ===

    @Test
    fun light_primary_isBrandBlue() = assertEquals(Color(0xFF1E4FD6), NearYouColorScheme.light.primary)

    @Test
    fun light_onPrimary_isWhite() = assertEquals(Color(0xFFFFFFFF), NearYouColorScheme.light.onPrimary)

    @Test
    fun light_primaryContainer() = assertEquals(Color(0xFFDCE1FF), NearYouColorScheme.light.primaryContainer)

    @Test
    fun light_onPrimaryContainer() = assertEquals(Color(0xFF354479), NearYouColorScheme.light.onPrimaryContainer)

    @Test
    fun light_inversePrimary() = assertEquals(Color(0xFFB7C4FF), NearYouColorScheme.light.inversePrimary)

    @Test
    fun light_secondary_isReadableAccentNotCoral() {
        // Post-mobile-m3-conformant-color-scheme: secondary is a genuine Secondary
        // tonal accent (was the neutralized near-white #EEF0F4). Still NOT coral
        // #FF7A5C, which stays reserved as the ColorScheme.locationPin extension.
        assertEquals(Color(0xFF595D72), NearYouColorScheme.light.secondary)
    }

    @Test
    fun light_onSecondary() = assertEquals(Color(0xFFFFFFFF), NearYouColorScheme.light.onSecondary)

    @Test
    fun light_secondaryContainer() = assertEquals(Color(0xFFDEE1F9), NearYouColorScheme.light.secondaryContainer)

    @Test
    fun light_onSecondaryContainer() = assertEquals(Color(0xFF424659), NearYouColorScheme.light.onSecondaryContainer)

    @Test
    fun light_tertiary_isReadableAccentNotAmber() {
        // Post-mobile-m3-conformant-color-scheme: tertiary is a genuine Tertiary
        // tonal accent (was a neutralized near-white tone). Still NOT amber
        // #F4B740, which stays reserved as the ColorScheme.premiumBadge extension.
        assertEquals(Color(0xFF75546F), NearYouColorScheme.light.tertiary)
    }

    @Test
    fun light_onTertiary() = assertEquals(Color(0xFFFFFFFF), NearYouColorScheme.light.onTertiary)

    @Test
    fun light_tertiaryContainer() = assertEquals(Color(0xFFFFD7F5), NearYouColorScheme.light.tertiaryContainer)

    @Test
    fun light_onTertiaryContainer() = assertEquals(Color(0xFF5B3D57), NearYouColorScheme.light.onTertiaryContainer)

    @Test
    fun light_background_aliasOfSurface() {
        assertEquals(Color(0xFFFAF8FF), NearYouColorScheme.light.background)
        assertEquals(NearYouColorScheme.light.surface, NearYouColorScheme.light.background)
    }

    @Test
    fun light_onBackground_aliasOfOnSurface() {
        assertEquals(Color(0xFF1A1B21), NearYouColorScheme.light.onBackground)
        assertEquals(NearYouColorScheme.light.onSurface, NearYouColorScheme.light.onBackground)
    }

    @Test
    fun light_surface() = assertEquals(Color(0xFFFAF8FF), NearYouColorScheme.light.surface)

    @Test
    fun light_onSurface() = assertEquals(Color(0xFF1A1B21), NearYouColorScheme.light.onSurface)

    @Test
    fun light_surfaceVariant() = assertEquals(Color(0xFFE2E1EC), NearYouColorScheme.light.surfaceVariant)

    @Test
    fun light_onSurfaceVariant() = assertEquals(Color(0xFF45464F), NearYouColorScheme.light.onSurfaceVariant)

    @Test
    fun light_surfaceTint_aliasOfPrimary() {
        assertEquals(Color(0xFF1E4FD6), NearYouColorScheme.light.surfaceTint)
        assertEquals(NearYouColorScheme.light.primary, NearYouColorScheme.light.surfaceTint)
    }

    @Test
    fun light_inverseSurface() = assertEquals(Color(0xFF2F3036), NearYouColorScheme.light.inverseSurface)

    @Test
    fun light_inverseOnSurface() = assertEquals(Color(0xFFF1F0F7), NearYouColorScheme.light.inverseOnSurface)

    @Test
    fun light_error() = assertEquals(Color(0xFFBA1A1A), NearYouColorScheme.light.error)

    @Test
    fun light_onError() = assertEquals(Color(0xFFFFFFFF), NearYouColorScheme.light.onError)

    @Test
    fun light_errorContainer() = assertEquals(Color(0xFFFFDAD6), NearYouColorScheme.light.errorContainer)

    @Test
    fun light_onErrorContainer() = assertEquals(Color(0xFF93000A), NearYouColorScheme.light.onErrorContainer)

    @Test
    fun light_outline_meetsM3ContrastGuideline() {
        // Preserved through this change: #79747E (M3 default) passes WCAG 4.5:1
        // against the near-white #FAF8FF surface, satisfying M3's outline contrast
        // requirement. The lower-contrast decorative tone lives on outlineVariant.
        assertEquals(Color(0xFF79747E), NearYouColorScheme.light.outline)
    }

    @Test
    fun light_outlineVariant() = assertEquals(Color(0xFFC4C5D7), NearYouColorScheme.light.outlineVariant)

    @Test
    fun light_scrim_isCorrectlyEncoded() {
        // Preserved translucent app value: 0x8F alpha ≈ 56% over the #0E1220 ink
        // (NOT the tonal pipeline's opaque #000000).
        assertEquals(Color(0x8F0E1220), NearYouColorScheme.light.scrim)
    }

    @Test
    fun light_surfaceBright() = assertEquals(Color(0xFFFAF8FF), NearYouColorScheme.light.surfaceBright)

    @Test
    fun light_surfaceDim() = assertEquals(Color(0xFFDAD9E0), NearYouColorScheme.light.surfaceDim)

    @Test
    fun light_surfaceContainerLowest() = assertEquals(Color(0xFFFFFFFF), NearYouColorScheme.light.surfaceContainerLowest)

    @Test
    fun light_surfaceContainerLow() = assertEquals(Color(0xFFF4F2FA), NearYouColorScheme.light.surfaceContainerLow)

    @Test
    fun light_surfaceContainer() = assertEquals(Color(0xFFEFEDF4), NearYouColorScheme.light.surfaceContainer)

    @Test
    fun light_surfaceContainerHigh() = assertEquals(Color(0xFFE9E7EF), NearYouColorScheme.light.surfaceContainerHigh)

    @Test
    fun light_surfaceContainerHighest() = assertEquals(Color(0xFFE3E1E9), NearYouColorScheme.light.surfaceContainerHighest)

    // === DARK scheme — seed dark tonal stops per design.md Decision 3 ===

    @Test
    fun dark_primary() = assertEquals(Color(0xFFB7C4FF), NearYouColorScheme.dark.primary)

    @Test
    fun dark_onPrimary() = assertEquals(Color(0xFF002780), NearYouColorScheme.dark.onPrimary)

    @Test
    fun dark_primaryContainer() = assertEquals(Color(0xFF354479), NearYouColorScheme.dark.primaryContainer)

    @Test
    fun dark_onPrimaryContainer() = assertEquals(Color(0xFFDCE1FF), NearYouColorScheme.dark.onPrimaryContainer)

    @Test
    fun dark_inversePrimary_rollsBackToLightPrimary() {
        assertEquals(Color(0xFF1E4FD6), NearYouColorScheme.dark.inversePrimary)
        assertEquals(NearYouColorScheme.light.primary, NearYouColorScheme.dark.inversePrimary)
    }

    @Test
    fun dark_secondary() = assertEquals(Color(0xFFC2C5DD), NearYouColorScheme.dark.secondary)

    @Test
    fun dark_onSecondary() = assertEquals(Color(0xFF2B3042), NearYouColorScheme.dark.onSecondary)

    @Test
    fun dark_tertiary() = assertEquals(Color(0xFFE3BADA), NearYouColorScheme.dark.tertiary)

    @Test
    fun dark_background_aliasOfSurface() {
        assertEquals(Color(0xFF121318), NearYouColorScheme.dark.background)
        assertEquals(NearYouColorScheme.dark.surface, NearYouColorScheme.dark.background)
    }

    @Test
    fun dark_surface() = assertEquals(Color(0xFF121318), NearYouColorScheme.dark.surface)

    @Test
    fun dark_onSurface() = assertEquals(Color(0xFFE3E1E9), NearYouColorScheme.dark.onSurface)

    @Test
    fun dark_outline() = assertEquals(Color(0xFF8E90A0), NearYouColorScheme.dark.outline)

    @Test
    fun dark_outlineVariant() = assertEquals(Color(0xFF45464F), NearYouColorScheme.dark.outlineVariant)

    @Test
    fun dark_scrim_matchesLightScrim() {
        // Scrim is intentionally identical between schemes (the modal overlay
        // color is theme-independent — same 56%-opacity ink on both backgrounds).
        assertEquals(Color(0x8F0E1220), NearYouColorScheme.dark.scrim)
    }

    @Test
    fun dark_surfaceBright() = assertEquals(Color(0xFF38393F), NearYouColorScheme.dark.surfaceBright)

    @Test
    fun dark_surfaceDim() = assertEquals(Color(0xFF121318), NearYouColorScheme.dark.surfaceDim)

    @Test
    fun dark_surfaceContainerLowest() = assertEquals(Color(0xFF0D0E13), NearYouColorScheme.dark.surfaceContainerLowest)

    @Test
    fun dark_surfaceContainerHighest() = assertEquals(Color(0xFF34343A), NearYouColorScheme.dark.surfaceContainerHighest)

    @Test
    fun dark_error() = assertEquals(Color(0xFFFFB4AB), NearYouColorScheme.dark.error)

    @Test
    fun dark_errorContainer() = assertEquals(Color(0xFF93000A), NearYouColorScheme.dark.errorContainer)

    // dark — remaining roles (full-table parity with light; many of these changed value in this change)

    @Test
    fun dark_secondaryContainer() = assertEquals(Color(0xFF424659), NearYouColorScheme.dark.secondaryContainer)

    @Test
    fun dark_onSecondaryContainer() = assertEquals(Color(0xFFDEE1F9), NearYouColorScheme.dark.onSecondaryContainer)

    @Test
    fun dark_onTertiary() = assertEquals(Color(0xFF43273F), NearYouColorScheme.dark.onTertiary)

    @Test
    fun dark_tertiaryContainer() = assertEquals(Color(0xFF5B3D57), NearYouColorScheme.dark.tertiaryContainer)

    @Test
    fun dark_onTertiaryContainer() = assertEquals(Color(0xFFFFD7F5), NearYouColorScheme.dark.onTertiaryContainer)

    @Test
    fun dark_onBackground_aliasOfOnSurface() {
        assertEquals(Color(0xFFE3E1E9), NearYouColorScheme.dark.onBackground)
        assertEquals(NearYouColorScheme.dark.onSurface, NearYouColorScheme.dark.onBackground)
    }

    @Test
    fun dark_surfaceVariant() = assertEquals(Color(0xFF45464F), NearYouColorScheme.dark.surfaceVariant)

    @Test
    fun dark_onSurfaceVariant() = assertEquals(Color(0xFFC6C5D0), NearYouColorScheme.dark.onSurfaceVariant)

    @Test
    fun dark_surfaceTint_aliasOfPrimary() {
        assertEquals(Color(0xFFB7C4FF), NearYouColorScheme.dark.surfaceTint)
        assertEquals(NearYouColorScheme.dark.primary, NearYouColorScheme.dark.surfaceTint)
    }

    @Test
    fun dark_inverseSurface() = assertEquals(Color(0xFFE3E1E9), NearYouColorScheme.dark.inverseSurface)

    @Test
    fun dark_inverseOnSurface() = assertEquals(Color(0xFF2F3036), NearYouColorScheme.dark.inverseOnSurface)

    @Test
    fun dark_onError() = assertEquals(Color(0xFF690005), NearYouColorScheme.dark.onError)

    @Test
    fun dark_onErrorContainer() = assertEquals(Color(0xFFFFDAD6), NearYouColorScheme.dark.onErrorContainer)

    @Test
    fun dark_surfaceContainerLow() = assertEquals(Color(0xFF1A1B21), NearYouColorScheme.dark.surfaceContainerLow)

    @Test
    fun dark_surfaceContainer() = assertEquals(Color(0xFF1E1F25), NearYouColorScheme.dark.surfaceContainer)

    @Test
    fun dark_surfaceContainerHigh() = assertEquals(Color(0xFF292A2F), NearYouColorScheme.dark.surfaceContainerHigh)

    // === NearYouColors extension palette — light + dark (UNCHANGED by this change) ===

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
    fun nearYouColors_light_link() {
        // link keeps its dark-cobalt value. The mobile-m3-conformant-color-scheme
        // change moved onPrimaryContainer off #1740B8, so link is now an
        // independent value — no longer incidentally aliased to a scheme role.
        assertEquals(Color(0xFF1740B8), NearYouColors.light.link)
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
    fun nearYouColors_dark_link() {
        // dark link keeps its value; dark primary moved to #B7C4FF, so the two are
        // no longer equal — link is an independent extension value.
        assertEquals(Color(0xFFB3C5FF), NearYouColors.dark.link)
    }
}
