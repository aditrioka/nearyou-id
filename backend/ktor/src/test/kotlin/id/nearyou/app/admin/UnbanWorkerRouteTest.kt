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
import id.nearyou.app.auth.configureUserJwt
import id.nearyou.app.auth.jwt.JwtIssuer
import id.nearyou.app.auth.jwt.RsaKeyLoader
import id.nearyou.app.auth.jwt.TestKeys
import id.nearyou.app.core.domain.oidc.OidcTokenVerifier
import id.nearyou.app.infra.oidc.GoogleOidcTokenVerifier
import id.nearyou.app.infra.repo.JdbcUserRepository
import id.nearyou.app.internal.InternalEndpointAuth
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
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
import io.ktor.server.routing.get as routingGet
import java.util.Date as UtilDate

private const val TEST_AUDIENCE = "https://api-staging.nearyou.id"
private const val TEST_KID = "test-route-kid"

private fun hikariRoute(): HikariDataSource {
    val url = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5433/nearyou_dev"
    val user = System.getenv("DB_USER") ?: "postgres"
    val password = System.getenv("DB_PASSWORD") ?: "postgres"
    val config =
        HikariConfig().apply {
            jdbcUrl = url
            username = user
            this.password = password
            maximumPoolSize = 4
            initializationFailTimeout = -1
        }
    return HikariDataSource(config)
}

private fun rsaKeypair(): Pair<RSAPublicKey, RSAPrivateKey> {
    val gen = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
    val kp = gen.generateKeyPair()
    return kp.public as RSAPublicKey to kp.private as RSAPrivateKey
}

private class FakeJwk(keyId: String, private val pubKey: RSAPublicKey) :
    Jwk(keyId, "RSA", "RS256", null, emptyList(), null, null, null, null) {
    override fun getPublicKey(): java.security.PublicKey = pubKey
}

private class StaticJwkProvider(private val mapping: Map<String, Jwk>) : JwkProvider {
    override fun get(keyId: String): Jwk = mapping[keyId] ?: throw JwkException("kid not found: $keyId")
}

private fun signedJwt(
    privateKey: RSAPrivateKey,
    publicKey: RSAPublicKey,
    audience: String = TEST_AUDIENCE,
    kid: String = TEST_KID,
    expiresAt: Instant = Instant.now().plus(1, ChronoUnit.HOURS),
    subject: String = "scheduler@nearyou-staging.iam.gserviceaccount.com",
): String =
    JWT.create()
        .withKeyId(kid)
        .withSubject(subject)
        .withAudience(audience)
        .withIssuedAt(UtilDate.from(Instant.now()))
        .withExpiresAt(UtilDate.from(expiresAt))
        .sign(Algorithm.RSA256(publicKey, privateKey))

