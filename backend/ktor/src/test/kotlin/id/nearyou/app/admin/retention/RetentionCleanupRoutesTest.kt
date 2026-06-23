package id.nearyou.app.admin.retention

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
import id.nearyou.app.admin.retention.RetentionCleanupTestSupport.cleanup
import id.nearyou.app.admin.retention.RetentionCleanupTestSupport.fcmTokenExists
import id.nearyou.app.admin.retention.RetentionCleanupTestSupport.hikari
import id.nearyou.app.admin.retention.RetentionCleanupTestSupport.notificationExists
import id.nearyou.app.admin.retention.RetentionCleanupTestSupport.refreshTokenExists
import id.nearyou.app.admin.retention.RetentionCleanupTestSupport.seedFcmToken
import id.nearyou.app.admin.retention.RetentionCleanupTestSupport.seedNotification
import id.nearyou.app.admin.retention.RetentionCleanupTestSupport.seedRefreshToken
import id.nearyou.app.admin.retention.RetentionCleanupTestSupport.seedUser
import id.nearyou.app.admin.retention.RetentionCleanupTestSupport.tag
import id.nearyou.app.config.SecretResolver
import id.nearyou.app.core.domain.oidc.OidcTokenVerifier
import id.nearyou.app.infra.oidc.GoogleOidcTokenVerifier
import id.nearyou.app.notifications.NoopNotificationDispatcher
import id.nearyou.app.notifications.NotificationEmitter
import id.nearyou.app.subscription.SubscriptionEventRepository
import id.nearyou.app.subscription.SubscriptionService
import id.nearyou.app.subscription.revenueCatWebhookRoutes
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
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
import java.sql.SQLTimeoutException
import java.time.Instant
import java.time.temporal.ChronoUnit
import ch.qos.logback.classic.Logger as LogbackLogger
import java.util.Date as UtilDate

private const val RC_AUDIENCE = "https://api-staging.nearyou.id"
private const val RC_KID = "test-rc-kid"

private fun rcKeypair(): Pair<RSAPublicKey, RSAPrivateKey> {
    val gen = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
    val kp = gen.generateKeyPair()
    return kp.public as RSAPublicKey to kp.private as RSAPrivateKey
}

private class RcFakeJwk(keyId: String, private val pubKey: RSAPublicKey) :
    Jwk(keyId, "RSA", "RS256", null, emptyList(), null, null, null, null) {
    override fun getPublicKey(): java.security.PublicKey = pubKey
}

private class RcStaticJwkProvider(private val mapping: Map<String, Jwk>) : JwkProvider {
    override fun get(keyId: String): Jwk = mapping[keyId] ?: throw JwkException("kid not found: $keyId")
}

private fun rcSignedJwt(
    privateKey: RSAPrivateKey,
    publicKey: RSAPublicKey,
    audience: String = RC_AUDIENCE,
    kid: String = RC_KID,
): String =
    JWT.create()
        .withKeyId(kid)
        .withSubject("scheduler@nearyou-staging.iam.gserviceaccount.com")
        .withAudience(audience)
        .withIssuedAt(UtilDate.from(Instant.now()))
        .withExpiresAt(UtilDate.from(Instant.now().plus(1, ChronoUnit.HOURS)))
        .sign(Algorithm.RSA256(publicKey, privateKey))

/**
 * Route-level tests for [retentionCleanupRoutes] — the endpoint contract (200 +
 * the three per-sweep counts), all-zero idempotency, the single structured INFO
 * log, the unauthenticated 401 with no deletions, the sibling-non-capture
 * (`/internal/revenuecat-webhook` is NOT 401'd by this gate), and the sanitized
 * classified-500. Real-Postgres (`@Tags("database")`), mirroring
 * `PrivacyFlipWorkerRouteTest`.
 *
 * Determinism in the shared dev DB: each count test first runs the worker once
 * to clear ALL currently-eligible rows, THEN seeds exactly its own eligible
 * rows, so the subsequent endpoint run's counts equal the seeded counts
 * (nothing ages into eligibility in the sub-second gap; Kotest runs specs
 * single-fork-sequentially).
 */
