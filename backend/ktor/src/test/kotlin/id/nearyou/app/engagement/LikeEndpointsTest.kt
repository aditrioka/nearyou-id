package id.nearyou.app.engagement

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.auth.configureUserJwt
import id.nearyou.app.auth.jwt.JwtIssuer
import id.nearyou.app.auth.jwt.RsaKeyLoader
import id.nearyou.app.auth.jwt.TestKeys
import id.nearyou.app.infra.repo.JdbcNotificationRepository
import id.nearyou.app.infra.repo.JdbcPostLikeRepository
import id.nearyou.app.infra.repo.JdbcUserRepository
import id.nearyou.app.notifications.DbNotificationEmitter
import id.nearyou.app.notifications.NoopNotificationDispatcher
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
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
class LikeEndpointsTest : StringSpec({

    val dataSource = hikari()
    val keys = RsaKeyLoader(TestKeys.freshEncodedPemPrivateKey(), kid = "test-like")
    val jwtIssuer = JwtIssuer(keys)
    val users = JdbcUserRepository(dataSource)
    val likes = JdbcPostLikeRepository(dataSource)
    val notificationsRepo = JdbcNotificationRepository(dataSource)
    val notificationEmitter = DbNotificationEmitter(notificationsRepo, NoopNotificationDispatcher())
    val service = LikeService(dataSource, likes, notificationEmitter)

    fun seedUser(shadowBanned: Boolean = false): Pair<UUID, String> {
        val id = UUID.randomUUID()
        val short = id.toString().replace("-", "").take(8)
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO users (
                    id, username, display_name, date_of_birth, invite_code_prefix, is_shadow_banned
                ) VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "le_$short")
                ps.setString(3, "Like Endpoint Tester")
                ps.setDate(4, Date.valueOf(LocalDate.of(1990, 1, 1)))
                ps.setString(5, "k${short.take(7)}")
                ps.setBoolean(6, shadowBanned)
                ps.executeUpdate()
            }
        }
        val token = jwtIssuer.issueAccessToken(id, tokenVersion = 0)
        return id to token
    }

    fun seedPost(
        authorId: UUID,
        autoHidden: Boolean = false,
    ): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO posts (id, author_id, content, display_location, actual_location, is_auto_hidden)
                VALUES (?, ?, ?,
                  ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                  ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                  ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setObject(2, authorId)
                ps.setString(3, "like-post-${id.toString().take(6)}")
                ps.setDouble(4, 106.8)
                ps.setDouble(5, -6.2)
                ps.setDouble(6, 106.8)
                ps.setDouble(7, -6.2)
                ps.setBoolean(8, autoHidden)
                ps.executeUpdate()
            }
        }
        return id
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

    fun insertLike(
        postId: UUID,
        userId: UUID,
    ) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "INSERT INTO post_likes (post_id, user_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
            ).use { ps ->
                ps.setObject(1, postId)
                ps.setObject(2, userId)
                ps.executeUpdate()
            }
        }
    }

    fun countLikeRows(
        postId: UUID,
        userId: UUID? = null,
    ): Int {
        dataSource.connection.use { conn ->
            val sql =
                if (userId != null) {
                    "SELECT COUNT(*) FROM post_likes WHERE post_id = ? AND user_id = ?"
                } else {
                    "SELECT COUNT(*) FROM post_likes WHERE post_id = ?"
                }
            conn.prepareStatement(sql).use { ps ->
                ps.setObject(1, postId)
                if (userId != null) ps.setObject(2, userId)
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
                    st.executeUpdate("DELETE FROM posts WHERE author_id = '$it'")
                    st.executeUpdate("DELETE FROM users WHERE id = '$it'")
                }
            }
        }
    }

    suspend fun withLikes(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) {
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
                likeRoutes(service)
            }
            block()
        }
    }

    "POST /like first like returns 204 and creates a row" {
        val (liker, tl) = seedUser()
        val (author, _) = seedUser()
        val p = seedPost(author)
        try {
            withLikes {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .post("/api/v1/posts/$p/like") { header(HttpHeaders.Authorization, "Bearer $tl") }
                resp.status shouldBe HttpStatusCode.NoContent
            }
            countLikeRows(p, liker) shouldBe 1
        } finally {
            cleanup(liker, author)
        }
    }

    "POST /like re-like is idempotent (still 204, still one row)" {
        val (liker, tl) = seedUser()
        val (author, _) = seedUser()
        val p = seedPost(author)
        try {
            withLikes {
                val client = createClient { install(ClientCN) { json() } }
                client.post("/api/v1/posts/$p/like") { header(HttpHeaders.Authorization, "Bearer $tl") }
                    .status shouldBe HttpStatusCode.NoContent
                client.post("/api/v1/posts/$p/like") { header(HttpHeaders.Authorization, "Bearer $tl") }
                    .status shouldBe HttpStatusCode.NoContent
            }
            countLikeRows(p, liker) shouldBe 1
        } finally {
            cleanup(liker, author)
        }
    }

    "POST /like on missing UUID returns 404 post_not_found" {
        val (liker, tl) = seedUser()
        try {
            withLikes {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .post("/api/v1/posts/${UUID.randomUUID()}/like") {
                            header(HttpHeaders.Authorization, "Bearer $tl")
                        }
                resp.status shouldBe HttpStatusCode.NotFound
                Json.parseToJsonElement(resp.bodyAsText())
                    .jsonObject["error"]!!.jsonObject["code"]!!
                    .jsonPrimitive.content shouldBe "post_not_found"
            }
        } finally {
            cleanup(liker)
        }
    }

    "POST /like on soft-deleted (auto-hidden) post returns 404 and inserts no row" {
        val (liker, tl) = seedUser()
        val (author, _) = seedUser()
        val p = seedPost(author, autoHidden = true)
        try {
            withLikes {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .post("/api/v1/posts/$p/like") { header(HttpHeaders.Authorization, "Bearer $tl") }
                resp.status shouldBe HttpStatusCode.NotFound
            }
            countLikeRows(p, liker) shouldBe 0
        } finally {
            cleanup(liker, author)
        }
    }

    "POST /like when caller blocked the post author returns 404 and inserts no row" {
        val (liker, tl) = seedUser()
        val (author, _) = seedUser()
        val p = seedPost(author)
        try {
            insertBlock(liker, author)
            withLikes {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .post("/api/v1/posts/$p/like") { header(HttpHeaders.Authorization, "Bearer $tl") }
                resp.status shouldBe HttpStatusCode.NotFound
            }
            countLikeRows(p, liker) shouldBe 0
        } finally {
            cleanup(liker, author)
        }
    }

    "POST /like when post author blocked the caller returns 404 and inserts no row" {
        val (liker, tl) = seedUser()
        val (author, _) = seedUser()
        val p = seedPost(author)
        try {
            insertBlock(author, liker)
            withLikes {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .post("/api/v1/posts/$p/like") { header(HttpHeaders.Authorization, "Bearer $tl") }
                resp.status shouldBe HttpStatusCode.NotFound
            }
            countLikeRows(p, liker) shouldBe 0
        } finally {
            cleanup(liker, author)
        }
    }

    "404 body identical across all four invisibility cases (byte-for-byte)" {
        val (l1, t1) = seedUser()
        val (a1, _) = seedUser()
        val missingUuid = UUID.randomUUID()
        val softDel = seedPost(a1, autoHidden = true)
        val (l2, t2) = seedUser()
        val (a2, _) = seedUser()
        val p2 = seedPost(a2)
        val (l3, t3) = seedUser()
        val (a3, _) = seedUser()
        val p3 = seedPost(a3)
        try {
            insertBlock(l2, a2) // caller blocks author
            insertBlock(a3, l3) // author blocks caller
            val bodies = mutableListOf<String>()
            withLikes {
                val client = createClient { install(ClientCN) { json() } }
                bodies +=
                    client.post("/api/v1/posts/$missingUuid/like") { header(HttpHeaders.Authorization, "Bearer $t1") }
                        .bodyAsText()
                bodies +=
                    client.post("/api/v1/posts/$softDel/like") { header(HttpHeaders.Authorization, "Bearer $t1") }
                        .bodyAsText()
                bodies +=
                    client.post("/api/v1/posts/$p2/like") { header(HttpHeaders.Authorization, "Bearer $t2") }
                        .bodyAsText()
                bodies +=
                    client.post("/api/v1/posts/$p3/like") { header(HttpHeaders.Authorization, "Bearer $t3") }
                        .bodyAsText()
            }
            bodies[0] shouldBe "{\"error\":{\"code\":\"post_not_found\"}}"
            bodies.toSet().size shouldBe 1
        } finally {
            cleanup(l1, a1, l2, a2, l3, a3)
        }
    }

    "POST /like on non-UUID path returns 400 invalid_uuid (no DB write)" {
        val (liker, tl) = seedUser()
        try {
            withLikes {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .post("/api/v1/posts/not-a-uuid/like") {
                            header(HttpHeaders.Authorization, "Bearer $tl")
                        }
                resp.status shouldBe HttpStatusCode.BadRequest
                Json.parseToJsonElement(resp.bodyAsText())
                    .jsonObject["error"]!!.jsonObject["code"]!!
                    .jsonPrimitive.content shouldBe "invalid_uuid"
            }
        } finally {
            cleanup(liker)
        }
    }

    "DELETE /like removes an existing row and returns 204" {
        val (liker, tl) = seedUser()
        val (author, _) = seedUser()
        val p = seedPost(author)
        try {
            insertLike(p, liker)
            countLikeRows(p, liker) shouldBe 1
            withLikes {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .delete("/api/v1/posts/$p/like") { header(HttpHeaders.Authorization, "Bearer $tl") }
                resp.status shouldBe HttpStatusCode.NoContent
            }
            countLikeRows(p, liker) shouldBe 0
        } finally {
            cleanup(liker, author)
        }
    }

    "DELETE /like no-op still returns 204" {
        val (liker, tl) = seedUser()
        val (author, _) = seedUser()
        val p = seedPost(author)
        try {
            withLikes {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .delete("/api/v1/posts/$p/like") { header(HttpHeaders.Authorization, "Bearer $tl") }
                resp.status shouldBe HttpStatusCode.NoContent
            }
        } finally {
            cleanup(liker, author)
        }
    }

    "DELETE /like after author block still removes row and returns 204" {
        val (liker, tl) = seedUser()
        val (author, _) = seedUser()
        val p = seedPost(author)
        try {
            insertLike(p, liker)
            insertBlock(author, liker)
            withLikes {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .delete("/api/v1/posts/$p/like") { header(HttpHeaders.Authorization, "Bearer $tl") }
                resp.status shouldBe HttpStatusCode.NoContent
            }
            countLikeRows(p, liker) shouldBe 0
        } finally {
            cleanup(liker, author)
        }
    }

    "DELETE /like on non-UUID path returns 400 invalid_uuid" {
        val (liker, tl) = seedUser()
        try {
            withLikes {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .delete("/api/v1/posts/not-a-uuid/like") {
                            header(HttpHeaders.Authorization, "Bearer $tl")
                        }
                resp.status shouldBe HttpStatusCode.BadRequest
                Json.parseToJsonElement(resp.bodyAsText())
                    .jsonObject["error"]!!.jsonObject["code"]!!
                    .jsonPrimitive.content shouldBe "invalid_uuid"
            }
        } finally {
            cleanup(liker)
        }
    }

    "GET /likes/count returns shadow-ban-filtered count on a visible post" {
        val (viewer, tv) = seedUser()
        val (author, _) = seedUser()
        val (likerA, _) = seedUser()
        val (likerB, _) = seedUser()
        val (likerBanned1, _) = seedUser(shadowBanned = true)
        val (likerBanned2, _) = seedUser(shadowBanned = true)
        val (likerVisible, _) = seedUser()
        val p = seedPost(author)
        try {
            insertLike(p, likerA)
            insertLike(p, likerB)
            insertLike(p, likerBanned1)
            insertLike(p, likerBanned2)
            insertLike(p, likerVisible)
            withLikes {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/posts/$p/likes/count") {
                            header(HttpHeaders.Authorization, "Bearer $tv")
                        }
                resp.status shouldBe HttpStatusCode.OK
                Json.parseToJsonElement(resp.bodyAsText())
                    .jsonObject["count"]!!.jsonPrimitive.content shouldBe "3"
            }
        } finally {
            cleanup(viewer, author, likerA, likerB, likerBanned1, likerBanned2, likerVisible)
        }
    }

    "GET /likes/count returns 0 on a visible post with no likes" {
        val (viewer, tv) = seedUser()
        val (author, _) = seedUser()
        val p = seedPost(author)
        try {
            withLikes {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/posts/$p/likes/count") {
                            header(HttpHeaders.Authorization, "Bearer $tv")
                        }
                resp.status shouldBe HttpStatusCode.OK
                Json.parseToJsonElement(resp.bodyAsText())
                    .jsonObject["count"]!!.jsonPrimitive.content shouldBe "0"
            }
        } finally {
            cleanup(viewer, author)
        }
    }

    "GET /likes/count returns 404 on missing or block-hidden post" {
        val (viewer, tv) = seedUser()
        val (author, _) = seedUser()
        val p = seedPost(author)
        try {
            insertBlock(viewer, author) // viewer blocks author
            withLikes {
                val client = createClient { install(ClientCN) { json() } }
                client.get("/api/v1/posts/${UUID.randomUUID()}/likes/count") {
                    header(HttpHeaders.Authorization, "Bearer $tv")
                }.status shouldBe HttpStatusCode.NotFound
                client.get("/api/v1/posts/$p/likes/count") {
                    header(HttpHeaders.Authorization, "Bearer $tv")
                }.status shouldBe HttpStatusCode.NotFound
            }
        } finally {
            cleanup(viewer, author)
        }
    }

    "GET /likes/count does NOT vary per-viewer by caller's block list" {
        val (viewerA, ta) = seedUser()
        val (viewerB, tb) = seedUser()
        val (author, _) = seedUser()
        val (liker1, _) = seedUser()
        val (liker2, _) = seedUser()
        val (liker3, _) = seedUser()
        val p = seedPost(author)
        try {
            insertLike(p, liker1)
            insertLike(p, liker2)
            insertLike(p, liker3)
            // A blocks liker1; B has no blocks.
            insertBlock(viewerA, liker1)
            withLikes {
                val client = createClient { install(ClientCN) { json() } }
                val countA =
                    Json.parseToJsonElement(
                        client.get("/api/v1/posts/$p/likes/count") {
                            header(HttpHeaders.Authorization, "Bearer $ta")
                        }.bodyAsText(),
                    ).jsonObject["count"]!!.jsonPrimitive.content
                val countB =
                    Json.parseToJsonElement(
                        client.get("/api/v1/posts/$p/likes/count") {
                            header(HttpHeaders.Authorization, "Bearer $tb")
                        }.bodyAsText(),
                    ).jsonObject["count"]!!.jsonPrimitive.content
                countA shouldBe "3"
                countB shouldBe "3"
            }
        } finally {
            cleanup(viewerA, viewerB, author, liker1, liker2, liker3)
        }
    }

    "auth — all three endpoints return 401 without JWT" {
        val ghost = UUID.randomUUID()
        withLikes {
            val client = createClient { install(ClientCN) { json() } }
            client.post("/api/v1/posts/$ghost/like").status shouldBe HttpStatusCode.Unauthorized
            client.delete("/api/v1/posts/$ghost/like").status shouldBe HttpStatusCode.Unauthorized
            client.get("/api/v1/posts/$ghost/likes/count").status shouldBe HttpStatusCode.Unauthorized
        }
    }
})
