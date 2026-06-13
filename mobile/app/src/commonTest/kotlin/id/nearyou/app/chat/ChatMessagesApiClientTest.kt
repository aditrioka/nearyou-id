package id.nearyou.app.chat

import id.nearyou.app.auth.InMemoryTokenStore
import id.nearyou.app.auth.SessionInvalidator
import id.nearyou.app.network.HttpClientFactory
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val JSON_HEADERS = headersOf("Content-Type", "application/json")

private fun OutgoingContent.bodyText(): String = (this as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString() ?: ""

/**
 * MockEngine coverage of [ChatMessagesApiClient] (task 12.2): request shapes, the send body is
 * `{ content }` with NO `embedded_*` keys, message-row casing pinned to the SHIPPED wire (content
 * nullable, redacted_at absent→null), the redaction assertions (content:null + redacted_at present;
 * redaction_reason never surfaced), and the status→result mapping.
 */
class ChatMessagesApiClientTest {
    private fun client(handler: MockRequestHandler): HttpClient =
        HttpClientFactory.create(
            installTimeouts = false,
            apiBaseUrl = "http://test.local",
            tokenStore = InMemoryTokenStore(),
            sessionInvalidator = SessionInvalidator(InMemoryTokenStore()),
            engine = MockEngine(handler),
            installLogging = false,
            nowMillis = { 0L },
        )

    @Test
    fun `getMessages targets the conversation-scoped path and omits the first-page cursor`() =
        runTest {
            var captured: HttpRequestData? = null
            val api =
                ChatMessagesApiClient(
                    client { request ->
                        captured = request
                        respond("""{"messages":[]}""", HttpStatusCode.OK, JSON_HEADERS)
                    },
                )
            api.getMessages("c1")
            val req = requireNotNull(captured)
            assertEquals("/api/v1/chat/c1/messages", req.url.encodedPath)
            assertFalse(req.url.parameters.contains("cursor"))
        }

    @Test
    fun `a non-redacted message parses content and defaults redacted_at to null`() =
        runTest {
            val body =
                """{"messages":[{"id":"m1","conversation_id":"c1","sender_id":"u2",""" +
                    """"content":"halo","created_at":"2026-06-02T11:00:00Z"}],"next_cursor":"tok"}"""
            val api = ChatMessagesApiClient(client { respond(body, HttpStatusCode.OK, JSON_HEADERS) })
            val result = api.getMessages("c1")
            assertTrue(result is ChatMessagesApiResult.Success)
            val m = result.body.messages.single()
            assertEquals("m1", m.id)
            assertEquals("c1", m.conversationId)
            assertEquals("u2", m.senderId)
            assertEquals("halo", m.content)
            assertEquals("2026-06-02T11:00:00Z", m.createdAt)
            assertNull(m.redactedAt, "redacted_at absent → null")
            assertEquals("tok", result.body.nextCursor)
        }

    @Test
    fun `a redacted message parses content null plus redacted_at and never surfaces redaction_reason`() =
        runTest {
            // (i) content:null + redacted_at present → a redacted model with null content;
            // (ii) a body carrying redaction_reason does NOT surface it (no model property — ignored).
            val body =
                """{"messages":[{"id":"m1","conversation_id":"c1","sender_id":"u2","content":null,""" +
                    """"created_at":"t","redacted_at":"2026-06-03T00:00:00Z","redaction_reason":"abuse"}]}"""
            val api = ChatMessagesApiClient(client { respond(body, HttpStatusCode.OK, JSON_HEADERS) })
            val result = api.getMessages("c1")
            assertTrue(result is ChatMessagesApiResult.Success)
            val m = result.body.messages.single()
            assertNull(m.content)
            assertEquals("2026-06-03T00:00:00Z", m.redactedAt)
            // redaction_reason is ignored by the shared Json (no property on ChatMessageDto) — assert the
            // serialized DTO shape carries no such field.
            val reSerialized = Json.encodeToString(ChatMessageDto.serializer(), m)
            assertFalse(reSerialized.contains("redaction_reason"), "redaction_reason must never surface")
        }

    @Test
    fun `sendMessage posts content only with no embedded keys and parses 201`() =
        runTest {
            var captured: HttpRequestData? = null
            var body: String? = null
            val api =
                ChatMessagesApiClient(
                    client { request ->
                        captured = request
                        body = request.body.bodyText()
                        respond(
                            """{"id":"m9","conversation_id":"c1","sender_id":"u1","content":"hi","created_at":"t"}""",
                            HttpStatusCode.Created,
                            JSON_HEADERS,
                        )
                    },
                )
            val result = api.sendMessage("c1", "hi")
            val req = requireNotNull(captured)
            assertEquals("/api/v1/chat/c1/messages", req.url.encodedPath)
            assertTrue(result is SendMessageApiResult.Created)
            assertEquals("m9", result.message.id)
            val sentBody = requireNotNull(body)
            assertTrue(sentBody.contains("content"))
            assertFalse(sentBody.contains("embedded_post_id"), "send body carries NO embedded_* keys")
            assertFalse(sentBody.contains("embedded_post_snapshot"))
            assertFalse(sentBody.contains("embedded_post_edit_id"))
        }

    @Test
    fun `send maps non-201 statuses to HttpError carrying the status`() =
        runTest {
            suspend fun sendStatus(status: HttpStatusCode): SendMessageApiResult =
                ChatMessagesApiClient(client { respond("", status, JSON_HEADERS) }).sendMessage("c1", "hi")

            assertEquals(400, (sendStatus(HttpStatusCode.BadRequest) as SendMessageApiResult.HttpError).status)
            assertEquals(403, (sendStatus(HttpStatusCode.Forbidden) as SendMessageApiResult.HttpError).status)
            assertEquals(404, (sendStatus(HttpStatusCode.NotFound) as SendMessageApiResult.HttpError).status)
            assertEquals(500, (sendStatus(HttpStatusCode.InternalServerError) as SendMessageApiResult.HttpError).status)
        }

    @Test
    fun `transport failure maps to NetworkError on both verbs`() =
        runTest {
            val api = ChatMessagesApiClient(client { throw RuntimeException("refused") })
            assertTrue(api.getMessages("c1") is ChatMessagesApiResult.NetworkError)
            assertTrue(api.sendMessage("c1", "hi") is SendMessageApiResult.NetworkError)
        }
}
