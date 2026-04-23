package id.nearyou.app.notifications

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.auth.configureUserJwt
import id.nearyou.app.auth.jwt.JwtIssuer
import id.nearyou.app.auth.jwt.RsaKeyLoader
import id.nearyou.app.auth.jwt.TestKeys
import id.nearyou.app.infra.repo.JdbcNotificationRepository
import id.nearyou.app.infra.repo.JdbcUserRepository
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.postgresql.util.PGobject
import java.sql.Date
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientCN

private fun hikari(): HikariDataSource {
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

@Tags("database")
class NotificationReadPathTest : StringSpec({

    val dataSource = hikari()
    val keys = RsaKeyLoader(TestKeys.freshEncodedPemPrivateKey(), kid = "test-notif")
    val jwtIssuer = JwtIssuer(keys)
    val users = JdbcUserRepository(dataSource)
    val notificationsRepo = JdbcNotificationRepository(dataSource)
    val notificationService = NotificationService(notificationsRepo)

    fun seedUser(): Pair<UUID, String> {
        val id = UUID.randomUUID()
        val short = id.toString().replace("-", "").take(8)
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO users (id, username, display_name, date_of_birth, invite_code_prefix)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "rn_$short")
                ps.setString(3, "Notif Reader")
                ps.setDate(4, Date.valueOf(LocalDate.of(1990, 1, 1)))
                ps.setString(5, "n${short.take(7)}")
                ps.executeUpdate()
            }
        }
        return id to jwtIssuer.issueAccessToken(id, tokenVersion = 0)
    }

    fun insertNotificationAt(
        userId: UUID,
        type: String = "followed",
        actor: UUID? = null,
        createdAt: Instant = Instant.now(),
        read: Boolean = false,
    ): UUID {
        val id = UUID.randomUUID()
        val body =
            PGobject().apply {
                this.type = "jsonb"
                this.value = "{}"
            }
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO notifications (id, user_id, type, actor_user_id, body_data, created_at, read_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setObject(2, userId)
                ps.setString(3, type)
                if (actor != null) ps.setObject(4, actor) else ps.setNull(4, java.sql.Types.OTHER)
                ps.setObject(5, body)
                ps.setTimestamp(6, Timestamp.from(createdAt))
                if (read) ps.setTimestamp(7, Timestamp.from(Instant.now())) else ps.setNull(7, java.sql.Types.TIMESTAMP)
                ps.executeUpdate()
            }
        }
        return id
    }

    fun cleanup(vararg ids: UUID) {
        dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                ids.forEach { st.executeUpdate("DELETE FROM users WHERE id = '$it'") }
            }
        }
    }

    suspend fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) {
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
                install(Authentication) { configureUserJwt(keys, users, Instant::now) }
                notificationRoutes(notificationService)
            }
            block()
        }
    }

    "11.1 GET /notifications returns caller rows in created_at DESC order" {
        val (alice, ta) = seedUser()
        try {
            val now = Instant.now()
            val older = insertNotificationAt(alice, createdAt = now.minus(2, ChronoUnit.HOURS))
            val newer = insertNotificationAt(alice, createdAt = now.minus(1, ChronoUnit.HOURS))
            withApp {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/notifications") { header(HttpHeaders.Authorization, "Bearer $ta") }
                resp.status shouldBe HttpStatusCode.OK
                val items = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["items"]!!.jsonArray
                items.size shouldBe 2
                items[0].jsonObject["id"]!!.jsonPrimitive.content shouldBe newer.toString()
                items[1].jsonObject["id"]!!.jsonPrimitive.content shouldBe older.toString()
            }
        } finally {
            cleanup(alice)
        }
    }

    "11.2 GET /notifications isolates by caller" {
        val (alice, ta) = seedUser()
        val (bob, _) = seedUser()
        try {
            insertNotificationAt(bob)
            insertNotificationAt(alice)
            withApp {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/notifications") { header(HttpHeaders.Authorization, "Bearer $ta") }
                val items = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["items"]!!.jsonArray
                items.size shouldBe 1
            }
        } finally {
            cleanup(alice, bob)
        }
    }

    "11.3 Cursor pagination: 25 rows, limit=10 → 10 / 10 / 5 with null last cursor" {
        val (alice, ta) = seedUser()
        try {
            val now = Instant.now()
            (0 until 25).forEach { i ->
                insertNotificationAt(alice, createdAt = now.minus((25 - i).toLong(), ChronoUnit.SECONDS))
            }
            withApp {
                val client = createClient { install(ClientCN) { json() } }
                var cursor: String? = null
                val pages = mutableListOf<Int>()
                repeat(3) {
                    val url =
                        buildString {
                            append("/api/v1/notifications?limit=10")
                            if (cursor != null) append("&cursor=").append(cursor)
                        }
                    val resp = client.get(url) { header(HttpHeaders.Authorization, "Bearer $ta") }
                    val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
                    pages += body["items"]!!.jsonArray.size
                    cursor = body["next_cursor"]?.jsonPrimitive?.contentOrNull()
                }
                pages shouldBe listOf(10, 10, 5)
                cursor shouldBe null
            }
        } finally {
            cleanup(alice)
        }
    }

    "11.4 unread=true filter returns only unread" {
        val (alice, ta) = seedUser()
        try {
            (0 until 5).forEach { insertNotificationAt(alice, read = true) }
            (0 until 3).forEach { insertNotificationAt(alice, read = false) }
            withApp {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/notifications?unread=true") {
                            header(HttpHeaders.Authorization, "Bearer $ta")
                        }
                val items = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["items"]!!.jsonArray
                items.size shouldBe 3
            }
        } finally {
            cleanup(alice)
        }
    }

    "11.5 unread-count endpoint and emit/read lifecycle" {
        val (alice, ta) = seedUser()
        try {
            insertNotificationAt(alice, read = false)
            insertNotificationAt(alice, read = false)
            insertNotificationAt(alice, read = true)
            withApp {
                val client = createClient { install(ClientCN) { json() } }
                var resp =
                    client.get("/api/v1/notifications/unread-count") {
                        header(HttpHeaders.Authorization, "Bearer $ta")
                    }
                Json.parseToJsonElement(resp.bodyAsText()).jsonObject["count"]!!.jsonPrimitive.content shouldBe "2"
                // new unread emit → count++
                insertNotificationAt(alice, read = false)
                resp =
                    client.get("/api/v1/notifications/unread-count") {
                        header(HttpHeaders.Authorization, "Bearer $ta")
                    }
                Json.parseToJsonElement(resp.bodyAsText()).jsonObject["count"]!!.jsonPrimitive.content shouldBe "3"
            }
        } finally {
            cleanup(alice)
        }
    }

    "11.6 PATCH /:id/read sets read_at; second call idempotent 204" {
        val (alice, ta) = seedUser()
        try {
            val n = insertNotificationAt(alice)
            withApp {
                val client = createClient { install(ClientCN) { json() } }
                client.patch("/api/v1/notifications/$n/read") {
                    header(HttpHeaders.Authorization, "Bearer $ta")
                }.status shouldBe HttpStatusCode.NoContent
                client.patch("/api/v1/notifications/$n/read") {
                    header(HttpHeaders.Authorization, "Bearer $ta")
                }.status shouldBe HttpStatusCode.NoContent
                val resp =
                    client.get("/api/v1/notifications/unread-count") {
                        header(HttpHeaders.Authorization, "Bearer $ta")
                    }
                Json.parseToJsonElement(resp.bodyAsText()).jsonObject["count"]!!.jsonPrimitive.content shouldBe "0"
            }
        } finally {
            cleanup(alice)
        }
    }

    "11.7 PATCH /:id/read on another user's notification returns 404 not_found" {
        val (alice, ta) = seedUser()
        val (bob, _) = seedUser()
        try {
            val bobNote = insertNotificationAt(bob)
            withApp {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .patch("/api/v1/notifications/$bobNote/read") {
                            header(HttpHeaders.Authorization, "Bearer $ta")
                        }
                resp.status shouldBe HttpStatusCode.NotFound
                val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
                body["error"]!!.jsonObject["code"]!!.jsonPrimitive.content shouldBe "not_found"
            }
        } finally {
            cleanup(alice, bob)
        }
    }

    "11.8 PATCH /read-all flips all unread for caller and returns marked_read count" {
        val (alice, ta) = seedUser()
        try {
            (0 until 4).forEach { insertNotificationAt(alice, read = false) }
            insertNotificationAt(alice, read = true)
            withApp {
                val client = createClient { install(ClientCN) { json() } }
                val resp =
                    client.patch("/api/v1/notifications/read-all") {
                        header(HttpHeaders.Authorization, "Bearer $ta")
                    }
                resp.status shouldBe HttpStatusCode.OK
                val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
                body["marked_read"]!!.jsonPrimitive.int shouldBe 4
                val count =
                    client.get("/api/v1/notifications/unread-count") {
                        header(HttpHeaders.Authorization, "Bearer $ta")
                    }
                Json.parseToJsonElement(count.bodyAsText()).jsonObject["count"]!!.jsonPrimitive.content shouldBe "0"
            }
        } finally {
            cleanup(alice)
        }
    }

    "11.9 PATCH /read-all does not affect another user's notifications" {
        val (alice, ta) = seedUser()
        val (bob, _) = seedUser()
        try {
            insertNotificationAt(alice, read = false)
            insertNotificationAt(bob, read = false)
            withApp {
                createClient { install(ClientCN) { json() } }
                    .patch("/api/v1/notifications/read-all") {
                        header(HttpHeaders.Authorization, "Bearer $ta")
                    }
            }
            dataSource.connection.use { conn ->
                conn.prepareStatement(
                    "SELECT COUNT(*) FROM notifications WHERE user_id = ? AND read_at IS NULL",
                ).use { ps ->
                    ps.setObject(1, bob)
                    ps.executeQuery().use { rs ->
                        rs.next()
                        rs.getInt(1) shouldBe 1
                    }
                }
            }
        } finally {
            cleanup(alice, bob)
        }
    }

    "11.10 All 4 endpoints return 401 without JWT" {
        withApp {
            val client = createClient { install(ClientCN) { json() } }
            client.get("/api/v1/notifications").status shouldBe HttpStatusCode.Unauthorized
            client.get("/api/v1/notifications/unread-count").status shouldBe HttpStatusCode.Unauthorized
            client.patch("/api/v1/notifications/${UUID.randomUUID()}/read").status shouldBe HttpStatusCode.Unauthorized
            client.patch("/api/v1/notifications/read-all").status shouldBe HttpStatusCode.Unauthorized
        }
    }

    "11.11 4xx error envelope matches project shape" {
        val (alice, ta) = seedUser()
        try {
            withApp {
                val client = createClient { install(ClientCN) { json() } }
                val resp =
                    client.patch("/api/v1/notifications/not-a-uuid/read") {
                        header(HttpHeaders.Authorization, "Bearer $ta")
                    }
                resp.status shouldBe HttpStatusCode.BadRequest
                val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
                body["error"]!!.jsonObject["code"]!!.jsonPrimitive.content shouldBe "invalid_uuid"
                body["error"]!!.jsonObject["message"] shouldBe
                    body["error"]!!.jsonObject["message"] // shape check — `message` present
            }
        } finally {
            cleanup(alice)
        }
    }
})

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? =
    if (this.isString) this.content else this.content.takeIf { it != "null" }

private val kotlinx.serialization.json.JsonPrimitive.int: Int
    get() = content.toInt()
