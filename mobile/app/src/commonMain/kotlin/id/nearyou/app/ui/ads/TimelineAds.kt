package id.nearyou.app.ui.ads

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import id.nearyou.app.ads.AdFeedController
import id.nearyou.app.infra.admob.NativeAdContent
import id.nearyou.app.ui.components.NativeAdCard
import org.koin.compose.koinInject

/**
 * Wires the timeline native-ad placement into a feed screen with a single call: it resolves the shared
 * [AdFeedController], triggers the once-per-session [AdFeedController.prepare] (config fetch + UMP gate),
 * and exposes the resolved [frequency] (null = no ads) + a [Slot] composable to hand to
 * `PostFeedList(adFrequency = …, adSlot = { ads.Slot(it) })`. mobile-admob-ads-foundation.
 */
class TimelineAds internal constructor(
    private val controller: AdFeedController,
    val frequency: Int?,
) {
    /** Loads + caches the slot's native ad off the composition path, then renders the "Bersponsor" card. */
    @Composable
    fun Slot(slotKey: String) {
        val content by produceState<NativeAdContent?>(null, slotKey) { value = controller.loadAd(slotKey) }
        content?.let { NativeAdCard(content = it) }
    }
}

@Composable
fun rememberTimelineAds(): TimelineAds {
    val controller = koinInject<AdFeedController>()
    LaunchedEffect(controller) { controller.prepare() }
    val frequency by controller.frequency.collectAsState()
    return remember(controller, frequency) { TimelineAds(controller, frequency) }
}
