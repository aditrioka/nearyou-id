package id.nearyou.app.auth.routes

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.auth.AuthRateLimiter
import id.nearyou.app.auth.configureUserJwt
import id.nearyou.app.auth.jwt.JwtIssuer
import id.nearyou.app.auth.jwt.RsaKeyLoader
import id.nearyou.app.auth.jwt.TestKeys
import id.nearyou.app.auth.loginhistory.InMemoryLoginEvents
import id.nearyou.app.auth.loginhistory.LoginEventRecorder
import id.nearyou.app.auth.provider.ProviderIdTokenVerifier
import id.nearyou.app.auth.provider.VerifiedIdToken
import id.nearyou.app.auth.session.RefreshTokenService
import id.nearyou.app.auth.session.TransactionalLogoutService
import id.nearyou.app.infra.redis.NoOpRateLimiter
import id.nearyou.app.infra.repo.JdbcRefreshTokenRepository
import id.nearyou.app.infra.repo.JdbcUserRepository
import id.nearyou.app.user.FcmTokenRepository
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import java.sql.Date
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientCN

private fun hikari(): HikariDataSource {
    val url = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5433/nearyou_dev"
    val user = System.getenv("DB_USER") ?: "postgres"
    val password = System.getenv("DB_PASSWORD") ?: "postgres"
    val config =
        HikariConfig().apply {
            jdbcUrl = url
            username = user
            this.password = password
            maximumPoolSize = 2
            initializationFailTimeout = -1
        }
    return HikariDataSource(config)
}

private object UnusedVerifier : ProviderIdTokenVerifier {
    override suspend fun verify(idToken: String): VerifiedIdToken = error("unused")
}

/**
 * DB-backed tests for the `auth-session` logout requirement (`logout-revocation`): the optional
 * `fcm_token` delete on single-device logout (including the stale-refresh-token path), the
 * one-transaction logout-all (refresh family + token_version + all FCM rows), and the spec'd
 * deferral that single-device logout does NOT bump `token_version`. Also exercises the new
 * `FcmTokenRepository.deleteByUserAndToken` / `deleteAllForUser(conn, …)` methods end-to-end.
 */
