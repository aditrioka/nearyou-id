package id.nearyou.app.auth.anomaly

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

private val logger = LoggerFactory.getLogger("id.nearyou.app.auth.anomaly.LoginAnomalyCheckRoutes")

@Serializable
data class LoginAnomalyCheckResponse(
    @SerialName("flagged_users") val flaggedUsers: Int,
    @SerialName("rows_recorded") val rowsRecorded: Int,
    @SerialName("record_failures") val recordFailures: Int,
)

@Serializable
private data class ErrorResponse(val error: String)

/**
 * Mounts `POST /login-anomaly-check` under the parent `route("/internal")` block
 * and installs the OIDC gate on ITS OWN subtree (mirroring
 * [id.nearyou.app.admin.retention.retentionCleanupRoutes] /
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
 * `{"flagged_users": N, "rows_recorded": N, "record_failures": N}`. On any thrown
 * exception: `500` with a sanitized `{"error": "<classification>"}`
 * (`timeout` / `connection_refused` / `unknown`, via [classifyHandlerError] —
 * shared with the sibling workers); the original exception is logged at WARN with
 * full context but never leaks into the response (no PII / exception text).
 */
fun Route.loginAnomalyCheckRoutes(
    service: LoginAnomalyDetectionService,
    oidcVerifier: OidcTokenVerifier,
) {
    route("/login-anomaly-check") {
        install(InternalEndpointAuth) { verifier = oidcVerifier }
        post {
            handleLoginAnomalyCheck(service)
        }
    }
}

private suspend fun io.ktor.server.routing.RoutingContext.handleLoginAnomalyCheck(service: LoginAnomalyDetectionService) {
    run {
        val result =
            try {
                service.sweep()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val classification = classifyHandlerError(e)
                logger.warn(
                    "event=login_anomaly_check_failed classification={} error_class={}",
                    classification,
                    e::class.simpleName,
                    e,
                )
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse(error = classification))
                return@run
            }

        call.respond(
            HttpStatusCode.OK,
            LoginAnomalyCheckResponse(
                flaggedUsers = result.flagged,
                rowsRecorded = result.recorded,
                recordFailures = result.failed,
            ),
        )
    }
}
