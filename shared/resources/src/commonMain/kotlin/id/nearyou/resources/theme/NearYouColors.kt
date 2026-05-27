package id.nearyou.resources.theme

import androidx.compose.ui.graphics.Color

/**
 * Reserved-purpose brand accents + semantic status colors exposed as
 * `ColorScheme` extension properties via `CompositionLocal` (see
 * [ColorSchemeExtensions]).
 *
 * Per `design.md` Decision 2: coral location pin + amber Premium badge are
 * NOT mapped to Material 3 `secondary`/`tertiary` (which would leak the
 * reserved-purpose semantic across every default M3 widget that consumes
 * those roles). They live here instead, accessible via
 * `MaterialTheme.colorScheme.locationPin` / `.premiumBadge` etc. at every
 * call site within a `NearYouTheme { ... }` wrapper.
 *
 * Light + dark variants in the [Companion] object.
 */
data class NearYouColors(
    val locationPin: Color,
    val onLocationPin: Color,
    val locationPinContainer: Color,
    val onLocationPinContainer: Color,
    val premiumBadge: Color,
    val onPremiumBadge: Color,
    val premiumBadgeContainer: Color,
    val onPremiumBadgeContainer: Color,
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val link: Color,
) {
    companion object {
        val light: NearYouColors = NearYouColors(
            locationPin = Color(0xFFFF7A5C),
            onLocationPin = Color(0xFFFFFFFF),
            locationPinContainer = Color(0xFFFFEFEA),
            onLocationPinContainer = Color(0xFFB8382A),
            premiumBadge = Color(0xFFF4B740),
            onPremiumBadge = Color(0xFFFFFFFF),
            premiumBadgeContainer = Color(0xFFFFF8EC),
            onPremiumBadgeContainer = Color(0xFFC98915),
            success = Color(0xFF1F9D55),
            onSuccess = Color(0xFFFFFFFF),
            successContainer = Color(0xFFE8F7EE),
            onSuccessContainer = Color(0xFF126B38),
            warning = Color(0xFFE49317),
            onWarning = Color(0xFFFFFFFF),
            warningContainer = Color(0xFFFFF4E0),
            onWarningContainer = Color(0xFF9C610A),
            link = Color(0xFF1740B8),
        )

        val dark: NearYouColors = NearYouColors(
            locationPin = Color(0xFFFFB59E),
            onLocationPin = Color(0xFF561F18),
            locationPinContainer = Color(0xFF7C2E22),
            onLocationPinContainer = Color(0xFFFFDAD2),
            premiumBadge = Color(0xFFE8B941),
            onPremiumBadge = Color(0xFF412D00),
            premiumBadgeContainer = Color(0xFF7A5400),
            onPremiumBadgeContainer = Color(0xFFFFDEA8),
            success = Color(0xFF7DDB9C),
            onSuccess = Color(0xFF003915),
            successContainer = Color(0xFF005321),
            onSuccessContainer = Color(0xFF9CF8B7),
            warning = Color(0xFFFFB874),
            onWarning = Color(0xFF4A2700),
            warningContainer = Color(0xFF693C00),
            onWarningContainer = Color(0xFFFFDDB9),
            link = Color(0xFFB3C5FF),
        )
    }
}
