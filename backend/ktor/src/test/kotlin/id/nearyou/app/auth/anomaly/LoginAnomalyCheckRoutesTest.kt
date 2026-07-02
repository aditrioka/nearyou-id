package id.nearyou.app.auth.anomaly

import com.auth0.jwk.Jwk
import com.auth0.jwk.JwkException
import com.auth0.jwk.JwkProvider
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import id.nearyou.app.auth.anomaly.LoginAnomalyTestSupport.anomalyRowCount
import id.nearyou.app.auth.anomaly.LoginAnomalyTestSupport.cleanup
import id.nearyou.app.auth.anomaly.LoginAnomalyTestSupport.hikari
import id.nearyou.app.auth.anomaly.LoginAnomalyTestSupport.seedLoginEvent
import id.nearyou.app.auth.anomaly.LoginAnomalyTestSupport.seedUser
import id.nearyou.app.auth.anomaly.LoginAnomalyTestSupport.subnetIp
import id.nearyou.app.auth.anomaly.LoginAnomalyTestSupport.tag
import id.nearyou.app.core.domain.oidc.OidcTokenVerifier
import id.nearyou.app.infra.oidc.GoogleOidcTokenVerifier
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
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
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date as UtilDate

private const val LA_AUDIENCE = "https://api-staging.nearyou.id"
private const val LA_KID = "test-la-kid"

private fun laKeypair(): Pair<RSAPublicKey, RSAPrivateKey> {
    val gen = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
    val kp = gen.generateKeyPair()
    return kp.public as RSAPublicKey to kp.private as RSAPrivateKey
}

private class LaFakeJwk(keyId: String, private val pubKey: RSAPublicKey) :
    Jwk(keyId, "RSA", "RS256", null, emptyList(), null, null, null, null) {
    override fun getPublicKey(): java.security.PublicKey = pubKey
}

private class LaStaticJwkProvider(private val mapping: Map<String, Jwk>) : JwkProvider {
    override fun get(keyId: String): Jwk = mapping[keyId] ?: throw JwkException("kid not found: $keyId")
}

private fun laSignedJwt(
    privateKey: RSAPrivateKey,
    publicKey: RSAPublicKey,
    audience: String = LA_AUDIENCE,
    kid: String = LA_KID,
): String =
    JWT.create()
        .withKeyId(kid)
        .withSubject("scheduler@nearyou-staging.iam.gserviceaccount.com")
        .withAudience(audience)
        .withIssuedAt(UtilDate.from(Instant.now()))
        .withExpiresAt(UtilDate.from(Instant.now().plus(1, ChronoUnit.HOURS)))
        .sign(Algorithm.RSA256(publicKey, privateKey))

/**
 * Route-level tests for [loginAnomalyCheckRoutes] — the endpoint contract (200 +
 * the JSON summary), the empty-sweep zero-count/no-write contract, and the
 * unauthenticated 401-with-no-sweep gate. Real-Postgres (`@Tags("database")`),
 * mirroring `RetentionCleanupRoutesTest`. The sibling-non-capture property is
 * pinned by `InternalRoutingIsolationTest` (which also mounts this worker).
 *
 * Determinism: the service is built with a FIXED near-future `nowProvider`, so
 * the window excludes sibling-suite real-now `login_events` and the empty-sweep
 * case sees zero flagged users globally.
 */
