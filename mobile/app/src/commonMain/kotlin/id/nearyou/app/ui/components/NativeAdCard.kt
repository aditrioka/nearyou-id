package id.nearyou.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import id.nearyou.app.infra.admob.NativeAdContent
import id.nearyou.app.infra.admob.NativeAdSurface
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.native_ad_label
import org.jetbrains.compose.resources.stringResource

const val NATIVE_AD_CARD_TAG: String = "nativeAdCard"
const val NATIVE_AD_LABEL_TAG: String = "nativeAdLabel"

/**
 * The timeline native-ad card (mobile-admob-ads-foundation) — reuses [PostCard]'s [OutlinedCard] geometry
 * (16.dp horizontal / 6.dp vertical outer, 16.dp inner) so an ad reads as a sibling of a post card, with a
 * localized "Bersponsor" label that distinguishes sponsored content (docs/01 § Placement). The ad creative
 * is drawn by [NativeAdSurface] — the platform `NativeAdView` / `GADNativeAdView` binding fenced in
 * `:infra:admob` (impression + click tracking), so this card never imports a Google SDK type (invariant #16).
 */
@Composable
fun NativeAdCard(
    content: NativeAdContent,
    modifier: Modifier = Modifier,
) {
    OutlinedCard(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).testTag(NATIVE_AD_CARD_TAG),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(Res.string.native_ad_label),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag(NATIVE_AD_LABEL_TAG),
            )
            Spacer(Modifier.height(8.dp))
            NativeAdSurface(content = content, modifier = Modifier.fillMaxWidth())
        }
    }
}
