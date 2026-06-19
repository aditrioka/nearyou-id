package id.nearyou.app.account

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

private val logger = LoggerFactory.getLogger("id.nearyou.app.account.AccountHardDeleteWorkerRoute")

@Serializable
data class AccountHardDeleteWorkerResponse(
    @SerialName("deleted_count") val deletedCount: Int,
)

@Serializable
private data class ErrorResponse(val error: String)

/**
 * Mounts `POST /account-hard-delete-worker` under the parent `route("/internal")`
 * block and installs the OIDC gate on ITS OWN subtree (mirroring
 * [id.nearyou.app.admin.unbanWorkerRoute] — the gate MUST NOT be installed on the
 * shared `/internal` node, which would also capture the Apple-S2S webhook). Never
 * reachable by a user JWT.
 *
 * Success: `200 OK` `{"deleted_count": N}`. On any thrown exception: `500` with a
 * sanitized `{"error": "<classification>"}` (`timeout` / `connection_refused` /
 * `unknown`); the original exception is logged at WARN, never leaked.
 */
fun Route.accountHardDeleteWorkerRoute(
    worker: AccountHardDeleteWorker,
    oidcVerifier: OidcTokenVerifier,
) {
    route("/account-hard-delete-worker") {
        install(InternalEndpointAuth) { verifier = oidcVerifier }
        post {
            val result =
                try {
                    worker.execute()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val classification = classifyHandlerError(e)
                    logger.warn(
                        "event=account_hard_delete_failed classification={} error_class={}",
                        classification,
                        e::class.simpleName,
                        e,
                    )
                    call.respond(HttpStatusCode.InternalServerError, ErrorResponse(error = classification))
                    return@post
                }
            logger.info(
                "event=account_hard_delete_applied deleted_count={} duration_ms={}",
                result.deletedCount,
                result.durationMs,
            )
            call.respond(HttpStatusCode.OK, AccountHardDeleteWorkerResponse(deletedCount = result.deletedCount))
        }
    }
}
