package id.nearyou.app.referral

import id.nearyou.app.auth.AUTH_PROVIDER_USER
import id.nearyou.app.auth.UserPrincipal
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("id.nearyou.app.referral.ReferralReadRoutes")

/**
 * `GET /api/v1/user/referral` — the JWT-required referral read (the `referral-read` capability).
 *
 * Returns the caller's shareable invite code (`users.invite_code_prefix`, returned verbatim — the
 * same value redemption resolves) plus referral progress: the `granted` referral-ticket count, the
 * 5-referral inviter milestone, and whether the inviter lifetime reward is already claimed. The caller
 * is resolved from the verified principal (`principal.userId`) ONLY — the route accepts no `user_id`
 * path or query param, so it has NO IDOR surface. A pure read: it writes nothing and grants nothing
 * (the activity-check worker remains the sole authority for promoting tickets + claiming the reward).
 *
 * Mirrors `HideDistanceRoutes` GET: `401` on a missing principal, `200` with the DTO, a fail-soft `500`
 * on a repository error, and a `CancellationException` rethrow. PII discipline: the handler logs only a
 * content-free event name (and, on failure, the exception class) — never the bearer token, the JWT
 * `sub`, the invite code, or the response body.
 */
fun Application.referralReadRoutes(repository: ReferralReadRepository) {
    routing {
        authenticate(AUTH_PROVIDER_USER) {
            get("/api/v1/user/referral") {
                val principal =
                    call.principal<UserPrincipal>() ?: run {
                        call.respond(HttpStatusCode.Unauthorized)
                        return@get
                    }
                try {
                    val state = repository.getReferralState(principal.userId)
                    call.respond(
                        HttpStatusCode.OK,
                        ReferralStateResponse(
                            inviteCode = state.inviteCode,
                            grantedReferrals = state.grantedReferrals,
                            milestone = ReferralActivityCheckWorker.INVITER_MILESTONE,
                            inviterRewardClaimed = state.inviterRewardClaimed,
                        ),
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("event=referral_read_failed error_class={}", e::class.simpleName, e)
                    call.respond(HttpStatusCode.InternalServerError)
                }
            }
        }
    }
}
