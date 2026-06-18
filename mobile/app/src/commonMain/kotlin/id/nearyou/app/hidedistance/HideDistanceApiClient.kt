package id.nearyou.app.hidedistance

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlin.coroutines.cancellation.CancellationException

/** `GET /api/v1/user/hide-distance` response — the stored flag + effective-Premium status. */
@Serializable
data class HideDistanceStateDto(
    val hideDistance: Boolean,
    val premium: Boolean,
)

/** `PATCH /api/v1/user/hide-distance` request body (bare camelCase, matching the backend DTO). */
@Serializable
data class HideDistanceUpdateBody(
    val hideDistance: Boolean,
)

/** Low-level result of the state read. The repository maps this to a domain state (or null on failure). */
sealed interface HideDistanceStateResult {
    data class Success(val hideDistance: Boolean, val premium: Boolean) : HideDistanceStateResult

    /** Non-2xx OR a transport/parse failure — the screen defaults to off / non-Premium. */
    data object Failure : HideDistanceStateResult
}

/** Low-level result of the toggle write. */
sealed interface HideDistanceWriteResult {
    data object Success : HideDistanceWriteResult

    /** Non-2xx OR a transport failure — the screen reverts the toggle and surfaces an error. */
    data object Failure : HideDistanceWriteResult
}

/**
 * Thin wrapper over the shared (bearer-authed) [HttpClient] for the `hide-distance` endpoints. The
 * `Auth { bearer }` interceptor on the shared client supplies the access token — this client adds no
 * auth header and never touches the token. Mirrors `ConsentApiClient` / `NearbyTimelineApiClient`.
 */
class HideDistanceApiClient(private val client: HttpClient) {
    suspend fun fetchState(): HideDistanceStateResult {
        val response: HttpResponse =
            try {
                client.get("/api/v1/user/hide-distance")
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                return HideDistanceStateResult.Failure
            }
        if (response.status != HttpStatusCode.OK) return HideDistanceStateResult.Failure
        return try {
            val dto = response.body<HideDistanceStateDto>()
            HideDistanceStateResult.Success(hideDistance = dto.hideDistance, premium = dto.premium)
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Throwable) {
            HideDistanceStateResult.Failure
        }
    }

    suspend fun setHideDistance(value: Boolean): HideDistanceWriteResult {
        val response: HttpResponse =
            try {
                client.patch("/api/v1/user/hide-distance") {
                    contentType(ContentType.Application.Json)
                    setBody(HideDistanceUpdateBody(hideDistance = value))
                }
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                return HideDistanceWriteResult.Failure
            }
        return if (response.status == HttpStatusCode.OK) {
            HideDistanceWriteResult.Success
        } else {
            HideDistanceWriteResult.Failure
        }
    }
}
