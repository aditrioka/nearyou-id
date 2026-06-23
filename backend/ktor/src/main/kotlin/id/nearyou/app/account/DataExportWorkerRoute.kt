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
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("id.nearyou.app.account.DataExportWorkerRoute")

@Serializable
data class DataExportWorkerResponse(
    val processed: Int,
    val ready: Int,
    val failed: Int,
)

@Serializable
private data class DataExportWorkerError(val error: String)

/**
 * Mounts `POST /data-export-worker` under the parent `route("/internal")` block and installs
 * the OIDC gate on ITS OWN subtree (mirroring [accountHardDeleteWorkerRoute] — the gate MUST
 * NOT be installed on the shared `/internal` node, which would also capture the vendor-auth
 * Apple-S2S webhook). Never reachable by a user JWT.
 *
 * Success: `200 OK` `{"processed": N, "ready": N, "failed": N}`. On any thrown exception: `500`
 * with a sanitized `{"error": "<classification>"}`; the original exception is logged at WARN,
 * never leaked. (Per-request gather/upload/email failures are handled inside the worker as soft
 * `failed` and do NOT surface as a 500 — only an unexpected worker-level error does.)
 */
fun Route.dataExportWorkerRoute(
    worker: DataExportWorker,
    oidcVerifier: OidcTokenVerifier,
) {
    route("/data-export-worker") {
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
                        "event=data_export_worker_failed classification={} error_class={}",
                        classification,
                        e::class.simpleName,
                        e,
                    )
                    call.respond(HttpStatusCode.InternalServerError, DataExportWorkerError(error = classification))
                    return@post
                }
            logger.info(
                "event=data_export_worker_applied processed={} ready={} failed={} duration_ms={}",
                result.processedCount,
                result.readyCount,
                result.failedCount,
                result.durationMs,
            )
            call.respond(
                HttpStatusCode.OK,
                DataExportWorkerResponse(
                    processed = result.processedCount,
                    ready = result.readyCount,
                    failed = result.failedCount,
                ),
            )
        }
    }
}
