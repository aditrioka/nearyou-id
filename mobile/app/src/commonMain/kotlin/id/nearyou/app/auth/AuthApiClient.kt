package id.nearyou.app.auth

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock

private const val ACCESS_TOKEN_TTL_SECONDS_DEFAULT = 900

/** `POST /api/v1/auth/signin` request body. `device_fingerprint_hash` is omitted (Decision 9). */
@Serializable
data class SignInRequest(
    val provider: String,
    @SerialName("id_token") val idToken: String,
)

/** `POST /api/v1/auth/refresh` request body. */
@Serializable
data class RefreshRequest(
    @SerialName("refresh_token") val refreshToken: String,
)

/** Shared 200 body for both `/signin` and `/refresh` per the auth-signin + auth-session specs. */
@Serializable
data class SignInResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: Int = ACCESS_TOKEN_TTL_SECONDS_DEFAULT,
)

/** Backend error envelope: `{ "error": { "code": "user_not_found", ... } }`. */
@Serializable
data class BackendErrorBody(
    val error: ErrorEnvelope,
)

@Serializable
data class ErrorEnvelope(
    val code: String,
)

/**
 * Low-level result of the `/signin` exchange. `AuthRepository` maps this onto the
 * user-facing `SignInOutcome` (Decision 7); keeping the HTTP detail here lets the repository
 * stay free of status-code arithmetic.
 */
sealed interface SignInApiResult {
    data class Success(val tokens: TokenPair) : SignInApiResult

    /** Non-2xx response. [code] is the backend error envelope's `error.code` when parseable. */
    data class HttpError(val status: Int, val code: String?) : SignInApiResult

    /** Transport-level failure (IOException, timeout, host unreachable). */
    data class NetworkError(val cause: Throwable) : SignInApiResult
}

/**
 * Thin wrapper over the shared [HttpClient] for the unauthenticated `/signin` exchange.
 *
 * The `/signin` request body is exactly `{ "provider": "google", "id_token": <token> }` — no
 * `device_fingerprint_hash` (Decision 9). The canonical unified-provider endpoint
 * `/api/v1/auth/signin` is used (NOT a per-provider sub-path) per
 * `openspec/specs/mobile-auth-signin/spec.md` § "Sign-in API call uses canonical
 * unified-provider endpoint".
 */
class AuthApiClient(
    private val client: HttpClient,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    suspend fun signIn(idToken: String): SignInApiResult {
        val response: HttpResponse =
            try {
                client.post("/api/v1/auth/signin") {
                    contentType(ContentType.Application.Json)
                    setBody(SignInRequest(provider = "google", idToken = idToken))
                }
            } catch (cause: CancellationException) {
                // Never swallow cancellation — let structured concurrency unwind so the
                // caller (AuthRepository) can map it to SignInOutcome.Cancelled (§6).
                throw cause
            } catch (cause: Throwable) {
                return SignInApiResult.NetworkError(cause)
            }

        if (response.status == HttpStatusCode.OK) {
            return try {
                val body = response.body<SignInResponse>()
                SignInApiResult.Success(
                    TokenPair(
                        accessToken = body.accessToken,
                        refreshToken = body.refreshToken,
                        accessExpiresAtEpochMillis = nowMillis() + body.expiresIn * 1000L,
                    ),
                )
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                SignInApiResult.NetworkError(cause)
            }
        }

        val code =
            try {
                response.body<BackendErrorBody>().error.code
            } catch (cause: CancellationException) {
                throw cause
            } catch (_: Throwable) {
                null
            }
        return SignInApiResult.HttpError(status = response.status.value, code = code)
    }
}
