package id.nearyou.app.admin.routes

import io.ktor.server.application.call
import io.ktor.server.pebble.PebbleContent
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.adminIndex() {
    get("/") {
        call.respond(PebbleContent("index.peb", emptyMap()))
    }
}
