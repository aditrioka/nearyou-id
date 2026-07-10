package id.nearyou.app.auth.routes

import id.nearyou.app.auth.AUTH_PROVIDER_USER
import id.nearyou.app.auth.AuthRateLimiter
import id.nearyou.app.auth.UserPrincipal
import id.nearyou.app.auth.jwt.ACCESS_TOKEN_TTL_SECONDS
import id.nearyou.app.auth.jwt.JwtIssuer
import id.nearyou.app.auth.loginhistory.LoginEventRecorder
import id.nearyou.app.auth.loginhistory.LoginEventType
import id.nearyou.app.auth.provider.InvalidIdTokenException
import id.nearyou.app.auth.provider.ProviderIdTokenVerifier
import id.nearyou.app.auth.session.LogoutService
import id.nearyou.app.auth.session.RefreshTokenInvalidException
import id.nearyou.app.auth.session.RefreshTokenService
import id.nearyou.app.auth.session.TokenReuseException
import id.nearyou.app.common.clientIp
import id.nearyou.app.infra.otel.IpHasher
import id.nearyou.app.infra.repo.UserRepository
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.security.MessageDigest

// Wire field names are snake_case per the canonical auth-signin / auth-session specs
// (`{ "provider", "id_token", "access_token", "refresh_token", "expires_in",
// "device_fingerprint_hash" }`). The app-wide ContentNegotiation Json uses kotlinx's default
// (camelCase) naming, so each non-single-word field needs an explicit @SerialName — without it
// the endpoints (de)serialize camelCase, silently violating the spec. The mobile client follows
// the spec (snake_case); the mismatch surfaced as a 400 "Malformed sign-in payload" on the first
// real-device sign-in. AuthWireFormatTest pins these literal wire names against regression.
@Serializable
data class SignInRequest(
    val provider: String,
    @SerialName("id_token") val idToken: String,
    @SerialName("device_fingerprint_hash") val deviceFingerprintHash: String? = null,
)

@Serializable
data class TokenPairResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: Long,
)

@Serializable
data class RefreshRequest(
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("device_fingerprint_hash") val deviceFingerprintHash: String? = null,
)

@Serializable
data class LogoutRequest(
    @SerialName("refresh_token") val refreshToken: String,
    // logout-revocation: when present, the caller's user_fcm_tokens row(s) for this token are
    // deleted so pushes stop on the signed-out device. Optional — older clients omit it.
    @SerialName("fcm_token") val fcmToken: String? = null,
)

@Serializable
data class ApiError(val error: Envelope) {
    @Serializable
    data class Envelope(val code: String, val message: String)
}

// content-moderation-appeal: the banned/suspended sign-in 403 carries the limited-scope
// appeal token alongside the error envelope, so the actioned user has a credential for
// the ban-exempt appeal realm (auth-signin § "Banned user blocked at sign-in").
//
// appeal-sign-in-ban-distinction: the 403 ALSO carries `suspended_until` so the client can
// route a suspension (in-app appeal form) differently from a permanent ban (support path).
// A non-null ISO-8601 timestamp ⇒ a 7-day suspension's expiry (present on the wire); a permanent
// ban ⇒ `suspendedUntil = null`, which the shared server `AppJson` (`explicitNulls = false`)
// OMITS from the wire entirely — so a permanent-ban body has no `suspended_until` key at all. The
// client treats absent / null / unparseable identically (⇒ permanent), so the omission is the
// permanent signal; the distinction is "field present with a value ⇒ suspension, field absent ⇒
// permanent". The code stays `account_banned` for both states (not a separate code).
@Serializable
data class BannedSignInResponse(
    val error: ApiError.Envelope,
    @SerialName("appeal_token") val appealToken: String,
    @SerialName("suspended_until") val suspendedUntil: String?,
)

private fun errorBody(
    code: String,
    message: String,
) = ApiError(ApiError.Envelope(code, message))

class Providers(
    val google: ProviderIdTokenVerifier,
    val apple: ProviderIdTokenVerifier,
)

private val log = LoggerFactory.getLogger("id.nearyou.app.auth.routes.AuthRoutes")

