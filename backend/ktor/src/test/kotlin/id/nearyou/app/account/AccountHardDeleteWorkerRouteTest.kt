package id.nearyou.app.account

import id.nearyou.app.core.domain.oidc.OidcTokenVerifier
import id.nearyou.app.infra.oidc.GoogleOidcTokenVerifier
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * OIDC-auth tests for `POST /internal/account-hard-delete-worker` (capability
 * `account-hard-delete-worker`, scenario "Unauthenticated internal call is rejected"; issue #347).
 * Mirrors [DataExportWorkerRouteTest]: the worker runs over a never-touched DataSource, so each
 * asserted 401 error code proves the gate rejected BEFORE any deletion could run (a dispatch would
 * surface as the route's sanitized 500 instead).
 */
class AccountHardDeleteWorkerRouteTest : StringSpec({

    val (pubKey, privKey) = rsaKeypair()
    val verifier: OidcTokenVerifier =
        GoogleOidcTokenVerifier(
            audience = TEST_AUDIENCE,
            jwkProvider = StaticJwkProvider(mapOf(TEST_KID to FakeJwk(TEST_KID, pubKey))),
        )

    suspend fun withRoute(block: suspend ApplicationTestBuilder.() -> Unit) {
        testApplication {
            application {
                install(ContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                            explicitNulls = false
                        },
                    )
                }
                routing {
                    route("/internal") {
                        accountHardDeleteWorkerRoute(AccountHardDeleteWorker(UnusedDataSource), verifier)
                    }
                }
            }
            block()
        }
    }

    suspend fun ApplicationTestBuilder.postWith(authorization: String?) =
        client.post("/internal/account-hard-delete-worker") {
            authorization?.let { header(HttpHeaders.Authorization, it) }
        }

    "401 on a missing Authorization header — no deletion runs" {
        withRoute {
            val resp = postWith(null)
            resp.status shouldBe HttpStatusCode.Unauthorized
            resp.bodyAsText() shouldContain "missing_authorization"
        }
    }

    "401 on a non-Bearer scheme" {
        withRoute {
            val resp = postWith("Basic dXNlcjpwYXNz")
            resp.status shouldBe HttpStatusCode.Unauthorized
            resp.bodyAsText() shouldContain "invalid_scheme"
        }
    }

    "401 on a malformed JWT" {
        withRoute {
            val resp = postWith("Bearer not.a.jwt")
            resp.status shouldBe HttpStatusCode.Unauthorized
            resp.bodyAsText() shouldContain "invalid_token"
        }
    }

    "401 on a bad-signature token" {
        val (pubB, privB) = rsaKeypair()
        withRoute {
            val resp = postWith("Bearer ${signedJwt(privB, pubB)}")
            resp.status shouldBe HttpStatusCode.Unauthorized
            resp.bodyAsText() shouldContain "invalid_token"
        }
    }

    "401 on an audience mismatch" {
        withRoute {
            val resp = postWith("Bearer ${signedJwt(privKey, pubKey, audience = "https://example.com/other")}")
            resp.status shouldBe HttpStatusCode.Unauthorized
            resp.bodyAsText() shouldContain "audience_mismatch"
        }
    }

    "401 on an expired token" {
        withRoute {
            val expired = signedJwt(privKey, pubKey, expiresAt = Instant.now().minus(5, ChronoUnit.MINUTES))
            val resp = postWith("Bearer $expired")
            resp.status shouldBe HttpStatusCode.Unauthorized
            resp.bodyAsText() shouldContain "expired_token"
        }
    }

    "a valid OIDC token is ADMITTED past the gate (worker dispatched)" {
        // Only an admitted request reaches execute(); its unreachable DataSource makes the route's
        // catch-all answer a sanitized 500 — exactly 500 (not 401/403) proves the gate let it through.
        // The DB-backed happy path is AccountHardDeleteWorkerTest.
        withRoute {
            postWith("Bearer ${signedJwt(privKey, pubKey)}").status shouldBe HttpStatusCode.InternalServerError
        }
    }
})
