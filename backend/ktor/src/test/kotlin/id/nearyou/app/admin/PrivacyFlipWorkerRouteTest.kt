package id.nearyou.app.admin

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.auth0.jwk.Jwk
import com.auth0.jwk.JwkException
import com.auth0.jwk.JwkProvider
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
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
import org.slf4j.LoggerFactory
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.sql.Date
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import ch.qos.logback.classic.Logger as LogbackLogger
import java.util.Date as UtilDate

private const val PFW_AUDIENCE = "https://api-staging.nearyou.id"
private const val PFW_KID = "test-pfw-kid"

private fun pfwHikari(): HikariDataSource {
    val url = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5433/nearyou_dev"
    val user = System.getenv("DB_USER") ?: "postgres"
    val password = System.getenv("DB_PASSWORD") ?: "postgres"
    return HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = url
            username = user
            this.password = password
            maximumPoolSize = 4
            initializationFailTimeout = -1
        },
    )
}

private fun pfwKeypair(): Pair<RSAPublicKey, RSAPrivateKey> {
    val gen = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
    val kp = gen.generateKeyPair()
    return kp.public as RSAPublicKey to kp.private as RSAPrivateKey
}

private class PfwFakeJwk(keyId: String, private val pubKey: RSAPublicKey) :
    Jwk(keyId, "RSA", "RS256", null, emptyList(), null, null, null, null) {
    override fun getPublicKey(): java.security.PublicKey = pubKey
}

private class PfwStaticJwkProvider(private val mapping: Map<String, Jwk>) : JwkProvider {
    override fun get(keyId: String): Jwk = mapping[keyId] ?: throw JwkException("kid not found: $keyId")
}

private fun pfwSignedJwt(
    privateKey: RSAPrivateKey,
    publicKey: RSAPublicKey,
    audience: String = PFW_AUDIENCE,
    kid: String = PFW_KID,
): String =
    JWT.create()
        .withKeyId(kid)
        .withSubject("scheduler@nearyou-staging.iam.gserviceaccount.com")
        .withAudience(audience)
        .withIssuedAt(UtilDate.from(Instant.now()))
        .withExpiresAt(UtilDate.from(Instant.now().plus(1, ChronoUnit.HOURS)))
        .sign(Algorithm.RSA256(publicKey, privateKey))

/**
 * Route-level tests for [privacyFlipWorkerRoute] — sanitized 500 classification,
 * unauthenticated 401 with no side effects, and the structured INFO log (incl.
 * the capped/truncated id list). Mirrors `UnbanWorkerRouteTest`.
 */
