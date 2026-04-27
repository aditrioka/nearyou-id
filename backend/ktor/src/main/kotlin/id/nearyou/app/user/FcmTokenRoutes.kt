package id.nearyou.app.user

import id.nearyou.app.auth.AUTH_PROVIDER_USER
import id.nearyou.app.auth.UserPrincipal
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.request.contentLength
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("id.nearyou.app.user.FcmTokenRoutes")

private const val ALLOWED_PLATFORM_ANDROID = "android"
private const val ALLOWED_PLATFORM_IOS = "ios"
private const val MAX_APP_VERSION_LEN = 64
private const val MAX_BODY_BYTES = 4096L

private const val ERROR_MALFORMED_BODY = """{"error":"malformed_body"}"""
private const val ERROR_INVALID_PLATFORM = """{"error":"invalid_platform"}"""
private const val ERROR_EMPTY_TOKEN = """{"error":"empty_token"}"""
private const val ERROR_APP_VERSION_TOO_LONG = """{"error":"app_version_too_long"}"""

/**
 * `POST /api/v1/user/fcm-token` — JWT-required device-token registration.
 *
 * Three layers of validation:
 *  1. Transport-layer 4 KB body cap (D10) — chunked-transfer requests
 *     bypass the contentLength() check; upstream Cloudflare / Cloud Run
 *     bound the worst case at MB-scale parse-and-reject.
 *  2. DTO deserialization — `token` and `platform` non-nullable so a
 *     missing field surfaces as `malformed_body`.
 *  3. Handler validators — closed-vocabulary error codes
 *     (`invalid_platform`, `empty_token`, `app_version_too_long`).
 *
 * The DB CHECKs (D9) are the fourth line of defense: if any non-route
 * write path bypasses the validators, the CHECKs reject at INSERT.
 *
 * Token confidentiality (D11): the raw token value is never logged.
 */
fun Application.fcmTokenRoutes(repository: FcmTokenRepository) {
    routing {
        authenticate(AUTH_PROVIDER_USER) {
            post("/api/v1/user/fcm-token") {
                val principal =
                    call.principal<UserPrincipal>() ?: run {
                        call.respond(HttpStatusCode.Unauthorized)
                        return@post
                    }

                // Transport-layer body cap (D10). chunked-transfer requests
                // omit Content-Length → fall through; the JSON parser plus
                // upstream limits absorb the worst case.
                call.request.contentLength()?.let { len ->
                    if (len > MAX_BODY_BYTES) {
                        call.respond(HttpStatusCode.PayloadTooLarge)
                        return@post
                    }
                }

                val req =
                    try {
                        call.receive<FcmTokenRequest>()
                    } catch (_: BadRequestException) {
                        return@post call.respondError(HttpStatusCode.BadRequest, ERROR_MALFORMED_BODY)
                    } catch (_: ContentTransformationException) {
                        return@post call.respondError(HttpStatusCode.BadRequest, ERROR_MALFORMED_BODY)
                    } catch (_: SerializationException) {
                        // kotlinx.serialization (e.g. MissingFieldException) — surfaces
                        // when a non-nullable required field is absent from the body.
                        return@post call.respondError(HttpStatusCode.BadRequest, ERROR_MALFORMED_BODY)
                    }

                if (req.platform != ALLOWED_PLATFORM_ANDROID && req.platform != ALLOWED_PLATFORM_IOS) {
                    return@post call.respondError(HttpStatusCode.BadRequest, ERROR_INVALID_PLATFORM)
                }
                val trimmedToken = req.token.trim()
                if (trimmedToken.isEmpty()) {
                    return@post call.respondError(HttpStatusCode.BadRequest, ERROR_EMPTY_TOKEN)
                }
                if ((req.appVersion?.length ?: 0) > MAX_APP_VERSION_LEN) {
                    return@post call.respondError(HttpStatusCode.BadRequest, ERROR_APP_VERSION_TOO_LONG)
                }

                try {
                    val result =
                        repository.upsert(
                            userId = principal.userId,
                            platform = req.platform,
                            token = trimmedToken,
                            appVersion = req.appVersion,
                        )
                    log.info(
                        "event=fcm_token_registered user_id={} platform={} created={} user_token_count={}",
                        principal.userId,
                        req.platform,
                        result.created,
                        result.userTokenCount,
                    )
                    call.respond(HttpStatusCode.NoContent)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // D11: WARN log MUST NOT include the raw token value or the
                    // request body. user_id + platform + error_class is the
                    // operator-debug envelope.
                    log.warn(
                        "event=fcm_token_registration_failed user_id={} platform={} error_class={}",
                        principal.userId,
                        req.platform,
                        e::class.simpleName,
                        e,
                    )
                    call.respond(HttpStatusCode.InternalServerError)
                }
            }
        }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.respondError(
    status: HttpStatusCode,
    body: String,
) {
    respondText(text = body, contentType = ContentType.Application.Json, status = status)
}
