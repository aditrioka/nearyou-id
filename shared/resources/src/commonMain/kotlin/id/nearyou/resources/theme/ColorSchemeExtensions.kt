package id.nearyou.resources.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * CompositionLocal providing the active [NearYouColors] palette. Wired by
 * `NearYouTheme` via `CompositionLocalProvider`; reading outside that wrapper
 * raises `IllegalStateException` per `design.md` Decision 10's contract.
 *
 * The error default (instead of a fabricated [NearYouColors] instance) is
 * intentional — a silent default would mask missing-theme bugs at composition
 * sites that legitimately need the brand accents.
 */
val LocalNearYouColors = staticCompositionLocalOf<NearYouColors> {
    error("NearYouTheme not applied — wrap your composition in NearYouTheme { ... } to access brand color extensions.")
}

// Reserved-purpose brand accents (per design.md Decision 2)

val ColorScheme.locationPin: Color
    @Composable @ReadOnlyComposable
    get() = LocalNearYouColors.current.locationPin

val ColorScheme.onLocationPin: Color
    @Composable @ReadOnlyComposable
    get() = LocalNearYouColors.current.onLocationPin

val ColorScheme.locationPinContainer: Color
    @Composable @ReadOnlyComposable
    get() = LocalNearYouColors.current.locationPinContainer

val ColorScheme.onLocationPinContainer: Color
    @Composable @ReadOnlyComposable
    get() = LocalNearYouColors.current.onLocationPinContainer

val ColorScheme.premiumBadge: Color
    @Composable @ReadOnlyComposable
    get() = LocalNearYouColors.current.premiumBadge

val ColorScheme.onPremiumBadge: Color
    @Composable @ReadOnlyComposable
    get() = LocalNearYouColors.current.onPremiumBadge

val ColorScheme.premiumBadgeContainer: Color
    @Composable @ReadOnlyComposable
    get() = LocalNearYouColors.current.premiumBadgeContainer

val ColorScheme.onPremiumBadgeContainer: Color
    @Composable @ReadOnlyComposable
    get() = LocalNearYouColors.current.onPremiumBadgeContainer

// Semantic status colors

val ColorScheme.success: Color
    @Composable @ReadOnlyComposable
    get() = LocalNearYouColors.current.success

val ColorScheme.onSuccess: Color
    @Composable @ReadOnlyComposable
    get() = LocalNearYouColors.current.onSuccess

val ColorScheme.successContainer: Color
    @Composable @ReadOnlyComposable
    get() = LocalNearYouColors.current.successContainer

val ColorScheme.onSuccessContainer: Color
    @Composable @ReadOnlyComposable
    get() = LocalNearYouColors.current.onSuccessContainer

val ColorScheme.warning: Color
    @Composable @ReadOnlyComposable
    get() = LocalNearYouColors.current.warning

val ColorScheme.onWarning: Color
    @Composable @ReadOnlyComposable
    get() = LocalNearYouColors.current.onWarning

val ColorScheme.warningContainer: Color
    @Composable @ReadOnlyComposable
    get() = LocalNearYouColors.current.warningContainer

val ColorScheme.onWarningContainer: Color
    @Composable @ReadOnlyComposable
    get() = LocalNearYouColors.current.onWarningContainer

val ColorScheme.link: Color
    @Composable @ReadOnlyComposable
    get() = LocalNearYouColors.current.link
