package id.nearyou.app.image

import id.nearyou.app.auth.InMemoryTokenStore
import id.nearyou.app.auth.SessionInvalidator
import id.nearyou.app.network.HttpClientFactory
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val JSON_HEADERS = headersOf("Content-Type", "application/json")

/** The real 4-segment `<base>/<accountHash>/<image_id>/public` delivery URL the server emits. */
private const val DELIVERY_URL = "https://img.nearyou.id/acct123/abc/public"

/** A `201` body matching the SHIPPED manual `buildJsonObject` wire (snake_case `image_id`/`delivery_url`). */
private const val CREATED_BODY = """{"image_id":"abc","delivery_url":"$DELIVERY_URL"}"""

/**
 * MockEngine-backed coverage of [ImageUploadApiClient] (7.3): the multipart POST request shape, parsing the
 * `201 {image_id, delivery_url}` body against the shipped snake_case wire, the CancellationException
 * rethrow discipline, and the non-2xx → [ImageUploadApiResult.HttpError] (status + parsed error.code)
 * mapping the repository keys on.
 */
class ImageUploadApiClientTest {
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
    fun `upload targets POST api v1 images with a multipart form-data body`() =
        runTest {
            var captured: HttpRequestData? = null
            val api =
                ImageUploadApiClient(
                    client { request ->
                        captured = request
                        respond(CREATED_BODY, HttpStatusCode.Created, JSON_HEADERS)
                    },
                )
            api.upload(bytes = byteArrayOf(1, 2, 3, 4, 5), mime = "image/jpeg")

            val req = requireNotNull(captured)
            assertEquals(HttpMethod.Post, req.method)
            assertEquals("/api/v1/images", req.url.encodedPath)
            // The Ktor multipart builder produces a MultiPartFormDataContent whose content-type is
            // multipart/form-data with a boundary — the canonical multipart POST shape the spec requires.
            val body = req.body
            assertTrue(body is MultiPartFormDataContent, "request body must be MultiPartFormDataContent, was ${body::class.simpleName}")
            assertTrue(
                body.contentType?.match("multipart/form-data") == true,
                "body content-type must be multipart/form-data, was ${body.contentType}",
            )
        }

    @Test
    fun `201 parses image_id and the opaque 4-segment delivery_url`() =
        runTest {
            val api = ImageUploadApiClient(client { respond(CREATED_BODY, HttpStatusCode.Created, JSON_HEADERS) })

            val result = api.upload(byteArrayOf(9), "image/png")
            assertTrue(result is ImageUploadApiResult.Success, "expected Success, was $result")
            assertEquals("abc", result.body.imageId)
            // The client treats the delivery URL as opaque — it never reconstructs the path.
            assertEquals(DELIVERY_URL, result.body.deliveryUrl)
        }

    @Test
    fun `non-2xx maps to HttpError carrying status and the parsed error code`() =
        runTest {
            val api =
                ImageUploadApiClient(
                    client {
                        respond(
                            """{"error":{"code":"image_rejected","message":"no"}}""",
                            HttpStatusCode.UnprocessableEntity,
                            JSON_HEADERS,
                        )
                    },
                )
            val result = api.upload(byteArrayOf(1), "image/jpeg")
            assertTrue(result is ImageUploadApiResult.HttpError, "expected HttpError, was $result")
            assertEquals(422, result.status)
            assertEquals("image_rejected", result.errorCode)
        }

    @Test
    fun `a non-2xx with an empty body yields a null error code`() =
        runTest {
            val api = ImageUploadApiClient(client { respond("", HttpStatusCode.ServiceUnavailable, JSON_HEADERS) })
            val result = api.upload(byteArrayOf(1), "image/jpeg")
            assertTrue(result is ImageUploadApiResult.HttpError, "expected HttpError, was $result")
            assertEquals(503, result.status)
            assertEquals(null, result.errorCode)
        }

    @Test
    fun `transport failure maps to NetworkError`() =
        runTest {
            val api = ImageUploadApiClient(client { throw RuntimeException("connection refused") })
            assertTrue(api.upload(byteArrayOf(1), "image/jpeg") is ImageUploadApiResult.NetworkError)
        }

    @Test
    fun `CancellationException is rethrown not mapped to NetworkError`() =
        runTest {
            val released = CompletableDeferred<Unit>()
            val api =
                ImageUploadApiClient(
                    client {
                        // Block until released, then fail with a CancellationException from inside the
                        // engine call — the client must rethrow it (structured concurrency unwinds), NOT
                        // swallow it into NetworkError.
                        released.await()
                        throw CancellationException("cancelled mid-flight")
                    },
                )

            var caught: Throwable? = null
            val deferred =
                CoroutineScope(coroutineContext).async {
                    try {
                        api.upload(byteArrayOf(1), "image/jpeg")
                    } catch (cause: CancellationException) {
                        caught = cause
                        throw cause
                    }
                }
            released.complete(Unit)
            try {
                deferred.await()
            } catch (_: CancellationException) {
                // expected — the cancellation propagated out of upload(...)
            }
            assertTrue(caught is CancellationException, "upload(...) must rethrow CancellationException, not map it")
        }
}
