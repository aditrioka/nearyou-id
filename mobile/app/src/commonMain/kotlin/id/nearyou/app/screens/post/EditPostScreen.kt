package id.nearyou.app.screens.post

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import id.nearyou.app.post.PostEditFlow
import id.nearyou.app.screens.routing.EditPostRoute
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.cta_activate_premium
import id.nearyou.resources.generated.resources.cta_cancel
import id.nearyou.resources.generated.resources.cta_close
import id.nearyou.resources.generated.resources.cta_save_edit
import id.nearyou.resources.generated.resources.post_create_char_counter
import id.nearyou.resources.generated.resources.post_edit_content_placeholder
import id.nearyou.resources.generated.resources.post_edit_error_conflict
import id.nearyou.resources.generated.resources.post_edit_error_moderated
import id.nearyou.resources.generated.resources.post_edit_error_no_changes
import id.nearyou.resources.generated.resources.post_edit_error_not_found
import id.nearyou.resources.generated.resources.post_edit_error_rate_limited
import id.nearyou.resources.generated.resources.post_edit_error_window
import id.nearyou.resources.generated.resources.post_edit_premium_body
import id.nearyou.resources.generated.resources.post_edit_premium_title
import id.nearyou.resources.generated.resources.signin_error_network
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/** Test tag on the edit content field. */
const val EDIT_POST_FIELD_TAG: String = "editPostField"

/** Test tag on the "Simpan" save action. */
const val EDIT_POST_SAVE_TAG: String = "editPostSave"

/** Test tag on the back/close affordance. */
const val EDIT_POST_BACK_TAG: String = "editPostBack"

/** Test tag on the inline error banner. */
const val EDIT_POST_ERROR_TAG: String = "editPostError"

/** Test tag on the "Aktifkan Premium" upsell CTA (the reactive 403 gate). */
const val EDIT_POST_PREMIUM_CTA_TAG: String = "editPostPremiumCta"

/**
 * The edit-post surface ([EditPostRoute], the `mobile-post-editing` editor) — opened from the post-detail
 * Edit affordance, pushed onto the ROOT back stack (overlaying the tab bar, like `PostDetailRoute`). A
 * prefilled content-only editor (NO location control) with a live `N/280` Unicode-code-point counter and a
 * "Simpan" action; on a `200` it invokes [onPostEdited] with the updated content (the caller pops to detail
 * + refreshes). Reactive premium gate (design D2): a `403 premium_required` raises the "Aktifkan Premium"
 * upsell — no client premium flag is read. Every other failure ([EditPostError]) shows an inline banner with
 * the editor text preserved. All copy via `stringResource` (zero literals); no PII rendered or logged.
 */
@Composable
fun EditPostScreen(
    route: EditPostRoute,
    onBack: () -> Unit,
    onPostEdited: (newContent: String) -> Unit,
) {
    val flow = koinInject<PostEditFlow>()
    val viewModel = viewModel { EditPostViewModel(flow, route.postId, route.initialContent) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Success → return to detail carrying the new content (one-shot; the screen pops, so no explicit clear).
    LaunchedEffect(uiState.editedContent) {
        uiState.editedContent?.let(onPostEdited)
    }

    if (uiState.showPremiumUpsell) {
        EditPremiumUpsellDialog(
            onDismiss = viewModel::onPremiumUpsellDismissed,
            // v1: no paywall yet — the CTA dismisses (mirrors the search PremiumGate + timeline cap upsell).
            onActivatePremium = viewModel::onPremiumUpsellDismissed,
        )
    }

    Scaffold(
        topBar = {
            EditTopBar(
                onBack = onBack,
                onSave = viewModel::submit,
                saveEnabled = uiState.submitEnabled,
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding()
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = uiState.content,
                onValueChange = viewModel::onContentChange,
                placeholder = { Text(text = stringResource(Res.string.post_edit_content_placeholder)) },
                enabled = !uiState.isSubmitting,
                minLines = 4,
                modifier = Modifier.fillMaxWidth().testTag(EDIT_POST_FIELD_TAG),
            )
            Text(
                text = stringResource(Res.string.post_create_char_counter, uiState.charCount),
                style = MaterialTheme.typography.labelMedium,
                color =
                    if (uiState.overLimit) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth(),
            )
            val errorMessage = uiState.error?.let { editErrorMessage(it) }
            if (errorMessage != null) {
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth().testTag(EDIT_POST_ERROR_TAG),
                )
            }
        }
    }
}

/** The edit top bar: a "Tutup" back affordance + the "Simpan" save action (disabled while empty / over-limit
 *  / in-flight). Applies its own status-bar inset (the screen owns its Scaffold, root-stack overlay). */
@Composable
private fun EditTopBar(
    onBack: () -> Unit,
    onSave: () -> Unit,
    saveEnabled: Boolean,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        TextButton(onClick = onBack, modifier = Modifier.testTag(EDIT_POST_BACK_TAG)) {
            Text(text = stringResource(Res.string.cta_close))
        }
        TextButton(onClick = onSave, enabled = saveEnabled, modifier = Modifier.testTag(EDIT_POST_SAVE_TAG)) {
            Text(text = stringResource(Res.string.cta_save_edit))
        }
    }
}

/** The reactive `403 premium_required` upsell — an "Aktifkan Premium" CTA (reuses the shared
 *  `cta_activate_premium` string; v1 has no paywall, so both buttons dismiss). */
@Composable
private fun EditPremiumUpsellDialog(
    onDismiss: () -> Unit,
    onActivatePremium: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(Res.string.post_edit_premium_title)) },
        text = { Text(text = stringResource(Res.string.post_edit_premium_body)) },
        confirmButton = {
            TextButton(onClick = onActivatePremium, modifier = Modifier.testTag(EDIT_POST_PREMIUM_CTA_TAG)) {
                Text(text = stringResource(Res.string.cta_activate_premium))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(Res.string.cta_cancel))
            }
        },
    )
}

/** Maps each [EditPostError] to its inline Bahasa Indonesia message (network reuses the shared copy). */
@Composable
private fun editErrorMessage(error: EditPostError): String =
    when (error) {
        EditPostError.WINDOW_EXPIRED -> stringResource(Res.string.post_edit_error_window)
        EditPostError.NO_CHANGES -> stringResource(Res.string.post_edit_error_no_changes)
        EditPostError.CONTENT_MODERATED -> stringResource(Res.string.post_edit_error_moderated)
        EditPostError.CONFLICT -> stringResource(Res.string.post_edit_error_conflict)
        EditPostError.RATE_LIMITED -> stringResource(Res.string.post_edit_error_rate_limited)
        EditPostError.NOT_FOUND -> stringResource(Res.string.post_edit_error_not_found)
        EditPostError.NETWORK -> stringResource(Res.string.signin_error_network)
    }