@Tags("database")
class RetentionCleanupRoutesTest : StringSpec({

    val dataSource = autoClose(hikari())
    val repository = JdbcRetentionCleanupRepository(dataSource)
    val worker = RetentionCleanupWorker(repository)
    val (pubKey, privKey) = rcKeypair()
    val verifier: OidcTokenVerifier =
        GoogleOidcTokenVerifier(
            audience = RC_AUDIENCE,
            jwkProvider = RcStaticJwkProvider(mapOf(RC_KID to RcFakeJwk(RC_KID, pubKey))),
        )

    suspend fun withRoute(
        customWorker: RetentionCleanupWorker = worker,
        mountRevenueCat: Boolean = false,
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
                if (mountRevenueCat) {
                    // Production shape: the vendor webhook in its OWN routing block,
                    // mounted OUTSIDE the OIDC gate (revenueCatWebhookRoutes opens its
                    // own `routing { post("/internal/revenuecat-webhook") ... }`).
                    revenueCatWebhookRoutes(StubRevenueCatService, StubRevenueCatSecrets, "test")
                }
                routing {
                    route("/internal") {
                        retentionCleanupRoutes(customWorker, verifier)
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

    "200 with the three correct per-sweep counts when each table has eligible rows" {
        val token = rcSignedJwt(privKey, pubKey)
        // Clear all currently-eligible rows so the post-seed counts are exact.
        worker.execute()
        val t = tag()
        val u = seedUser(dataSource, t)
        val rt =
            seedRefreshToken(
                dataSource,
                u,
                t,
                expiresAt = Instant.now().minus(2, ChronoUnit.DAYS),
                lastUsedAt = Instant.now().minus(1, ChronoUnit.DAYS),
            )
        val n = seedNotification(dataSource, u, createdAt = Instant.now().minus(91, ChronoUnit.DAYS))
        val fcm = seedFcmToken(dataSource, u, t, lastSeenAt = Instant.now().minus(31, ChronoUnit.DAYS))
        try {
            withRoute {
                val resp =
                    client.post("/internal/cleanup") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }
                resp.status shouldBe HttpStatusCode.OK
                val body = resp.bodyAsText()
                body shouldContain "\"refresh_tokens_deleted\":1"
                body shouldContain "\"notifications_deleted\":1"
                body shouldContain "\"fcm_tokens_deleted\":1"
            }
            refreshTokenExists(dataSource, rt) shouldBe false
            notificationExists(dataSource, n) shouldBe false
            fcmTokenExists(dataSource, fcm) shouldBe false
        } finally {
            cleanup(dataSource, listOf(u))
        }
    }

    "a cold/idempotent re-run returns 200 with all counts zero" {
        val token = rcSignedJwt(privKey, pubKey)
        // First run clears whatever is currently eligible (count unknown). The
        // immediate second run has nothing newly aged → every count is 0.
        worker.execute()
        withRoute {
            val resp =
                client.post("/internal/cleanup") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }
            resp.status shouldBe HttpStatusCode.OK
            val body = resp.bodyAsText()
            body shouldContain "\"refresh_tokens_deleted\":0"
            body shouldContain "\"notifications_deleted\":0"
            body shouldContain "\"fcm_tokens_deleted\":0"
        }
    }

    "exactly one structured INFO log line with the three counts + duration" {
        val token = rcSignedJwt(privKey, pubKey)
        worker.execute()
        withLogCapture("id.nearyou.app.admin.retention.RetentionCleanupWorker") { appender ->
            withRoute {
                val resp =
                    client.post("/internal/cleanup") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }
                resp.status shouldBe HttpStatusCode.OK
            }
            val info =
                appender.list.filter { it.level == Level.INFO }
                    .map { it.formattedMessage }
                    .filter { it.contains("event=retention_cleanup") }
            info.size shouldBe 1
            val msg = info.single()
            msg shouldContain "refresh_tokens_deleted="
            msg shouldContain "notifications_deleted="
            msg shouldContain "fcm_tokens_deleted="
            msg shouldContain "duration_ms="
            msg shouldNotContain token
        }
    }

    "401 on missing Authorization → no deletions" {
        // Clear eligibles, then seed one eligible row of each type; assert the gate
        // rejects before the handler runs and the rows are untouched.
        worker.execute()
        val t = tag()
        val u = seedUser(dataSource, t)
        val rt =
            seedRefreshToken(
                dataSource,
                u,
                t,
                expiresAt = Instant.now().minus(2, ChronoUnit.DAYS),
                lastUsedAt = Instant.now().minus(1, ChronoUnit.DAYS),
            )
        val n = seedNotification(dataSource, u, createdAt = Instant.now().minus(91, ChronoUnit.DAYS))
        val fcm = seedFcmToken(dataSource, u, t, lastSeenAt = Instant.now().minus(31, ChronoUnit.DAYS))
        try {
            withRoute {
                val resp = client.post("/internal/cleanup")
                resp.status shouldBe HttpStatusCode.Unauthorized
            }
            refreshTokenExists(dataSource, rt) shouldBe true
            notificationExists(dataSource, n) shouldBe true
            fcmTokenExists(dataSource, fcm) shouldBe true
        } finally {
            cleanup(dataSource, listOf(u))
        }
    }

    "the cleanup gate does NOT capture the sibling RevenueCat webhook" {
        withRoute(mountRevenueCat = true) {
            // Correct VENDOR bearer (NOT a Google-OIDC token) + a malformed body. If
            // the OIDC gate had captured this route, a non-OIDC bearer would 401
            // before any handler logic. Instead the vendor route answers from its OWN
            // auth + body validation (non-401), proving it does NOT inherit OIDC.
            val resp =
                client.post("/internal/revenuecat-webhook") {
                    header(HttpHeaders.Authorization, "Bearer rc-test-bearer")
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }
            resp.status shouldNotBe HttpStatusCode.Unauthorized
        }
    }

    "sanitized 500 on DataSource failure" {
        val token = rcSignedJwt(privKey, pubKey)
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
            val brokenWorker = RetentionCleanupWorker(JdbcRetentionCleanupRepository(brokenDs))
            withRoute(customWorker = brokenWorker) {
                val resp =
                    client.post("/internal/cleanup") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }
                resp.status shouldBe HttpStatusCode.InternalServerError
                val body = resp.bodyAsText()
                body shouldContain "\"error\":"
                val classified =
                    body.contains("\"timeout\"") ||
                        body.contains("\"connection_refused\"") ||
                        body.contains("\"unknown\"")
                classified shouldBe true
                body shouldNotContain "Connection to localhost"
                body shouldNotContain "SQLException"
            }
        } finally {
            brokenDs.close()
        }
    }

    "sanitized 500 with error=timeout when a sweep throws SQLTimeoutException" {
        val token = rcSignedJwt(privKey, pubKey)
        // A stub repository whose first sweep throws SQLTimeoutException, which
        // classifyHandlerError maps deterministically to "timeout". Proves the
        // timeout classification branch (the existing 500 test only exercises
        // connection-refused) and that the raw exception text never leaks.
        val timeoutMarker = "rc-raw-timeout-detail-should-not-leak"
        val timeoutWorker =
            RetentionCleanupWorker(
                object : RetentionCleanupRepository {
                    override suspend fun deleteExpiredAndStaleRefreshTokens(): Int = throw SQLTimeoutException(timeoutMarker)

                    override suspend fun purgeOldNotifications(): Int = error("not reached")

                    override suspend fun deleteStaleFcmTokens(): Int = error("not reached")

                    override suspend fun deleteOldLoginEvents(): Int = error("not reached")
                },
            )
        withRoute(customWorker = timeoutWorker) {
            val resp =
                client.post("/internal/cleanup") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }
            resp.status shouldBe HttpStatusCode.InternalServerError
            val body = resp.bodyAsText()
            body shouldContain "\"error\":\"timeout\""
            body shouldNotContain timeoutMarker
            body shouldNotContain "SQLTimeoutException"
        }
    }
})

