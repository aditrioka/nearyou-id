package id.nearyou.app.screens.settings

import id.nearyou.app.profile.FakeProfileFlow
import id.nearyou.app.profile.ProfileOutcome
import id.nearyou.app.profile.UserProfile
import id.nearyou.app.screens.username.FakeSelfUserIdProvider
import id.nearyou.app.screens.username.selfProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

private val NOW = Instant.parse("2026-10-19T12:00:00Z")

private fun profile(
    isPremium: Boolean,
    lastChangedAt: Instant?,
): UserProfile = (selfProfile(username = "dewi.kuliner", isPremium = isPremium, usernameLastChangedAt = lastChangedAt) as ProfileOutcome.Loaded).profile

/**
 * Coverage of the Settings "Ganti username" entry state (`mobile-premium-username` § "The Settings entry shows
 * the proactive cooldown and the downgrade banner"): the pure [usernameEntryState] matrix (#333 cooldown, #335
 * retained) and the [SettingsUsernameEntryViewModel] read lifecycle — fail-safe to `Open`, a failed re-read
 * keeps the last state, and a resume during an in-flight read issues no second read.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsUsernameEntryViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setMainDispatcher() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun projection_premiumInsideTheCooldown_isCooldownWithRoundedUpDays() {
        assertEquals(UsernameEntryState.Cooldown(14), usernameEntryState(profile(true, NOW - 16.days), false, NOW))
        assertEquals(UsernameEntryState.Cooldown(1), usernameEntryState(profile(true, NOW - 29.days - 1.hours), false, NOW))
    }

    @Test
    fun projection_premiumAtOrPastThirtyDaysOrNeverChanged_isOpen() {
        assertEquals(UsernameEntryState.Open, usernameEntryState(profile(true, NOW - 30.days), false, NOW))
        assertEquals(UsernameEntryState.Open, usernameEntryState(profile(true, null), false, NOW))
    }

    @Test
    fun projection_freeWhoCustomized_isRetainedWithTheHandle_andFreeWhoNeverDid_isOpen() {
        assertEquals(UsernameEntryState.Retained("dewi.kuliner"), usernameEntryState(profile(false, NOW - 3.days), false, NOW))
        // Retained is not time-bound: a Free user who customized long ago still keeps the handle.
        assertEquals(UsernameEntryState.Retained("dewi.kuliner"), usernameEntryState(profile(false, NOW - 400.days), false, NOW))
        assertEquals(UsernameEntryState.Open, usernameEntryState(profile(false, null), false, NOW))
    }

    @Test
    fun projection_billingRetry_countsAsPremium_viaTheEffectivePremiumFlag() {
        // isPremium is premium_active only; the hide-distance read's premium also counts billing-retry.
        assertEquals(UsernameEntryState.Cooldown(14), usernameEntryState(profile(false, NOW - 16.days), true, NOW))
        assertEquals(UsernameEntryState.Open, usernameEntryState(profile(false, NOW - 40.days), true, NOW), "no retained banner")
    }

    @Test
    fun vm_loadsTheSelfProfileOnInit() =
        runTest(dispatcher) {
            val flow = FakeProfileFlow(profileOutcome = selfProfile(isPremium = true, usernameLastChangedAt = NOW - 16.days))
            val viewModel = SettingsUsernameEntryViewModel(flow, FakeSelfUserIdProvider("self-id"), now = { NOW })
            backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()
            assertEquals("self-id", flow.loadedUserId)
            assertEquals(UsernameEntryState.Cooldown(14), viewModel.uiState.value)
        }

    @Test
    fun vm_reProjectsWhenTheEffectivePremiumReadLands() =
        runTest(dispatcher) {
            // The hide-distance seed read lands after the self read: a billing-retry user first projects as
            // Retained (Free), then as Cooldown once the effective-Premium flag arrives — no second self read.
            val premium = MutableStateFlow(false)
            val flow = FakeProfileFlow(profileOutcome = selfProfile(isPremium = false, usernameLastChangedAt = NOW - 16.days))
            val viewModel = SettingsUsernameEntryViewModel(flow, FakeSelfUserIdProvider(), premium, now = { NOW })
            backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()
            assertEquals(UsernameEntryState.Retained("oldname"), viewModel.uiState.value)
            premium.value = true
            advanceUntilIdle()
            assertEquals(UsernameEntryState.Cooldown(14), viewModel.uiState.value)
            assertEquals(1, flow.loadCalls)
        }

    @Test
    fun vm_failedRead_missingSelfId_andUnboundSeam_allStayOpen() =
        runTest(dispatcher) {
            val failed = SettingsUsernameEntryViewModel(FakeProfileFlow(ProfileOutcome.NetworkError), FakeSelfUserIdProvider(), now = { NOW })
            val noId = FakeProfileFlow()
            val missingId = SettingsUsernameEntryViewModel(noId, FakeSelfUserIdProvider(null), now = { NOW })
            val unbound = SettingsUsernameEntryViewModel(profileFlow = null, selfUserIdProvider = null, now = { NOW })
            listOf(failed, missingId, unbound).forEach { v -> backgroundScope.launch { v.uiState.collect {} } }
            advanceUntilIdle()
            assertEquals(UsernameEntryState.Open, failed.uiState.value)
            assertEquals(UsernameEntryState.Open, missingId.uiState.value)
            assertEquals(0, noId.loadCalls, "no self id → no read")
            assertEquals(UsernameEntryState.Open, unbound.uiState.value)
        }

    @Test
    fun vm_aFailedReRead_keepsTheLastState() =
        runTest(dispatcher) {
            val flow =
                FakeProfileFlow(
                    profileOutcome = selfProfile(username = "dewi.kuliner", isPremium = false, usernameLastChangedAt = NOW - 3.days),
                )
            val viewModel = SettingsUsernameEntryViewModel(flow, FakeSelfUserIdProvider(), now = { NOW })
            backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()
            flow.profileOutcome = ProfileOutcome.NetworkError
            viewModel.refresh()
            advanceUntilIdle()
            assertEquals(2, flow.loadCalls)
            assertEquals(UsernameEntryState.Retained("dewi.kuliner"), viewModel.uiState.value)
        }

    @Test
    fun vm_aResumeDuringTheInFlightRead_isANoOp_andALaterResumeReReads() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            val flow = FakeProfileFlow(profileOutcome = selfProfile(isPremium = true)).apply { loadGate = gate }
            val viewModel = SettingsUsernameEntryViewModel(flow, FakeSelfUserIdProvider(), now = { NOW })
            backgroundScope.launch { viewModel.uiState.collect {} }
            viewModel.refresh() // the first ON_RESUME lands while the init read is suspended
            assertEquals(1, flow.loadCalls, "no second read while one is in flight")

            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(UsernameEntryState.Open, viewModel.uiState.value)

            // Back from a successful change: the re-read now sees the fresh last-changed time.
            flow.profileOutcome = selfProfile(isPremium = true, usernameLastChangedAt = NOW)
            viewModel.refresh()
            advanceUntilIdle()
            assertEquals(2, flow.loadCalls)
            assertEquals(UsernameEntryState.Cooldown(30), viewModel.uiState.value)
        }
}
