package id.nearyou.app.moderation.csam

import id.nearyou.app.admin.SuspensionUnbanWorker
import id.nearyou.app.admin.auth.AdminAuthProvider
import id.nearyou.app.admin.auth.AdminRoleGate
import id.nearyou.app.admin.auth.AdminUserRepository
import id.nearyou.app.admin.auth.HashUtil
import id.nearyou.app.admin.auth.SessionRepository
import id.nearyou.app.admin.auth.isActive
import id.nearyou.app.auth.routes.ApiError
import id.nearyou.app.common.AppJson
import id.nearyou.app.common.clientIp
import id.nearyou.app.config.SecretResolver
import id.nearyou.app.config.secretKey
import id.nearyou.app.core.domain.oidc.OidcTokenVerifier
import id.nearyou.app.core.domain.ratelimit.RateLimiter
import id.nearyou.app.infra.otel.IpHasher
import id.nearyou.app.internal.InternalEndpointAuth
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.request.contentLength
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * `POST /internal/csam-webhook` — the CSAM takedown handler (`csam-detection`
 * capability). A NON-OIDC vendor/admin webhook: it authenticates with its OWN auth on
 * two paths (NOT the OIDC `InternalEndpointAuth` plugin), so — like
 * `revenueCatWebhookRoutes` / `appleS2SRoutes` — it is mounted via its OWN
 * `routing {}` block, a sibling of (NOT under) the gated `route("/internal")` node.
 * Ktor merges identical path segments across routing blocks, so installing the OIDC
 * gate on the shared `/internal` node would also gate this route (guarded by
 * `InternalRoutingIsolationTest`).
 *
 * Two invocation paths (docs/06 § Internal Endpoint Security), dispatched by the
 * presence of the admin session cookie vs a Bearer header:
 *  - **Admin-internal (MVP)** — `__Host-admin_session` cookie + `X-CSRF-Token`, gated
 *    to `owner`/`admin`. Reuses the existing admin-session seam
 *    ([SessionRepository] / [AdminUserRepository] / [HashUtil] / [AdminRoleGate]) — the
 *    same validators [AdminAuthProvider] uses — rather than the admin-subtree-scoped
 *    plugin (this route is outside that subtree) and never a hand-rolled CSRF check.
 *    Its production caller (the admin paste-URL form) ships with the deferred admin
 *    viewer; this change ships + tests the auth contract.
 *  - **Cloudflare Worker (Phase 2+)** — `Authorization: Bearer <cf-worker-csam-secret>`
 *    AND a MANDATORY `X-CSAM-Signature` HMAC-SHA256 over the raw body (same secret),
 *    both constant-time; rate-limited 100/hour per client IP (replay-amplification
 *    guard). The system sentinel admin (`system-actor`) attributes its audit rows.
 *
 * A bare OIDC token (neither cookie nor Bearer-with-secret) does NOT authorize — the
 * OIDC plugin never runs on this route.
 *
 * `POST /internal/csam-archive-purge` is the ordinary OIDC-gated retention worker,
 * mounted under `route("/internal")` with its own `InternalEndpointAuth` gate.
 */
fun Application.csamWebhookRoutes(
    service: CsamDetectionService,
    sessionRepository: SessionRepository,
    adminUserRepository: AdminUserRepository,
    rateLimiter: RateLimiter,
    secrets: SecretResolver,
    env: String,
) {
    val cfWorkerSlot = secretKey(env, SECRET_CF_WORKER)
    routing {
        post(CSAM_WEBHOOK_PATH) {
            call.request.contentLength()?.let { len ->
                if (len > MAX_BODY_BYTES) {
                    call.respond(HttpStatusCode.PayloadTooLarge)
                    return@post
                }
            }

            val cookie = call.request.cookies[AdminAuthProvider.COOKIE_NAME]
            val bearerHeader = call.request.header(HttpHeaders.Authorization)

            val auth =
                when {
                    !cookie.isNullOrBlank() ->
                        authenticateAdminInternal(cookie, sessionRepository, adminUserRepository)
                    bearerHeader != null ->
                        authenticateCfWorker(bearerHeader, rateLimiter, secrets, cfWorkerSlot)
                    else ->
                        AuthOutcome.Reject(HttpStatusCode.Unauthorized, "unauthorized")
                }

            val authed =
                when (auth) {
                    is AuthOutcome.Reject -> {
                        auth.retryAfterSeconds?.let { call.response.header(HttpHeaders.RetryAfter, it.toString()) }
                        return@post call.respond(auth.status, error(auth.code))
                    }
                    is AuthOutcome.Ok -> auth
                }

            // CF-Worker auth already consumed the raw body (HMAC); the admin path reads it here.
            val rawBody = authed.rawBody ?: call.receiveText()
            val req =
                try {
                    AppJson.decodeFromString(CsamWebhookRequest.serializer(), rawBody)
                } catch (_: SerializationException) {
                    return@post call.respond(HttpStatusCode.BadRequest, error("invalid_request"))
                } catch (_: IllegalArgumentException) {
                    return@post call.respond(HttpStatusCode.BadRequest, error("invalid_request"))
                }
            if (req.imageHash.isNullOrBlank() || req.imageId.isNullOrBlank()) {
                return@post call.respond(HttpStatusCode.BadRequest, error("invalid_request"))
            }

            try {
                val result =
                    service.handleDetection(
                        CsamDetectionService.Input(
                            cfImageId = req.imageId,
                            imageHash = req.imageHash,
                            cfMatchId = req.cfMatchId,
                            ncmecReference = req.ncmecReference,
                            source = authed.source,
                            actorAdminId = authed.actorAdminId,
                            ip = call.clientIp,
                            userAgent = call.request.header(HttpHeaders.UserAgent),
                        ),
                    )
                val status = if (result.uploaderResolved) "actioned" else "archived"
                call.respond(HttpStatusCode.OK, mapOf("status" to status))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Never echo the payload/secret — class only.
                log.warn("event=csam_webhook_failed error_class={}", e::class.simpleName, e)
                call.respond(HttpStatusCode.InternalServerError, error("internal_error"))
            }
        }
    }
}

