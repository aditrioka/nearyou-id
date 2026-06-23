package id.nearyou.app.image

import id.nearyou.app.auth.InMemoryTokenStore
import id.nearyou.app.auth.SessionInvalidator
import id.nearyou.app.network.HttpClientFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val JSON_HEADERS = headersOf("Content-Type", "application/json")

/** An error envelope body for [code], as the shipped `respondError` emits it. */
private fun errorBody(code: String): String = """{"error":{"code":"$code","message":"x"}}"""

/**
 * MockEngine-backed coverage of [ImageUploadRepository] (7.3): the HTTP status + `error.code` →
 * [ImageUploadOutcome] mapping with NO generic fallthrough (the two `403`s and two `429`s disambiguated by
 * `error.code`, `503 image_upload_unavailable` → `Unavailable`, transport → `Network`), and the spec's
 * "every outcome is a declared sealed member" exhaustiveness scenario.
 */
class ImageUploadRepositoryTest {
    private fun repository(
        log: (status: Int, errorCode: String?) -> Unit = { _, _ -> },
        handler: MockRequestHandler,
    ): ImageUploadRepository {
        val tokenStore = InMemoryTokenStore()
        val httpClient =
            HttpClientFactory.create(
                installTimeouts = false,
                apiBaseUrl = "http://test.local",
                tokenStore = tokenStore,
                sessionInvalidator = SessionInvalidator(tokenStore),
                engine = MockEngine(handler),
                installLogging = false,
                nowMillis = { 0L },
            )
        return ImageUploadRepository(apiClient = ImageUploadApiClient(httpClient), diagnosticLog = log)
    }

    private suspend fun outcomeFor(
        status: HttpStatusCode,
        code: String,
    ): ImageUploadOutcome =
        repository { respond(errorBody(code), status, JSON_HEADERS) }
            .upload(byteArrayOf(1, 2, 3), "image/jpeg")

    @Test
    fun `201 maps to Success carrying image id and delivery url`() =
        runTest {
            val repo =
                repository {
                    respond(
                        """{"image_id":"abc","delivery_url":"https://img.nearyou.id/acct123/abc/public"}""",
                        HttpStatusCode.Created,
                        JSON_HEADERS,
                    )
                }
            val outcome = repo.upload(byteArrayOf(1), "image/png")
            assertTrue(outcome is ImageUploadOutcome.Success, "expected Success, was $outcome")
            assertEquals("abc", outcome.imageId)
            assertEquals("https://img.nearyou.id/acct123/abc/public", outcome.deliveryUrl)
        }

    // ---- The status + error.code → outcome TABLE (all 8 mapped branches). ----

    @Test
    fun `403 image_upload_disabled maps to FeatureDisabled`() =
        runTest {
            assertEquals(ImageUploadOutcome.FeatureDisabled, outcomeFor(HttpStatusCode.Forbidden, "image_upload_disabled"))
        }

    @Test
    fun `403 premium_required maps to PremiumRequired`() =
        runTest {
            assertEquals(ImageUploadOutcome.PremiumRequired, outcomeFor(HttpStatusCode.Forbidden, "premium_required"))
        }

    @Test
    fun `the two 403s are disambiguated by error code not status`() =
        runTest {
            // Same 403 status, distinct outcomes keyed on error.code (the spec's distinct-copy requirement).
            assertEquals(ImageUploadOutcome.FeatureDisabled, outcomeFor(HttpStatusCode.Forbidden, "image_upload_disabled"))
            assertEquals(ImageUploadOutcome.PremiumRequired, outcomeFor(HttpStatusCode.Forbidden, "premium_required"))
        }

    @Test
    fun `429 image_upload_throttled maps to Throttled`() =
        runTest {
            assertEquals(ImageUploadOutcome.Throttled, outcomeFor(HttpStatusCode.TooManyRequests, "image_upload_throttled"))
        }

    @Test
    fun `429 image_upload_quota_exceeded maps to QuotaExceeded`() =
        runTest {
            assertEquals(ImageUploadOutcome.QuotaExceeded, outcomeFor(HttpStatusCode.TooManyRequests, "image_upload_quota_exceeded"))
        }

    @Test
    fun `the two 429s are disambiguated by error code not status`() =
        runTest {
            // Same 429 status, distinct outcomes keyed on error.code.
            assertEquals(ImageUploadOutcome.Throttled, outcomeFor(HttpStatusCode.TooManyRequests, "image_upload_throttled"))
            assertEquals(ImageUploadOutcome.QuotaExceeded, outcomeFor(HttpStatusCode.TooManyRequests, "image_upload_quota_exceeded"))
        }

