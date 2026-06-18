package id.nearyou.app.post

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.auth.configureUserJwt
import id.nearyou.app.auth.jwt.JwtIssuer
import id.nearyou.app.auth.jwt.RsaKeyLoader
import id.nearyou.app.auth.jwt.TestKeys
import id.nearyou.app.infra.repo.JdbcSinglePostRepository
import id.nearyou.app.infra.repo.JdbcUserRepository
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
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
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.sql.Date
import java.sql.Timestamp
import java.sql.Types
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
            // Frugal pool (2 conns) + autoClose: sequential DB ops, release on spec finish — keeps
            // this spec from adding to the suite-wide leaked-HikariPool pressure on the CI Postgres
            // service container (CI connection-budget rule; tasks 5.1).
            maximumPoolSize = 2
            minimumIdle = 0
            initializationFailTimeout = -1
        }
    return HikariDataSource(config)
}

/**
 * Constant, direction-less 404 body — byte-identical for every unresolvable cause. MUST equal the
 * `LikeRoutes` / `ReplyRoutes` `POST_NOT_FOUND_BODY` literal (the shared like/reply `post_not_found`
 * contract); pinning the literal here is the cross-route byte-equality guard so a future divergent
 * literal cannot slip in.
 */
private const val POST_NOT_FOUND_BODY = """{"error":{"code":"post_not_found"}}"""

