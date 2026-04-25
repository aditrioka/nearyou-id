package id.nearyou.app.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import id.nearyou.app.auth.jwt.RsaKeyLoader
import id.nearyou.app.infra.repo.UserRepository
import id.nearyou.app.infra.repo.UserRow
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.jwt.JWTCredential
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.response.respond
import io.ktor.util.AttributeKey
import java.time.Instant
import java.util.UUID

const val AUTH_PROVIDER_USER = "user-jwt"

val AuthFailureKey = AttributeKey<String>("auth.failure_code")

/**
 * Authenticated request principal populated by the auth-jwt plugin's `validate { ... }`
 * block. Loaded once per request from a single `users` row read at auth time.
 *
 * [subscriptionStatus] is read once here — handlers (notably the like rate-limit gate
 * in `POST /api/v1/posts/{post_id}/like`) MUST read tier from this principal rather
 * than issuing a fresh `SELECT subscription_status FROM users WHERE id = :caller`,
 * per `openspec/specs/post-likes/spec.md` § "Read-site constraint" (the rate limiter
 * runs before any DB read; a fresh tier SELECT would violate that ordering).
 */
data class UserPrincipal(val userId: UUID, val tokenVersion: Int, val subscriptionStatus: String)

fun Application.installAuth(
    keys: RsaKeyLoader,
    users: UserRepository,
    nowProvider: () -> Instant = Instant::now,
) {
    install(Authentication) {
        configureUserJwt(keys, users, nowProvider)
    }
}

internal fun AuthenticationConfig.configureUserJwt(
    keys: RsaKeyLoader,
    users: UserRepository,
    nowProvider: () -> Instant,
) {
    jwt(AUTH_PROVIDER_USER) {
        val verifier = JWT.require(Algorithm.RSA256(keys.publicKey, keys.privateKey)).build()
        verifier(verifier)

        validate { credential: JWTCredential ->
            val tokenVersion = credential.payload.getClaim("token_version").asInt()
            val sub = credential.payload.subject
            if (tokenVersion == null || sub == null) {
                this.attributes.put(AuthFailureKey, "token_revoked")
                return@validate null
            }
            val userId =
                runCatching { UUID.fromString(sub) }.getOrElse {
                    this.attributes.put(AuthFailureKey, "token_revoked")
                    return@validate null
                }
            val user = users.findById(userId)
            when {
                user == null -> {
                    this.attributes.put(AuthFailureKey, "token_revoked")
                    null
                }
                user.tokenVersion != tokenVersion -> {
                    this.attributes.put(AuthFailureKey, "token_revoked")
                    null
                }
                user.isBanned -> {
                    this.attributes.put(AuthFailureKey, "account_banned")
                    null
                }
                user.isSuspendedAt(nowProvider()) -> {
                    this.attributes.put(AuthFailureKey, "account_suspended")
                    null
                }
                else -> UserPrincipal(user.id, user.tokenVersion, user.subscriptionStatus)
            }
        }

        challenge { _, _ ->
            val code = call.attributes.getOrNull(AuthFailureKey) ?: "token_revoked"
            val status =
                when (code) {
                    "account_banned", "account_suspended" -> HttpStatusCode.Forbidden
                    else -> HttpStatusCode.Unauthorized
                }
            call.respond(
                status,
                mapOf(
                    "error" to
                        mapOf(
                            "code" to code,
                            "message" to authMessageFor(code),
                        ),
                ),
            )
        }
    }
}

private fun UserRow.isSuspendedAt(now: Instant): Boolean = suspendedUntil?.isAfter(now) == true

private fun authMessageFor(code: String): String =
    when (code) {
        "account_banned" -> "Account is banned."
        "account_suspended" -> "Account is suspended."
        else -> "Token is invalid or revoked."
    }
