package id.nearyou.app.user

import id.nearyou.app.auth.AUTH_PROVIDER_USER
import id.nearyou.app.auth.UserPrincipal
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

private val log = LoggerFactory.getLogger("id.nearyou.app.user.PrivateProfileRoutes")

private const val MAX_BODY_BYTES = 4096L

/** Effective-Premium statuses (mirrors the user-profile-read / timeline / hide-distance gate). */
private val PREMIUM_STATES = setOf("premium_active", "premium_billing_retry")

/**
 * `PATCH` / `GET /api/v1/user/private-profile` — JWT-required Premium private-profile toggle
 * (the `private-profile` capability — the sanctioned `user_settings` privacy-flag writer).
 *
 * Writes `users.private_profile_opt_in` for the authenticated caller (JWT `sub`). The body is
 * `{"privateProfile": <bool>}`; the user identity comes solely from the verified principal — the
 * route accepts no `user_id` param, so there is no IDOR surface. An opt-OUT (`false`) also clears
 * any pending `privacy_flip_scheduled_at` in the repository (the "confirm switch public" grace-clear).
 *
 * Write-anytime + read-gated: the write succeeds for any tier; effectiveness is gated at READ (the
 * canonical effective-private formula), so a Free caller's stored `TRUE` produces no read effect
 * (mirrors `hide-distance`). The mobile UX is the tier gate (Free sees the upsell).
 *
 * Validation mirrors `HideDistanceRoutes` / `ConsentRoutes`: a transport-layer body cap, then DTO
 * deserialization (the single field is non-nullable, so a missing key or non-boolean surfaces as
 * `400`).
 *
 * PII discipline: the handler logs only the content-free event name (and, on failure, the exception
 * class) — never the bearer token, the JWT `sub`, or the body.
 */
fun Application.privateProfileRoutes(repository: PrivateProfileRepository) {
    routing {
        authenticate(AUTH_PROVIDER_USER) {
            patch("/api/v1/user/private-profile") {
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
                        call.receive<PrivateProfileUpdateRequest>()
                    } catch (_: BadRequestException) {
                        return@patch call.respond(HttpStatusCode.BadRequest)
                    } catch (_: ContentTransformationException) {
                        return@patch call.respond(HttpStatusCode.BadRequest)
                    } catch (_: SerializationException) {
                        return@patch call.respond(HttpStatusCode.BadRequest)
                    }

                try {
                    repository.updatePrivateProfile(
                        userId = principal.userId,
                        privateProfile = req.privateProfile,
                    )
                    log.info("event=private_profile_updated")
                    call.respond(
                        HttpStatusCode.OK,
                        PrivateProfileResponse(privateProfile = req.privateProfile),
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn(
                        "event=private_profile_update_failed error_class={}",
                        e::class.simpleName,
                        e,
                    )
                    call.respond(HttpStatusCode.InternalServerError)
                }
            }

            get("/api/v1/user/private-profile") {
                val principal =
                    call.principal<UserPrincipal>() ?: run {
                        call.respond(HttpStatusCode.Unauthorized)
                        return@get
                    }
                try {
                    call.respond(
                        HttpStatusCode.OK,
                        PrivateProfileStateResponse(
                            privateProfile = repository.getPrivateProfile(principal.userId),
                            premium = principal.subscriptionStatus in PREMIUM_STATES,
                        ),
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("event=private_profile_read_failed error_class={}", e::class.simpleName, e)
                    call.respond(HttpStatusCode.InternalServerError)
                }
            }
        }
    }
}
