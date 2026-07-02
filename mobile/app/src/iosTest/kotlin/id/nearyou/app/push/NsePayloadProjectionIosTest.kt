package id.nearyou.app.push

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `mobile-push-message-handling` task 6.4 — the NSE body-rewrite projection (the pure logic the
 * operator-added Swift NSE implements), testable on `iosSimulatorArm64Test` without a device:
 * preview ON rewrites a chat body to the preview; OFF/unset leaves the server-built private body;
 * a JSON-null preview (embedded-only message) leaves the private body; malformed / non-chat
 * `body_full` never rewrites; `thread-id` = `conversation_id` for OS grouping.
 */
class NsePayloadProjectionIosTest {
    private val serverBody = "bobby mengirim pesan"
    private val chatBodyFull = """{"conversation_id":"conv-1","preview":"halo apa kabar"}"""

    @Test
    fun previewOn_chatWithPreview_rewritesBodyToPreview() {
        assertEquals(
            "halo apa kabar",
            NsePayloadProjection.projectedBody(serverBody, chatBodyFull, previewEnabled = true),
        )
    }

    @Test
    fun previewOffOrUnset_leavesTheServerPrivateBody() {
        assertEquals(
            serverBody,
            NsePayloadProjection.projectedBody(serverBody, chatBodyFull, previewEnabled = false),
        )
    }

    @Test
    fun previewOn_nullPreview_leavesTheServerPrivateBody() {
        val embeddedOnly = """{"conversation_id":"conv-1","preview":null}"""
        assertEquals(
            serverBody,
            NsePayloadProjection.projectedBody(serverBody, embeddedOnly, previewEnabled = true),
        )
    }

    @Test
    fun malformedOrEmptyBodyFull_neverRewrites() {
        for (bodyFull in listOf(null, "", "not{json", """["array"]""")) {
            assertEquals(
                serverBody,
                NsePayloadProjection.projectedBody(serverBody, bodyFull, previewEnabled = true),
                "malformed body_full '$bodyFull' must leave the server body",
            )
        }
    }

    @Test
    fun nonChatBodyFull_noConversationId_neverRewrites() {
        val postBody = """{"post_excerpt":"Hi from Jakarta"}"""
        assertEquals(
            serverBody,
            NsePayloadProjection.projectedBody(serverBody, postBody, previewEnabled = true),
        )
    }

    @Test
    fun threadIdentifier_isTheConversationId_forOsGrouping() {
        assertEquals("conv-1", NsePayloadProjection.threadIdentifier(chatBodyFull))
    }

    @Test
    fun threadIdentifier_isNull_forNonChatOrMalformedPayloads() {
        assertNull(NsePayloadProjection.threadIdentifier("""{"post_excerpt":"Hi"}"""))
        assertNull(NsePayloadProjection.threadIdentifier("not{json"))
        assertNull(NsePayloadProjection.threadIdentifier(null))
        assertNull(NsePayloadProjection.threadIdentifier("""{"conversation_id":""}"""))
    }
}
