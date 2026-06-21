package id.nearyou.app.appeal

import id.nearyou.app.auth.AUTH_PROVIDER_APPEAL
import id.nearyou.app.auth.UserPrincipal
import id.nearyou.app.guard.ContentLengthGuard
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("id.nearyou.app.appeal.AppealRoutes")

@Serializable
data class AppealSubmitRequest(
    @SerialName("appeal_text") val appealText: String? = null,
)

@Serializable
data class AppealCreatedResponse(
    val id: String,
    val status: String,
)

@Serializable
data class AppealStatusResponse(
    @SerialName("has_appeal") val hasAppeal: Boolean,
    @SerialName("action_type") val actionType: String? = null,
    val status: String? = null,
    @SerialName("decision_reason") val decisionReason: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("reviewed_at") val reviewedAt: String? = null,
)

/**
 * `/api/v1/appeals` — the ban/suspension appeal surface (`content-moderation-appeal`),
 * mounted under the ban-exempt [AUTH_PROVIDER_APPEAL] realm so an actioned (`is_banned`)
 * caller — 403'd on every standard authenticated route — can reach it. The realm still
 * enforces `token_version` revocation; only the ban gate is relaxed.
 *
 *  - `POST` — submit one appeal. `201` on success; `409 no_actionable_moderation`
 *    (caller not banned — the SAME envelope for a normal and a shadow-banned-only
 *    user, so the shadow-ban state cannot leak); `409 appeal_already_pending`;
 *    `429 rate_limited` (daily cap, with `Retry-After`). `appeal_text` is length-
 *    guarded ≤1000 code points BEFORE any DB work (→ `400 content_empty` /
 *    `content_too_long` via StatusPages).
 *  - `GET`  — the caller's latest appeal status (the pull-only decision-outcome
 *    surface), or `has_appeal = false` when they have never appealed.
 */
fun Application.appealRoutes(
    service: AppealService,
    contentGuard: ContentLengthGuard,
) {
    routing {
        authenticate(AUTH_PROVIDER_APPEAL) {
            route("/api/v1/appeals") {
                post {
                    val principal =
                        call.principal<UserPrincipal>() ?: run {
                            call.respond(HttpStatusCode.Unauthorized)
                            return@post
                        }
                    val body =
                        try {
                            call.receive<AppealSubmitRequest>()
                        } catch (_: Exception) {
                            call.respond(HttpStatusCode.BadRequest, errorEnvelope(INVALID_REQUEST, "Request body must be JSON."))
                            return@post
                        }
                    // Length guard BEFORE the DB — throws → StatusPages maps to
                    // 400 content_empty / content_too_long (the V31 CHECK is the backstop).
                    val appealText = contentGuard.enforce(APPEAL_TEXT_KEY, body.appealText)

                    when (val outcome = service.submit(principal.userId, appealText)) {
                        is AppealService.SubmitOutcome.Created -> {
                            log.info("event=appeal_submitted")
                            call.respond(
                                HttpStatusCode.Created,
                                AppealCreatedResponse(id = outcome.appealId.toString(), status = STATUS_PENDING),
                            )
                        }
                        AppealService.SubmitOutcome.NoActionableModeration ->
                            // Identical envelope for normal + shadow-banned-only callers — no state leak.
                            call.respond(
                                HttpStatusCode.Conflict,
                                errorEnvelope(NO_ACTIONABLE_MODERATION, "There is no moderation action to appeal."),
                            )
                        AppealService.SubmitOutcome.AlreadyPending ->
                            call.respond(
                                HttpStatusCode.Conflict,
                                errorEnvelope(APPEAL_ALREADY_PENDING, "You already have a pending appeal."),
                            )
                        is AppealService.SubmitOutcome.RateLimited -> {
                            call.response.header(HttpHeaders.RetryAfter, outcome.retryAfterSeconds.toString())
                            call.respond(
                                HttpStatusCode.TooManyRequests,
                                errorEnvelope(RATE_LIMITED, "Too many appeal submissions. Try again later."),
                            )
                        }
                    }
                }

                get {
                    val principal =
                        call.principal<UserPrincipal>() ?: run {
                            call.respond(HttpStatusCode.Unauthorized)
                            return@get
                        }
                    val latest = service.latestStatus(principal.userId)
                    call.respond(
                        HttpStatusCode.OK,
                        if (latest == null) {
                            AppealStatusResponse(hasAppeal = false)
                        } else {
                            AppealStatusResponse(
                                hasAppeal = true,
                                actionType = latest.actionType,
                                status = latest.status,
                                decisionReason = latest.decisionReason,
                                createdAt = latest.createdAt.toString(),
                                reviewedAt = latest.reviewedAt?.toString(),
                            )
                        },
                    )
                }
            }
        }
    }
}

private const val APPEAL_TEXT_KEY = "appeal.text"
private const val STATUS_PENDING = "pending"
private const val INVALID_REQUEST = "invalid_request"
private const val NO_ACTIONABLE_MODERATION = "no_actionable_moderation"
private const val APPEAL_ALREADY_PENDING = "appeal_already_pending"
private const val RATE_LIMITED = "rate_limited"

private fun errorEnvelope(
    code: String,
    message: String,
): Map<String, Any> = mapOf("error" to mapOf("code" to code, "message" to message))