@Tags("database")
class UnbanWorkerRouteTest : StringSpec({

    val dataSource = hikariRoute()
    val worker = SuspensionUnbanWorker(dataSource)
    val (pubKey, privKey) = rsaKeypair()
    val defaultVerifier: OidcTokenVerifier =
        GoogleOidcTokenVerifier(
            audience = TEST_AUDIENCE,
            jwkProvider = StaticJwkProvider(mapOf(TEST_KID to FakeJwk(TEST_KID, pubKey))),
        )
    val keys = RsaKeyLoader(TestKeys.freshEncodedPemPrivateKey(), kid = "test-supabase")
    val users = JdbcUserRepository(dataSource)
    val userJwtIssuer = JwtIssuer(keys)

    fun seedUser(
        isBanned: Boolean,
        suspendedUntil: Instant?,
        deletedAt: Instant? = null,
    ): UUID {
        val id = UUID.randomUUID()
        val short = id.toString().replace("-", "").take(8)
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO users (
                    id, username, display_name, date_of_birth, invite_code_prefix,
                    is_banned, suspended_until, deleted_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "rt_$short")
                ps.setString(3, "Route Test User")
                ps.setDate(4, Date.valueOf(LocalDate.of(1990, 1, 1)))
                ps.setString(5, "r${short.take(7)}")
                ps.setBoolean(6, isBanned)
                if (suspendedUntil != null) {
                    ps.setTimestamp(7, Timestamp.from(suspendedUntil))
                } else {
                    ps.setNull(7, java.sql.Types.TIMESTAMP)
                }
                if (deletedAt != null) {
                    ps.setTimestamp(8, Timestamp.from(deletedAt))
                } else {
                    ps.setNull(8, java.sql.Types.TIMESTAMP)
                }
                ps.executeUpdate()
            }
        }
        return id
    }

    fun cleanup(vararg ids: UUID) {
        if (ids.isEmpty()) return
        dataSource.connection.use { conn ->
            conn.prepareStatement("DELETE FROM users WHERE id = ANY(?)").use { ps ->
                val arr = conn.createArrayOf("uuid", ids.toList().toTypedArray())
                ps.setArray(1, arr)
                ps.executeUpdate()
            }
        }
    }

    fun loadIsBanned(id: UUID): Boolean =
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT is_banned FROM users WHERE id = ?").use { ps ->
                ps.setObject(1, id)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getBoolean("is_banned")
                }
            }
        }

    fun countNewNotificationRows(
        ids: List<UUID>,
        beforeCount: Int,
    ): Int = countNotifications(dataSource, ids) - beforeCount

    suspend fun withRoute(
        customVerifier: OidcTokenVerifier = defaultVerifier,
        customWorker: SuspensionUnbanWorker = worker,
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
                // Supabase auth installed for the cross-path-isolation test (9.29).
                install(Authentication) { configureUserJwt(keys, users, java.time.Instant::now) }
                routing {
                    // /health/live to verify regression in 9.22.
                    healthLiveStub()
                    route("/internal") {
                        install(InternalEndpointAuth) { this.verifier = customVerifier }
                        unbanWorkerRoute(customWorker)
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

    "9.10 401 on missing Authorization header" {
        withRoute {
            val resp = client.post("/internal/unban-worker")
            resp.status shouldBe HttpStatusCode.Unauthorized
            resp.bodyAsText() shouldContain "missing_authorization"
        }
    }

    "9.11 401 on Basic scheme" {
        withRoute {
            val resp =
                client.post("/internal/unban-worker") {
                    header(HttpHeaders.Authorization, "Basic dXNlcjpwYXNz")
                }
            resp.status shouldBe HttpStatusCode.Unauthorized
            resp.bodyAsText() shouldContain "invalid_scheme"
        }
    }

    "9.12 401 on malformed JWT" {
        withRoute {
            val resp =
                client.post("/internal/unban-worker") {
                    header(HttpHeaders.Authorization, "Bearer not.a.jwt")
                }
            resp.status shouldBe HttpStatusCode.Unauthorized
            resp.bodyAsText() shouldContain "invalid_token"
        }
    }

    "9.13 401 on token with bad signature" {
        // Sign with an unrelated keypair so the signature does not match the JWKS.
        val (pubB, privB) = rsaKeypair()
        val token = signedJwt(privateKey = privB, publicKey = pubB)
        withRoute {
            val resp =
                client.post("/internal/unban-worker") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }
            resp.status shouldBe HttpStatusCode.Unauthorized
            resp.bodyAsText() shouldContain "invalid_token"
        }
    }

    "9.14 401 on audience mismatch" {
        val token =
            signedJwt(
                privateKey = privKey,
                publicKey = pubKey,
                audience = "https://example.com/other-service",
            )
        withRoute {
            val resp =
                client.post("/internal/unban-worker") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }
            resp.status shouldBe HttpStatusCode.Unauthorized
            resp.bodyAsText() shouldContain "audience_mismatch"
        }
    }

    "9.15 401 on expired token" {
        val token =
            signedJwt(
                privateKey = privKey,
                publicKey = pubKey,
                expiresAt = Instant.now().minus(5, ChronoUnit.MINUTES),
            )
        withRoute {
            val resp =
                client.post("/internal/unban-worker") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }
            resp.status shouldBe HttpStatusCode.Unauthorized
            resp.bodyAsText() shouldContain "expired_token"
        }
    }

    "9.16 200 on valid token + zero eligible users" {
        val token = signedJwt(privKey, pubKey)
        withRoute {
            val resp =
                client.post("/internal/unban-worker") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }
            resp.status shouldBe HttpStatusCode.OK
            resp.bodyAsText() shouldContain "\"unbanned_count\":"
        }
    }

    "9.17 200 on valid token + three eligible users → unbanned_count = 3" {
        val token = signedJwt(privKey, pubKey)
        val ids =
            (1..3).map {
                seedUser(isBanned = true, suspendedUntil = Instant.now().minusSeconds(3600))
            }
        try {
            withRoute {
                val resp =
                    client.post("/internal/unban-worker") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }
                resp.status shouldBe HttpStatusCode.OK
                val body = resp.bodyAsText()
                body shouldContain "\"unbanned_count\":3"
                ids.forEach { loadIsBanned(it) shouldBe false }
            }
        } finally {
            cleanup(*ids.toTypedArray())
        }
    }

    "9.18 + 9.19 structured INFO event + log redaction" {
        val token = signedJwt(privKey, pubKey)
        val uid = seedUser(isBanned = true, suspendedUntil = Instant.now().minusSeconds(3600))
        try {
            withLogCapture("id.nearyou.app.admin.UnbanWorkerRoute") { routeAppender ->
                withLogCapture("id.nearyou.app.internal.InternalEndpointAuth") { authAppender ->
                    withRoute {
                        val resp =
                            client.post("/internal/unban-worker") {
                                header(HttpHeaders.Authorization, "Bearer $token")
                            }
                        resp.status shouldBe HttpStatusCode.OK
                    }
                    val infoEvents =
                        routeAppender.list.filter { it.level == Level.INFO }
                            .map { it.formattedMessage }
                            .filter { it.contains("event=suspension_unban_applied") }
                    infoEvents.size shouldBe 1
                    val msg = infoEvents.single()
                    msg shouldContain "unbanned_count="
                    msg shouldContain "unbanned_user_ids="
                    // 9.19 redaction: no token bytes, no claim, no audience leak.
                    msg shouldNotContain token
                    val allMessages =
                        (routeAppender.list + authAppender.list).map { it.formattedMessage }
                    allMessages.forEach { line ->
                        line shouldNotContain token
                        line shouldNotContain "audience-canary"
                        line shouldNotContain "REDACTION_PROBE_SUB"
                        line shouldNotContain "REDACTION_PROBE_JTI"
                    }
                }
            }
        } finally {
            cleanup(uid)
        }
    }

    "9.19 log-redaction on 401 path — bearer + audience + claims absent" {
        val canaryToken =
            JWT.create()
                .withKeyId(TEST_KID)
                .withSubject("REDACTION_PROBE_SUB")
                .withAudience("https://probe.test/audience-canary")
                .withClaim("jti", "REDACTION_PROBE_JTI")
                .withIssuedAt(UtilDate.from(Instant.now()))
                .withExpiresAt(UtilDate.from(Instant.now().plus(1, ChronoUnit.HOURS)))
                .sign(Algorithm.RSA256(pubKey, privKey))
        withLogCapture("id.nearyou.app.internal.InternalEndpointAuth") { appender ->
            withRoute {
                val resp =
                    client.post("/internal/unban-worker") {
                        header(HttpHeaders.Authorization, "Bearer $canaryToken")
                    }
                resp.status shouldBe HttpStatusCode.Unauthorized
                resp.bodyAsText() shouldContain "audience_mismatch"
                // Body must not echo the configured audience nor the token claims.
                resp.bodyAsText() shouldNotContain TEST_AUDIENCE
                resp.bodyAsText() shouldNotContain "audience-canary"
            }
            appender.list.map { it.formattedMessage }.forEach { line ->
                line shouldNotContain canaryToken
                line shouldNotContain "audience-canary"
                line shouldNotContain "REDACTION_PROBE_SUB"
                line shouldNotContain "REDACTION_PROBE_JTI"
            }
        }
    }

    "9.20 pathological volume — 60 eligible users → response 60, log capped at 50 with truncated flag" {
        val token = signedJwt(privKey, pubKey)
        val ids =
            (1..60).map {
                seedUser(isBanned = true, suspendedUntil = Instant.now().minusSeconds(3600))
            }
        try {
            withLogCapture("id.nearyou.app.admin.UnbanWorkerRoute") { appender ->
                withRoute {
                    val resp =
                        client.post("/internal/unban-worker") {
                            header(HttpHeaders.Authorization, "Bearer $token")
                        }
                    resp.status shouldBe HttpStatusCode.OK
                    resp.bodyAsText() shouldContain "\"unbanned_count\":60"
                    ids.forEach { loadIsBanned(it) shouldBe false }
                }
                val info =
                    appender.list.filter { it.level == Level.INFO }
                        .map { it.formattedMessage }
                        .single { it.contains("event=suspension_unban_applied") }
                info shouldContain "unbanned_user_ids_truncated=true"
                // Naive check: count UUID-shaped substrings in the log line; should be 50.
                val uuidRegex = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                uuidRegex.findAll(info).count() shouldBe 50
            }
        } finally {
            cleanup(*ids.toTypedArray())
        }
    }

    "9.21 sanitized 500 on DataSource failure" {
        val token = signedJwt(privKey, pubKey)
        // A pre-closed DataSource → connection acquire throws, route classifies + sanitizes.
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
            val brokenWorker = SuspensionUnbanWorker(brokenDs)
            withRoute(customWorker = brokenWorker) {
                val resp =
                    client.post("/internal/unban-worker") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }
                resp.status shouldBe HttpStatusCode.InternalServerError
                val body = resp.bodyAsText()
                body shouldContain "\"error\":"
                // Must contain one of the sanitized classifications; must NOT contain raw SQL detail.
                val ok =
                    body.contains("\"timeout\"") ||
                        body.contains("\"connection_refused\"") ||
                        body.contains("\"unknown\"")
                ok shouldBe true
                body shouldNotContain "Connection to localhost"
                body shouldNotContain "ConnectException"
                body shouldNotContain "SQLException"
            }
        } finally {
            brokenDs.close()
        }
    }

    "9.22 health endpoint unaffected by InternalEndpointAuth" {
        withRoute {
            val resp = client.get("/health/live")
            resp.status shouldBe HttpStatusCode.OK
        }
    }

    "9.23 route is mounted under /internal — bare path returns 404" {
        val token = signedJwt(privKey, pubKey)
        withRoute {
            val bare =
                client.post("/unban-worker") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }
            bare.status shouldBe HttpStatusCode.NotFound
            val mounted =
                client.post("/internal/unban-worker") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }
            mounted.status shouldBe HttpStatusCode.OK
        }
    }

    "9.24 no notification rows inserted on unban" {
        val token = signedJwt(privKey, pubKey)
        val ids =
            (1..3).map {
                seedUser(isBanned = true, suspendedUntil = Instant.now().minusSeconds(3600))
            }
        try {
            val before = countNotifications(dataSource, ids)
            withRoute {
                val resp =
                    client.post("/internal/unban-worker") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }
                resp.status shouldBe HttpStatusCode.OK
            }
            val newRows = countNewNotificationRows(ids, before)
            newRows shouldBe 0
        } finally {
            cleanup(*ids.toTypedArray())
        }
    }

    "9.27 JWKS rotation refresh resolves new key → 200; second unknown kid → 401" {
        val token = signedJwt(privKey, pubKey, kid = "rotating-kid")
        val rotatingProvider =
            object : JwkProvider {
                var calls = 0

                override fun get(keyId: String): Jwk {
                    calls += 1
                    return when {
                        keyId == "rotating-kid" && calls > 1 -> FakeJwk("rotating-kid", pubKey)
                        else -> throw JwkException("kid not found: $keyId")
                    }
                }
            }
        val refreshingProvider =
            object : JwkProvider {
                override fun get(keyId: String): Jwk =
                    try {
                        rotatingProvider.get(keyId)
                    } catch (_: JwkException) {
                        rotatingProvider.get(keyId)
                    }
            }
        val rotatingVerifier: OidcTokenVerifier =
            GoogleOidcTokenVerifier(audience = TEST_AUDIENCE, jwkProvider = refreshingProvider)

        withRoute(customVerifier = rotatingVerifier) {
            val resp1 =
                client.post("/internal/unban-worker") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }
            resp1.status shouldBe HttpStatusCode.OK
        }

        val noopProvider =
            object : JwkProvider {
                override fun get(keyId: String): Jwk = throw JwkException("kid never present: $keyId")
            }
        val refreshingNoop =
            object : JwkProvider {
                override fun get(keyId: String): Jwk =
                    try {
                        noopProvider.get(keyId)
                    } catch (_: JwkException) {
                        noopProvider.get(keyId)
                    }
            }
        val deadVerifier: OidcTokenVerifier =
            GoogleOidcTokenVerifier(audience = TEST_AUDIENCE, jwkProvider = refreshingNoop)
        val unresolvableToken = signedJwt(privKey, pubKey, kid = "still-unknown")
        withRoute(customVerifier = deadVerifier) {
            val resp =
                client.post("/internal/unban-worker") {
                    header(HttpHeaders.Authorization, "Bearer $unresolvableToken")
                }
            resp.status shouldBe HttpStatusCode.Unauthorized
            resp.bodyAsText() shouldContain "invalid_token"
        }
    }

    "9.28 blank token after Bearer scheme → invalid_token" {
        withRoute {
            val resp =
                client.post("/internal/unban-worker") {
                    header(HttpHeaders.Authorization, "Bearer ")
                }
            resp.status shouldBe HttpStatusCode.Unauthorized
            resp.bodyAsText() shouldContain "invalid_token"
        }
    }

    "9.29 cross-path auth isolation — Supabase HS256 token rejected by /internal/*" {
        val supabaseUserId = UUID.randomUUID()
        val short = supabaseUserId.toString().replace("-", "").take(7)
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO users (id, username, display_name, date_of_birth, invite_code_prefix)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, supabaseUserId)
                ps.setString(2, "iso_$short")
                ps.setString(3, "Cross Path Tester")
                ps.setDate(4, Date.valueOf(LocalDate.of(1990, 1, 1)))
                ps.setString(5, "i$short")
                ps.executeUpdate()
            }
        }
        try {
            val supabaseUserToken = userJwtIssuer.issueAccessToken(supabaseUserId, tokenVersion = 0)
            withRoute {
                val resp =
                    client.post("/internal/unban-worker") {
                        header(HttpHeaders.Authorization, "Bearer $supabaseUserToken")
                    }
                resp.status shouldBe HttpStatusCode.Unauthorized
                resp.bodyAsText() shouldContain "invalid_token"
            }
        } finally {
            cleanup(supabaseUserId)
        }
    }
})

private fun Routing.healthLiveStub() {
    routingGet("/health/live") {
        call.respondText("OK")
    }
}

private fun countNotifications(
    dataSource: HikariDataSource,
    ids: List<UUID>,
): Int {
    if (ids.isEmpty()) return 0
    return dataSource.connection.use { conn ->
        conn.prepareStatement("SELECT COUNT(*) FROM notifications WHERE user_id = ANY(?)").use { ps ->
            ps.setArray(1, conn.createArrayOf("uuid", ids.toTypedArray()))
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
    }
}
