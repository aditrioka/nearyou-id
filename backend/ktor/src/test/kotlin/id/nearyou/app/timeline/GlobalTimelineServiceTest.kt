package id.nearyou.app.timeline

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.auth.configureUserJwt
import id.nearyou.app.auth.jwt.JwtIssuer
import id.nearyou.app.auth.jwt.RsaKeyLoader
import id.nearyou.app.auth.jwt.TestKeys
import id.nearyou.app.infra.repo.JdbcPostsGlobalRepository
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
class GlobalTimelineServiceTest : StringSpec({

    val dataSource = hikari()
    val keys = RsaKeyLoader(TestKeys.freshEncodedPemPrivateKey(), kid = "test-global")
    val jwtIssuer = JwtIssuer(keys)
    val users = JdbcUserRepository(dataSource)
    val timeline = JdbcPostsGlobalRepository(dataSource)
    val service = GlobalTimelineService(timeline)

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
                ps.setString(2, "gt_$short")
                ps.setString(3, "Global Timeline Tester")
                ps.setDate(4, Date.valueOf(LocalDate.of(1990, 1, 1)))
                ps.setString(5, "j${short.take(7)}")
                ps.executeUpdate()
            }
        }
        val token = jwtIssuer.issueAccessToken(id, tokenVersion = 0)
        return id to token
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
                ps.setString(2, "gsb_$short")
                ps.setString(3, "Shadow Banned")
                ps.setDate(4, Date.valueOf(LocalDate.of(1990, 1, 1)))
                ps.setString(5, "k${short.take(7)}")
                ps.executeUpdate()
            }
        }
        return id
    }

    /**
     * Seed a post. By default lets the V11 `posts_set_city_tg` trigger populate `city_name`
     * based on the supplied `actual_location` + current `admin_regions` seed — in Session 1
     * that seed is empty, so the trigger falls through to step 4 and `city_name` stays NULL,
     * which is the legacy-row case the spec expects to render as "".
     *
     * Callers may pass [cityNameOverride] to short-circuit the trigger and pin a specific
     * label (spec: the trigger's caller-override guard — `IF NEW.city_name IS NOT NULL`).
     * This is how Session 1 tests simulate the "trigger-populated value" scenario without
     * needing the polygon seed.
     */
    fun seedPost(
        authorId: UUID,
        lat: Double = -6.200,
        lng: Double = 106.800,
        autoHidden: Boolean = false,
        cityNameOverride: String? = null,
    ): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO posts (id, author_id, content, display_location, actual_location, is_auto_hidden, city_name)
                VALUES (?, ?, ?,
                  ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                  ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                  ?, ?)
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
                if (cityNameOverride != null) {
                    ps.setString(9, cityNameOverride)
                } else {
                    ps.setNull(9, java.sql.Types.VARCHAR)
                }
                ps.executeUpdate()
            }
        }
        return id
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
                    st.executeUpdate("DELETE FROM post_replies WHERE author_id = '$it'")
                    st.executeUpdate("DELETE FROM posts WHERE author_id = '$it'")
                    st.executeUpdate("DELETE FROM users WHERE id = '$it'")
                }
            }
        }
    }

    suspend fun withGlobal(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) {
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
                globalTimelineRoutes(service)
            }
            block()
        }
    }

    // 1. Happy path: three posts by three different authors in three different cities,
    //    each carrying the expected city_name (simulated via cityNameOverride — Session 1
    //    lands without the polygon seed).
    "happy path — three posts from three authors with distinct city labels, DESC by created_at" {
        val (viewer, vt) = seedUser()
        val (a, _) = seedUser()
        val (b, _) = seedUser()
        val (c, _) = seedUser()
        try {
            val pa = seedPost(a, cityNameOverride = "Jakarta Pusat")
            Thread.sleep(10)
            val pb = seedPost(b, cityNameOverride = "Bandung")
            Thread.sleep(10)
            val pc = seedPost(c, cityNameOverride = "Surabaya")
            withGlobal {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/timeline/global") {
                            header(HttpHeaders.Authorization, "Bearer $vt")
                        }
                resp.status shouldBe HttpStatusCode.OK
                val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
                val arr = body["posts"]!!.jsonArray
                val ids = arr.map { (it as JsonObject)["id"]!!.jsonPrimitive.content }
                // Ordering: newest first. pa, pb, pc were created oldest → newest.
                ids.indexOf(pc.toString()) shouldBe 0
                ids.indexOf(pb.toString()) shouldBe 1
                ids.indexOf(pa.toString()) shouldBe 2
                // city_name labels
                val byId = arr.associate { (it as JsonObject)["id"]!!.jsonPrimitive.content to it }
                byId[pa.toString()]!!["city_name"]!!.jsonPrimitive.content shouldBe "Jakarta Pusat"
                byId[pb.toString()]!!["city_name"]!!.jsonPrimitive.content shouldBe "Bandung"
                byId[pc.toString()]!!["city_name"]!!.jsonPrimitive.content shouldBe "Surabaya"
            }
        } finally {
            cleanup(viewer, a, b, c)
        }
    }

    // 2. Cursor pagination: 35 posts → page 1 = 30 + nextCursor, page 2 = 5, no overlap.
    "cursor pagination — 35 posts, page 1 of 30 + cursor, page 2 of 5, no overlap" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        try {
            for (i in 0 until 35) {
                seedPost(author)
                Thread.sleep(2)
            }
            withGlobal {
                val client = createClient { install(ClientCN) { json() } }
                val r1 =
                    client.get("/api/v1/timeline/global") {
                        header(HttpHeaders.Authorization, "Bearer $vt")
                    }
                r1.status shouldBe HttpStatusCode.OK
                val b1 = Json.parseToJsonElement(r1.bodyAsText()).jsonObject
                b1["posts"]!!.jsonArray shouldHaveSize 30
                val cursor = b1["nextCursor"]!!.jsonPrimitive.content
                val r2 =
                    client.get("/api/v1/timeline/global?cursor=$cursor") {
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

    // 3. No follows filter: caller follows nobody, but still sees the stranger's post.
    "no follows filter — caller follows nobody yet still sees every visible author's post" {
        val (viewer, vt) = seedUser()
        val (stranger, _) = seedUser()
        try {
            val p = seedPost(stranger)
            withGlobal {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/timeline/global") {
                            header(HttpHeaders.Authorization, "Bearer $vt")
                        }
                val ids =
                    Json.parseToJsonElement(resp.bodyAsText())
                        .jsonObject["posts"]!!.jsonArray
                        .map { (it as JsonObject)["id"]!!.jsonPrimitive.content }
                ids.contains(p.toString()) shouldBe true
            }
        } finally {
            cleanup(viewer, stranger)
        }
    }

    // 4. Auto-hidden exclusion.
    "auto-hidden post excluded via visible_posts view" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        try {
            val visible = seedPost(author)
            val hidden = seedPost(author, autoHidden = true)
            withGlobal {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/timeline/global") {
                            header(HttpHeaders.Authorization, "Bearer $vt")
                        }
                val ids =
                    Json.parseToJsonElement(resp.bodyAsText())
                        .jsonObject["posts"]!!.jsonArray
                        .map { (it as JsonObject)["id"]!!.jsonPrimitive.content }
                ids.contains(visible.toString()) shouldBe true
                ids.contains(hidden.toString()) shouldBe false
            }
        } finally {
            cleanup(viewer, author)
        }
    }

    // 5a. Bidirectional block exclusion: viewer blocked author.
    "bidirectional block exclusion — viewer blocked author hides their posts" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        try {
            val p = seedPost(author)
            insertBlock(viewer, author)
            withGlobal {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/timeline/global") {
                            header(HttpHeaders.Authorization, "Bearer $vt")
                        }
                val ids =
                    Json.parseToJsonElement(resp.bodyAsText())
                        .jsonObject["posts"]!!.jsonArray
                        .map { (it as JsonObject)["id"]!!.jsonPrimitive.content }
                ids.contains(p.toString()) shouldBe false
            }
        } finally {
            cleanup(viewer, author)
        }
    }

    // 5b. Bidirectional block exclusion: author blocked viewer.
    "bidirectional block exclusion — author blocked viewer hides their posts" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        try {
            val p = seedPost(author)
            insertBlock(author, viewer)
            withGlobal {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/timeline/global") {
                            header(HttpHeaders.Authorization, "Bearer $vt")
                        }
                val ids =
                    Json.parseToJsonElement(resp.bodyAsText())
                        .jsonObject["posts"]!!.jsonArray
                        .map { (it as JsonObject)["id"]!!.jsonPrimitive.content }
                ids.contains(p.toString()) shouldBe false
            }
        } finally {
            cleanup(viewer, author)
        }
    }

    // 6. Auth required.
    "auth required — 401 without JWT" {
        withGlobal {
            createClient { install(ClientCN) { json() } }
                .get("/api/v1/timeline/global")
                .status shouldBe HttpStatusCode.Unauthorized
        }
    }

    // 7a. liked_by_viewer true.
    "liked_by_viewer — true when caller has liked a post" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        try {
            val p = seedPost(author)
            dataSource.connection.use { conn ->
                conn.prepareStatement("INSERT INTO post_likes (post_id, user_id) VALUES (?, ?)").use { ps ->
                    ps.setObject(1, p)
                    ps.setObject(2, viewer)
                    ps.executeUpdate()
                }
            }
            withGlobal {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/timeline/global") {
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

    // 7b. liked_by_viewer false.
    "liked_by_viewer — false when caller has not liked" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        try {
            val p = seedPost(author)
            withGlobal {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/timeline/global") {
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

    // 7c. liked_by_viewer key present on every post.
    "liked_by_viewer — key present on every Global post" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        try {
            repeat(5) {
                seedPost(author)
                Thread.sleep(2)
            }
            withGlobal {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/timeline/global") {
                            header(HttpHeaders.Authorization, "Bearer $vt")
                        }
                val arr = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["posts"]!!.jsonArray
                arr.forEach { (it as JsonObject).containsKey("liked_by_viewer") shouldBe true }
            }
        } finally {
            cleanup(viewer, author)
        }
    }

    // 8. LEFT JOIN cardinality invariant with likes.
    "liked_by_viewer — cardinality invariant: 20 visible with 7 liked → 20 rows, not 27" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        try {
            val posts = (0 until 20).map { seedPost(author).also { Thread.sleep(2) } }
            dataSource.connection.use { conn ->
                conn.prepareStatement("INSERT INTO post_likes (post_id, user_id) VALUES (?, ?)").use { ps ->
                    posts.take(7).forEach { pid ->
                        ps.setObject(1, pid)
                        ps.setObject(2, viewer)
                        ps.executeUpdate()
                    }
                }
            }
            withGlobal {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/timeline/global") {
                            header(HttpHeaders.Authorization, "Bearer $vt")
                        }
                val arr = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["posts"]!!.jsonArray
                // At most PAGE_SIZE=30; we seeded only 20 so expect exactly 20.
                arr shouldHaveSize 20
            }
        } finally {
            cleanup(viewer, author)
        }
    }

    // 9. reply_count = 0 for a post with no replies.
    "reply_count — 0 for a post with no replies" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        try {
            val p = seedPost(author)
            withGlobal {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/timeline/global") {
                            header(HttpHeaders.Authorization, "Bearer $vt")
                        }
                val post =
                    Json.parseToJsonElement(resp.bodyAsText())
                        .jsonObject["posts"]!!.jsonArray
                        .first { (it as JsonObject)["id"]!!.jsonPrimitive.content == p.toString() }
                        .jsonObject
                post["reply_count"]!!.jsonPrimitive.content shouldBe "0"
            }
        } finally {
            cleanup(viewer, author)
        }
    }

    // 10. reply_count excludes shadow-banned repliers.
    "reply_count — excludes shadow-banned repliers via visible_users JOIN" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        val (replierVisible, _) = seedUser()
        val replierBanned = seedShadowBannedUser()
        try {
            val p = seedPost(author)
            seedReply(p, replierVisible)
            seedReply(p, replierVisible)
            seedReply(p, replierBanned)
            withGlobal {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/timeline/global") {
                            header(HttpHeaders.Authorization, "Bearer $vt")
                        }
                val post =
                    Json.parseToJsonElement(resp.bodyAsText())
                        .jsonObject["posts"]!!.jsonArray
                        .first { (it as JsonObject)["id"]!!.jsonPrimitive.content == p.toString() }
                        .jsonObject
                post["reply_count"]!!.jsonPrimitive.content shouldBe "2"
            }
        } finally {
            cleanup(viewer, author, replierVisible, replierBanned)
        }
    }

    // 11. reply_count excludes soft-deleted replies.
    "reply_count — excludes soft-deleted replies" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        val (replier, _) = seedUser()
        try {
            val p = seedPost(author)
            seedReply(p, replier)
            seedReply(p, replier)
            seedReply(p, replier)
            seedReply(p, replier, deletedAt = java.time.Instant.now())
            seedReply(p, replier, deletedAt = java.time.Instant.now())
            withGlobal {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/timeline/global") {
                            header(HttpHeaders.Authorization, "Bearer $vt")
                        }
                val post =
                    Json.parseToJsonElement(resp.bodyAsText())
                        .jsonObject["posts"]!!.jsonArray
                        .first { (it as JsonObject)["id"]!!.jsonPrimitive.content == p.toString() }
                        .jsonObject
                post["reply_count"]!!.jsonPrimitive.content shouldBe "3"
            }
        } finally {
            cleanup(viewer, author, replier)
        }
    }

    // 12. reply_count does NOT apply viewer-block exclusion (privacy tradeoff).
    "reply_count — does NOT apply viewer-block exclusion (privacy tradeoff)" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        val (replierOk1, _) = seedUser()
        val (replierOk2, _) = seedUser()
        val (replierBlocked, _) = seedUser()
        try {
            insertBlock(viewer, replierBlocked)
            val p = seedPost(author)
            seedReply(p, replierOk1)
            seedReply(p, replierOk2)
            seedReply(p, replierBlocked)
            withGlobal {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/timeline/global") {
                            header(HttpHeaders.Authorization, "Bearer $vt")
                        }
                val post =
                    Json.parseToJsonElement(resp.bodyAsText())
                        .jsonObject["posts"]!!.jsonArray
                        .first { (it as JsonObject)["id"]!!.jsonPrimitive.content == p.toString() }
                        .jsonObject
                post["reply_count"]!!.jsonPrimitive.content shouldBe "3"
            }
        } finally {
            cleanup(viewer, author, replierOk1, replierOk2, replierBlocked)
        }
    }

    // 13. city_name reflects trigger-populated value.
    //     Session 1 note: admin_regions is empty in this session, so we exercise the
    //     trigger's caller-override guard (cityNameOverride) to pin a label. The full
    //     "trigger picks strict match from polygon" scenario is covered by Session 2's
    //     MigrationV11SmokeTest once the seed lands.
    "city_name — reflects trigger-populated (or override-supplied) value" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        try {
            val p = seedPost(author, cityNameOverride = "Jakarta Selatan")
            withGlobal {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/timeline/global") {
                            header(HttpHeaders.Authorization, "Bearer $vt")
                        }
                val post =
                    Json.parseToJsonElement(resp.bodyAsText())
                        .jsonObject["posts"]!!.jsonArray
                        .first { (it as JsonObject)["id"]!!.jsonPrimitive.content == p.toString() }
                        .jsonObject
                post["city_name"]!!.jsonPrimitive.content shouldBe "Jakarta Selatan"
            }
        } finally {
            cleanup(viewer, author)
        }
    }

    // 14. city_name = "" for legacy/NULL row.
    "city_name — empty string when underlying row is NULL" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        try {
            // No cityNameOverride + empty admin_regions seed ⇒ trigger leaves NULL.
            val p = seedPost(author)
            withGlobal {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/timeline/global") {
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

    // 15. city_name key present on every post, always string-typed.
    "city_name — key present on every Global post and is always a string" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        try {
            seedPost(author, cityNameOverride = "Jakarta Pusat")
            Thread.sleep(2)
            seedPost(author) // NULL → ""
            Thread.sleep(2)
            seedPost(author, cityNameOverride = "Surabaya")
            withGlobal {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/timeline/global") {
                            header(HttpHeaders.Authorization, "Bearer $vt")
                        }
                val arr = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["posts"]!!.jsonArray
                arr.forEach {
                    val obj = it as JsonObject
                    obj.containsKey("city_name") shouldBe true
                    // Always a JSON string primitive (never null).
                    obj["city_name"]!!.jsonPrimitive.isString shouldBe true
                }
            }
        } finally {
            cleanup(viewer, author)
        }
    }

    // 16. distance_m absent on every post in Global responses.
    "distance_m — absent from every Global post (Global is not geographic)" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        try {
            repeat(3) {
                seedPost(author)
                Thread.sleep(2)
            }
            withGlobal {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/timeline/global") {
                            header(HttpHeaders.Authorization, "Bearer $vt")
                        }
                val arr = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["posts"]!!.jsonArray
                arr.forEach {
                    val obj = it as JsonObject
                    obj.containsKey("distanceM") shouldBe false
                    obj.containsKey("distance_m") shouldBe false
                }
                // Documented full key set.
                val first = arr.first().jsonObject
                first.keys shouldBe
                    setOf(
                        "id",
                        "authorUserId",
                        "content",
                        "latitude",
                        "longitude",
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

    // 17. Malformed cursor → 400 invalid_cursor.
    "invalid cursor — 400 invalid_cursor" {
        val (viewer, vt) = seedUser()
        try {
            withGlobal {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/timeline/global?cursor=not-a-cursor") {
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
})
