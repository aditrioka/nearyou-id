package id.nearyou.app.auth.routes

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import id.nearyou.app.auth.provider.JwksCache
import id.nearyou.app.infra.repo.UserRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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

    @Synchronized
    fun seen(id: String): Boolean = !seen.add(id)
}

fun Application.appleS2SRoutes(
    jwksCache: JwksCache,
    allowedAudiences: Set<String>,
    users: UserRepository,
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
            if (allowedAudiences.isNotEmpty() && tokenAud.none { it in allowedAudiences }) {
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ApiError(ApiError.Envelope("invalid_signature", "audience not allowed")),
                )
                return@post
            }

            val payloadJson = String(java.util.Base64.getUrlDecoder().decode(envelope.signedPayload.split(".")[1]))
            val payload =
                try {
                    Json { ignoreUnknownKeys = true }.decodeFromString(AppleS2SPayload.serializer(), payloadJson)
                } catch (ex: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ApiError(ApiError.Envelope("invalid_request", "Could not parse JWT payload: ${ex.message}")),
                    )
                    return@post
                }

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
                    call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
                }
                "consent-revoked", "account-delete" -> {
                    logger.warn("Apple S2S event {} received but deletion handlers are deferred", payload.type)
                    call.respond(
                        HttpStatusCode.NotImplemented,
                        ApiError(ApiError.Envelope("not_implemented", "${payload.type} deferred to deletion-flows change")),
                    )
                }
                else -> {
                    logger.info("Apple S2S unknown event type: {}", payload.type)
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
