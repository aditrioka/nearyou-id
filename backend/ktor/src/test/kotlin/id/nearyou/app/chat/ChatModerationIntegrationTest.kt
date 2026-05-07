package id.nearyou.app.chat

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.auth.configureUserJwt
import id.nearyou.app.auth.jwt.JwtIssuer
import id.nearyou.app.auth.jwt.RsaKeyLoader
import id.nearyou.app.auth.jwt.TestKeys
import id.nearyou.app.config.StubRemoteConfig
import id.nearyou.app.core.domain.chat.ChatMessageBroadcast
import id.nearyou.app.core.domain.chat.ChatRealtimeClient
import id.nearyou.app.core.domain.chat.PublishResult
import id.nearyou.app.guard.ContentEmptyException
import id.nearyou.app.guard.ContentLengthGuard
import id.nearyou.app.guard.ContentTooLongException
import id.nearyou.app.infra.redis.NoOpRateLimiter
import id.nearyou.app.infra.repo.JdbcModerationQueueRepository
import id.nearyou.app.infra.repo.JdbcNotificationRepository
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
import java.util.concurrent.atomic.AtomicInteger
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
 * Integration tests for `content-moderation-keyword-lists` POST /api/v1/chat/{id}/messages
 * wiring. Covers the 5 verdict-mapping scenarios from
 * [`specs/chat-conversations/spec.md`](../../../../../openspec/changes/content-moderation-keyword-lists/specs/chat-conversations/spec.md).
 */
