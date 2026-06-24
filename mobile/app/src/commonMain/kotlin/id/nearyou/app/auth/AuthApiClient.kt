package id.nearyou.app.auth

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
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

/**
 * `POST /api/v1/auth/signup` request body (Mobile #4). Carries exactly `{provider, id_token,
 * date_of_birth}` in snake_case per the `auth-signup` spec wire contract (pinned by the backend
 * `AuthWireFormatTest`). `device_fingerprint_hash` is omitted (design D4 — attestation deferred,
 * consistent with the sign-in DTO's Decision 9). `dateOfBirth` is an ISO-8601 `YYYY-MM-DD` string
 * (the `LocalDate.toString()` form).
 */
@Serializable
data class SignUpRequest(
    val provider: String,
    @SerialName("id_token") val idToken: String,
    @SerialName("date_of_birth") val dateOfBirth: String,
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
    val error: ErrorEnvelope? = null,
    // content-moderation-appeal: the banned/suspended sign-in `403` carries the limited appeal token
    // alongside the error envelope (`auth-signin`). Null on every other error body.
    @SerialName("appeal_token") val appealToken: String? = null,
)

@Serializable
data class ErrorEnvelope(
    val code: String,
)

/**
 * Low-level result of an auth token-exchange call (`/signin` AND `/signup` — both yield a token
 * pair on success, an HTTP status on rejection, or a transport failure). `AuthRepository` maps
 * this onto the user-facing `SignInOutcome` / `SignUpOutcome`, keying on [HttpError.status] so it
 * stays free of status-code arithmetic AND so the flat-vs-nested `/signup` error envelopes never
 * affect routing (design D2/D8 — [code] is informational only and is `null` for the flat `403`).
 */
sealed interface SignInApiResult {
    data class Success(val tokens: TokenPair) : SignInApiResult

    /** Non-2xx response. [code] is the backend error envelope's `error.code` when parseable
     *  (the flat `/signup` `403 user_blocked` body has no `code` → `null`; map on [status]).
     *  [appealToken] is the limited appeal token from the banned/suspended sign-in `403` body
     *  (content-moderation-appeal); null on every other non-2xx. [retryAfterSeconds] is the
     *  parsed `Retry-After` header on a `429` (auth-endpoint-rate-limits); null when absent /
     *  unparseable / not a `429`. */
    data class HttpError(
        val status: Int,
        val code: String?,
        val appealToken: String? = null,
        val retryAfterSeconds: Long? = null,
    ) : SignInApiResult

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

        val parsed =
            try {
                response.body<BackendErrorBody>()
            } catch (cause: CancellationException) {
                throw cause
            } catch (_: Throwable) {
                null
            }
        return SignInApiResult.HttpError(
            status = response.status.value,
            code = parsed?.error?.code,
            appealToken = parsed?.appealToken,
            retryAfterSeconds = response.headers[HttpHeaders.RetryAfter]?.toLongOrNull(),
        )
    }

    /**
     * `POST /api/v1/auth/signup` (Mobile #4). Body is `{provider:"google", id_token, date_of_birth}`
     * — snake_case, NO `device_fingerprint_hash` (design D4). Success is HTTP **`201 Created`** (not
     * `200`). On non-201 the [SignInApiResult.HttpError.status] is what `AuthRepository` keys on:
     * the `403 user_blocked` body is the FLAT `{"error":"user_blocked",...}` shape that
     * [BackendErrorBody] (nested parser) cannot decode, so [code] comes back `null` for it — that
     * is fine and expected, because the outcome mapping is status-driven (design D2). The `403`
     * body is deliberately NOT logged (`LogLevel.HEADERS` already excludes bodies).
     */
    suspend fun signUp(
        idToken: String,
        dateOfBirth: String,
    ): SignInApiResult {
        val response: HttpResponse =
            try {
                client.post("/api/v1/auth/signup") {
                    contentType(ContentType.Application.Json)
                    setBody(SignUpRequest(provider = "google", idToken = idToken, dateOfBirth = dateOfBirth))
                }
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                return SignInApiResult.NetworkError(cause)
            }

        if (response.status == HttpStatusCode.Created) {
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

        // Best-effort parse of the NESTED envelope (400/401/409/503); the FLAT 403 body yields
        // null here — intentionally informational only, since the repository maps on `status`.
        val parsed =
            try {
                response.body<BackendErrorBody>()
            } catch (cause: CancellationException) {
                throw cause
            } catch (_: Throwable) {
                null
            }
        return SignInApiResult.HttpError(
            status = response.status.value,
            code = parsed?.error?.code,
            appealToken = parsed?.appealToken,
            retryAfterSeconds = response.headers[HttpHeaders.RetryAfter]?.toLongOrNull(),
        )
    }
}
