package id.nearyou.app.push

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

/** Max token / app_version lengths — mirror the backend `FcmTokenRoutes.kt` `MAX_TOKEN_LEN` / `MAX_APP_VERSION_LEN`. */
private const val MAX_TOKEN_LEN = 4096
private const val MAX_APP_VERSION_LEN = 64

/**
 * `POST /api/v1/user/fcm-token` request body — the `{token, platform, app_version}` triple the SHIPPED
 * backend (`backend/ktor/.../user/FcmTokenRequest.kt`) deserializes. `app_version` is nullable on the
 * wire (the backend column is nullable); `explicitNulls = false` on the shared `Json` omits it when null,
 * which the backend accepts.
 */
@Serializable
data class FcmTokenRegistrationRequest(
    val token: String,
    val platform: String,
    @SerialName("app_version") val appVersion: String? = null,
)

/**
 * The error-body shape of a `400` rejection — `{"error":<code>}` (the SHIPPED `FcmTokenRoutes.kt` closed
 * vocabulary). Defaulted so a malformed/absent body decodes to `Rejected(unknown)` rather than throwing.
 */
@Serializable
private data class FcmTokenErrorBody(val error: String = FcmRegistrationOutcome.UNKNOWN)

/**
 * Thin wrapper over the shared (bearer-authed) [HttpClient] for `POST /api/v1/user/fcm-token`
 * (`mobile-fcm-token-registration`). The Bearer `Authorization` header is attached by the shipped `Auth`
 * plugin — this client MUST NOT reimplement token attachment or 401 refresh (mirrors `ConsentApiClient` /
 * `NotificationsApiClient`).
 *
 * Client-side guards (mirroring the backend validators) reject a malformed value BEFORE any request so a
 * silent registrar never round-trips a 400 it cannot surface: the token is trimmed + must be non-empty +
 * ≤ [MAX_TOKEN_LEN]; the [appVersion], when present, must be ≤ [MAX_APP_VERSION_LEN]. [platform] is a
 * compile-time constant supplied per platform (`"android"` / `"ios"`), never runtime-detected.
 *
 * Token confidentiality: the raw token is NEVER logged here (it is a device-addressed credential) —
 * mirrors backend D11. This client makes no logging call.
 */
class FcmTokenApiClient(
    private val client: HttpClient,
    private val platform: String,
    private val appVersion: String?,
) {
    suspend fun register(token: String): FcmRegistrationOutcome {
        // Client-side guards (no request issued on failure) — mirror the backend empty_token / token_too_long /
        // app_version_too_long validators so the silent registrar never round-trips an un-surfaceable 400.
        val trimmed = token.trim()
        if (trimmed.isEmpty()) return FcmRegistrationOutcome.Rejected(FcmRegistrationOutcome.EMPTY_TOKEN)
        if (trimmed.length > MAX_TOKEN_LEN) return FcmRegistrationOutcome.Rejected(FcmRegistrationOutcome.TOKEN_TOO_LONG)
        if ((appVersion?.length ?: 0) > MAX_APP_VERSION_LEN) {
            return FcmRegistrationOutcome.Rejected(FcmRegistrationOutcome.APP_VERSION_TOO_LONG)
        }

        val response: HttpResponse =
            try {
                client.post("/api/v1/user/fcm-token") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        FcmTokenRegistrationRequest(
                            token = trimmed,
                            platform = platform,
                            appVersion = appVersion,
                        ),
                    )
                }
            } catch (cause: CancellationException) {
                // Never swallow cancellation — let structured concurrency unwind (mirrors AuthApiClient).
                throw cause
            } catch (_: Throwable) {
                return FcmRegistrationOutcome.TransportError
            }

        return when (response.status) {
            HttpStatusCode.NoContent -> FcmRegistrationOutcome.Registered
            HttpStatusCode.BadRequest -> FcmRegistrationOutcome.Rejected(parseErrorCode(response))
            HttpStatusCode.Unauthorized -> FcmRegistrationOutcome.Unauthorized
            else -> FcmRegistrationOutcome.TransportError
        }
    }

    /** Parse the closed `{"error":<code>}` vocabulary; an unrecognized / unparseable body → [FcmRegistrationOutcome.UNKNOWN]. */
    private suspend fun parseErrorCode(response: HttpResponse): String =
        try {
            when (val code = response.body<FcmTokenErrorBody>().error) {
                FcmRegistrationOutcome.MALFORMED_BODY,
                FcmRegistrationOutcome.INVALID_PLATFORM,
                FcmRegistrationOutcome.EMPTY_TOKEN,
                FcmRegistrationOutcome.TOKEN_TOO_LONG,
                FcmRegistrationOutcome.APP_VERSION_TOO_LONG,
                -> code
                else -> FcmRegistrationOutcome.UNKNOWN
            }
        } catch (cause: CancellationException) {
            throw cause
        } catch (_: Throwable) {
            FcmRegistrationOutcome.UNKNOWN
        }
}
