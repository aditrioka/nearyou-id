package id.nearyou.app.data.ads

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

/**
 * The `GET /api/v1/config/ads` wire body — camelCase, matching the backend `AdsConfigResponse`. Defaults
 * mirror the fail-safe posture (ads OFF, frequency 6) so a partial/missing payload degrades to no ads.
 */
@Serializable
data class AdsConfigResponse(
    val adsEnabled: Boolean = false,
    val timelineFrequency: Int = 6,
)

/** Low-level fetch outcome keyed on HTTP status (never on an `error.code`). */
sealed interface AdsConfigApiResult {
    data class Success(val body: AdsConfigResponse) : AdsConfigApiResult

    data class HttpError(val status: Int) : AdsConfigApiResult

    data class NetworkError(val cause: Throwable) : AdsConfigApiResult
}

/**
 * Thin Ktor client for the ads-config read. The Bearer token is attached by the shared client's Auth
 * plugin (relative path, no header plumbing here). mobile-admob-ads-foundation.
 */
class AdsConfigApiClient(private val client: HttpClient) {
    suspend fun fetch(): AdsConfigApiResult {
        val response: HttpResponse =
            try {
                client.get("/api/v1/config/ads")
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                return AdsConfigApiResult.NetworkError(cause)
            }
        if (response.status == HttpStatusCode.OK) {
            return try {
                AdsConfigApiResult.Success(response.body())
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                AdsConfigApiResult.NetworkError(cause)
            }
        }
        return AdsConfigApiResult.HttpError(response.status.value)
    }
}
