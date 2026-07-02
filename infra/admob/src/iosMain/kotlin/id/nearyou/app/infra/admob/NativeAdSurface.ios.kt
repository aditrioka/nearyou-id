@file:OptIn(ExperimentalForeignApi::class)

package id.nearyou.app.infra.admob

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import cocoapods.Google_Mobile_Ads_SDK.GADNativeAd
import cocoapods.Google_Mobile_Ads_SDK.GADNativeAdView
import kotlinx.cinterop.ExperimentalForeignApi
import platform.UIKit.UIButton
import platform.UIKit.UIButtonTypeSystem
import platform.UIKit.UILabel
import platform.UIKit.UIView

/**
 * iOS [NativeAdSurface] — draws a `GADNativeAdView` and registers the headline / body / CTA asset views so
 * the Google SDK tracks the impression + click on the live native ad (AdMob policy, design D2). When the
 * handle is not a live [GADNativeAd] (a fake), it renders the vendor-free fields as Compose text.
 *
 * Needs local `linkDebugFrameworkIosSimulatorArm64` verification (§2.5) to confirm the GMA-iOS-12 cinterop
 * asset-view selectors; iOS is not CI-gated.
 */
@Composable
actual fun NativeAdSurface(
    content: NativeAdContent,
    modifier: Modifier,
) {
    val nativeAd = content.handle as? GADNativeAd
    if (nativeAd == null) {
        Column(modifier = modifier) {
            content.headline?.let { BasicText(it) }
            content.body?.let { BasicText(it) }
            content.callToAction?.let { BasicText(it) }
        }
        return
    }
    UIKitView(
        modifier = modifier,
        factory = {
            val adView = GADNativeAdView()
            val headlineLabel = UILabel()
            val bodyLabel = UILabel()
            val ctaButton = UIButton.buttonWithType(UIButtonTypeSystem)
            adView.addSubview(headlineLabel)
            adView.addSubview(bodyLabel)
            adView.addSubview(ctaButton)
            adView.headlineView = headlineLabel
            adView.bodyView = bodyLabel
            adView.callToActionView = ctaButton
            adView as UIView
        },
        update = { view ->
            val adView = view as GADNativeAdView
            (adView.headlineView as? UILabel)?.text = nativeAd.headline
            (adView.bodyView as? UILabel)?.text = nativeAd.body
            (adView.callToActionView as? UIButton)?.setTitle(nativeAd.callToAction, forState = 0u)
            // Registers the asset views with the SDK — impression + click tracking on the live ad.
            adView.nativeAd = nativeAd
        },
    )
}
