package id.nearyou.app.engagement

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.auth.configureUserJwt
import id.nearyou.app.auth.jwt.JwtIssuer
import id.nearyou.app.auth.jwt.RsaKeyLoader
import id.nearyou.app.auth.jwt.TestKeys
import id.nearyou.app.guard.ContentEmptyException
import id.nearyou.app.guard.ContentLengthGuard
import id.nearyou.app.guard.ContentTooLongException
import id.nearyou.app.infra.repo.JdbcPostReplyRepository
import id.nearyou.app.infra.repo.JdbcUserRepository
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.ktor.client.request.delete
import io.ktor.client.request.get
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
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.sql.Date
import java.sql.Timestamp
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
            maximumPoolSize = 4
            initializationFailTimeout = -1
        }
    return HikariDataSource(config)
}

@Tags("database")
class ReplyEndpointsTest : StringSpec({

    val dataSource = hikari()
    val keys = RsaKeyLoader(TestKeys.freshEncodedPemPrivateKey(), kid = "test-reply")
    val jwtIssuer = JwtIssuer(keys)
    val users = JdbcUserRepository(dataSource)
    val repo = JdbcPostReplyRepository(dataSource)
    val service = ReplyService(repo)
    val contentGuard = ContentLengthGuard(mapOf(REPLY_CONTENT_KEY to 280))

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
                ps.setString(2, "re_$short")
                ps.setString(3, "Reply Endpoint Tester")
                ps.setDate(4, Date.valueOf(LocalDate.of(1990, 1, 1)))
                ps.setString(5, "e${short.take(7)}")
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
                ps.setString(3, "reply-post-${id.toString().take(6)}")
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

    fun seedReply(
        postId: UUID,
        authorId: UUID,
        autoHidden: Boolean = false,
        deletedAt: Instant? = null,
        content: String = "reply-${UUID.randomUUID().toString().take(6)}",
    ): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "INSERT INTO post_replies (id, post_id, author_id, content, is_auto_hidden, deleted_at) VALUES (?, ?, ?, ?, ?, ?)",
            ).use { ps ->
                ps.setObject(1, id)
                ps.setObject(2, postId)
                ps.setObject(3, authorId)
                ps.setString(4, content)
                ps.setBoolean(5, autoHidden)
                if (deletedAt != null) {
                    ps.setTimestamp(6, Timestamp.from(deletedAt))
                } else {
                    ps.setNull(6, java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                }
                ps.executeUpdate()
            }
        }
        return id
    }

    fun replyRow(replyId: UUID): Triple<Instant?, UUID, String>? {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT deleted_at, author_id, content FROM post_replies WHERE id = ?",
            ).use { ps ->
                ps.setObject(1, replyId)
                ps.executeQuery().use { rs ->
                    return if (rs.next()) {
                        Triple(
                            rs.getTimestamp("deleted_at")?.toInstant(),
                            rs.getObject("author_id", UUID::class.java),
                            rs.getString("content"),
                        )
                    } else {
                        null
                    }
                }
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

    suspend fun withReplies(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) {
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
                    // If the route handler ever lets ContentEmpty/TooLong leak through,
                    // StatusPages would map them to content_empty/content_too_long — the
                    // route maps them to invalid_request locally, so these handlers must
                    // NEVER fire; they exist only to keep the test app self-contained.
                    exception<ContentEmptyException> { call, _ ->
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to mapOf("code" to "content_empty", "message" to "empty")),
                        )
                    }
                    exception<ContentTooLongException> { call, _ ->
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to mapOf("code" to "content_too_long", "message" to "too long")),
                        )
                    }
                }
                install(Authentication) { configureUserJwt(keys, users, java.time.Instant::now) }
                replyRoutes(service, contentGuard)
            }
            block()
        }
    }

    // ---- POST /replies ----

    "POST /replies — happy path returns 201 with full reply JSON" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        try {
            val p = seedPost(author)
            withReplies {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .post("/api/v1/posts/$p/replies") {
                            header(HttpHeaders.Authorization, "Bearer $vt")
                            contentType(ContentType.Application.Json)
                            setBody("""{"content":"great post"}""")
                        }
                resp.status shouldBe HttpStatusCode.Created
                val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
                body["author_id"]!!.jsonPrimitive.content shouldBe viewer.toString()
                body["post_id"]!!.jsonPrimitive.content shouldBe p.toString()
                body["content"]!!.jsonPrimitive.content shouldBe "great post"
                body["is_auto_hidden"]!!.jsonPrimitive.booleanOrNull shouldBe false
                body.containsKey("created_at") shouldBe true
                body["updated_at"] shouldBe null
                body["deleted_at"] shouldBe null
            }
        } finally {
            cleanup(viewer, author)
        }
    }

    "POST /replies — non-UUID path returns 400 invalid_uuid (no DB write)" {
        val (viewer, vt) = seedUser()
        try {
            withReplies {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .post("/api/v1/posts/not-a-uuid/replies") {
                            header(HttpHeaders.Authorization, "Bearer $vt")
                            contentType(ContentType.Application.Json)
                            setBody("""{"content":"hi"}""")
                        }
                resp.status shouldBe HttpStatusCode.BadRequest
                Json.parseToJsonElement(resp.bodyAsText())
                    .jsonObject["error"]!!.jsonObject["code"]!!
                    .jsonPrimitive.content shouldBe "invalid_uuid"
            }
        } finally {
            cleanup(viewer)
        }
    }

    "POST /replies — empty content returns 400 invalid_request" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        try {
            val p = seedPost(author)
            withReplies {
                val client = createClient { install(ClientCN) { json() } }
                val cases = listOf("""{"content":""}""", """{"content":"   "}""")
                for (body in cases) {
                    val resp =
                        client.post("/api/v1/posts/$p/replies") {
                            header(HttpHeaders.Authorization, "Bearer $vt")
                            contentType(ContentType.Application.Json)
                            setBody(body)
                        }
                    resp.status shouldBe HttpStatusCode.BadRequest
                    Json.parseToJsonElement(resp.bodyAsText())
                        .jsonObject["error"]!!.jsonObject["code"]!!
                        .jsonPrimitive.content shouldBe "invalid_request"
                }
            }
        } finally {
            cleanup(viewer, author)
        }
    }

    "POST /replies — 281-char content returns 400 invalid_request" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        try {
            val p = seedPost(author)
            val tooLong = "x".repeat(281)
            withReplies {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .post("/api/v1/posts/$p/replies") {
                            header(HttpHeaders.Authorization, "Bearer $vt")
                            contentType(ContentType.Application.Json)
                            setBody("""{"content":"$tooLong"}""")
                        }
                resp.status shouldBe HttpStatusCode.BadRequest
                Json.parseToJsonElement(resp.bodyAsText())
                    .jsonObject["error"]!!.jsonObject["code"]!!
                    .jsonPrimitive.content shouldBe "invalid_request"
            }
        } finally {
            cleanup(viewer, author)
        }
    }

    "POST /replies — missing/null content returns 400 invalid_request" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        try {
            val p = seedPost(author)
            withReplies {
                val client = createClient { install(ClientCN) { json() } }
                val cases = listOf("""{}""", """{"content":null}""")
                for (body in cases) {
                    val resp =
                        client.post("/api/v1/posts/$p/replies") {
                            header(HttpHeaders.Authorization, "Bearer $vt")
                            contentType(ContentType.Application.Json)
                            setBody(body)
                        }
                    resp.status shouldBe HttpStatusCode.BadRequest
                    Json.parseToJsonElement(resp.bodyAsText())
                        .jsonObject["error"]!!.jsonObject["code"]!!
                        .jsonPrimitive.content shouldBe "invalid_request"
                }
            }
        } finally {
            cleanup(viewer, author)
        }
    }

    "POST /replies — author_id comes from JWT, not body" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        val spoofId = UUID.randomUUID()
        try {
            val p = seedPost(author)
            withReplies {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .post("/api/v1/posts/$p/replies") {
                            header(HttpHeaders.Authorization, "Bearer $vt")
                            contentType(ContentType.Application.Json)
                            setBody("""{"content":"hi","author_id":"$spoofId"}""")
                        }
                resp.status shouldBe HttpStatusCode.Created
                val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
                body["author_id"]!!.jsonPrimitive.content shouldBe viewer.toString()
            }
        } finally {
            cleanup(viewer, author)
        }
    }

    "POST /replies — 404 on missing/auto-hidden/caller-blocked/author-blocked (opaque envelope)" {
        // Note: the "soft-deleted post" sub-case from post-replies-v8 spec is currently
        // unreachable — V4 `visible_posts` filters only `is_auto_hidden = FALSE`, not
        // `deleted_at IS NULL`, and no code path soft-deletes posts at runtime. When a
        // future change extends `visible_posts` to also gate on `deleted_at`, the
        // identical opaque envelope will automatically apply (no code change here).
        val (v1, t1) = seedUser()
        val (a1, _) = seedUser()
        val missingUuid = UUID.randomUUID()
        val autoHiddenPost = seedPost(a1, autoHidden = true)
        val (v2, t2) = seedUser()
        val (a2, _) = seedUser()
        val p2 = seedPost(a2)
        val (v3, t3) = seedUser()
        val (a3, _) = seedUser()
        val p3 = seedPost(a3)
        try {
            insertBlock(v2, a2) // v2 blocks a2
            insertBlock(a3, v3) // a3 blocks v3
            val bodies = mutableListOf<String>()
            withReplies {
                val client = createClient { install(ClientCN) { json() } }
                bodies +=
                    client.post("/api/v1/posts/$missingUuid/replies") {
                        header(HttpHeaders.Authorization, "Bearer $t1")
                        contentType(ContentType.Application.Json)
                        setBody("""{"content":"hi"}""")
                    }.bodyAsText()
                bodies +=
                    client.post("/api/v1/posts/$autoHiddenPost/replies") {
                        header(HttpHeaders.Authorization, "Bearer $t1")
                        contentType(ContentType.Application.Json)
                        setBody("""{"content":"hi"}""")
                    }.bodyAsText()
                bodies +=
                    client.post("/api/v1/posts/$p2/replies") {
                        header(HttpHeaders.Authorization, "Bearer $t2")
                        contentType(ContentType.Application.Json)
                        setBody("""{"content":"hi"}""")
                    }.bodyAsText()
                bodies +=
                    client.post("/api/v1/posts/$p3/replies") {
                        header(HttpHeaders.Authorization, "Bearer $t3")
                        contentType(ContentType.Application.Json)
                        setBody("""{"content":"hi"}""")
                    }.bodyAsText()
            }
            bodies[0] shouldBe "{\"error\":{\"code\":\"post_not_found\"}}"
            bodies.toSet().size shouldBe 1
        } finally {
            cleanup(v1, a1, v2, a2, v3, a3)
        }
    }

    // ---- GET /replies ----

    "GET /replies — happy path three replies ordered DESC by created_at, id" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        val (replier, _) = seedUser()
        try {
            val p = seedPost(author)
            val r1 = seedReply(p, replier)
            Thread.sleep(3)
            val r2 = seedReply(p, replier)
            Thread.sleep(3)
            val r3 = seedReply(p, replier)
            withReplies {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/posts/$p/replies") {
                            header(HttpHeaders.Authorization, "Bearer $vt")
                        }
                resp.status shouldBe HttpStatusCode.OK
                val ids =
                    Json.parseToJsonElement(resp.bodyAsText()).jsonObject["replies"]!!
                        .jsonArray.map { (it as JsonObject)["id"]!!.jsonPrimitive.content }
                ids shouldBe listOf(r3.toString(), r2.toString(), r1.toString())
            }
        } finally {
            cleanup(viewer, author, replier)
        }
    }

    "GET /replies — invisible parent returns 404 post_not_found (opaque, NOT empty list)" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        try {
            val p = seedPost(author, autoHidden = true)
            withReplies {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/posts/$p/replies") {
                            header(HttpHeaders.Authorization, "Bearer $vt")
                        }
                resp.status shouldBe HttpStatusCode.NotFound
                resp.bodyAsText() shouldBe "{\"error\":{\"code\":\"post_not_found\"}}"
            }
        } finally {
            cleanup(viewer, author)
        }
    }

    "GET /replies — cursor pagination: 35 replies → 30 + cursor → 5, no overlap" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        val (replier, _) = seedUser()
        try {
            val p = seedPost(author)
            val replyIds = (0 until 35).map { seedReply(p, replier).also { Thread.sleep(2) } }
            withReplies {
                val client = createClient { install(ClientCN) { json() } }
                val r1 =
                    client.get("/api/v1/posts/$p/replies") {
                        header(HttpHeaders.Authorization, "Bearer $vt")
                    }
                val b1 = Json.parseToJsonElement(r1.bodyAsText()).jsonObject
                b1["replies"]!!.jsonArray shouldHaveSize 30
                val cursor = b1["next_cursor"]!!.jsonPrimitive.content
                val r2 =
                    client.get("/api/v1/posts/$p/replies?cursor=$cursor") {
                        header(HttpHeaders.Authorization, "Bearer $vt")
                    }
                val b2 = Json.parseToJsonElement(r2.bodyAsText()).jsonObject
                b2["replies"]!!.jsonArray shouldHaveSize 5
                val ids1 = b1["replies"]!!.jsonArray.map { (it as JsonObject)["id"]!!.jsonPrimitive.content }.toSet()
                val ids2 = b2["replies"]!!.jsonArray.map { (it as JsonObject)["id"]!!.jsonPrimitive.content }.toSet()
                (ids1 + ids2).size shouldBe 35
                (ids1 + ids2).containsAll(replyIds.map { it.toString() }) shouldBe true
            }
        } finally {
            cleanup(viewer, author, replier)
        }
    }

    "GET /replies — excludes soft-deleted replies for everyone including author" {
        val (author, at) = seedUser()
        try {
            val p = seedPost(author)
            val active = seedReply(p, author)
            seedReply(p, author, deletedAt = Instant.now())
            withReplies {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/posts/$p/replies") {
                            header(HttpHeaders.Authorization, "Bearer $at")
                        }
                val ids =
                    Json.parseToJsonElement(resp.bodyAsText()).jsonObject["replies"]!!
                        .jsonArray.map { (it as JsonObject)["id"]!!.jsonPrimitive.content }
                ids shouldBe listOf(active.toString())
            }
        } finally {
            cleanup(author)
        }
    }

    "GET /replies — excludes shadow-banned repliers via visible_users JOIN" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        val (visibleReplier, _) = seedUser()
        val (bannedReplier, _) = seedUser(shadowBanned = true)
        try {
            val p = seedPost(author)
            val rVisible = seedReply(p, visibleReplier)
            seedReply(p, bannedReplier)
            withReplies {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/posts/$p/replies") {
                            header(HttpHeaders.Authorization, "Bearer $vt")
                        }
                val ids =
                    Json.parseToJsonElement(resp.bodyAsText()).jsonObject["replies"]!!
                        .jsonArray.map { (it as JsonObject)["id"]!!.jsonPrimitive.content }
                ids shouldBe listOf(rVisible.toString())
            }
        } finally {
            cleanup(viewer, author, visibleReplier, bannedReplier)
        }
    }

    "GET /replies — bidirectional block exclusion on reply author" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        val (ok, _) = seedUser()
        val (callerBlocked, _) = seedUser()
        val (authorBlockedCaller, _) = seedUser()
        try {
            val p = seedPost(author)
            val rOk = seedReply(p, ok)
            seedReply(p, callerBlocked)
            seedReply(p, authorBlockedCaller)
            insertBlock(viewer, callerBlocked)
            insertBlock(authorBlockedCaller, viewer)
            withReplies {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/posts/$p/replies") {
                            header(HttpHeaders.Authorization, "Bearer $vt")
                        }
                val ids =
                    Json.parseToJsonElement(resp.bodyAsText()).jsonObject["replies"]!!
                        .jsonArray.map { (it as JsonObject)["id"]!!.jsonPrimitive.content }
                ids shouldBe listOf(rOk.toString())
            }
        } finally {
            cleanup(viewer, author, ok, callerBlocked, authorBlockedCaller)
        }
    }

    "GET /replies — auto-hidden reply visible to its author only" {
        val (author, at) = seedUser()
        val (other, ot) = seedUser()
        try {
            val p = seedPost(author)
            val r = seedReply(p, author, autoHidden = true)
            withReplies {
                val client = createClient { install(ClientCN) { json() } }
                val authorResp =
                    client.get("/api/v1/posts/$p/replies") {
                        header(HttpHeaders.Authorization, "Bearer $at")
                    }
                val authorIds =
                    Json.parseToJsonElement(authorResp.bodyAsText()).jsonObject["replies"]!!
                        .jsonArray.map { (it as JsonObject)["id"]!!.jsonPrimitive.content }
                authorIds shouldBe listOf(r.toString())

                val otherResp =
                    client.get("/api/v1/posts/$p/replies") {
                        header(HttpHeaders.Authorization, "Bearer $ot")
                    }
                Json.parseToJsonElement(otherResp.bodyAsText()).jsonObject["replies"]!!
                    .jsonArray shouldHaveSize 0
            }
        } finally {
            cleanup(author, other)
        }
    }

    "GET /replies — deleted_at is always null in response body" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        try {
            val p = seedPost(author)
            seedReply(p, author)
            seedReply(p, author)
            withReplies {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/posts/$p/replies") {
                            header(HttpHeaders.Authorization, "Bearer $vt")
                        }
                val arr = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["replies"]!!.jsonArray
                arr.forEach {
                    val deleted = (it as JsonObject)["deleted_at"]
                    (deleted == null || deleted.toString() == "null") shouldBe true
                }
            }
        } finally {
            cleanup(viewer, author)
        }
    }

    "GET /replies — malformed cursor returns 400 invalid_cursor" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        try {
            val p = seedPost(author)
            withReplies {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/posts/$p/replies?cursor=not-a-base64-json") {
                            header(HttpHeaders.Authorization, "Bearer $vt")
                        }
                resp.status shouldBe HttpStatusCode.BadRequest
                Json.parseToJsonElement(resp.bodyAsText())
                    .jsonObject["error"]!!.jsonObject["code"]!!
                    .jsonPrimitive.content shouldBe "invalid_cursor"
            }
        } finally {
            cleanup(viewer, author)
        }
    }

    // ---- DELETE /replies/{reply_id} ----

    "DELETE /replies — author soft-deletes own reply: 204 + deleted_at set" {
        val (author, at) = seedUser()
        try {
            val p = seedPost(author)
            val r = seedReply(p, author)
            withReplies {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .delete("/api/v1/posts/$p/replies/$r") {
                            header(HttpHeaders.Authorization, "Bearer $at")
                        }
                resp.status shouldBe HttpStatusCode.NoContent
            }
            val row = replyRow(r)!!
            (row.first != null) shouldBe true // deleted_at now populated
        } finally {
            cleanup(author)
        }
    }

    "DELETE /replies — non-author returns opaque 204 and row is unchanged" {
        val (author, _) = seedUser()
        val (other, ot) = seedUser()
        try {
            val p = seedPost(author)
            val r = seedReply(p, author)
            withReplies {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .delete("/api/v1/posts/$p/replies/$r") {
                            header(HttpHeaders.Authorization, "Bearer $ot")
                        }
                resp.status shouldBe HttpStatusCode.NoContent
            }
            val row = replyRow(r)!!
            row.first shouldBe null // still NOT deleted
            row.second shouldBe author
        } finally {
            cleanup(author, other)
        }
    }

    "DELETE /replies — already-tombstoned: second call stays 204 and stored deleted_at unchanged" {
        val (author, at) = seedUser()
        try {
            val p = seedPost(author)
            val preDeletion = Instant.now().minusSeconds(60)
            val r = seedReply(p, author, deletedAt = preDeletion)
            withReplies {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .delete("/api/v1/posts/$p/replies/$r") {
                            header(HttpHeaders.Authorization, "Bearer $at")
                        }
                resp.status shouldBe HttpStatusCode.NoContent
            }
            val row = replyRow(r)!!
            // deleted_at should be very close to preDeletion (within 1 second — the
            // WHERE guard stops the UPDATE from overwriting).
            val delta =
                kotlin.math.abs(row.first!!.toEpochMilli() - preDeletion.toEpochMilli())
            (delta < 2000) shouldBe true
        } finally {
            cleanup(author)
        }
    }

    "DELETE /replies — nonexistent reply_id returns 204 (no 404)" {
        val (author, at) = seedUser()
        try {
            val p = seedPost(author)
            val ghost = UUID.randomUUID()
            withReplies {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .delete("/api/v1/posts/$p/replies/$ghost") {
                            header(HttpHeaders.Authorization, "Bearer $at")
                        }
                resp.status shouldBe HttpStatusCode.NoContent
            }
        } finally {
            cleanup(author)
        }
    }

    "DELETE /replies — non-UUID reply_id returns 400 invalid_uuid" {
        val (author, at) = seedUser()
        try {
            val p = seedPost(author)
            withReplies {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .delete("/api/v1/posts/$p/replies/not-a-uuid") {
                            header(HttpHeaders.Authorization, "Bearer $at")
                        }
                resp.status shouldBe HttpStatusCode.BadRequest
                Json.parseToJsonElement(resp.bodyAsText())
                    .jsonObject["error"]!!.jsonObject["code"]!!
                    .jsonPrimitive.content shouldBe "invalid_uuid"
            }
        } finally {
            cleanup(author)
        }
    }

    // ---- Auth ----

    "auth — all three endpoints return 401 without JWT" {
        val ghostPost = UUID.randomUUID()
        val ghostReply = UUID.randomUUID()
        withReplies {
            val client = createClient { install(ClientCN) { json() } }
            client.post("/api/v1/posts/$ghostPost/replies") {
                contentType(ContentType.Application.Json)
                setBody("""{"content":"hi"}""")
            }.status shouldBe HttpStatusCode.Unauthorized
            client.get("/api/v1/posts/$ghostPost/replies").status shouldBe HttpStatusCode.Unauthorized
            client.delete("/api/v1/posts/$ghostPost/replies/$ghostReply").status shouldBe HttpStatusCode.Unauthorized
        }
    }

    // ---- Design Decision 2: no hard delete ever ----

    "no hard DELETE: grep assertion on ReplyService + JdbcPostReplyRepository sources" {
        // Resolve paths relative to the repo root. Gradle runs tests with the module
        // directory (backend/ktor) as the working directory, so step up two levels.
        val repoRoot = java.io.File("..").canonicalFile.parentFile
        val sources =
            listOf(
                java.io.File(repoRoot, "backend/ktor/src/main/kotlin/id/nearyou/app/engagement/ReplyService.kt"),
                java.io.File(repoRoot, "backend/ktor/src/main/kotlin/id/nearyou/app/engagement/ReplyRoutes.kt"),
                java.io.File(repoRoot, "infra/supabase/src/main/kotlin/id/nearyou/app/infra/repo/JdbcPostReplyRepository.kt"),
            )
        sources.forEach { file ->
            file.exists() shouldBe true
            // "DELETE FROM post_replies" must not appear anywhere in production sources.
            file.readText().contains("DELETE FROM post_replies") shouldBe false
        }
    }

    // ---- sanity: POST then GET then DELETE flow ----

    "end-to-end: POST reply, GET shows it, DELETE, GET now empty" {
        val (author, at) = seedUser()
        try {
            val p = seedPost(author)
            withReplies {
                val client = createClient { install(ClientCN) { json() } }
                val createResp =
                    client.post("/api/v1/posts/$p/replies") {
                        header(HttpHeaders.Authorization, "Bearer $at")
                        contentType(ContentType.Application.Json)
                        setBody("""{"content":"hello world"}""")
                    }
                createResp.status shouldBe HttpStatusCode.Created
                val replyId = Json.parseToJsonElement(createResp.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
                val getAfterCreate =
                    client.get("/api/v1/posts/$p/replies") {
                        header(HttpHeaders.Authorization, "Bearer $at")
                    }
                Json.parseToJsonElement(getAfterCreate.bodyAsText()).jsonObject["replies"]!!
                    .jsonArray shouldHaveSize 1
                val delResp =
                    client.delete("/api/v1/posts/$p/replies/$replyId") {
                        header(HttpHeaders.Authorization, "Bearer $at")
                    }
                delResp.status shouldBe HttpStatusCode.NoContent
                val getAfterDelete =
                    client.get("/api/v1/posts/$p/replies") {
                        header(HttpHeaders.Authorization, "Bearer $at")
                    }
                Json.parseToJsonElement(getAfterDelete.bodyAsText()).jsonObject["replies"]!!
                    .jsonArray shouldHaveSize 0
            }
        } finally {
            cleanup(author)
        }
    }
})
