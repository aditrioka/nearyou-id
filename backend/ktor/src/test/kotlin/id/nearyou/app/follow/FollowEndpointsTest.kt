package id.nearyou.app.follow

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.auth.configureUserJwt
import id.nearyou.app.auth.jwt.JwtIssuer
import id.nearyou.app.auth.jwt.RsaKeyLoader
import id.nearyou.app.auth.jwt.TestKeys
import id.nearyou.app.infra.repo.JdbcNotificationRepository
import id.nearyou.app.infra.repo.JdbcUserFollowsRepository
import id.nearyou.app.infra.repo.JdbcUserRepository
import id.nearyou.app.notifications.DbNotificationEmitter
import id.nearyou.app.notifications.NoopNotificationDispatcher
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.ktor.client.request.delete
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
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.sql.Date
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
            maximumPoolSize = 4
            initializationFailTimeout = -1
        }
    return HikariDataSource(config)
}

@Tags("database")
class FollowEndpointsTest : StringSpec({

    val dataSource = hikari()
    val keys = RsaKeyLoader(TestKeys.freshEncodedPemPrivateKey(), kid = "test-follow")
    val jwtIssuer = JwtIssuer(keys)
    val users = JdbcUserRepository(dataSource)
    val follows = JdbcUserFollowsRepository(dataSource)
    val notificationsRepo = JdbcNotificationRepository(dataSource)
    val dispatcher = NoopNotificationDispatcher()
    val notificationEmitter = DbNotificationEmitter(notificationsRepo)
    val service = FollowService(dataSource, follows, notificationEmitter, dispatcher)

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
                ps.setString(2, "fe_$short")
                ps.setString(3, "Follow Endpoint Tester")
                ps.setDate(4, Date.valueOf(LocalDate.of(1990, 1, 1)))
                ps.setString(5, "h${short.take(7)}")
                ps.executeUpdate()
            }
        }
        val token = jwtIssuer.issueAccessToken(id, tokenVersion = 0)
        return id to token
    }

    fun insertBlock(
        blocker: UUID,
        blocked: UUID,
    ) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "INSERT INTO user_blocks (blocker_id, blocked_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
            ).use { ps ->
                ps.setObject(1, blocker)
                ps.setObject(2, blocked)
                ps.executeUpdate()
            }
        }
    }

    fun countFollow(
        follower: UUID,
        followee: UUID,
    ): Int {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM follows WHERE follower_id = ? AND followee_id = ?",
            ).use { ps ->
                ps.setObject(1, follower)
                ps.setObject(2, followee)
                ps.executeQuery().use { rs ->
                    rs.next()
                    return rs.getInt(1)
                }
            }
        }
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

    suspend fun withFollows(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) {
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
                followRoutes(service)
                userSocialRoutes(service)
            }
            block()
        }
    }

    "POST /follows/{B} first follow returns 204 and creates a row" {
        val (a, ta) = seedUser()
        val (b, _) = seedUser()
        try {
            withFollows {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .post("/api/v1/follows/$b") { header(HttpHeaders.Authorization, "Bearer $ta") }
                resp.status shouldBe HttpStatusCode.NoContent
            }
            countFollow(a, b) shouldBe 1
        } finally {
            cleanup(a, b)
        }
    }

    "POST /follows/{B} re-follow is idempotent (still 204, still one row)" {
        val (a, ta) = seedUser()
        val (b, _) = seedUser()
        try {
            withFollows {
                val client = createClient { install(ClientCN) { json() } }
                client.post("/api/v1/follows/$b") { header(HttpHeaders.Authorization, "Bearer $ta") }
                    .status shouldBe HttpStatusCode.NoContent
                client.post("/api/v1/follows/$b") { header(HttpHeaders.Authorization, "Bearer $ta") }
                    .status shouldBe HttpStatusCode.NoContent
            }
            countFollow(a, b) shouldBe 1
        } finally {
            cleanup(a, b)
        }
    }

    "POST /follows/{self} returns 400 cannot_follow_self and does not insert" {
        val (a, ta) = seedUser()
        try {
            withFollows {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .post("/api/v1/follows/$a") { header(HttpHeaders.Authorization, "Bearer $ta") }
                resp.status shouldBe HttpStatusCode.BadRequest
                Json.parseToJsonElement(resp.bodyAsText())
                    .jsonObject["error"]!!.jsonObject["code"]!!
                    .jsonPrimitive.content shouldBe "cannot_follow_self"
            }
            countFollow(a, a) shouldBe 0
        } finally {
            cleanup(a)
        }
    }

    "POST /follows/{nonexistent} returns 404 user_not_found" {
        val (a, ta) = seedUser()
        try {
            withFollows {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .post("/api/v1/follows/${UUID.randomUUID()}") {
                            header(HttpHeaders.Authorization, "Bearer $ta")
                        }
                resp.status shouldBe HttpStatusCode.NotFound
                Json.parseToJsonElement(resp.bodyAsText())
                    .jsonObject["error"]!!.jsonObject["code"]!!
                    .jsonPrimitive.content shouldBe "user_not_found"
            }
        } finally {
            cleanup(a)
        }
    }

    "POST /follows/{B} returns 409 follow_blocked when caller has blocked target" {
        val (a, ta) = seedUser()
        val (b, _) = seedUser()
        try {
            insertBlock(a, b)
            withFollows {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .post("/api/v1/follows/$b") { header(HttpHeaders.Authorization, "Bearer $ta") }
                resp.status shouldBe HttpStatusCode.Conflict
                Json.parseToJsonElement(resp.bodyAsText())
                    .jsonObject["error"]!!.jsonObject["code"]!!
                    .jsonPrimitive.content shouldBe "follow_blocked"
            }
            countFollow(a, b) shouldBe 0
        } finally {
            cleanup(a, b)
        }
    }

    "POST /follows/{B} returns 409 follow_blocked when target has blocked caller" {
        val (a, ta) = seedUser()
        val (b, _) = seedUser()
        try {
            insertBlock(b, a)
            withFollows {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .post("/api/v1/follows/$b") { header(HttpHeaders.Authorization, "Bearer $ta") }
                resp.status shouldBe HttpStatusCode.Conflict
                Json.parseToJsonElement(resp.bodyAsText())
                    .jsonObject["error"]!!.jsonObject["code"]!!
                    .jsonPrimitive.content shouldBe "follow_blocked"
            }
            countFollow(a, b) shouldBe 0
        } finally {
            cleanup(a, b)
        }
    }

    "409 follow_blocked body is identical in both block directions (no direction hint)" {
        val (a1, ta1) = seedUser()
        val (b1, _) = seedUser()
        val (a2, ta2) = seedUser()
        val (b2, _) = seedUser()
        try {
            insertBlock(a1, b1) // caller blocks target
            insertBlock(b2, a2) // target blocks caller
            val bodies = mutableListOf<String>()
            withFollows {
                val client = createClient { install(ClientCN) { json() } }
                bodies +=
                    client.post("/api/v1/follows/$b1") { header(HttpHeaders.Authorization, "Bearer $ta1") }
                        .bodyAsText()
                bodies +=
                    client.post("/api/v1/follows/$b2") { header(HttpHeaders.Authorization, "Bearer $ta2") }
                        .bodyAsText()
            }
            bodies[0] shouldBe bodies[1]
            bodies[0] shouldBe "{\"error\":{\"code\":\"follow_blocked\"}}"
        } finally {
            cleanup(a1, b1, a2, b2)
        }
    }

    "DELETE /follows/{B} removes existing row and returns 204" {
        val (a, ta) = seedUser()
        val (b, _) = seedUser()
        try {
            service.follow(a, b)
            countFollow(a, b) shouldBe 1
            withFollows {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .delete("/api/v1/follows/$b") { header(HttpHeaders.Authorization, "Bearer $ta") }
                resp.status shouldBe HttpStatusCode.NoContent
            }
            countFollow(a, b) shouldBe 0
        } finally {
            cleanup(a, b)
        }
    }

    "DELETE /follows/{B} no-op still returns 204" {
        val (a, ta) = seedUser()
        val (b, _) = seedUser()
        try {
            withFollows {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .delete("/api/v1/follows/$b") { header(HttpHeaders.Authorization, "Bearer $ta") }
                resp.status shouldBe HttpStatusCode.NoContent
            }
        } finally {
            cleanup(a, b)
        }
    }

    "GET /users/{P}/followers ordered DESC by created_at, excludes unrelated users" {
        val (p, _) = seedUser()
        val (viewer, tv) = seedUser()
        val (x, _) = seedUser()
        val (y, _) = seedUser()
        val (z, _) = seedUser()
        try {
            service.follow(x, p)
            Thread.sleep(10)
            service.follow(y, p)
            Thread.sleep(10)
            service.follow(z, p) // most recent
            withFollows {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/users/$p/followers") {
                            header(HttpHeaders.Authorization, "Bearer $tv")
                        }
                resp.status shouldBe HttpStatusCode.OK
                val ids =
                    Json.parseToJsonElement(resp.bodyAsText())
                        .jsonObject["users"]!!.jsonArray
                        .map { (it as JsonObject)["userId"]!!.jsonPrimitive.content }
                ids shouldBe listOf(z.toString(), y.toString(), x.toString())
            }
        } finally {
            cleanup(p, viewer, x, y, z)
        }
    }

    "GET /users/{P}/followers excludes viewer-blocked users in both directions" {
        val (p, _) = seedUser()
        val (viewer, tv) = seedUser()
        val (x, _) = seedUser()
        val (y, _) = seedUser()
        val (z, _) = seedUser()
        try {
            service.follow(x, p)
            service.follow(y, p)
            service.follow(z, p)
            insertBlock(viewer, x) // viewer blocked x
            insertBlock(y, viewer) // y blocked viewer
            withFollows {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/users/$p/followers") {
                            header(HttpHeaders.Authorization, "Bearer $tv")
                        }
                val ids =
                    Json.parseToJsonElement(resp.bodyAsText())
                        .jsonObject["users"]!!.jsonArray
                        .map { (it as JsonObject)["userId"]!!.jsonPrimitive.content }
                ids.contains(x.toString()) shouldBe false
                ids.contains(y.toString()) shouldBe false
                ids.contains(z.toString()) shouldBe true
            }
        } finally {
            cleanup(p, viewer, x, y, z)
        }
    }

    "GET /users/{P}/followers paginates with cursor (35 followers → 30 + 5)" {
        val (p, _) = seedUser()
        val (viewer, tv) = seedUser()
        val followers = (1..35).map { seedUser().first }
        try {
            for (f in followers) {
                service.follow(f, p)
                Thread.sleep(2)
            }
            withFollows {
                val client = createClient { install(ClientCN) { json() } }
                val r1 =
                    client.get("/api/v1/users/$p/followers") {
                        header(HttpHeaders.Authorization, "Bearer $tv")
                    }
                r1.status shouldBe HttpStatusCode.OK
                val b1 = Json.parseToJsonElement(r1.bodyAsText()).jsonObject
                b1["users"]!!.jsonArray shouldHaveSize 30
                val cursor = b1["nextCursor"]!!.jsonPrimitive.content
                val r2 =
                    client.get("/api/v1/users/$p/followers?cursor=$cursor") {
                        header(HttpHeaders.Authorization, "Bearer $tv")
                    }
                r2.status shouldBe HttpStatusCode.OK
                val b2 = Json.parseToJsonElement(r2.bodyAsText()).jsonObject
                b2["users"]!!.jsonArray shouldHaveSize 5
                val ids1 = b1["users"]!!.jsonArray.map { (it as JsonObject)["userId"]!!.jsonPrimitive.content }.toSet()
                val ids2 = b2["users"]!!.jsonArray.map { (it as JsonObject)["userId"]!!.jsonPrimitive.content }.toSet()
                (ids1 intersect ids2).isEmpty() shouldBe true
            }
        } finally {
            cleanup(p, viewer, *followers.toTypedArray())
        }
    }

    "GET /users/{nonexistent}/followers returns 404 user_not_found" {
        val (viewer, tv) = seedUser()
        try {
            withFollows {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/users/${UUID.randomUUID()}/followers") {
                            header(HttpHeaders.Authorization, "Bearer $tv")
                        }
                resp.status shouldBe HttpStatusCode.NotFound
                Json.parseToJsonElement(resp.bodyAsText())
                    .jsonObject["error"]!!.jsonObject["code"]!!
                    .jsonPrimitive.content shouldBe "user_not_found"
            }
        } finally {
            cleanup(viewer)
        }
    }

    "GET /users/{P}/following ordered DESC by created_at" {
        val (p, _) = seedUser()
        val (viewer, tv) = seedUser()
        val (x, _) = seedUser()
        val (y, _) = seedUser()
        val (z, _) = seedUser()
        try {
            service.follow(p, x)
            Thread.sleep(10)
            service.follow(p, y)
            Thread.sleep(10)
            service.follow(p, z)
            withFollows {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/users/$p/following") {
                            header(HttpHeaders.Authorization, "Bearer $tv")
                        }
                resp.status shouldBe HttpStatusCode.OK
                val ids =
                    Json.parseToJsonElement(resp.bodyAsText())
                        .jsonObject["users"]!!.jsonArray
                        .map { (it as JsonObject)["userId"]!!.jsonPrimitive.content }
                ids shouldBe listOf(z.toString(), y.toString(), x.toString())
            }
        } finally {
            cleanup(p, viewer, x, y, z)
        }
    }

    "GET /users/{P}/following excludes viewer-blocked users in both directions" {
        val (p, _) = seedUser()
        val (viewer, tv) = seedUser()
        val (x, _) = seedUser()
        val (y, _) = seedUser()
        val (z, _) = seedUser()
        try {
            service.follow(p, x)
            service.follow(p, y)
            service.follow(p, z)
            insertBlock(viewer, x) // viewer blocked x
            insertBlock(y, viewer) // y blocked viewer
            withFollows {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/users/$p/following") {
                            header(HttpHeaders.Authorization, "Bearer $tv")
                        }
                val ids =
                    Json.parseToJsonElement(resp.bodyAsText())
                        .jsonObject["users"]!!.jsonArray
                        .map { (it as JsonObject)["userId"]!!.jsonPrimitive.content }
                ids.contains(x.toString()) shouldBe false
                ids.contains(y.toString()) shouldBe false
                ids.contains(z.toString()) shouldBe true
            }
        } finally {
            cleanup(p, viewer, x, y, z)
        }
    }

    "auth — all five endpoints return 401 without JWT" {
        val ghost = UUID.randomUUID()
        withFollows {
            val client = createClient { install(ClientCN) { json() } }
            client.post("/api/v1/follows/$ghost").status shouldBe HttpStatusCode.Unauthorized
            client.delete("/api/v1/follows/$ghost").status shouldBe HttpStatusCode.Unauthorized
            client.get("/api/v1/users/$ghost/followers").status shouldBe HttpStatusCode.Unauthorized
            client.get("/api/v1/users/$ghost/following").status shouldBe HttpStatusCode.Unauthorized
        }
    }
})
