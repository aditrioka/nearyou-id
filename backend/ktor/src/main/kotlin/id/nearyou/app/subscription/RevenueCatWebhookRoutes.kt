package id.nearyou.app.subscription

import id.nearyou.app.auth.routes.ApiError
import id.nearyou.app.common.AppJson
import id.nearyou.app.config.SecretResolver
import id.nearyou.app.config.secretKey
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.contentLength
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * `POST /internal/revenuecat-webhook` — the RevenueCat billing webhook
 * (`subscription-billing-webhook` capability).
 *
 * A VENDOR-AUTH webhook: it authenticates with its own shared-secret Bearer +
 * optional HMAC, NOT the OIDC `InternalEndpointAuth` plugin, and is therefore
 * mounted via its OWN `routing {}` block — a sibling of, NOT under, the gated
 * `route("/internal")` node (mirrors `appleS2SRoutes`; the `internal-endpoint-auth`
 * spec sanctions this route by name in its "Vendor-webhook route does NOT inherit
 * OIDC" scenario). Ktor merges identical path segments across routing blocks, so
 * installing the OIDC gate on the shared `/internal` node would also gate this
 * webhook — guarded by `InternalRoutingIsolationTest`.
 *
 * Auth (docs/01 § Payment Stack): `Authorization: Bearer <revenuecat-webhook-secret>`
 * is mandatory, compared constant-time; `X-RevenueCat-Signature` (HMAC-SHA256 over
 * the raw body, lower-hex) is verified against `revenuecat-webhook-hmac-secret`
 * WHEN present. Secret slot names come from `secretKey(env, name)`; values from the
 * resolver. Auth failures → 401 with a sanitized body + a WARN security-event log
 * (no secret / token / signature echo) feeding the webhook-auth-failure anomaly
 * signal. Mandatory production signing is deferred — an unsigned request is
 * admitted on the Bearer alone.
 *
 * Response contract (design D8): 200 for processed / duplicate / orphan / ignored
 * (so RevenueCat marks delivery complete); 400 for a malformed body; 401 for an
 * auth failure. Only the non-2xx paths are retried by RevenueCat.
 */
fun Application.revenueCatWebhookRoutes(
    service: SubscriptionService,
    secrets: SecretResolver,
    env: String,
) {
    val bearerSlot = secretKey(env, SECRET_BEARER)
    val hmacSlot = secretKey(env, SECRET_HMAC)
    routing {
        post("/internal/revenuecat-webhook") {
            // Bound the raw-body read (HMAC + parse both walk the full body).
            call.request.contentLength()?.let { len ->
                if (len > MAX_BODY_BYTES) {
                    call.respond(HttpStatusCode.PayloadTooLarge)
                    return@post
                }
            }

            // --- Vendor auth: mandatory Bearer FIRST, before reading the body, so an
            //     unauthenticated (especially chunked) caller cannot stream an unbounded
            //     body past the Content-Length guard above. ---
            val bearer = extractBearer(call.request.header(HttpHeaders.Authorization))
            if (bearer == null) {
                logAuthFailure("missing_bearer")
                return@post call.respond(HttpStatusCode.Unauthorized, error("unauthorized"))
            }
            val configuredSecret = secrets.resolve(SECRET_BEARER)
            if (configuredSecret.isNullOrEmpty()) {
                // Fail closed — without a configured secret nothing can authenticate.
                log.warn("event=revenuecat_webhook_auth_failed reason=bearer_secret_unset slot={}", bearerSlot)
                return@post call.respond(HttpStatusCode.Unauthorized, error("unauthorized"))
            }
            if (!constantTimeEquals(bearer, configuredSecret)) {
                logAuthFailure("bearer_mismatch")
                return@post call.respond(HttpStatusCode.Unauthorized, error("unauthorized"))
            }

            // Read the raw body only AFTER the Bearer is validated (HMAC needs it raw).
            val rawBody = call.receiveText()

            // --- Conditional HMAC over the raw body ---
            val signature = call.request.header(SIGNATURE_HEADER)
            if (signature != null) {
                val hmacSecret = secrets.resolve(SECRET_HMAC)
                if (hmacSecret.isNullOrEmpty()) {
                    log.warn("event=revenuecat_webhook_auth_failed reason=hmac_secret_unset slot={}", hmacSlot)
                    return@post call.respond(HttpStatusCode.Unauthorized, error("invalid_signature"))
                }
                if (!verifyHmac(hmacSecret, rawBody, signature)) {
                    logAuthFailure("signature_mismatch")
                    return@post call.respond(HttpStatusCode.Unauthorized, error("invalid_signature"))
                }
            }
            // An absent signature header is accepted on the Bearer alone — mandatory
            // production signing is deferred (spec § "Mandatory production webhook
            // signing is deferred").

            // --- Envelope parse + validation ---
            val envelope =
                try {
                    AppJson.decodeFromString(RevenueCatWebhookEnvelope.serializer(), rawBody)
                } catch (_: SerializationException) {
                    return@post call.respond(HttpStatusCode.BadRequest, error("invalid_request"))
                } catch (_: IllegalArgumentException) {
                    return@post call.respond(HttpStatusCode.BadRequest, error("invalid_request"))
                }
            val event = envelope.event
            val type = event?.type
            val rcEventId = event?.id
            val appUserId = event?.appUserId
            if (event == null || type.isNullOrBlank() || rcEventId.isNullOrBlank() || appUserId.isNullOrBlank()) {
                return@post call.respond(HttpStatusCode.BadRequest, error("invalid_request"))
            }
            val userId =
                try {
                    UUID.fromString(appUserId)
                } catch (_: IllegalArgumentException) {
                    return@post call.respond(HttpStatusCode.BadRequest, error("invalid_request"))
                }

            val incoming =
                SubscriptionService.IncomingEvent(
                    type = type,
                    revenuecatEventId = rcEventId,
                    userId = userId,
                    entitlementStart = event.purchasedAtMs?.let(Instant::ofEpochMilli),
                    entitlementEnd = event.expirationAtMs?.let(Instant::ofEpochMilli),
                    // Indonesia-only app: the purchased currency is IDR (whole rupiah).
                    amountRupiah = event.price?.toLong(),
                    platform = event.store?.lowercase()?.take(MAX_PLATFORM_LEN),
                )

            try {
                val status =
                    when (service.process(incoming)) {
                        SubscriptionService.Result.Ok -> "ok"
                        SubscriptionService.Result.Duplicate -> "duplicate"
                        SubscriptionService.Result.UnknownUser -> "ignored"
                        SubscriptionService.Result.Ignored -> "ignored"
                    }
                call.respond(HttpStatusCode.OK, mapOf("status" to status))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Never echo secrets/body in the failure log — class only.
                log.warn("event=revenuecat_webhook_failed rc_event_id={} error_class={}", rcEventId, e::class.simpleName)
                call.respond(HttpStatusCode.InternalServerError, error("internal_error"))
            }
        }
    }
}

private fun logAuthFailure(reason: String) {
    // WARN security event — distinguishes bearer-failure from signature-failure so the
    // "signature fail rate" anomaly alert keys on genuine signature failures, not scans.
    log.warn("event=revenuecat_webhook_auth_failed reason={}", reason)
}

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
        ByteArray(cleaned.length / 2) { i ->
            cleaned.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    } catch (_: NumberFormatException) {
        null
    }
}

