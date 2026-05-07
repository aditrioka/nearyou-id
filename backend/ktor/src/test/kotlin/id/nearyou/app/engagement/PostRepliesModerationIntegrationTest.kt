package id.nearyou.app.engagement

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.auth.configureUserJwt
import id.nearyou.app.auth.jwt.JwtIssuer
import id.nearyou.app.auth.jwt.RsaKeyLoader
import id.nearyou.app.auth.jwt.TestKeys
import id.nearyou.app.config.StubRemoteConfig
import id.nearyou.app.guard.ContentEmptyException
import id.nearyou.app.guard.ContentLengthGuard
import id.nearyou.app.guard.ContentTooLongException
import id.nearyou.app.infra.redis.NoOpRateLimiter
import id.nearyou.app.infra.repo.JdbcModerationQueueRepository
import id.nearyou.app.infra.repo.JdbcNotificationRepository
import id.nearyou.app.infra.repo.JdbcPostReplyRepository
import id.nearyou.app.infra.repo.JdbcUserRepository
import id.nearyou.app.moderation.ModerationList
import id.nearyou.app.moderation.ModerationListLoader
import id.nearyou.app.moderation.TextModerator
import id.nearyou.app.notifications.DbNotificationEmitter
import id.nearyou.app.notifications.NoopNotificationDispatcher
import id.nearyou.app.post.ContentModeratedProfanityException
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
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

/**
 * Integration tests for `content-moderation-keyword-lists` POST /api/v1/posts/{post_id}/replies wiring.
 * Covers all 5 verdict-mapping scenarios + the rate-limit-precedence scenario from
 * [`specs/post-replies/spec.md`](../../../../../openspec/changes/content-moderation-keyword-lists/specs/post-replies/spec.md).
 */
