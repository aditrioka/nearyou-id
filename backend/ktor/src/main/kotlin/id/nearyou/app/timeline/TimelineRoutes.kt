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
    val createdAt: String,
    @SerialName("liked_by_viewer") val likedByViewer: Boolean,
    @SerialName("reply_count") val replyCount: Int,
)

@Serializable
data class NearbyResponse(val posts: List<NearbyPostDto>, val nextCursor: String? = null)

@Serializable
data class FollowingPostDto(
    val id: String,
    val authorUserId: String,
    val content: String,
    val latitude: Double,
    val longitude: Double,
    val createdAt: String,
    @SerialName("liked_by_viewer") val likedByViewer: Boolean,
    @SerialName("reply_count") val replyCount: Int,
)

@Serializable
data class FollowingResponse(val posts: List<FollowingPostDto>, val nextCursor: String? = null)

fun Application.followingTimelineRoutes(service: FollowingTimelineService) {
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
                val page = service.following(viewerId = principal.userId, cursor = cursor)
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
                                    createdAt = it.createdAt.toString(),
                                    likedByViewer = it.likedByViewer,
                                    replyCount = it.replyCount,
                                )
                            },
                        nextCursor = page.nextCursor?.let(::encodeCursor),
                    ),
                )
            }
        }
    }
}

fun Application.timelineRoutes(service: NearbyTimelineService) {
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
                                    createdAt = it.createdAt.toString(),
                                    likedByViewer = it.likedByViewer,
                                    replyCount = it.replyCount,
                                )
                            },
                        nextCursor = page.nextCursor?.let(::encodeCursor),
                    ),
                )
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
