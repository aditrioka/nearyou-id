package id.nearyou.app.screens.username

import id.nearyou.app.profile.FakeProfileFlow
import id.nearyou.app.profile.ProfileOutcome
import id.nearyou.app.username.FakeUsernameFlow
import id.nearyou.app.username.UsernameChangeOutcome
import id.nearyou.app.username.UsernameCheckOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit coverage of [UsernameCustomizationViewModel]: the on-entry self-Premium resolution (Premium →
 * editor; Free → gate; null id → editor), the 500 ms debounce coalescing rapid keystrokes into one
 * `check`, and the confirm-gated submit (confirm fires the change + raises the one-shot; dismiss issues
 * nothing). Mirrors `ProfileViewModelTest`: a shared [UnconfinedTestDispatcher] is installed as `Main`
 * and passed to `runTest`, and a `backgroundScope` collector activates the `WhileSubscribed` uiState
 * share so `uiState.value` reflects the produced state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UsernameCustomizationViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setMainDispatcher() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    private fun vm(
        usernameFlow: FakeUsernameFlow = FakeUsernameFlow(),
        profileOutcome: ProfileOutcome = selfProfile(),
        selfId: String? = "self-id",
    ): UsernameCustomizationViewModel =
        UsernameCustomizationViewModel(
            flow = usernameFlow,
            profileFlow = FakeProfileFlow(profileOutcome = profileOutcome),
            selfUserIdProvider = FakeSelfUserIdProvider(selfId),
        )

    @Test
    fun onEntry_premiumSelfRead_rendersTheEditorWithTheCurrentHandle() =
        runTest(dispatcher) {
            val viewModel = vm(profileOutcome = selfProfile(username = "oldname", isPremium = true))
            backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.status is UsernameStatus.Editing)
            assertEquals("oldname", viewModel.uiState.value.currentUsername)
        }

    @Test
    fun onEntry_freeSelfRead_rendersThePremiumGate() =
        runTest(dispatcher) {
            val viewModel = vm(profileOutcome = selfProfile(isPremium = false))
            backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()
            assertEquals(UsernameStatus.PremiumGate, viewModel.uiState.value.status)
        }

    @Test
    fun onEntry_missingSelfId_degradesToTheEditor() =
        runTest(dispatcher) {
            val viewModel = vm(selfId = null)
            backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.status is UsernameStatus.Editing, "a missing id degrades to the editor")
        }

    @Test
    fun onEntry_selfReadFailure_degradesToTheEditor() =
        runTest(dispatcher) {
            // The VM's NotFound/NetworkError arm (distinct from the null-id early return) must degrade to
            // the editor (reactive 403 backstops correctness), never an error wall or a stuck Resolving.
            val viewModel = vm(profileOutcome = ProfileOutcome.NetworkError)
            backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.status is UsernameStatus.Editing, "a self-read failure degrades to the editor")
        }

    @Test
    fun probePath_premiumGate_rendersTheGate() =
        runTest(dispatcher) {
            // A Premium user whose PROBE returns 403 (e.g. a downgrade race) → the gate, reached via the
            // probe path (not just the change path or the on-entry read).
            val fake = FakeUsernameFlow(checkOutcomes = listOf(UsernameCheckOutcome.CheckPremiumGate))
            val viewModel = vm(usernameFlow = fake)
            backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()
            viewModel.onCandidateChange("newhandle")
            advanceUntilIdle()
            assertEquals(UsernameStatus.PremiumGate, viewModel.uiState.value.status)
        }

    @Test
    fun debounce_coalescesRapidKeystrokesIntoOneProbe() =
        runTest(dispatcher) {
            val fake = FakeUsernameFlow(checkOutcomes = listOf(UsernameCheckOutcome.Available(true)))
            val viewModel = vm(usernameFlow = fake)
            backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()
            viewModel.onCandidateChange("abcde")
            viewModel.onCandidateChange("abcdef")
            viewModel.onCandidateChange("abcdefg")
            advanceUntilIdle()
            assertEquals(1, fake.checkCalls.size, "one probe for the settled candidate, not one per keystroke: ${fake.checkCalls}")
            assertEquals("abcdefg", fake.checkCalls.single())
        }

    @Test
    fun submit_confirmFiresTheChangeAndRaisesTheOneShot() =
        runTest(dispatcher) {
            val fake = FakeUsernameFlow(changeOutcome = UsernameChangeOutcome.Success("newhandle"))
            val viewModel = vm(usernameFlow = fake)
            backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()
            viewModel.onCandidateChange("newhandle")
            advanceUntilIdle()
            viewModel.onSubmitClick()
            assertTrue(viewModel.uiState.value.confirmVisible, "the confirm modal is shown")
            viewModel.onConfirmChange()
            advanceUntilIdle()
            assertEquals(listOf("newhandle"), fake.changeCalls)
            assertTrue(viewModel.uiState.value.changed, "Success raises the changed one-shot")
            viewModel.onChangedShown()
            assertFalse(viewModel.uiState.value.changed, "the one-shot clears")
        }

    @Test
    fun submit_dismissIssuesNothing() =
        runTest(dispatcher) {
            val fake = FakeUsernameFlow()
            val viewModel = vm(usernameFlow = fake)
            backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()
            viewModel.onCandidateChange("newhandle")
            advanceUntilIdle()
            viewModel.onSubmitClick()
            viewModel.onDismissConfirm()
            advanceUntilIdle()
            assertTrue(fake.changeCalls.isEmpty(), "Batal issues no change request")
            assertFalse(viewModel.uiState.value.confirmVisible)
        }
}
