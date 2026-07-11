package id.nearyou.app.ui.timeline

import id.nearyou.app.data.block.BlockOutcome
import id.nearyou.app.data.block.FakeBlockSubmitter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit coverage of [TimelineBlockController] — the ONE shared timeline-card block lifecycle all three
 * feed ViewModels delegate to (`timeline-card-block-kebab` design D3/D4). Pins, per the spec delta:
 * the dialog-target one-shot (open / dismiss-without-submit / cleared on confirm), the confirm →
 * exactly one [FakeBlockSubmitter] submission with the target author UUID, the outcome→message
 * mapping with the removal signal (`Blocked` → removal fired + SUCCESS; `RateLimited`/`NetworkError`
 * → typed message + NO removal), and the one-shot message clear ([TimelineBlockController.onMessageShown]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TimelineBlockControllerTest {
    @Test
    fun blockClicked_opensTheDialogTarget_andDismissClearsItWithoutSubmitting() =
        runTest {
            val submitter = FakeBlockSubmitter()
            val removed = mutableListOf<String>()
            val controller = TimelineBlockController(submitter, this) { removed += it }

            controller.onBlockClicked("author-1", "raka.jkt")
            assertEquals(TimelineBlockTarget("author-1", "raka.jkt"), controller.blockTarget.value)

            controller.onDialogDismissed()
            advanceUntilIdle()
            assertNull(controller.blockTarget.value)
            assertEquals(0, submitter.submitCount, "dismiss submits nothing")
            assertEquals(emptyList(), removed, "dismiss removes nothing")
        }

    @Test
    fun confirm_closesTheDialogImmediately_submitsTheAuthorUuid_andBlockedFiresRemovalThenSuccess() =
        runTest {
            val submitter = FakeBlockSubmitter(BlockOutcome.Blocked)
            val removed = mutableListOf<String>()
            val controller = TimelineBlockController(submitter, this) { removed += it }

            controller.onBlockClicked("author-1", "raka.jkt")
            controller.onConfirmed()
            assertNull(controller.blockTarget.value, "the dialog closes before the outcome resolves")
            advanceUntilIdle()

            assertEquals(1, submitter.submitCount)
            assertEquals("author-1", submitter.lastUserId)
            assertEquals(listOf("author-1"), removed, "Blocked fires the removal signal with the author UUID")
            assertEquals(TimelineBlockMessage.SUCCESS, controller.blockMessage.value)
        }

    @Test
    fun rateLimitedAndNetworkError_mapToTheirTypedMessages_andFireNoRemoval() =
        runTest {
            for (
            (outcome, expected) in
            listOf(
                BlockOutcome.RateLimited(3600) to TimelineBlockMessage.RATE_LIMITED,
                BlockOutcome.NetworkError to TimelineBlockMessage.FAILED,
            )
            ) {
                val removed = mutableListOf<String>()
                val controller = TimelineBlockController(FakeBlockSubmitter(outcome), this) { removed += it }
                controller.onBlockClicked("author-1", "raka.jkt")
                controller.onConfirmed()
                advanceUntilIdle()
                assertEquals(expected, controller.blockMessage.value, "$outcome")
                assertEquals(emptyList(), removed, "$outcome fires no removal (the feed stays unchanged)")
            }
        }

    @Test
    fun messageShown_clearsTheOneShot_andConfirmWithoutTargetIsANoOp() =
        runTest {
            val submitter = FakeBlockSubmitter()
            val controller = TimelineBlockController(submitter, this) {}

            controller.onBlockClicked("author-1", "raka.jkt")
            controller.onConfirmed()
            advanceUntilIdle()
            controller.onMessageShown()
            assertNull(controller.blockMessage.value, "shown message is cleared (no re-fire)")

            // No target set → defensive no-op (no second submission).
            controller.onConfirmed()
            advanceUntilIdle()
            assertEquals(1, submitter.submitCount)
            assertNull(controller.blockMessage.value)
        }
}