/**
 * Mounts `POST /csam-archive-purge` under the parent `route("/internal")` block and
 * installs the OIDC gate on ITS OWN subtree (the `unbanWorkerRoute` precedent) — it is
 * an ordinary Cloud-Scheduler worker, NOT a vendor opt-out.
 */
fun Route.csamArchivePurgeRoute(
    service: CsamDetectionService,
    oidcVerifier: OidcTokenVerifier,
) {
    route("/csam-archive-purge") {
        install(InternalEndpointAuth) { verifier = oidcVerifier }
        post {
            try {
                val purged = service.purge()
                log.info("event=csam_archive_purged purged_count={}", purged)
                call.respond(HttpStatusCode.OK, CsamPurgeResponse(purgedCount = purged))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("event=csam_archive_purge_failed error_class={}", e::class.simpleName, e)
                call.respond(HttpStatusCode.InternalServerError, error("internal_error"))
            }
        }
    }
}

// --- Auth dispatch ---

private sealed interface AuthOutcome {
    data class Ok(
        val source: CsamDetectionService.Source,
        val actorAdminId: UUID,
        val rawBody: String?,
    ) : AuthOutcome

    data class Reject(
        val status: HttpStatusCode,
        val code: String,
        val retryAfterSeconds: Long? = null,
    ) : AuthOutcome
}

/**
 * Admin-internal path: validate the `__Host-admin_session` cookie + `X-CSRF-Token`
 * header + `owner`/`admin` role by reusing the admin-session components. Returns an
 * [AuthOutcome.Reject] (401/403) on any failure; never extends the session.
 */
private fun io.ktor.server.routing.RoutingContext.authenticateAdminInternal(
    cookie: String,
    sessionRepository: SessionRepository,
    adminUserRepository: AdminUserRepository,
): AuthOutcome {
    val session = sessionRepository.findBySessionHash(HashUtil.sha256Hex(cookie))
    if (session == null || !session.isActive(Instant.now(), AdminAuthProvider.DEFAULT_IDLE_TIMEOUT)) {
        return AuthOutcome.Reject(HttpStatusCode.Unauthorized, "unauthorized")
    }
    val admin = adminUserRepository.findById(session.adminId)
    if (admin == null || !admin.isActive) {
        return AuthOutcome.Reject(HttpStatusCode.Unauthorized, "unauthorized")
    }
    if (admin.role !in AdminRoleGate.OWNER_ADMIN_ROLES) {
        return AuthOutcome.Reject(HttpStatusCode.Forbidden, "forbidden")
    }
    // CSRF: the submitted token's hash must match THIS session's csrf_token_hash, so a
    // token captured from another session (S1) replayed here (S2) does not validate.
    val submittedCsrf = call.request.header(CSRF_HEADER)
    if (submittedCsrf.isNullOrBlank() ||
        !HashUtil.constantTimeEqualHex(HashUtil.sha256Hex(submittedCsrf), session.csrfTokenHash)
    ) {
        return AuthOutcome.Reject(HttpStatusCode.Forbidden, "csrf_failed")
    }
    return AuthOutcome.Ok(CsamDetectionService.Source.ADMIN_MANUAL, admin.id, rawBody = null)
}

/**
 * Cloudflare-Worker path: rate-limit per IP first (replay-amplification guard), then
 * verify the mandatory Bearer AND HMAC-SHA256 body signature (both keyed by
 * `cf-worker-csam-secret`, constant-time). Attributes audit to the `system` sentinel.
 */
