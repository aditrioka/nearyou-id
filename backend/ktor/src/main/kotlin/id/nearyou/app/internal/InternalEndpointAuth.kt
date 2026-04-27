package id.nearyou.app.internal

import id.nearyou.app.core.domain.oidc.OidcTokenVerifier
import id.nearyou.app.core.domain.oidc.OidcVerificationException
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.hooks.CallSetup
import io.ktor.server.response.respondText
import io.ktor.util.AttributeKey
import org.slf4j.LoggerFactory
import java.security.MessageDigest

/**
 * Plugin configuration. Holds the [OidcTokenVerifier] used to verify inbound
 * `Authorization: Bearer <token>` headers on routes mounted under the
 * `internal/` subtree.
 */
class InternalEndpointAuthConfig {
    var verifier: OidcTokenVerifier? = null
}

/**
 * Attribute carrying the verified OIDC subject (the Cloud Scheduler service-account
 * email) on a successfully authenticated call. Handlers can read via
 * `call.attributes[OidcSubjectKey]` if they need to gate per-subject logic.
 */
val OidcSubjectKey: AttributeKey<String> = AttributeKey("OidcSubject")

private val logger = LoggerFactory.getLogger("id.nearyou.app.internal.InternalEndpointAuth")

private const val ERROR_MISSING_AUTHORIZATION = """{"error":"missing_authorization"}"""
private const val ERROR_INVALID_SCHEME = """{"error":"invalid_scheme"}"""
private const val ERROR_INVALID_TOKEN = """{"error":"invalid_token"}"""
private const val ERROR_EXPIRED_TOKEN = """{"error":"expired_token"}"""
private const val ERROR_AUDIENCE_MISMATCH = """{"error":"audience_mismatch"}"""

/**
 * Route-scoped plugin that gates every request to its installed route subtree behind
 * a Google OIDC bearer token. Any failure short-circuits with `401 Unauthorized`
 * and a sanitized JSON body whose `error` field uses the fixed vocabulary defined
 * by the `internal-endpoint-auth` capability spec.
 *
 * Health endpoints (`/health/live`, `/health/ready`) are NOT mounted under
 * `route("/internal")` so this plugin never applies to them.
 */
val InternalEndpointAuth =
    createRouteScopedPlugin(
        name = "InternalEndpointAuth",
        createConfiguration = ::InternalEndpointAuthConfig,
    ) {
        val verifier =
            pluginConfig.verifier
                ?: error("InternalEndpointAuth requires a non-null OidcTokenVerifier")

        on(CallSetup) { call ->
            val authHeader = call.request.headers[HttpHeaders.Authorization]
            if (authHeader.isNullOrBlank()) {
                logRejection("missing_authorization", null, null)
                respondUnauthorized(call, ERROR_MISSING_AUTHORIZATION)
                return@on
            }
            val parts = authHeader.split(' ', limit = 2)
            if (parts.size != 2 || !parts[0].equals("Bearer", ignoreCase = true)) {
                logRejection("invalid_scheme", parts.getOrNull(0), null)
                respondUnauthorized(call, ERROR_INVALID_SCHEME)
                return@on
            }
            val token = parts[1].trim()
            if (token.isEmpty()) {
                logRejection("invalid_token", "Bearer", null)
                respondUnauthorized(call, ERROR_INVALID_TOKEN)
                return@on
            }
            try {
                val claims = verifier.verify(token)
                call.attributes.put(OidcSubjectKey, claims.sub)
            } catch (e: OidcVerificationException.AudienceMismatch) {
                logRejection("audience_mismatch", "Bearer", token)
                respondUnauthorized(call, ERROR_AUDIENCE_MISMATCH)
            } catch (e: OidcVerificationException.ExpiredToken) {
                logRejection("expired_token", "Bearer", token)
                respondUnauthorized(call, ERROR_EXPIRED_TOKEN)
            } catch (e: OidcVerificationException.InvalidToken) {
                logRejection("invalid_token", "Bearer", token)
                respondUnauthorized(call, ERROR_INVALID_TOKEN)
            } catch (e: OidcVerificationException.InvalidScheme) {
                logRejection("invalid_scheme", "Bearer", token)
                respondUnauthorized(call, ERROR_INVALID_SCHEME)
            } catch (e: OidcVerificationException.MissingAuthorization) {
                logRejection("missing_authorization", "Bearer", token)
                respondUnauthorized(call, ERROR_MISSING_AUTHORIZATION)
            }
        }
    }

private suspend fun respondUnauthorized(
    call: ApplicationCall,
    body: String,
) {
    call.respondText(
        text = body,
        contentType = ContentType.Application.Json,
        status = HttpStatusCode.Unauthorized,
    )
}

private fun logRejection(
    reason: String,
    scheme: String?,
    token: String?,
) {
    val correlationId = token?.let { tokenCorrelationId(it) } ?: "n/a"
    logger.warn(
        "event=internal_auth_rejected reason={} scheme={} token_corr_id={}",
        reason,
        scheme ?: "none",
        correlationId,
    )
}

/**
 * 16-hex-char SHA-256 prefix of the raw token bytes. Operators correlate this
 * with Cloud Scheduler invocation logs without ever logging the token itself,
 * the JWT claims, or the configured audience.
 */
private fun tokenCorrelationId(token: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
    return digest.joinToString(separator = "", limit = 8, truncated = "") { byte ->
        "%02x".format(byte)
    }
}
