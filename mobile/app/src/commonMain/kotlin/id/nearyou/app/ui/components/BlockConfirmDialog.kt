package id.nearyou.app.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.cta_block
import id.nearyou.resources.generated.resources.cta_cancel
import id.nearyou.resources.generated.resources.profile_block_confirm_body
import id.nearyou.resources.generated.resources.profile_block_confirm_title
import org.jetbrains.compose.resources.stringResource

/**
 * The single shared block confirmation dialog (an M3 [AlertDialog]) gating EVERY block action
 * (mobile-block-from-content, design D3) — extracted verbatim from the profile `BlockConfirmDialog`
 * and reused by the profile kebab AND the post-detail post-header + reply-row kebabs (mirroring
 * `ReportDialog`'s target-agnostic extraction). Renders the canonical `docs/03` §Block User UX copy:
 * the "Blokir @{username}?" title + the mutual-invisibility body, a destructive (error-colored)
 * "Blokir" confirm, and a secondary "Batal" dismiss. All strings via `Res.string.*` — NO hardcoded
 * UI literals. [username] is the target's public handle (display identity); the author UUID is never
 * passed to or rendered by this dialog.
 */
@Composable
fun BlockConfirmDialog(
    username: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    testTag: String,
) {
    AlertDialog(
        modifier = Modifier.testTag(testTag),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.profile_block_confirm_title, username)) },
        text = { Text(stringResource(Res.string.profile_block_confirm_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(Res.string.cta_block), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cta_cancel)) }
        },
    )
}
