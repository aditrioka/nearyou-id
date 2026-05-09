package id.nearyou.app.timeline

import id.nearyou.app.auth.AUTH_PROVIDER_USER
import id.nearyou.app.auth.UserPrincipal
import id.nearyou.app.common.InvalidCursorException
import id.nearyou.app.common.decodeCursor
import id.nearyou.app.common.encodeCursor
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
    val content: String,
    val latitude: Double,
    val longitude: Double,
    val distanceM: Double,
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
                                    "radius_m must be in [${NearbyTimelineService.RADIUS_MIN}, ${NearbyTimelineService.RADIUS_MAX}].",
                                )
                                return@get
                            }
                        // LocationOutOfBoundsException propagates to StatusPages, which renders the 400.
                        rateLimiter.postIncrement(principal, sanitizedSessionId, page.rows.size)
                        call.respond(
                            NearbyResponse(
                                posts =
                                    page.rows.map {
                                        NearbyPostDto(
                                            id = it.id.toString(),
                                            authorUserId = it.authorId.toString(),
                                            content = it.content,
                                            latitude = it.latitude,
                                            longitude = it.longitude,
                                            distanceM = it.distanceMeters,
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
