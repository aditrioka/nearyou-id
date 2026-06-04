package id.nearyou.app.post

import id.nearyou.app.auth.InMemoryTokenStore
import id.nearyou.app.auth.SessionInvalidator
import id.nearyou.app.location.FakeLocationPermissionController
import id.nearyou.app.location.LocationPermissionController
import id.nearyou.app.location.LocationPermissionStatus
import id.nearyou.app.location.LocationUnavailableException
import id.nearyou.app.network.HttpClientFactory
import id.nearyou.app.timeline.LocationProvider
import id.nearyou.distance.LatLng
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val JSON_HEADERS = headersOf("Content-Type", "application/json")

private fun OutgoingContent.bodyText(): String = (this as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString() ?: ""

/** Fake [LocationProvider] that returns a fixed (distinctive) fix and counts `current()` calls so a
 *  test can assert the un-guarded provider is NEVER reached under a denied permission. */
private class CountingLocationProvider(
    // Surabaya — distinct from the StubLocationProvider's Jakarta, so the body assertion is unambiguous.
    private val fix: LatLng = LatLng(lat = -7.25, lng = 112.75),
    private val throwOnCurrent: Throwable? = null,
) : LocationProvider {
    var currentCount: Int = 0
        private set

    override suspend fun current(): LatLng {
        currentCount++
        throwOnCurrent?.let { throw it }
        return fix
    }
}

/**
 * MockEngine-backed coverage of [CreatePostRepository] (task 7.3): the mandatory permission gate
 * (denied / not-determined / granted), the granted-but-no-fix path, and the HTTP status + `error.code`
 * → [PostCreationOutcome] mapping with no generic fallthrough (design D1/D3). The permission gate is
 * asserted to short-circuit BEFORE any `current()` call or POST (the un-guarded platform provider is
 * never reached under a denied permission).
 */
class CreatePostRepositoryTest {
    private fun repository(
        controller: LocationPermissionController,
        locationProvider: LocationProvider,
        diagnosticLog: (Int, String?) -> Unit = { _, _ -> },
        handler: MockRequestHandler,
    ): CreatePostRepository {
        val httpClient =
            HttpClientFactory.create(
                apiBaseUrl = "http://test.local",
                tokenStore = InMemoryTokenStore(),
                sessionInvalidator = SessionInvalidator(InMemoryTokenStore()),
                engine = MockEngine(handler),
                installLogging = false,
                nowMillis = { 0L },
            )
        return CreatePostRepository(
            apiClient = PostCreationApiClient(httpClient),
            locationProvider = locationProvider,
            permissionController = controller,
            diagnosticLog = diagnosticLog,
        )
    }

    @Test
    fun `denied permission short-circuits before any current call or POST`() =
        runTest {
            var requestCount = 0
            val provider = CountingLocationProvider()
            val controller = FakeLocationPermissionController(current = LocationPermissionStatus.DENIED)
            val repo =
                repository(controller, provider) {
                    requestCount++
                    respond("""{"id":"p1"}""", HttpStatusCode.Created, JSON_HEADERS)
                }

            assertEquals(PostCreationOutcome.LocationUnavailable, repo.submit("halo"))
            assertEquals(0, provider.currentCount, "the un-guarded current() must NOT be called under a denial")
            assertEquals(0, requestCount, "no POST is issued under a denial")
            assertEquals(0, controller.requestCount, "a terminal denial does not fire the OS prompt")
        }

    @Test
    fun `not-determined fires the prompt then proceeds on grant to Success`() =
        runTest {
            var requestCount = 0
            val provider = CountingLocationProvider()
            val controller =
                FakeLocationPermissionController(
                    current = LocationPermissionStatus.NOT_DETERMINED,
                    afterRequest = LocationPermissionStatus.GRANTED,
                )
            val repo =
                repository(controller, provider) {
                    requestCount++
                    respond("""{"id":"p1"}""", HttpStatusCode.Created, JSON_HEADERS)
                }

            assertEquals(PostCreationOutcome.Success("p1"), repo.submit("halo"))
            assertEquals(1, controller.requestCount, "the OS prompt fired exactly once")
            assertEquals(1, provider.currentCount, "the fix was acquired after the grant")
            assertEquals(1, requestCount, "the POST was issued after the grant")
        }

    @Test
    fun `not-determined that is not granted after the prompt maps to LocationUnavailable with no POST`() =
        runTest {
            var requestCount = 0
            val provider = CountingLocationProvider()
            val controller =
                FakeLocationPermissionController(
                    current = LocationPermissionStatus.NOT_DETERMINED,
                    afterRequest = LocationPermissionStatus.DENIED,
                )
            val repo =
                repository(controller, provider) {
                    requestCount++
                    respond("", HttpStatusCode.Created, JSON_HEADERS)
                }

            assertEquals(PostCreationOutcome.LocationUnavailable, repo.submit("halo"))
            assertEquals(1, controller.requestCount, "the prompt fired")
            assertEquals(0, provider.currentCount, "no fix is acquired when the prompt is declined")
            assertEquals(0, requestCount, "no POST is issued when the prompt is declined")
        }

    @Test
    fun `granted but no fix maps to LocationUnavailable with zero requests`() =
        runTest {
            var requestCount = 0
            val provider = CountingLocationProvider(throwOnCurrent = LocationUnavailableException())
            val controller = FakeLocationPermissionController(current = LocationPermissionStatus.GRANTED)
            val repo =
                repository(controller, provider) {
                    requestCount++
                    respond("""{"id":"p1"}""", HttpStatusCode.Created, JSON_HEADERS)
                }

            assertEquals(PostCreationOutcome.LocationUnavailable, repo.submit("halo"))
            assertEquals(1, provider.currentCount, "current() was attempted under the granted permission")
            assertEquals(0, requestCount, "granted-but-no-fix issues no POST")
        }

    @Test
    fun `granted happy path posts the device fix coordinate and maps 201 to Success`() =
        runTest {
            var capturedBody = ""
            val provider = CountingLocationProvider() // fix = (-7.25, 112.75)
            val controller = FakeLocationPermissionController(current = LocationPermissionStatus.GRANTED)
            val repo =
                repository(controller, provider) { request ->
                    capturedBody = request.body.bodyText()
                    respond("""{"id":"created-123"}""", HttpStatusCode.Created, JSON_HEADERS)
                }

            assertEquals(PostCreationOutcome.Success("created-123"), repo.submit("halo"))
            // The submitted coordinate equals the device fix (NOT any default / user-entered value).
            assertTrue(capturedBody.contains("-7.25"), "submitted latitude must be the provider fix: $capturedBody")
            assertTrue(capturedBody.contains("112.75"), "submitted longitude must be the provider fix: $capturedBody")
        }

    @Test
    fun `each enumerated 400 error code maps to its outcome`() =
        runTest {
            val cases =
                mapOf(
                    "content_empty" to PostCreationOutcome.ContentEmpty,
                    "content_too_long" to PostCreationOutcome.ContentTooLong,
                    "location_out_of_bounds" to PostCreationOutcome.LocationOutOfBounds,
                    "content_moderated_profanity" to PostCreationOutcome.ContentRejected,
                )
            for ((code, expected) in cases) {
                val controller = FakeLocationPermissionController(current = LocationPermissionStatus.GRANTED)
                val repo =
                    repository(controller, CountingLocationProvider()) {
                        respond("""{"error":{"code":"$code"}}""", HttpStatusCode.BadRequest, JSON_HEADERS)
                    }
                assertEquals(expected, repo.submit("halo"), "400 $code must map to $expected")
            }
        }

    @Test
    fun `unknown 400 code maps to retryable Error with a logged diagnostic`() =
        runTest {
            val logged = mutableListOf<Pair<Int, String?>>()
            val controller = FakeLocationPermissionController(current = LocationPermissionStatus.GRANTED)
            val repo =
                repository(controller, CountingLocationProvider(), diagnosticLog = { status, code -> logged.add(status to code) }) {
                    respond("""{"error":{"code":"invalid_json"}}""", HttpStatusCode.BadRequest, JSON_HEADERS)
                }

            assertEquals(PostCreationOutcome.Error, repo.submit("halo"))
            assertTrue(logged.any { it.first == 400 }, "a diagnostic must be emitted on an unknown 400 (not a silent no-op): $logged")
        }

    @Test
    fun `5xx maps to NetworkError`() =
        runTest {
            val controller = FakeLocationPermissionController(current = LocationPermissionStatus.GRANTED)
            val repo = repository(controller, CountingLocationProvider()) { respond("", HttpStatusCode.InternalServerError, JSON_HEADERS) }
            assertEquals(PostCreationOutcome.NetworkError, repo.submit("halo"))
        }

    @Test
    fun `transport IO failure maps to NetworkError`() =
        runTest {
            val controller = FakeLocationPermissionController(current = LocationPermissionStatus.GRANTED)
            val repo = repository(controller, CountingLocationProvider()) { throw RuntimeException("connection refused") }
            assertEquals(PostCreationOutcome.NetworkError, repo.submit("halo"))
        }
}
