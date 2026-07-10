package id.nearyou.app.data.block

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import kotlin.coroutines.cancellation.CancellationException

/** The `{ "error": { "code" } }` envelope (block-local mirror of the per-package error bodies). */
@Serializable
private data class BlockErrorBody(val error: BlockErrorDetail)

@Serializable
private data class BlockErrorDetail(val code: String? = null)

/**
 * The single shared block-create seam consumed by the profile AND post-detail (post-header +
 * reply-row) surfaces (mobile-block-from-content, design D2): wraps `POST /api/v1/blocks/{userId}`
 * on the shared bearer-authed [HttpClient] and maps each response to EXACTLY one member of the
 * sealed [BlockOutcome] — `204` → `Blocked`, `429` → `RateLimited(Retry-After)`, transport/any
 * other → `NetworkError`, with no generic "failed" wildcard. This IS the block-create mapping
 * previously inlined in `ProfileRepository.block` — relocated here so there is exactly ONE
 * block-create implementation (the anti-patchwork rule; the `data/report/ReportSubmitter`
 * precedent).
 *
 * A stateless seam: every call takes the target [userId] explicitly (so `single { … }` is safe).
 * The Bearer header + terminal 401 are owned by the shipped `Auth` plugin — this seam MUST NOT
 * reimplement token attachment or 401 refresh.
 *
 * PII discipline: the [diagnosticLog] sink carries only the HTTP `status` + `errorCode` primitives —
 * never a body, the [userId], or a coordinate; this seam never `println`s/logs bodies.
 *
 * `open` (class + [submit]) only so commonTest can substitute a `FakeBlockSubmitter` for the
 * ViewModel mapping tests (the `ReportSubmitter` precedent); production never subclasses it.
 */
open class BlockSubmitter(
    private val client: HttpClient,
    // Diagnostic sink for non-user-facing error detail (status + error code only). MUST NOT carry
    // tokens, bodies, the userId, or coordinates.
    private val diagnosticLog: (status: Int, errorCode: String?) -> Unit = { _, _ -> },
) {
    open suspend fun submit(userId: String): BlockOutcome {
        val response: HttpResponse =
            try {
                client.post("/api/v1/blocks/$userId")
            } catch (cause: CancellationException) {
                throw cause
            } catch (_: Throwable) {
                return BlockOutcome.NetworkError
            }
        return when {
            response.status == HttpStatusCode.NoContent -> BlockOutcome.Blocked
            response.status.value == 429 ->
                // An absent / HTTP-date / garbage Retry-After degrades to 0; never negative (a future
                // countdown consumer must not see a below-zero value).
                BlockOutcome.RateLimited(
                    (response.headers[HttpHeaders.RetryAfter]?.trim()?.toLongOrNull() ?: 0L).coerceAtLeast(0L),
                )
            else -> {
                // An unreachable-from-UI 404 (the affordance only shows on read content) + 5xx +
                // any other → the retryable NetworkError (a non-actionable failure).
                diagnosticLog(response.status.value, response.errorCode())
                BlockOutcome.NetworkError
            }
        }
    }

    /** Best-effort parse of the `{ "error": { "code" } }` envelope; null on an empty/non-JSON body. */
    private suspend fun HttpResponse.errorCode(): String? =
        try {
            body<BlockErrorBody>().error.code
        } catch (cause: CancellationException) {
            throw cause
        } catch (_: Throwable) {
            null
        }
}
