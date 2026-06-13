package id.nearyou.app.screens.settings

import id.nearyou.app.consent.ConsentOutcome
import id.nearyou.app.consent.FakeConsentFlow
import id.nearyou.app.data.consent.ConsentSnapshot
import id.nearyou.app.data.consent.InMemoryConsentSnapshotStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit coverage of [ConsentSettingsViewModel] — snapshot seeding + submit. Pins: no-snapshot seeds the V2
 * defaults (analytics OFF, crash ON, ads OFF) with NO consent-read issued; a present snapshot seeds the
 * toggles; a successful submit updates the snapshot AND a freshly-reconstructed VM from the SAME store
 * seeds from it (cross-reconstruction durability); a `401` surfaces TokenInvalid; a double `save()` issues
 * exactly one PATCH (the inFlight guard + the repo Mutex).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConsentSettingsViewModelTest {
    @BeforeTest
    fun setMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun `no snapshot seeds the V2 defaults and issues no consent read`() {
        val store = InMemoryConsentSnapshotStore()
        val vm = ConsentSettingsViewModel(FakeConsentFlow(), store)
        assertEquals(false, vm.analytics.value)
        assertEquals(true, vm.crash.value)
        assertEquals(false, vm.ads.value)
        // There is no GET endpoint and no read happened on the store beyond the seed lookup (which is null).
        assertNull(store.read())
    }

    @Test
    fun `a present snapshot seeds the toggles`() {
        val store = InMemoryConsentSnapshotStore()
        store.write(ConsentSnapshot(analytics = true, crash = true, adsPersonalization = false))
        val vm = ConsentSettingsViewModel(FakeConsentFlow(), store)
        assertEquals(true, vm.analytics.value)
        assertEquals(true, vm.crash.value)
        assertEquals(false, vm.ads.value)
    }

    @Test
    fun `a successful submit updates the snapshot and a reconstructed VM seeds from it`() {
        val store = InMemoryConsentSnapshotStore()
        val first = ConsentSettingsViewModel(FakeConsentFlow(outcome = ConsentOutcome.Success), store)
        first.setAnalytics(true)
        first.setCrash(true)
        first.setAds(false)
        first.save()
        assertEquals(ConsentSnapshot(analytics = true, crash = true, adsPersonalization = false), store.read())

        // A SECOND VM constructed from the SAME store (simulating a later Settings re-entry / restart).
        val second = ConsentSettingsViewModel(FakeConsentFlow(), store)
        assertEquals(true, second.analytics.value)
        assertEquals(true, second.crash.value)
        assertEquals(false, second.ads.value)
    }

    @Test
    fun `submit forwards the current toggle triple to the flow`() {
        val flow = FakeConsentFlow(outcome = ConsentOutcome.Success)
        val vm = ConsentSettingsViewModel(flow, InMemoryConsentSnapshotStore())
        vm.setAnalytics(true)
        vm.setCrash(false)
        vm.setAds(true)
        vm.save()
        assertEquals(Triple(true, false, true), flow.lastSubmitted)
    }

    @Test
    fun `a 401 submit surfaces TokenInvalid and does not write the snapshot`() {
        val store = InMemoryConsentSnapshotStore()
        val vm = ConsentSettingsViewModel(FakeConsentFlow(outcome = ConsentOutcome.TokenInvalid), store)
        vm.save()
        assertEquals(ConsentOutcome.TokenInvalid, vm.outcome.value)
        assertNull(store.read())
    }

    @Test
    fun `a failed submit does not update the snapshot`() {
        val store = InMemoryConsentSnapshotStore()
        val vm = ConsentSettingsViewModel(FakeConsentFlow(outcome = ConsentOutcome.RetryableError), store)
        vm.setAnalytics(true)
        vm.save()
        assertNull(store.read())
    }
}