@Tags("database")
class SinglePostRoutesTest : StringSpec({

    val dataSource = autoClose(hikari())
    val keys = RsaKeyLoader(TestKeys.freshEncodedPemPrivateKey(), kid = "test-single-post")
    val jwtIssuer = JwtIssuer(keys)
    val users = JdbcUserRepository(dataSource)
    val service = PostReadService(JdbcSinglePostRepository(dataSource))

    data class SeededUser(val id: UUID, val token: String, val username: String, val displayName: String)

    fun seedUser(
        displayName: String = "Single Post Tester",
        shadowBanned: Boolean = false,
    ): SeededUser {
        val id = UUID.randomUUID()
        val short = id.toString().replace("-", "").take(8)
        val username = "sp_$short"
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO users (id, username, display_name, date_of_birth, invite_code_prefix, is_shadow_banned)
                VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setString(2, username)
                ps.setString(3, displayName)
                ps.setDate(4, Date.valueOf(LocalDate.of(1990, 1, 1)))
                ps.setString(5, "p${short.take(7)}")
                ps.setBoolean(6, shadowBanned)
                ps.executeUpdate()
            }
        }
        return SeededUser(id, jwtIssuer.issueAccessToken(id, tokenVersion = 0), username, displayName)
    }

    // Jakarta coords (106.8, -6.2) fall inside a V12-seeded kabupaten polygon → the V11 trigger sets a
    // non-empty city_name. Deep-Indian-Ocean coords (105.0, -10.5) are covered by no polygon → city_name
    // stays NULL → the HTTP layer maps it to "" (the empty-city test).
    fun seedPost(
        authorId: UUID,
        content: String = "halo dunia",
        autoHidden: Boolean = false,
        softDeleted: Boolean = false,
        lng: Double = 106.8,
        lat: Double = -6.2,
    ): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO posts (id, author_id, content, display_location, actual_location, is_auto_hidden, deleted_at)
                VALUES (?, ?, ?,
                  ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                  ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                  ?, ?)
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
                if (softDeleted) ps.setTimestamp(9, Timestamp.from(Instant.now())) else ps.setNull(9, Types.TIMESTAMP)
                ps.executeUpdate()
            }
        }
        return id
    }

    fun seedLike(
        postId: UUID,
        userId: UUID,
    ) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("INSERT INTO post_likes (post_id, user_id) VALUES (?, ?)").use { ps ->
                ps.setObject(1, postId)
                ps.setObject(2, userId)
                ps.executeUpdate()
            }
        }
    }

    fun seedReply(
        postId: UUID,
        authorId: UUID,
    ) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "INSERT INTO post_replies (id, post_id, author_id, content) VALUES (?, ?, ?, ?)",
            ).use { ps ->
                ps.setObject(1, UUID.randomUUID())
                ps.setObject(2, postId)
                ps.setObject(3, authorId)
                ps.setString(4, "reply-${UUID.randomUUID().toString().take(6)}")
                ps.executeUpdate()
            }
        }
    }

    fun seedEdit(
        postId: UUID,
        editedBy: UUID,
        editedAt: Instant,
    ) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO post_edits (id, post_id, edited_at, content_snapshot, location_snapshot, edited_by)
                VALUES (?, ?, ?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, UUID.randomUUID())
                ps.setObject(2, postId)
                ps.setTimestamp(3, Timestamp.from(editedAt))
                ps.setString(4, "old content")
                ps.setDouble(5, 106.8)
                ps.setDouble(6, -6.2)
                ps.setObject(7, editedBy)
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

    // posts/replies/likes/blocks FK to users — delete children then users to be order-safe.
    fun cleanup(vararg ids: UUID) {
        dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                ids.forEach {
                    st.executeUpdate("DELETE FROM post_edits WHERE edited_by = '$it'")
                    st.executeUpdate("DELETE FROM post_likes WHERE user_id = '$it'")
                    st.executeUpdate("DELETE FROM post_replies WHERE author_id = '$it'")
                    st.executeUpdate("DELETE FROM posts WHERE author_id = '$it'")
                    st.executeUpdate("DELETE FROM users WHERE id = '$it'")
                }
            }
        }
    }

    suspend fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) {
        testApplication {
            application {
                // Match production ContentNegotiation: explicitNulls = false drops null-valued keys
                // (so a null distanceM is absent) — asserts the real wire shape, not a present-null fiction.
                install(ContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                            explicitNulls = false
                        },
                    )
                }
                install(Authentication) { configureUserJwt(keys, users, Instant::now) }
                singlePostRoutes(service)
            }
            block()
        }
    }

    suspend fun ApplicationTestBuilder.getPost(
        postId: String,
        token: String?,
    ): Pair<HttpStatusCode, String> {
        val resp =
            createClient { install(ClientCN) { json() } }.get("/api/v1/posts/$postId") {
                if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
            }
        return resp.status to resp.bodyAsText()
    }

    fun String.obj(): JsonObject = Json.parseToJsonElement(this).jsonObject

    fun JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content

    fun JsonObject.bool(key: String): Boolean = this[key]!!.jsonPrimitive.boolean

    fun JsonObject.int(key: String): Int = this[key]!!.jsonPrimitive.int

    fun JsonObject.hasKey(key: String): Boolean = this[key] != null

    fun JsonObject.isAbsentOrNull(key: String): Boolean = this[key] == null || this[key] is JsonNull

    "5.1 200 — visible post returns the full projection" {
        val author = seedUser(displayName = "Raka Pratama")
        val viewer = seedUser()
        try {
            val postId = seedPost(author.id, content = "halo")
            withApp {
                val (status, body) = getPost(postId.toString(), viewer.token)
                status shouldBe HttpStatusCode.OK
                val o = body.obj()
                o.str("id") shouldBe postId.toString()
                o.str("authorUsername") shouldBe author.username
                o.str("authorDisplayName") shouldBe "Raka Pratama"
                o.str("content") shouldBe "halo"
                o.hasKey("city_name") shouldBe true // Jakarta coords → non-empty city
                o.str("city_name").isNotEmpty() shouldBe true
                o.hasKey("createdAt") shouldBe true
                o.bool("liked_by_viewer") shouldBe false
                o.int("reply_count") shouldBe 0
                o.isAbsentOrNull("isAuthor") shouldBe true // non-author → omitted at default false (client reads false)
            }
        } finally {
            cleanup(author.id, viewer.id)
        }
    }

    "5.1 200 — likedByViewer reflects the viewer's own like state" {
        val author = seedUser()
        val viewer = seedUser()
        try {
            val liked = seedPost(author.id)
            val notLiked = seedPost(author.id)
            seedLike(liked, viewer.id)
            withApp {
                getPost(liked.toString(), viewer.token).second.obj().bool("liked_by_viewer") shouldBe true
                getPost(notLiked.toString(), viewer.token).second.obj().bool("liked_by_viewer") shouldBe false
            }
        } finally {
            cleanup(author.id, viewer.id)
        }
    }

    "5.1 200 — replyCount reflects the post's reply count" {
        val author = seedUser()
        val viewer = seedUser()
        try {
            val postId = seedPost(author.id)
            repeat(3) { seedReply(postId, author.id) }
            withApp {
                getPost(postId.toString(), viewer.token).second.obj().int("reply_count") shouldBe 3
            }
        } finally {
            cleanup(author.id, viewer.id)
        }
    }

    "5.1 200 — empty city_name (no-polygon coords) renders as \"\"" {
        val author = seedUser()
        val viewer = seedUser()
        try {
            // Deep-Indian-Ocean coords: no kabupaten polygon → city_name NULL → "" on the wire.
            val postId = seedPost(author.id, lng = 105.0, lat = -10.5)
            withApp {
                val o = getPost(postId.toString(), viewer.token).second.obj()
                o.str("city_name") shouldBe ""
            }
        } finally {
            cleanup(author.id, viewer.id)
        }
    }

    "5.1 200 — editedAt present (camelCase) for an edited post, absent for an unedited post" {
        val author = seedUser()
        val viewer = seedUser()
        try {
            val edited = seedPost(author.id, content = "v2")
            val unedited = seedPost(author.id)
            seedEdit(edited, author.id, Instant.parse("2026-02-02T03:04:05Z"))
            withApp {
                val editedObj = getPost(edited.toString(), viewer.token).second.obj()
                editedObj.hasKey("editedAt") shouldBe true // bare camelCase, present iff edited
                editedObj.hasKey("edited_at") shouldBe false // not snake_case
                val uneditedObj = getPost(unedited.toString(), viewer.token).second.obj()
                uneditedObj.isAbsentOrNull("editedAt") shouldBe true // omitted under explicitNulls = false
            }
        } finally {
            cleanup(author.id, viewer.id)
        }
    }

    "5.2 200 — body carries no author UUID, no coordinates, no non-null distanceM" {
        val author = seedUser()
        val viewer = seedUser()
        try {
            val postId = seedPost(author.id)
            withApp {
                val o = getPost(postId.toString(), viewer.token).second.obj()
                // No author UUID under any plausible key.
                o.hasKey("authorUserId") shouldBe false
                o.hasKey("authorId") shouldBe false
                o.hasKey("author_id") shouldBe false
                // No coordinates.
                o.hasKey("latitude") shouldBe false
                o.hasKey("longitude") shouldBe false
                o.hasKey("lat") shouldBe false
                o.hasKey("lng") shouldBe false
                // distanceM is null/absent in v1 (explicitNulls=false omits it).
                o.isAbsentOrNull("distanceM") shouldBe true
            }
        } finally {
            cleanup(author.id, viewer.id)
        }
    }

    "5.3 404 — all six unresolvable causes return the byte-identical constant body" {
        val viewer = seedUser()
        val visibleAuthor = seedUser()
        val shadowAuthor = seedUser(shadowBanned = true) // other-author shadow-banned
        val viewerBlocksAuthor = seedUser() // viewer blocked author
        val authorBlocksViewer = seedUser() // author blocked viewer
        insertBlock(viewer.id, viewerBlocksAuthor.id)
        insertBlock(authorBlocksViewer.id, viewer.id)
        try {
            val unknown = UUID.randomUUID()
            val softDeleted = seedPost(visibleAuthor.id, softDeleted = true)
            val shadowed = seedPost(shadowAuthor.id)
            val autoHidden = seedPost(visibleAuthor.id, autoHidden = true)
            val blockedA = seedPost(viewerBlocksAuthor.id)
            val blockedB = seedPost(authorBlocksViewer.id)
            withApp {
                // Order: unknown UUID, soft-deleted, other-author shadow-banned, auto-hidden,
                // viewer-blocked-author, author-blocked-viewer.
                val results =
                    listOf(
                        getPost(unknown.toString(), viewer.token),
                        getPost(softDeleted.toString(), viewer.token),
                        getPost(shadowed.toString(), viewer.token),
                        getPost(autoHidden.toString(), viewer.token),
                        getPost(blockedA.toString(), viewer.token),
                        getPost(blockedB.toString(), viewer.token),
                    )
                results.forEach { (status, body) ->
                    status shouldBe HttpStatusCode.NotFound
                    // Byte-identical to the shared like/reply post_not_found contract (cross-route guard).
                    body shouldBe POST_NOT_FOUND_BODY
                }
                // All six bodies mutually byte-identical (no cause/direction leak).
                results.map { it.second }.toSet().size shouldBe 1
            }
        } finally {
            cleanup(viewer.id, visibleAuthor.id, shadowAuthor.id, viewerBlocksAuthor.id, authorBlocksViewer.id)
        }
    }

    "5.4 200/404 — shadow-banned author reads OWN post via the own-content arm; own soft-deleted still 404" {
        val shadowAuthor = seedUser(displayName = "Hidden Author", shadowBanned = true)
        try {
            val ownLive = seedPost(shadowAuthor.id, content = "my own post")
            val ownDeleted = seedPost(shadowAuthor.id, softDeleted = true)
            withApp {
                // Own live post: 200 with identity (visible_posts arm excludes it; own-content arm resolves it).
                val (liveStatus, liveBody) = getPost(ownLive.toString(), shadowAuthor.token)
                liveStatus shouldBe HttpStatusCode.OK
                val o = liveBody.obj()
                o.str("content") shouldBe "my own post"
                o.str("authorDisplayName") shouldBe "Hidden Author"
                o.bool("isAuthor") shouldBe true // the shadow-banned author is reading their OWN post
                // Own soft-deleted post: still 404 (own-content arm requires deleted_at IS NULL).
                getPost(ownDeleted.toString(), shadowAuthor.token).first shouldBe HttpStatusCode.NotFound
            }
        } finally {
            cleanup(shadowAuthor.id)
        }
    }

    "5.5 400 — malformed (non-UUID) post_id returns invalid_request, not 404" {
        val viewer = seedUser()
        try {
            withApp {
                val (status, body) = getPost("not-a-uuid", viewer.token)
                status shouldBe HttpStatusCode.BadRequest
                body.obj()["error"]!!.jsonObject.str("code") shouldBe "invalid_request"
            }
        } finally {
            cleanup(viewer.id)
        }
    }

    "5.5 401 — unauthenticated request is rejected before resolution" {
        withApp {
            val (status, _) = getPost(UUID.randomUUID().toString(), token = null)
            status shouldBe HttpStatusCode.Unauthorized
        }
    }
})