private suspend fun io.ktor.server.routing.RoutingContext.authenticateCfWorker(
    bearerHeader: String,
    rateLimiter: RateLimiter,
    secrets: SecretResolver,
    cfWorkerSlot: String,
): AuthOutcome {
    val hashedIp = IpHasher.hash(call.clientIp)
    val rl =
        rateLimiter.tryAcquireByKey(
            key = "{scope:$RATE_SCOPE}:{ip:$hashedIp}",
            capacity = RATE_CAPACITY,
            ttl = RATE_WINDOW,
        )
    if (rl is RateLimiter.Outcome.RateLimited) {
        return AuthOutcome.Reject(HttpStatusCode.TooManyRequests, "rate_limited", rl.retryAfterSeconds)
    }

    val bearer = extractBearer(bearerHeader) ?: return AuthOutcome.Reject(HttpStatusCode.Unauthorized, "unauthorized")
    val secret = secrets.resolve(SECRET_CF_WORKER)
    if (secret.isNullOrEmpty()) {
        // Fail closed — without a configured secret nothing can authenticate.
        log.warn("event=csam_webhook_auth_failed reason=cf_secret_unset slot={}", cfWorkerSlot)
        return AuthOutcome.Reject(HttpStatusCode.Unauthorized, "unauthorized")
    }
    if (!constantTimeEquals(bearer, secret)) {
        log.warn("event=csam_webhook_auth_failed reason=bearer_mismatch")
        return AuthOutcome.Reject(HttpStatusCode.Unauthorized, "unauthorized")
    }

    // Read the raw body only after the Bearer is validated (HMAC needs it raw).
    val rawBody = call.receiveText()
    val signature =
        call.request.header(SIGNATURE_HEADER)
            ?: run {
                // HMAC is MANDATORY on the CF-Worker path (stricter than the RevenueCat webhook).
                log.warn("event=csam_webhook_auth_failed reason=signature_missing")
                return AuthOutcome.Reject(HttpStatusCode.Unauthorized, "invalid_signature")
            }
    if (!verifyHmac(secret, rawBody, signature)) {
        log.warn("event=csam_webhook_auth_failed reason=signature_mismatch")
        return AuthOutcome.Reject(HttpStatusCode.Unauthorized, "invalid_signature")
    }
    return AuthOutcome.Ok(CsamDetectionService.Source.CF_WORKER, SuspensionUnbanWorker.SYSTEM_ACTOR_ID, rawBody = rawBody)
}

// --- Crypto helpers (mirror RevenueCatWebhookRoutes — constant-time throughout) ---

private fun extractBearer(header: String?): String? {
    if (header == null) return null
    val prefix = "Bearer "
    if (!header.startsWith(prefix)) return null
    return header.substring(prefix.length).takeIf { it.isNotEmpty() }
}

private fun constantTimeEquals(
    a: String,
    b: String,
): Boolean = MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

private fun verifyHmac(
    secret: String,
    rawBody: String,
    providedHex: String,
): Boolean {
    val provided = hexToBytesOrNull(providedHex) ?: return false
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
    val computed = mac.doFinal(rawBody.toByteArray(Charsets.UTF_8))
    return MessageDigest.isEqual(computed, provided)
}

private fun hexToBytesOrNull(hex: String): ByteArray? {
    val cleaned = hex.trim()
    if (cleaned.isEmpty() || cleaned.length % 2 != 0) return null
    return try {
        ByteArray(cleaned.length / 2) { i -> cleaned.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    } catch (_: NumberFormatException) {
        null
    }
}

private fun error(code: String) = ApiError(ApiError.Envelope(code, code))

@Serializable
data class CsamWebhookRequest(
    @SerialName("image_id") val imageId: String? = null,
    @SerialName("image_hash") val imageHash: String? = null,
    @SerialName("cf_match_id") val cfMatchId: String? = null,
    @SerialName("ncmec_reference") val ncmecReference: String? = null,
)

@Serializable
data class CsamPurgeResponse(
    @SerialName("purged_count") val purgedCount: Int,
)

const val CSAM_WEBHOOK_PATH = "/internal/csam-webhook"
private const val SECRET_CF_WORKER = "cf-worker-csam-secret"
private const val SIGNATURE_HEADER = "X-CSAM-Signature"
private const val CSRF_HEADER = "X-CSRF-Token"
private const val RATE_SCOPE = "csam_webhook"
private const val RATE_CAPACITY = 100
private val RATE_WINDOW: Duration = Duration.ofHours(1)
private const val MAX_BODY_BYTES = 65_536L

private val log = LoggerFactory.getLogger("id.nearyou.app.moderation.csam.CsamWebhookRoutes")
