package id.nearyou.app.health

import id.nearyou.app.auth.jwt.TestKeys
import id.nearyou.app.module
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication

// EnvVarSecretResolver falls back to System.getProperty; stash 32-byte base64
// blobs here so module() can boot without INVITE_CODE_SECRET / JITTER_SECRET in the OS env.
private val seedInviteSecret: Unit =
    run {
        if (System.getenv("INVITE_CODE_SECRET").isNullOrBlank()) {
            System.setProperty("INVITE_CODE_SECRET", "dGVzdC1pbnZpdGUtY29kZS1zZWNyZXQtMzJieXRlcw==")
        }
        if (System.getenv("JITTER_SECRET").isNullOrBlank()) {
            // 32 random bytes, base64-encoded. Content is arbitrary for test purposes.
            // 32 bytes of ASCII, base64-encoded. Arbitrary content; only the length is enforced.
            System.setProperty("JITTER_SECRET", "MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MCE=")
        }
    }

class HealthRoutesTest : StringSpec({
    seedInviteSecret

    fun config(dbUrl: String) =
        MapApplicationConfig(
            "ktor.environment" to "test",
            "db.url" to dbUrl,
            "db.user" to "x",
            "db.password" to "x",
            "db.maxPoolSize" to "1",
            "auth.rsaPrivateKey" to TestKeys.freshEncodedPemPrivateKey(),
            "auth.supabaseJwtSecret" to "test-supabase-secret-32-bytes-long!!",
        )

    "GET /health/live always returns 200" {
        testApplication {
            environment { config = config("jdbc:postgresql://nowhere:1/nodb") }
            application { module() }
            val response = client.get("/health/live")
            response.status shouldBe HttpStatusCode.OK
        }
    }

    "GET /health/ready returns 503 when Postgres unreachable" {
        testApplication {
            environment { config = config("jdbc:postgresql://nowhere:1/nodb") }
            application { module() }
            val response = client.get("/health/ready")
            response.status shouldBe HttpStatusCode.ServiceUnavailable
            response.bodyAsText() shouldContain "degraded"
        }
    }
})