/** Vendor bearer resolver for the revenuecat-webhook isolation case. */
private val StubRevenueCatSecrets =
    object : SecretResolver {
        override fun resolve(name: String): String? = if (name == "revenuecat-webhook-secret") "rc-test-bearer" else null
    }

/** Never invoked — the malformed `{}` body 400s in the route before the service runs. */
private val StubRevenueCatService =
    SubscriptionService(
        dataSource = UnusedDataSourceForRc,
        repository = SubscriptionEventRepository(),
        notifications =
            object : NotificationEmitter {
                override fun emit(
                    conn: java.sql.Connection,
                    recipientId: java.util.UUID,
                    actorUserId: java.util.UUID?,
                    type: id.nearyou.data.repository.NotificationType,
                    targetType: String?,
                    targetId: java.util.UUID?,
                    bodyData: kotlinx.serialization.json.JsonObject,
                ): java.util.UUID? = error("not used")
            },
        dispatcher = NoopNotificationDispatcher(),
    )

/** Worker ctor needs a DataSource; the malformed-body 400 path never touches it. */
private object UnusedDataSourceForRc : javax.sql.DataSource {
    override fun getConnection(): java.sql.Connection = error("not used")

    override fun getConnection(
        username: String?,
        password: String?,
    ): java.sql.Connection = error("not used")

    override fun getLogWriter(): java.io.PrintWriter = error("not used")

    override fun setLogWriter(out: java.io.PrintWriter?) = error("not used")

    override fun setLoginTimeout(seconds: Int) = error("not used")

    override fun getLoginTimeout(): Int = error("not used")

    override fun getParentLogger(): java.util.logging.Logger = error("not used")

    override fun <T : Any?> unwrap(iface: Class<T>?): T = error("not used")

    override fun isWrapperFor(iface: Class<*>?): Boolean = error("not used")
}
