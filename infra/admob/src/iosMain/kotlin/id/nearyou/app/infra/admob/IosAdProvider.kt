@file:OptIn(ExperimentalForeignApi::class)

package id.nearyou.app.infra.admob

import cocoapods.Google_Mobile_Ads_SDK.GADAdLoader
import cocoapods.Google_Mobile_Ads_SDK.GADAdLoaderDelegateProtocol
import cocoapods.Google_Mobile_Ads_SDK.GADExtras
import cocoapods.Google_Mobile_Ads_SDK.GADMobileAds
import cocoapods.Google_Mobile_Ads_SDK.GADNativeAd
import cocoapods.Google_Mobile_Ads_SDK.GADNativeAdLoaderDelegateProtocol
import cocoapods.Google_Mobile_Ads_SDK.GADRequest
import cocoapods.GoogleUserMessagingPlatform.UMPConsentForm
import cocoapods.GoogleUserMessagingPlatform.UMPConsentInformation
import cocoapods.GoogleUserMessagingPlatform.UMPRequestParameters
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSError
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.darwin.NSObject
import kotlin.coroutines.resume

/**
 * The iOS [AdProvider] actual over the cocoapods-generated cinterop bindings for the `Google-Mobile-Ads-SDK`
 * + `GoogleUserMessagingPlatform` Pods (design D7 — Google publishes no KMP Ads SDK, so `:infra:admob`
 * applies the `native.cocoapods` plugin to own the cinterop, the GoogleSignIn-pod precedent). The ONLY iOS
 * code that touches the Google SDK (invariant #16). Every callback is fail-safe (a consent/load failure →
 * [ConsentState.ERROR] / null ad). Data minimization: the request never carries a precise location.
 *
 * iOS is NOT CI-gated (docs/13) — this actual MUST be verified locally with
 * `./gradlew :infra:admob:linkDebugFrameworkIosSimulatorArm64` (the §2.5 cinterop/category compile gate;
 * Linux CI can't catch GMA-iOS-12 ObjC symbol-name or category-import drift) + a simulator/device
 * ad-render smoke (task 5.4 / 5.6). Any GMA-iOS-12 ObjC name that differs from the cinterop is fixed at
 * that gate.
 */
class IosAdProvider : AdProvider {
    private var initialized = false

    private var consent: ConsentState = ConsentState.UNKNOWN

    private var lastAdLoader: GADAdLoader? = null
    private var lastDelegate: NativeAdDelegate? = null

    override suspend fun initialize() {
        if (!initialized) {
            initialized = true
            GADMobileAds.sharedInstance().startWithCompletionHandler(null)
        }
    }

    override suspend fun requestConsent(): ConsentState {
        val vc = topViewController() ?: run {
            consent = ConsentState.ERROR
            return ConsentState.ERROR
        }
        val info = UMPConsentInformation.sharedInstance()
        val params = UMPRequestParameters()
        val resolved =
            suspendCancellableCoroutine { cont ->
                info.requestConsentInfoUpdateWithParameters(params) { requestError: NSError? ->
                    if (requestError != null) {
                        if (cont.isActive) cont.resume(ConsentState.ERROR)
                    } else {
                        UMPConsentForm.loadAndPresentIfRequiredFromViewController(vc) { _: NSError? ->
                            if (cont.isActive) {
                                cont.resume(
                                    if (info.canRequestAds()) ConsentState.OBTAINED else ConsentState.REQUIRED,
                                )
                            }
                        }
                    }
                }
            }
        consent = resolved
        return resolved
    }

    override fun consentState(): ConsentState = consent

    override suspend fun loadNativeAd(
        adUnitId: String,
        mode: AdRequestMode,
    ): NativeAdContent? {
        val vc = topViewController() ?: return null
        return suspendCancellableCoroutine { cont ->
            val delegate =
                NativeAdDelegate(
                    onLoaded = { nativeAd -> if (cont.isActive) cont.resume(nativeAd.toContent()) },
                    onFailed = { if (cont.isActive) cont.resume(null) },
                )
            lastDelegate = delegate
            val loader =
                GADAdLoader(
                    adUnitID = adUnitId,
                    rootViewController = vc,
                    adTypes = listOf(cocoapods.Google_Mobile_Ads_SDK.GADAdLoaderAdTypeNative),
                    options = null,
                )
            loader.delegate = delegate
            lastAdLoader = loader

            val request = GADRequest()
            if (mode == AdRequestMode.NON_PERSONALIZED) {
                // Force non-personalized via the documented `npa=1` extra (design D3). No precise location
                // is ever set on the request — data minimization (UU PDP / docs/06).
                val extras = GADExtras()
                extras.additionalParameters = mapOf<Any?, Any?>("npa" to "1")
                request.registerAdNetworkExtras(extras)
            }
            loader.loadRequest(request)
        }
    }

    override fun dispose() {
        lastAdLoader = null
        lastDelegate = null
    }

    private fun GADNativeAd.toContent(): NativeAdContent =
        NativeAdContent(
            headline = headline,
            body = body,
            advertiser = advertiser,
            callToAction = callToAction,
            iconUrl = icon?.imageURL?.absoluteString,
            handle = this,
        )
}

/**
 * `GADAdLoader` delegate bridging the ObjC native-ad callbacks to the suspending load. The two `adLoader`
 * overloads map the `adLoader:didReceiveNativeAd:` / `adLoader:didFailToReceiveAdWithError:` selectors.
 */
private class NativeAdDelegate(
    private val onLoaded: (GADNativeAd) -> Unit,
    private val onFailed: () -> Unit,
) : NSObject(), GADNativeAdLoaderDelegateProtocol, GADAdLoaderDelegateProtocol {
    override fun adLoader(
        adLoader: GADAdLoader,
        didReceiveNativeAd: GADNativeAd,
    ) {
        onLoaded(didReceiveNativeAd)
    }

    override fun adLoader(
        adLoader: GADAdLoader,
        didFailToReceiveAdWithError: NSError,
    ) {
        onFailed()
    }
}

/** The top-most presented view controller off the foreground key window (UMP form / ad-loader host). */
private fun topViewController(): UIViewController? {
    val keyWindow: UIWindow? =
        UIApplication.sharedApplication.windows
            .filterIsInstance<UIWindow>()
            .firstOrNull { it.isKeyWindow() }
            ?: UIApplication.sharedApplication.keyWindow
    var top = keyWindow?.rootViewController
    while (top?.presentedViewController != null) {
        top = top.presentedViewController
    }
    return top
}
