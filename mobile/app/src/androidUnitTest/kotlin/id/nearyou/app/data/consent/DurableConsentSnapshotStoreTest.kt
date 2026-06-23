package id.nearyou.app.data.consent

import android.content.Context
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Round-trip coverage of the Android [DurableConsentSnapshotStore] (SharedPreferences) — the #198
 * durable-persistence contract: a snapshot written by one instance is read back by a freshly
 * constructed instance over the same prefs (a process-restart simulation), and the presence marker
 * distinguishes a written all-false triple from "never written" (`null`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DurableConsentSnapshotStoreTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @AfterTest
    fun clearPrefs() {
        context.getSharedPreferences("nearyou_consent_snapshot", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun readBeforeWrite_returnsNull() {
        assertNull(DurableConsentSnapshotStore(context).read())
    }

    @Test
    fun writeThenReadFromNewInstance_returnsPersistedTriple() {
        DurableConsentSnapshotStore(context).write(
            ConsentSnapshot(analytics = true, crash = false, adsPersonalization = true),
        )
        // A freshly constructed instance over the same SharedPreferences (process-restart simulation)
        // reads back the persisted triple — the durable-persistence contract that resolves #198, and the
        // mechanism by which a crash decline (crash = false) survives a cold start.
        val readBack = DurableConsentSnapshotStore(context).read()
        assertEquals(ConsentSnapshot(analytics = true, crash = false, adsPersonalization = true), readBack)
    }

    @Test
    fun writtenAllFalseTriple_isDistinguishedFromAbsent() {
        DurableConsentSnapshotStore(context).write(
            ConsentSnapshot(analytics = false, crash = false, adsPersonalization = false),
        )
        // The presence marker distinguishes a written all-false triple from "never written" (null).
        assertEquals(
            ConsentSnapshot(analytics = false, crash = false, adsPersonalization = false),
            DurableConsentSnapshotStore(context).read(),
        )
    }
}
