package id.nearyou.app.image

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

private val logger = LoggerFactory.getLogger("id.nearyou.app.image.OrphanImageCleanupRoutes")

@Serializable
data class OrphanImageCleanupResponse(
    @SerialName("scanned") val scanned: Int,
    @SerialName("deleted") val deleted: Int,
    @SerialName("cf_failures") val cfFailures: Int,
)

@Serializable
private data class ErrorResponse(val error: String)

/**
 * Mounts `POST /cleanup-orphan-images` under the parent `route("/internal")`
 * block and installs the OIDC gate on ITS OWN subtree (mirroring
 * [id.nearyou.app.admin.retention.retentionCleanupRoutes] — see that KDoc for
 * why the gate MUST NOT sit on the shared `/internal` node: the vendor-auth
 * webhook siblings carry non-OIDC credentials; regression guard
 * `InternalRoutingIsolationTest`). Never reachable by a user JWT.
 *
 * Success: `200 OK` with `{"scanned": N, "deleted": N, "cf_failures": N}`.
 * On any thrown exception: `500` with a sanitized `{"error": "<classification>"}`
 * via [classifyHandlerError]; the original exception is logged at WARN with
 * full context but never leaks into the response.
 */
fun Route.orphanImageCleanupRoutes(
    worker: OrphanImageCleanupWorker,
    oidcVerifier: OidcTokenVerifier,
) {
    route("/cleanup-orphan-images") {
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
                        "event=orphan_image_cleanup_failed classification={} error_class={}",
                        classification,
                        e::class.simpleName,
                        e,
                    )
                    call.respond(HttpStatusCode.InternalServerError, ErrorResponse(error = classification))
                    return@post
                }

            call.respond(
                HttpStatusCode.OK,
                OrphanImageCleanupResponse(
                    scanned = result.scanned,
                    deleted = result.deleted,
                    cfFailures = result.cfFailures,
                ),
            )
        }
    }
}
