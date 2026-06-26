package id.nearyou.app.infra.admob

import android.app.Activity
import android.content.Context
import android.os.Bundle
import com.google.ads.mediation.admob.AdMobAdapter
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/** Seam for the foreground [Activity] the UMP consent form needs; `:mobile:app` wires its activity holder. */
fun interface AdsActivityProvider {
    fun current(): Activity?
}

/**
 * The Android [AdProvider] actual — the ONLY code that touches the Google Mobile Ads + UMP SDK on Android
 * (invariant #16; the dependency is `implementation`-scoped in `:infra:admob` so it never reaches
 * `:mobile:app`'s compile classpath). Every SDK callback is wrapped fail-safe: a consent or load failure
 * resolves to [ConsentState.ERROR] / a null ad, never a thrown vendor exception.
 *
 * Data minimization (UU PDP / docs/06): the ad request NEVER carries a precise device location — no
 * `location` is set on the [AdRequest], even though the app holds Precise permission for small-radius
 * Nearby. (GMA dropped the publisher `setLocation` API; not setting it is the correct + only path.)
 */
class AndroidAdProvider(
    private val context: Context,
    private val activityProvider: AdsActivityProvider,
) : AdProvider {
    private val initialized = AtomicBoolean(false)

    @Volatile
    private var consent: ConsentState = ConsentState.UNKNOWN

    @Volatile
    private var lastAd: NativeAd? = null

    override suspend fun initialize() {
        if (initialized.compareAndSet(false, true)) {
            MobileAds.initialize(context) { /* init complete; status ignored — load is fail-safe */ }
        }
    }

    override suspend fun requestConsent(): ConsentState {
        val activity =
            activityProvider.current() ?: run {
                consent = ConsentState.ERROR
                return ConsentState.ERROR
            }
        val consentInformation: ConsentInformation = UserMessagingPlatform.getConsentInformation(context)
        val params = ConsentRequestParameters.Builder().build()
        val resolved =
            suspendCancellableCoroutine { cont ->
                consentInformation.requestConsentInfoUpdate(
                    activity,
                    params,
                    {
                        // Info updated — load + show the form IF UMP says it is required, then resolve.
                        UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) {
                            if (cont.isActive) {
                                cont.resume(
                                    if (consentInformation.canRequestAds()) {
                                        ConsentState.OBTAINED
                                    } else {
                                        ConsentState.REQUIRED
                                    },
                                )
                            }
                        }
                    },
                    {
                        if (cont.isActive) cont.resume(ConsentState.ERROR)
                    },
                )
            }
        consent = resolved
        return resolved
    }

    override fun consentState(): ConsentState = consent

    override suspend fun loadNativeAd(
        adUnitId: String,
        mode: AdRequestMode,
    ): NativeAdContent? =
        suspendCancellableCoroutine { cont ->
            val adLoader =
                AdLoader.Builder(context, adUnitId)
                    .forNativeAd { nativeAd ->
                        lastAd?.destroy()
                        lastAd = nativeAd
                        if (cont.isActive) cont.resume(nativeAd.toContent())
                    }
                    .withAdListener(
                        object : AdListener() {
                            override fun onAdFailedToLoad(error: LoadAdError) {
                                if (cont.isActive) cont.resume(null)
                            }
                        },
                    )
                    .build()

            val request = AdRequest.Builder()
            if (mode == AdRequestMode.NON_PERSONALIZED) {
                // Force non-personalized via the documented `npa=1` AdMob network extra (belt-and-suspenders
                // over UMP, design D3). No precise location is ever added — data minimization (UU PDP).
                val extras = Bundle().apply { putString("npa", "1") }
                request.addNetworkExtrasBundle(AdMobAdapter::class.java, extras)
            }
            adLoader.loadAd(request.build())
        }

    override fun dispose() {
        lastAd?.destroy()
        lastAd = null
    }

    private fun NativeAd.toContent(): NativeAdContent =
        NativeAdContent(
            headline = headline,
            body = body,
            advertiser = advertiser,
            callToAction = callToAction,
            iconUrl = icon?.uri?.toString(),
            handle = this,
        )
}
