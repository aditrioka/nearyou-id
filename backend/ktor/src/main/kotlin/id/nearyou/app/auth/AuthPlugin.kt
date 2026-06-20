package id.nearyou.app.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import id.nearyou.app.auth.jwt.APPEAL_TOKEN_SCOPE
import id.nearyou.app.auth.jwt.RsaKeyLoader
import id.nearyou.app.infra.repo.UserRepository
import id.nearyou.app.infra.repo.UserRow
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.jwt.JWTCredential
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.response.respond
import io.ktor.util.AttributeKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID

const val AUTH_PROVIDER_USER = "user-jwt"

// content-moderation-appeal: a dedicated ban-exempt realm mounted ONLY on the appeal
// routes. It runs the same signature + token_version + soft-delete checks as the user
// realm and populates the same UserPrincipal, but does NOT apply the is_banned /
// suspended_until 403 short-circuit — so an actioned user can contest the action
// (auth-jwt § "Banned and suspended users blocked" carve-out). It accepts any `scope`
// (a normal-token caller authenticates and is then handled by the route, e.g. a
// non-banned caller gets 409 no_actionable_moderation).
const val AUTH_PROVIDER_APPEAL = "appeal-jwt"

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
 * documented in `openspec/specs/auth-jwt/spec.md`; a `follow-up` GitHub issue
 * tracks the future docs-only OpenSpec change that documents both.
 */
data class UserPrincipal(
    val userId: UUID,
    val tokenVersion: Int,
    val subscriptionStatus: String,
    val isShadowBanned: Boolean,
    // hide-distance capability: loaded at auth time (like subscriptionStatus) so the Nearby read
    // path evaluates the viewer-side hide from the principal — no per-request users SELECT.
    val hideDistanceOptIn: Boolean = false,
)

fun Application.installAuth(
    keys: RsaKeyLoader,
    users: UserRepository,
    nowProvider: () -> Instant = Instant::now,
    // Production MUST pass the pool-bounded dispatcher (docs/11 §3.2) — the
    // Application.kt call site does. The default exists for the many test
    // co-mounts that don't exercise pool contention.
    dbDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    install(Authentication) {
        configureUserJwt(keys, users, nowProvider, dbDispatcher)
        configureAppealJwt(keys, users, nowProvider, dbDispatcher)
    }
}

internal fun AuthenticationConfig.configureUserJwt(
    keys: RsaKeyLoader,
    users: UserRepository,
    nowProvider: () -> Instant,
    // Default kept for the many test co-mounts that call this 3-arg; production
    // wiring (installAuth) always passes the pool-bounded dispatcher (docs/11 §3.2).
    dbDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    jwt(AUTH_PROVIDER_USER) {
        val verifier = JWT.require(Algorithm.RSA256(keys.publicKey, keys.privateKey)).build()
        verifier(verifier)

        validate { credential: JWTCredential ->
            // The user realm enforces the ban gates AND rejects the limited appeal token
            // (scope=appeal) so it can never reach a normal authenticated route.
            resolvePrincipal(
                credential = credential,
                users = users,
                nowProvider = nowProvider,
                dbDispatcher = dbDispatcher,
                enforceBanGates = true,
                rejectAppealScope = true,
            )
        }

        challenge { _, _ -> call.respondAuthChallenge() }
    }
}

// content-moderation-appeal: the ban-exempt appeal realm. Same identity validation as
// the user realm, but the ban gates are NOT enforced and the appeal scope is accepted —
// see AUTH_PROVIDER_APPEAL. Mounted only on the appeal-submission + own-appeal-status
// routes (the `content-moderation-appeal` capability).
internal fun AuthenticationConfig.configureAppealJwt(
    keys: RsaKeyLoader,
    users: UserRepository,
    nowProvider: () -> Instant,
    dbDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    jwt(AUTH_PROVIDER_APPEAL) {
        val verifier = JWT.require(Algorithm.RSA256(keys.publicKey, keys.privateKey)).build()
        verifier(verifier)

        validate { credential: JWTCredential ->
            resolvePrincipal(
                credential = credential,
                users = users,
                nowProvider = nowProvider,
                dbDispatcher = dbDispatcher,
                enforceBanGates = false,
                rejectAppealScope = false,
            )
        }

        challenge { _, _ -> call.respondAuthChallenge() }
    }
}

