package id.nearyou.app.timeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the spec § "SessionIdProvider yields a stable per-process session id": the id is captured
 * ONCE at construction, so two reads return the identical string (a regression to a per-call
 * `Uuid.random()` would fail this — and would defeat the backend's per-session soft cap).
 */
class SessionIdProviderTest {
    @Test
    fun `two reads return the identical captured id`() {
        val provider = SessionIdProvider()
        assertEquals(provider.sessionId, provider.sessionId, "the id must be captured once, not regenerated per access")
    }

    @Test
    fun `session id matches the backend-accepted regex`() {
        val id = SessionIdProvider().sessionId
        assertTrue(
            Regex("^[A-Za-z0-9-]{1,64}$").matches(id),
            "session id must match ^[A-Za-z0-9-]{1,64}$ (else the backend uses the no-session bucket): $id",
        )
    }
}
