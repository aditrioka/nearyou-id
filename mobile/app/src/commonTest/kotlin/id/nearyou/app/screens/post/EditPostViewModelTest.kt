package id.nearyou.app.screens.post

import id.nearyou.app.post.EditHistoryOutcome
import id.nearyou.app.post.FakePostEditFlow
import id.nearyou.app.post.PostEditFlow
import id.nearyou.app.post.PostEditOutcome
import id.nearyou.app.post.PostRefreshOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit coverage of [EditPostViewModel]: a success sets the one-shot `editedContent`; a `403` raises the
 * premium upsell (NOT an error); every other outcome maps to its distinct [EditPostError]; the client
 * gate blocks empty / over-limit submits with NO flow call; and the reactive gate attempts the PATCH with
 * no client premium pre-check (design D2). `viewModelScope` dispatches on `Dispatchers.Main` → an
 * [UnconfinedTestDispatcher] over an explicit scheduler runs the launches eagerly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditPostViewModelTest {
    private val scheduler = TestCoroutineScheduler()

    @BeforeTest
    fun setMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
    }

    @AfterTest
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun submitSuccess_setsEditedContent_andCallsFlowOnce() {
        val fake = FakePostEditFlow(editOutcome = PostEditOutcome.Success("isi baru"))
        val vm = EditPostViewModel(fake, "p1", "lama")
        vm.onContentChange("isi baru")
        vm.submit()
        scheduler.advanceUntilIdle()

        assertEquals("isi baru", vm.uiState.value.editedContent)
        assertEquals(1, fake.editCount)
        assertEquals("isi baru", fake.lastEditContent)
    }

    @Test
    fun premiumRequired_raisesUpsell_notError() {
        val vm = EditPostViewModel(FakePostEditFlow(editOutcome = PostEditOutcome.PremiumRequired), "p1", "lama")
        vm.onContentChange("baru")
        vm.submit()
        scheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value.showPremiumUpsell, "403 → the Premium upsell")
        assertNull(vm.uiState.value.error, "403 is NOT a generic error")
        assertNull(vm.uiState.value.editedContent)
    }

    @Test
    fun eachErrorOutcome_mapsToItsDistinctErrorField() {
        val cases =
            mapOf(
                PostEditOutcome.WindowExpired to EditPostError.WINDOW_EXPIRED,
                PostEditOutcome.NoChanges to EditPostError.NO_CHANGES,
                PostEditOutcome.ContentModerated to EditPostError.CONTENT_MODERATED,
                PostEditOutcome.Conflict to EditPostError.CONFLICT,
                PostEditOutcome.RateLimited(10L) to EditPostError.RATE_LIMITED,
                PostEditOutcome.NotFound to EditPostError.NOT_FOUND,
                PostEditOutcome.Network to EditPostError.NETWORK,
            )
        for ((outcome, expected) in cases) {
            val vm = EditPostViewModel(FakePostEditFlow(editOutcome = outcome), "p1", "lama")
            vm.onContentChange("baru")
            vm.submit()
            scheduler.advanceUntilIdle()
            assertEquals(expected, vm.uiState.value.error, "$outcome must map to $expected")
            assertNull(vm.uiState.value.editedContent, "$outcome is not a success")
        }
    }

    @Test
    fun emptyOrOverLimitContent_blocksSubmit_withNoFlowCall() {
        val emptyFake = FakePostEditFlow()
        val emptyVm = EditPostViewModel(emptyFake, "p1", "")
        emptyVm.submit()
        scheduler.advanceUntilIdle()
        assertEquals(0, emptyFake.editCount, "empty content issues no PATCH")

        val longFake = FakePostEditFlow()
        val longVm = EditPostViewModel(longFake, "p1", "lama")
        longVm.onContentChange("x".repeat(281))
        longVm.submit()
        scheduler.advanceUntilIdle()
        assertEquals(0, longFake.editCount, "over-limit content issues no PATCH")
        assertTrue(longVm.uiState.value.overLimit)
    }

    @Test
    fun submit_attemptsPatchWithNoEntitlementPreCheck() {
        // The reactive gate (design D2): the editor submits regardless of premium status — the 403 is the
        // gate, NOT a client entitlement read. Structurally guaranteed (no entitlement seam exists); the
        // PATCH being issued (editCount == 1) before the eventual PremiumRequired asserts it.
        val fake = FakePostEditFlow(editOutcome = PostEditOutcome.PremiumRequired)
        val vm = EditPostViewModel(fake, "p1", "lama")
        vm.onContentChange("baru")
        vm.submit()
        scheduler.advanceUntilIdle()
        assertEquals(1, fake.editCount, "the editor attempts the PATCH with no client premium pre-check")
    }

    @Test
    fun onPremiumUpsellDismissed_clearsFlag_preservesText() {
        val vm = EditPostViewModel(FakePostEditFlow(editOutcome = PostEditOutcome.PremiumRequired), "p1", "lama")
        vm.onContentChange("baru")
        vm.submit()
        scheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value.showPremiumUpsell)

        vm.onPremiumUpsellDismissed()
        assertTrue(!vm.uiState.value.showPremiumUpsell)
        assertEquals("baru", vm.uiState.value.content, "the editor text survives the dismissal")
    }

    @Test
    fun submit_isSingleFlight_sameFrameDoubleTapIssuesOnePatch() {
        // The 05-#10 double-tap invariant: a second submit() in the same frame (while the first PATCH is
        // still in flight, before recomposition disables "Simpan") must NOT launch a second PATCH.
        val gate = CompletableDeferred<Unit>()
        var editCount = 0
        val suspendingFlow =
            object : PostEditFlow {
                override suspend fun editPost(
                    postId: String,
                    content: String,
                ): PostEditOutcome {
                    editCount++
                    gate.await() // stay in flight until released
                    return PostEditOutcome.Success(content)
                }

                override suspend fun loadHistory(postId: String): EditHistoryOutcome = EditHistoryOutcome.Loaded(emptyList())

                override suspend fun refreshPost(postId: String): PostRefreshOutcome = PostRefreshOutcome.Unavailable
            }
        val vm = EditPostViewModel(suspendingFlow, "p1", "lama")
        vm.onContentChange("baru")
        vm.submit() // first: claims in-flight, editPost increments to 1, suspends on the gate
        vm.submit() // same-frame second: the live-isSubmitting guard returns → no second PATCH
        gate.complete(Unit)
        scheduler.advanceUntilIdle()
        assertEquals(1, editCount, "a same-frame double-tap issues exactly one PATCH (single-flight)")
        assertEquals("baru", vm.uiState.value.editedContent)
    }
}
