package id.nearyou.app.data.consent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Round-trip coverage of [InMemoryConsentSnapshotStore] — read-before-write is null; write then read echoes. */
class ConsentSnapshotStoreTest {
    @Test
    fun `read before any write returns null`() {
        assertNull(InMemoryConsentSnapshotStore().read())
    }

    @Test
    fun `write then read returns the written snapshot`() {
        val store = InMemoryConsentSnapshotStore()
        val snapshot = ConsentSnapshot(analytics = true, crash = true, adsPersonalization = false)
        store.write(snapshot)
        assertEquals(snapshot, store.read())
    }

    @Test
    fun `a second write overwrites the first`() {
        val store = InMemoryConsentSnapshotStore()
        store.write(ConsentSnapshot(analytics = false, crash = true, adsPersonalization = false))
        store.write(ConsentSnapshot(analytics = true, crash = false, adsPersonalization = true))
        assertEquals(ConsentSnapshot(analytics = true, crash = false, adsPersonalization = true), store.read())
    }
}
