package id.nearyou.app.search

import id.nearyou.app.auth.AUTH_PROVIDER_USER
import id.nearyou.app.auth.UserPrincipal
import id.nearyou.data.repository.SearchHit
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * `GET /api/v1/search?q=<query>&offset=<n>` — Premium full-text + fuzzy search
 * over post content + usernames. See `openspec/changes/premium-search/specs/
 * premium-search/spec.md` § "Search endpoint exposes Premium full-text +
 * fuzzy search" for the full requirement set.
 *
 * **Error mapping** (drives the route's `when` block):
 *  - `400 invalid_offset` for negative, non-integer, or `> 10000` offsets;
 *    runs BEFORE any service call (cheap parse-time defense against deep-
 *    OFFSET DoS).
 *  - `400 invalid_query_length` from [SearchService.Result.InvalidQueryLength].
 *    Body shape mirrors other 400s; no further detail (don't leak which side
 *    of the bound failed — single error code).
 *  - `401` is auto-emitted by the `AUTH_PROVIDER_USER` plugin when the JWT is
 *    missing or invalid. The handler never observes that path.
 *  - `403 premium_required` from [SearchService.Result.PremiumRequired]. Body
 *    `{ "error": "premium_required", "upsell": true }` matches the canonical
 *    Premium-gate envelope at `docs/05-Implementation.md:1185`.
 *  - `429 rate_limited` from [SearchService.Result.RateLimited]. Carries a
 *    `Retry-After` header set to the seconds returned by the limiter (≥ 1
 *    per the `RateLimiter` contract).
 *  - `503 search_disabled` from [SearchService.Result.KillSwitchActive].
 *
 * **Ordering** per `SearchService.search` kdoc: parse offset → auth (route-
 * level via `authenticate`) → length guard → kill switch → Premium gate →
 * rate limit → repository.
 */
fun Application.searchRoutes(service: SearchService) {
    routing {
        authenticate(AUTH_PROVIDER_USER) {
            get("/api/v1/search") {
                val principal =
                    call.principal<UserPrincipal>() ?: run {
                        call.respond(HttpStatusCode.Unauthorized)
                        return@get
                    }

                val query = call.parameters["q"] ?: ""
                val offsetRaw = call.parameters["offset"]
                val offset =
                    when {
                        offsetRaw == null -> 0
                        else ->
                            offsetRaw.toIntOrNull()?.takeIf { it >= 0 && it <= SearchService.MAX_OFFSET }
                                ?: run {
                                    call.respondInvalidOffset()
                                    return@get
                                }
                    }

                when (
                    val result =
                        service.search(
                            viewerId = principal.userId,
                            viewerSubscriptionStatus = principal.subscriptionStatus,
                            query = query,
                            offset = offset,
                        )
                ) {
                    is SearchService.Result.Success ->
                        call.respond(
                            SearchResponse(
                                results = result.hits.map { it.toDto() },
                                nextOffset = result.nextOffset,
                            ),
                        )
                    SearchService.Result.InvalidQueryLength ->
                        call.respondText(
                            text = INVALID_QUERY_LENGTH_BODY,
                            contentType = ContentType.Application.Json,
                            status = HttpStatusCode.BadRequest,
                        )
                    SearchService.Result.KillSwitchActive ->
                        call.respondText(
                            text = SEARCH_DISABLED_BODY,
                            contentType = ContentType.Application.Json,
                            status = HttpStatusCode.ServiceUnavailable,
                        )
                    SearchService.Result.PremiumRequired ->
                        call.respondText(
                            text = PREMIUM_REQUIRED_BODY,
                            contentType = ContentType.Application.Json,
                            status = HttpStatusCode.Forbidden,
                        )
                    is SearchService.Result.RateLimited -> {
                        call.response.header(
                            HttpHeaders.RetryAfter,
                            result.retryAfterSeconds.toString(),
                        )
                        call.respondText(
                            text = RATE_LIMITED_BODY,
                            contentType = ContentType.Application.Json,
                            status = HttpStatusCode.TooManyRequests,
                        )
                    }
                }
            }
        }
    }
}

@Serializable
data class SearchResponse(
    val results: List<SearchResultDto>,
    @kotlinx.serialization.SerialName("next_offset")
    val nextOffset: Int?,
)

@Serializable
data class SearchResultDto(
    @kotlinx.serialization.SerialName("post_id")
    val postId: String,
    @kotlinx.serialization.SerialName("author_id")
    val authorId: String,
    @kotlinx.serialization.SerialName("author_username")
    val authorUsername: String,
    @kotlinx.serialization.SerialName("author_display_name")
    val authorDisplayName: String,
    val content: String,
    @kotlinx.serialization.SerialName("created_at")
    val createdAt: String,
    val rank: Float,
)

private fun SearchHit.toDto(): SearchResultDto =
    SearchResultDto(
        postId = postId.toString(),
        authorId = authorId.toString(),
        authorUsername = authorUsername,
        authorDisplayName = authorDisplayName,
        content = content,
        createdAt = createdAt.toIsoString(),
        rank = rank,
    )

private fun Instant.toIsoString(): String = this.toString()

private const val INVALID_QUERY_LENGTH_BODY = """{"error":"invalid_query_length"}"""
private const val INVALID_OFFSET_BODY = """{"error":"invalid_offset"}"""
private const val SEARCH_DISABLED_BODY = """{"error":"search_disabled"}"""
private const val PREMIUM_REQUIRED_BODY = """{"error":"premium_required","upsell":true}"""
private const val RATE_LIMITED_BODY = """{"error":"rate_limited"}"""

private suspend fun io.ktor.server.application.ApplicationCall.respondInvalidOffset() {
    respondText(
        text = INVALID_OFFSET_BODY,
        contentType = ContentType.Application.Json,
        status = HttpStatusCode.BadRequest,
    )
}
