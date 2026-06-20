package id.nearyou.app.admin.retention

import id.nearyou.app.admin.classifyHandlerError
import id.nearyou.app.core.domain.oidc.OidcTokenVerifier
import id.nearyou.app.internal.InternalEndpointAuth
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("id.nearyou.app.admin.retention.RetentionCleanupRoutes")

@Serializable
data class RetentionCleanupResponse(
    @SerialName("refresh_tokens_deleted") val refreshTokensDeleted: Int,
    @SerialName("notifications_deleted") val notificationsDeleted: Int,
    @SerialName("fcm_tokens_deleted") val fcmTokensDeleted: Int,
)

@Serializable
private data class ErrorResponse(val error: String)

/**
 * Mounts `POST /cleanup` under the parent `route("/internal")` block and
 * installs the OIDC gate on ITS OWN subtree (mirroring
 * [id.nearyou.app.admin.privacyFlipWorkerRoute] /
 * [id.nearyou.app.account.accountHardDeleteWorkerRoute]). The gate MUST NOT be
 * installed on the shared `/internal` node: Ktor merges identical path segments
 * across separate `routing {}` blocks, so a plugin there would also capture the
 * sibling vendor-auth webhooks — `/internal/revenuecat-webhook` (shared-secret
 * Bearer + HMAC) and `/internal/apple/s2s-notifications` (Apple-signed payload),
 * neither carrying a Google OIDC bearer — and 401 them before their own
 * verification ran. Regression guard: `InternalRoutingIsolationTest`. Never
 * reachable by a user JWT.
 *
 * Success: `200 OK` with body
 * `{"refresh_tokens_deleted": N, "notifications_deleted": N, "fcm_tokens_deleted": N}`.
 * On any thrown exception: `500` with a sanitized `{"error": "<classification>"}`
 * (`timeout` / `connection_refused` / `unknown`, via [classifyHandlerError] —
 * shared with the sibling workers); the original exception is logged at WARN
 * with full context but never leaks into the response.
 */
fun Route.retentionCleanupRoutes(
    worker: RetentionCleanupWorker,
    oidcVerifier: OidcTokenVerifier,
) {
    route("/cleanup") {
        install(InternalEndpointAuth) { verifier = oidcVerifier }
        post {
            handleRetentionCleanup(worker)
        }
    }
}

private suspend fun io.ktor.server.routing.RoutingContext.handleRetentionCleanup(worker: RetentionCleanupWorker) {
    run {
        val result =
            try {
                worker.execute()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val classification = classifyHandlerError(e)
                logger.warn(
                    "event=retention_cleanup_failed classification={} error_class={}",
                    classification,
                    e::class.simpleName,
                    e,
                )
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse(error = classification))
                return@run
            }

        call.respond(
            HttpStatusCode.OK,
            RetentionCleanupResponse(
                refreshTokensDeleted = result.refreshTokensDeleted,
                notificationsDeleted = result.notificationsDeleted,
                fcmTokensDeleted = result.fcmTokensDeleted,
            ),
        )
    }
}