    @Test
    fun `413 image_too_large maps to TooLarge`() =
        runTest {
            assertEquals(ImageUploadOutcome.TooLarge, outcomeFor(HttpStatusCode.PayloadTooLarge, "image_too_large"))
        }

    @Test
    fun `422 image_rejected maps to ModerationRejected`() =
        runTest {
            assertEquals(ImageUploadOutcome.ModerationRejected, outcomeFor(HttpStatusCode.UnprocessableEntity, "image_rejected"))
        }

    @Test
    fun `503 image_upload_unavailable maps to Unavailable`() =
        runTest {
            assertEquals(ImageUploadOutcome.Unavailable, outcomeFor(HttpStatusCode.ServiceUnavailable, "image_upload_unavailable"))
        }

    @Test
    fun `transport failure maps to Network`() =
        runTest {
            val repo = repository { throw RuntimeException("connection refused") }
            assertEquals(ImageUploadOutcome.Network, repo.upload(byteArrayOf(1), "image/jpeg"))
        }

    // ---- Defensive fallback: any unexpected non-2xx → Unavailable (NOT a silent success), logged. ----

    @Test
    fun `an unexpected non-2xx code maps to Unavailable with a logged diagnostic`() =
        runTest {
            val logs = mutableListOf<Pair<Int, String?>>()
            // The client-prevented 415 unsupported_image_type is unreachable in correct operation; if it
            // ever arrives it must map to the safe retryable terminal, NOT a silent success.
            val repo =
                repository(log = { status, code -> logs.add(status to code) }) {
                    respond(errorBody("unsupported_image_type"), HttpStatusCode.UnsupportedMediaType, JSON_HEADERS)
                }
            assertEquals(ImageUploadOutcome.Unavailable, repo.upload(byteArrayOf(1), "image/jpeg"))
            assertTrue(logs.any { it.first == 415 }, "an unexpected non-2xx must emit a diagnostic (not a silent map): $logs")
        }

    @Test
    fun `a non-2xx with an absent error code maps to Unavailable`() =
        runTest {
            // A bare 5xx with an empty/non-JSON body parses to errorCode = null → the defined fallback.
            val repo = repository { respond("", HttpStatusCode.BadGateway, JSON_HEADERS) }
            assertEquals(ImageUploadOutcome.Unavailable, repo.upload(byteArrayOf(1), "image/jpeg"))
        }

    // ---- Structural: ImageUploadOutcome has EXACTLY the nine declared sealed members. ----

    @Test
    fun `ImageUploadOutcome declares exactly the nine sealed members`() {
        // The spec's "every outcome is a declared sealed member" scenario: a when over the type is
        // exhaustive WITHOUT an else, and the set of members is precisely these nine. The exhaustive
        // when below would fail to compile if a member were added/removed; the count pins the size.
        val members =
            listOf(
                ImageUploadOutcome.Success(imageId = "i", deliveryUrl = "u"),
                ImageUploadOutcome.PremiumRequired,
                ImageUploadOutcome.FeatureDisabled,
                ImageUploadOutcome.QuotaExceeded,
                ImageUploadOutcome.Throttled,
                ImageUploadOutcome.ModerationRejected,
                ImageUploadOutcome.TooLarge,
                ImageUploadOutcome.Unavailable,
                ImageUploadOutcome.Network,
            )
        assertEquals(9, members.size)
        // Exhaustive when with NO else — compiles only while these nine are the complete member set.
        members.forEach { outcome ->
            val tag: String =
                when (outcome) {
                    is ImageUploadOutcome.Success -> "Success"
                    ImageUploadOutcome.PremiumRequired -> "PremiumRequired"
                    ImageUploadOutcome.FeatureDisabled -> "FeatureDisabled"
                    ImageUploadOutcome.QuotaExceeded -> "QuotaExceeded"
                    ImageUploadOutcome.Throttled -> "Throttled"
                    ImageUploadOutcome.ModerationRejected -> "ModerationRejected"
                    ImageUploadOutcome.TooLarge -> "TooLarge"
                    ImageUploadOutcome.Unavailable -> "Unavailable"
                    ImageUploadOutcome.Network -> "Network"
                }
            assertTrue(tag.isNotEmpty())
        }
    }
}