@Tags("database")
class LogoutRoutesTest : StringSpec({

    val dataSource = autoClose(hikari())
    val keys = RsaKeyLoader(TestKeys.freshEncodedPemPrivateKey(), kid = "test-logout")
    val jwtIssuer = JwtIssuer(keys)
    val users = JdbcUserRepository(dataSource)
    val refreshRepo = JdbcRefreshTokenRepository(dataSource)
    val refreshService = RefreshTokenService(refreshRepo, users)
    val fcmRepo = FcmTokenRepository(dataSource)

    fun seedUser(): Pair<UUID, String> {
        val id = UUID.randomUUID()
        val short = id.toString().replace("-", "").take(8)
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO users (
                    id, username, display_name, date_of_birth, invite_code_prefix
                ) VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "lo_$short")
                ps.setString(3, "Logout Tester")
                ps.setDate(4, Date.valueOf(LocalDate.of(1990, 1, 1)))
                ps.setString(5, "l${short.take(7)}")
                ps.executeUpdate()
            }
        }
        val access = jwtIssuer.issueAccessToken(id, tokenVersion = 0)
        return id to access
    }

    fun cleanup(vararg ids: UUID) {
        dataSource.connection.use { conn ->
            ids.forEach { id ->
                // refresh_tokens.user_id has no ON DELETE CASCADE — child rows first.
                listOf(
                    "DELETE FROM refresh_tokens WHERE user_id = ?",
                    "DELETE FROM user_fcm_tokens WHERE user_id = ?",
                    "DELETE FROM users WHERE id = ?",
                ).forEach { sql ->
                    conn.prepareStatement(sql).use { ps ->
                        ps.setObject(1, id)
                        ps.executeUpdate()
                    }
                }
            }
        }
    }

    fun fcmTokenCount(userId: UUID): Int =
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM user_fcm_tokens WHERE user_id = ?").use { ps ->
                ps.setObject(1, userId)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    fun fcmRowExists(
        userId: UUID,
        token: String,
    ): Boolean =
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM user_fcm_tokens WHERE user_id = ? AND token = ?").use { ps ->
                ps.setObject(1, userId)
                ps.setString(2, token)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1) > 0
                }
            }
        }

    fun refreshCounts(userId: UUID): Pair<Int, Int> =
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*), COUNT(revoked_at) FROM refresh_tokens WHERE user_id = ?",
            ).use { ps ->
                ps.setObject(1, userId)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1) to rs.getInt(2)
                }
            }
        }

    fun tokenVersion(userId: UUID): Int =
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT token_version FROM users WHERE id = ?").use { ps ->
                ps.setObject(1, userId)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    suspend fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) {
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                install(Authentication) { configureUserJwt(keys, users, nowProvider = Instant::now) }
                authRoutes(
                    Providers(google = UnusedVerifier, apple = UnusedVerifier),
                    users,
                    refreshService,
                    jwtIssuer,
                    LoginEventRecorder(InMemoryLoginEvents()),
                    AuthRateLimiter(NoOpRateLimiter()),
                    TransactionalLogoutService(
                        dataSource,
                        refreshRepo,
                        users,
                        refreshService,
                        fcmRepo,
                        Dispatchers.IO,
                    ),
                )
            }
            block()
        }
    }

    "logout with fcm_token deletes that row, keeps the other, and does not bump token_version" {
        val (userId, access) = seedUser()
        try {
            fcmRepo.upsert(userId, "android", "fcm-tok-A", null)
            fcmRepo.upsert(userId, "android", "fcm-tok-B", null)
            val thisDevice = refreshService.issue(userId, null)
            val otherDevice = refreshService.issue(userId, null)

            withApp {
                val client = createClient { install(ClientCN) { json() } }
                val response =
                    client.post("/api/v1/auth/logout") {
                        bearerAuth(access)
                        contentType(ContentType.Application.Json)
                        setBody(LogoutRequest(refreshToken = thisDevice.rawToken, fcmToken = "fcm-tok-A"))
                    }
                response.status shouldBe HttpStatusCode.NoContent
            }

            fcmRowExists(userId, "fcm-tok-A") shouldBe false
            fcmRowExists(userId, "fcm-tok-B") shouldBe true
            // The supplied token is revoked; the other device's token remains active.
            refreshCounts(userId) shouldBe (2 to 1)
            // Spec'd deferral: single-device logout does NOT bump token_version.
            tokenVersion(userId) shouldBe 0
            otherDevice.row.revokedAt shouldBe null
        } finally {
            cleanup(userId)
        }
    }

    "logout with a stale refresh token still deletes the fcm row and returns 204" {
        val (userId, access) = seedUser()
        try {
            fcmRepo.upsert(userId, "ios", "fcm-tok-stale", null)

            withApp {
                val client = createClient { install(ClientCN) { json() } }
                val response =
                    client.post("/api/v1/auth/logout") {
                        bearerAuth(access)
                        contentType(ContentType.Application.Json)
                        setBody(LogoutRequest(refreshToken = "rotated-away-not-in-db", fcmToken = "fcm-tok-stale"))
                    }
                response.status shouldBe HttpStatusCode.NoContent
            }

            fcmRowExists(userId, "fcm-tok-stale") shouldBe false
        } finally {
            cleanup(userId)
        }
    }

    "logout without fcm_token revokes the refresh token and leaves fcm rows unchanged" {
        val (userId, access) = seedUser()
        try {
            fcmRepo.upsert(userId, "android", "fcm-tok-keep", null)
            val issued = refreshService.issue(userId, null)

            withApp {
                val client = createClient { install(ClientCN) { json() } }
                val response =
                    client.post("/api/v1/auth/logout") {
                        bearerAuth(access)
                        contentType(ContentType.Application.Json)
                        setBody(LogoutRequest(refreshToken = issued.rawToken))
                    }
                response.status shouldBe HttpStatusCode.NoContent
            }

            fcmTokenCount(userId) shouldBe 1
            refreshCounts(userId) shouldBe (1 to 1)
        } finally {
            cleanup(userId)
        }
    }

    "logout-all deletes every refresh token, bumps token_version, and deletes all fcm rows" {
        val (userId, access) = seedUser()
        try {
            fcmRepo.upsert(userId, "android", "fcm-all-1", null)
            fcmRepo.upsert(userId, "ios", "fcm-all-2", null)
            refreshService.issue(userId, null)
            refreshService.issue(userId, null)

            withApp {
                val client = createClient { install(ClientCN) { json() } }
                val response =
                    client.post("/api/v1/auth/logout-all") {
                        bearerAuth(access)
                    }
                response.status shouldBe HttpStatusCode.NoContent
            }

            refreshCounts(userId) shouldBe (0 to 0)
            tokenVersion(userId) shouldBe 1
            fcmTokenCount(userId) shouldBe 0
        } finally {
            cleanup(userId)
        }
    }
})
