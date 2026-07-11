package id.nearyou.app.image

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.auth0.jwk.Jwk
import com.auth0.jwk.JwkException
import com.auth0.jwk.JwkProvider
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import id.nearyou.app.admin.retention.RetentionCleanupTestSupport.cleanup
import id.nearyou.app.admin.retention.RetentionCleanupTestSupport.hikari
import id.nearyou.app.admin.retention.RetentionCleanupTestSupport.seedUser
import id.nearyou.app.admin.retention.RetentionCleanupTestSupport.tag
import id.nearyou.app.core.domain.oidc.OidcTokenVerifier
import id.nearyou.app.image.OrphanImageCleanupTestSupport.imageUploadExists
import id.nearyou.app.image.OrphanImageCleanupTestSupport.seedImageUpload
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
import org.slf4j.LoggerFactory
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.time.temporal.ChronoUnit
import ch.qos.logback.classic.Logger as LogbackLogger
import java.util.Date as UtilDate

private const val OIC_AUDIENCE = "https://api-staging.nearyou.id"
private const val OIC_KID = "test-oic-kid"

private fun oicKeypair(): Pair<RSAPublicKey, RSAPrivateKey> {
    val gen = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
    val kp = gen.generateKeyPair()
    return kp.public as RSAPublicKey to kp.private as RSAPrivateKey
}

private class OicFakeJwk(keyId: String, private val pubKey: RSAPublicKey) :
    Jwk(keyId, "RSA", "RS256", null, emptyList(), null, null, null, null) {
    override fun getPublicKey(): java.security.PublicKey = pubKey
}

private class OicStaticJwkProvider(private val mapping: Map<String, Jwk>) : JwkProvider {
    override fun get(keyId: String): Jwk = mapping[keyId] ?: throw JwkException("kid not found: $keyId")
}

private fun oicSignedJwt(
    privateKey: RSAPrivateKey,
    publicKey: RSAPublicKey,
): String =
    JWT.create()
        .withKeyId(OIC_KID)
        .withSubject("scheduler@nearyou-staging.iam.gserviceaccount.com")
        .withAudience(OIC_AUDIENCE)
        .withIssuedAt(UtilDate.from(Instant.now()))
        .withExpiresAt(UtilDate.from(Instant.now().plus(1, ChronoUnit.HOURS)))
        .sign(Algorithm.RSA256(publicKey, privateKey))

/**
 * Route-level tests for [orphanImageCleanupRoutes] — the endpoint contract
 * (200 + counts, the Cloud-Scheduler OIDC identity accepted), the
 * unauthenticated 401 with no deletions, the sanitized classified-500, and
 * the single structured INFO log line. Real Postgres (`@Tags("database")`),
 * mirroring `RetentionCleanupRoutesTest`.
 */
@Tags("database")
class OrphanImageCleanupRoutesTest : StringSpec({

    val dataSource = autoClose(hikari())
    val repository = JdbcOrphanImageCleanupRepository(dataSource)
    val (pubKey, privKey) = oicKeypair()
    val verifier: OidcTokenVerifier =
        GoogleOidcTokenVerifier(
            audience = OIC_AUDIENCE,
            jwkProvider = OicStaticJwkProvider(mapOf(OIC_KID to OicFakeJwk(OIC_KID, pubKey))),
        )

    suspend fun withRoute(
        worker: OrphanImageCleanupWorker,
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
                        orphanImageCleanupRoutes(worker, verifier)
                    }
                }
            }
            block()
        }
    }

    suspend fun withLogCapture(
        loggerName: String,
        block: suspend (ListAppender<ILoggingEvent>) -> Unit,
    ) {
        val logger = LoggerFactory.getLogger(loggerName) as LogbackLogger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        val previous = logger.level
        logger.level = Level.TRACE
        try {
            block(appender)
        } finally {
            logger.detachAppender(appender)
            logger.level = previous
            appender.stop()
        }
    }

    "200 with the counts when an aged orphan exists (scheduler OIDC identity accepted)" {
        val token = oicSignedJwt(privKey, pubKey)
        // Drain currently-eligible rows so the post-seed counts are exact.
        OrphanImageCleanupWorker(repository, RecordingImageStore()).execute()
        val t = tag()
        val u = seedUser(dataSource, t)
        val orphan = seedImageUpload(dataSource, u, createdAt = Instant.now().minus(25, ChronoUnit.HOURS))
        try {
            val store = RecordingImageStore()
            withLogCapture("id.nearyou.app.image.OrphanImageCleanupWorker") { appender ->
                withRoute(OrphanImageCleanupWorker(repository, store)) {
                    val resp =
                        client.post("/internal/cleanup-orphan-images") {
                            header(HttpHeaders.Authorization, "Bearer $token")
                        }
                    resp.status shouldBe HttpStatusCode.OK
                    val body = resp.bodyAsText()
                    body shouldContain "\"scanned\":1"
                    body shouldContain "\"deleted\":1"
                    body shouldContain "\"cf_failures\":0"
                }
                val info =
                    appender.list.filter { it.level == Level.INFO }
                        .map { it.formattedMessage }
                        .filter { it.contains("event=orphan_image_cleanup") }
                info.size shouldBe 1
                val msg = info.single()
                msg shouldContain "scanned="
                msg shouldContain "deleted="
                msg shouldContain "cf_failures="
                msg shouldContain "duration_ms="
                msg shouldNotContain token
            }
            imageUploadExists(dataSource, orphan) shouldBe false
        } finally {
            cleanup(dataSource, listOf(u))
        }
    }

    "401 on missing Authorization → no deletions" {
        val t = tag()
        val u = seedUser(dataSource, t)
        val orphan = seedImageUpload(dataSource, u, createdAt = Instant.now().minus(25, ChronoUnit.HOURS))
        try {
            withRoute(OrphanImageCleanupWorker(repository, RecordingImageStore())) {
                val resp = client.post("/internal/cleanup-orphan-images")
                resp.status shouldBe HttpStatusCode.Unauthorized
            }
            imageUploadExists(dataSource, orphan) shouldBe true
        } finally {
            cleanup(dataSource, listOf(u))
        }
    }

    "sanitized 500 when the worker throws — no exception detail leaks" {
        val token = oicSignedJwt(privKey, pubKey)
        val rawMarker = "oic-raw-detail-should-not-leak"
        val throwingWorker =
            OrphanImageCleanupWorker(
                object : OrphanImageCleanupRepository {
                    override suspend fun findOrphans(): List<String> = throw java.sql.SQLTimeoutException(rawMarker)

                    override suspend fun sweepOrphan(
                        cfImageId: String,
                        deleteFromStore: suspend () -> Unit,
                    ): OrphanSweepOutcome = error("not reached")
                },
                RecordingImageStore(),
            )
        withRoute(throwingWorker) {
            val resp =
                client.post("/internal/cleanup-orphan-images") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }
            resp.status shouldBe HttpStatusCode.InternalServerError
            val body = resp.bodyAsText()
            body shouldContain "\"error\":\"timeout\""
            body shouldNotContain rawMarker
            body shouldNotContain "SQLTimeoutException"
        }
    }
})
