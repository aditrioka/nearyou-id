package id.nearyou.app.account

import id.nearyou.app.core.domain.oidc.OidcTokenVerifier
import id.nearyou.app.infra.oidc.GoogleOidcTokenVerifier
import id.nearyou.app.internal.TEST_OIDC_PRINCIPAL
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
 * OIDC-auth tests for `POST /internal/data-export-worker` (capability `account-data-export`,
 * spec scenario "Worker rejects an unauthenticated invocation" / task 8.4). Mirrors the
 * [id.nearyou.app.admin.UnbanWorkerRouteTest] gate cases. No DB tag: a recording fake
 * [DataExportWorker] subtype is impossible (final class), so we use a worker over a never-touched
 * DataSource — each rejected case asserts the specific error code in the 401/403 body, which is
 * what proves the gate rejected BEFORE any dispatch (the worker's DataSource is unreachable, so a
 * dispatch would surface as a 500, not the asserted 401/403).
 */
class DataExportWorkerRouteTest : StringSpec({

    val (pubKey, privKey) = rsaKeypair()
    val defaultVerifier: OidcTokenVerifier =
        GoogleOidcTokenVerifier(
            audience = TEST_AUDIENCE,
            allowedPrincipals = setOf(TEST_OIDC_PRINCIPAL),
            jwkProvider = StaticJwkProvider(mapOf(TEST_KID to FakeJwk(TEST_KID, pubKey))),
        )

    // A worker whose DataSource is never used on the 401/403 path (the OIDC gate rejects before
    // dispatch). On the admitted path, execute() runs against an unreachable DataSource — but the
    // route catches the resulting exception and returns a sanitized 500, which still proves the
    // gate ADMITTED the request (auth passed).
    fun buildWorker(): DataExportWorker =
        DataExportWorker(
            dataSource = UnusedDataSource,
            requests = DataExportRequestRepository(UnusedDataSource),
            gather = DataExportGatherRepository(UnusedDataSource, PeerIdHasher.fromSecret(null)),
            archiveService = DataExportArchiveService(),
            objectStore = id.nearyou.app.infra.r2.NoOpObjectStore,
            emailSender = id.nearyou.app.infra.resend.NoOpEmailSender,
            notificationEmitter =
                object : id.nearyou.app.notifications.NotificationEmitter {
                    override fun emit(
                        conn: java.sql.Connection,
                        recipientId: java.util.UUID,
                        actorUserId: java.util.UUID?,
                        type: id.nearyou.data.repository.NotificationType,
                        targetType: String?,
                        targetId: java.util.UUID?,
                        bodyData: kotlinx.serialization.json.JsonObject,
                    ): java.util.UUID? = null
                },
        )

    suspend fun withRoute(
        verifier: OidcTokenVerifier = defaultVerifier,
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) {
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
                        dataExportWorkerRoute(buildWorker(), verifier)
                    }
                }
            }
            block()
        }
    }

    "8.4 401 on a missing Authorization header — no processing" {
        withRoute {
            val resp = client.post("/internal/data-export-worker")
            resp.status shouldBe HttpStatusCode.Unauthorized
            resp.bodyAsText() shouldContain "missing_authorization"
        }
    }

    "8.4 401 on a non-Bearer scheme" {
        withRoute {
            val resp =
                client.post("/internal/data-export-worker") {
                    header(HttpHeaders.Authorization, "Basic dXNlcjpwYXNz")
                }
            resp.status shouldBe HttpStatusCode.Unauthorized
            resp.bodyAsText() shouldContain "invalid_scheme"
        }
    }

    "8.4 401 on a malformed JWT" {
        withRoute {
            val resp =
                client.post("/internal/data-export-worker") {
                    header(HttpHeaders.Authorization, "Bearer not.a.jwt")
                }
            resp.status shouldBe HttpStatusCode.Unauthorized
            resp.bodyAsText() shouldContain "invalid_token"
        }
    }

    "8.4 401 on a bad-signature token" {
        val (pubB, privB) = rsaKeypair()
        val token = signedJwt(privB, pubB)
        withRoute {
            val resp =
                client.post("/internal/data-export-worker") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }
            resp.status shouldBe HttpStatusCode.Unauthorized
            resp.bodyAsText() shouldContain "invalid_token"
        }
    }

    "8.4 401 on an audience mismatch" {
        val token = signedJwt(privKey, pubKey, audience = "https://example.com/other")
        withRoute {
            val resp =
                client.post("/internal/data-export-worker") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }
            resp.status shouldBe HttpStatusCode.Unauthorized
            resp.bodyAsText() shouldContain "audience_mismatch"
        }
    }

    "8.4 401 on an expired token" {
        val token = signedJwt(privKey, pubKey, expiresAt = Instant.now().minus(5, ChronoUnit.MINUTES))
        withRoute {
            val resp =
                client.post("/internal/data-export-worker") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }
            resp.status shouldBe HttpStatusCode.Unauthorized
            resp.bodyAsText() shouldContain "expired_token"
        }
    }

    "8.4 a valid OIDC token is ADMITTED past the gate (worker dispatched)" {
        // With a valid token the gate admits the request and execute() runs. The fake worker's
        // DataSource is unreachable, so the route catches the dispatch failure and maps it to a
        // sanitized 500 (DataExportWorkerRoute's catch-all). Asserting the status is EXACTLY 500
        // (not merely "not 401/403") is the stronger proof the gate ADMITTED and dispatch ran:
        // a 401/403 would mean the gate rejected; only an admitted request reaching execute()
        // produces this sanitized 500. The DB-backed admit happy-path (200 + a real ready export)
        // is covered by DataExportWorkerTest 8.5.
        val token = signedJwt(privKey, pubKey)
        withRoute {
            val resp =
                client.post("/internal/data-export-worker") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }
            resp.status shouldBe HttpStatusCode.InternalServerError
        }
    }
})
