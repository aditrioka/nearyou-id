package id.nearyou.app.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.cta_activate_premium
import id.nearyou.resources.generated.resources.cta_close
import id.nearyou.resources.generated.resources.radius_upsell_body
import id.nearyou.resources.generated.resources.radius_upsell_title
import org.jetbrains.compose.resources.stringResource

/** Test tag on the dialog surface — lets hosts/screen tests assert show/dismiss. */
const val RADIUS_UPSELL_DIALOG_TAG: String = "radiusUpsellDialog"

/** Test tag on the "Aktifkan Premium" confirm button. */
const val RADIUS_UPSELL_DIALOG_PREMIUM_TAG: String = "radiusUpsellDialogPremium"

/** Test tag on the "Tutup" dismiss button. */
const val RADIUS_UPSELL_DIALOG_CLOSE_TAG: String = "radiusUpsellDialogClose"

/**
 * The Premium radius upsell (`mobile-nearby-radius-slider`): an M3 [AlertDialog] shown when a Free viewer
 * tries to pick a Premium-only Nearby radius (the slider snaps back to 20 km) OR when the server returns
 * a `radius_premium_only` 403. Unlike [DailyCapUpsellDialog] it carries **no countdown** — the radius gate
 * is not time-bounded (a separate, countdown-less surface; the cap dialog is the daily/like-cap idiom).
 * The "Aktifkan Premium" button routes to the paywall via the hoisted [onActivatePremium]; "Tutup" / scrim
 * dismisses. Holds no navigation reference; show/hide is the host's one-shot state (docs/11 § 2.2). All
 * copy via `stringResource` (zero literals).
 */
@Composable
fun RadiusPremiumUpsellDialog(
    onDismiss: () -> Unit,
    onActivatePremium: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(RADIUS_UPSELL_DIALOG_TAG),
        title = { Text(text = stringResource(Res.string.radius_upsell_title)) },
        text = { Text(text = stringResource(Res.string.radius_upsell_body)) },
        confirmButton = {
            Button(
                onClick = onActivatePremium,
                modifier = Modifier.testTag(RADIUS_UPSELL_DIALOG_PREMIUM_TAG),
            ) {
                Text(text = stringResource(Res.string.cta_activate_premium))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag(RADIUS_UPSELL_DIALOG_CLOSE_TAG),
            ) {
                Text(text = stringResource(Res.string.cta_close))
            }
        },
    )
}
