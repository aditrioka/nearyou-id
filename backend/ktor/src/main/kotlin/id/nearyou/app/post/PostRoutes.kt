package id.nearyou.app.post

import id.nearyou.app.auth.AUTH_PROVIDER_USER
import id.nearyou.app.auth.UserPrincipal
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

@Serializable
data class CreatePostRequestDto(
    val content: String? = null,
    val latitude: Double,
    val longitude: Double,
)

fun Application.postRoutes(service: CreatePostService) {
    routing {
        authenticate(AUTH_PROVIDER_USER) {
            post("/api/v1/posts") {
                val principal =
                    call.principal<UserPrincipal>() ?: run {
                        call.respond(HttpStatusCode.Unauthorized)
                        return@post
                    }
                val req =
                    try {
                        call.receive<CreatePostRequestDto>()
                    } catch (_: Exception) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf(
                                "error" to
                                    mapOf(
                                        "code" to "invalid_json",
                                        "message" to "Malformed request body.",
                                    ),
                            ),
                        )
                        return@post
                    }
                val created =
                    service.create(
                        authorId = principal.userId,
                        rawContent = req.content,
                        latitude = req.latitude,
                        longitude = req.longitude,
                    )
                // Manual JSON build: the app-wide ContentNegotiation has `explicitNulls = false`
                // (so optional nulls stay out of wire format), but the post-creation spec
                // requires `distance_m` to appear on the 201 body with value null.
                val body =
                    buildJsonObject {
                        put("id", JsonPrimitive(created.id.toString()))
                        put("content", JsonPrimitive(created.content))
                        put("latitude", JsonPrimitive(created.latitude))
                        put("longitude", JsonPrimitive(created.longitude))
                        put("distance_m", JsonNull)
                        put("created_at", JsonPrimitive(created.createdAt.toString()))
                    }
                call.respondText(
                    text = body.toString(),
                    contentType = ContentType.Application.Json,
                    status = HttpStatusCode.Created,
                )
            }
        }
    }
}
