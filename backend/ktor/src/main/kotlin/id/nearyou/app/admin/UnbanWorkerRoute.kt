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
import java.sql.SQLNonTransientConnectionException
import java.sql.SQLTimeoutException
import java.sql.SQLTransientConnectionException

private val logger = LoggerFactory.getLogger("id.nearyou.app.admin.UnbanWorkerRoute")

/** Cap on `unbanned_user_ids` array length in the structured INFO log. */
const val MAX_LOGGED_USER_IDS: Int = 50

@Serializable
data class UnbanWorkerResponse(
    @SerialName("unbanned_count") val unbannedCount: Int,
)

@Serializable
private data class ErrorResponse(val error: String)

/**
 * Mounts `POST /unban-worker` under the parent `route("/internal")` block and
 * installs the OIDC gate on ITS OWN subtree (per the `internal-endpoint-auth`
 * spec scenario "the InternalEndpointAuth plugin is mounted on
 * `/internal/unban-worker`"). The gate MUST NOT be installed on the shared
 * `/internal` node: Ktor merges identical path segments across separate
 * `routing {}` blocks, so a plugin on the shared node would also capture
 * vendor-auth webhooks (`/internal/apple/s2s-notifications` authenticates via
 * Apple-signed payload, not Google OIDC) — they would 401 before their own
 * verification ran. Regression guard: `InternalRoutingIsolationTest`.
 *
 * Response shape on success: `200 OK` with body `{"unbannedCount": N}`. On any
 * thrown exception: `500` with body `{"error": "<classification>"}` where
 * classification is one of `timeout`, `connection_refused`, `unknown`. The
 * original exception is logged at WARN with full context but never leaks into
 * the response.
 *
 * The classifier is inlined here (per `tasks.md` 7.3 — `health-check-endpoints`
 * did not extract a reusable helper). Once a third call site lands, this could
 * be promoted to a shared utility (rule of three).
 */
fun Route.unbanWorkerRoute(
    worker: SuspensionUnbanWorker,
    oidcVerifier: OidcTokenVerifier,
) {
    route("/unban-worker") {
        install(InternalEndpointAuth) { verifier = oidcVerifier }
        post {
            handleUnbanWorker(worker)
        }
    }
}

private suspend fun io.ktor.server.routing.RoutingContext.handleUnbanWorker(worker: SuspensionUnbanWorker) {
    run {
        val result =
            try {
                worker.execute()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val classification = classifyHandlerError(e)
                logger.warn(
                    "event=suspension_unban_failed classification={} error_class={}",
                    classification,
                    e::class.simpleName,
                    e,
                )
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse(error = classification))
                return@run
            }

        val cappedIds = result.unbannedUserIds.take(MAX_LOGGED_USER_IDS).map { it.toString() }
        val truncated = result.unbannedUserIds.size > MAX_LOGGED_USER_IDS
        if (truncated) {
            logger.info(
                "event=suspension_unban_applied unbanned_count={} unbanned_user_ids={} unbanned_user_ids_truncated={} duration_ms={}",
                result.unbannedCount,
                cappedIds,
                true,
                result.durationMs,
            )
        } else {
            logger.info(
                "event=suspension_unban_applied unbanned_count={} unbanned_user_ids={} duration_ms={}",
                result.unbannedCount,
                cappedIds,
                result.durationMs,
            )
        }

        call.respond(HttpStatusCode.OK, UnbanWorkerResponse(unbannedCount = result.unbannedCount))
    }
}

/**
 * Maps a thrown exception to one of the fixed-vocabulary handler-level error
 * classifications: `timeout`, `connection_refused`, `unknown`. The vocabulary is
 * documented in the `suspension-unban-worker` capability spec § "Response shape".
 */
internal fun classifyHandlerError(e: Throwable): String {
    return when (e) {
        is SQLTimeoutException -> "timeout"
        is SQLTransientConnectionException -> "connection_refused"
        is SQLNonTransientConnectionException -> "connection_refused"
        else -> {
            // HikariCP wraps connection-acquire failures in its own exception type
            // whose message starts with "HikariPool-N - Connection is not available".
            val msg = e.message.orEmpty()
            if (msg.contains("Connection is not available") ||
                msg.contains("connection refused", ignoreCase = true)
            ) {
                "connection_refused"
            } else {
                "unknown"
            }
        }
    }
}
