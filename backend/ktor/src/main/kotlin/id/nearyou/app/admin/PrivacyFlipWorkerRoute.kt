package id.nearyou.app.admin

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

private val logger = LoggerFactory.getLogger("id.nearyou.app.admin.PrivacyFlipWorkerRoute")

@Serializable
data class PrivacyFlipWorkerResponse(
    @SerialName("flipped_count") val flippedCount: Int,
)

@Serializable
private data class PrivacyFlipErrorResponse(val error: String)

/**
 * Mounts `POST /privacy-flip-worker` under the parent `route("/internal")` block
 * and installs the OIDC gate on ITS OWN subtree (per the `internal-endpoint-auth`
 * spec — mirroring [unbanWorkerRoute]). The gate MUST NOT be installed on the
 * shared `/internal` node: Ktor merges identical path segments across separate
 * `routing {}` blocks, so a plugin on the shared node would also capture the
 * vendor-auth webhooks (`/internal/revenuecat-webhook` authenticates via
 * shared-secret Bearer + HMAC, `/internal/apple/s2s-notifications` via an
 * Apple-signed payload — neither carries a Google OIDC bearer) — they would 401
 * before their own verification ran. Regression guard: `InternalRoutingIsolationTest`.
 *
 * Response shape on success: `200 OK` with body `{"flipped_count": N}`. On any
 * thrown exception: `500` with body `{"error": "<classification>"}` where
 * classification is one of `timeout`, `connection_refused`, `unknown`
 * ([classifyHandlerError], shared with [unbanWorkerRoute] — same package). The
 * original exception is logged at WARN with full context but never leaks into
 * the response.
 */
fun Route.privacyFlipWorkerRoute(
    worker: PrivacyFlipWorker,
    oidcVerifier: OidcTokenVerifier,
) {
    route("/privacy-flip-worker") {
        install(InternalEndpointAuth) { verifier = oidcVerifier }
        post {
            handlePrivacyFlipWorker(worker)
        }
    }
}

private suspend fun io.ktor.server.routing.RoutingContext.handlePrivacyFlipWorker(worker: PrivacyFlipWorker) {
    run {
        val result =
            try {
                worker.execute()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val classification = classifyHandlerError(e)
                logger.warn(
                    "event=privacy_flip_failed classification={} error_class={}",
                    classification,
                    e::class.simpleName,
                    e,
                )
                call.respond(HttpStatusCode.InternalServerError, PrivacyFlipErrorResponse(error = classification))
                return@run
            }

        val cappedIds = result.flippedUserIds.take(MAX_LOGGED_USER_IDS).map { it.toString() }
        val truncated = result.flippedUserIds.size > MAX_LOGGED_USER_IDS
        if (truncated) {
            logger.info(
                "event=privacy_flip_applied flipped_count={} flipped_user_ids={} flipped_user_ids_truncated={} duration_ms={}",
                result.flippedCount,
                cappedIds,
                true,
                result.durationMs,
            )
        } else {
            logger.info(
                "event=privacy_flip_applied flipped_count={} flipped_user_ids={} duration_ms={}",
                result.flippedCount,
                cappedIds,
                result.durationMs,
            )
        }

        call.respond(HttpStatusCode.OK, PrivacyFlipWorkerResponse(flippedCount = result.flippedCount))
    }
}
