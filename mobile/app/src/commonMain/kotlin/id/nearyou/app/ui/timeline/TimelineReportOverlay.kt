package id.nearyou.app.ui.timeline

import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import id.nearyou.app.data.report.ReportReasonCategory
import id.nearyou.app.ui.components.ReportDialog
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.profile_report_rate_limited
import id.nearyou.resources.generated.resources.profile_report_success_toast
import id.nearyou.resources.generated.resources.report_title_post
import id.nearyou.resources.generated.resources.signin_error_network
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The shared timeline report UI (`timeline-card-report-kebab`, design D5) — mounted once inside each
 * feed screen's root `Box` (the timeline screens are inset-free with no `Scaffold` of their own, so
 * there is no existing snackbar host). Renders, off a [TimelineReportController]'s hoisted state:
 *
 * - the shared [ReportDialog] (post title variant) while [reportingPostId] is non-null — the
 *   `AlertDialog` is a `Dialog` window, so where this composable sits in the tree does not affect
 *   its z-order;
 * - a [SnackbarHost] fed by the one-shot [reportMessage] (resolved via `stringResource`, cleared via
 *   [onMessageShown] once shown — the post-detail snackbar pattern). Hosts pass [modifier] to align
 *   the snackbar (bottom of the feed Box).
 */
@Composable
fun TimelineReportOverlay(
    reportingPostId: String?,
    reportMessage: TimelineReportMessage?,
    onSubmit: (ReportReasonCategory, String?) -> Unit,
    onDismiss: () -> Unit,
    onMessageShown: () -> Unit,
    dialogTestTag: String,
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
    val snackbarHostState = remember { SnackbarHostState() }
    val messageText = reportMessage?.let { stringResource(it.resource()) }
    LaunchedEffect(reportMessage) {
        if (messageText != null) {
            snackbarHostState.showSnackbar(messageText)
            onMessageShown()
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
