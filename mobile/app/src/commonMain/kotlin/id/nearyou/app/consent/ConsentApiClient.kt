package id.nearyou.app.consent

import io.ktor.client.HttpClient
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.coroutines.cancellation.CancellationException

/**
 * `PATCH /api/v1/user/consent` request body — the complete `{analytics, crash, ads_personalization}`
 * triple, snake_case to match the `users.analytics_consent` JSONB sub-keys (the backend
 * `analytics-consent-update` capability full-object-writes it). The screen always submits all three,
 * so there is no partial-update shape.
 */
@Serializable
data class ConsentUpdateRequest(
    val analytics: Boolean,
    val crash: Boolean,
    @SerialName("ads_personalization") val adsPersonalization: Boolean,
)

/**
 * Low-level result of the consent PATCH. [ConsentRepository] maps this onto the user-facing
 * [ConsentOutcome], keying on [HttpError.status] (design D5 — status-driven, no parsed `error.code`).
 * The `200` response body (the echoed triple) is not read — the client only needs the status.
 */
sealed interface ConsentApiResult {
    data object Success : ConsentApiResult

    /** Non-2xx response — mapped on [status] alone. */
    data class HttpError(val status: Int) : ConsentApiResult

    /** Transport-level failure (IOException, timeout, host unreachable). */
    data class NetworkError(val cause: Throwable) : ConsentApiResult
}

/**
 * Thin wrapper over the shared (bearer-authed) [HttpClient] for `PATCH /api/v1/user/consent`. The
 * `Auth { bearer }` interceptor on the shared client supplies the access token — this client adds no
 * auth header itself and never touches the token. Never logs the body or any token (PII discipline,
 * `mobile-analytics-consent` spec).
 */
class ConsentApiClient(private val client: HttpClient) {
    suspend fun submitConsent(
        analytics: Boolean,
        crash: Boolean,
        adsPersonalization: Boolean,
    ): ConsentApiResult {
        val response: HttpResponse =
            try {
                client.patch("/api/v1/user/consent") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        ConsentUpdateRequest(
                            analytics = analytics,
                            crash = crash,
                            adsPersonalization = adsPersonalization,
                        ),
                    )
                }
            } catch (cause: CancellationException) {
                // Never swallow cancellation — let structured concurrency unwind.
                throw cause
            } catch (cause: Throwable) {
                return ConsentApiResult.NetworkError(cause)
            }

        return if (response.status == HttpStatusCode.OK) {
            ConsentApiResult.Success
        } else {
            ConsentApiResult.HttpError(status = response.status.value)
        }
    }
}
