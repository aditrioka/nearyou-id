package id.nearyou.app.push

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `mobile-push-message-handling` task 3.5 — the commonMain default-OFF
 * projection over the platform KV seam: default-OFF-when-unset + a write
 * round-trips to the next read. The platform actuals (DataStore / App-Group
 * UserDefaults) implement only the nullable KV; this default logic lives once
 * in commonMain, so testing it here covers both platforms.
 */
class NotificationContentPreferenceTest {
    private class InMemoryStore : NotificationContentPreferenceStore {
        var value: Boolean? = null

        override suspend fun read(): Boolean? = value

        override suspend fun write(v: Boolean) {
            value = v
        }
    }

    @Test
    fun defaultIsPrivateWhenUnset() =
        runTest {
            val pref = PersistedNotificationContentPreference(InMemoryStore())
            assertFalse(pref.previewEnabled(), "unset preference must default to OFF (private)")
        }

    @Test
    fun writeRoundTripsToRead() =
        runTest {
            val pref = PersistedNotificationContentPreference(InMemoryStore())
            pref.setPreviewEnabled(true)
            assertTrue(pref.previewEnabled(), "a write must be reflected by the next read")
            pref.setPreviewEnabled(false)
            assertFalse(pref.previewEnabled(), "an explicit OFF write is read back as OFF (not unset)")
        }
}
