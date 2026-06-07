package id.nearyou.app.notifications

import id.nearyou.app.auth.InMemoryTokenStore
import id.nearyou.app.auth.SessionInvalidator
import id.nearyou.app.network.HttpClientFactory
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val JSON_HEADERS = headersOf("Content-Type", "application/json")

/** A 200 list body whose item uses the SHIPPED notifications wire (bare `id`/`type`; snake
 *  `actor_user_id`/`target_type`/`target_id`/`body_data`/`created_at`/`read_at`) + an opaque base64url
 *  `next_cursor`. NOT the stale spec's snake-`unread_only` / timestamp-cursor shape. */
private const val SHIPPED_LIST_BODY =
    """{"items":[{"id":"n1","type":"post_liked","actor_user_id":"a-1","target_type":"post",""" +
        """"target_id":"t-1","body_data":{"post_excerpt":"halo"},"created_at":"2026-05-31T10:00:00Z",""" +
        """"read_at":null}],"next_cursor":"eyJjIjoiMjAyNiJ9"}"""

/**
 * MockEngine-backed coverage of [NotificationsApiClient] (§6): the request path + first-page-omits-cursor,
 * the opaque-cursor pass-back-verbatim, the `unread=true` filter, parsing against the SHIPPED mixed-case
 * wire, `body_data` non-null + absent-`next_cursor` tolerance, the stale-spec negative regression, and the
 * non-2xx / mutation status mapping.
 */
class NotificationsApiClientTest {
    private fun client(handler: MockRequestHandler): HttpClient =
        HttpClientFactory.create(
            apiBaseUrl = "http://test.local",
            tokenStore = InMemoryTokenStore(),
            sessionInvalidator = SessionInvalidator(InMemoryTokenStore()),
            engine = MockEngine(handler),
            installLogging = false,
            nowMillis = { 0L },
        )

    @Test
    fun `fetch targets the canonical path and omits the first-page cursor`() =
        runTest {
            var captured: HttpRequestData? = null
            val api =
                NotificationsApiClient(
                    client { request ->
                        captured = request
                        respond("""{"items":[]}""", HttpStatusCode.OK, JSON_HEADERS)
                    },
                )
            api.fetch()

            val req = requireNotNull(captured)
            assertEquals("/api/v1/notifications", req.url.encodedPath)
            assertFalse(req.url.parameters.contains("cursor"), "first-page request must omit cursor")
            assertFalse(req.url.parameters.contains("unread"), "no unread filter unless requested")
        }

    @Test
    fun `opaque cursor is passed back verbatim`() =
        runTest {
            var captured: HttpRequestData? = null
            val api =
                NotificationsApiClient(
                    client { request ->
                        captured = request
                        respond("""{"items":[]}""", HttpStatusCode.OK, JSON_HEADERS)
                    },
                )
            api.fetch(cursor = "eyJjIjoiMjAyNiJ9")

            assertEquals(
                "eyJjIjoiMjAyNiJ9",
                requireNotNull(captured).url.parameters["cursor"],
                "the opaque next_cursor is sent byte-for-byte (no decoding / reformatting)",
            )
        }

    @Test
    fun `unread filter is sent as unread=true`() =
        runTest {
            var captured: HttpRequestData? = null
            val api =
                NotificationsApiClient(
                    client { request ->
                        captured = request
                        respond("""{"items":[]}""", HttpStatusCode.OK, JSON_HEADERS)
                    },
                )
            api.fetch(unreadOnly = true)

            assertEquals("true", requireNotNull(captured).url.parameters["unread"], "unread filter is unread=true (NOT unread_only)")
        }

    @Test
    fun `full notification shape parses against the shipped mixed-case wire`() =
        runTest {
            val api = NotificationsApiClient(client { respond(SHIPPED_LIST_BODY, HttpStatusCode.OK, JSON_HEADERS) })
            val result = api.fetch()

            val body = assertIs<NotificationsApiResult.Success>(result).body
            assertEquals(1, body.items.size)
            val item = body.items.first()
            assertEquals("n1", item.id)
            assertEquals("post_liked", item.type)
            assertEquals("a-1", item.actorUserId)
            assertEquals("post", item.targetType)
            assertEquals("t-1", item.targetId)
            assertIs<JsonObject>(item.bodyData)
            assertEquals("2026-05-31T10:00:00Z", item.createdAt)
            assertNull(item.readAt)
            assertEquals("eyJjIjoiMjAyNiJ9", body.nextCursor)
        }

