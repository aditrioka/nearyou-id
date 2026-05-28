package id.nearyou.app.admin.routes

import id.nearyou.app.admin.auth.AdminAuthProvider
import id.nearyou.app.admin.auth.HashUtil
import io.ktor.server.application.call
import io.ktor.server.pebble.PebbleContent
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * `GET /admin/` — the admin index page.
 *
 * Wired INSIDE the `authenticate("admin") { ... }` block so the session
 * middleware (per `admin-login-argon2-totp` spec Req "Session middleware
 * validates the cookie on every authenticated request") guarantees a
 * valid session before the handler runs.
 *
 * The handler reads the session cookie to derive the plaintext CSRF token
 * (per [HashUtil.deriveCsrfFromSessionToken] — see design.md D6) and
 * passes it into the Pebble model so the layout renders the CSRF meta
 * tag + the HTMX configRequest JS hook per spec Req "Authenticated layout
 * includes CSRF meta tag and HTMX configRequest hook".
 */
fun Route.adminIndex() {
    get("/") {
        val sessionCookie = call.request.cookies[AdminAuthProvider.COOKIE_NAME]
        val model =
            buildMap<String, Any> {
                sessionCookie
                    ?.takeIf { it.isNotBlank() }
                    ?.let { HashUtil.deriveCsrfFromSessionToken(it) }
                    ?.let { put("csrfToken", it) }
            }
        call.respond(PebbleContent("index.peb", model = model))
    }
}
