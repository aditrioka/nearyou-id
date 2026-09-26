package id.nearyou.app.common

import id.nearyou.app.infra.sentryjvm.testing.SentryEventRecorder
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication

/**
 * backend-error-reporting § "Unhandled route exception is reported" + "A request error carries its
 * call_id tag" — end to end through the EXACT production StatusPages mapping and the production
 * CallId/CallLogging MDC key, with the Sentry transport replaced by the vendor-free recorder.
 */
class SentryErrorCaptureTest : StringSpec({
    "a route exception returns the fixed 500 AND is reported with its call_id tag" {
        SentryEventRecorder.start(env = "test").use { recorder ->
            testApplication {
                application {
                    install(ContentNegotiation) { json(AppJson) }
                    install(CallId) {
                        retrieveFromHeader(HttpHeaders.XRequestId)
                        replyToHeader(HttpHeaders.XRequestId)
                    }
                    install(CallLogging) { callIdMdc("call_id") }
                    installAppStatusPages()
                    routing { get("/probe/boom") { error("synthetic server fault") } }
                }
                val response = client.get("/probe/boom?lat=-6.2088&lng=106.8456") { header(HttpHeaders.XRequestId, "abc-123") }
                response.status shouldBe HttpStatusCode.InternalServerError
                response.bodyAsText() shouldContain "internal_error"
            }
            val event = recorder.events.single()
            event.message shouldBe "event=unhandled_exception method=GET path=/probe/boom"
            event.exceptionTypes shouldContain "IllegalStateException"
            event.exceptionValues shouldContain "synthetic server fault"
            event.tags["call_id"] shouldBe "abc-123"
            event.hasUser shouldBe false
        }
    }
})
