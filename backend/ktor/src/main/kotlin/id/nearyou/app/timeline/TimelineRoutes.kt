package id.nearyou.app.timeline

import id.nearyou.app.auth.AUTH_PROVIDER_USER
import id.nearyou.app.auth.UserPrincipal
import id.nearyou.app.common.InvalidCursorException
import id.nearyou.app.common.decodeCursor
import id.nearyou.app.common.encodeCursor
import id.nearyou.app.post.LocationOutOfBoundsException
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class NearbyPostDto(
    val id: String,
    val authorUserId: String,
    // Author display identity — bare camelCase wire (NO @SerialName), per the shipped
    // identity-field precedent (authorUserId here; username/displayName in
    // UserProfileRoutes.kt). Sourced from visible_users; NOT NULL since V2.
    val authorUsername: String,
    val authorDisplayName: String,
    val content: String,
    val latitude: Double,
    val longitude: Double,
    // Nullable as of the hide-distance capability: omitted (via the app-wide explicitNulls=false)
    // when the symmetric hide rule suppresses the distance for this (author, viewer) pair; raw
    // meters otherwise.
    val distanceM: Double?,
    @SerialName("city_name") val cityName: String,
    val createdAt: String,
    @SerialName("liked_by_viewer") val likedByViewer: Boolean,
    @SerialName("reply_count") val replyCount: Int,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class NearbyResponse(
    val posts: List<NearbyPostDto>,
    val nextCursor: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val upsell: Upsell? = null,
)

@Serializable
data class FollowingPostDto(
    val id: String,
    val authorUserId: String,
    // Bare camelCase wire — same identity-field convention as NearbyPostDto.
    val authorUsername: String,
    val authorDisplayName: String,
    val content: String,
    val latitude: Double,
    val longitude: Double,
    @SerialName("city_name") val cityName: String,
    val createdAt: String,
    @SerialName("liked_by_viewer") val likedByViewer: Boolean,
    @SerialName("reply_count") val replyCount: Int,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class FollowingResponse(
    val posts: List<FollowingPostDto>,
    val nextCursor: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val upsell: Upsell? = null,
)

@Serializable
data class GlobalPostDto(
    val id: String,
    val authorUserId: String,
    // Bare camelCase wire — same identity-field convention as NearbyPostDto.
    val authorUsername: String,
    val authorDisplayName: String,
    val content: String,
    val latitude: Double,
    val longitude: Double,
    @SerialName("city_name") val cityName: String,
    val createdAt: String,
    @SerialName("liked_by_viewer") val likedByViewer: Boolean,
    @SerialName("reply_count") val replyCount: Int,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class GlobalResponse(
    val posts: List<GlobalPostDto>,
    val nextCursor: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val upsell: Upsell? = null,
)

private const val SESSION_ID_HEADER = "X-Session-Id"

fun Application.followingTimelineRoutes(
    service: FollowingTimelineService,
    rateLimiter: TimelineReadRateLimiter,
) {
    routing {
        authenticate(AUTH_PROVIDER_USER) {
            get("/api/v1/timeline/following") {
                val principal =
                    call.principal<UserPrincipal>() ?: run {
                        call.respond(HttpStatusCode.Unauthorized)
                        return@get
                    }
                val cursor =
                    try {
                        call.parameters["cursor"]?.let { decodeCursor(it) }
                    } catch (_: InvalidCursorException) {
                        call.respondError(HttpStatusCode.BadRequest, "invalid_cursor", "Cursor is malformed.")
                        return@get
                    }
                // Per `timeline-read-rate-limit` § "Limiter ordering": rate-limit pre-check
                // runs AFTER auth + cursor parsing but BEFORE the timeline DB query. On
                // hard-cap, return empty + upsell.hard=true and short-circuit (no DB query).
                val sanitizedSessionId =
                    TimelineReadRateLimiter.sanitizeSessionId(call.request.headers[SESSION_ID_HEADER])
                when (val outcome = rateLimiter.preCheck(principal, sanitizedSessionId)) {
                    is TimelineReadRateLimiter.PreCheckOutcome.HardCapped -> {
                        call.respond(
                            FollowingResponse(
                                posts = emptyList(),
                                nextCursor = null,
                                upsell = Upsell(hard = true),
                            ),
                        )
                        return@get
                    }
                    is TimelineReadRateLimiter.PreCheckOutcome.Admit -> {
                        val page = service.following(viewerId = principal.userId, cursor = cursor)
                        rateLimiter.postIncrement(principal, sanitizedSessionId, page.rows.size)
                        call.respond(
                            FollowingResponse(
                                posts =
                                    page.rows.map {
                                        FollowingPostDto(
                                            id = it.id.toString(),
                                            authorUserId = it.authorId.toString(),
                                            authorUsername = it.authorUsername,
                                            authorDisplayName = it.authorDisplayName,
                                            content = it.content,
                                            latitude = it.latitude,
                                            longitude = it.longitude,
                                            cityName = it.cityName.orEmpty(),
                                            createdAt = it.createdAt.toString(),
                                            likedByViewer = it.likedByViewer,
                                            replyCount = it.replyCount,
                                        )
                                    },
                                nextCursor = page.nextCursor?.let(::encodeCursor),
                                upsell = if (outcome.softCapReached) Upsell(soft = true) else null,
                            ),
                        )
                    }
                }
            }
        }
    }
}

fun Application.timelineRoutes(
    service: NearbyTimelineService,
    rateLimiter: TimelineReadRateLimiter,
) {
    routing {
        authenticate(AUTH_PROVIDER_USER) {
            get("/api/v1/timeline/nearby") {
                val principal =
                    call.principal<UserPrincipal>() ?: run {
                        call.respond(HttpStatusCode.Unauthorized)
                        return@get
                    }
                val lat = call.parameters["lat"]?.toDoubleOrNull()
                val lng = call.parameters["lng"]?.toDoubleOrNull()
                val radius = call.parameters["radius_m"]?.toIntOrNull()
                if (lat == null || lng == null || radius == null) {
                    call.respondError(
                        HttpStatusCode.BadRequest,
                        "invalid_request",
                        "lat, lng, and radius_m are required and must be numeric.",
                    )
                    return@get
                }
                val cursor =
                    try {
                        call.parameters["cursor"]?.let { decodeCursor(it) }
                    } catch (_: InvalidCursorException) {
                        call.respondError(HttpStatusCode.BadRequest, "invalid_cursor", "Cursor is malformed.")
                        return@get
                    }
                // Envelope + radius validation BEFORE the limiter pre-check — the
                // timeline-read-rate-limit spec's ordering puts parameter validation at
                // step 2, ahead of the step-5/6 pre-checks: a 400 must not burn a Free
                // user's rolling/session quota. (The service re-validates as defense in
                // depth; LocationOutOfBoundsException renders via StatusPages either way.)
                if (lat !in NearbyTimelineService.LAT_MIN..NearbyTimelineService.LAT_MAX ||
                    lng !in NearbyTimelineService.LNG_MIN..NearbyTimelineService.LNG_MAX
                ) {
                    throw LocationOutOfBoundsException(lat, lng)
                }
                if (radius !in NearbyTimelineService.ALLOWED_RADII_M) {
                    call.respondError(
                        HttpStatusCode.BadRequest,
                        "radius_out_of_bounds",
                        "radius_m must be one of ${NearbyTimelineService.ALLOWED_RADII_M}.",
                    )
                    return@get
                }
                // Premium radius gate (mobile-nearby-radius-slider): a Free viewer may use only
                // FREE_RADIUS_M (20 km); every other allowed-set position is Premium-only. Tier is
                // read from the auth principal — NO users SELECT (timeline-read-rate-limit invariant).
                // Placed AFTER set-membership validation and BEFORE the limiter pre-check (mirroring
                // the radius/location 400s above) so a 403 never burns a Free user's read quota.
                if (radius != NearbyTimelineService.FREE_RADIUS_M &&
                    !isPremiumStatus(principal.subscriptionStatus)
                ) {
                    call.respondError(
                        HttpStatusCode.Forbidden,
                        "radius_premium_only",
                        "radius_m other than ${NearbyTimelineService.FREE_RADIUS_M} requires Premium.",
                    )
                    return@get
                }
                // Pre-check runs AFTER cursor parsing but BEFORE the (expensive) PostGIS
                // ST_DWithin query. The rolling pre-check is consulted before the session
                // pre-check; on rolling hard-cap the response is shaped without the DB
                // round-trip.
                val sanitizedSessionId =
                    TimelineReadRateLimiter.sanitizeSessionId(call.request.headers[SESSION_ID_HEADER])
                when (val outcome = rateLimiter.preCheck(principal, sanitizedSessionId)) {
                    is TimelineReadRateLimiter.PreCheckOutcome.HardCapped -> {
                        call.respond(
                            NearbyResponse(
                                posts = emptyList(),
                                nextCursor = null,
                                upsell = Upsell(hard = true),
                            ),
                        )
                        return@get
                    }
                    is TimelineReadRateLimiter.PreCheckOutcome.Admit -> {
                        val page =
                            try {
                                service.nearby(
                                    viewerId = principal.userId,
                                    viewerLat = lat,
                                    viewerLng = lng,
                                    radiusMeters = radius,
                                    cursor = cursor,
                                )
                            } catch (_: RadiusOutOfBoundsException) {
                                call.respondError(
                                    HttpStatusCode.BadRequest,
                                    "radius_out_of_bounds",
                                    "radius_m must be one of ${NearbyTimelineService.ALLOWED_RADII_M}.",
                                )
                                return@get
                            }
                        // LocationOutOfBoundsException propagates to StatusPages, which renders the 400.
                        rateLimiter.postIncrement(principal, sanitizedSessionId, page.rows.size)
                        // hide-distance: the viewer's effective preference, resolved ONCE from the auth
                        // principal (no per-request users SELECT — preserves the timeline-read-rate-limit
                        // "zero users SELECTs in the handler" invariant). Symmetric: combined per-row with
                        // the author preference by effectiveDistanceMeters.
                        val viewerHidesDistance =
                            principal.hideDistanceOptIn && isPremiumStatus(principal.subscriptionStatus)
                        call.respond(
                            NearbyResponse(
                                posts =
                                    page.rows.map {
                                        NearbyPostDto(
                                            id = it.id.toString(),
                                            authorUserId = it.authorId.toString(),
                                            authorUsername = it.authorUsername,
                                            authorDisplayName = it.authorDisplayName,
                                            content = it.content,
                                            latitude = it.latitude,
                                            longitude = it.longitude,
                                            // hide-distance: omit the number when the author OR
                                            // the viewer is effectively hiding (symmetric rule).
                                            distanceM =
                                                effectiveDistanceMeters(
                                                    rawMeters = it.distanceMeters,
                                                    authorHidesDistance = it.authorHidesDistance,
                                                    viewerHidesDistance = viewerHidesDistance,
                                                ),
                                            cityName = it.cityName.orEmpty(),
                                            createdAt = it.createdAt.toString(),
                                            likedByViewer = it.likedByViewer,
                                            replyCount = it.replyCount,
                                        )
                                    },
                                nextCursor = page.nextCursor?.let(::encodeCursor),
                                upsell = if (outcome.softCapReached) Upsell(soft = true) else null,
                            ),
                        )
                    }
                }
            }
        }
    }
}

fun Application.globalTimelineRoutes(
    service: GlobalTimelineService,
    rateLimiter: TimelineReadRateLimiter,
) {
    routing {
        authenticate(AUTH_PROVIDER_USER) {
            get("/api/v1/timeline/global") {
                val principal =
                    call.principal<UserPrincipal>() ?: run {
                        call.respond(HttpStatusCode.Unauthorized)
                        return@get
                    }
                val cursor =
                    try {
                        call.parameters["cursor"]?.let { decodeCursor(it) }
                    } catch (_: InvalidCursorException) {
                        call.respondError(HttpStatusCode.BadRequest, "invalid_cursor", "Cursor is malformed.")
                        return@get
                    }
                val sanitizedSessionId =
                    TimelineReadRateLimiter.sanitizeSessionId(call.request.headers[SESSION_ID_HEADER])
                when (val outcome = rateLimiter.preCheck(principal, sanitizedSessionId)) {
                    is TimelineReadRateLimiter.PreCheckOutcome.HardCapped -> {
                        call.respond(
                            GlobalResponse(
                                posts = emptyList(),
                                nextCursor = null,
                                upsell = Upsell(hard = true),
                            ),
                        )
                        return@get
                    }
                    is TimelineReadRateLimiter.PreCheckOutcome.Admit -> {
                        val page = service.global(viewerId = principal.userId, cursor = cursor)
                        rateLimiter.postIncrement(principal, sanitizedSessionId, page.rows.size)
                        call.respond(
                            GlobalResponse(
                                posts =
                                    page.rows.map {
                                        GlobalPostDto(
                                            id = it.id.toString(),
                                            authorUserId = it.authorId.toString(),
                                            authorUsername = it.authorUsername,
                                            authorDisplayName = it.authorDisplayName,
                                            content = it.content,
                                            latitude = it.latitude,
                                            longitude = it.longitude,
                                            cityName = it.cityName.orEmpty(),
                                            createdAt = it.createdAt.toString(),
                                            likedByViewer = it.likedByViewer,
                                            replyCount = it.replyCount,
                                        )
                                    },
                                nextCursor = page.nextCursor?.let(::encodeCursor),
                                upsell = if (outcome.softCapReached) Upsell(soft = true) else null,
                            ),
                        )
                    }
                }
            }
        }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.respondError(
    status: HttpStatusCode,
    code: String,
    message: String,
) {
    respond(
        status,
        mapOf("error" to mapOf("code" to code, "message" to message)),
    )
}

/**
 * Premium-tier predicate shared (within this file) by the Nearby radius gate and the hide-distance
 * projection. Matches the canonical premium states — `premium_active` plus the 7-day `premium_billing_retry`
 * billing-retry grace — used by `SearchService.PREMIUM_STATES` and the timeline read limiter. Kept local
 * to avoid a cross-module refactor of the three existing copies; the tier is always read from the auth
 * principal, never a `users` SELECT (timeline-read-rate-limit invariant).
 */
private fun isPremiumStatus(subscriptionStatus: String?): Boolean =
    subscriptionStatus == "premium_active" || subscriptionStatus == "premium_billing_retry"
