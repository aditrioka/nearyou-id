package id.nearyou.app.infra.supabaserealtime

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * `ChatMessageInbound` embedded-fields decode tests (task 8.5, the D7 decode path):
 *  - a populated `embedded_post_snapshot` JsonElement decodes at the infra boundary to the plain,
 *    vendor-free [EmbeddedPostSnapshot] model carrying the author/content/city fields and NO coordinate;
 *  - a plain (null-embed) payload yields null embed fields;
 *  - [ChatMessageInbound] exposes the three `embedded_*` fields as vendor-free types and has NO
 *    `redactionReason` property (the latter is structural — there is no such property to reference; the
 *    no-vendor-import invariant is enforced module-wide by CLAUDE.md invariant #16, the supabase-kt SDK
 *    being confined to [SupabaseChatRealtimeSubscriber]).
 */
@OptIn(ExperimentalUuidApi::class)
class ChatMessageInboundEmbedTest {
    private val id = "11111111-1111-1111-1111-111111111111"
    private val conv = "22222222-2222-2222-2222-222222222222"
    private val sender = "33333333-3333-3333-3333-333333333333"
    private val post = "44444444-4444-4444-4444-444444444444"
    private val edit = "55555555-5555-5555-5555-555555555555"

    private fun payload(
        snapshotJson: String?,
        embeddedPostId: String? = post,
        embeddedPostEditId: String? = edit,
    ) = RealtimeBroadcastPayload(
        id = id,
        conversationId = conv,
        senderId = sender,
        content = null,
        createdAt = "2026-06-01T12:00:00Z",
        embeddedPostId = embeddedPostId,
        embeddedPostSnapshot = snapshotJson?.let { Json.parseToJsonElement(it) },
        embeddedPostEditId = embeddedPostEditId,
    )

    @Test
    fun populatedSnapshotDecodesToVendorFreeModelWithNoCoordinate() {
        val snapshot =
            """
            {"authorUsername":"raka","authorDisplayName":"Raka Pratama","content":"halo dunia",
             "cityName":"Jakarta","createdAt":"2026-06-01T10:00:00Z","editedAt":"2026-06-01T11:00:00Z"}
            """.trimIndent()
        val inbound = payload(snapshotJson = snapshot).toInbound()

        assertEquals(Uuid.parse(post), inbound.embeddedPostId)
        assertEquals(Uuid.parse(edit), inbound.embeddedPostEditId)
        val decoded: EmbeddedPostSnapshot? = inbound.embeddedPostSnapshot
        assertEquals("raka", decoded?.authorUsername)
        assertEquals("Raka Pratama", decoded?.authorDisplayName)
        assertEquals("halo dunia", decoded?.content)
        assertEquals("Jakarta", decoded?.cityName)
        assertEquals("2026-06-01T10:00:00Z", decoded?.createdAt)
        assertEquals("2026-06-01T11:00:00Z", decoded?.editedAt)
        // The model is coordinate-free by construction — it carries ONLY the six display fields, so a
        // re-serialization can contain no lat/lng/display_location key.
        val reserialized = Json.encodeToString(EmbeddedPostSnapshot.serializer(), decoded!!)
        for (forbidden in listOf("latitude", "longitude", "\"lat\"", "\"lng\"", "display_location", "authorId")) {
            assertTrue(forbidden !in reserialized, "snapshot must not serialize a $forbidden key")
        }
    }

    @Test
    fun plainPayloadHasNullEmbedFields() {
        val inbound = payload(snapshotJson = null, embeddedPostId = null, embeddedPostEditId = null).toInbound()
        assertNull(inbound.embeddedPostId)
        assertNull(inbound.embeddedPostSnapshot)
        assertNull(inbound.embeddedPostEditId)
    }

    @Test
    fun inboundExposesEmbedFieldsAsVendorFreeTypes() {
        // Compile-time shape assertion: the embed fields are Uuid? / EmbeddedPostSnapshot? (no Supabase
        // type). A no-redaction-reason property is structurally absent (cannot be referenced).
        val inbound: ChatMessageInbound =
            ChatMessageInbound(
                id = Uuid.parse(id),
                conversationId = Uuid.parse(conv),
                senderId = Uuid.parse(sender),
                content = "halo",
                createdAt = kotlinx.datetime.Instant.parse("2026-06-01T12:00:00Z"),
                redactedAt = null,
                embeddedPostId = Uuid.parse(post),
                embeddedPostSnapshot = EmbeddedPostSnapshot("raka", "Raka", "c", "Jakarta", "2026-06-01T10:00:00Z", null),
                embeddedPostEditId = Uuid.parse(edit),
            )
        val pid: Uuid? = inbound.embeddedPostId
        val snap: EmbeddedPostSnapshot? = inbound.embeddedPostSnapshot
        val eid: Uuid? = inbound.embeddedPostEditId
        assertEquals(Uuid.parse(post), pid)
        assertEquals("raka", snap?.authorUsername)
        assertEquals(Uuid.parse(edit), eid)
    }
}
