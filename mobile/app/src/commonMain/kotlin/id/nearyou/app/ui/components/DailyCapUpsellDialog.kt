package id.nearyou.app.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.cap_countdown_hours_minutes
import id.nearyou.resources.generated.resources.cap_countdown_minutes
import id.nearyou.resources.generated.resources.cap_dialog_title
import id.nearyou.resources.generated.resources.cta_activate_premium
import id.nearyou.resources.generated.resources.cta_close
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/** Test tag on the dialog surface — lets hosts assert show/dismiss. */
const val DAILY_CAP_DIALOG_TAG: String = "dailyCapDialog"

/** Test tag on the "Aktifkan Premium" confirm button. */
const val DAILY_CAP_DIALOG_PREMIUM_TAG: String = "dailyCapDialogPremium"

/** Test tag on the "Tutup" dismiss button. */
const val DAILY_CAP_DIALOG_CLOSE_TAG: String = "dailyCapDialogClose"

/**
 * The shared daily-cap upsell dialog (`mobile-cap-upsell-dialog`, mockup frame 18, docs/11 § 2.8): an
 * M3 [AlertDialog] titled "Batas harian tercapai" with a caller-supplied [body] (the reuse seam — frame
 * 18's caption declares this same modal pattern for the future post/reply/chat caps; the like
 * instantiation passes `post_detail_likes_cap_upsell`, the verbatim docs/03:187 copy), a filled
 * "Aktifkan Premium" confirm button (hoisted [onActivatePremium] — v1 hosts wire it to dismiss only;
 * the paywall destination is the deferred requirement tracked by issue #235), and a "Tutup" text
 * dismiss button. Scrim/back dismissal behaves as "Tutup".
 *
 * The countdown handed to [body] derives from the 429's [retryAfterSeconds] (`Retry-After` — the like
 * wire's only reset signal; ≤ 0 is floored to one minute so a proxy-stripped header never
 * flash-dismisses), renders "X j Y mnt" / "Y mnt" per frame 18, and **ticks down each minute** via
 * monotonic [delay] (docs/03:185 "realtime to the reset moment" at minute granularity — no wall-clock
 * platform API, so tests drive it with the compose test clock). Reaching zero auto-dismisses: the cap
 * has reset. The component holds no navigation reference and no rate-limit state of its own — show/hide
 * is the host's one-shot state (docs/11 § 2.2).
 */
@Composable
fun DailyCapUpsellDialog(
    retryAfterSeconds: Long,
    body: @Composable (countdown: String) -> String,
    onDismiss: () -> Unit,
    onActivatePremium: () -> Unit,
) {
    var remainingMinutes by remember(retryAfterSeconds) {
        mutableStateOf(capCountdownMinutes(retryAfterSeconds))
    }
    // The tick outlives recompositions that swap the lambda — read the CURRENT onDismiss at fire time.
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(retryAfterSeconds) {
        while (true) {
            delay(60_000)
            if (remainingMinutes > 1) {
                remainingMinutes -= 1
            } else {
                // The floored final minute elapsed — the cap has reset; close the dialog.
                currentOnDismiss()
                break
            }
        }
    }
    val split = capCountdown(remainingMinutes)
    val countdown =
        if (split.hours > 0) {
            stringResource(Res.string.cap_countdown_hours_minutes, split.hours, split.minutes)
        } else {
            stringResource(Res.string.cap_countdown_minutes, split.minutes)
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(DAILY_CAP_DIALOG_TAG),
        title = { Text(text = stringResource(Res.string.cap_dialog_title)) },
        text = { Text(text = body(countdown)) },
        confirmButton = {
            Button(
                onClick = onActivatePremium,
                modifier = Modifier.testTag(DAILY_CAP_DIALOG_PREMIUM_TAG),
            ) {
                Text(text = stringResource(Res.string.cta_activate_premium))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag(DAILY_CAP_DIALOG_CLOSE_TAG),
            ) {
                Text(text = stringResource(Res.string.cta_close))
            }
        },
    )
}