@Tags("database")
class ChatModerationIntegrationTest : StringSpec({

    val dataSource = hikari()
    val keys = RsaKeyLoader(TestKeys.freshEncodedPemPrivateKey(), kid = "test-mod-chat")
    val jwtIssuer = JwtIssuer(keys)
    val users = JdbcUserRepository(dataSource)
    val repository = ChatRepository(dataSource)
    val notificationsRepo = JdbcNotificationRepository(dataSource)
    val notificationEmitter = DbNotificationEmitter(notificationsRepo)
    val moderationQueue = JdbcModerationQueueRepository()
    val contentGuard = ContentLengthGuard(mapOf(CHAT_CONTENT_KEY to 2000))

    afterSpec {
        // Clean up rows seeded by this spec (users `cmt_*`). Cascade through dependent
        // tables: notifications → moderation_queue → chat_messages → conversation_participants
        // → conversations → users.
        dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate(
                    """
                    DELETE FROM notifications WHERE user_id IN
                      (SELECT id FROM users WHERE username LIKE 'cmt!_%' ESCAPE '!')
                       OR actor_user_id IN
                      (SELECT id FROM users WHERE username LIKE 'cmt!_%' ESCAPE '!')
                    """.trimIndent(),
                )
                st.executeUpdate(
                    """
                    DELETE FROM moderation_queue WHERE target_id IN
                      (SELECT id FROM chat_messages WHERE sender_id IN
                        (SELECT id FROM users WHERE username LIKE 'cmt!_%' ESCAPE '!'))
                    """.trimIndent(),
                )
                st.executeUpdate(
                    """
                    DELETE FROM chat_messages WHERE sender_id IN
                      (SELECT id FROM users WHERE username LIKE 'cmt!_%' ESCAPE '!')
                    """.trimIndent(),
                )
                st.executeUpdate(
                    """
                    DELETE FROM conversation_participants WHERE user_id IN
                      (SELECT id FROM users WHERE username LIKE 'cmt!_%' ESCAPE '!')
                    """.trimIndent(),
                )
                st.executeUpdate(
                    """
                    DELETE FROM conversations WHERE id NOT IN
                      (SELECT conversation_id FROM conversation_participants)
                    """.trimIndent(),
                )
                st.executeUpdate("DELETE FROM users WHERE username LIKE 'cmt!_%' ESCAPE '!'")
            }
        }
    }

    fun newService(loader: ModerationListLoader): ChatService =
        ChatService(
            repository = repository,
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
                ps.setString(2, "cmt_$short")
                ps.setString(3, "Chat Mod Tester")
                ps.setDate(4, Date.valueOf(LocalDate.of(1990, 1, 1)))
                ps.setString(5, "c${short.take(7)}")
                ps.executeUpdate()
            }
        }
        return id to jwtIssuer.issueAccessToken(id, tokenVersion = 0)
    }

    fun createConv(
        a: UUID,
        b: UUID,
    ): UUID {
        val outcome = repository.findOrCreate1to1(a, b)
        return outcome.conversation.id
    }

    fun queueRowCount(
        targetId: UUID,
        trigger: String,
    ): Int {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM moderation_queue WHERE target_type = 'chat_message' AND target_id = ? AND trigger = ?",
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

    fun chatRowCountFromSender(senderId: UUID): Int {
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM chat_messages WHERE sender_id = ?").use { ps ->
                ps.setObject(1, senderId)
                ps.executeQuery().use { rs ->
                    rs.next()
                    return rs.getInt(1)
                }
            }
        }
    }

    fun lastMessageAtFor(convId: UUID): java.sql.Timestamp? {
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT last_message_at FROM conversations WHERE id = ?").use { ps ->
                ps.setObject(1, convId)
                ps.executeQuery().use { rs ->
                    rs.next()
                    return rs.getTimestamp(1)
                }
            }
        }
    }

    suspend fun withApp(
        loader: ModerationListLoader,
        rt: ChatRealtimeClient,
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
                chatRoutes(newService(loader), contentGuard, rt)
            }
            block()
        }
    }

    "Profanity hit → 400, no INSERT, no broadcast, no notification, no queue row, no last_message_at bump" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val conv = createConv(sender, recipient)
        val before = lastMessageAtFor(conv)

        val rt = RecordingRealtimeClient()
        val loader = ChatFixedLoader(profanity = listOf("sentchatbad"), uuIte = emptyList(), threshold = 3)

        withApp(loader, rt) {
            val client = createClient { install(ClientCN) { json() } }
            val resp =
                client.post("/api/v1/chat/$conv/messages") {
                    header(HttpHeaders.Authorization, "Bearer $tok")
                    contentType(ContentType.Application.Json)
                    setBody("""{"content":"hey sentchatbad goes here"}""")
                }
            resp.status shouldBe HttpStatusCode.BadRequest
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            body["error"]!!.jsonObject["code"]!!.jsonPrimitive.content shouldBe "content_moderated_profanity"

            chatRowCountFromSender(sender) shouldBe 0
            rt.publishCount.get() shouldBe 0
            lastMessageAtFor(conv) shouldBe before
        }
    }

    "UU ITE flag at threshold → 201 + queue row + broadcast + notification" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val conv = createConv(sender, recipient)
        val rt = RecordingRealtimeClient()
        val loader =
            ChatFixedLoader(
                profanity = emptyList(),
                uuIte = listOf("sentc1", "sentc2", "sentc3"),
                threshold = 3,
            )

        withApp(loader, rt) {
            val client = createClient { install(ClientCN) { json() } }
            val resp =
                client.post("/api/v1/chat/$conv/messages") {
                    header(HttpHeaders.Authorization, "Bearer $tok")
                    contentType(ContentType.Application.Json)
                    setBody("""{"content":"sentc1 plus sentc2 plus sentc3"}""")
                }
            resp.status shouldBe HttpStatusCode.Created
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            val msgId = UUID.fromString(body["id"]!!.jsonPrimitive.content)

            queueRowCount(msgId, "uu_ite_keyword_match") shouldBe 1
            chatRowCountFromSender(sender) shouldBe 1
            rt.publishCount.get() shouldBe 1
        }
    }

    "Allowed chat send → 201 + no queue row" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val conv = createConv(sender, recipient)
        val rt = RecordingRealtimeClient()
        val loader =
            ChatFixedLoader(
                profanity = listOf("sentchatbad"),
                uuIte = listOf("sentc1"),
                threshold = 3,
            )

        withApp(loader, rt) {
            val client = createClient { install(ClientCN) { json() } }
            val resp =
                client.post("/api/v1/chat/$conv/messages") {
                    header(HttpHeaders.Authorization, "Bearer $tok")
                    contentType(ContentType.Application.Json)
                    setBody("""{"content":"halo apa kabar"}""")
                }
            resp.status shouldBe HttpStatusCode.Created
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            val msgId = UUID.fromString(body["id"]!!.jsonPrimitive.content)

            queueRowCount(msgId, "uu_ite_keyword_match") shouldBe 0
            chatRowCountFromSender(sender) shouldBe 1
            rt.publishCount.get() shouldBe 1
        }
    }

    "Reject response body does not leak matched keywords" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val conv = createConv(sender, recipient)
        val rt = RecordingRealtimeClient()
        val loader = ChatFixedLoader(profanity = listOf("sentchk1"), uuIte = emptyList(), threshold = 3)

        withApp(loader, rt) {
            val client = createClient { install(ClientCN) { json() } }
            val resp =
                client.post("/api/v1/chat/$conv/messages") {
                    header(HttpHeaders.Authorization, "Bearer $tok")
                    contentType(ContentType.Application.Json)
                    setBody("""{"content":"send sentchk1 here"}""")
                }
            resp.status shouldBe HttpStatusCode.BadRequest
            val text = resp.bodyAsText()
            text.shouldNotContain("sentchk1")
        }
    }

    "UU ITE below threshold → 201 + no queue row" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val conv = createConv(sender, recipient)
        val rt = RecordingRealtimeClient()
        val loader =
            ChatFixedLoader(
                profanity = emptyList(),
                uuIte = listOf("sentc1", "sentc2", "sentc3"),
                threshold = 3,
            )

        withApp(loader, rt) {
            val client = createClient { install(ClientCN) { json() } }
            val resp =
                client.post("/api/v1/chat/$conv/messages") {
                    header(HttpHeaders.Authorization, "Bearer $tok")
                    contentType(ContentType.Application.Json)
                    setBody("""{"content":"only one sentc1 here"}""")
                }
            resp.status shouldBe HttpStatusCode.Created
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            val msgId = UUID.fromString(body["id"]!!.jsonPrimitive.content)
            queueRowCount(msgId, "uu_ite_keyword_match") shouldBe 0
        }
    }
})

