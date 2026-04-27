package id.nearyou.app.user

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.auth.configureUserJwt
import id.nearyou.app.auth.jwt.JwtIssuer
import id.nearyou.app.auth.jwt.RsaKeyLoader
import id.nearyou.app.auth.jwt.TestKeys
import id.nearyou.app.infra.repo.JdbcUserRepository
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
import io.ktor.server.auth.Authentication
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.sql.Date
import java.time.LocalDate
import java.util.UUID
import ch.qos.logback.classic.Logger as LogbackLogger
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
            maximumPoolSize = 4
            initializationFailTimeout = -1
        }
    return HikariDataSource(config)
}

@Tags("database")
class FcmTokenRoutesTest : StringSpec({

    val dataSource = hikari()
    val keys = RsaKeyLoader(TestKeys.freshEncodedPemPrivateKey(), kid = "test-fcm")
    val jwtIssuer = JwtIssuer(keys)
    val users = JdbcUserRepository(dataSource)
    val repository = FcmTokenRepository(dataSource)

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
                ps.setString(2, "fr_$short")
                ps.setString(3, "FCM Routes Tester")
                ps.setDate(4, Date.valueOf(LocalDate.of(1990, 1, 1)))
                ps.setString(5, "g${short.take(7)}")
                ps.executeUpdate()
            }
        }
        val token = jwtIssuer.issueAccessToken(id, tokenVersion = 0)
        return id to token
    }

    fun cleanup(vararg ids: UUID) {
        dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                ids.forEach {
                    st.executeUpdate("DELETE FROM users WHERE id = '$it'")
                }
            }
        }
    }

    fun countTokens(userId: UUID): Int =
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM user_fcm_tokens WHERE user_id = ?").use { ps ->
                ps.setObject(1, userId)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    fun countTokensForToken(token: String): Int =
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM user_fcm_tokens WHERE token = ?").use { ps ->
                ps.setString(1, token)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    fun selectTokenRow(
        userId: UUID,
        token: String,
    ): TokenRow? =
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT user_id, platform, token, app_version, created_at, last_seen_at
                  FROM user_fcm_tokens
                 WHERE user_id = ? AND token = ?
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, userId)
                ps.setString(2, token)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) {
                        null
                    } else {
                        TokenRow(
                            userId = rs.getObject("user_id", UUID::class.java),
                            platform = rs.getString("platform"),
                            token = rs.getString("token"),
                            appVersion = rs.getString("app_version"),
                            createdAt = rs.getTimestamp("created_at").toInstant(),
                            lastSeenAt = rs.getTimestamp("last_seen_at").toInstant(),
                        )
                    }
                }
            }
        }

    suspend fun withFcm(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) {
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
                install(Authentication) { configureUserJwt(keys, users, java.time.Instant::now) }
                fcmTokenRoutes(repository)
            }
            block()
        }
    }

    suspend fun withLogCapture(block: suspend (ListAppender<ILoggingEvent>) -> Unit) {
        val logger = LoggerFactory.getLogger("id.nearyou.app.user.FcmTokenRoutes") as LogbackLogger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        val previousLevel = logger.level
        logger.level = Level.INFO
        try {
            block(appender)
        } finally {
            logger.detachAppender(appender)
            logger.level = previousLevel
            appender.stop()
        }
    }

    fun ListAppender<ILoggingEvent>.fcmInfoLines(): List<String> =
        list.filter { it.level == Level.INFO }
            .map { it.formattedMessage }
            .filter { it.contains("event=fcm_token_registered") }

    "6.2 happy-path new registration → 204 + row exists with created_at ≈ last_seen_at" {
        val (uid, jwt) = seedUser()
        try {
            withFcm {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .post("/api/v1/user/fcm-token") {
                            header(HttpHeaders.Authorization, "Bearer $jwt")
                            contentType(ContentType.Application.Json)
                            setBody("""{"token":"happy-tok","platform":"android","app_version":"0.1.0"}""")
                        }
                resp.status shouldBe HttpStatusCode.NoContent
            }
            val row = selectTokenRow(uid, "happy-tok")!!
            row.platform shouldBe "android"
            row.appVersion shouldBe "0.1.0"
            // created_at and last_seen_at are set in the same statement → equal.
            row.createdAt shouldBe row.lastSeenAt
        } finally {
            cleanup(uid)
        }
    }

    "6.3 re-registration of same triple → 204 + 1 row + last_seen_at > created_at + new app_version" {
        val (uid, jwt) = seedUser()
        try {
            withFcm {
                val client = createClient { install(ClientCN) { json() } }
                client.post("/api/v1/user/fcm-token") {
                    header(HttpHeaders.Authorization, "Bearer $jwt")
                    contentType(ContentType.Application.Json)
                    setBody("""{"token":"refresh-tok","platform":"android","app_version":"0.1.0"}""")
                }.status shouldBe HttpStatusCode.NoContent
                Thread.sleep(20)
                client.post("/api/v1/user/fcm-token") {
                    header(HttpHeaders.Authorization, "Bearer $jwt")
                    contentType(ContentType.Application.Json)
                    setBody("""{"token":"refresh-tok","platform":"android","app_version":"0.1.1"}""")
                }.status shouldBe HttpStatusCode.NoContent
            }
            countTokens(uid) shouldBe 1
            val row = selectTokenRow(uid, "refresh-tok")!!
            row.appVersion shouldBe "0.1.1"
            (row.lastSeenAt > row.createdAt) shouldBe true
        } finally {
            cleanup(uid)
        }
    }

    "6.4 same user registers Android + iOS → 2 rows" {
        val (uid, jwt) = seedUser()
        try {
            withFcm {
                val client = createClient { install(ClientCN) { json() } }
                client.post("/api/v1/user/fcm-token") {
                    header(HttpHeaders.Authorization, "Bearer $jwt")
                    contentType(ContentType.Application.Json)
                    setBody("""{"token":"a-tok","platform":"android"}""")
                }.status shouldBe HttpStatusCode.NoContent
                client.post("/api/v1/user/fcm-token") {
                    header(HttpHeaders.Authorization, "Bearer $jwt")
                    contentType(ContentType.Application.Json)
                    setBody("""{"token":"i-tok","platform":"ios"}""")
                }.status shouldBe HttpStatusCode.NoContent
            }
            countTokens(uid) shouldBe 2
        } finally {
            cleanup(uid)
        }
    }

    "6.5 same token registered by two different users → both 204 + 2 rows" {
        val (uA, jA) = seedUser()
        val (uB, jB) = seedUser()
        try {
            withFcm {
                val client = createClient { install(ClientCN) { json() } }
                client.post("/api/v1/user/fcm-token") {
                    header(HttpHeaders.Authorization, "Bearer $jA")
                    contentType(ContentType.Application.Json)
                    setBody("""{"token":"shared-tok","platform":"android"}""")
                }.status shouldBe HttpStatusCode.NoContent
                client.post("/api/v1/user/fcm-token") {
                    header(HttpHeaders.Authorization, "Bearer $jB")
                    contentType(ContentType.Application.Json)
                    setBody("""{"token":"shared-tok","platform":"android"}""")
                }.status shouldBe HttpStatusCode.NoContent
            }
            countTokensForToken("shared-tok") shouldBe 2
        } finally {
            cleanup(uA, uB)
        }
    }

    "6.6 platform=web → 400 invalid_platform + zero rows" {
        val (uid, jwt) = seedUser()
        try {
            withFcm {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .post("/api/v1/user/fcm-token") {
                            header(HttpHeaders.Authorization, "Bearer $jwt")
                            contentType(ContentType.Application.Json)
                            setBody("""{"token":"x","platform":"web"}""")
                        }
                resp.status shouldBe HttpStatusCode.BadRequest
                Json.parseToJsonElement(resp.bodyAsText())
                    .jsonObject["error"]!!.jsonPrimitive.content shouldBe "invalid_platform"
            }
            countTokens(uid) shouldBe 0
        } finally {
            cleanup(uid)
        }
    }

    "6.7 platform=Android (mixed case) → 400 invalid_platform + zero rows" {
        val (uid, jwt) = seedUser()
        try {
            withFcm {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .post("/api/v1/user/fcm-token") {
                            header(HttpHeaders.Authorization, "Bearer $jwt")
                            contentType(ContentType.Application.Json)
                            setBody("""{"token":"x","platform":"Android"}""")
                        }
                resp.status shouldBe HttpStatusCode.BadRequest
                Json.parseToJsonElement(resp.bodyAsText())
                    .jsonObject["error"]!!.jsonPrimitive.content shouldBe "invalid_platform"
            }
            countTokens(uid) shouldBe 0
        } finally {
            cleanup(uid)
        }
    }

    "6.8 missing platform → 400 malformed_body + zero rows" {
        val (uid, jwt) = seedUser()
        try {
            withFcm {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .post("/api/v1/user/fcm-token") {
                            header(HttpHeaders.Authorization, "Bearer $jwt")
                            contentType(ContentType.Application.Json)
                            setBody("""{"token":"x"}""")
                        }
                resp.status shouldBe HttpStatusCode.BadRequest
                Json.parseToJsonElement(resp.bodyAsText())
                    .jsonObject["error"]!!.jsonPrimitive.content shouldBe "malformed_body"
            }
            countTokens(uid) shouldBe 0
        } finally {
            cleanup(uid)
        }
    }

    "6.9 empty token → 400 empty_token + zero rows" {
        val (uid, jwt) = seedUser()
        try {
            withFcm {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .post("/api/v1/user/fcm-token") {
                            header(HttpHeaders.Authorization, "Bearer $jwt")
                            contentType(ContentType.Application.Json)
                            setBody("""{"token":"","platform":"android"}""")
                        }
                resp.status shouldBe HttpStatusCode.BadRequest
                Json.parseToJsonElement(resp.bodyAsText())
                    .jsonObject["error"]!!.jsonPrimitive.content shouldBe "empty_token"
            }
            countTokens(uid) shouldBe 0
        } finally {
            cleanup(uid)
        }
    }

    "6.10 whitespace-only token → 400 empty_token + zero rows" {
        val (uid, jwt) = seedUser()
        try {
            withFcm {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .post("/api/v1/user/fcm-token") {
                            header(HttpHeaders.Authorization, "Bearer $jwt")
                            contentType(ContentType.Application.Json)
                            setBody("""{"token":"   ","platform":"android"}""")
                        }
                resp.status shouldBe HttpStatusCode.BadRequest
                Json.parseToJsonElement(resp.bodyAsText())
                    .jsonObject["error"]!!.jsonPrimitive.content shouldBe "empty_token"
            }
            countTokens(uid) shouldBe 0
        } finally {
            cleanup(uid)
        }
    }

    "6.11 missing token field → 400 malformed_body + zero rows" {
        val (uid, jwt) = seedUser()
        try {
            withFcm {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .post("/api/v1/user/fcm-token") {
                            header(HttpHeaders.Authorization, "Bearer $jwt")
                            contentType(ContentType.Application.Json)
                            setBody("""{"platform":"android"}""")
                        }
                resp.status shouldBe HttpStatusCode.BadRequest
                Json.parseToJsonElement(resp.bodyAsText())
                    .jsonObject["error"]!!.jsonPrimitive.content shouldBe "malformed_body"
            }
            countTokens(uid) shouldBe 0
        } finally {
            cleanup(uid)
        }
    }

    "6.12 app_version 65 chars → 400 app_version_too_long + zero rows" {
        val (uid, jwt) = seedUser()
        try {
            val long = "v".repeat(65)
            withFcm {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .post("/api/v1/user/fcm-token") {
                            header(HttpHeaders.Authorization, "Bearer $jwt")
                            contentType(ContentType.Application.Json)
                            setBody("""{"token":"x","platform":"android","app_version":"$long"}""")
                        }
                resp.status shouldBe HttpStatusCode.BadRequest
                Json.parseToJsonElement(resp.bodyAsText())
                    .jsonObject["error"]!!.jsonPrimitive.content shouldBe "app_version_too_long"
            }
            countTokens(uid) shouldBe 0
        } finally {
            cleanup(uid)
        }
    }

    "6.13 malformed JSON → 400 malformed_body + zero rows" {
        val (uid, jwt) = seedUser()
        try {
            withFcm {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .post("/api/v1/user/fcm-token") {
                            header(HttpHeaders.Authorization, "Bearer $jwt")
                            contentType(ContentType.Application.Json)
                            setBody("""{token: "abc", platform: "android"}""")
                        }
                resp.status shouldBe HttpStatusCode.BadRequest
                Json.parseToJsonElement(resp.bodyAsText())
                    .jsonObject["error"]!!.jsonPrimitive.content shouldBe "malformed_body"
            }
            countTokens(uid) shouldBe 0
        } finally {
            cleanup(uid)
        }
    }

    "6.14 missing Authorization → 401 + zero rows" {
        val (uid, _) = seedUser()
        try {
            withFcm {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .post("/api/v1/user/fcm-token") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"token":"x","platform":"android"}""")
                        }
                resp.status shouldBe HttpStatusCode.Unauthorized
            }
            countTokens(uid) shouldBe 0
        } finally {
            cleanup(uid)
        }
    }

    "6.15 invalid JWT → 401 + zero rows" {
        val (uid, _) = seedUser()
        try {
            withFcm {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .post("/api/v1/user/fcm-token") {
                            header(HttpHeaders.Authorization, "Bearer not-a-real-jwt")
                            contentType(ContentType.Application.Json)
                            setBody("""{"token":"x","platform":"android"}""")
                        }
                resp.status shouldBe HttpStatusCode.Unauthorized
            }
            countTokens(uid) shouldBe 0
        } finally {
            cleanup(uid)
        }
    }

    "6.16 user_id field in body is ignored — JWT principal wins" {
        val (uAuth, jAuth) = seedUser()
        val (uOther, _) = seedUser()
        try {
            withFcm {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .post("/api/v1/user/fcm-token") {
                            header(HttpHeaders.Authorization, "Bearer $jAuth")
                            contentType(ContentType.Application.Json)
                            setBody("""{"user_id":"$uOther","token":"abc","platform":"android"}""")
                        }
                resp.status shouldBe HttpStatusCode.NoContent
            }
            countTokens(uAuth) shouldBe 1
            countTokens(uOther) shouldBe 0
        } finally {
            cleanup(uAuth, uOther)
        }
    }

    "6.17 4097-byte body → 413 + zero rows" {
        val (uid, jwt) = seedUser()
        try {
            // Pad an otherwise-valid JSON body's `token` field so the total body is > 4096 bytes.
            val pad = "a".repeat(4096)
            val body = """{"token":"$pad","platform":"android"}"""
            (body.toByteArray().size > 4096) shouldBe true
            withFcm {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .post("/api/v1/user/fcm-token") {
                            header(HttpHeaders.Authorization, "Bearer $jwt")
                            contentType(ContentType.Application.Json)
                            setBody(body)
                        }
                resp.status shouldBe HttpStatusCode.PayloadTooLarge
            }
            countTokens(uid) shouldBe 0
        } finally {
            cleanup(uid)
        }
    }

    "6.18 ON DELETE CASCADE — deleting user removes token rows" {
        val (uid, jwt) = seedUser()
        withFcm {
            createClient { install(ClientCN) { json() } }
                .post("/api/v1/user/fcm-token") {
                    header(HttpHeaders.Authorization, "Bearer $jwt")
                    contentType(ContentType.Application.Json)
                    setBody("""{"token":"cascade-tok","platform":"android"}""")
                }.status shouldBe HttpStatusCode.NoContent
        }
        countTokens(uid) shouldBe 1
        cleanup(uid)
        countTokens(uid) shouldBe 0
    }

    "6.19 INFO log includes event, user_id, platform, created, user_token_count" {
        val (uid, jwt) = seedUser()
        try {
            withLogCapture { appender ->
                withFcm {
                    createClient { install(ClientCN) { json() } }
                        .post("/api/v1/user/fcm-token") {
                            header(HttpHeaders.Authorization, "Bearer $jwt")
                            contentType(ContentType.Application.Json)
                            setBody("""{"token":"log-tok","platform":"android"}""")
                        }.status shouldBe HttpStatusCode.NoContent
                }
                val lines = appender.fcmInfoLines()
                lines.size shouldBe 1
                val msg = lines.single()
                msg.shouldContain("event=fcm_token_registered")
                msg.shouldContain("user_id=$uid")
                msg.shouldContain("platform=android")
                msg.shouldContain("created=true")
                msg.shouldContain("user_token_count=1")
            }
        } finally {
            cleanup(uid)
        }
    }

    "6.20 WARN/INFO logs do NOT include the raw token value or the JWT" {
        val (uid, jwt) = seedUser()
        val probeToken = "REDACTION_PROBE_TOKEN_xyzzy123"
        try {
            withLogCapture { appender ->
                withFcm {
                    val client = createClient { install(ClientCN) { json() } }
                    // Happy-path INFO log MUST NOT include the raw token.
                    client.post("/api/v1/user/fcm-token") {
                        header(HttpHeaders.Authorization, "Bearer $jwt")
                        contentType(ContentType.Application.Json)
                        setBody("""{"token":"$probeToken","platform":"android"}""")
                    }.status shouldBe HttpStatusCode.NoContent
                }
                val all = appender.list.joinToString("\n") { it.formattedMessage }
                all.contains(probeToken) shouldBe false
                all.contains(jwt) shouldBe false
            }
        } finally {
            cleanup(uid)
        }
    }

    "6.21 user_token_count grows as tokens accumulate, refresh holds steady" {
        val (uid, jwt) = seedUser()
        try {
            withLogCapture { appender ->
                withFcm {
                    val client = createClient { install(ClientCN) { json() } }
                    client.post("/api/v1/user/fcm-token") {
                        header(HttpHeaders.Authorization, "Bearer $jwt")
                        contentType(ContentType.Application.Json)
                        setBody("""{"token":"tok-A","platform":"android"}""")
                    }.status shouldBe HttpStatusCode.NoContent
                    client.post("/api/v1/user/fcm-token") {
                        header(HttpHeaders.Authorization, "Bearer $jwt")
                        contentType(ContentType.Application.Json)
                        setBody("""{"token":"tok-B","platform":"ios"}""")
                    }.status shouldBe HttpStatusCode.NoContent
                    client.post("/api/v1/user/fcm-token") {
                        header(HttpHeaders.Authorization, "Bearer $jwt")
                        contentType(ContentType.Application.Json)
                        setBody("""{"token":"tok-A","platform":"android"}""")
                    }.status shouldBe HttpStatusCode.NoContent
                }
                val counts =
                    Regex("""user_token_count=(\d+)""")
                        .findAll(appender.fcmInfoLines().joinToString("\n"))
                        .map { it.groupValues[1].toInt() }
                        .toList()
                counts shouldBe listOf(1, 2, 2)
            }
        } finally {
            cleanup(uid)
        }
    }

    "6.22 created=true for new triple, false on immediate re-registration" {
        val (uid, jwt) = seedUser()
        try {
            withLogCapture { appender ->
                withFcm {
                    val client = createClient { install(ClientCN) { json() } }
                    client.post("/api/v1/user/fcm-token") {
                        header(HttpHeaders.Authorization, "Bearer $jwt")
                        contentType(ContentType.Application.Json)
                        setBody("""{"token":"created-tok","platform":"android"}""")
                    }.status shouldBe HttpStatusCode.NoContent
                    client.post("/api/v1/user/fcm-token") {
                        header(HttpHeaders.Authorization, "Bearer $jwt")
                        contentType(ContentType.Application.Json)
                        setBody("""{"token":"created-tok","platform":"android"}""")
                    }.status shouldBe HttpStatusCode.NoContent
                }
                val createds =
                    Regex("""created=(true|false)""")
                        .findAll(appender.fcmInfoLines().joinToString("\n"))
                        .map { it.groupValues[1] }
                        .toList()
                createds shouldBe listOf("true", "false")
            }
        } finally {
            cleanup(uid)
        }
    }
})

private data class TokenRow(
    val userId: UUID,
    val platform: String,
    val token: String,
    val appVersion: String?,
    val createdAt: java.time.Instant,
    val lastSeenAt: java.time.Instant,
)
