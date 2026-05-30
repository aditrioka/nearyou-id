package id.nearyou.app.admin.auth

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.pebble.Pebble
import io.ktor.server.pebble.PebbleContent
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.pebbletemplates.pebble.loader.ClasspathLoader

/**
 * Verifies the `admin-login` spec Req "Login input handling tolerates
 * Unicode and rejects malformed shapes safely" scenario "Pebble template
 * escapes the errorMessage variable".
 *
 * The production login route only ever renders the fixed
 * GENERIC_ERROR_MESSAGE, so this regression guard renders `login.peb`
 * directly with an XSS payload (the case that arises if a future change
 * ever interpolates user input into `errorMessage`) and asserts Pebble's
 * default autoescape entity-encodes it. No DB — runs in PR CI.
 */
class AdminTemplateEscapeTest : StringSpec({

    "login.peb entity-escapes a script payload in errorMessage" {
        testApplication {
            application {
                install(Pebble) {
                    loader(ClasspathLoader().apply { prefix = "templates/admin" })
                }
                routing {
                    get("/render-login") {
                        call.respond(
                            PebbleContent(
                                "login.peb",
                                model = mapOf<String, Any>("errorMessage" to "<script>alert(1)</script>"),
                            ),
                        )
                    }
                }
            }
            val body = client.get("/render-login").bodyAsText()
            body shouldContain "&lt;script&gt;alert(1)&lt;/script&gt;"
            body shouldNotContain "<script>alert(1)</script>"
        }
    }
})
