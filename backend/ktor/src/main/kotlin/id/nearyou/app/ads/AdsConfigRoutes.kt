package id.nearyou.app.ads

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
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("id.nearyou.app.ads.AdsConfigRoutes")

/**
 * The wire shape of `GET /api/v1/config/ads`. camelCase keys match the project's serialized-DTO
 * convention (kotlinx default; the timeline + hide-distance + account DTOs are all camelCase) — the
 * mobile `AdsConfigApiClient` DTO mirrors these names.
 */
@Serializable
data class AdsConfigResponse(
    val adsEnabled: Boolean,
    val timelineFrequency: Int,
)

/**
 * `GET /api/v1/config/ads` — the JWT-required, server-authoritative ad-serving configuration read (the
 * `ads-config` capability). A **thin route** (docs/11 §3.1): it authenticates, delegates the effective-gate
 * decision to [AdsConfigService] (premium suppression + the `ads_enabled` kill-switch + the timeline
 * frequency), and responds — no SQL, no business logic in the handler.
 *
 * The user identity (and the premium `subscription_status`) come solely from the verified principal, so
 * there is no `user_id` param and no IDOR surface. PII discipline: the handler logs only the content-free
 * event name (and, on failure, the exception class).
 */
fun Application.adsConfigRoutes(service: AdsConfigService) {
    routing {
        authenticate(AUTH_PROVIDER_USER) {
            get("/api/v1/config/ads") {
                val principal =
                    call.principal<UserPrincipal>() ?: run {
                        call.respond(HttpStatusCode.Unauthorized)
                        return@get
                    }
                try {
                    val config = service.resolve(principal.subscriptionStatus)
                    call.respond(
                        HttpStatusCode.OK,
                        AdsConfigResponse(
                            adsEnabled = config.adsEnabled,
                            timelineFrequency = config.timelineFrequency,
                        ),
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("event=ads_config_read_failed error_class={}", e::class.simpleName, e)
                    call.respond(HttpStatusCode.InternalServerError)
                }
            }
        }
    }
}
