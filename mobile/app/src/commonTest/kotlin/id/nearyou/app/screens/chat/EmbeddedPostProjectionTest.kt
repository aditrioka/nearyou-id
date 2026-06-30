package id.nearyou.app.screens.chat

import id.nearyou.app.infra.supabaserealtime.EmbeddedPostSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure projection tests for the `chat-embedded-posts` mobile merge model — the edited-since-shared
 * banner gate (task 8.3) and the redaction-precedence row projection (task 8.6). No Compose, no
 * coroutines: these are deterministic functions of the inputs.
 */
class EmbeddedPostProjectionTest {
    private val snapshot =
        EmbeddedPostSnapshot(
            authorUsername = "raka",
            authorDisplayName = "Raka",
            content = "halo dunia",
            cityName = "Jakarta",
            createdAt = "2026-06-01T10:00:00Z",
            editedAt = null,
        )

    private fun embedMessage(
        id: String,
        redacted: Boolean = false,
        postId: String? = "p1",
        anchor: String? = null,
    ) = ChatMessage(
        id = id,
        senderId = "viewer",
        // embed-only message (content null) — the embed fields carry the shared post.
        content = null,
        createdAtIso = "2026-06-01T12:00:00Z",
        isRedacted = redacted,
        embeddedPostId = postId,
        embeddedPostSnapshot = snapshot,
        embeddedPostEditId = anchor,
    )

    // ---- 8.3 edited-since-shared banner gate -----------------------------------

    @Test
    fun banner_shown_when_anchor_differs_from_live_latest_edit() {
        assertTrue(editedSinceShared(anchorEditId = "edit-1", liveLatestEditId = "edit-2"))
    }

    @Test
    fun banner_hidden_when_anchor_equals_live_latest_edit() {
        assertFalse(editedSinceShared(anchorEditId = "edit-1", liveLatestEditId = "edit-1"))
    }

    @Test
    fun banner_hidden_when_both_unedited() {
        assertFalse(editedSinceShared(anchorEditId = null, liveLatestEditId = null))
    }

    @Test
    fun banner_shown_when_unedited_at_share_but_edited_since() {
        assertTrue(editedSinceShared(anchorEditId = null, liveLatestEditId = "edit-9"))
    }

    @Test
    fun banner_hidden_when_live_edit_unknown() {
        // A null live signal means "not fetched" — never a false-positive banner from missing data.
        assertFalse(editedSinceShared(anchorEditId = "edit-1", liveLatestEditId = null))
    }

    // ---- 8.6 redaction precedence ----------------------------------------------

    @Test
    fun embed_only_message_not_redacted_carries_the_card() {
        val rows = chatMessageRows(merged = listOf(embedMessage(id = "m1")), viewerId = "viewer")
        val row = rows.single()
        assertTrue(row.hasEmbeddedPost)
        assertEquals(snapshot, row.embeddedPostSnapshot)
        assertEquals("p1", row.embeddedPostId)
    }

    @Test
    fun redacted_embed_message_suppresses_the_card() {
        // A redacted message (redacted_at set) drops the embed fields from the row — the placeholder
        // renders, NOT the context card (the precedence keys on redaction, not on a null content).
        val rows = chatMessageRows(merged = listOf(embedMessage(id = "m1", redacted = true)), viewerId = "viewer")
        val row = rows.single()
        assertTrue(row.isRedacted)
        assertFalse(row.hasEmbeddedPost)
        assertNull(row.embeddedPostSnapshot)
        assertNull(row.embeddedPostId)
        assertNull(row.embeddedPostEditId)
    }

    @Test
    fun deleted_source_post_still_carries_snapshot_with_null_post_id() {
        // Hard-deleted source post: snapshot present, embeddedPostId null → the "post telah dihapus"
        // state (the card renders deleted + non-tappable; the row keeps the snapshot, drops the id).
        val rows = chatMessageRows(merged = listOf(embedMessage(id = "m1", postId = null)), viewerId = "viewer")
        val row = rows.single()
        assertTrue(row.hasEmbeddedPost) // snapshot present → a card renders (in its deleted state)
        assertNull(row.embeddedPostId)
        assertEquals(snapshot, row.embeddedPostSnapshot)
    }
}