    @Test
    fun `body_data is non-null and absent next_cursor is tolerated`() =
        runTest {
            // body_data present as {} + no top-level next_cursor + nullable fields absent.
            val bodyJson = """{"items":[{"id":"n1","type":"followed","body_data":{},"created_at":"t"}]}"""
            val api = NotificationsApiClient(client { respond(bodyJson, HttpStatusCode.OK, JSON_HEADERS) })

            val body = assertIs<NotificationsApiResult.Success>(api.fetch()).body
            val item = body.items.first()
            assertEquals(JsonObject(emptyMap()), item.bodyData, "body_data is a non-null empty JSON object")
            assertNull(item.actorUserId, "absent nullable actor_user_id tolerated → null")
            assertNull(body.nextCursor, "absent next_cursor tolerated → null")
        }

    // ---- Negative regression: the shipped wire is `count` / `marked_read`, NOT the spec's
    // ---- `unread_count` / `marked`. A stale-key body must NOT populate the shipped field.
    @Test
    fun `stale-spec unread-count and read-all keys do NOT populate the shipped fields`() {
        val json =
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            }
        // Stale shapes: the key the field is NOT bound to.
        assertEquals(
            0L,
            json.decodeFromString(UnreadCountDto.serializer(), """{"unread_count":3}""").count,
            "the stale `unread_count` key must NOT populate `count`",
        )
        assertEquals(
            0,
            json.decodeFromString(ReadAllResponseDto.serializer(), """{"marked":5}""").markedRead,
            "the stale `marked` key must NOT populate `marked_read`",
        )
        // Shipped shapes DO populate (the fixture proving the regression cannot silently slip in).
        assertEquals(4L, json.decodeFromString(UnreadCountDto.serializer(), """{"count":4}""").count)
        assertEquals(5, json.decodeFromString(ReadAllResponseDto.serializer(), """{"marked_read":5}""").markedRead)
    }

    @Test
    fun `non-2xx maps to HttpError carrying the status`() =
        runTest {
            val api400 =
                NotificationsApiClient(
                    client { respond("""{"error":{"code":"invalid_cursor"}}""", HttpStatusCode.BadRequest, JSON_HEADERS) },
                )
            assertEquals(400, assertIs<NotificationsApiResult.HttpError>(api400.fetch()).status)

            val api500 = NotificationsApiClient(client { respond("", HttpStatusCode.InternalServerError, JSON_HEADERS) })
            assertEquals(500, assertIs<NotificationsApiResult.HttpError>(api500.fetch()).status)
        }

    @Test
    fun `transport failure maps to NetworkError`() =
        runTest {
            val api = NotificationsApiClient(client { throw RuntimeException("connection refused") })
            assertTrue(api.fetch() is NotificationsApiResult.NetworkError)
        }

    @Test
    fun `unread-count parses the shipped count and fails soft on non-200`() =
        runTest {
            val ok = NotificationsApiClient(client { respond("""{"count":7}""", HttpStatusCode.OK, JSON_HEADERS) })
            assertEquals(7L, assertIs<UnreadCountResult.Success>(ok.unreadCount()).count)

            val err = NotificationsApiClient(client { respond("", HttpStatusCode.InternalServerError, JSON_HEADERS) })
            assertEquals(UnreadCountResult.Failure, err.unreadCount())
        }

    @Test
    fun `markRead maps 204 to Acknowledged 404 to NotFound and other to Failed`() =
        runTest {
            val ack = NotificationsApiClient(client { respond("", HttpStatusCode.NoContent) })
            assertEquals(MarkReadResult.Acknowledged, ack.markRead("n1"))

            val notFound =
                NotificationsApiClient(
                    client { respond("""{"error":{"code":"not_found"}}""", HttpStatusCode.NotFound, JSON_HEADERS) },
                )
            assertEquals(MarkReadResult.NotFound, notFound.markRead("n1"))

            val failed = NotificationsApiClient(client { respond("", HttpStatusCode.InternalServerError) })
            assertEquals(MarkReadResult.Failed, failed.markRead("n1"))
        }

    @Test
    fun `markRead PATCHes the canonical per-id path`() =
        runTest {
            var captured: HttpRequestData? = null
            val api =
                NotificationsApiClient(
                    client { request ->
                        captured = request
                        respond("", HttpStatusCode.NoContent)
                    },
                )
            api.markRead("n-123")
            assertEquals("/api/v1/notifications/n-123/read", requireNotNull(captured).url.encodedPath)
        }

    @Test
    fun `markAllRead parses marked_read on 200 and fails on non-200`() =
        runTest {
            val ok = NotificationsApiClient(client { respond("""{"marked_read":3}""", HttpStatusCode.OK, JSON_HEADERS) })
            assertEquals(3, assertIs<MarkAllReadResult.Success>(ok.markAllRead()).markedRead)

            val failed = NotificationsApiClient(client { respond("", HttpStatusCode.InternalServerError) })
            assertEquals(MarkAllReadResult.Failed, failed.markAllRead())
        }
}