@Tags("database")
class LoginAnomalyCheckRoutesTest : StringSpec({

    val dataSource = autoClose(hikari())
    val repository = JdbcLoginAnomalyRepository(dataSource)
    val now: Instant = Instant.parse("2027-01-01T00:00:00Z")
    val inWindow: Instant = now.minus(30, ChronoUnit.MINUTES)
    val service = LoginAnomalyDetectionService(repository, nowProvider = { now })

    val (pubKey, privKey) = laKeypair()
    val verifier: OidcTokenVerifier =
        GoogleOidcTokenVerifier(
            audience = LA_AUDIENCE,
            jwkProvider = LaStaticJwkProvider(mapOf(LA_KID to LaFakeJwk(LA_KID, pubKey))),
        )

    suspend fun withRoute(
        customService: LoginAnomalyDetectionService = service,
        block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit,
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
                        loginAnomalyCheckRoutes(customService, verifier)
                    }
                }
            }
            block()
        }
    }

    "a valid OIDC bearer runs the sweep → 200 with a JSON summary and the flagged user's row" {
        val token = laSignedJwt(privKey, pubKey)
        val u = seedUser(dataSource, tag())
        repeat(6) { i -> seedLoginEvent(dataSource, u, inWindow, subnetIp(i)) }
        try {
            withRoute {
                val resp =
                    client.post("/internal/login-anomaly-check") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }
                resp.status shouldBe HttpStatusCode.OK
                val body = resp.bodyAsText()
                body shouldContain "\"flagged_users\":"
                body shouldContain "\"rows_recorded\":"
                body shouldContain "\"record_failures\":"
            }
            anomalyRowCount(dataSource, u) shouldBe 1
        } finally {
            cleanup(dataSource, listOf(u))
        }
    }

    "a request without a valid OIDC bearer is rejected 401 and the sweep does not run" {
        val u = seedUser(dataSource, tag())
        repeat(6) { i -> seedLoginEvent(dataSource, u, inWindow, subnetIp(i)) }
        try {
            withRoute {
                val resp = client.post("/internal/login-anomaly-check")
                resp.status shouldBe HttpStatusCode.Unauthorized
            }
            // The flaggable user has NO moderation_queue row → the sweep never ran.
            anomalyRowCount(dataSource, u) shouldBe 0
        } finally {
            cleanup(dataSource, listOf(u))
        }
    }

    "an empty sweep returns 200 with a zero-count summary and writes no row" {
        val token = laSignedJwt(privKey, pubKey)
        // A user UNDER the threshold (3 distinct subnets in-window) → not flagged.
        val u = seedUser(dataSource, tag())
        repeat(3) { i -> seedLoginEvent(dataSource, u, inWindow, subnetIp(i)) }
        try {
            withRoute {
                val resp =
                    client.post("/internal/login-anomaly-check") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }
                resp.status shouldBe HttpStatusCode.OK
                val body = resp.bodyAsText()
                // No sibling-suite login_events fall in the near-future window, so the
                // global sweep flags nobody → all counts zero.
                body shouldContain "\"flagged_users\":0"
                body shouldContain "\"rows_recorded\":0"
                body shouldContain "\"record_failures\":0"
            }
            anomalyRowCount(dataSource, u) shouldBe 0
        } finally {
            cleanup(dataSource, listOf(u))
        }
    }

    "a thrown sweep error returns a sanitized 500 with no PII / exception leak" {
        val token = laSignedJwt(privKey, pubKey)
        // A service whose detection read throws SQLTimeoutException → classifyHandlerError
        // maps it to "timeout"; the raw exception text must never reach the response body.
        val marker = "la-raw-timeout-detail-should-not-leak"
        val throwingService =
            LoginAnomalyDetectionService(
                object : LoginAnomalyRepository {
                    override suspend fun findUsersExceedingSubnetThreshold(
                        windowStart: java.time.Instant,
                        threshold: Int,
                    ): List<FlaggedUser> = throw java.sql.SQLTimeoutException(marker)

                    override suspend fun recordAnomaly(
                        userId: java.util.UUID,
                        notes: String,
                    ): Boolean = error("not reached — detection threw first")
                },
                nowProvider = { now },
            )
        withRoute(customService = throwingService) {
            val resp =
                client.post("/internal/login-anomaly-check") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }
            resp.status shouldBe HttpStatusCode.InternalServerError
            val body = resp.bodyAsText()
            body shouldContain "\"error\":\"timeout\""
            body shouldNotContain marker
            body shouldNotContain "SQLTimeoutException"
        }
    }
})
