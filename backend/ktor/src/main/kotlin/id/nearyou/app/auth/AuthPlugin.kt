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
 *
 * [isShadowBanned] is read once here — the chat send handler's publish-side shadow-ban
 * skip (per `chat-realtime-broadcast` spec § Publish-side shadow-ban skip) reads from
 * this field instead of issuing a fresh `SELECT is_shadow_banned FROM users` for the
 * publish decision. Mid-flight admin flips between auth and publish are accepted (stale
 * state acceptable per design § D2; mobile consumer-side filter at
 * `docs/05-Implementation.md:1880` is defense-in-depth).
 *
 * Both [subscriptionStatus] and [isShadowBanned] are populated by the same auth-time
 * `users` row SELECT in [JdbcUserRepository.baseSelect]. Spec debt: neither field is
 * documented in `openspec/specs/auth-jwt/spec.md`; the `auth-jwt-spec-debt-userprincipal-subscription-status`
 * `FOLLOW_UPS.md` entry tracks the future docs-only OpenSpec change that documents both.
 */
data class UserPrincipal(
    val userId: UUID,
    val tokenVersion: Int,
    val subscriptionStatus: String,
    val isShadowBanned: Boolean,
)

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
                else -> {
                    // Per `observability-otel-foundation` capability spec § "Mandatory
                    // span attributes": when a request is authenticated against a
                    // `UserPrincipal`-backed identity, the active Ktor server span
                    // gets a `user.id` attribute set to the SHA-256-truncated form
                    // (16 hex chars). The raw UUID is NEVER set; `:infra:otel`'s
                    // `UserIdHasher` is the only sanctioned anonymization path.
                    // /internal/* requests authenticated by Cloud Scheduler OIDC do
                    // NOT pass through this plugin — they get `service.account.id`
                    // via the mirror writer at `InternalEndpointAuth.kt`. Contract
                    // shipped by the `internal-endpoint-auth-otel-attributes`
                    // capability; the server-span-level mutual exclusion with
                    // `user.id` is enforced structurally via route-scoped plugin
                    // installation. See `openspec/specs/internal-endpoint-auth/spec.md`
                    // § "Requirement: /internal server spans carry service.account.id".
                    try {
                        io.opentelemetry.api.trace.Span.current()
                            .setAttribute("user.id", id.nearyou.app.infra.otel.UserIdHasher.hash(user.id))
                    } catch (_: Throwable) {
                        // Span recording failure MUST NOT block authentication —
                        // observability is a side-effect surface. Per spec
                        // § "Span recording failure does not block dispatch".
                    }
                    UserPrincipal(
                        userId = user.id,
                        tokenVersion = user.tokenVersion,
                        subscriptionStatus = user.subscriptionStatus,
                        isShadowBanned = user.isShadowBanned,
                    )
                }
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
