package id.nearyou.app.ui.components

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.cta_activate_premium
import id.nearyou.resources.generated.resources.cta_retry
import org.jetbrains.compose.resources.stringResource

/**
 * The action button of an inline Free gate (the hard read-cap state, the search and username `403` panels):
 * "Aktifkan Premium" firing [onActivatePremium] (tagged [premiumTag]) — or, while [premiumActivating] (a
 * purchase is confirmed but the server still answered Free, #517), "Coba lagi" firing [onRetry] (tagged
 * [retryTag]) so the buyer re-checks instead of being sent to buy again. The host picks the signal and the
 * body copy; this owns only the one swap every inline gate shares (docs/11 § 4 rule of three).
 */
@Composable
fun PremiumGateAction(
    premiumActivating: Boolean,
    onActivatePremium: () -> Unit,
    onRetry: () -> Unit,
    premiumTag: String,
    retryTag: String,
    modifier: Modifier = Modifier,
) {
    if (premiumActivating) {
        Button(onClick = onRetry, modifier = modifier.testTag(retryTag)) {
            Text(text = stringResource(Res.string.cta_retry))
        }
    } else {
        Button(onClick = onActivatePremium, modifier = modifier.testTag(premiumTag)) {
            Text(text = stringResource(Res.string.cta_activate_premium))
        }
    }
}
