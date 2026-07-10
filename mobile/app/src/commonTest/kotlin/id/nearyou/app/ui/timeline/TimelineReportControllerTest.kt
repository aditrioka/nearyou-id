package id.nearyou.app.ui.timeline

import id.nearyou.app.data.report.FakeReportSubmitter
import id.nearyou.app.data.report.ReportOutcome
import id.nearyou.app.data.report.ReportReasonCategory
import id.nearyou.app.data.report.ReportTargetType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit coverage of [TimelineReportController] — the ONE shared timeline-card report lifecycle all three
 * feed ViewModels delegate to (`timeline-card-report-kebab` design D3). Pins, per the spec delta: the
 * dialog-target one-shot (open / dismiss-without-submit / cleared on submit), the anti-enumeration
 * outcome→message mapping (Submitted AND Duplicate → the SAME [TimelineReportMessage.SUCCESS];
 * RateLimited / NetworkError typed), the `target_type = "post"` wire submission through the shared
 * [FakeReportSubmitter] seam, and the one-shot message clear ([TimelineReportController.onMessageShown]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TimelineReportControllerTest {
    @Test
    fun reportClicked_opensTheDialogTarget_andDismissClearsItWithoutSubmitting() =
        runTest {
            val submitter = FakeReportSubmitter()
            val controller = TimelineReportController(submitter, this)

            controller.onReportClicked("p1")
            assertEquals("p1", controller.reportingPostId.value)

            controller.onDialogDismissed()
            advanceUntilIdle()
            assertNull(controller.reportingPostId.value)
            assertEquals(0, submitter.submitCount, "dismiss submits nothing")
        }

    @Test
    fun submit_closesTheDialogImmediately_andSubmitsTargetTypePostWithTheTappedPostId() =
        runTest {
            val submitter = FakeReportSubmitter(ReportOutcome.Submitted)
            val controller = TimelineReportController(submitter, this)

            controller.onReportClicked("p1")
            controller.onSubmitted(ReportReasonCategory.SPAM, "catatan")
            assertNull(controller.reportingPostId.value, "the dialog closes before the outcome resolves")
            advanceUntilIdle()

            assertEquals(1, submitter.submitCount)
            assertEquals(ReportTargetType.POST, submitter.lastTarget)
            assertEquals("p1", submitter.lastTargetId)
            assertEquals(ReportReasonCategory.SPAM, submitter.lastCategory)
            assertEquals("catatan", submitter.lastNote)
        }

    @Test
    fun submittedAndDuplicate_mapToTheSameSuccessMessage() =
        runTest {
            // Anti-enumeration (docs/03:234): a reporter cannot distinguish a prior report.
            for (outcome in listOf(ReportOutcome.Submitted, ReportOutcome.Duplicate)) {
                val controller = TimelineReportController(FakeReportSubmitter(outcome), this)
                controller.onReportClicked("p1")
                controller.onSubmitted(ReportReasonCategory.SPAM, null)
                advanceUntilIdle()
                assertEquals(TimelineReportMessage.SUCCESS, controller.reportMessage.value, "$outcome")
            }
        }

    @Test
    fun rateLimitedAndNetworkError_mapToTheirTypedMessages() =
        runTest {
            val rateLimited = TimelineReportController(FakeReportSubmitter(ReportOutcome.RateLimited(3600)), this)
            rateLimited.onReportClicked("p1")
            rateLimited.onSubmitted(ReportReasonCategory.OTHER, null)
            advanceUntilIdle()
            assertEquals(TimelineReportMessage.RATE_LIMITED, rateLimited.reportMessage.value)

            val failed = TimelineReportController(FakeReportSubmitter(ReportOutcome.NetworkError), this)
            failed.onReportClicked("p2")
            failed.onSubmitted(ReportReasonCategory.OTHER, null)
            advanceUntilIdle()
            assertEquals(TimelineReportMessage.FAILED, failed.reportMessage.value)
        }

    @Test
    fun messageShown_clearsTheOneShot_andSubmitWithoutTargetIsANoOp() =
        runTest {
            val submitter = FakeReportSubmitter()
            val controller = TimelineReportController(submitter, this)

            controller.onReportClicked("p1")
            controller.onSubmitted(ReportReasonCategory.SPAM, null)
            advanceUntilIdle()
            controller.onMessageShown()
            assertNull(controller.reportMessage.value, "shown message is cleared (no re-fire)")

            // No target set → defensive no-op (no second submission).
            controller.onSubmitted(ReportReasonCategory.SPAM, null)
            advanceUntilIdle()
            assertEquals(1, submitter.submitCount)
            assertNull(controller.reportMessage.value)
        }
}
