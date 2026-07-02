package id.nearyou.app.auth.routes

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import id.nearyou.app.account.AccountDeletionRepository
import id.nearyou.app.account.AccountHardDeleteWorker
import id.nearyou.app.auth.provider.JwksCache
import id.nearyou.app.common.AppJson
import id.nearyou.app.infra.repo.UserRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.util.Collections

const val APPLE_S2S_DEDUP_CAPACITY = 2_000

@Serializable
data class AppleS2SEnvelope(val signedPayload: String)

@Serializable
data class AppleS2SPayload(
    val type: String,
    val sub: String? = null,
    val transaction_id: String? = null,
    val events: String? = null,
)

private val logger = LoggerFactory.getLogger("AppleS2SRoutes")

class InMemoryDedup(private val capacity: Int = APPLE_S2S_DEDUP_CAPACITY) {
    private val seen: MutableSet<String> =
        Collections.newSetFromMap(
            object : LinkedHashMap<String, Boolean>(capacity, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean = size > capacity
            },
        )

    /** Check WITHOUT recording — the key is committed via [record] only on a 2xx outcome. */
    @Synchronized
    fun seen(id: String): Boolean = id in seen

    /**
     * Record a fully-processed notification. Deliberately NOT part of [seen]: a
     * non-2xx receipt (persist failure → 500, missing sub → 400) must leave the key
     * unconsumed so Apple's retry of the same `transaction_id` is processed, not
     * short-circuited to `duplicate` (review finding on the D3 retry contract).
     */
    @Synchronized
    fun record(id: String) {
        seen.add(id)
    }
}

