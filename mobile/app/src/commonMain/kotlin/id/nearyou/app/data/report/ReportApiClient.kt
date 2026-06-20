package id.nearyou.app.data.report

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

/**
 * `POST /api/v1/reports` request body, mirroring the SHIPPED `reports` wire — **snake_case**
 * (`target_type`, `target_id`, `reason_category`, `reason_note`). [targetType] is `user` / `post` /
 * `reply` (the shared seam parameterizes it). [reasonNote] is omitted from the JSON when null (the
 * app-wide `Json { explicitNulls = false }`), so a blank note is normalized to null by the client.
 */
@Serializable
data class ReportRequest(
    @SerialName("target_type") val targetType: String,
    @SerialName("target_id") val targetId: String,
    @SerialName("reason_category") val reasonCategory: String,
    @SerialName("reason_note") val reasonNote: String? = null,
)

/** The `{ "error": { "code" } }` envelope (report-local mirror of the per-package error bodies). */
@Serializable
private data class ReportErrorBody(val error: ReportErrorDetail)

@Serializable
private data class ReportErrorDetail(val code: String? = null)

/**
 * Low-level result of a report submission. `204` → [NoContent]; any non-204 → [HttpError] (status +
 * best-effort `error.code` + parsed `Retry-After` seconds, present on a 429); transport → [NetworkError].
 * A non-2xx is a VALUE, never a thrown exception (mirrors `ActionApiResult` / `LikeApiResult`).
 */
sealed interface ReportApiResult {
    data object NoContent : ReportApiResult

    data class HttpError(val status: Int, val errorCode: String?, val retryAfterSeconds: Long?) : ReportApiResult

    data class NetworkError(val cause: Throwable) : ReportApiResult
}

/**
 * Thin wrapper over the shared [HttpClient] for the SHIPPED `POST /api/v1/reports` endpoint, generic over
 * the target — consumed by BOTH the profile (`target_type = "user"`) and post-detail
 * (`target_type = "post"` / `"reply"`) surfaces (mobile-content-report; relocated from `ProfileApiClient`).
 * The Bearer `Authorization` header is attached by the shipped `Auth` plugin and a terminal 401 is handled
 * there (→ `SessionInvalidator` → `SignInScreen`) — this client MUST NOT reimplement token attachment or
 * 401 refresh. NO `X-Session-Id` header (the reports endpoint is not per-session soft-capped). This client
 * MUST NOT `println`/log bodies, the `targetId`, or any coordinate, and MUST NOT widen the client log
 * level (PII discipline).
 */
class ReportApiClient(
    private val client: HttpClient,
) {
    suspend fun submit(
        targetType: String,
        targetId: String,
        reasonCategory: String,
        reasonNote: String?,
    ): ReportApiResult =
        action {
            client.post("/api/v1/reports") {
                contentType(ContentType.Application.Json)
                setBody(
                    ReportRequest(
                        targetType = targetType,
                        targetId = targetId,
                        reasonCategory = reasonCategory,
                        // A blank note is normalized to null so the key is omitted from the body.
                        reasonNote = reasonNote?.takeIf { it.isNotBlank() },
                    ),
                )
            }
        }

    private suspend fun action(execute: suspend () -> HttpResponse): ReportApiResult {
        val response: HttpResponse =
            try {
                execute()
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                return ReportApiResult.NetworkError(cause)
            }
        if (response.status == HttpStatusCode.NoContent) return ReportApiResult.NoContent
        return ReportApiResult.HttpError(
            status = response.status.value,
            errorCode = response.errorCode(),
            retryAfterSeconds = response.headers[HttpHeaders.RetryAfter]?.trim()?.toLongOrNull(),
        )
    }

    /** Best-effort parse of the `{ "error": { "code" } }` envelope; null on an empty/non-JSON body. */
    private suspend fun HttpResponse.errorCode(): String? =
        try {
            body<ReportErrorBody>().error.code
        } catch (cause: CancellationException) {
            throw cause
        } catch (_: Throwable) {
            null
        }
}
