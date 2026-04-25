package id.nearyou.app.timeline

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.auth.configureUserJwt
import id.nearyou.app.auth.jwt.JwtIssuer
import id.nearyou.app.auth.jwt.RsaKeyLoader
import id.nearyou.app.auth.jwt.TestKeys
import id.nearyou.app.infra.repo.JdbcPostsTimelineRepository
import id.nearyou.app.infra.repo.JdbcUserRepository
import id.nearyou.app.post.LocationOutOfBoundsException
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
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
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
class NearbyTimelineServiceTest : StringSpec({

    val dataSource = hikari()
    val keys = RsaKeyLoader(TestKeys.freshEncodedPemPrivateKey(), kid = "test-timeline")
    val jwtIssuer = JwtIssuer(keys)
    val users = JdbcUserRepository(dataSource)
    val timeline = JdbcPostsTimelineRepository(dataSource)
    val service = NearbyTimelineService(timeline)

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
                ps.setString(2, "tl_$short")
                ps.setString(3, "Timeline Tester")
                ps.setDate(4, Date.valueOf(LocalDate.of(1990, 1, 1)))
                ps.setString(5, "t${short.take(7)}")
                ps.executeUpdate()
            }
        }
        val token = jwtIssuer.issueAccessToken(id, tokenVersion = 0)
        return id to token
    }

    fun seedPost(
        authorId: UUID,
        lat: Double,
        lng: Double,
        autoHidden: Boolean = false,
        content: String = "post-${UUID.randomUUID().toString().take(6)}",
    ): UUID {
        val id = UUID.randomUUID()
        // Tests insert directly into posts (NOT via the Nearby read path) so display ==
        // actual is fine — the read query returns ST_Y/ST_X(display_location) which we
        // then assert against. This file is a test fixture; it bypasses the lint rule
        // by sitting under src/test/.
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
                ps.setString(3, content)
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

    fun cleanup(vararg userIds: UUID) {
        dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                // V8 post_replies.author_id uses ON DELETE RESTRICT, so we must
                // hard-delete any reply rows these users authored BEFORE the user
                // rows can be removed. Deleting their posts first cascades replies
                // ON THEIR posts, but not replies they authored elsewhere.
                userIds.forEach {
                    st.executeUpdate("DELETE FROM post_replies WHERE author_id = '$it'")
                    st.executeUpdate("DELETE FROM posts WHERE author_id = '$it'")
                    st.executeUpdate("DELETE FROM users WHERE id = '$it'")
                }
            }
        }
    }

    fun seedReply(
        postId: UUID,
        authorId: UUID,
        deletedAt: java.time.Instant? = null,
    ) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "INSERT INTO post_replies (post_id, author_id, content, deleted_at) VALUES (?, ?, ?, ?)",
            ).use { ps ->
                ps.setObject(1, postId)
                ps.setObject(2, authorId)
                ps.setString(3, "r-${UUID.randomUUID().toString().take(6)}")
                if (deletedAt != null) {
                    ps.setTimestamp(4, java.sql.Timestamp.from(deletedAt))
                } else {
                    ps.setNull(4, java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                }
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
                ps.setString(2, "sb_$short")
                ps.setString(3, "Shadow Banned")
                ps.setDate(4, Date.valueOf(LocalDate.of(1990, 1, 1)))
                ps.setString(5, "s${short.take(7)}")
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

    suspend fun withTimeline(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) {
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
                install(StatusPages) {
                    exception<LocationOutOfBoundsException> { call, _ ->
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf(
                                "error" to mapOf("code" to "location_out_of_bounds", "message" to "envelope"),
                            ),
                        )
                    }
                }
                install(Authentication) { configureUserJwt(keys, users, java.time.Instant::now) }
                timelineRoutes(service)
            }
            block()
        }
    }

    "happy path — Jakarta viewer sees nearby posts ordered DESC by created_at" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        try {
            // Three posts ~1km from Jakarta center; tiny created_at staggering.
            val p1 = seedPost(author, -6.200, 106.800)
            Thread.sleep(10)
            val p2 = seedPost(author, -6.205, 106.805)
            Thread.sleep(10)
            val p3 = seedPost(author, -6.210, 106.810)
            withTimeline {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/timeline/nearby?lat=-6.2&lng=106.8&radius_m=5000") {
                            header(HttpHeaders.Authorization, "Bearer $vt")
                        }
                resp.status shouldBe HttpStatusCode.OK
                val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
                val ids = body["posts"]!!.jsonArray.map { (it as JsonObject)["id"]!!.jsonPrimitive.content }
                ids shouldBe listOf(p3.toString(), p2.toString(), p1.toString())
                // Each post item carries the documented keys (V7 adds liked_by_viewer; V8 adds reply_count).
                val first = body["posts"]!!.jsonArray.first().jsonObject
                first.keys shouldBe
                    setOf(
                        "id",
                        "authorUserId",
                        "content",
                        "latitude",
                        "longitude",
                        "distanceM",
                        "city_name",
                        "createdAt",
                        "liked_by_viewer",
                        "reply_count",
                    )
            }
        } finally {
            cleanup(viewer, author)
        }
    }

    "cursor pagination — page 1 of 30, page 2 of remaining" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        try {
            // 35 posts within radius
            for (i in 0 until 35) {
                seedPost(author, -6.200 + i * 0.0001, 106.800)
                Thread.sleep(2)
            }
            withTimeline {
                val client = createClient { install(ClientCN) { json() } }
                val r1 =
                    client.get("/api/v1/timeline/nearby?lat=-6.2&lng=106.8&radius_m=5000") {
                        header(HttpHeaders.Authorization, "Bearer $vt")
                    }
                r1.status shouldBe HttpStatusCode.OK
                val b1 = Json.parseToJsonElement(r1.bodyAsText()).jsonObject
                b1["posts"]!!.jsonArray shouldHaveSize 30
                val cursor = b1["nextCursor"]!!.jsonPrimitive.content
                val r2 =
                    client.get("/api/v1/timeline/nearby?lat=-6.2&lng=106.8&radius_m=5000&cursor=$cursor") {
                        header(HttpHeaders.Authorization, "Bearer $vt")
                    }
                r2.status shouldBe HttpStatusCode.OK
                val b2 = Json.parseToJsonElement(r2.bodyAsText()).jsonObject
                b2["posts"]!!.jsonArray shouldHaveSize 5
                // No overlap.
                val ids1 = b1["posts"]!!.jsonArray.map { (it as JsonObject)["id"]!!.jsonPrimitive.content }.toSet()
                val ids2 = b2["posts"]!!.jsonArray.map { (it as JsonObject)["id"]!!.jsonPrimitive.content }.toSet()
                (ids1 intersect ids2).isEmpty() shouldBe true
            }
        } finally {
            cleanup(viewer, author)
        }
    }

    "radius filter — post outside radius excluded" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        try {
            val near = seedPost(author, -6.201, 106.801) // ~150m from -6.2,106.8
            val far = seedPost(author, -7.000, 107.500) // ~110km
            withTimeline {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/timeline/nearby?lat=-6.2&lng=106.8&radius_m=1000") {
                            header(HttpHeaders.Authorization, "Bearer $vt")
                        }
                val ids =
                    Json.parseToJsonElement(resp.bodyAsText())
                        .jsonObject["posts"]!!.jsonArray
                        .map { (it as JsonObject)["id"]!!.jsonPrimitive.content }
                ids shouldBe listOf(near.toString())
                ids.contains(far.toString()) shouldBe false
            }
        } finally {
            cleanup(viewer, author)
        }
    }

    "auto-hidden post excluded (visible_posts view filters is_auto_hidden = TRUE)" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        try {
            val visible = seedPost(author, -6.200, 106.800)
            val hidden = seedPost(author, -6.201, 106.801, autoHidden = true)
            withTimeline {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/timeline/nearby?lat=-6.2&lng=106.8&radius_m=5000") {
                            header(HttpHeaders.Authorization, "Bearer $vt")
                        }
                val ids =
                    Json.parseToJsonElement(resp.bodyAsText())
                        .jsonObject["posts"]!!.jsonArray
                        .map { (it as JsonObject)["id"]!!.jsonPrimitive.content }
                ids shouldBe listOf(visible.toString())
                ids.contains(hidden.toString()) shouldBe false
            }
        } finally {
            cleanup(viewer, author)
        }
    }

    "bidirectional block exclusion — A blocked B (viewer = A): B's post hidden" {
        val (a, ta) = seedUser()
        val (b, _) = seedUser()
        try {
            val bp = seedPost(b, -6.200, 106.800)
            // A blocks B
            dataSource.connection.use { conn ->
                conn.prepareStatement("INSERT INTO user_blocks (blocker_id, blocked_id) VALUES (?, ?)").use { ps ->
                    ps.setObject(1, a)
                    ps.setObject(2, b)
                    ps.executeUpdate()
                }
            }
            withTimeline {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/timeline/nearby?lat=-6.2&lng=106.8&radius_m=5000") {
                            header(HttpHeaders.Authorization, "Bearer $ta")
                        }
                val ids =
                    Json.parseToJsonElement(resp.bodyAsText())
                        .jsonObject["posts"]!!.jsonArray
                        .map { (it as JsonObject)["id"]!!.jsonPrimitive.content }
                ids.contains(bp.toString()) shouldBe false
            }
        } finally {
            cleanup(a, b)
        }
    }

    "bidirectional block exclusion — B blocked A (viewer = A): B's post hidden" {
        val (a, ta) = seedUser()
        val (b, _) = seedUser()
        try {
            val bp = seedPost(b, -6.200, 106.800)
            // B blocks A — viewer A should still NOT see B's posts.
            dataSource.connection.use { conn ->
                conn.prepareStatement("INSERT INTO user_blocks (blocker_id, blocked_id) VALUES (?, ?)").use { ps ->
                    ps.setObject(1, b)
                    ps.setObject(2, a)
                    ps.executeUpdate()
                }
            }
            withTimeline {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/timeline/nearby?lat=-6.2&lng=106.8&radius_m=5000") {
                            header(HttpHeaders.Authorization, "Bearer $ta")
                        }
                val ids =
                    Json.parseToJsonElement(resp.bodyAsText())
                        .jsonObject["posts"]!!.jsonArray
                        .map { (it as JsonObject)["id"]!!.jsonPrimitive.content }
                ids.contains(bp.toString()) shouldBe false
            }
        } finally {
            cleanup(a, b)
        }
    }

    "out-of-envelope coordinates — 400 location_out_of_bounds (NY)" {
        val (viewer, vt) = seedUser()
        try {
            withTimeline {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/timeline/nearby?lat=40.0&lng=-74.0&radius_m=1000") {
                            header(HttpHeaders.Authorization, "Bearer $vt")
                        }
                resp.status shouldBe HttpStatusCode.BadRequest
                Json.parseToJsonElement(resp.bodyAsText())
                    .jsonObject["error"]!!.jsonObject["code"]!!
                    .jsonPrimitive.content shouldBe "location_out_of_bounds"
            }
        } finally {
            cleanup(viewer)
        }
    }

    "radius bounds — 50 (too small) and 100000 (too large) rejected; 100 and 50000 accepted" {
        val (viewer, vt) = seedUser()
        try {
            withTimeline {
                val client = createClient { install(ClientCN) { json() } }
                val tooSmall =
                    client.get("/api/v1/timeline/nearby?lat=-6.2&lng=106.8&radius_m=50") {
                        header(HttpHeaders.Authorization, "Bearer $vt")
                    }
                tooSmall.status shouldBe HttpStatusCode.BadRequest
                Json.parseToJsonElement(tooSmall.bodyAsText())
                    .jsonObject["error"]!!.jsonObject["code"]!!
                    .jsonPrimitive.content shouldBe "radius_out_of_bounds"
                val tooLarge =
                    client.get("/api/v1/timeline/nearby?lat=-6.2&lng=106.8&radius_m=100000") {
                        header(HttpHeaders.Authorization, "Bearer $vt")
                    }
                tooLarge.status shouldBe HttpStatusCode.BadRequest
                client.get("/api/v1/timeline/nearby?lat=-6.2&lng=106.8&radius_m=100") {
                    header(HttpHeaders.Authorization, "Bearer $vt")
                }.status shouldBe HttpStatusCode.OK
                client.get("/api/v1/timeline/nearby?lat=-6.2&lng=106.8&radius_m=50000") {
                    header(HttpHeaders.Authorization, "Bearer $vt")
                }.status shouldBe HttpStatusCode.OK
            }
        } finally {
            cleanup(viewer)
        }
    }

    "auth required — 401 without JWT" {
        withTimeline {
            createClient { install(ClientCN) { json() } }
                .get("/api/v1/timeline/nearby?lat=-6.2&lng=106.8&radius_m=1000")
                .status shouldBe HttpStatusCode.Unauthorized
        }
    }

    "liked_by_viewer — true when caller has liked the post" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        try {
            val p = seedPost(author, -6.200, 106.800)
            dataSource.connection.use { conn ->
                conn.prepareStatement("INSERT INTO post_likes (post_id, user_id) VALUES (?, ?)").use { ps ->
                    ps.setObject(1, p)
                    ps.setObject(2, viewer)
                    ps.executeUpdate()
                }
            }
            withTimeline {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/timeline/nearby?lat=-6.2&lng=106.8&radius_m=5000") {
                            header(HttpHeaders.Authorization, "Bearer $vt")
                        }
                val post =
                    Json.parseToJsonElement(resp.bodyAsText())
                        .jsonObject["posts"]!!.jsonArray
                        .first { (it as JsonObject)["id"]!!.jsonPrimitive.content == p.toString() }
                        .jsonObject
                post["liked_by_viewer"]!!.jsonPrimitive.content shouldBe "true"
            }
        } finally {
            cleanup(viewer, author)
        }
    }

    "liked_by_viewer — false when caller has not liked the post" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        try {
            val p = seedPost(author, -6.200, 106.800)
            withTimeline {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/timeline/nearby?lat=-6.2&lng=106.8&radius_m=5000") {
                            header(HttpHeaders.Authorization, "Bearer $vt")
                        }
                val post =
                    Json.parseToJsonElement(resp.bodyAsText())
                        .jsonObject["posts"]!!.jsonArray
                        .first { (it as JsonObject)["id"]!!.jsonPrimitive.content == p.toString() }
                        .jsonObject
                post["liked_by_viewer"]!!.jsonPrimitive.content shouldBe "false"
            }
        } finally {
            cleanup(viewer, author)
        }
    }

    "liked_by_viewer — key present on every post in the response" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        try {
            val posts = (0 until 5).map { seedPost(author, -6.200 + it * 0.0001, 106.800) }
            // Like only some of them to cover the mixed case.
            dataSource.connection.use { conn ->
                conn.prepareStatement("INSERT INTO post_likes (post_id, user_id) VALUES (?, ?)").use { ps ->
                    ps.setObject(1, posts[0])
                    ps.setObject(2, viewer)
                    ps.executeUpdate()
                    ps.setObject(1, posts[2])
                    ps.setObject(2, viewer)
                    ps.executeUpdate()
                }
            }
            withTimeline {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/timeline/nearby?lat=-6.2&lng=106.8&radius_m=5000") {
                            header(HttpHeaders.Authorization, "Bearer $vt")
                        }
                val arr = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["posts"]!!.jsonArray
                arr.forEach { (it as JsonObject).containsKey("liked_by_viewer") shouldBe true }
            }
        } finally {
            cleanup(viewer, author)
        }
    }

    "liked_by_viewer — cardinality invariant: 35 visible posts with 7 liked → 35 returned, not 42" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        try {
            val posts =
                (0 until 35).map {
                    seedPost(author, -6.200 + it * 0.0001, 106.800)
                        .also { _ -> Thread.sleep(2) }
                }
            // Like 7 of them.
            dataSource.connection.use { conn ->
                conn.prepareStatement("INSERT INTO post_likes (post_id, user_id) VALUES (?, ?)").use { ps ->
                    posts.take(7).forEach { pid ->
                        ps.setObject(1, pid)
                        ps.setObject(2, viewer)
                        ps.executeUpdate()
                    }
                }
            }
            withTimeline {
                val client = createClient { install(ClientCN) { json() } }
                val r1 =
                    client.get("/api/v1/timeline/nearby?lat=-6.2&lng=106.8&radius_m=5000") {
                        header(HttpHeaders.Authorization, "Bearer $vt")
                    }
                val b1 = Json.parseToJsonElement(r1.bodyAsText()).jsonObject
                b1["posts"]!!.jsonArray shouldHaveSize 30
                val cursor = b1["nextCursor"]!!.jsonPrimitive.content
                val r2 =
                    client.get("/api/v1/timeline/nearby?lat=-6.2&lng=106.8&radius_m=5000&cursor=$cursor") {
                        header(HttpHeaders.Authorization, "Bearer $vt")
                    }
                val b2 = Json.parseToJsonElement(r2.bodyAsText()).jsonObject
                b2["posts"]!!.jsonArray shouldHaveSize 5
                val ids1 = b1["posts"]!!.jsonArray.map { (it as JsonObject)["id"]!!.jsonPrimitive.content }.toSet()
                val ids2 = b2["posts"]!!.jsonArray.map { (it as JsonObject)["id"]!!.jsonPrimitive.content }.toSet()
                (ids1 + ids2).size shouldBe 35
            }
        } finally {
            cleanup(viewer, author)
        }
    }

    "invalid cursor — 400 invalid_cursor" {
        val (viewer, vt) = seedUser()
        try {
            withTimeline {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/timeline/nearby?lat=-6.2&lng=106.8&radius_m=1000&cursor=not-a-real-cursor") {
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

    "reply_count — 0 for post with no replies" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        try {
            val p = seedPost(author, -6.200, 106.800)
            withTimeline {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/timeline/nearby?lat=-6.2&lng=106.8&radius_m=5000") {
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
            val p = seedPost(author, -6.200, 106.800)
            seedReply(p, replier)
            seedReply(p, replier)
            seedReply(p, replier)
            withTimeline {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/timeline/nearby?lat=-6.2&lng=106.8&radius_m=5000") {
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
            val p = seedPost(author, -6.200, 106.800)
            // 3 replies: 2 visible + 1 shadow-banned → reply_count = 2.
            seedReply(p, replierVisible)
            seedReply(p, replierVisible)
            seedReply(p, replierBanned)
            withTimeline {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/timeline/nearby?lat=-6.2&lng=106.8&radius_m=5000") {
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
            val p = seedPost(author, -6.200, 106.800)
            seedReply(p, replier)
            seedReply(p, replier)
            seedReply(p, replier)
            seedReply(p, replier, deletedAt = java.time.Instant.now())
            seedReply(p, replier, deletedAt = java.time.Instant.now())
            withTimeline {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/timeline/nearby?lat=-6.2&lng=106.8&radius_m=5000") {
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
            val p = seedPost(author, -6.200, 106.800)
            // Viewer blocks replierBlocked — their reply is still counted.
            insertBlock(viewer, replierBlocked)
            seedReply(p, replierOk1)
            seedReply(p, replierOk2)
            seedReply(p, replierBlocked)
            withTimeline {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/timeline/nearby?lat=-6.2&lng=106.8&radius_m=5000") {
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

    "reply_count — key present on every post" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        try {
            repeat(3) {
                seedPost(author, -6.200 + it * 0.0001, 106.800)
                Thread.sleep(2)
            }
            withTimeline {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/timeline/nearby?lat=-6.2&lng=106.8&radius_m=5000") {
                            header(HttpHeaders.Authorization, "Bearer $vt")
                        }
                val arr = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["posts"]!!.jsonArray
                arr.forEach { (it as JsonObject).containsKey("reply_count") shouldBe true }
            }
        } finally {
            cleanup(viewer, author)
        }
    }

    "reply_count — LATERAL cardinality invariant: 5 posts with 20 total replies → 5 rows, not 25" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        val (replier, _) = seedUser()
        try {
            val posts =
                (0 until 5).map {
                    seedPost(author, -6.200 + it * 0.0001, 106.800).also { Thread.sleep(2) }
                }
            // Spread 20 replies across the 5 posts (4 each).
            posts.forEach { pid -> repeat(4) { seedReply(pid, replier) } }
            withTimeline {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/timeline/nearby?lat=-6.2&lng=106.8&radius_m=5000") {
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

    // ---- V11: city_name tests (nearby-timeline spec delta) ----
    //
    // Session 1 note: admin_regions is empty in this session, so real trigger-populated
    // matches are covered by Session 2's MigrationV11SmokeTest once the polygon seed lands.
    // These scenarios exercise the trigger's caller-override guard to simulate a populated
    // row, plus the NULL-as-"" path with the default seedPost (trigger falls through to
    // step 4 → NULL with an empty admin_regions table).

    fun seedPostWithCity(
        authorId: UUID,
        cityName: String?,
        lat: Double = -6.200,
        lng: Double = 106.800,
    ): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO posts (id, author_id, content, display_location, actual_location, is_auto_hidden, city_name)
                VALUES (?, ?, ?,
                  ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                  ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                  FALSE, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setObject(2, authorId)
                ps.setString(3, "post-${id.toString().take(6)}")
                ps.setDouble(4, lng)
                ps.setDouble(5, lat)
                ps.setDouble(6, lng)
                ps.setDouble(7, lat)
                if (cityName != null) ps.setString(8, cityName) else ps.setNull(8, java.sql.Types.VARCHAR)
                ps.executeUpdate()
            }
        }
        return id
    }

    "city_name — key present on every Nearby post and is always a string" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        try {
            seedPostWithCity(author, cityName = "Jakarta Pusat", lat = -6.200, lng = 106.800)
            Thread.sleep(2)
            seedPostWithCity(author, cityName = null, lat = -6.201, lng = 106.801)
            Thread.sleep(2)
            seedPostWithCity(author, cityName = "Jakarta Selatan", lat = -6.202, lng = 106.802)
            withTimeline {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/timeline/nearby?lat=-6.2&lng=106.8&radius_m=5000") {
                            header(HttpHeaders.Authorization, "Bearer $vt")
                        }
                val arr = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["posts"]!!.jsonArray
                arr.forEach {
                    val obj = it as JsonObject
                    obj.containsKey("city_name") shouldBe true
                    obj["city_name"]!!.jsonPrimitive.isString shouldBe true
                }
            }
        } finally {
            cleanup(viewer, author)
        }
    }

    "city_name — reflects trigger-populated (or override-supplied) value on Nearby" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        try {
            val p = seedPostWithCity(author, cityName = "Surabaya", lat = -6.200, lng = 106.800)
            withTimeline {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/timeline/nearby?lat=-6.2&lng=106.8&radius_m=5000") {
                            header(HttpHeaders.Authorization, "Bearer $vt")
                        }
                val post =
                    Json.parseToJsonElement(resp.bodyAsText())
                        .jsonObject["posts"]!!.jsonArray
                        .first { (it as JsonObject)["id"]!!.jsonPrimitive.content == p.toString() }
                        .jsonObject
                post["city_name"]!!.jsonPrimitive.content shouldBe "Surabaya"
            }
        } finally {
            cleanup(viewer, author)
        }
    }

    "city_name — empty string for Nearby post whose underlying row is NULL" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        try {
            // Deep Indian Ocean (>50km from any kabupaten even with 12nm maritime
            // buffer applied). cityName=null + trigger step 4 ⇒ NULL underlying row.
            // Viewer + post both inside the envelope [-11, 6.5] × [94, 142], within
            // the 5km radius filter.
            val p = seedPostWithCity(author, cityName = null, lat = -10.500, lng = 105.000)
            withTimeline {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/timeline/nearby?lat=-10.5&lng=105.0&radius_m=5000") {
                            header(HttpHeaders.Authorization, "Bearer $vt")
                        }
                val post =
                    Json.parseToJsonElement(resp.bodyAsText())
                        .jsonObject["posts"]!!.jsonArray
                        .first { (it as JsonObject)["id"]!!.jsonPrimitive.content == p.toString() }
                        .jsonObject
                post["city_name"]!!.jsonPrimitive.content shouldBe ""
            }
        } finally {
            cleanup(viewer, author)
        }
    }
})
