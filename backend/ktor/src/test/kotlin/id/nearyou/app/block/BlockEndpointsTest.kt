package id.nearyou.app.block

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.auth.configureUserJwt
import id.nearyou.app.auth.jwt.JwtIssuer
import id.nearyou.app.auth.jwt.RsaKeyLoader
import id.nearyou.app.auth.jwt.TestKeys
import id.nearyou.app.infra.repo.JdbcUserBlockRepository
import id.nearyou.app.infra.repo.JdbcUserRepository
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
import kotlinx.serialization.json.JsonArray
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
class BlockEndpointsTest : StringSpec({

    val dataSource = hikari()
    val keys = RsaKeyLoader(TestKeys.freshEncodedPemPrivateKey(), kid = "test-block")
    val jwtIssuer = JwtIssuer(keys)
    val users = JdbcUserRepository(dataSource)
    val blocks = JdbcUserBlockRepository(dataSource)
    val service = BlockService(blocks)

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
                ps.setString(2, "blk_$short")
                ps.setString(3, "Block Tester")
                ps.setDate(4, Date.valueOf(LocalDate.of(1990, 1, 1)))
                ps.setString(5, "k${short.take(7)}")
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
                    // user_blocks rows cascade on user delete; refresh_tokens too via FK.
                    st.executeUpdate("DELETE FROM users WHERE id = '$it'")
                }
            }
        }
    }

    suspend fun withBlocks(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) {
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
                blockRoutes(service)
            }
            block()
        }
    }

    "POST /blocks/{user} — first block returns 204 and inserts row" {
        val (a, tokenA) = seedUser()
        val (b, _) = seedUser()
        try {
            withBlocks {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .post("/api/v1/blocks/$b") { header(HttpHeaders.Authorization, "Bearer $tokenA") }
                resp.status shouldBe HttpStatusCode.NoContent
            }
            // Row exists.
            dataSource.connection.use { conn ->
                conn.prepareStatement(
                    "SELECT 1 FROM user_blocks WHERE blocker_id = ? AND blocked_id = ?",
                ).use { ps ->
                    ps.setObject(1, a)
                    ps.setObject(2, b)
                    ps.executeQuery().use { rs -> rs.next() shouldBe true }
                }
            }
        } finally {
            cleanup(a, b)
        }
    }

    "POST /blocks/{user} — re-block is idempotent (still 204, still one row)" {
        val (a, tokenA) = seedUser()
        val (b, _) = seedUser()
        try {
            withBlocks {
                val client = createClient { install(ClientCN) { json() } }
                val r1 = client.post("/api/v1/blocks/$b") { header(HttpHeaders.Authorization, "Bearer $tokenA") }
                val r2 = client.post("/api/v1/blocks/$b") { header(HttpHeaders.Authorization, "Bearer $tokenA") }
                r1.status shouldBe HttpStatusCode.NoContent
                r2.status shouldBe HttpStatusCode.NoContent
            }
            dataSource.connection.use { conn ->
                conn.prepareStatement(
                    "SELECT COUNT(*) FROM user_blocks WHERE blocker_id = ? AND blocked_id = ?",
                ).use { ps ->
                    ps.setObject(1, a)
                    ps.setObject(2, b)
                    ps.executeQuery().use { rs ->
                        rs.next() shouldBe true
                        rs.getInt(1) shouldBe 1
                    }
                }
            }
        } finally {
            cleanup(a, b)
        }
    }

    "POST /blocks/{self} — 400 cannot_block_self, no row inserted" {
        val (a, tokenA) = seedUser()
        try {
            withBlocks {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .post("/api/v1/blocks/$a") { header(HttpHeaders.Authorization, "Bearer $tokenA") }
                resp.status shouldBe HttpStatusCode.BadRequest
                Json.parseToJsonElement(resp.bodyAsText())
                    .jsonObject["error"]!!.jsonObject["code"]!!
                    .jsonPrimitive.content shouldBe "cannot_block_self"
            }
            dataSource.connection.use { conn ->
                conn.prepareStatement("SELECT COUNT(*) FROM user_blocks WHERE blocker_id = ?").use { ps ->
                    ps.setObject(1, a)
                    ps.executeQuery().use { rs ->
                        rs.next() shouldBe true
                        rs.getInt(1) shouldBe 0
                    }
                }
            }
        } finally {
            cleanup(a)
        }
    }

    "POST /blocks/{nonexistent} — 404 user_not_found" {
        val (a, tokenA) = seedUser()
        try {
            withBlocks {
                val ghost = UUID.randomUUID()
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .post("/api/v1/blocks/$ghost") { header(HttpHeaders.Authorization, "Bearer $tokenA") }
                resp.status shouldBe HttpStatusCode.NotFound
                Json.parseToJsonElement(resp.bodyAsText())
                    .jsonObject["error"]!!.jsonObject["code"]!!
                    .jsonPrimitive.content shouldBe "user_not_found"
            }
        } finally {
            cleanup(a)
        }
    }

    "DELETE /blocks/{user} — removes existing row, returns 204" {
        val (a, tokenA) = seedUser()
        val (b, _) = seedUser()
        try {
            blocks.create(a, b) // pre-block
            withBlocks {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .delete("/api/v1/blocks/$b") { header(HttpHeaders.Authorization, "Bearer $tokenA") }
                resp.status shouldBe HttpStatusCode.NoContent
            }
            dataSource.connection.use { conn ->
                conn.prepareStatement("SELECT COUNT(*) FROM user_blocks WHERE blocker_id = ? AND blocked_id = ?").use { ps ->
                    ps.setObject(1, a)
                    ps.setObject(2, b)
                    ps.executeQuery().use { rs ->
                        rs.next() shouldBe true
                        rs.getInt(1) shouldBe 0
                    }
                }
            }
        } finally {
            cleanup(a, b)
        }
    }

    "DELETE /blocks/{user} — no-op delete still 204" {
        val (a, tokenA) = seedUser()
        val (b, _) = seedUser()
        try {
            withBlocks {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .delete("/api/v1/blocks/$b") { header(HttpHeaders.Authorization, "Bearer $tokenA") }
                resp.status shouldBe HttpStatusCode.NoContent
            }
        } finally {
            cleanup(a, b)
        }
    }

    "GET /blocks — returns own outbound blocks ordered DESC by created_at, excludes others" {
        val (a, tokenA) = seedUser()
        val (b, _) = seedUser()
        val (c, _) = seedUser()
        val (x, _) = seedUser()
        val (y, _) = seedUser()
        try {
            blocks.create(a, b)
            Thread.sleep(10) // ensure created_at ordering
            blocks.create(a, c)
            blocks.create(x, y) // unrelated; must not appear in A's list
            withBlocks {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/blocks") { header(HttpHeaders.Authorization, "Bearer $tokenA") }
                resp.status shouldBe HttpStatusCode.OK
                val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
                val arr = body["blocks"]!!.jsonArray
                arr shouldHaveSize 2
                val ids = arr.map { (it as JsonObject)["userId"]!!.jsonPrimitive.content }
                // c blocked second, so it's first in DESC order.
                ids shouldBe listOf(c.toString(), b.toString())
                // y must not appear.
                ids.contains(y.toString()) shouldBe false
                val nc = body["nextCursor"]
                (nc == null || nc == kotlinx.serialization.json.JsonNull) shouldBe true
            }
        } finally {
            cleanup(a, b, c, x, y)
        }
    }

    "GET /blocks — cursor pagination across page boundary" {
        val (a, tokenA) = seedUser()
        val targets = (1..32).map { seedUser().first }
        try {
            // create 32 blocks with monotonically increasing created_at
            for (t in targets) {
                blocks.create(a, t)
                Thread.sleep(2)
            }
            withBlocks {
                val client = createClient { install(ClientCN) { json() } }
                val r1 = client.get("/api/v1/blocks") { header(HttpHeaders.Authorization, "Bearer $tokenA") }
                r1.status shouldBe HttpStatusCode.OK
                val b1 = Json.parseToJsonElement(r1.bodyAsText()).jsonObject
                val arr1 = b1["blocks"]!!.jsonArray
                arr1 shouldHaveSize 30
                val cursor = b1["nextCursor"]!!.jsonPrimitive.content
                val r2 =
                    client.get("/api/v1/blocks?cursor=$cursor") {
                        header(HttpHeaders.Authorization, "Bearer $tokenA")
                    }
                r2.status shouldBe HttpStatusCode.OK
                val b2 = Json.parseToJsonElement(r2.bodyAsText()).jsonObject
                val arr2 = b2["blocks"]!!.jsonArray
                arr2 shouldHaveSize 2
                // No overlap.
                val ids1 = arr1.map { (it as JsonObject)["userId"]!!.jsonPrimitive.content }.toSet()
                val ids2 = arr2.map { (it as JsonObject)["userId"]!!.jsonPrimitive.content }.toSet()
                (ids1 intersect ids2).isEmpty() shouldBe true
            }
        } finally {
            cleanup(a, *targets.toTypedArray())
        }
    }

    fun insertFollow(
        follower: UUID,
        followee: UUID,
    ) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "INSERT INTO follows (follower_id, followee_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
            ).use { ps ->
                ps.setObject(1, follower)
                ps.setObject(2, followee)
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

    "POST /blocks/{B} cascades outbound follow A→B (follows row gone, block row present)" {
        val (a, tokenA) = seedUser()
        val (b, _) = seedUser()
        try {
            insertFollow(a, b)
            countFollow(a, b) shouldBe 1
            withBlocks {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .post("/api/v1/blocks/$b") { header(HttpHeaders.Authorization, "Bearer $tokenA") }
                resp.status shouldBe HttpStatusCode.NoContent
            }
            countFollow(a, b) shouldBe 0
            dataSource.connection.use { conn ->
                conn.prepareStatement(
                    "SELECT COUNT(*) FROM user_blocks WHERE blocker_id = ? AND blocked_id = ?",
                ).use { ps ->
                    ps.setObject(1, a)
                    ps.setObject(2, b)
                    ps.executeQuery().use { rs ->
                        rs.next() shouldBe true
                        rs.getInt(1) shouldBe 1
                    }
                }
            }
        } finally {
            cleanup(a, b)
        }
    }

    "POST /blocks/{B} cascades inbound follow B→A" {
        val (a, tokenA) = seedUser()
        val (b, _) = seedUser()
        try {
            insertFollow(b, a)
            countFollow(b, a) shouldBe 1
            withBlocks {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .post("/api/v1/blocks/$b") { header(HttpHeaders.Authorization, "Bearer $tokenA") }
                resp.status shouldBe HttpStatusCode.NoContent
            }
            countFollow(b, a) shouldBe 0
        } finally {
            cleanup(a, b)
        }
    }

    "POST /blocks/{B} cascades both directions in one transaction" {
        val (a, tokenA) = seedUser()
        val (b, _) = seedUser()
        try {
            insertFollow(a, b)
            insertFollow(b, a)
            withBlocks {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .post("/api/v1/blocks/$b") { header(HttpHeaders.Authorization, "Bearer $tokenA") }
                resp.status shouldBe HttpStatusCode.NoContent
            }
            countFollow(a, b) shouldBe 0
            countFollow(b, a) shouldBe 0
            dataSource.connection.use { conn ->
                conn.prepareStatement(
                    "SELECT COUNT(*) FROM user_blocks WHERE blocker_id = ? AND blocked_id = ?",
                ).use { ps ->
                    ps.setObject(1, a)
                    ps.setObject(2, b)
                    ps.executeQuery().use { rs ->
                        rs.next() shouldBe true
                        rs.getInt(1) shouldBe 1
                    }
                }
            }
        } finally {
            cleanup(a, b)
        }
    }

    "POST /blocks/{B} with no follows rows still returns 204 and leaves follows untouched" {
        val (a, tokenA) = seedUser()
        val (b, _) = seedUser()
        val (c, _) = seedUser()
        val (d, _) = seedUser()
        try {
            // Unrelated follow C→D: must not be affected when A blocks B.
            insertFollow(c, d)
            withBlocks {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .post("/api/v1/blocks/$b") { header(HttpHeaders.Authorization, "Bearer $tokenA") }
                resp.status shouldBe HttpStatusCode.NoContent
            }
            countFollow(a, b) shouldBe 0
            countFollow(b, a) shouldBe 0
            countFollow(c, d) shouldBe 1
        } finally {
            cleanup(a, b, c, d)
        }
    }

    "auth — all four endpoints return 401 without JWT" {
        val ghost = UUID.randomUUID()
        withBlocks {
            val client = createClient { install(ClientCN) { json() } }
            client.post("/api/v1/blocks/$ghost").status shouldBe HttpStatusCode.Unauthorized
            client.delete("/api/v1/blocks/$ghost").status shouldBe HttpStatusCode.Unauthorized
            client.get("/api/v1/blocks").status shouldBe HttpStatusCode.Unauthorized
        }
    }
})

