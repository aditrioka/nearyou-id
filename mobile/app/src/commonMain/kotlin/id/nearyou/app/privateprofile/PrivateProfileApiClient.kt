package id.nearyou.app.privateprofile

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

/** `GET /api/v1/user/private-profile` response — the stored flag + effective-Premium status. */
@Serializable
data class PrivateProfileStateDto(
    val privateProfile: Boolean,
    val premium: Boolean,
)

/** `PATCH /api/v1/user/private-profile` request body (bare camelCase, matching the backend DTO). */
@Serializable
data class PrivateProfileUpdateBody(
    val privateProfile: Boolean,
)

/** Low-level result of the state read. The repository maps this to a domain state (or null on failure). */
sealed interface PrivateProfileStateResult {
    data class Success(val privateProfile: Boolean, val premium: Boolean) : PrivateProfileStateResult

    /** Non-2xx OR a transport/parse failure — the screen defaults to off / non-Premium. */
    data object Failure : PrivateProfileStateResult
}

/** Low-level result of the toggle write. */
sealed interface PrivateProfileWriteResult {
    data object Success : PrivateProfileWriteResult

    /** Non-2xx OR a transport failure — the screen reverts the toggle and surfaces an error. */
    data object Failure : PrivateProfileWriteResult
}

/**
 * Thin wrapper over the shared (bearer-authed) [HttpClient] for the `private-profile` endpoints. The
 * `Auth { bearer }` interceptor on the shared client supplies the access token — this client adds no
 * auth header and never touches the token. Mirrors `HideDistanceApiClient`.
 */
class PrivateProfileApiClient(private val client: HttpClient) {
    suspend fun fetchState(): PrivateProfileStateResult {
        val response: HttpResponse =
            try {
                client.get("/api/v1/user/private-profile")
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                return PrivateProfileStateResult.Failure
            }
        if (response.status != HttpStatusCode.OK) return PrivateProfileStateResult.Failure
        return try {
            val dto = response.body<PrivateProfileStateDto>()
            PrivateProfileStateResult.Success(privateProfile = dto.privateProfile, premium = dto.premium)
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Throwable) {
            PrivateProfileStateResult.Failure
        }
    }

    suspend fun setPrivateProfile(value: Boolean): PrivateProfileWriteResult {
        val response: HttpResponse =
            try {
                client.patch("/api/v1/user/private-profile") {
                    contentType(ContentType.Application.Json)
                    setBody(PrivateProfileUpdateBody(privateProfile = value))
                }
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                return PrivateProfileWriteResult.Failure
            }
        return if (response.status == HttpStatusCode.OK) {
            PrivateProfileWriteResult.Success
        } else {
            PrivateProfileWriteResult.Failure
        }
    }
}
