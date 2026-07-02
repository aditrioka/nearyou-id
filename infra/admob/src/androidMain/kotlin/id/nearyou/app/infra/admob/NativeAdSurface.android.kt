package id.nearyou.app.infra.admob

import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdView

/**
 * Android [NativeAdSurface] — draws a `NativeAdView` and registers the headline / body / CTA asset views so
 * the Google SDK tracks the impression + click on the live native ad (AdMob policy, design D2). When the
 * handle is not a live [NativeAd] (a commonTest/Robolectric fake), it falls back to plain Compose text of
 * the vendor-free fields so the slot + "Bersponsor" label still render + assert in tests.
 */
@Composable
actual fun NativeAdSurface(
    content: NativeAdContent,
    modifier: Modifier,
) {
    val nativeAd = content.handle as? NativeAd
    if (nativeAd == null) {
        // Test / no-handle path: render the vendor-free fields directly (no NativeAdView to register).
        Column(modifier = modifier) {
            content.headline?.let { BasicText(it) }
            content.body?.let { BasicText(it) }
            content.callToAction?.let { BasicText(it) }
        }
        return
    }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val adView = NativeAdView(ctx)
            val container =
                LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams =
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        )
                }

            val headlineView = TextView(ctx)
            val bodyView = TextView(ctx)
            val ctaView = Button(ctx)
            container.addView(headlineView)
            container.addView(bodyView)
            container.addView(ctaView)
            adView.addView(container)

            adView.headlineView = headlineView
            adView.bodyView = bodyView
            adView.callToActionView = ctaView
            adView
        },
        update = { adView ->
            (adView.headlineView as? TextView)?.text = nativeAd.headline
            (adView.bodyView as? TextView)?.apply { text = nativeAd.body.orEmpty() }
            (adView.callToActionView as? Button)?.text = nativeAd.callToAction.orEmpty()
            // Registers the asset views with the SDK — impression + click tracking on the live ad.
            adView.setNativeAd(nativeAd)
        },
    )
}
