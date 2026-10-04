package id.nearyou.app.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.cta_close
import id.nearyou.resources.generated.resources.premium_activating_body
import id.nearyou.resources.generated.resources.premium_activating_title
import org.jetbrains.compose.resources.stringResource

/** Test tag on the dialog surface. */
const val PREMIUM_ACTIVATING_DIALOG_TAG: String = "premiumActivatingDialog"

/** Test tag on the "Tutup" button. */
const val PREMIUM_ACTIVATING_DIALOG_CLOSE_TAG: String = "premiumActivatingDialogClose"

/**
 * The webhook-lag stand-in for every upsell dialog (`mobile-premium-entitlement`, #517): a purchase is
 * confirmed but the server still answered as Free, so instead of an upgrade pitch the viewer reads that
 * Premium is being activated and can try again shortly. The same M3 [AlertDialog] as the frame-18 cap
 * dialog, with a single "Tutup" (the confirm slot, which [AlertDialog] requires) and no paywall CTA.
 * Scrim/back dismissal behaves as "Tutup". Holds no navigation reference.
 */
@Composable
fun PremiumActivatingDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(PREMIUM_ACTIVATING_DIALOG_TAG),
        title = { Text(text = stringResource(Res.string.premium_activating_title)) },
        text = { Text(text = stringResource(Res.string.premium_activating_body)) },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(PREMIUM_ACTIVATING_DIALOG_CLOSE_TAG)) {
                Text(text = stringResource(Res.string.cta_close))
            }
        },
    )
}
