package id.nearyou.app.health

import id.nearyou.app.module
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication

class HealthRoutesTest : StringSpec({
    "GET /health/live returns 200" {
        testApplication {
            application { module() }
            val response = client.get("/health/live")
            response.status shouldBe HttpStatusCode.OK
        }
    }

    "GET /health/ready returns 200 with status field" {
        testApplication {
            application { module() }
            val response = client.get("/health/ready")
            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldBe """{"status":"ok"}"""
        }
    }
})
