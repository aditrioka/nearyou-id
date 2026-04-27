package id.nearyou.app.common

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication

class ClientIpExtractorTest : StringSpec({

    "CF-Connecting-IP header takes precedence over XFF" {
        testApplication {
            application {
                install(ClientIpExtractorPlugin)
                routing {
                    get("/ip") { call.respondText(call.clientIp) }
                }
            }
            val response =
                client.get("/ip") {
                    header("CF-Connecting-IP", "1.2.3.4")
                    header("X-Forwarded-For", "5.6.7.8, 9.10.11.12")
                }
            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldBe "1.2.3.4"
        }
    }

    "Falls back to first XFF entry when CF-Connecting-IP absent" {
        testApplication {
            application {
                install(ClientIpExtractorPlugin)
                routing { get("/ip") { call.respondText(call.clientIp) } }
            }
            val response =
                client.get("/ip") {
                    header("X-Forwarded-For", "5.6.7.8, 9.10.11.12")
                }
            response.bodyAsText() shouldBe "5.6.7.8"
        }
    }

    "Falls back to remoteHost when CF and XFF absent" {
        testApplication {
            application {
                install(ClientIpExtractorPlugin)
                routing { get("/ip") { call.respondText(call.clientIp) } }
            }
            val response = client.get("/ip")
            // Test client uses 'localhost' or '127.0.0.1'; assert it's not the unknown sentinel.
            response.bodyAsText() shouldNotBe UNKNOWN_CLIENT_IP
        }
    }

    "Trims whitespace from CF-Connecting-IP" {
        testApplication {
            application {
                install(ClientIpExtractorPlugin)
                routing { get("/ip") { call.respondText(call.clientIp) } }
            }
            val response =
                client.get("/ip") {
                    header("CF-Connecting-IP", "  1.2.3.4  ")
                }
            response.bodyAsText() shouldBe "1.2.3.4"
        }
    }

    "Trims whitespace from XFF first entry" {
        testApplication {
            application {
                install(ClientIpExtractorPlugin)
                routing { get("/ip") { call.respondText(call.clientIp) } }
            }
            val response =
                client.get("/ip") {
                    header("X-Forwarded-For", "  5.6.7.8  , 9.10.11.12")
                }
            response.bodyAsText() shouldBe "5.6.7.8"
        }
    }

    "Empty CF-Connecting-IP header falls through to XFF" {
        testApplication {
            application {
                install(ClientIpExtractorPlugin)
                routing { get("/ip") { call.respondText(call.clientIp) } }
            }
            val response =
                client.get("/ip") {
                    header("CF-Connecting-IP", "   ")
                    header("X-Forwarded-For", "5.6.7.8")
                }
            response.bodyAsText() shouldBe "5.6.7.8"
        }
    }

    "Plugin is idempotent: attribute set once even if accessor invoked multiple times" {
        testApplication {
            application {
                install(ClientIpExtractorPlugin)
                routing {
                    get("/ip") {
                        // Access twice — second call must not overwrite.
                        val first = call.clientIp
                        val second = call.clientIp
                        call.respondText("$first|$second")
                    }
                }
            }
            val response =
                client.get("/ip") {
                    header("CF-Connecting-IP", "1.2.3.4")
                }
            response.bodyAsText() shouldBe "1.2.3.4|1.2.3.4"
        }
    }
})

// kotest-style infix not used here — kept consistent with the StringSpec pattern.
private infix fun String.shouldNotBe(other: String) {
    if (this == other) {
        throw AssertionError("expected '$this' != '$other'")
    }
}
