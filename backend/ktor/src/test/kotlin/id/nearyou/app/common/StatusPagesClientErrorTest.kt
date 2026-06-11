package id.nearyou.app.common

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.Serializable

@Serializable
private data class ProbeDto(val value: Int)

/**
 * Regression guard for the StatusPages catch-all shadowing Ktor's built-in client-error
 * mapping (caught LIVE in the 2026-06-11 device verification): with only an
 * `exception<Throwable>` handler registered, a malformed `Authorization` header
 * (`parseAuthorizationHeader` throws [BadRequestException]) and a malformed JSON body
 * (`receive` wraps the deserialization failure in [BadRequestException]) were both turned
 * into alarm-grade 500s. The production mapping ([installAppStatusPages]) must answer
 * client mistakes with 4xx and reserve 500 for genuine server faults.
 *
 * Mounts the EXACT production configuration (the extracted installer), not a copy.
 */
class StatusPagesClientErrorTest : StringSpec({

    fun io.ktor.server.testing.ApplicationTestBuilder.mountProbes() {
        application {
            install(ContentNegotiation) { json(AppJson) }
            installAppStatusPages()
            routing {
                get("/probe/bad-request") { throw BadRequestException("Invalid auth header") }
                get("/probe/not-found") { throw NotFoundException() }
                get("/probe/boom") { error("synthetic server fault") }
                post("/probe/receive") {
                    call.respond(mapOf("value" to call.receive<ProbeDto>().value))
                }
            }
        }
    }

    "BadRequestException maps to 400, not the Throwable 500 catch-all" {
        testApplication {
            mountProbes()
            val response = client.get("/probe/bad-request")
            response.status shouldBe HttpStatusCode.BadRequest
            response.bodyAsText() shouldContain "invalid_request"
        }
    }

    "malformed JSON body through receive() maps to 400" {
        testApplication {
            mountProbes()
            val response =
                client.post("/probe/receive") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"value": not-json""")
                }
            response.status shouldBe HttpStatusCode.BadRequest
        }
    }

    "NotFoundException maps to 404" {
        testApplication {
            mountProbes()
            client.get("/probe/not-found").status shouldBe HttpStatusCode.NotFound
        }
    }

    "genuine server faults still map to the fixed 500 envelope without leaking the cause" {
        testApplication {
            mountProbes()
            val response = client.get("/probe/boom")
            response.status shouldBe HttpStatusCode.InternalServerError
            val body = response.bodyAsText()
            body shouldContain "internal_error"
            // docs/11 §3.3 — the cause message must never reach the client.
            body shouldNotContain "synthetic server fault"
        }
    }
})
