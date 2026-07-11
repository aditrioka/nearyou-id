package id.nearyou.app.ui.timeline

import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import id.nearyou.app.data.report.ReportReasonCategory
import id.nearyou.app.ui.components.BlockConfirmDialog
import id.nearyou.app.ui.components.ReportDialog
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.profile_block_rate_limited
import id.nearyou.resources.generated.resources.profile_block_success_toast
import id.nearyou.resources.generated.resources.profile_report_rate_limited
import id.nearyou.resources.generated.resources.profile_report_success_toast
import id.nearyou.resources.generated.resources.report_title_post
import id.nearyou.resources.generated.resources.signin_error_network
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The shared timeline kebab-actions UI (`timeline-card-report-kebab` design D5, grown by
 * `timeline-card-block-kebab` design D6 — formerly `TimelineReportOverlay`) — mounted once inside each
 * feed screen's root `Box` (the timeline screens are inset-free with no `Scaffold` of their own, so
 * there is no existing snackbar host). Renders, off the hoisted per-feed controller state:
 *
 * - the shared [ReportDialog] (post title variant) while [reportingPostId] is non-null;
 * - the shared [BlockConfirmDialog] (canonical docs/03 copy) while [blockTarget] is non-null — both
 *   `AlertDialog`s are `Dialog` windows, so where this composable sits in the tree does not affect
 *   their z-order;
 * - ONE [SnackbarHost] fed by the report AND block one-shot messages (each resolved via
 *   `stringResource`, cleared via its own shown-callback once shown — the post-detail snackbar
 *   pattern; a single host so the two actions never stack duplicate hosts in the feed Box). Hosts
 *   pass [modifier] to align the snackbar (bottom of the feed Box).
 */
@Composable
fun TimelineActionsOverlay(
    reportingPostId: String?,
    reportMessage: TimelineReportMessage?,
    onSubmit: (ReportReasonCategory, String?) -> Unit,
    onDismiss: () -> Unit,
    onMessageShown: () -> Unit,
    dialogTestTag: String,
    blockTarget: TimelineBlockTarget?,
    blockMessage: TimelineBlockMessage?,
    onBlockConfirm: () -> Unit,
    onBlockDismiss: () -> Unit,
    onBlockMessageShown: () -> Unit,
    blockDialogTestTag: String,
    modifier: Modifier = Modifier,
) {
    if (reportingPostId != null) {
        ReportDialog(
            title = Res.string.report_title_post,
            testTag = dialogTestTag,
            onSubmit = onSubmit,
            onDismiss = onDismiss,
        )
    }
    if (blockTarget != null) {
        BlockConfirmDialog(
            username = blockTarget.authorUsername,
            onConfirm = onBlockConfirm,
            onDismiss = onBlockDismiss,
            testTag = blockDialogTestTag,
        )
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val reportText = reportMessage?.let { stringResource(it.resource()) }
    LaunchedEffect(reportMessage) {
        if (reportText != null) {
            snackbarHostState.showSnackbar(reportText)
            onMessageShown()
        }
    }
    val blockText = blockMessage?.let { stringResource(it.resource()) }
    LaunchedEffect(blockMessage) {
        if (blockText != null) {
            snackbarHostState.showSnackbar(blockText)
            onBlockMessageShown()
        }
    }
    SnackbarHost(hostState = snackbarHostState, modifier = modifier)
}

/** Maps the one-shot [TimelineReportMessage] to its `:shared:resources` string — the SAME copy the
 *  post-detail report surface uses (success toast / rate-limit / network retry). */
private fun TimelineReportMessage.resource(): StringResource =
    when (this) {
        TimelineReportMessage.SUCCESS -> Res.string.profile_report_success_toast
        TimelineReportMessage.RATE_LIMITED -> Res.string.profile_report_rate_limited
        TimelineReportMessage.FAILED -> Res.string.signin_error_network
    }

/** Maps the one-shot [TimelineBlockMessage] to its `:shared:resources` string — the SAME canonical
 *  block copy the profile + post-detail surfaces use (docs/03 §Block User UX). */
private fun TimelineBlockMessage.resource(): StringResource =
    when (this) {
        TimelineBlockMessage.SUCCESS -> Res.string.profile_block_success_toast
        TimelineBlockMessage.RATE_LIMITED -> Res.string.profile_block_rate_limited
        TimelineBlockMessage.FAILED -> Res.string.signin_error_network
    }
