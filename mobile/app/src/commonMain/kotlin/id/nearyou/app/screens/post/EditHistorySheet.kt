package id.nearyou.app.screens.post

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import id.nearyou.app.post.EditHistoryOutcome
import id.nearyou.app.post.EditVersionDto
import id.nearyou.app.post.PostEditFlow
import id.nearyou.app.ui.components.postDateLabel
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.cta_close
import id.nearyou.resources.generated.resources.cta_retry
import id.nearyou.resources.generated.resources.post_edit_history_empty
import id.nearyou.resources.generated.resources.post_edit_history_title
import id.nearyou.resources.generated.resources.signin_error_network
import id.nearyou.resources.generated.resources.timeline_loading
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/** Test tag on the "Riwayat edit" overlay root. */
const val EDIT_HISTORY_SHEET_TAG: String = "editHistorySheet"

/** Test tag on the history-error retry control. */
const val EDIT_HISTORY_RETRY_TAG: String = "editHistoryRetry"

/** Test tag on the empty-history copy. */
const val EDIT_HISTORY_EMPTY_TAG: String = "editHistoryEmpty"

/**
 * The screen-local "Riwayat edit" modal (the `mobile-post-editing` history surface, design D3) — a
 * full-screen overlay `Surface` over post-detail (NOT a NavKey; the back stack holds screen-level
 * destinations only). Loads `GET /api/v1/posts/{postId}/edits` via [PostEditFlow.loadHistory] and renders
 * the chronological "Versi ke-N" versions (content + version label + edit time ONLY — never a location,
 * matching the endpoint's no-location contract). States: loading / loaded list / empty / error+retry.
 * Dismissed via the "Tutup" close action ([onDismiss]); all copy via `stringResource`.
 */
@Composable
fun EditHistorySheet(
    postId: String,
    onDismiss: () -> Unit,
) {
    val flow = koinInject<PostEditFlow>()
    var outcome by remember { mutableStateOf<EditHistoryOutcome?>(null) }
    var retryKey by remember { mutableStateOf(0) }
    // Keyed load — the retry simply bumps the key to re-run (mirrors the post-detail replies retry).
    LaunchedEffect(postId, retryKey) {
        outcome = null
        outcome = flow.loadHistory(postId)
    }

    Surface(
        modifier = Modifier.fillMaxSize().testTag(EDIT_HISTORY_SHEET_TAG),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().padding(16.dp)) {
            Column(modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text(text = stringResource(Res.string.cta_close))
                }
                Text(
                    text = stringResource(Res.string.post_edit_history_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            when (val current = outcome) {
                null -> HistoryLoading()
                is EditHistoryOutcome.Loaded ->
                    if (current.versions.isEmpty()) {
                        HistoryEmpty()
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(
                                items = current.versions,
                                key = { it.versionLabel },
                                contentType = { "edit_version" },
                            ) { version -> VersionCard(version) }
                        }
                    }
                EditHistoryOutcome.Network -> HistoryError(onRetry = { retryKey++ })
            }
        }
    }
}

/** One history version: the "Versi ke-N" label (rendered verbatim from the wire) + the content snapshot +
 *  the edit-time date treatment. NO location is shown (the wire carries none — the spatial-fuzzing invariant). */
@Composable
private fun VersionCard(version: EditVersionDto) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                text = version.versionLabel,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = version.content,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                text = postDateLabel(version.editedAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun HistoryLoading() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text(
            text = stringResource(Res.string.timeline_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun HistoryEmpty() {
    Text(
        text = stringResource(Res.string.post_edit_history_empty),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp).testTag(EDIT_HISTORY_EMPTY_TAG),
    )
}

@Composable
private fun HistoryError(onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(Res.string.signin_error_network),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        OutlinedButton(
            onClick = onRetry,
            modifier = Modifier.padding(top = 12.dp).testTag(EDIT_HISTORY_RETRY_TAG),
        ) {
            Text(text = stringResource(Res.string.cta_retry))
        }
    }
}
