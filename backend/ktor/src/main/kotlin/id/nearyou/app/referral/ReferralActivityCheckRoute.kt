package id.nearyou.app.referral

import id.nearyou.app.core.domain.oidc.OidcTokenVerifier
import id.nearyou.app.internal.InternalEndpointAuth
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("id.nearyou.app.referral.ReferralActivityCheckRoute")

@Serializable
data class ReferralActivityCheckResponse(
    val expired: Int,
    val granted: Int,
    val pending: Int,
)

@Serializable
private data class ReferralActivityCheckErrorResponse(val error: String)

/**
 * Mounts `POST /referral-activity-check` under the parent `route("/internal")`
 * block and installs the OIDC gate on ITS OWN subtree (the `privacy-flip-worker`
 * precedent). The gate MUST NOT be installed on the shared `/internal` node: Ktor
 * merges identical path segments across separate `routing {}` blocks, so a plugin
 * on the shared node would also capture `/internal/revenuecat-webhook` (Bearer +
 * HMAC, not OIDC) and 401 it before its own verification ran. Regression guard:
 * `InternalRoutingIsolationTest`.
 */
fun Route.referralActivityCheckRoute(
    worker: ReferralActivityCheckWorker,
    oidcVerifier: OidcTokenVerifier,
) {
    route("/referral-activity-check") {
        install(InternalEndpointAuth) { verifier = oidcVerifier }
        post {
            handleReferralActivityCheck(worker)
        }
    }
}

private suspend fun RoutingContext.handleReferralActivityCheck(worker: ReferralActivityCheckWorker) {
    val result =
        try {
            worker.execute()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("event=referral_activity_check_failed error_class={}", e::class.simpleName, e)
            call.respond(
                HttpStatusCode.InternalServerError,
                ReferralActivityCheckErrorResponse(error = "internal_error"),
            )
            return
        }

    call.respond(
        HttpStatusCode.OK,
        ReferralActivityCheckResponse(
            expired = result.expired,
            granted = result.granted,
            pending = result.stillPending,
        ),
    )
}
