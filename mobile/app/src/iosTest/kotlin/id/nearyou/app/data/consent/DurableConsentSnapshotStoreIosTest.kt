package id.nearyou.app.data.consent

import platform.Foundation.NSUserDefaults
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Round-trip coverage of the iOS [DurableConsentSnapshotStore] (`NSUserDefaults`) — the iOS twin of the
 * Robolectric `DurableConsentSnapshotStoreTest` (#400): a snapshot written by one instance is read back
 * by a freshly constructed instance (process-restart simulation), and the presence marker distinguishes
 * a written all-false triple from "never written" (`null`). The store's 4 keys are cleared around every
 * test so the standard defaults carry no state between cases.
 */
class DurableConsentSnapshotStoreIosTest {
    @BeforeTest
    @AfterTest
    fun clearDefaults() {
        val defaults = NSUserDefaults.standardUserDefaults
        listOf("present", "analytics", "crash", "ads").forEach { defaults.removeObjectForKey("nearyou_consent_$it") }
    }

    @Test
    fun readBeforeWrite_returnsNull() {
        assertNull(DurableConsentSnapshotStore().read())
    }

    @Test
    fun writeThenReadFromNewInstance_returnsPersistedTriple() {
        DurableConsentSnapshotStore().write(ConsentSnapshot(analytics = true, crash = false, adsPersonalization = true))
        assertEquals(
            ConsentSnapshot(analytics = true, crash = false, adsPersonalization = true),
            DurableConsentSnapshotStore().read(),
        )
    }

    @Test
    fun writtenAllFalseTriple_isDistinguishedFromAbsent() {
        DurableConsentSnapshotStore().write(ConsentSnapshot(analytics = false, crash = false, adsPersonalization = false))
        assertEquals(
            ConsentSnapshot(analytics = false, crash = false, adsPersonalization = false),
            DurableConsentSnapshotStore().read(),
        )
    }
}
