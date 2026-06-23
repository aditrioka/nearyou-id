package id.nearyou.app.account

import id.nearyou.app.auth.AUTH_PROVIDER_USER
import id.nearyou.app.auth.UserPrincipal
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("id.nearyou.app.account.AccountDataExportRoutes")

/** `POST` response: the enqueued (or existing active) request id + its status (camelCase wire). */
@Serializable
data class DataExportRequestResponse(
    val requestId: String,
    val status: String,
)

/** `GET` response: the caller's latest export status, deadline, and (when ready) a fresh signed URL. */
@Serializable
data class DataExportStatusResponse(
    val status: String,
    val downloadExpiresAt: String? = null,
    val downloadUrl: String? = null,
)

/**
 * User-facing data-export endpoints under `/api/v1/account/export` (the `account-data-export`
 * capability), JWT-required via `AUTH_PROVIDER_USER`. The caller identity comes solely from the
 * verified principal — no `user_id` param, so no IDOR surface; export is keyed strictly on the
 * authenticated `user_id`.
 *
 * - `POST` — enqueue an export (idempotent / single-active: an existing `pending`/`processing`
 *   request is returned rather than duplicated) → `202` + `{request_id, status}`.
 * - `GET`  — the caller's latest status; for a `ready`-and-unexpired request a freshly-presigned
 *   24h download URL + `download_expires_at`; an `expired`/in-progress/absent request returns no
 *   URL. Never leaks another user's state, object key, or URL.
 */
fun Application.accountDataExportRoutes(service: DataExportService) {
    routing {
        authenticate(AUTH_PROVIDER_USER) {
            route("/api/v1/account/export") {
                post {
                    val principal =
                        call.principal<UserPrincipal>() ?: run {
                            call.respond(HttpStatusCode.Unauthorized)
                            return@post
                        }
                    val row = service.requestExport(principal.userId)
                    log.info("event=data_export_requested request_id={} status={}", row.id, row.status.wire)
                    call.respond(
                        HttpStatusCode.Accepted,
                        DataExportRequestResponse(requestId = row.id.toString(), status = row.status.wire),
                    )
                }

                get {
                    val principal =
                        call.principal<UserPrincipal>() ?: run {
                            call.respond(HttpStatusCode.Unauthorized)
                            return@get
                        }
                    val view = service.status(principal.userId)
                    if (view == null) {
                        // No export ever requested — report a no-export state (not a 404).
                        call.respond(
                            HttpStatusCode.OK,
                            DataExportStatusResponse(status = NO_EXPORT_STATUS),
                        )
                    } else {
                        call.respond(
                            HttpStatusCode.OK,
                            DataExportStatusResponse(
                                status = view.status.wire,
                                downloadExpiresAt = view.downloadExpiresAt?.toString(),
                                downloadUrl = view.downloadUrl,
                            ),
                        )
                    }
                }
            }
        }
    }
}

/** Sentinel status reported when the caller has never requested an export. */
private const val NO_EXPORT_STATUS = "none"
