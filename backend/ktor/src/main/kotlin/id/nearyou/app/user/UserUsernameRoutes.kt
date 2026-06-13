package id.nearyou.app.user

import id.nearyou.app.auth.AUTH_PROVIDER_USER
import id.nearyou.app.auth.UserPrincipal
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receiveNullable
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

/**
 * Premium username customization endpoints.
 *  - `PATCH /api/v1/user/username` body `{ "new_username": "<handle>" }`.
 *  - `GET  /api/v1/username/check?candidate=<handle>` — non-authoritative probe.
 *
 * Error mapping (mirrors `SearchRoutes`; `401` is auto-emitted by
 * `AUTH_PROVIDER_USER` when the JWT is missing/invalid):
 *  - `200` change success `{ "username": "<new>" }` / probe `{ "available": <bool> }`
 *  - `403 premium_required` (the canonical `{"error":"premium_required","upsell":true}` envelope)
 *  - `409 username_unavailable` (reserved / on release hold / taken / lost race)
 *  - `422 invalid_username` (format) / `422 username_rejected` (moderation hit)
 *  - `429 cooldown_active` / `429 rate_limited` (both carry `Retry-After`)
 *  - `503 feature_disabled` (flag off)
 *  - `400 invalid_request` (unparseable body)
 */
fun Application.userUsernameRoutes(service: UsernameChangeService) {
    routing {
        authenticate(AUTH_PROVIDER_USER) {
            patch("/api/v1/user/username") {
                val principal =
                    call.principal<UserPrincipal>() ?: run {
                        call.respond(HttpStatusCode.Unauthorized)
                        return@patch
                    }
                val body =
                    runCatching { call.receiveNullable<UsernameChangeRequest>() }.getOrNull()
                if (body == null || body.newUsername.isBlank()) {
                    call.respondText(INVALID_REQUEST_BODY, ContentType.Application.Json, HttpStatusCode.BadRequest)
                    return@patch
                }

                when (
                    val result =
                        service.changeUsername(
                            userId = principal.userId,
                            subscriptionStatus = principal.subscriptionStatus,
                            rawCandidate = body.newUsername,
                        )
                ) {
                    is UsernameChangeService.ChangeResult.Success ->
                        call.respond(UsernameChangeResponse(username = result.newUsername))
                    UsernameChangeService.ChangeResult.FeatureDisabled ->
                        call.respondJson(FEATURE_DISABLED_BODY, HttpStatusCode.ServiceUnavailable)
                    UsernameChangeService.ChangeResult.PremiumRequired ->
                        call.respondJson(PREMIUM_REQUIRED_BODY, HttpStatusCode.Forbidden)
                    is UsernameChangeService.ChangeResult.CooldownActive -> {
                        call.response.header(HttpHeaders.RetryAfter, result.retryAfterSeconds.toString())
                        call.respondJson(COOLDOWN_ACTIVE_BODY, HttpStatusCode.TooManyRequests)
                    }
                    UsernameChangeService.ChangeResult.InvalidFormat ->
                        call.respondJson(INVALID_USERNAME_BODY, HttpStatusCode.UnprocessableEntity)
                    UsernameChangeService.ChangeResult.Unavailable ->
                        call.respondJson(USERNAME_UNAVAILABLE_BODY, HttpStatusCode.Conflict)
                    UsernameChangeService.ChangeResult.Moderated ->
                        call.respondJson(USERNAME_REJECTED_BODY, HttpStatusCode.UnprocessableEntity)
                    is UsernameChangeService.ChangeResult.RateLimited -> {
                        call.response.header(HttpHeaders.RetryAfter, result.retryAfterSeconds.toString())
                        call.respondJson(RATE_LIMITED_BODY, HttpStatusCode.TooManyRequests)
                    }
                }
            }

            get("/api/v1/username/check") {
                val principal =
                    call.principal<UserPrincipal>() ?: run {
                        call.respond(HttpStatusCode.Unauthorized)
                        return@get
                    }
                val candidate = call.parameters["candidate"] ?: ""

                when (
                    val result =
                        service.checkAvailability(
                            userId = principal.userId,
                            subscriptionStatus = principal.subscriptionStatus,
                            rawCandidate = candidate,
                        )
                ) {
                    is UsernameChangeService.CheckResult.Probed ->
                        call.respond(UsernameCheckResponse(available = result.available))
                    UsernameChangeService.CheckResult.FeatureDisabled ->
                        call.respondJson(FEATURE_DISABLED_BODY, HttpStatusCode.ServiceUnavailable)
                    UsernameChangeService.CheckResult.PremiumRequired ->
                        call.respondJson(PREMIUM_REQUIRED_BODY, HttpStatusCode.Forbidden)
                    UsernameChangeService.CheckResult.InvalidFormat ->
                        call.respondJson(INVALID_USERNAME_BODY, HttpStatusCode.UnprocessableEntity)
                    is UsernameChangeService.CheckResult.RateLimited -> {
                        call.response.header(HttpHeaders.RetryAfter, result.retryAfterSeconds.toString())
                        call.respondJson(RATE_LIMITED_BODY, HttpStatusCode.TooManyRequests)
                    }
                }
            }
        }
    }
}

@Serializable
data class UsernameChangeRequest(
    @kotlinx.serialization.SerialName("new_username")
    val newUsername: String,
)

@Serializable
data class UsernameChangeResponse(
    val username: String,
)

@Serializable
data class UsernameCheckResponse(
    val available: Boolean,
)

private suspend fun io.ktor.server.application.ApplicationCall.respondJson(
    body: String,
    status: HttpStatusCode,
) {
    respondText(text = body, contentType = ContentType.Application.Json, status = status)
}

private const val PREMIUM_REQUIRED_BODY = """{"error":"premium_required","upsell":true}"""
private const val FEATURE_DISABLED_BODY = """{"error":"feature_disabled"}"""
private const val COOLDOWN_ACTIVE_BODY = """{"error":"cooldown_active"}"""
private const val INVALID_USERNAME_BODY = """{"error":"invalid_username"}"""
private const val USERNAME_UNAVAILABLE_BODY = """{"error":"username_unavailable"}"""
private const val USERNAME_REJECTED_BODY = """{"error":"username_rejected"}"""
private const val RATE_LIMITED_BODY = """{"error":"rate_limited"}"""
private const val INVALID_REQUEST_BODY = """{"error":"invalid_request"}"""
