package id.nearyou.app.timeline

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.auth.configureUserJwt
import id.nearyou.app.auth.jwt.JwtIssuer
import id.nearyou.app.auth.jwt.RsaKeyLoader
import id.nearyou.app.auth.jwt.TestKeys
import id.nearyou.app.infra.repo.JdbcPostsFollowingRepository
import id.nearyou.app.infra.repo.JdbcUserRepository
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
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
class FollowingTimelineServiceTest : StringSpec({

    val dataSource = hikari()
    val keys = RsaKeyLoader(TestKeys.freshEncodedPemPrivateKey(), kid = "test-following")
    val jwtIssuer = JwtIssuer(keys)
    val users = JdbcUserRepository(dataSource)
    val timeline = JdbcPostsFollowingRepository(dataSource)
    val service = FollowingTimelineService(timeline)

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
                ps.setString(2, "ft_$short")
                ps.setString(3, "Following Timeline Tester")
                ps.setDate(4, Date.valueOf(LocalDate.of(1990, 1, 1)))
                ps.setString(5, "g${short.take(7)}")
                ps.executeUpdate()
            }
        }
        val token = jwtIssuer.issueAccessToken(id, tokenVersion = 0)
        return id to token
    }

    fun seedPost(
        authorId: UUID,
        lat: Double = -6.200,
        lng: Double = 106.800,
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
                ps.setString(3, "post-${id.toString().take(6)}")
                ps.setDouble(4, lng)
                ps.setDouble(5, lat)
                ps.setDouble(6, lng)
                ps.setDouble(7, lat)
                ps.setBoolean(8, autoHidden)
                ps.executeUpdate()
            }
        }
        return id
    }

    fun follow(
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

    fun cleanup(vararg userIds: UUID) {
        dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                userIds.forEach {
                    // posts cascade fails (RESTRICT) — delete posts explicitly first.
                    // V8: also hard-delete any replies authored by this user elsewhere
                    // (post_replies.author_id is RESTRICT).
                    st.executeUpdate("DELETE FROM post_replies WHERE author_id = '$it'")
                    st.executeUpdate("DELETE FROM posts WHERE author_id = '$it'")
                    st.executeUpdate("DELETE FROM users WHERE id = '$it'")
                }
            }
        }
    }

    fun seedReply(postId: UUID, authorId: UUID, deletedAt: java.time.Instant? = null) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "INSERT INTO post_replies (post_id, author_id, content, deleted_at) VALUES (?, ?, ?, ?)",
            ).use { ps ->
                ps.setObject(1, postId)
                ps.setObject(2, authorId)
                ps.setString(3, "r-${UUID.randomUUID().toString().take(6)}")
                if (deletedAt != null) ps.setTimestamp(4, java.sql.Timestamp.from(deletedAt))
                else ps.setNull(4, java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                ps.executeUpdate()
            }
        }
    }

    fun seedShadowBannedUser(): UUID {
        val id = UUID.randomUUID()
        val short = id.toString().replace("-", "").take(8)
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO users (
                    id, username, display_name, date_of_birth, invite_code_prefix, is_shadow_banned
                ) VALUES (?, ?, ?, ?, ?, TRUE)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "fsb_$short")
                ps.setString(3, "Shadow Banned")
                ps.setDate(4, Date.valueOf(LocalDate.of(1990, 1, 1)))
                ps.setString(5, "h${short.take(7)}")
                ps.executeUpdate()
            }
        }
        return id
    }

    suspend fun withFollowing(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) {
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
                followingTimelineRoutes(service)
            }
            block()
        }
    }

    "happy path — viewer sees posts from three followed authors ordered DESC by created_at" {
        val (viewer, vt) = seedUser()
        val (a, _) = seedUser()
        val (b, _) = seedUser()
        val (c, _) = seedUser()
        try {
            follow(viewer, a)
            follow(viewer, b)
            follow(viewer, c)
            val pa = seedPost(a)
            Thread.sleep(10)
            val pb = seedPost(b)
            Thread.sleep(10)
            val pc = seedPost(c)
            withFollowing {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/timeline/following") {
                            header(HttpHeaders.Authorization, "Bearer $vt")
                        }
                resp.status shouldBe HttpStatusCode.OK
                val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
                val ids = body["posts"]!!.jsonArray.map { (it as JsonObject)["id"]!!.jsonPrimitive.content }
                ids shouldBe listOf(pc.toString(), pb.toString(), pa.toString())
                // No distance_m / distanceM in response.
                val first = body["posts"]!!.jsonArray.first().jsonObject
                first.keys shouldBe setOf(
                    "id",
                    "authorUserId",
                    "content",
                    "latitude",
                    "longitude",
                    "createdAt",
                    "liked_by_viewer",
                    "reply_count",
                )
                first.containsKey("distanceM") shouldBe false
                first.containsKey("distance_m") shouldBe false
            }
        } finally {
            cleanup(viewer, a, b, c)
        }
    }

    "empty result — viewer follows nobody, posts = [], nextCursor = null" {
        val (viewer, vt) = seedUser()
        val (other, _) = seedUser()
        try {
            // `other` has a post but viewer does not follow them.
            seedPost(other)
            withFollowing {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/timeline/following") {
                            header(HttpHeaders.Authorization, "Bearer $vt")
                        }
                resp.status shouldBe HttpStatusCode.OK
                val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
                body["posts"]!!.jsonArray shouldHaveSize 0
                val nc = body["nextCursor"]
                (nc == null || nc == kotlinx.serialization.json.JsonNull) shouldBe true
            }
        } finally {
            cleanup(viewer, other)
        }
    }

    "cursor pagination — 35 posts, page 1 of 30 + cursor, page 2 of 5, no overlap" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        try {
            follow(viewer, author)
            for (i in 0 until 35) {
                seedPost(author)
                Thread.sleep(2)
            }
            withFollowing {
                val client = createClient { install(ClientCN) { json() } }
                val r1 =
                    client.get("/api/v1/timeline/following") {
                        header(HttpHeaders.Authorization, "Bearer $vt")
                    }
                r1.status shouldBe HttpStatusCode.OK
                val b1 = Json.parseToJsonElement(r1.bodyAsText()).jsonObject
                b1["posts"]!!.jsonArray shouldHaveSize 30
                val cursor = b1["nextCursor"]!!.jsonPrimitive.content
                val r2 =
                    client.get("/api/v1/timeline/following?cursor=$cursor") {
                        header(HttpHeaders.Authorization, "Bearer $vt")
                    }
                r2.status shouldBe HttpStatusCode.OK
                val b2 = Json.parseToJsonElement(r2.bodyAsText()).jsonObject
                b2["posts"]!!.jsonArray shouldHaveSize 5
                val ids1 = b1["posts"]!!.jsonArray.map { (it as JsonObject)["id"]!!.jsonPrimitive.content }.toSet()
                val ids2 = b2["posts"]!!.jsonArray.map { (it as JsonObject)["id"]!!.jsonPrimitive.content }.toSet()
                (ids1 intersect ids2).isEmpty() shouldBe true
            }
        } finally {
            cleanup(viewer, author)
        }
    }

    "auto-hidden followed-author post excluded (visible_posts view filter)" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        try {
            follow(viewer, author)
            val visible = seedPost(author)
            val hidden = seedPost(author, autoHidden = true)
            withFollowing {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/timeline/following") {
                            header(HttpHeaders.Authorization, "Bearer $vt")
                        }
                val ids = Json.parseToJsonElement(resp.bodyAsText())
                    .jsonObject["posts"]!!.jsonArray
                    .map { (it as JsonObject)["id"]!!.jsonPrimitive.content }
                ids.contains(visible.toString()) shouldBe true
                ids.contains(hidden.toString()) shouldBe false
            }
        } finally {
            cleanup(viewer, author)
        }
    }

    "bidirectional block exclusion — viewer-blocked-author hidden even if followed" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        try {
            follow(viewer, author)
            val p = seedPost(author)
            insertBlock(viewer, author) // viewer blocked author
            withFollowing {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/timeline/following") {
                            header(HttpHeaders.Authorization, "Bearer $vt")
                        }
                val ids = Json.parseToJsonElement(resp.bodyAsText())
                    .jsonObject["posts"]!!.jsonArray
                    .map { (it as JsonObject)["id"]!!.jsonPrimitive.content }
                ids.contains(p.toString()) shouldBe false
            }
        } finally {
            cleanup(viewer, author)
        }
    }

    "bidirectional block exclusion — author-blocked-viewer hidden even if followed" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        try {
            follow(viewer, author)
            val p = seedPost(author)
            insertBlock(author, viewer) // author blocked viewer
            withFollowing {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/timeline/following") {
                            header(HttpHeaders.Authorization, "Bearer $vt")
                        }
                val ids = Json.parseToJsonElement(resp.bodyAsText())
                    .jsonObject["posts"]!!.jsonArray
                    .map { (it as JsonObject)["id"]!!.jsonPrimitive.content }
                ids.contains(p.toString()) shouldBe false
            }
        } finally {
            cleanup(viewer, author)
        }
    }

    "non-followed-author posts excluded" {
        val (viewer, vt) = seedUser()
        val (followed, _) = seedUser()
        val (stranger, _) = seedUser()
        try {
            follow(viewer, followed)
            val mine = seedPost(followed)
            val theirs = seedPost(stranger)
            withFollowing {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/timeline/following") {
                            header(HttpHeaders.Authorization, "Bearer $vt")
                        }
                val ids = Json.parseToJsonElement(resp.bodyAsText())
                    .jsonObject["posts"]!!.jsonArray
                    .map { (it as JsonObject)["id"]!!.jsonPrimitive.content }
                ids shouldBe listOf(mine.toString())
                ids.contains(theirs.toString()) shouldBe false
            }
        } finally {
            cleanup(viewer, followed, stranger)
        }
    }

    "auth required — 401 without JWT" {
        withFollowing {
            createClient { install(ClientCN) { json() } }
                .get("/api/v1/timeline/following")
                .status shouldBe HttpStatusCode.Unauthorized
        }
    }

    "liked_by_viewer — true when caller has liked a followed-author post" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        try {
            follow(viewer, author)
            val p = seedPost(author)
            dataSource.connection.use { conn ->
                conn.prepareStatement("INSERT INTO post_likes (post_id, user_id) VALUES (?, ?)").use { ps ->
                    ps.setObject(1, p)
                    ps.setObject(2, viewer)
                    ps.executeUpdate()
                }
            }
            withFollowing {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/timeline/following") {
                            header(HttpHeaders.Authorization, "Bearer $vt")
                        }
                val post = Json.parseToJsonElement(resp.bodyAsText())
                    .jsonObject["posts"]!!.jsonArray
                    .first { (it as JsonObject)["id"]!!.jsonPrimitive.content == p.toString() }
                    .jsonObject
                post["liked_by_viewer"]!!.jsonPrimitive.content shouldBe "true"
            }
        } finally {
            cleanup(viewer, author)
        }
    }

    "liked_by_viewer — false when caller has not liked" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        try {
            follow(viewer, author)
            val p = seedPost(author)
            withFollowing {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/timeline/following") {
                            header(HttpHeaders.Authorization, "Bearer $vt")
                        }
                val post = Json.parseToJsonElement(resp.bodyAsText())
                    .jsonObject["posts"]!!.jsonArray
                    .first { (it as JsonObject)["id"]!!.jsonPrimitive.content == p.toString() }
                    .jsonObject
                post["liked_by_viewer"]!!.jsonPrimitive.content shouldBe "false"
            }
        } finally {
            cleanup(viewer, author)
        }
    }

    "liked_by_viewer — key present on every Following post" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        try {
            follow(viewer, author)
            val posts = (0 until 5).map { seedPost(author).also { Thread.sleep(2) } }
            dataSource.connection.use { conn ->
                conn.prepareStatement("INSERT INTO post_likes (post_id, user_id) VALUES (?, ?)").use { ps ->
                    ps.setObject(1, posts[1]); ps.setObject(2, viewer); ps.executeUpdate()
                }
            }
            withFollowing {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/timeline/following") {
                            header(HttpHeaders.Authorization, "Bearer $vt")
                        }
                val arr = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["posts"]!!.jsonArray
                arr.forEach { (it as JsonObject).containsKey("liked_by_viewer") shouldBe true }
            }
        } finally {
            cleanup(viewer, author)
        }
    }

    "liked_by_viewer — cardinality invariant: 20 eligible with 6 liked → 20 returned, not 26" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        try {
            follow(viewer, author)
            val posts = (0 until 20).map { seedPost(author).also { Thread.sleep(2) } }
            dataSource.connection.use { conn ->
                conn.prepareStatement("INSERT INTO post_likes (post_id, user_id) VALUES (?, ?)").use { ps ->
                    posts.take(6).forEach { pid ->
                        ps.setObject(1, pid); ps.setObject(2, viewer); ps.executeUpdate()
                    }
                }
            }
            withFollowing {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/timeline/following") {
                            header(HttpHeaders.Authorization, "Bearer $vt")
                        }
                val arr = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["posts"]!!.jsonArray
                arr shouldHaveSize 20
            }
        } finally {
            cleanup(viewer, author)
        }
    }

    "invalid cursor — 400 invalid_cursor" {
        val (viewer, vt) = seedUser()
        try {
            withFollowing {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/timeline/following?cursor=not-a-cursor") {
                            header(HttpHeaders.Authorization, "Bearer $vt")
                        }
                resp.status shouldBe HttpStatusCode.BadRequest
                Json.parseToJsonElement(resp.bodyAsText())
                    .jsonObject["error"]!!.jsonObject["code"]!!
                    .jsonPrimitive.content shouldBe "invalid_cursor"
            }
        } finally {
            cleanup(viewer)
        }
    }

    // ---- V8: reply_count tests ----

    "reply_count — 0 for a followed-author post with no replies" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        try {
            follow(viewer, author)
            val p = seedPost(author)
            withFollowing {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/timeline/following") {
                            header(HttpHeaders.Authorization, "Bearer $vt")
                        }
                val arr = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["posts"]!!.jsonArray
                val post = arr.first { (it as JsonObject)["id"]!!.jsonPrimitive.content == p.toString() }
                (post as JsonObject)["reply_count"]!!.jsonPrimitive.content shouldBe "0"
            }
        } finally {
            cleanup(viewer, author)
        }
    }

    "reply_count — exact count when multiple visible replies exist" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        val (replier, _) = seedUser()
        try {
            follow(viewer, author)
            val p = seedPost(author)
            seedReply(p, replier)
            seedReply(p, replier)
            seedReply(p, replier)
            withFollowing {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/timeline/following") {
                            header(HttpHeaders.Authorization, "Bearer $vt")
                        }
                val arr = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["posts"]!!.jsonArray
                val post = arr.first { (it as JsonObject)["id"]!!.jsonPrimitive.content == p.toString() }
                (post as JsonObject)["reply_count"]!!.jsonPrimitive.content shouldBe "3"
            }
        } finally {
            cleanup(viewer, author, replier)
        }
    }

    "reply_count — excludes shadow-banned repliers via visible_users JOIN" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        val (replierVisible, _) = seedUser()
        val replierBanned = seedShadowBannedUser()
        try {
            follow(viewer, author)
            val p = seedPost(author)
            seedReply(p, replierVisible)
            seedReply(p, replierVisible)
            seedReply(p, replierBanned)
            withFollowing {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/timeline/following") {
                            header(HttpHeaders.Authorization, "Bearer $vt")
                        }
                val arr = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["posts"]!!.jsonArray
                val post = arr.first { (it as JsonObject)["id"]!!.jsonPrimitive.content == p.toString() }
                (post as JsonObject)["reply_count"]!!.jsonPrimitive.content shouldBe "2"
            }
        } finally {
            cleanup(viewer, author, replierVisible, replierBanned)
        }
    }

    "reply_count — excludes soft-deleted replies" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        val (replier, _) = seedUser()
        try {
            follow(viewer, author)
            val p = seedPost(author)
            seedReply(p, replier)
            seedReply(p, replier)
            seedReply(p, replier)
            seedReply(p, replier, deletedAt = java.time.Instant.now())
            seedReply(p, replier, deletedAt = java.time.Instant.now())
            withFollowing {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/timeline/following") {
                            header(HttpHeaders.Authorization, "Bearer $vt")
                        }
                val arr = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["posts"]!!.jsonArray
                val post = arr.first { (it as JsonObject)["id"]!!.jsonPrimitive.content == p.toString() }
                (post as JsonObject)["reply_count"]!!.jsonPrimitive.content shouldBe "3"
            }
        } finally {
            cleanup(viewer, author, replier)
        }
    }

    "reply_count — does NOT apply viewer-block exclusion (privacy tradeoff)" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        val (replierOk1, _) = seedUser()
        val (replierOk2, _) = seedUser()
        val (replierBlocked, _) = seedUser()
        try {
            follow(viewer, author)
            insertBlock(viewer, replierBlocked)
            val p = seedPost(author)
            seedReply(p, replierOk1)
            seedReply(p, replierOk2)
            seedReply(p, replierBlocked)
            withFollowing {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/timeline/following") {
                            header(HttpHeaders.Authorization, "Bearer $vt")
                        }
                val arr = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["posts"]!!.jsonArray
                val post = arr.first { (it as JsonObject)["id"]!!.jsonPrimitive.content == p.toString() }
                (post as JsonObject)["reply_count"]!!.jsonPrimitive.content shouldBe "3"
            }
        } finally {
            cleanup(viewer, author, replierOk1, replierOk2, replierBlocked)
        }
    }

    "reply_count — key present on every Following post" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        try {
            follow(viewer, author)
            repeat(3) { seedPost(author); Thread.sleep(2) }
            withFollowing {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/timeline/following") {
                            header(HttpHeaders.Authorization, "Bearer $vt")
                        }
                val arr = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["posts"]!!.jsonArray
                arr.forEach { (it as JsonObject).containsKey("reply_count") shouldBe true }
            }
        } finally {
            cleanup(viewer, author)
        }
    }

    "reply_count — LATERAL cardinality invariant: 5 eligible posts with 20 total replies → 5 rows, not 25" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        val (replier, _) = seedUser()
        try {
            follow(viewer, author)
            val posts = (0 until 5).map { seedPost(author).also { Thread.sleep(2) } }
            posts.forEach { pid -> repeat(4) { seedReply(pid, replier) } }
            withFollowing {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/timeline/following") {
                            header(HttpHeaders.Authorization, "Bearer $vt")
                        }
                val arr = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["posts"]!!.jsonArray
                arr shouldHaveSize 5
                arr.forEach { (it as JsonObject)["reply_count"]!!.jsonPrimitive.content shouldBe "4" }
            }
        } finally {
            cleanup(viewer, author, replier)
        }
    }
})