fun Application.authRoutes(
    providers: Providers,
    users: UserRepository,
    tokens: RefreshTokenService,
    jwtIssuer: JwtIssuer,
    loginEventRecorder: LoginEventRecorder,
    authRateLimiter: AuthRateLimiter,
    logoutService: LogoutService,
) {
    routing {
        post("/api/v1/auth/signin") {
            // Defense-in-depth rate limit FIRST (before body parse / id-token verification),
            // keyed by hashed client IP (audit #214). The hash is hoisted out of the key literal
            // per the RedisHashTagRule block-interpolation note; the raw IP never reaches the Lua
            // key (OtelForbiddenAttributeRule). A 429'd attempt records no login_events row — the
            // limiter itself is the abuse signal.
            val signinHashedIp = IpHasher.hash(call.clientIp)
            val signinRate = authRateLimiter.trySignin(signinHashedIp)
            if (signinRate is AuthRateLimiter.Outcome.RateLimited) {
                call.response.header(HttpHeaders.RetryAfter, signinRate.retryAfterSeconds.toString())
                call.respond(HttpStatusCode.TooManyRequests, errorBody("rate_limited", "Too many requests. Please try again later."))
                return@post
            }
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
                // DEBUG diagnostic: correlate a no-account sign-in to the exact provider-id hash
                // that matched no `users` row. The hash is a one-way SHA-256 of the provider `sub`
                // (NOT PII / NOT the raw subject), so it is safe to log; it lets ops triage
                // "sign-in says not-registered" reports and seed staging test accounts.
                log.debug("signin no-account: provider={} sub_hash={}", req.provider, subHash)
                call.respond(
                    HttpStatusCode.NotFound,
                    errorBody("user_not_found", "No account linked to this identity."),
                )
                return@post
            }
            if (user.isBanned) {
                // content-moderation-appeal: NO normal access/refresh token is issued (no
                // refresh_tokens row), but the 403 carries a limited-scope appeal token
                // (scope=appeal, current token_version, ≤1h) so the actioned user can reach
                // the ban-exempt appeal realm to contest the action. A suspended user is
                // is_banned=TRUE, so this path covers suspension + permanent ban alike.
                val appealToken = jwtIssuer.issueAppealToken(user.id, user.tokenVersion)
                // appeal-sign-in-ban-distinction: echo the matched user's suspended_until (already
                // selected on UserRow — no extra query) so the client routes suspension vs permanent.
                // java.time.Instant.toString() is the ISO-8601 form (e.g. "2026-07-03T00:00:00Z");
                // null for a permanent ban.
                call.respond(
                    HttpStatusCode.Forbidden,
                    BannedSignInResponse(
                        ApiError.Envelope("account_banned", "Account is banned."),
                        appealToken,
                        user.suspendedUntil?.toString(),
                    ),
                )
                return@post
            }
            val access = jwtIssuer.issueAccessToken(user.id, user.tokenVersion)
            val refresh = tokens.issue(user.id, req.deviceFingerprintHash)
            // Durable login-history (security/anti-abuse, best-effort). identifier_hash is the
            // already-verified provider sub hash; ip via the clientIp invariant accessor.
            loginEventRecorder.record(
                userId = user.id,
                eventType = LoginEventType.SIGNIN,
                ip = call.clientIp,
                deviceFingerprintHash = req.deviceFingerprintHash,
                identifierHash = subHash,
            )
            call.respond(TokenPairResponse(access, refresh.rawToken, ACCESS_TOKEN_TTL_SECONDS))
        }

        post("/api/v1/auth/refresh") {
            // Defense-in-depth rate limit FIRST (before body parse / token rotation), keyed by
            // hashed client IP (audit #214). Per-MINUTE cap (CGNAT-safe; see AuthRateLimiter KDoc).
            val refreshHashedIp = IpHasher.hash(call.clientIp)
            val refreshRate = authRateLimiter.tryRefresh(refreshHashedIp)
            if (refreshRate is AuthRateLimiter.Outcome.RateLimited) {
                call.response.header(HttpHeaders.RetryAfter, refreshRate.retryAfterSeconds.toString())
                call.respond(HttpStatusCode.TooManyRequests, errorBody("rate_limited", "Too many requests. Please try again later."))
                return@post
            }
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
            // Account-state gate (auth-session: "Refresh denies a banned or
            // soft-deleted account"). Mirror the per-request AuthPlugin gate
            // (AuthPlugin.kt deletedAt → token_revoked, isBanned → account_banned)
            // so a refresh cannot mint a fresh access token for an account every
            // authenticated route already 403s. A permanent ban (mirroring the
            // shipped suspend / report-queue ban) bumps no token_version and
            // deletes no refresh token, so this gate is what makes the ban
            // effective within one ~15-min access-token TTL instead of the 30-day
            // refresh-token TTL. is_banned covers BOTH a permanent ban and an
            // active time-bound suspension. No new access token is issued on a
            // denied refresh (the rotated refresh token's raw value is discarded,
            // never returned — so the denied caller gains no usable token).
            if (user.deletedAt != null) {
                call.respond(HttpStatusCode.Unauthorized, errorBody("token_revoked", "User no longer exists."))
                return@post
            }
            if (user.isBanned) {
                call.respond(HttpStatusCode.Forbidden, errorBody("account_banned", "Account is banned."))
                return@post
            }
            val access = jwtIssuer.issueAccessToken(user.id, user.tokenVersion)
            // Durable login-history (security/anti-abuse, best-effort). identifier_hash is the
            // account's stored provider hash (refresh carries no id_token); ip via clientIp.
            loginEventRecorder.record(
                userId = user.id,
                eventType = LoginEventType.REFRESH,
                ip = call.clientIp,
                deviceFingerprintHash = req.deviceFingerprintHash,
                identifierHash = user.googleIdHash ?: user.appleIdHash,
            )
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
                logoutService.logout(principal.userId, req.refreshToken, req.fcmToken)
                call.respond(HttpStatusCode.NoContent)
            }

            post("/api/v1/auth/logout-all") {
                val principal = call.principal<UserPrincipal>()!!
                logoutService.logoutAll(principal.userId)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

private fun sha256Hex(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}
