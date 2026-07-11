package id.nearyou.app.user

import id.nearyou.app.auth.AUTH_PROVIDER_USER
import id.nearyou.app.auth.UserPrincipal
import id.nearyou.app.subscription.PREMIUM_STATES
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.request.contentLength
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("id.nearyou.app.user.HideDistanceRoutes")

private const val MAX_BODY_BYTES = 4096L

/**
 * `PATCH /api/v1/user/hide-distance` — JWT-required Premium hide-distance toggle
 * (the `hide-distance` capability).
 *
 * Writes `users.hide_distance_opt_in` for the authenticated caller (JWT `sub`). The body
 * is `{"hideDistance": <bool>}`; the user identity comes solely from the verified principal —
 * the route accepts no `user_id` param, so there is no IDOR surface.
 *
 * Write-anytime + read-gated: the write succeeds for any tier; the Nearby read path gates the
 * flag's *effect* on Premium status, so a Free caller's stored `TRUE` produces no suppression
 * (mirrors `private_profile_opt_in`). The mobile UX is the tier gate (Free sees the upsell).
 *
 * Validation mirrors `ConsentRoutes`: a transport-layer body cap, then DTO deserialization
 * (the single field is non-nullable, so a missing key or non-boolean surfaces as `400`).
 *
 * PII discipline: the handler logs only the content-free event name (and, on failure, the
 * exception class) — never the bearer token, the JWT `sub`, or the body.
 */
fun Application.hideDistanceRoutes(repository: HideDistanceRepository) {
    routing {
        authenticate(AUTH_PROVIDER_USER) {
            patch("/api/v1/user/hide-distance") {
                val principal =
                    call.principal<UserPrincipal>() ?: run {
                        call.respond(HttpStatusCode.Unauthorized)
                        return@patch
                    }

                call.request.contentLength()?.let { len ->
                    if (len > MAX_BODY_BYTES) {
                        call.respond(HttpStatusCode.PayloadTooLarge)
                        return@patch
                    }
                }

                val req =
                    try {
                        call.receive<HideDistanceUpdateRequest>()
                    } catch (_: BadRequestException) {
                        return@patch call.respond(HttpStatusCode.BadRequest)
                    } catch (_: ContentTransformationException) {
                        return@patch call.respond(HttpStatusCode.BadRequest)
                    } catch (_: SerializationException) {
                        return@patch call.respond(HttpStatusCode.BadRequest)
                    }

                try {
                    repository.updateHideDistance(
                        userId = principal.userId,
                        hideDistance = req.hideDistance,
                    )
                    log.info("event=hide_distance_updated")
                    call.respond(
                        HttpStatusCode.OK,
                        HideDistanceResponse(hideDistance = req.hideDistance),
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn(
                        "event=hide_distance_update_failed error_class={}",
                        e::class.simpleName,
                        e,
                    )
                    call.respond(HttpStatusCode.InternalServerError)
                }
            }

            get("/api/v1/user/hide-distance") {
                val principal =
                    call.principal<UserPrincipal>() ?: run {
                        call.respond(HttpStatusCode.Unauthorized)
                        return@get
                    }
                try {
                    call.respond(
                        HttpStatusCode.OK,
                        HideDistanceStateResponse(
                            hideDistance = repository.getHideDistance(principal.userId),
                            premium = principal.subscriptionStatus in PREMIUM_STATES,
                        ),
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("event=hide_distance_read_failed error_class={}", e::class.simpleName, e)
                    call.respond(HttpStatusCode.InternalServerError)
                }
            }
        }
    }
}
