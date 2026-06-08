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
import io.ktor.server.routing.patch
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("id.nearyou.app.user.ConsentRoutes")

private const val MAX_BODY_BYTES = 4096L

/**
 * `PATCH /api/v1/user/consent` — JWT-required analytics-consent update
 * (the `analytics-consent-update` capability).
 *
 * Full-object write of `users.analytics_consent` for the authenticated caller
 * (JWT `sub`). The body is the complete `{analytics, crash, ads_personalization}`
 * boolean triple; the user identity comes solely from the verified principal —
 * the route accepts no `user_id` param, so there is no IDOR surface.
 *
 * Validation layers:
 *  1. Transport-layer body cap (chunked-transfer requests omit Content-Length →
 *     fall through; upstream limits bound the worst case).
 *  2. DTO deserialization — all three fields non-nullable, so a missing key or a
 *     non-boolean value surfaces as `400`. Extra unknown keys are ignored by the
 *     app-wide `ignoreUnknownKeys = true` (they cannot corrupt the write).
 *
 * PII discipline (per the `analytics-consent-update` spec): the handler logs
 * neither the bearer token, the JWT `sub`, nor the consent body — only the
 * content-free event name (and, on failure, the exception class).
 */
fun Application.consentRoutes(repository: ConsentRepository) {
    routing {
        authenticate(AUTH_PROVIDER_USER) {
            patch("/api/v1/user/consent") {
                val principal =
                    call.principal<UserPrincipal>() ?: run {
                        call.respond(HttpStatusCode.Unauthorized)
                        return@patch
                    }

                // Transport-layer body cap. chunked-transfer requests omit
                // Content-Length → fall through; the JSON parser plus upstream
                // limits absorb the worst case.
                call.request.contentLength()?.let { len ->
                    if (len > MAX_BODY_BYTES) {
                        call.respond(HttpStatusCode.PayloadTooLarge)
                        return@patch
                    }
                }

                val req =
                    try {
                        call.receive<ConsentUpdateRequest>()
                    } catch (_: BadRequestException) {
                        return@patch call.respond(HttpStatusCode.BadRequest)
                    } catch (_: ContentTransformationException) {
                        return@patch call.respond(HttpStatusCode.BadRequest)
                    } catch (_: SerializationException) {
                        // kotlinx.serialization (e.g. MissingFieldException for a
                        // missing key, or a type mismatch for a non-boolean value).
                        return@patch call.respond(HttpStatusCode.BadRequest)
                    }

                try {
                    repository.updateConsent(
                        userId = principal.userId,
                        analytics = req.analytics,
                        crash = req.crash,
                        adsPersonalization = req.adsPersonalization,
                    )
                    // Content-free event: no sub, no consent values, no body (PII discipline).
                    log.info("event=analytics_consent_updated")
                    call.respond(
                        HttpStatusCode.OK,
                        ConsentResponse(
                            analytics = req.analytics,
                            crash = req.crash,
                            adsPersonalization = req.adsPersonalization,
                        ),
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // PII discipline: no sub, no body — error_class is the
                    // operator-debug envelope.
                    log.warn(
                        "event=analytics_consent_update_failed error_class={}",
                        e::class.simpleName,
                        e,
                    )
                    call.respond(HttpStatusCode.InternalServerError)
                }
            }
        }
    }
}
