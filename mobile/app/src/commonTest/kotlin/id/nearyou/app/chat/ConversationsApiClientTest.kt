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
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val JSON_HEADERS = headersOf("Content-Type", "application/json")

/** A 200 list body using the SHIPPED nested wire (`{conversation,partner}`); snake_case created_at /
 *  last_message_at / display_name / is_premium / next_cursor. NOT a flattened shape. */
private const val LIST_BODY =
    """{"conversations":[{"conversation":{"id":"c1","created_at":"2026-06-01T10:00:00Z",""" +
        """"last_message_at":"2026-06-02T11:00:00Z"},"partner":{"id":"u2","username":"dewi.kuliner",""" +
        """"display_name":"Dewi Lestari","is_premium":true}}],"next_cursor":"tok"}"""

/**
 * MockEngine coverage of [ConversationsApiClient] (task 12.2): request shape, per-field casing pinned to
 * the SHIPPED nested wire (the Nearby D10 trap), a different-casing regression, and the create-or-return
 * status split (201 Created / 200 Returned / 403 Blocked / 400 SelfConversation / 404 RecipientNotFound).
 */
class ConversationsApiClientTest {
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
    fun `getConversations targets the canonical path and omits the first-page cursor`() =
        runTest {
            var captured: HttpRequestData? = null
            val api =
                ConversationsApiClient(
                    client { request ->
                        captured = request
                        respond("""{"conversations":[]}""", HttpStatusCode.OK, JSON_HEADERS)
                    },
                )
            api.getConversations()
            val req = requireNotNull(captured)
            assertEquals("/api/v1/conversations", req.url.encodedPath)
            assertTrue(!req.url.parameters.contains("cursor"), "first-page request must omit cursor")
        }

    @Test
    fun `a passed cursor is sent as the cursor param`() =
        runTest {
            var captured: HttpRequestData? = null
            val api =
                ConversationsApiClient(
                    client { request ->
                        captured = request
                        respond("""{"conversations":[]}""", HttpStatusCode.OK, JSON_HEADERS)
                    },
                )
            api.getConversations(cursor = "tok-2")
            assertEquals("tok-2", requireNotNull(captured).url.parameters["cursor"])
        }

    @Test
    fun `nested conversation row parses field-by-field against the shipped wire`() =
        runTest {
            val api = ConversationsApiClient(client { respond(LIST_BODY, HttpStatusCode.OK, JSON_HEADERS) })
            val result = api.getConversations()
            assertTrue(result is ConversationsApiResult.Success)
            val item = result.body.conversations.single()
            assertEquals("c1", item.conversation.id)
            assertEquals("2026-06-01T10:00:00Z", item.conversation.createdAt)
            assertEquals("2026-06-02T11:00:00Z", item.conversation.lastMessageAt)
            assertEquals("u2", item.partner.id)
            assertEquals("dewi.kuliner", item.partner.username)
            assertEquals("Dewi Lestari", item.partner.displayName)
            assertEquals(true, item.partner.isPremium)
            assertEquals("tok", result.body.nextCursor)
        }

    @Test
    fun `null last_message_at parses for a brand-new conversation`() =
        runTest {
            val body =
                """{"conversations":[{"conversation":{"id":"c1","created_at":"t","last_message_at":null},""" +
                    """"partner":{"id":"u2","username":"u","display_name":"D","is_premium":false}}]}"""
            val api = ConversationsApiClient(client { respond(body, HttpStatusCode.OK, JSON_HEADERS) })
            val result = api.getConversations()
            assertTrue(result is ConversationsApiResult.Success)
            assertNull(result.body.conversations.single().conversation.lastMessageAt)
        }

    @Test
    fun `a camelCase body does NOT populate the snake_case partner fields`() {
        val json =
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            }
        // displayName / isPremium camelCase (NOT the shipped snake_case) → the required display_name is
        // absent → decoding throws (the casing pin guards against a stale-spec DTO).
        val camel =
            """{"id":"u2","username":"u","displayName":"D","isPremium":true}"""
        assertFailsWithSerialization { json.decodeFromString(PartnerProfileDto.serializer(), camel) }
    }

    @Test
    fun `createOrReturn sends recipient_user_id and parses 201 Created`() =
        runTest {
            var body: String? = null
            val api =
                ConversationsApiClient(
                    client { request ->
                        body = request.body.bodyText()
                        respond(
                            """{"conversation":{"id":"c9","created_at":"t"},"participants":[]}""",
                            HttpStatusCode.Created,
                            JSON_HEADERS,
                        )
                    },
                )
            val result = api.createOrReturnConversation("u2")
            assertTrue(result is CreateConversationApiResult.Created)
            assertEquals("c9", result.body.conversation.id)
            assertTrue(requireNotNull(body).contains("recipient_user_id"))
        }

    @Test
    fun `createOrReturn maps 200 to Returned and 403 400 404 to distinct members`() =
        runTest {
            suspend fun call(status: HttpStatusCode): CreateConversationApiResult =
                ConversationsApiClient(
                    client {
                        respond(
                            """{"conversation":{"id":"c1","created_at":"t"},"participants":[]}""",
                            status,
                            JSON_HEADERS,
                        )
                    },
                ).createOrReturnConversation("u2")

            assertTrue(call(HttpStatusCode.OK) is CreateConversationApiResult.Returned)
            assertTrue(call(HttpStatusCode.Forbidden) is CreateConversationApiResult.Blocked)
            assertTrue(call(HttpStatusCode.BadRequest) is CreateConversationApiResult.SelfConversation)
            assertTrue(call(HttpStatusCode.NotFound) is CreateConversationApiResult.RecipientNotFound)
        }

    @Test
    fun `transport failure maps to NetworkError`() =
        runTest {
            val api = ConversationsApiClient(client { throw RuntimeException("refused") })
            assertTrue(api.getConversations() is ConversationsApiResult.NetworkError)
        }
}

private fun OutgoingContent.bodyText(): String = (this as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString() ?: ""

private inline fun assertFailsWithSerialization(block: () -> Unit) {
    try {
        block()
    } catch (_: SerializationException) {
        return
    }
    throw AssertionError("expected a SerializationException for a mismatched-casing body")
}
