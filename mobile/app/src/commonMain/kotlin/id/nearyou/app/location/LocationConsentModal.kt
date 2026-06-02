package id.nearyou.app.location

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.cta_cancel
import id.nearyou.resources.generated.resources.location_consent_allow
import id.nearyou.resources.generated.resources.location_consent_body
import id.nearyou.resources.generated.resources.location_consent_title
import org.jetbrains.compose.resources.stringResource

/**
 * The UU-PDP location-consent rationale modal, surfaced by the Nearby gate (the [LocationGate]
 * orchestration) BEFORE the OS permission prompt — `openspec/specs/mobile-location` § "UU-PDP
 * location-consent rationale precedes the OS permission prompt". Every user-facing string is
 * `stringResource(Res.string.*)`; zero hardcoded literals (spec § "Consent-modal copy is fully
 * resource-backed").
 *
 * [onAccept] proceeds to the OS prompt (the gate then calls `LocationPermissionController.request()`);
 * [onDecline] (the dismiss button OR a scrim/back dismissal) leaves the user in the denial fallback
 * WITHOUT forcing the OS prompt.
 */
@Composable
fun LocationConsentModal(
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDecline,
        title = { Text(text = stringResource(Res.string.location_consent_title)) },
        text = { Text(text = stringResource(Res.string.location_consent_body)) },
        confirmButton = {
            TextButton(onClick = onAccept) {
                Text(text = stringResource(Res.string.location_consent_allow))
            }
        },
        dismissButton = {
            TextButton(onClick = onDecline) {
                Text(text = stringResource(Res.string.cta_cancel))
            }
        },
    )
}
