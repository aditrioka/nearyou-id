package id.nearyou.app.ui.timeline

import id.nearyou.app.data.report.ReportOutcome
import id.nearyou.app.data.report.ReportReasonCategory
import id.nearyou.app.data.report.ReportSubmitter
import id.nearyou.app.data.report.ReportTargetType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The one-shot timeline report-result message (rendered as a snackbar by `TimelineActionsOverlay`).
 *  Deliberately parallel to the post-detail `PostDetailReportMessage` (that enum is post-detail-scoped;
 *  see design D3): SUCCESS covers Submitted AND Duplicate — anti-enumeration, `docs/03`:234. */
enum class TimelineReportMessage {
    /** Report 204 (Submitted) OR 409 duplicate_report (Duplicate) → the SAME "report submitted" copy. */
    SUCCESS,

    /** Report 429 → the report rate-limit copy. */
    RATE_LIMITED,

    /** A report network/transport failure → a generic retryable "try again" copy. */
    FAILED,
}

/**
 * Maps a [ReportOutcome] to its one-shot timeline message (the anti-enumeration mapping, design D3):
 * [ReportOutcome.Submitted] AND [ReportOutcome.Duplicate] → [TimelineReportMessage.SUCCESS] (identical —
 * no "already reported" wording). Pure + exhaustive (no wildcard) so the mapping is unit-testable.
 */
fun timelineReportMessage(outcome: ReportOutcome): TimelineReportMessage =
    when (outcome) {
        ReportOutcome.Submitted, ReportOutcome.Duplicate -> TimelineReportMessage.SUCCESS
        is ReportOutcome.RateLimited -> TimelineReportMessage.RATE_LIMITED
        ReportOutcome.NetworkError -> TimelineReportMessage.FAILED
    }

/**
 * The ONE shared timeline-card report lifecycle (`timeline-card-report-kebab`) — the Nearby, Global,
 * and Following feed ViewModels each hold their own instance (per-surface), mirroring the
 * [InlineLikeController] pattern, so the dialog-target + one-shot-message state machine exists exactly
 * once. Compose-free and commonTest-unit-testable.
 *
 * Both signals are **one-shot state, not event streams** (docs/11 § 2.2):
 * - [reportingPostId] — non-null while the shared `ReportDialog` should be shown for that post;
 *   cleared by [onDialogDismissed] or on submit.
 * - [reportMessage] — the submission result (anti-enumeration mapping via [timelineReportMessage]);
 *   cleared by [onMessageShown] after the host renders it.
 *
 * Submission goes through the shared [ReportSubmitter] seam with `target_type = "post"` — no second
 * report path. Authorship gating lives in the host VM ([reportingPostId] is only ever set for posts the
 * host offered the action on).
 */
class TimelineReportController(
    private val reportSubmitter: ReportSubmitter,
    private val scope: CoroutineScope,
) {
    private val _reportingPostId = MutableStateFlow<String?>(null)

    /** Non-null while the report dialog should be shown, carrying the target post id. */
    val reportingPostId: StateFlow<String?> = _reportingPostId.asStateFlow()

    private val _reportMessage = MutableStateFlow<TimelineReportMessage?>(null)

    /** Non-null while the one-shot result message should be shown. */
    val reportMessage: StateFlow<TimelineReportMessage?> = _reportMessage.asStateFlow()

    /** The card kebab's "Laporkan" tap — opens the dialog targeting [postId]. */
    fun onReportClicked(postId: String) {
        _reportingPostId.value = postId
    }

    /** Dismiss the report dialog without submitting (clears the target one-shot). */
    fun onDialogDismissed() {
        _reportingPostId.value = null
    }

    /** Submits the report for the currently-targeted post via the shared [ReportSubmitter]
     *  (`target_type = "post"`). Closes the dialog immediately; the result surfaces as the one-shot
     *  [reportMessage]. A no-op if no target is set (defensive). */
    fun onSubmitted(
        category: ReportReasonCategory,
        note: String?,
    ) {
        val postId = _reportingPostId.value ?: return
        _reportingPostId.value = null
        scope.launch {
            val outcome = reportSubmitter.submit(ReportTargetType.POST, postId, category, note)
            _reportMessage.value = timelineReportMessage(outcome)
        }
    }

    /** Clears the one-shot [reportMessage] after the host has shown it (no re-fire on recomposition). */
    fun onMessageShown() {
        _reportMessage.value = null
    }
}
