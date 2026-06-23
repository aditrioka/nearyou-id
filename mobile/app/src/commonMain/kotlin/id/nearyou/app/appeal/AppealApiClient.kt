package id.nearyou.app.appeal

import id.nearyou.app.post.retryAfterSeconds
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
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

/**
 * `POST /api/v1/appeals` request body — snake_case `appeal_text`, mirroring the SHIPPED
 * `AppealSubmitRequest` wire in `backend/ktor/.../appeal/AppealRoutes.kt` (NOT a spec JSON example —
 * the PR #128 casing-drift trap).
 */
@Serializable
data class AppealSubmitRequestDto(
    @SerialName("appeal_text") val appealText: String,
)

/** `201` success body — the shipped `AppealCreatedResponse` (`{ "id": "<uuid>", "status": "pending" }`). */
@Serializable
data class AppealCreatedResponseDto(
    val id: String,
    val status: String,
)

/**
 * `200` own-status body — the shipped `AppealStatusResponse`. `has_appeal = false` (no fields) when the
 * caller has never appealed; otherwise the latest appeal's snake_case fields.
 */
@Serializable
data class AppealStatusResponseDto(
    @SerialName("has_appeal") val hasAppeal: Boolean,
    @SerialName("action_type") val actionType: String? = null,
    val status: String? = null,
    @SerialName("decision_reason") val decisionReason: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("reviewed_at") val reviewedAt: String? = null,
)

/**
 * The appeal endpoints use the NESTED `{ "error": { "code", "message" } }` envelope (the feature-route
 * `errorEnvelope` shape) — NOT the flat `{ "error": "<code>" }` some packages use. The repository reads
 * [AppealErrorDetail.code] to split the two same-status `409`s (no_actionable_moderation vs
 * appeal_already_pending).
 */
@Serializable
data class AppealErrorBody(val error: AppealErrorDetail? = null)

@Serializable
data class AppealErrorDetail(val code: String? = null, val message: String? = null)

/** Low-level result of the `POST` submit. `201` → [Success]; any non-201 → [HttpError]; transport/parse → [NetworkError]. */
sealed interface AppealSubmitApiResult {
    data class Success(val body: AppealCreatedResponseDto) : AppealSubmitApiResult

    data class HttpError(val status: Int, val errorCode: String?, val retryAfterSeconds: Long?) : AppealSubmitApiResult

    data class NetworkError(val cause: Throwable) : AppealSubmitApiResult
}

/** Low-level result of the `GET` own-status read. `200` → [Success]; non-200 → [HttpError]; transport/parse → [NetworkError]. */
sealed interface AppealStatusApiResult {
    data class Success(val body: AppealStatusResponseDto) : AppealStatusApiResult

    data class HttpError(val status: Int, val errorCode: String?, val retryAfterSeconds: Long?) : AppealStatusApiResult

    data class NetworkError(val cause: Throwable) : AppealStatusApiResult
}

/**
 * Thin wrapper over a RAW [HttpClient] (NO `Auth` plugin) for the two ban-exempt appeal endpoints
 * (`content-moderation-appeal`). Unlike every other mobile API client, the caller is `is_banned` and has
 * NO normal access token in `TokenStore` — so the limited **appeal token** (captured from the sign-in
 * `403` body) is attached EXPLICITLY per call via the `Authorization` header. The production client is the
 * dedicated raw client (no Bearer plugin → no `loadTokens`/`refreshTokens` interference); tests pass a
 * MockEngine client.
 *
 * PII / logging posture: never logs bodies. The appeal token travels only in the `Authorization` header,
 * which the shared logging `sanitizeHeader` masks to `***` on the raw client when logging is installed.
 */
class AppealApiClient(
    private val client: HttpClient,
) {
    suspend fun submit(
        appealText: String,
        appealToken: String,
    ): AppealSubmitApiResult {
        val response: HttpResponse =
            try {
                client.post("/api/v1/appeals") {
                    header(HttpHeaders.Authorization, "Bearer $appealToken")
                    contentType(ContentType.Application.Json)
                    setBody(AppealSubmitRequestDto(appealText = appealText))
                }
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                return AppealSubmitApiResult.NetworkError(cause)
            }
        if (response.status == HttpStatusCode.Created) {
            return try {
                AppealSubmitApiResult.Success(response.body<AppealCreatedResponseDto>())
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                AppealSubmitApiResult.NetworkError(cause)
            }
        }
        return AppealSubmitApiResult.HttpError(
            status = response.status.value,
            errorCode = response.errorCode(),
            retryAfterSeconds = response.retryAfterSeconds(),
        )
    }

    suspend fun status(appealToken: String): AppealStatusApiResult {
        val response: HttpResponse =
            try {
                client.get("/api/v1/appeals") {
                    header(HttpHeaders.Authorization, "Bearer $appealToken")
                }
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                return AppealStatusApiResult.NetworkError(cause)
            }
        if (response.status == HttpStatusCode.OK) {
            return try {
                AppealStatusApiResult.Success(response.body<AppealStatusResponseDto>())
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                AppealStatusApiResult.NetworkError(cause)
            }
        }
        return AppealStatusApiResult.HttpError(
            status = response.status.value,
            errorCode = response.errorCode(),
            retryAfterSeconds = response.retryAfterSeconds(),
        )
    }

    /** Best-effort parse of the nested `{ "error": { "code" } }` envelope; null on an empty/non-JSON body. */
    private suspend fun HttpResponse.errorCode(): String? =
        try {
            body<AppealErrorBody>().error?.code
        } catch (cause: CancellationException) {
            throw cause
        } catch (_: Throwable) {
            null
        }
}
