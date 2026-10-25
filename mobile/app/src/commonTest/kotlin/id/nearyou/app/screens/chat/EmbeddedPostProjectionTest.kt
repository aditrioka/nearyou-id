package id.nearyou.app.screens.chat

import id.nearyou.app.infra.supabaserealtime.EmbeddedPostSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure projection tests for the `chat-embedded-posts` mobile merge model — the edited-since-shared
 * banner gate over `editedAt` (`chat-embed-live-edit-banner` D1) and its row projection, plus the
 * redaction-precedence row projection (task 8.6). No Compose, no coroutines: deterministic functions.
 */
private const val SHARED_EDITED_AT = "2026-06-01T11:00:00Z"

class EmbeddedPostProjectionTest {
    private val snapshot =
        EmbeddedPostSnapshot(
            authorUsername = "raka",
            authorDisplayName = "Raka",
            content = "halo dunia",
            cityName = "Jakarta",
            createdAt = "2026-06-01T10:00:00Z",
            editedAt = SHARED_EDITED_AT,
        )

    private fun embedMessage(
        id: String,
        redacted: Boolean = false,
        postId: String? = "p1",
    ) = ChatMessage(
        id = id,
        senderId = "viewer",
        // embed-only message (content null) — the embed fields carry the shared post.
        content = null,
        createdAtIso = "2026-06-01T12:00:00Z",
        isRedacted = redacted,
        embeddedPostId = postId,
        embeddedPostSnapshot = snapshot,
    )

    // ---- edited-since-shared banner gate (editedAt, design D1) -------------------

    @Test
    fun banner_shown_when_live_post_edited_after_the_share() {
        assertTrue(editedSinceShared(sharedEditedAt = SHARED_EDITED_AT, liveEditedAt = "2026-06-02T09:00:00Z"))
    }

    @Test
    fun banner_shown_when_unedited_at_share_but_edited_since() {
        assertTrue(editedSinceShared(sharedEditedAt = null, liveEditedAt = "2026-06-02T09:00:00Z"))
    }

    @Test
    fun banner_hidden_when_no_edit_since_the_share() {
        assertFalse(editedSinceShared(sharedEditedAt = SHARED_EDITED_AT, liveEditedAt = SHARED_EDITED_AT))
    }

    @Test
    fun banner_hidden_when_equal_instants_are_formatted_differently() {
        // Compared as instants: a formatting difference between two equal instants never raises the banner.
        assertFalse(editedSinceShared(sharedEditedAt = "2026-06-01T11:00:00Z", liveEditedAt = "2026-06-01T11:00:00.000Z"))
    }

    @Test
    fun banner_hidden_when_both_unedited() {
        assertFalse(editedSinceShared(sharedEditedAt = null, liveEditedAt = null))
    }

    @Test
    fun banner_hidden_when_live_edit_state_unknown() {
        // A null live value means unknown (in flight / Unavailable) — never a false-positive banner from missing data.
        assertFalse(editedSinceShared(sharedEditedAt = SHARED_EDITED_AT, liveEditedAt = null))
    }

    @Test
    fun banner_hidden_when_either_value_is_unparseable() {
        assertFalse(editedSinceShared(sharedEditedAt = SHARED_EDITED_AT, liveEditedAt = "not-a-timestamp"))
        assertFalse(editedSinceShared(sharedEditedAt = "not-a-timestamp", liveEditedAt = "2026-06-02T09:00:00Z"))
    }

    @Test
    fun live_card_row_carries_the_banner_from_the_live_map() {
        val edited = chatMessageRows(listOf(embedMessage(id = "m1")), "viewer", mapOf("p1" to "2026-06-02T09:00:00Z"))
        assertTrue(edited.single().editedSinceShared)
        val unchanged = chatMessageRows(listOf(embedMessage(id = "m1")), "viewer", mapOf("p1" to SHARED_EDITED_AT))
        assertFalse(unchanged.single().editedSinceShared)
        // No live entry for the post (not read yet) → unknown → no banner.
        assertFalse(chatMessageRows(listOf(embedMessage(id = "m1")), "viewer").single().editedSinceShared)
    }

    @Test
    fun redacted_and_deleted_source_rows_never_carry_the_banner() {
        val live = mapOf("p1" to "2026-06-02T09:00:00Z")
        assertFalse(chatMessageRows(listOf(embedMessage(id = "m1", redacted = true)), "viewer", live).single().editedSinceShared)
        assertFalse(chatMessageRows(listOf(embedMessage(id = "m1", postId = null)), "viewer", live).single().editedSinceShared)
        // A post id with no snapshot renders no card, so it never carries the banner either.
        val noSnapshot = embedMessage(id = "m1").copy(embeddedPostSnapshot = null)
        assertFalse(chatMessageRows(listOf(noSnapshot), "viewer", live).single().editedSinceShared)
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