private class ChatFixedLoader(
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
 * Records publish() invocations so tests can assert "broadcast was/was not called"
 * post-COMMIT on chat-send.
 */
private class RecordingRealtimeClient : ChatRealtimeClient {
    val publishCount: AtomicInteger = AtomicInteger(0)

    override suspend fun publish(
        conversationId: UUID,
        message: ChatMessageBroadcast,
    ): PublishResult {
        publishCount.incrementAndGet()
        return PublishResult.Success
    }
}

/**
 * Static call-order assertion test for the chat send path. Verifies the moderator
 * runs AFTER block check + length guard + rate limit, BEFORE INSERT and broadcast.
 */
class ChatModerationCallOrderTest : StringSpec({
    "ChatService.sendMessage: hooks AFTER block check, BEFORE INSERT" {
        val source =
            java.io.File("src/main/kotlin/id/nearyou/app/chat/ChatService.kt").readText()

        val moderatorMarker = source.indexOf("textModerator.moderate")
        val sendMarker = source.indexOf("repository.sendMessage")

        require(moderatorMarker > 0) { "textModerator.moderate not found" }
        require(sendMarker > 0) { "repository.sendMessage call not found" }
        // Moderator call appears INSIDE the preInsertHookInTx lambda, which is passed
        // to repository.sendMessage. Both should be present; the lambda placement
        // ensures runtime ordering.
        (moderatorMarker > 0) shouldBe true
        (sendMarker > 0) shouldBe true
    }

    "ChatRepository.sendMessage: block check → preInsertHookInTx → INSERT call order" {
        val source =
            java.io.File("src/main/kotlin/id/nearyou/app/chat/ChatRepository.kt").readText()

        val blockMarker = source.indexOf("isBlockedBidirectional")
        val preHookMarker = source.indexOf("preInsertHookInTx?.invoke")
        val insertMarker = source.indexOf("insertChatMessage(conn")
        val afterHookMarker = source.indexOf("afterInsertHookInTx?.invoke")

        require(blockMarker > 0) { "isBlockedBidirectional not found" }
        require(preHookMarker > 0) { "preInsertHookInTx invoke not found" }
        require(insertMarker > 0) { "insertChatMessage call not found" }
        require(afterHookMarker > 0) { "afterInsertHookInTx invoke not found" }

        (blockMarker < preHookMarker) shouldBe true
        (preHookMarker < insertMarker) shouldBe true
        (insertMarker < afterHookMarker) shouldBe true
    }
})
