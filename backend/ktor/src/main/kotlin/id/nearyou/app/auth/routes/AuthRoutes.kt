package id.nearyou.app.auth.routes

import id.nearyou.app.auth.AUTH_PROVIDER_USER
import id.nearyou.app.auth.UserPrincipal
import id.nearyou.app.auth.jwt.ACCESS_TOKEN_TTL_SECONDS
import id.nearyou.app.auth.jwt.JwtIssuer
import id.nearyou.app.auth.provider.InvalidIdTokenException
import id.nearyou.app.auth.provider.ProviderIdTokenVerifier
import id.nearyou.app.auth.session.RefreshTokenInvalidException
import id.nearyou.app.auth.session.RefreshTokenService
import id.nearyou.app.auth.session.TokenReuseException
import id.nearyou.app.infra.repo.UserRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import java.security.MessageDigest

@Serializable
data class SignInRequest(
    val provider: String,
    val idToken: String,
    val deviceFingerprintHash: String? = null,
)

@Serializable
data class TokenPairResponse(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long,
)

@Serializable
data class RefreshRequest(
    val refreshToken: String,
    val deviceFingerprintHash: String? = null,
)

@Serializable
data class LogoutRequest(val refreshToken: String)

@Serializable
data class ApiError(val error: Envelope) {
    @Serializable
    data class Envelope(val code: String, val message: String)
}

private fun errorBody(
    code: String,
    message: String,
) = ApiError(ApiError.Envelope(code, message))

class Providers(
    val google: ProviderIdTokenVerifier,
    val apple: ProviderIdTokenVerifier,
)

fun Application.authRoutes(
    providers: Providers,
    users: UserRepository,
    tokens: RefreshTokenService,
    jwtIssuer: JwtIssuer,
) {
    routing {
        post("/api/v1/auth/signin") {
            val req =
                try {
                    call.receive<SignInRequest>()
                } catch (_: Exception) {
                    call.respond(HttpStatusCode.BadRequest, errorBody("invalid_request", "Malformed sign-in payload."))
                    return@post
                }
            val verifier =
                when (req.provider) {
                    "google" -> providers.google
                    "apple" -> providers.apple
                    else -> {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            errorBody("invalid_request", "provider must be 'google' or 'apple'"),
                        )
                        return@post
                    }
                }
            val verified =
                try {
                    verifier.verify(req.idToken)
                } catch (ex: InvalidIdTokenException) {
                    call.respond(HttpStatusCode.Unauthorized, errorBody("invalid_id_token", ex.message ?: "id_token invalid"))
                    return@post
                }
            val subHash = sha256Hex(verified.sub)
            val user =
                when (req.provider) {
                    "google" -> users.findByGoogleIdHash(subHash)
                    else -> users.findByAppleIdHash(subHash)
                }
            if (user == null) {
                call.respond(
                    HttpStatusCode.NotFound,
                    errorBody("user_not_found", "No account linked to this identity."),
                )
                return@post
            }
            if (user.isBanned) {
                call.respond(HttpStatusCode.Forbidden, errorBody("account_banned", "Account is banned."))
                return@post
            }
            val access = jwtIssuer.issueAccessToken(user.id, user.tokenVersion)
            val refresh = tokens.issue(user.id, req.deviceFingerprintHash)
            call.respond(TokenPairResponse(access, refresh.rawToken, ACCESS_TOKEN_TTL_SECONDS))
        }

        post("/api/v1/auth/refresh") {
            val req =
                try {
                    call.receive<RefreshRequest>()
                } catch (_: Exception) {
                    call.respond(HttpStatusCode.BadRequest, errorBody("invalid_request", "Malformed refresh payload."))
                    return@post
                }
            val rotated =
                try {
                    tokens.rotate(req.refreshToken, req.deviceFingerprintHash)
                } catch (_: TokenReuseException) {
                    call.respond(
                        HttpStatusCode.Unauthorized,
                        errorBody("token_reuse_detected", "Refresh token reuse detected; please sign in again."),
                    )
                    return@post
                } catch (_: RefreshTokenInvalidException) {
                    call.respond(HttpStatusCode.Unauthorized, errorBody("refresh_token_invalid", "Refresh token invalid."))
                    return@post
                }
            val user =
                users.findById(rotated.row.userId) ?: run {
                    call.respond(HttpStatusCode.Unauthorized, errorBody("token_revoked", "User no longer exists."))
                    return@post
                }
            val access = jwtIssuer.issueAccessToken(user.id, user.tokenVersion)
            call.respond(TokenPairResponse(access, rotated.rawToken, ACCESS_TOKEN_TTL_SECONDS))
        }

        authenticate(AUTH_PROVIDER_USER) {
            post("/api/v1/auth/logout") {
                val principal = call.principal<UserPrincipal>()!!
                val req =
                    try {
                        call.receive<LogoutRequest>()
                    } catch (_: Exception) {
                        call.respond(HttpStatusCode.BadRequest, errorBody("invalid_request", "Malformed logout payload."))
                        return@post
                    }
                tokens.revokeSingle(principal.userId, req.refreshToken)
                call.respond(HttpStatusCode.NoContent)
            }

            post("/api/v1/auth/logout-all") {
                val principal = call.principal<UserPrincipal>()!!
                tokens.revokeAll(principal.userId)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

private fun sha256Hex(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}
