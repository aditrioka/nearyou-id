package id.nearyou.app.account

import id.nearyou.app.auth.AUTH_PROVIDER_USER
import id.nearyou.app.auth.UserPrincipal
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("id.nearyou.app.account.AccountRoutes")

/** `POST` response: the scheduled hard-delete deadline (ISO-8601, camelCase wire). */
@Serializable
data class DeletionScheduledResponse(val scheduledHardDeleteAt: String)

/** `GET` response: whether a deletion is pending + (if so) the restore-by deadline. */
@Serializable
data class DeletionStatusResponse(
    val pending: Boolean,
    val scheduledHardDeleteAt: String? = null,
)

/**
 * Account-deletion endpoints under `/api/v1/account/deletion-request` (the
 * `account-deletion` capability), all JWT-required via `AUTH_PROVIDER_USER`. The
 * caller identity comes solely from the verified principal — no `user_id` param,
 * so no IDOR surface.
 *
 * - `POST`   — idempotently schedule deletion (30-day grace) → `200` + deadline.
 * - `DELETE` — cancel/restore within grace → `200` on success, `409` when there is
 *   nothing to cancel (no pending request, already executed, or non-cancellable).
 * - `GET`    — read the pending state so the client can show the restore banner.
 *
 * Requesting deletion deliberately does NOT terminate the session or bump
 * `token_version` (it is not a suspension) — the account stays fully functional
 * during grace so the user can restore.
 */
fun Application.accountRoutes(service: AccountDeletionService) {
    routing {
        authenticate(AUTH_PROVIDER_USER) {
            route("/api/v1/account/deletion-request") {
                post {
                    val principal =
                        call.principal<UserPrincipal>() ?: run {
                            call.respond(HttpStatusCode.Unauthorized)
                            return@post
                        }
                    val scheduled = service.requestDeletion(principal.userId)
                    log.info("event=account_deletion_requested")
                    call.respond(
                        HttpStatusCode.OK,
                        DeletionScheduledResponse(scheduledHardDeleteAt = scheduled.toString()),
                    )
                }

                delete {
                    val principal =
                        call.principal<UserPrincipal>() ?: run {
                            call.respond(HttpStatusCode.Unauthorized)
                            return@delete
                        }
                    val cancelled = service.cancelDeletion(principal.userId)
                    if (cancelled) {
                        log.info("event=account_deletion_cancelled")
                        call.respond(HttpStatusCode.OK)
                    } else {
                        // Nothing to cancel: no pending request, already executed, or a
                        // non-cancellable source. Direction-less 409 (no state leak).
                        call.respond(HttpStatusCode.Conflict)
                    }
                }

                get {
                    val principal =
                        call.principal<UserPrincipal>() ?: run {
                            call.respond(HttpStatusCode.Unauthorized)
                            return@get
                        }
                    val scheduled = service.getStatus(principal.userId)
                    call.respond(
                        HttpStatusCode.OK,
                        DeletionStatusResponse(
                            pending = scheduled != null,
                            scheduledHardDeleteAt = scheduled?.toString(),
                        ),
                    )
                }
            }
        }
    }
}