/**
 * Shared validation for both the user realm and the ban-exempt appeal realm. Performs
 * the signature-verified credential's `token_version` revocation check, the
 * unknown/soft-deleted-subject check, and (when [enforceBanGates]) the `is_banned` /
 * `suspended_until` 403 short-circuit. The single `users` row SELECT populates the
 * entire [UserPrincipal] (auth-jwt § "Single auth-time `users` row SELECT").
 *
 * The realms differ ONLY in [enforceBanGates] (the appeal realm relaxes the ban gate so
 * an actioned user can appeal) and [rejectAppealScope] (the user realm rejects the
 * limited `scope = "appeal"` token so it never reaches a normal route).
 */
private suspend fun ApplicationCall.resolvePrincipal(
    credential: JWTCredential,
    users: UserRepository,
    nowProvider: () -> Instant,
    dbDispatcher: CoroutineDispatcher,
    enforceBanGates: Boolean,
    rejectAppealScope: Boolean,
): UserPrincipal? {
    // Scope confinement (auth-jwt § "Limited-scope tokens are confined to the appeal
    // realm"): the user realm rejects a scope=appeal token; the appeal realm passes
    // rejectAppealScope=false and accepts any scope.
    if (rejectAppealScope && credential.payload.getClaim("scope").asString() == APPEAL_TOKEN_SCOPE) {
        attributes.put(AuthFailureKey, "token_revoked")
        return null
    }
    val tokenVersion = credential.payload.getClaim("token_version").asInt()
    val sub = credential.payload.subject
    if (tokenVersion == null || sub == null) {
        attributes.put(AuthFailureKey, "token_revoked")
        return null
    }
    val userId =
        runCatching { UUID.fromString(sub) }.getOrElse {
            attributes.put(AuthFailureKey, "token_revoked")
            return null
        }
    // Blocking JDBC hops to the pool-bounded dispatcher — this runs on EVERY
    // authenticated request and must never occupy a Netty call thread (docs/11 §3.2).
    val user = withContext(dbDispatcher) { users.findById(userId) }
    return when {
        user == null -> {
            attributes.put(AuthFailureKey, "token_revoked")
            null
        }
        user.deletedAt != null -> {
            // Soft-deleted accounts are terminal. Deletion flows bump token_version,
            // but a missed bump must not leave a live session (defense-in-depth). This
            // gate is retained on BOTH realms — only the ban gate is relaxed for appeals.
            attributes.put(AuthFailureKey, "token_revoked")
            null
        }
        user.tokenVersion != tokenVersion -> {
            attributes.put(AuthFailureKey, "token_revoked")
            null
        }
        enforceBanGates && user.isBanned -> {
            attributes.put(AuthFailureKey, "account_banned")
            null
        }
        enforceBanGates && user.isSuspendedAt(nowProvider()) -> {
            attributes.put(AuthFailureKey, "account_suspended")
            null
        }
        else -> {
            // Per `observability-otel-foundation` capability spec § "Mandatory span
            // attributes": authenticated requests set `user.id` on the active server span
            // to the SHA-256-truncated form (16 hex chars). The raw UUID is NEVER set;
            // `:infra:otel`'s `UserIdHasher` is the only sanctioned anonymization path.
            try {
                io.opentelemetry.api.trace.Span.current()
                    .setAttribute("user.id", id.nearyou.app.infra.otel.UserIdHasher.hash(user.id))
            } catch (_: Throwable) {
                // Span recording failure MUST NOT block authentication — observability is
                // a side-effect surface. Per spec § "Span recording failure does not block dispatch".
            }
            UserPrincipal(
                userId = user.id,
                tokenVersion = user.tokenVersion,
                subscriptionStatus = user.subscriptionStatus,
                isShadowBanned = user.isShadowBanned,
                hideDistanceOptIn = user.hideDistanceOptIn,
            )
        }
    }
}

private suspend fun ApplicationCall.respondAuthChallenge() {
    val code = attributes.getOrNull(AuthFailureKey) ?: "token_revoked"
    val status =
        when (code) {
            "account_banned", "account_suspended" -> HttpStatusCode.Forbidden
            else -> HttpStatusCode.Unauthorized
        }
    respond(
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

private fun UserRow.isSuspendedAt(now: Instant): Boolean = suspendedUntil?.isAfter(now) == true

private fun authMessageFor(code: String): String =
    when (code) {
        "account_banned" -> "Account is banned."
        "account_suspended" -> "Account is suspended."
        else -> "Token is invalid or revoked."
    }
