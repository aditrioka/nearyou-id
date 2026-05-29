package id.nearyou.app.auth.signup

import id.nearyou.app.auth.jwt.ACCESS_TOKEN_TTL_SECONDS
import id.nearyou.app.auth.provider.InvalidIdTokenException
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Canonical "user_blocked" response body. Used for BOTH the rejected-identifier
 * pre-check hit AND the under-18 rejection path so the two cases are
 * byte-indistinguishable to the caller (privacy note in
 * docs/05-Implementation.md § Rejected Identifiers Schema).
 *
 * If adding a new 403 branch to this endpoint, ensure the body is exactly this
 * string; otherwise the age-gate spec's byte-identity scenario will fail.
 */
const val SIGNUP_USER_BLOCKED_BODY: String =
    """{"error":"user_blocked","message":"Akun tidak dapat dibuat dengan data ini."}"""

// Wire field names are snake_case per the canonical auth-signup spec
// (`{ "provider", "id_token", "date_of_birth", "device_fingerprint_hash" }` →
// `{ "access_token", "refresh_token", "expires_in" }`). Same camelCase-vs-spec slip as the
// sign-in DTOs (see AuthRoutes.kt) — explicit @SerialName is required because the app-wide Json
// uses kotlinx's default (camelCase) naming. Pinned by AuthWireFormatTest.
@Serializable
data class SignupRequestDto(
    val provider: String,
    @SerialName("id_token") val idToken: String,
    @SerialName("date_of_birth") val dateOfBirth: String,
    @SerialName("device_fingerprint_hash") val deviceFingerprintHash: String? = null,
)

@Serializable
data class SignupTokenPairResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: Long,
)

@Serializable
data class SignupErrorBody(val error: SignupErrorEnvelope) {
    @Serializable
    data class SignupErrorEnvelope(val code: String, val message: String)
}

private fun err(
    code: String,
    message: String,
) = SignupErrorBody(SignupErrorBody.SignupErrorEnvelope(code, message))

fun Application.signupRoutes(signupService: SignupService) {
    routing {
        post("/api/v1/auth/signup") {
            val req =
                try {
                    call.receive<SignupRequestDto>()
                } catch (_: Exception) {
                    call.respond(HttpStatusCode.BadRequest, err("invalid_request", "Malformed signup payload."))
                    return@post
                }
            try {
                val result =
                    signupService.signup(
                        SignupRequest(
                            provider = req.provider,
                            idToken = req.idToken,
                            dateOfBirth = req.dateOfBirth,
                            deviceFingerprintHash = req.deviceFingerprintHash,
                        ),
                    )
                call.respond(
                    HttpStatusCode.Created,
                    SignupTokenPairResponse(
                        accessToken = result.accessToken,
                        refreshToken = result.refreshToken,
                        expiresIn = ACCESS_TOKEN_TTL_SECONDS,
                    ),
                )
            } catch (ex: InvalidRequestException) {
                call.respond(HttpStatusCode.BadRequest, err("invalid_request", ex.message ?: "Invalid request."))
            } catch (ex: InvalidIdTokenException) {
                call.respond(HttpStatusCode.Unauthorized, err("invalid_id_token", ex.message ?: "id_token invalid"))
            } catch (_: UserBlockedException) {
                call.respondText(
                    text = SIGNUP_USER_BLOCKED_BODY,
                    contentType = ContentType.Application.Json,
                    status = HttpStatusCode.Forbidden,
                )
            } catch (_: UserExistsException) {
                call.respond(HttpStatusCode.Conflict, err("user_exists", "An account already exists for this identity."))
            } catch (_: UsernameGenerationFailedException) {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    err("username_generation_failed", "Try again in a moment."),
                )
            }
        }
    }
}