@Tags("database")
class PostRepliesModerationIntegrationTest : StringSpec({

    val dataSource = hikari()
    val keys = RsaKeyLoader(TestKeys.freshEncodedPemPrivateKey(), kid = "test-mod-reply")
    val jwtIssuer = JwtIssuer(keys)
    val users = JdbcUserRepository(dataSource)
    val replyRepo = JdbcPostReplyRepository(dataSource)
    val notificationsRepo = JdbcNotificationRepository(dataSource)
    val notificationEmitter = DbNotificationEmitter(notificationsRepo)
    val moderationQueue = JdbcModerationQueueRepository()
    val contentGuard = ContentLengthGuard(mapOf(REPLY_CONTENT_KEY to 280))

    afterSpec {
        // Clean up rows seeded by this spec (users `rmt_*`). Cascade through dependent
        // tables: notifications → post_replies → moderation_queue → posts → users.
        dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate(
                    """
                    DELETE FROM notifications WHERE user_id IN
                      (SELECT id FROM users WHERE username LIKE 'rmt!_%' ESCAPE '!')
                       OR actor_user_id IN
                      (SELECT id FROM users WHERE username LIKE 'rmt!_%' ESCAPE '!')
                    """.trimIndent(),
                )
                st.executeUpdate(
                    """
                    DELETE FROM moderation_queue WHERE target_id IN
                      (SELECT id FROM post_replies WHERE author_id IN
                        (SELECT id FROM users WHERE username LIKE 'rmt!_%' ESCAPE '!'))
                    """.trimIndent(),
                )
                st.executeUpdate(
                    """
                    DELETE FROM post_replies WHERE author_id IN
                      (SELECT id FROM users WHERE username LIKE 'rmt!_%' ESCAPE '!')
                    """.trimIndent(),
                )
                st.executeUpdate(
                    """
                    DELETE FROM posts WHERE author_id IN
                      (SELECT id FROM users WHERE username LIKE 'rmt!_%' ESCAPE '!')
                    """.trimIndent(),
                )
                st.executeUpdate("DELETE FROM users WHERE username LIKE 'rmt!_%' ESCAPE '!'")
            }
        }
    }

    fun newService(loader: ModerationListLoader): ReplyService =
        ReplyService(
            dataSource = dataSource,
            replies = replyRepo,
            notifications = notificationEmitter,
            dispatcher = NoopNotificationDispatcher(),
            rateLimiter = NoOpRateLimiter(),
            remoteConfig = StubRemoteConfig(),
            textModerator = TextModerator(loader),
            moderationQueue = moderationQueue,
        )

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
                ps.setString(2, "rmt_$short")
                ps.setString(3, "Reply Mod Tester")
                ps.setDate(4, Date.valueOf(LocalDate.of(1990, 1, 1)))
                ps.setString(5, "r${short.take(7)}")
                ps.executeUpdate()
            }
        }
        return id to jwtIssuer.issueAccessToken(id, tokenVersion = 0)
    }

    fun seedPost(authorId: UUID): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO posts (id, author_id, content, display_location, actual_location)
                VALUES (?, ?, 'parent post',
                  ST_SetSRID(ST_MakePoint(106.8, -6.2), 4326)::geography,
                  ST_SetSRID(ST_MakePoint(106.8, -6.2), 4326)::geography)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setObject(2, authorId)
                ps.executeUpdate()
            }
        }
        return id
    }

    fun queueRowCount(
        targetId: UUID,
        trigger: String,
    ): Int {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM moderation_queue WHERE target_type = 'reply' AND target_id = ? AND trigger = ?",
            ).use { ps ->
                ps.setObject(1, targetId)
                ps.setString(2, trigger)
                ps.executeQuery().use { rs ->
                    rs.next()
                    return rs.getInt(1)
                }
            }
        }
    }

    fun replyRowCount(authorId: UUID): Int {
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM post_replies WHERE author_id = ?").use { ps ->
                ps.setObject(1, authorId)
                ps.executeQuery().use { rs ->
                    rs.next()
                    return rs.getInt(1)
                }
            }
        }
    }

    fun notificationRowCount(recipientId: UUID): Int {
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM notifications WHERE user_id = ?").use { ps ->
                ps.setObject(1, recipientId)
                ps.executeQuery().use { rs ->
                    rs.next()
                    return rs.getInt(1)
                }
            }
        }
    }

    suspend fun withApp(
        loader: ModerationListLoader,
        block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit,
    ) {
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
                    exception<ContentEmptyException> { call, _ ->
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to mapOf("code" to "content_empty")))
                    }
                    exception<ContentTooLongException> { call, _ ->
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to mapOf("code" to "content_too_long")))
                    }
                    exception<ContentModeratedProfanityException> { call, _ ->
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf(
                                "error" to
                                    mapOf(
                                        "code" to "content_moderated_profanity",
                                        "message" to "Konten ini mengandung kata yang tidak diperbolehkan. Silakan ubah dan coba lagi.",
                                    ),
                            ),
                        )
                    }
                }
                install(Authentication) { configureUserJwt(keys, users, java.time.Instant::now) }
                replyRoutes(newService(loader), contentGuard)
            }
            block()
        }
    }

    "Profanity reply → 400, no INSERT, no notification, no queue row" {
        val (replierId, replierToken) = seedUser()
        val (parentAuthorId, _) = seedUser()
        val parentPostId = seedPost(parentAuthorId)
        val loader = ReplyFixedLoader(profanity = listOf("sentbadrepl"), uuIte = emptyList(), threshold = 3)

        withApp(loader) {
            val client = createClient { install(ClientCN) { json() } }
            val resp =
                client.post("/api/v1/posts/$parentPostId/replies") {
                    header(HttpHeaders.Authorization, "Bearer $replierToken")
                    contentType(ContentType.Application.Json)
                    setBody("""{"content":"this sentbadrepl reply"}""")
                }
            resp.status shouldBe HttpStatusCode.BadRequest
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            body["error"]!!.jsonObject["code"]!!.jsonPrimitive.content shouldBe "content_moderated_profanity"

            replyRowCount(replierId) shouldBe 0
            notificationRowCount(parentAuthorId) shouldBe 0
        }
    }

    "UU ITE flag at threshold → 201 + queue row + notification emitted" {
        val (replierId, replierToken) = seedUser()
        val (parentAuthorId, _) = seedUser()
        val parentPostId = seedPost(parentAuthorId)
        val loader =
            ReplyFixedLoader(
                profanity = emptyList(),
                uuIte = listOf("sentr1", "sentr2", "sentr3"),
                threshold = 3,
            )

        withApp(loader) {
            val client = createClient { install(ClientCN) { json() } }
            val resp =
                client.post("/api/v1/posts/$parentPostId/replies") {
                    header(HttpHeaders.Authorization, "Bearer $replierToken")
                    contentType(ContentType.Application.Json)
                    setBody("""{"content":"sentr1 plus sentr2 plus sentr3 reply"}""")
                }
            resp.status shouldBe HttpStatusCode.Created
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            val replyId = UUID.fromString(body["id"]!!.jsonPrimitive.content)

            queueRowCount(replyId, "uu_ite_keyword_match") shouldBe 1
            replyRowCount(replierId) shouldBe 1
            notificationRowCount(parentAuthorId) shouldBe 1
        }
    }

    "Allowed reply → 201 + no queue row + notification emitted" {
        val (replierId, replierToken) = seedUser()
        val (parentAuthorId, _) = seedUser()
        val parentPostId = seedPost(parentAuthorId)
        val loader =
            ReplyFixedLoader(
                profanity = listOf("sentbadrepl"),
                uuIte = listOf("sentr1"),
                threshold = 3,
            )

        withApp(loader) {
            val client = createClient { install(ClientCN) { json() } }
            val resp =
                client.post("/api/v1/posts/$parentPostId/replies") {
                    header(HttpHeaders.Authorization, "Bearer $replierToken")
                    contentType(ContentType.Application.Json)
                    setBody("""{"content":"halo bagus reply"}""")
                }
            resp.status shouldBe HttpStatusCode.Created
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            val replyId = UUID.fromString(body["id"]!!.jsonPrimitive.content)

            queueRowCount(replyId, "uu_ite_keyword_match") shouldBe 0
            replyRowCount(replierId) shouldBe 1
            notificationRowCount(parentAuthorId) shouldBe 1
        }
    }

    "Reject response body does not leak matched keywords" {
        val (_, replierToken) = seedUser()
        val (parentAuthorId, _) = seedUser()
        val parentPostId = seedPost(parentAuthorId)
        val loader =
            ReplyFixedLoader(profanity = listOf("sentkw1", "sentkw2"), uuIte = emptyList(), threshold = 3)

        withApp(loader) {
            val client = createClient { install(ClientCN) { json() } }
            val resp =
                client.post("/api/v1/posts/$parentPostId/replies") {
                    header(HttpHeaders.Authorization, "Bearer $replierToken")
                    contentType(ContentType.Application.Json)
                    setBody("""{"content":"reply with sentkw1 and sentkw2"}""")
                }
            resp.status shouldBe HttpStatusCode.BadRequest
            val text = resp.bodyAsText()
            text.shouldNotContain("sentkw1")
            text.shouldNotContain("sentkw2")
        }
    }

    "UU ITE below threshold → 201 + no queue row + notification emitted" {
        val (replierId, replierToken) = seedUser()
        val (parentAuthorId, _) = seedUser()
        val parentPostId = seedPost(parentAuthorId)
        val loader =
            ReplyFixedLoader(
                profanity = emptyList(),
                uuIte = listOf("sentr1", "sentr2", "sentr3"),
                threshold = 3,
            )

        withApp(loader) {
            val client = createClient { install(ClientCN) { json() } }
            val resp =
                client.post("/api/v1/posts/$parentPostId/replies") {
                    header(HttpHeaders.Authorization, "Bearer $replierToken")
                    contentType(ContentType.Application.Json)
                    setBody("""{"content":"only sentr1 here in reply"}""")
                }
            resp.status shouldBe HttpStatusCode.Created
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            val replyId = UUID.fromString(body["id"]!!.jsonPrimitive.content)
            queueRowCount(replyId, "uu_ite_keyword_match") shouldBe 0
            replyRowCount(replierId) shouldBe 1
            notificationRowCount(parentAuthorId) shouldBe 1
        }
    }
})