fun Application.appleS2SRoutes(
    jwksCache: JwksCache,
    allowedAudiences: Set<String>,
    users: UserRepository,
    deletionRepo: AccountDeletionRepository,
    hardDeleteWorker: AccountHardDeleteWorker,
    dedup: InMemoryDedup = InMemoryDedup(),
) {
    routing {
        post("/internal/apple/s2s-notifications") {
            val envelope =
                try {
                    call.receive<AppleS2SEnvelope>()
                } catch (_: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ApiError(ApiError.Envelope("invalid_request", "Malformed Apple notification envelope.")),
                    )
                    return@post
                }

            val decoded =
                try {
                    JWT.decode(envelope.signedPayload)
                } catch (ex: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ApiError(ApiError.Envelope("invalid_request", "Could not decode JWT envelope: ${ex.message}")),
                    )
                    return@post
                }
            val kid = decoded.keyId
            if (kid == null) {
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ApiError(ApiError.Envelope("invalid_signature", "missing kid")),
                )
                return@post
            }
            val key = jwksCache.keyFor(kid)
            if (key == null) {
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ApiError(ApiError.Envelope("invalid_signature", "unknown kid: $kid")),
                )
                return@post
            }
            val verified =
                try {
                    JWT.require(Algorithm.RSA256(key, null)).acceptLeeway(5).build().verify(envelope.signedPayload)
                } catch (ex: JWTVerificationException) {
                    call.respond(
                        HttpStatusCode.Unauthorized,
                        ApiError(ApiError.Envelope("invalid_signature", ex.message ?: "verification failed")),
                    )
                    return@post
                }

            val tokenAud = verified.audience.orEmpty()
            if (allowedAudiences.isEmpty()) {
                // Fail CLOSED on an unconfigured allow-list, mirroring verifyRs256's
                // sign-in behavior — an unset APPLE_BUNDLE_IDS must not silently skip
                // the aud check (docs/06 §147 counts aud in S2S verification).
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ApiError(ApiError.Envelope("invalid_signature", "audience allow-list is empty")),
                )
                return@post
            }
            if (tokenAud.none { it in allowedAudiences }) {
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ApiError(ApiError.Envelope("invalid_signature", "audience not allowed")),
                )
                return@post
            }

            val payloadJson = String(java.util.Base64.getUrlDecoder().decode(envelope.signedPayload.split(".")[1]))
            val payload =
                try {
                    // Shared AppJson (01-#14) — this previously constructed a fresh
                    // Json (codec-cache and all) on EVERY notification.
                    AppJson.decodeFromString(AppleS2SPayload.serializer(), payloadJson)
                } catch (ex: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ApiError(ApiError.Envelope("invalid_request", "Could not parse JWT payload: ${ex.message}")),
                    )
                    return@post
                }

            // Fallback key when Apple omits transaction_id: `sub:type` means a later
            // IDENTICAL event for the same user (e.g. revoke → cancel → re-link →
            // revoke again) could be suppressed while the key lives in the LRU —
            // accepted: Apple sends transaction_id in practice, and the per-instance
            // LRU/restart bounds the window (spec R6 keeps the existing dedup shape).
            val dedupKey = payload.transaction_id ?: payload.sub.orEmpty() + ":" + payload.type
            if (dedup.seen(dedupKey)) {
                call.respond(HttpStatusCode.OK, mapOf("status" to "duplicate"))
                return@post
            }

            when (payload.type) {
                "email-enabled", "email-disabled" -> {
                    val sub = payload.sub
                    if (sub == null) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ApiError(ApiError.Envelope("invalid_request", "missing sub for ${payload.type}")),
                        )
                        return@post
                    }
                    val enabled = payload.type == "email-enabled"
                    users.setAppleRelayEmail(sha256Hex(sub), enabled)
                    dedup.record(dedupKey)
                    call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
                }
                "consent-revoked", "account-delete" -> {
                    val sub = payload.sub
                    if (sub == null) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ApiError(ApiError.Envelope("invalid_request", "missing sub for ${payload.type}")),
                        )
                        return@post
                    }
                    // Resolve to a LIVE user: findByAppleIdHash does NOT filter tombstoned
                    // rows, so an explicit deleted_at guard makes a re-sent notification for
                    // an already-deleted user a safe no-op (no row, no crash). Never log the
                    // raw sub / resolved user_id (PII discipline).
                    val user = users.findByAppleIdHash(sha256Hex(sub))?.takeIf { it.deletedAt == null }
                    if (user == null) {
                        dedup.record(dedupKey)
                        call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
                        return@post
                    }
                    if (payload.type == "consent-revoked") {
                        // 30-day cancellable grace (idempotent insert) + every-receipt
                        // session-kick (separate write, fires even on the no-op-insert path).
                        deletionRepo.scheduleConsentRevoked(user.id)
                        users.incrementTokenVersion(user.id)
                        dedup.record(dedupKey)
                        call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
                    } else {
                        // account-delete: persist the immediate row FIRST (durable backstop),
                        // then synchronously tombstone before the 200 to Apple (design D2/D3).
                        val rowId =
                            try {
                                deletionRepo.scheduleAppleAccountDelete(user.id)
                            } catch (ce: CancellationException) {
                                throw ce
                            } catch (ex: Exception) {
                                // Pre-persist failure (DB down) → non-2xx so Apple retries.
                                // Dedup key deliberately NOT recorded — the retry must be processed.
                                logger.warn(
                                    "event=apple_s2s_account_delete_persist_failed error_class={}",
                                    ex::class.simpleName,
                                )
                                call.respond(
                                    HttpStatusCode.InternalServerError,
                                    ApiError(ApiError.Envelope("internal_error", "could not persist deletion request")),
                                )
                                return@post
                            }
                        if (rowId != null) {
                            try {
                                hardDeleteWorker.executeImmediate(rowId)
                            } catch (ce: CancellationException) {
                                throw ce
                            } catch (ex: Exception) {
                                // Row is durably committed → the daily worker backstops it via
                                // deletion_requests_immediate_idx. Still 200 (D3). No PII in the log.
                                logger.warn(
                                    "event=apple_s2s_account_delete_exec_failed error_class={}",
                                    ex::class.simpleName,
                                )
                            }
                        }
                        dedup.record(dedupKey)
                        call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
                    }
                }
                else -> {
                    logger.info("Apple S2S unknown event type: {}", payload.type)
                    dedup.record(dedupKey)
                    call.respond(HttpStatusCode.OK, mapOf("status" to "ignored"))
                }
            }
        }
    }
}

private fun sha256Hex(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}
