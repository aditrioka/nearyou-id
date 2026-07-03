package id.nearyou.app.infra.admob

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The platform native-ad rendering surface — the design-D2 contract whose actual draws the platform
 * `NativeAdView` (Android) / `GADNativeAdView` (iOS) and binds [content]'s opaque handle so impressions
 * and clicks register per AdMob policy. `:mobile:app`'s `NativeAdCard` wraps this with the card chrome
 * (`PostCard` geometry + the localized "Bersponsor" label) and calls this for the tracked ad creative.
 *
 * Vendor fencing: only this composable's actuals touch the Google native-ad view types; the signature
 * carries only [NativeAdContent] (vendor-free) + a Compose [Modifier], so `:mobile:app` never imports a
 * Google SDK type (invariant #16). When [content]'s handle is not a live platform native ad (e.g. a
 * commonTest/Robolectric fake), the actual renders the vendor-free display fields as plain text so the
 * slot + label still assert in tests.
 */
@Composable
expect fun NativeAdSurface(
    content: NativeAdContent,
    modifier: Modifier,
)
