package id.nearyou.app.infra.revenuecatapi

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.apache5.Apache5
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Live RevenueCat v1 promotional-entitlement grant client (raw Ktor, no vendor SDK).
 *
 * Endpoint (verified 2026-06-20, tasks 1.1):
 * `POST https://api.revenuecat.com/v1/subscribers/{app_user_id}/entitlements/{entitlement_id}/promotional`
 * authenticated by the project **secret** API key as a Bearer token; the body carries
 * the absolute `end_time_ms`. A 2xx response means the entitlement was granted/extended.
 *
 * The [HttpClient] engine is injectable so tests bind a `MockEngine`; production uses
 * [Apache5]. The instance is constructed once (via [referralEntitlementGranter]) and
 * lives for the app's lifetime.
 */
class RevenueCatPromotionalGranter(
    private val secretApiKey: String,
    engine: HttpClientEngine = Apache5.create(),
    private val baseUrl: String = REVENUECAT_API_BASE,
) : ReferralEntitlementGranter {
    private val log = LoggerFactory.getLogger(RevenueCatPromotionalGranter::class.java)
    private val client = HttpClient(engine)
    private val json = Json { encodeDefaults = true }

    override fun isConfigured(): Boolean = true

    override suspend fun grant(request: GrantRequest): GrantResult {
        val url =
            "$baseUrl/v1/subscribers/${request.appUserId.encodePathSegment()}" +
                "/entitlements/${request.entitlementId.encodePathSegment()}/promotional"
        return try {
            val response: HttpResponse =
                client.post(url) {
                    header(HttpHeaders.Authorization, "Bearer $secretApiKey")
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(PromotionalGrantBody(endTimeMs = request.endTimeMs)))
                }
            if (response.status.isSuccess()) {
                GrantResult.Dispatched
            } else {
                // Body may carry RevenueCat's error detail; do NOT log the secret.
                val detail = runCatching { response.bodyAsText() }.getOrDefault("")
                log.warn(
                    "event=revenuecat_grant_rejected status={} dedup_key={} detail={}",
                    response.status.value,
                    request.dedupKey,
                    detail.take(ERROR_DETAIL_MAX),
                )
                GrantResult.Failed("status=${response.status.value}")
            }
        } catch (t: Throwable) {
            log.warn(
                "event=revenuecat_grant_error dedup_key={} error={}",
                request.dedupKey,
                t.message,
            )
            GrantResult.Failed(t.message ?: t::class.simpleName ?: "unknown")
        }
    }

    @Serializable
    private data class PromotionalGrantBody(
        @SerialName("end_time_ms") val endTimeMs: Long,
    )

    private companion object {
        const val REVENUECAT_API_BASE = "https://api.revenuecat.com"
        const val ERROR_DETAIL_MAX = 500
    }
}

/** Percent-encodes a single path segment (app-user id / entitlement id) for the URL. */
private fun String.encodePathSegment(): String = encodeURLPathPart()