@Tags("database")
class PrivacyFlipWorkerRouteTest : StringSpec({

    val dataSource = autoClose(pfwHikari())
    val worker = PrivacyFlipWorker(dataSource)
    val (pubKey, privKey) = pfwKeypair()
    val verifier: OidcTokenVerifier =
        GoogleOidcTokenVerifier(
            audience = PFW_AUDIENCE,
            jwkProvider = PfwStaticJwkProvider(mapOf(PFW_KID to PfwFakeJwk(PFW_KID, pubKey))),
        )

    // Each test's cleanup() removes its users + their system_privacy_flip_applied
    // audit rows (by target_id); no global afterSpec scrub (it would race the
    // autoClose pool shutdown).

    fun seedElapsedPrivate(): UUID {
        val id = UUID.randomUUID()
        val short = id.toString().replace("-", "").take(8)
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO users (id, username, display_name, date_of_birth, invite_code_prefix,
                                   subscription_status, private_profile_opt_in, privacy_flip_scheduled_at)
                VALUES (?, ?, ?, ?, ?, 'free', TRUE, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "pfwr_$short")
                ps.setString(3, "PFWR $short")
                ps.setDate(4, Date.valueOf(LocalDate.of(1990, 1, 1)))
                ps.setString(5, "r${short.take(7)}")
                ps.setTimestamp(6, Timestamp.from(Instant.now().minusSeconds(3600)))
                ps.executeUpdate()
            }
        }
        return id
    }

    fun cleanup(vararg ids: UUID) {
        if (ids.isEmpty()) return
        dataSource.connection.use { conn ->
            conn.prepareStatement("DELETE FROM admin_actions_log WHERE target_id = ANY(?)").use { ps ->
                ps.setArray(1, conn.createArrayOf("text", ids.map { it.toString() }.toTypedArray()))
                ps.executeUpdate()
            }
            conn.prepareStatement("DELETE FROM users WHERE id = ANY(?)").use { ps ->
                ps.setArray(1, conn.createArrayOf("uuid", ids.toList().toTypedArray()))
                ps.executeUpdate()
            }
        }
    }

    fun loadPrivate(id: UUID): Boolean =
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT private_profile_opt_in FROM users WHERE id = ?").use { ps ->
                ps.setObject(1, id)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getBoolean(1)
                }
            }
        }

    fun countAudit(id: UUID): Int =
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM admin_actions_log WHERE target_id = ? AND action_type = 'system_privacy_flip_applied'",
            ).use { ps ->
                ps.setString(1, id.toString())
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    suspend fun withRoute(
        customWorker: PrivacyFlipWorker = worker,
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
                        privacyFlipWorkerRoute(customWorker, verifier)
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

    "3.10 401 on missing Authorization → no flip, no audit row" {
        val u = seedElapsedPrivate()
        try {
            withRoute {
                val resp = client.post("/internal/privacy-flip-worker")
                resp.status shouldBe HttpStatusCode.Unauthorized
            }
            // The gate rejects before the handler runs — the eligible row is untouched.
            loadPrivate(u) shouldBe true
            countAudit(u) shouldBe 0
        } finally {
            cleanup(u)
        }
    }

    "3.9 sanitized 500 on DataSource failure" {
        val token = pfwSignedJwt(privKey, pubKey)
        val brokenDs =
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = "jdbc:postgresql://localhost:1/nonexistent"
                    username = "x"
                    password = "x"
                    maximumPoolSize = 1
                    initializationFailTimeout = -1
                    connectionTimeout = 1000
                },
            )
        try {
            withRoute(customWorker = PrivacyFlipWorker(brokenDs)) {
                val resp =
                    client.post("/internal/privacy-flip-worker") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }
                resp.status shouldBe HttpStatusCode.InternalServerError
                val body = resp.bodyAsText()
                body shouldContain "\"error\":"
                val classified =
                    body.contains("\"timeout\"") || body.contains("\"connection_refused\"") || body.contains("\"unknown\"")
                classified shouldBe true
                body shouldNotContain "Connection to localhost"
                body shouldNotContain "SQLException"
            }
        } finally {
            brokenDs.close()
        }
    }

    "3.12 structured INFO log on a flip run" {
        val token = pfwSignedJwt(privKey, pubKey)
        val u = seedElapsedPrivate()
        try {
            withLogCapture("id.nearyou.app.admin.PrivacyFlipWorkerRoute") { appender ->
                withRoute {
                    val resp =
                        client.post("/internal/privacy-flip-worker") {
                            header(HttpHeaders.Authorization, "Bearer $token")
                        }
                    resp.status shouldBe HttpStatusCode.OK
                    resp.bodyAsText() shouldContain "\"flipped_count\":"
                }
                val info =
                    appender.list.filter { it.level == Level.INFO }
                        .map { it.formattedMessage }
                        .filter { it.contains("event=privacy_flip_applied") }
                info.size shouldBe 1
                val msg = info.single()
                msg shouldContain "flipped_count="
                msg shouldContain "duration_ms="
                msg shouldNotContain token
            }
        } finally {
            cleanup(u)
        }
    }

    "3.12 pathological volume — 60 elapsed users → response 60, log capped at 50 + truncated flag" {
        val token = pfwSignedJwt(privKey, pubKey)
        val ids = (1..60).map { seedElapsedPrivate() }
        try {
            withLogCapture("id.nearyou.app.admin.PrivacyFlipWorkerRoute") { appender ->
                withRoute {
                    val resp =
                        client.post("/internal/privacy-flip-worker") {
                            header(HttpHeaders.Authorization, "Bearer $token")
                        }
                    resp.status shouldBe HttpStatusCode.OK
                    resp.bodyAsText() shouldContain "\"flipped_count\":60"
                    ids.forEach { loadPrivate(it) shouldBe false }
                }
                val info =
                    appender.list.filter { it.level == Level.INFO }
                        .map { it.formattedMessage }
                        .single { it.contains("event=privacy_flip_applied") }
                info shouldContain "flipped_user_ids_truncated=true"
                val uuidRegex = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                uuidRegex.findAll(info).count() shouldBe 50
            }
        } finally {
            cleanup(*ids.toTypedArray())
        }
    }
})
