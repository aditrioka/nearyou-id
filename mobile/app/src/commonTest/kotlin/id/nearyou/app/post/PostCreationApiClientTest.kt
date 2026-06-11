package id.nearyou.app.post

import id.nearyou.app.auth.InMemoryTokenStore
import id.nearyou.app.auth.SessionInvalidator
import id.nearyou.app.network.HttpClientFactory
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val JSON_HEADERS = headersOf("Content-Type", "application/json")

/** Reads a serialized request body — `TextContent` (what ContentNegotiation emits) extends
 *  `OutgoingContent.ByteArrayContent`, so the byte form decodes cleanly (mirrors `AuthApiClientTest`). */
private fun OutgoingContent.bodyText(): String = (this as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString() ?: ""

/**
 * MockEngine-backed coverage of [PostCreationApiClient] (task 7.2): the `POST /api/v1/posts` endpoint
 * + method, the bare-`latitude`/`longitude` body shape (negative guard vs snake_case / `lat`/`lng`),
 * the minimal 201 parse tolerating the snake_case `distance_m`/`created_at`, the non-2xx → `HttpError`
 * mapping carrying the parsed `error.code`, the transport → `NetworkError` mapping, and cancellation
 * rethrow.
 */
class PostCreationApiClientTest {
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
    fun `createPost targets POST api v1 posts with bare latitude longitude body`() =
        runTest {
            var captured: HttpRequestData? = null
            var capturedBody = ""
            val api =
                PostCreationApiClient(
                    client { request ->
                        captured = request
                        capturedBody = request.body.bodyText()
                        respond("""{"id":"p1"}""", HttpStatusCode.Created, JSON_HEADERS)
                    },
                )
            api.createPost(content = "halo", lat = -6.2, lng = 106.8)

            val req = requireNotNull(captured)
            assertEquals(HttpMethod.Post, req.method)
            assertEquals("/api/v1/posts", req.url.encodedPath)
            assertTrue(capturedBody.contains("\"content\""), "body: $capturedBody")
            assertTrue(capturedBody.contains("halo"))
            // The wire keys are exactly latitude / longitude with the supplied values …
            assertTrue(capturedBody.contains("\"latitude\""), "wire key must be latitude: $capturedBody")
            assertTrue(capturedBody.contains("\"longitude\""), "wire key must be longitude: $capturedBody")
            assertTrue(capturedBody.contains("-6.2"))
            assertTrue(capturedBody.contains("106.8"))
            // … NOT the short lat/lng (negative guard vs the snake_case / shorthand trap).
            assertFalse(capturedBody.contains("\"lat\":"), "must NOT use the short lat key: $capturedBody")
            assertFalse(capturedBody.contains("\"lng\":"), "must NOT use the short lng key: $capturedBody")
        }

    @Test
    fun `request DTO serializes bare latitude longitude not lat lng`() {
        val wire = Json.encodeToString(CreatePostRequestDto.serializer(), CreatePostRequestDto("halo", -6.2, 106.8))
        assertTrue(wire.contains("\"latitude\""), wire)
        assertTrue(wire.contains("\"longitude\""), wire)
        assertFalse(wire.contains("\"lat\":"), wire)
        assertFalse(wire.contains("\"lng\":"), wire)
    }

    @Test
    fun `201 parses the id and tolerates the snake_case distance_m and created_at`() =
        runTest {
            // The create response is snake_case for distance_m / created_at (unlike the Nearby wire).
            val body =
                """{"id":"0193abcd-7def","content":"halo","latitude":-6.2,"longitude":106.8,""" +
                    """"distance_m":null,"created_at":"2026-06-03T00:00:00Z"}"""
            val api = PostCreationApiClient(client { respond(body, HttpStatusCode.Created, JSON_HEADERS) })

            val result = api.createPost("halo", -6.2, 106.8)
            assertTrue(result is PostCreationApiResult.Success, "expected Success, was $result")
            assertEquals("0193abcd-7def", result.id)
        }

    @Test
    fun `non-2xx parses the error envelope into HttpError status and errorCode`() =
        runTest {
            val api =
                PostCreationApiClient(
                    client { respond("""{"error":{"code":"content_too_long","message":"…"}}""", HttpStatusCode.BadRequest, JSON_HEADERS) },
                )
            val result = api.createPost("x".repeat(300), -6.2, 106.8)
            assertTrue(result is PostCreationApiResult.HttpError, "expected HttpError, was $result")
            assertEquals(400, result.status)
            assertEquals("content_too_long", result.errorCode)
        }

    @Test
    fun `5xx with an empty body maps to HttpError with a null errorCode`() =
        runTest {
            val api = PostCreationApiClient(client { respond("", HttpStatusCode.InternalServerError, JSON_HEADERS) })
            val result = api.createPost("halo", -6.2, 106.8)
            assertTrue(result is PostCreationApiResult.HttpError, "expected HttpError, was $result")
            assertEquals(500, result.status)
            assertNull(result.errorCode, "an unparseable error body yields a null code, never a throw")
        }

    @Test
    fun `transport failure maps to NetworkError`() =
        runTest {
            val api = PostCreationApiClient(client { throw RuntimeException("connection refused") })
            assertTrue(api.createPost("halo", -6.2, 106.8) is PostCreationApiResult.NetworkError)
        }

    @Test
    fun `cancellation mid-createPost propagates rather than mapping to NetworkError`() =
        runTest {
            val api =
                PostCreationApiClient(
                    client {
                        delay(60_000) // never completes within the job's lifetime
                        respond("""{"id":"p1"}""", HttpStatusCode.Created, JSON_HEADERS)
                    },
                )
            var completed = false
            val job =
                launch {
                    api.createPost("halo", -6.2, 106.8)
                    completed = true
                }
            delay(100)
            job.cancel()
            job.join()

            assertTrue(job.isCancelled, "the in-flight createPost job is cancelled, not hung")
            assertFalse(completed, "createPost did not silently complete with a NetworkError after cancellation")
        }
}
