package id.nearyou.app.post

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.auth.configureUserJwt
import id.nearyou.app.auth.jwt.JwtIssuer
import id.nearyou.app.auth.jwt.RsaKeyLoader
import id.nearyou.app.auth.jwt.TestKeys
import id.nearyou.app.common.installAppStatusPages
import id.nearyou.app.config.StubRemoteConfig
import id.nearyou.app.core.domain.ratelimit.InMemoryRateLimiter
import id.nearyou.app.guard.ContentLengthGuard
import id.nearyou.app.infra.repo.JdbcModerationQueueRepository
import id.nearyou.app.infra.repo.JdbcPostRepository
import id.nearyou.app.infra.repo.JdbcUserRepository
import id.nearyou.app.infra.repo.PostEditHistoryQuery
import id.nearyou.app.moderation.ModerationList
import id.nearyou.app.moderation.ModerationListLoader
import id.nearyou.app.moderation.TextModerator
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
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
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.sql.Date
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientCN

private fun hikari(): HikariDataSource {
    val config =
        HikariConfig().apply {
            jdbcUrl = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5433/nearyou_dev"
            username = System.getenv("DB_USER") ?: "postgres"
            password = System.getenv("DB_PASSWORD") ?: "postgres"
            // Frugal pool (2 conns) + autoClose: CI connection-budget rule (PR #157).
            maximumPoolSize = 2
            minimumIdle = 0
            initializationFailTimeout = -1
        }
    return HikariDataSource(config)
}

/**
 * Route-layer DB-tagged tests for `premium-post-editing`: the HTTP wire shape +
 * StatusPages mapping for `PATCH /api/v1/posts/{post_id}` and the visibility-checked
 * `GET /api/v1/posts/{post_id}/edits` history read (tasks 5.11–5.14, 5.18 + the
 * route mapping of the service exceptions). Service-internal behavior is in
 * [PostEditServiceTest].
 */