private fun error(code: String) = ApiError(ApiError.Envelope(code, code))

private const val SECRET_BEARER = "revenuecat-webhook-secret"
private const val SECRET_HMAC = "revenuecat-webhook-hmac-secret"
private const val SIGNATURE_HEADER = "X-RevenueCat-Signature"
private const val MAX_BODY_BYTES = 65_536L
private const val MAX_PLATFORM_LEN = 16

private val log = LoggerFactory.getLogger("id.nearyou.app.subscription.RevenueCatWebhookRoutes")

@Serializable
private data class RevenueCatWebhookEnvelope(val event: RevenueCatEvent? = null)

@Serializable
private data class RevenueCatEvent(
    val type: String? = null,
    val id: String? = null,
    @SerialName("app_user_id") val appUserId: String? = null,
    @SerialName("purchased_at_ms") val purchasedAtMs: Long? = null,
    @SerialName("expiration_at_ms") val expirationAtMs: Long? = null,
    val store: String? = null,
    @SerialName("price_in_purchased_currency") val price: Double? = null,
    // Other envelope fields (expiration_reason, currency, …) are intentionally not
    // deserialized: the reason does not gate the EXPIRATION downgrade and is not
    // persisted (no `subscription_events` column per docs/05); the app is IDR-only
    // so `amount_rupiah` is currency-implicit. AppJson `ignoreUnknownKeys` drops them.
)
