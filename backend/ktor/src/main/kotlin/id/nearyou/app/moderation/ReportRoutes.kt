package id.nearyou.app.moderation

import id.nearyou.app.auth.AUTH_PROVIDER_USER
import id.nearyou.app.auth.UserPrincipal
import id.nearyou.data.repository.ReportReasonCategory
import id.nearyou.data.repository.ReportTargetType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.text.Normalizer
import java.util.UUID

/**
 * `POST /api/v1/reports` — user-submitted content report.
 *
 * Error codes (matches `reports` spec Requirement "Error envelope matches existing
 * routes"):
 *  - `401 unauthenticated` (auth plugin)
 *  - `400 invalid_request` — malformed JSON, missing field, out-of-enum
 *    target_type / reason_category, non-UUID target_id, reason_note > 200 NFKC
 *    code points
 *  - `400 self_report_rejected` — `target_type = "user" && target_id = caller`
 *  - `404 target_not_found`
 *  - `409 duplicate_report`
 *  - `429 rate_limited` (with `Retry-After` header)
 *
 * Success: `204 No Content`, empty body.
 */
fun Application.reportRoutes(service: ReportService) {
    routing {
        authenticate(AUTH_PROVIDER_USER) {
            post("/api/v1/reports") {
                val principal =
                    call.principal<UserPrincipal>() ?: run {
                        call.respond(HttpStatusCode.Unauthorized)
                        return@post
                    }

                val body =
                    try {
                        call.receive<ReportRequest>()
                    } catch (_: Exception) {
                        call.respondInvalidRequest("Request body must be JSON.")
                        return@post
                    }

                val targetType = body.targetType?.let(ReportTargetType.Companion::fromWire)
                if (targetType == null) {
                    call.respondInvalidRequest("target_type must be one of post, reply, user, chat_message.")
                    return@post
                }
                val targetId = body.targetId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                if (targetId == null) {
                    call.respondInvalidRequest("target_id must be a UUID.")
                    return@post
                }
                val reasonCategory =
                    body.reasonCategory?.let(ReportReasonCategory.Companion::fromWire)
                if (reasonCategory == null) {
                    call.respondInvalidRequest(
                        "reason_category must be one of spam, hate_speech_sara, harassment, adult_content, " +
                            "misinformation, self_harm, csam_suspected, other.",
                    )
                    return@post
                }
                val reasonNote = normalizeReasonNote(body.reasonNote)
                if (reasonNote != null && reasonNote.codePointCount(0, reasonNote.length) > MAX_NOTE_CODE_POINTS) {
                    call.respondInvalidRequest("reason_note must be ≤ $MAX_NOTE_CODE_POINTS code points.")
                    return@post
                }

                when (
                    val result =
                        service.submit(
                            reporterId = principal.userId,
                            targetType = targetType,
                            targetId = targetId,
                            reasonCategory = reasonCategory,
                            reasonNote = reasonNote,
                        )
                ) {
                    is ReportService.Result.Success ->
                        call.respond(HttpStatusCode.NoContent)
                    ReportService.Result.SelfReportRejected ->
                        call.respond(
                            HttpStatusCode.BadRequest,
                            errorEnvelope(SELF_REPORT_REJECTED, "Cannot report yourself."),
                        )
                    ReportService.Result.TargetNotFound ->
                        call.respond(
                            HttpStatusCode.NotFound,
                            errorEnvelope(TARGET_NOT_FOUND, "Report target does not exist."),
                        )
                    ReportService.Result.Duplicate ->
                        call.respond(
                            HttpStatusCode.Conflict,
                            errorEnvelope(DUPLICATE_REPORT, "You have already reported this target."),
                        )
                    is ReportService.Result.RateLimited -> {
                        call.response.header(HttpHeaders.RetryAfter, result.retryAfterSeconds.toString())
                        call.respond(
                            HttpStatusCode.TooManyRequests,
                            errorEnvelope(RATE_LIMITED, "Too many reports. Try again later."),
                        )
                    }
                }
            }
        }
    }
}

@Serializable
data class ReportRequest(
    @SerialName("target_type") val targetType: String? = null,
    @SerialName("target_id") val targetId: String? = null,
    @SerialName("reason_category") val reasonCategory: String? = null,
    @SerialName("reason_note") val reasonNote: String? = null,
)

// Error codes — match the `reports` capability spec's error-envelope requirement.
private const val INVALID_REQUEST = "invalid_request"
private const val SELF_REPORT_REJECTED = "self_report_rejected"
private const val TARGET_NOT_FOUND = "target_not_found"
private const val DUPLICATE_REPORT = "duplicate_report"
private const val RATE_LIMITED = "rate_limited"

private const val MAX_NOTE_CODE_POINTS = 200

private fun errorEnvelope(code: String, message: String): Map<String, Any> =
    mapOf("error" to mapOf("code" to code, "message" to message))

private fun normalizeReasonNote(raw: String?): String? {
    if (raw == null) return null
    val normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC)
    return normalized
}

private suspend fun io.ktor.server.application.ApplicationCall.respondInvalidRequest(message: String) {
    respond(HttpStatusCode.BadRequest, errorEnvelope(INVALID_REQUEST, message))
}