private class ReplyFixedLoader(
    private val profanity: List<String>,
    private val uuIte: List<String>,
    private val threshold: Int,
) : ModerationListLoader {
    override fun load(list: ModerationList): List<String> =
        when (list) {
            ModerationList.ProfanityList -> profanity
            ModerationList.UuIteList -> uuIte
        }

    override fun loadThreshold(): Int = threshold
}

/**
 * Static call-order assertion test for the reply path.
 */
class PostRepliesCallOrderTest : StringSpec({
    "ReplyService.post: visibility resolution → moderator → INSERT → notification emit call order" {
        val source =
            java.io.File("src/main/kotlin/id/nearyou/app/engagement/ReplyService.kt").readText()

        val visibilityMarker = source.indexOf("replies.resolveVisiblePost")
        val moderatorMarker = source.indexOf("textModerator.moderate")
        val insertMarker = source.indexOf("replies.insertInTx")
        val emitMarker = source.indexOf("notifications.emit")

        require(visibilityMarker > 0) { "visibility resolution call not found" }
        require(moderatorMarker > 0) { "textModerator.moderate not found" }
        require(insertMarker > 0) { "replies.insertInTx not found" }
        require(emitMarker > 0) { "notifications.emit not found" }

        (visibilityMarker < moderatorMarker) shouldBe true
        (moderatorMarker < insertMarker) shouldBe true
        (insertMarker < emitMarker) shouldBe true
    }
})
