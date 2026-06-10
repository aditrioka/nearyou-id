package id.nearyou.app.screens.notifications

import id.nearyou.app.notifications.NotificationsOutcome
import id.nearyou.app.notifications.fakeNotification
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure coverage of the [notificationsUiState] projection (design D8) — the outcome→state mapping the
 * screen renders, exercised without a Compose UI runner (mirroring `GlobalTimelineUiStateTest`).
 * Exhaustive over [NotificationsOutcome] + the in-flight flag; also asserts the projected rows carry no
 * PII (no `actor_user_id` / `target_id` UUID) while keeping the non-PII `body_data` excerpt.
 */
class NotificationsUiStateTest {
    private val oneRow = listOf(fakeNotification())

    @Test
    fun `in-flight maps to Loading and wins over any prior outcome`() {
        assertEquals(NotificationsUiState.Loading, notificationsUiState(outcome = null, isInitialLoad = true))
        assertEquals(
            NotificationsUiState.Loading,
            notificationsUiState(NotificationsOutcome.Loaded(oneRow, null), isInitialLoad = true),
        )
    }

    @Test
    fun `null outcome while not in-flight maps to Loading`() {
        assertEquals(NotificationsUiState.Loading, notificationsUiState(outcome = null, isInitialLoad = false))
    }

    @Test
    fun `loaded non-empty maps to Content`() {
        val state = notificationsUiState(NotificationsOutcome.Loaded(oneRow, "tok"), isInitialLoad = false)
        assertEquals(1, assertIs<NotificationsUiState.Content>(state).rows.size)
    }

    @Test
    fun `loaded empty maps to Empty`() {
        assertEquals(
            NotificationsUiState.Empty,
            notificationsUiState(NotificationsOutcome.Loaded(emptyList(), null), isInitialLoad = false),
        )
    }

    @Test
    fun `NetworkError and Error both map to the Error state`() {
        assertEquals(NotificationsUiState.Error, notificationsUiState(NotificationsOutcome.NetworkError, isInitialLoad = false))
        assertEquals(NotificationsUiState.Error, notificationsUiState(NotificationsOutcome.Error, isInitialLoad = false))
    }

    @Test
    fun `row read flag derives from read_at`() {
        val rows =
            assertIs<NotificationsUiState.Content>(
                notificationsUiState(
                    NotificationsOutcome.Loaded(
                        listOf(
                            fakeNotification(id = "unread", readAt = null),
                            fakeNotification(id = "read", readAt = "2026-05-31T11:00:00Z"),
                        ),
                        null,
                    ),
                    isInitialLoad = false,
                ),
            ).rows
        assertFalse(rows.first { it.id == "unread" }.read)
        assertTrue(rows.first { it.id == "read" }.read)
    }

    @Test
    fun `excerpt is extracted from body_data and absent when the key is missing`() {
        val withExcerpt = fakeNotification(id = "a", bodyData = buildJsonObject { put("post_excerpt", "halo dunia") })
        val noExcerpt = fakeNotification(id = "b", bodyData = buildJsonObject {})
        val rows =
            assertIs<NotificationsUiState.Content>(
                notificationsUiState(NotificationsOutcome.Loaded(listOf(withExcerpt, noExcerpt), null), isInitialLoad = false),
            ).rows
        assertEquals("halo dunia", rows.first { it.id == "a" }.excerpt)
        assertNull(rows.first { it.id == "b" }.excerpt, "a missing excerpt key yields no excerpt (base copy)")
    }

    @Test
    fun `projected content carries no actor or target UUID`() {
        val piiRow =
            fakeNotification(
                actorUserId = "ACTOR-SENTINEL",
                targetId = "TARGET-SENTINEL",
                bodyData = buildJsonObject { put("post_excerpt", "halo dunia") },
            )
        val rendered =
            notificationsUiState(NotificationsOutcome.Loaded(listOf(piiRow), null), isInitialLoad = false).toString()
        assertFalse(rendered.contains("ACTOR-SENTINEL"), "actor id must not reach the UI state: $rendered")
        assertFalse(rendered.contains("TARGET-SENTINEL"), "target id must not reach the UI state: $rendered")
        assertTrue(rendered.contains("halo dunia"), "the non-PII body_data excerpt is retained: $rendered")
    }
}
