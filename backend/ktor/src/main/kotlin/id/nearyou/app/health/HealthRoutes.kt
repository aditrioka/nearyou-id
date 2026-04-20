package id.nearyou.app.health

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

fun Application.healthRoutes() {
    routing {
        get("/health/live") {
            call.respond(HttpStatusCode.OK)
        }
        get("/health/ready") {
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }
    }
}