@Tags("database")
class PostEditRoutesTest : StringSpec({

    val dataSource = autoClose(hikari())
    val keys = RsaKeyLoader(TestKeys.freshEncodedPemPrivateKey(), kid = "test-post-edit")
    val jwtIssuer = JwtIssuer(keys)
    val users = JdbcUserRepository(dataSource)
    val realPosts = JdbcPostRepository()
    val moderationQueue = JdbcModerationQueueRepository()
    val contentGuard = ContentLengthGuard(mapOf("post.content" to 280))
    val historyQuery = PostEditHistoryQuery(dataSource)

    fun passThroughModerator(): TextModerator =
        TextModerator(
            object : ModerationListLoader {
                override fun load(list: ModerationList): List<String> = emptyList()

                override fun loadThreshold(): Int = 3
            },
        )

    fun service(): PostEditService =
        PostEditService(
            dataSource = dataSource,
            posts = realPosts,
            contentGuard = contentGuard,
            textModerator = passThroughModerator(),
            moderationQueue = moderationQueue,
            rateLimiter = PostEditRateLimiter(InMemoryRateLimiter()),
            remoteConfig = StubRemoteConfig(),
        )

    data class Seeded(val id: UUID, val token: String)

    fun seedUser(
        subscriptionStatus: String = "premium_active",
        shadowBanned: Boolean = false,
    ): Seeded {
        val id = UUID.randomUUID()
        val short = id.toString().replace("-", "").take(8)
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO users (id, username, display_name, date_of_birth, invite_code_prefix, subscription_status, is_shadow_banned)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "per_$short")
                ps.setString(3, "Post Edit Route Tester")
                ps.setDate(4, Date.valueOf(LocalDate.of(1990, 1, 1)))
                ps.setString(5, "r${short.take(7)}")
                ps.setString(6, subscriptionStatus)
                ps.setBoolean(7, shadowBanned)
                ps.executeUpdate()
            }
        }
        return Seeded(id, jwtIssuer.issueAccessToken(id, tokenVersion = 0))
    }

    fun seedPost(
        authorId: UUID,
        content: String = "route original",
        ageMinutes: Long = 5,
    ): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO posts (id, author_id, content, display_location, actual_location, created_at)
                VALUES (?, ?, ?,
                  ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                  ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                  NOW() - (? || ' minutes')::interval)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setObject(2, authorId)
                ps.setString(3, content)
                ps.setDouble(4, 106.8)
                ps.setDouble(5, -6.2)
                ps.setDouble(6, 106.8)
                ps.setDouble(7, -6.2)
                ps.setString(8, ageMinutes.toString())
                ps.executeUpdate()
            }
        }
        return id
    }

    /** Inserts an explicit edit-history row (pre-edit snapshot) at [editedAt]. */
    fun seedEdit(
        postId: UUID,
        editedBy: UUID,
        snapshot: String,
        editedAt: Instant,
    ) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO post_edits (post_id, content_snapshot, location_snapshot, edited_at, edited_by)
                VALUES (?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, postId)
                ps.setString(2, snapshot)
                ps.setDouble(3, 106.8)
                ps.setDouble(4, -6.2)
                ps.setTimestamp(5, Timestamp.from(editedAt))
                ps.setObject(6, editedBy)
                ps.executeUpdate()
            }
        }
    }

    fun block(
        blocker: UUID,
        blocked: UUID,
    ) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("INSERT INTO user_blocks (blocker_id, blocked_id) VALUES (?, ?)").use { ps ->
                ps.setObject(1, blocker)
                ps.setObject(2, blocked)
                ps.executeUpdate()
            }
        }
    }

    fun cleanup(vararg seeded: Seeded) {
        dataSource.connection.use { conn ->
            seeded.forEach { s ->
                conn.createStatement().use { st ->
                    // post_edits + user_blocks cascade from posts/users FKs; delete posts then users.
                    st.executeUpdate("DELETE FROM posts WHERE author_id = '${s.id}'")
                    st.executeUpdate("DELETE FROM users WHERE id = '${s.id}'")
                }
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
                installAppStatusPages()
                postEditRoutes(service(), historyQuery)
            }
            block()
        }
    }

    suspend fun ApplicationTestBuilder.patchEdit(
        postId: String,
        token: String?,
        content: String?,
    ): Pair<HttpStatusCode, String> {
        val resp =
            createClient { install(ClientCN) { json() } }.patch("/api/v1/posts/$postId") {
                if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                if (content != null) {
                    val payload = buildJsonObject { put("content", JsonPrimitive(content)) }
                    setBody(payload.toString())
                }
            }
        return resp.status to resp.bodyAsText()
    }

    suspend fun ApplicationTestBuilder.getEdits(
        postId: String,
        token: String?,
    ): Pair<HttpStatusCode, String> {
        val resp =
            createClient { install(ClientCN) { json() } }.get("/api/v1/posts/$postId/edits") {
                if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
            }
        return resp.status to resp.bodyAsText()
    }

    fun errorCode(body: String): String = Json.parseToJsonElement(body).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content

    "PATCH 200 — premium author edits a fresh post (wire carries id/content/updated_at)" {
        val author = seedUser("premium_active")
        try {
            val postId = seedPost(author.id, content = "route before")
            withApp {
                val (status, body) = patchEdit(postId.toString(), author.token, "route after")
                status shouldBe HttpStatusCode.OK
                val o = Json.parseToJsonElement(body).jsonObject
                o["id"]!!.jsonPrimitive.content shouldBe postId.toString()
                o["content"]!!.jsonPrimitive.content shouldBe "route after"
                (o["updated_at"] != null) shouldBe true
            }
        } finally {
            cleanup(author)
        }
    }

    "PATCH 403 — free user gets premium_required" {
        val author = seedUser("free")
        try {
            val postId = seedPost(author.id, content = "free route")
            withApp {
                val (status, body) = patchEdit(postId.toString(), author.token, "free attempt")
                status shouldBe HttpStatusCode.Forbidden
                errorCode(body) shouldBe "premium_required"
            }
        } finally {
            cleanup(author)
        }
    }

    "5.14 PATCH 404 — non-existent post id and non-author share the same code/shape (no existence reveal)" {
        val author = seedUser("premium_active")
        val other = seedUser("premium_active")
        try {
            val postId = seedPost(author.id, content = "owned route")
            withApp {
                val (s1, b1) = patchEdit(UUID.randomUUID().toString(), other.token, "ghost")
                val (s2, b2) = patchEdit(postId.toString(), other.token, "hijack")
                s1 shouldBe HttpStatusCode.NotFound
                s2 shouldBe HttpStatusCode.NotFound
                // Byte-identical bodies — non-author indistinguishable from non-existent (D7).
                b1 shouldBe b2
                errorCode(b1) shouldBe "post_not_found"
            }
        } finally {
            cleanup(author, other)
        }
    }

    "PATCH 409 — author's own post past the window returns edit_window_expired" {
        val author = seedUser("premium_active")
        try {
            val postId = seedPost(author.id, content = "stale route", ageMinutes = 31)
            withApp {
                val (status, body) = patchEdit(postId.toString(), author.token, "too late route")
                status shouldBe HttpStatusCode.Conflict
                errorCode(body) shouldBe "edit_window_expired"
            }
        } finally {
            cleanup(author)
        }
    }

    "5.14 PATCH 400 — malformed post_id is invalid_request" {
        val author = seedUser("premium_active")
        try {
            withApp {
                val (status, body) = patchEdit("not-a-uuid", author.token, "whatever")
                status shouldBe HttpStatusCode.BadRequest
                errorCode(body) shouldBe "invalid_request"
            }
        } finally {
            cleanup(author)
        }
    }

    "PATCH 401 — unauthenticated edit" {
        withApp {
            val (status, _) = patchEdit(UUID.randomUUID().toString(), token = null, content = "x")
            status shouldBe HttpStatusCode.Unauthorized
        }
    }

    "5.11 GET edits — twice-edited post lists Versi ke-1 / Versi ke-2 in chronological order" {
        val author = seedUser("premium_active")
        val viewer = seedUser("free")
        try {
            val postId = seedPost(author.id, content = "current")
            val now = Instant.now()
            seedEdit(postId, author.id, "version one", now.minusSeconds(120))
            seedEdit(postId, author.id, "version two", now.minusSeconds(60))
            withApp {
                val (status, body) = getEdits(postId.toString(), viewer.token)
                status shouldBe HttpStatusCode.OK
                val edits = Json.parseToJsonElement(body).jsonObject["edits"]!!.jsonArray
                edits.size shouldBe 2
                edits[0].jsonObject["version_label"]!!.jsonPrimitive.content shouldBe "Versi ke-1"
                edits[0].jsonObject["content"]!!.jsonPrimitive.content shouldBe "version one"
                edits[1].jsonObject["version_label"]!!.jsonPrimitive.content shouldBe "Versi ke-2"
                edits[1].jsonObject["content"]!!.jsonPrimitive.content shouldBe "version two"
            }
        } finally {
            cleanup(author, viewer)
        }
    }

    "5.11 GET edits — never-edited post returns an empty list (200)" {
        val author = seedUser("premium_active")
        val viewer = seedUser("free")
        try {
            val postId = seedPost(author.id, content = "unedited")
            withApp {
                val (status, body) = getEdits(postId.toString(), viewer.token)
                status shouldBe HttpStatusCode.OK
                Json.parseToJsonElement(body).jsonObject["edits"]!!.jsonArray.size shouldBe 0
            }
        } finally {
            cleanup(author, viewer)
        }
    }

    "5.12 GET edits — blocked viewer (either direction) gets 404" {
        val author = seedUser("premium_active")
        val viewerBlockedByAuthor = seedUser("free")
        val viewerWhoBlockedAuthor = seedUser("free")
        try {
            val postId = seedPost(author.id, content = "blocked-history")
            seedEdit(postId, author.id, "snap", Instant.now().minusSeconds(60))
            block(author.id, viewerBlockedByAuthor.id) // author → viewer
            block(viewerWhoBlockedAuthor.id, author.id) // viewer → author
            withApp {
                getEdits(postId.toString(), viewerBlockedByAuthor.token).first shouldBe HttpStatusCode.NotFound
                getEdits(postId.toString(), viewerWhoBlockedAuthor.token).first shouldBe HttpStatusCode.NotFound
            }
        } finally {
            cleanup(author, viewerBlockedByAuthor, viewerWhoBlockedAuthor)
        }
    }

    "5.12 GET edits — shadow-banned author's history is 404 for others, 200 for the author" {
        val author = seedUser("premium_active", shadowBanned = true)
        val other = seedUser("free")
        try {
            val postId = seedPost(author.id, content = "shadow-history")
            seedEdit(postId, author.id, "shadow snap", Instant.now().minusSeconds(60))
            withApp {
                // Non-author cannot see a shadow-banned author's post → 404.
                getEdits(postId.toString(), other.token).first shouldBe HttpStatusCode.NotFound
                // The author themselves can read their own post's history.
                val (selfStatus, selfBody) = getEdits(postId.toString(), author.token)
                selfStatus shouldBe HttpStatusCode.OK
                Json.parseToJsonElement(selfBody).jsonObject["edits"]!!.jsonArray.size shouldBe 1
            }
        } finally {
            cleanup(author, other)
        }
    }

    "5.13 cascade — hard-deleting a post with history removes its post_edits rows" {
        val author = seedUser("premium_active")
        try {
            val postId = seedPost(author.id, content = "cascade")
            seedEdit(postId, author.id, "cascade snap", Instant.now().minusSeconds(60))
            // sanity
            dataSource.connection.use { conn ->
                conn.prepareStatement("SELECT COUNT(*) FROM post_edits WHERE post_id = ?").use { ps ->
                    ps.setObject(1, postId)
                    ps.executeQuery().use { rs ->
                        rs.next()
                        rs.getInt(1) shouldBe 1
                    }
                }
                conn.prepareStatement("DELETE FROM posts WHERE id = ?").use { ps ->
                    ps.setObject(1, postId)
                    ps.executeUpdate()
                }
                conn.prepareStatement("SELECT COUNT(*) FROM post_edits WHERE post_id = ?").use { ps ->
                    ps.setObject(1, postId)
                    ps.executeQuery().use { rs ->
                        rs.next()
                        rs.getInt(1) shouldBe 0
                    }
                }
            }
        } finally {
            cleanup(author)
        }
    }

    "5.18 GET edits — response carries content/version_label/edited_at and NO raw location field" {
        val author = seedUser("premium_active")
        val viewer = seedUser("free")
        try {
            val postId = seedPost(author.id, content = "no-location-leak")
            seedEdit(postId, author.id, "snap text", Instant.now().minusSeconds(60))
            withApp {
                val (status, body) = getEdits(postId.toString(), viewer.token)
                status shouldBe HttpStatusCode.OK
                val first = Json.parseToJsonElement(body).jsonObject["edits"]!!.jsonArray[0].jsonObject
                first["content"]!!.jsonPrimitive.content shouldBe "snap text"
                first["version_label"]!!.jsonPrimitive.content shouldBe "Versi ke-1"
                (first["edited_at"] != null) shouldBe true
                // Spatial-fuzzing invariant #3 / D10: no location key on the wire, AND the
                // raw seeded coordinates never appear anywhere in the response body.
                first.containsKey("location_snapshot") shouldBe false
                first.containsKey("actual_location") shouldBe false
                first.containsKey("location") shouldBe false
                body.contains("106.8") shouldBe false
                body.contains("-6.2") shouldBe false
            }
        } finally {
            cleanup(author, viewer)
        }
    }

    "5.14 GET edits — malformed post_id is 400 invalid_request" {
        val viewer = seedUser("premium_active")
        try {
            withApp {
                val (status, body) = getEdits("not-a-uuid", viewer.token)
                status shouldBe HttpStatusCode.BadRequest
                errorCode(body) shouldBe "invalid_request"
            }
        } finally {
            cleanup(viewer)
        }
    }
})
