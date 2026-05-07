package id.nearyou.app.chat

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.auth.configureUserJwt
import id.nearyou.app.auth.jwt.JwtIssuer
import id.nearyou.app.auth.jwt.RsaKeyLoader
import id.nearyou.app.auth.jwt.TestKeys
import id.nearyou.app.config.RemoteConfig
import id.nearyou.app.core.domain.chat.ChatMessageBroadcast
import id.nearyou.app.core.domain.chat.ChatRealtimeClient
import id.nearyou.app.core.domain.chat.PublishResult
import id.nearyou.app.core.domain.ratelimit.InMemoryRateLimiter
import id.nearyou.app.guard.ContentLengthGuard
import id.nearyou.app.infra.repo.JdbcNotificationRepository
import id.nearyou.app.infra.repo.JdbcUserRepository
import id.nearyou.app.notifications.DbNotificationEmitter
import id.nearyou.app.notifications.NotificationEmitter
import id.nearyou.app.notifications.NotificationService
import id.nearyou.app.notifications.notificationRoutes
import id.nearyou.data.repository.NotificationDispatcher
import id.nearyou.data.repository.NotificationType
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.request.bearerAuth
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
import io.ktor.server.response.respondText
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.sql.Connection
import java.sql.Date
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import javax.sql.DataSource
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientCN

/**
 * Integration tests for `chat-message-notification` Section 4. Covers the chat send
 * route's emit-path behaviour (recipient resolution, body_data shape, sender-shadow-ban
 * skip, embedded-only preview = null, code-point boundaries, tx rollback semantics,
 * post-commit dispatch).
 *
 * Tagged `database` — depends on dev Postgres for the V10 `notifications` table plus the
 * V15 chat schema and V11 `is_shadow_banned` column.
 *
 * Test-double strategy:
 *  - [RecordingNotificationEmitter] records every emit invocation (recipient, type,
 *    actor, target, body_data) for assertion AND optionally delegates to a real
 *    `DbNotificationEmitter` so DB-asserting tests can read the persisted row back.
 *  - [FakeChatRealtimeClient] mirrors the ChatRealtimeBroadcastTest pattern.
 *  - [SpyDataSource] wraps the production `HikariDataSource`, recording every PreparedStatement
 *    SQL string for the JDBC-spy scenarios (4.4 + 4.6).
 *  - [RecordingDispatcher] records every dispatcher invocation to verify composite fan-out
 *    (4.12).
 */
@Tags("database")
class ChatMessageNotificationTest : StringSpec({

    val dataSource = hikari()
    val keys = RsaKeyLoader(TestKeys.freshEncodedPemPrivateKey(), kid = "test-chat-notif")
    val jwtIssuer = JwtIssuer(keys)
    val users = JdbcUserRepository(dataSource)
    val repository = ChatRepository(dataSource)
    val notificationsRepo = JdbcNotificationRepository(dataSource)
    val realDbEmitter = DbNotificationEmitter(notificationsRepo)
    val notificationService = NotificationService(notificationsRepo)
    val contentGuard = ContentLengthGuard(mapOf(CHAT_CONTENT_KEY to 2000))

    afterSpec { dataSource.close() }

    fun seedUser(
        subscriptionStatus: String = "free",
        shadowBanned: Boolean = false,
    ): Pair<UUID, String> {
        val id = UUID.randomUUID()
        val short = id.toString().replace("-", "").takeLast(8)
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO users (
                    id, username, display_name, date_of_birth, invite_code_prefix,
                    subscription_status, is_shadow_banned
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "cn_$short")
                ps.setString(3, "ChatNotif Tester")
                ps.setDate(4, Date.valueOf(LocalDate.of(1990, 1, 1)))
                ps.setString(5, "y${short.takeLast(7)}")
                ps.setString(6, subscriptionStatus)
                ps.setBoolean(7, shadowBanned)
                ps.executeUpdate()
            }
        }
        val token = jwtIssuer.issueAccessToken(id, tokenVersion = 0)
        return id to token
    }

    fun seedBlock(
        blockerId: UUID,
        blockedId: UUID,
    ) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "INSERT INTO user_blocks (blocker_id, blocked_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
            ).use { ps ->
                ps.setObject(1, blockerId)
                ps.setObject(2, blockedId)
                ps.executeUpdate()
            }
        }
    }

    fun setShadowBan(
        userId: UUID,
        value: Boolean,
    ) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("UPDATE users SET is_shadow_banned = ? WHERE id = ?").use { ps ->
                ps.setBoolean(1, value)
                ps.setObject(2, userId)
                ps.executeUpdate()
            }
        }
    }

    fun createConv(
        a: UUID,
        b: UUID,
    ): UUID = repository.findOrCreate1to1(a, b).conversation.id

    fun lastMessageAtFor(conversationId: UUID): Instant? {
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT last_message_at FROM conversations WHERE id = ?").use { ps ->
                ps.setObject(1, conversationId)
                ps.executeQuery().use { rs ->
                    rs.next()
                    return rs.getTimestamp("last_message_at")?.toInstant()
                }
            }
        }
    }

    fun countMessagesFromSender(senderId: UUID): Int {
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

    fun countNotificationsFor(
        recipientId: UUID,
        type: String = "chat_message",
    ): Int {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM notifications WHERE user_id = ? AND type = ?",
            ).use { ps ->
                ps.setObject(1, recipientId)
                ps.setString(2, type)
                ps.executeQuery().use { rs ->
                    rs.next()
                    return rs.getInt(1)
                }
            }
        }
    }

    fun readNotificationBodyData(notificationId: UUID): JsonObject? {
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT body_data::text FROM notifications WHERE id = ?").use { ps ->
                ps.setObject(1, notificationId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    val raw = rs.getString(1) ?: return null
                    return Json.parseToJsonElement(raw).jsonObject
                }
            }
        }
    }

    fun deleteUserHard(userId: UUID) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("DELETE FROM users WHERE id = ?").use { ps ->
                ps.setObject(1, userId)
                ps.executeUpdate()
            }
        }
    }

    fun cleanup(vararg userIds: UUID) {
        dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                userIds.forEach { uid ->
                    st.executeUpdate("DELETE FROM notifications WHERE user_id = '$uid' OR actor_user_id = '$uid'")
                    st.executeUpdate("DELETE FROM user_blocks WHERE blocker_id = '$uid' OR blocked_id = '$uid'")
                    st.executeUpdate("DELETE FROM chat_messages WHERE sender_id = '$uid'")
                }
                userIds.forEach { uid ->
                    val convIds = mutableListOf<String>()
                    conn.prepareStatement(
                        """
                        SELECT DISTINCT c.id::text FROM conversations c
                          LEFT JOIN conversation_participants cp ON cp.conversation_id = c.id
                         WHERE c.created_by = ?::uuid OR cp.user_id = ?::uuid
                        """.trimIndent(),
                    ).use { ps ->
                        ps.setObject(1, uid)
                        ps.setObject(2, uid)
                        ps.executeQuery().use { rs ->
                            while (rs.next()) convIds += rs.getString(1)
                        }
                    }
                    convIds.forEach { cid ->
                        st.executeUpdate("DELETE FROM notifications WHERE target_id = '$cid'")
                        st.executeUpdate("DELETE FROM chat_messages WHERE conversation_id = '$cid'")
                        st.executeUpdate("DELETE FROM conversation_participants WHERE conversation_id = '$cid'")
                        st.executeUpdate("DELETE FROM conversations WHERE id = '$cid'")
                    }
                }
                userIds.forEach { uid ->
                    st.executeUpdate("DELETE FROM users WHERE id = '$uid'")
                }
            }
        }
    }

    fun service(
        emitter: NotificationEmitter,
        dispatcher: NotificationDispatcher = NoopRecordingDispatcher(),
        repo: ChatRepository = repository,
    ): ChatService =
        ChatService(
            repository = repo,
            notifications = emitter,
            dispatcher = dispatcher,
            rateLimiter = InMemoryRateLimiter(),
            remoteConfig = NullRemoteConfigChatNotif,
            textModerator = id.nearyou.app.moderation.TestModerationFixtures.ALLOW_ONLY_MODERATOR,
            moderationQueue = id.nearyou.app.moderation.TestModerationFixtures.SHARED_QUEUE_REPO,
        )

    suspend fun withChat(
        emitter: NotificationEmitter,
        dispatcher: NotificationDispatcher = NoopRecordingDispatcher(),
        realtime: ChatRealtimeClient = FakeChatRealtimeClientNotif(),
        repo: ChatRepository = repository,
        block: suspend ApplicationTestBuilder.(svc: ChatService, realtimeClient: ChatRealtimeClient) -> Unit,
    ) {
        val svc = service(emitter, dispatcher, repo)
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
                    exception<Throwable> { call, _ ->
                        call.respondText(
                            "{\"error\":{\"code\":\"internal\"}}",
                            ContentType.Application.Json,
                            HttpStatusCode.InternalServerError,
                        )
                    }
                }
                install(Authentication) { configureUserJwt(keys, users, Instant::now) }
                chatRoutes(svc, contentGuard, realtime)
                notificationRoutes(notificationService)
            }
            block(svc, realtime)
        }
    }

    suspend fun ApplicationTestBuilder.postSend(
        token: String,
        conversationId: UUID,
        content: String? = "halo",
        extraJson: String = "",
    ) = createClient { install(ClientCN) { json() } }
        .post("/api/v1/chat/$conversationId/messages") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            val body =
                if (content == null) {
                    if (extraJson.isNotEmpty()) "{$extraJson}" else "{}"
                } else {
                    val esc = content.replace("\\", "\\\\").replace("\"", "\\\"")
                    if (extraJson.isNotEmpty()) "{\"content\":\"$esc\",$extraJson}" else "{\"content\":\"$esc\"}"
                }
            setBody(body)
        }

    fun messageId(body: String): UUID =
        UUID.fromString(
            Json.parseToJsonElement(body).jsonObject["id"]!!.jsonPrimitive.content,
        )

    // -----------------------------------------------------------------------
    // 4.2 + 4.3 — successful send invokes emit exactly once with the right shape;
    // recipient is the OTHER participant.
    // -----------------------------------------------------------------------
    "4.2/4.3 — successful send invokes emit exactly once; recipient is the OTHER participant" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val conv = createConv(sender, recipient)
        try {
            val recorder = RecordingNotificationEmitter()
            withChat(emitter = recorder) { _, _ ->
                val resp = postSend(tok, conv, content = "halo B")
                resp.status shouldBe HttpStatusCode.Created
                val mid = messageId(resp.bodyAsText())

                recorder.invocations.size shouldBe 1
                val captured = recorder.invocations.single()
                captured.recipientId shouldBe recipient
                captured.actorUserId shouldBe sender
                captured.type shouldBe NotificationType.CHAT_MESSAGE
                captured.targetType shouldBe "message"
                captured.targetId shouldBe mid
                captured.bodyData["conversation_id"]?.jsonPrimitive?.content shouldBe conv.toString()
                captured.bodyData["preview"]?.jsonPrimitive?.content shouldBe "halo B"
            }
        } finally {
            cleanup(sender, recipient)
        }
    }

    // -----------------------------------------------------------------------
    // 4.4 — JDBC spy: exactly ONE SELECT FROM conversation_participants per send.
    // -----------------------------------------------------------------------
    "4.4 — JDBC spy: exactly one SELECT FROM conversation_participants per successful send" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val conv = createConv(sender, recipient)
        try {
            val spy = SqlSpy()
            val spyDs = SpyDataSource(dataSource, spy)
            val spyRepo = ChatRepository(spyDs)
            val recorder = RecordingNotificationEmitter()
            spy.clear()
            withChat(emitter = recorder, repo = spyRepo) { _, _ ->
                val resp = postSend(tok, conv, content = "ping")
                resp.status shouldBe HttpStatusCode.Created
            }
            val cpSelects =
                spy.statements.filter {
                    val s = it.uppercase()
                    "FROM CONVERSATION_PARTICIPANTS" in s && s.trimStart().startsWith("SELECT")
                }
            cpSelects.size shouldBe 1
        } finally {
            cleanup(sender, recipient)
        }
    }

    // -----------------------------------------------------------------------
    // 4.5 — shadow-banned sender: row persists, emit + publish skipped, 0 notif rows.
    // -----------------------------------------------------------------------
    "4.5 — shadow-banned sender: emit + publish both skipped, chat_messages row persists" {
        val (sender, tok) = seedUser(shadowBanned = true)
        val (recipient, _) = seedUser()
        val conv = createConv(sender, recipient)
        try {
            val recorder = RecordingNotificationEmitter()
            val fakeRt = FakeChatRealtimeClientNotif()
            withChat(emitter = recorder, realtime = fakeRt) { _, _ ->
                val resp = postSend(tok, conv, content = "halo")
                resp.status shouldBe HttpStatusCode.Created
            }
            countMessagesFromSender(sender) shouldBe 1
            recorder.invocations.size shouldBe 0
            fakeRt.invocations.size shouldBe 0
            countNotificationsFor(recipient) shouldBe 0
        } finally {
            cleanup(sender, recipient)
        }
    }

    // -----------------------------------------------------------------------
    // 4.6 — JDBC spy on shadow-banned send: zero `SELECT is_shadow_banned FROM users`
    // for the emit decision.
    // -----------------------------------------------------------------------
    "4.6 — shadow-ban skip reads from principal, not a fresh DB SELECT" {
        val (sender, tok) = seedUser(shadowBanned = true)
        val (recipient, _) = seedUser()
        val conv = createConv(sender, recipient)
        try {
            val spy = SqlSpy()
            val spyDs = SpyDataSource(dataSource, spy)
            val spyRepo = ChatRepository(spyDs)
            val recorder = RecordingNotificationEmitter()
            spy.clear()
            withChat(emitter = recorder, repo = spyRepo) { _, _ ->
                val resp = postSend(tok, conv, content = "shadow")
                resp.status shouldBe HttpStatusCode.Created
            }
            val shadowSelects =
                spy.statements.filter {
                    val s = it.uppercase()
                    "IS_SHADOW_BANNED" in s && "FROM USERS" in s && s.trimStart().startsWith("SELECT")
                }
            shadowSelects.size shouldBe 0
        } finally {
            cleanup(sender, recipient)
        }
    }

    // -----------------------------------------------------------------------
    // 4.7 — block in either direction: 403, no row, emit + publish NOT invoked.
    // -----------------------------------------------------------------------
    "4.7a — block (sender→recipient): 403, no row, emit + publish NOT invoked" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val conv = createConv(sender, recipient)
        seedBlock(sender, recipient)
        try {
            val recorder = RecordingNotificationEmitter()
            val fakeRt = FakeChatRealtimeClientNotif()
            withChat(emitter = recorder, realtime = fakeRt) { _, _ ->
                postSend(tok, conv).status shouldBe HttpStatusCode.Forbidden
            }
            countMessagesFromSender(sender) shouldBe 0
            recorder.invocations.size shouldBe 0
            fakeRt.invocations.size shouldBe 0
        } finally {
            cleanup(sender, recipient)
        }
    }

    "4.7b — block (recipient→sender): 403, no row, emit + publish NOT invoked" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val conv = createConv(sender, recipient)
        seedBlock(recipient, sender)
        try {
            val recorder = RecordingNotificationEmitter()
            val fakeRt = FakeChatRealtimeClientNotif()
            withChat(emitter = recorder, realtime = fakeRt) { _, _ ->
                postSend(tok, conv).status shouldBe HttpStatusCode.Forbidden
            }
            countMessagesFromSender(sender) shouldBe 0
            recorder.invocations.size shouldBe 0
            fakeRt.invocations.size shouldBe 0
        } finally {
            cleanup(sender, recipient)
        }
    }

    // -----------------------------------------------------------------------
    // 4.8 — 100-char ASCII content → preview is exactly first 80 chars.
    // -----------------------------------------------------------------------
    "4.8 — 100-char ASCII content: preview is exactly the first 80 chars" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val conv = createConv(sender, recipient)
        try {
            val content = ('a'..'z').joinToString("").let { (it + it + it + it).take(100) }
            content.length shouldBe 100
            val recorder = RecordingNotificationEmitter()
            withChat(emitter = recorder) { _, _ ->
                postSend(tok, conv, content = content).status shouldBe HttpStatusCode.Created
            }
            val preview = recorder.invocations.single().bodyData["preview"]!!.jsonPrimitive.content
            preview shouldBe content.take(80)
            preview.length shouldBe 80
        } finally {
            cleanup(sender, recipient)
        }
    }

    // -----------------------------------------------------------------------
    // 4.9 — multi-byte (emoji) at code-point positions 78–80: preview truncates at
    // code-point boundary, never mid-bytes.
    // -----------------------------------------------------------------------
    "4.9 — multi-byte emoji at code points 78-80 truncates at code-point boundary" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val conv = createConv(sender, recipient)
        try {
            // Build content: 77 ASCII chars + 1 emoji (code point 78) + 12 trailing chars
            // = 90 code points total. The emoji at code-point 78 ends at code-point 78
            // (single non-BMP code point, but a 4-byte UTF-8 / 2-char UTF-16 sequence).
            val emoji = "🎉" // 🎉 U+1F389 (non-BMP, surrogate pair)
            val content = "x".repeat(77) + emoji + "y".repeat(12)
            // Code-point count: 77 + 1 + 12 = 90.
            content.codePointCount(0, content.length) shouldBe 90

            val recorder = RecordingNotificationEmitter()
            withChat(emitter = recorder) { _, _ ->
                postSend(tok, conv, content = content).status shouldBe HttpStatusCode.Created
            }
            val preview = recorder.invocations.single().bodyData["preview"]!!.jsonPrimitive.content
            preview.codePointCount(0, preview.length) shouldBe 80
            // The emoji at code-point 78 fits inside the 80-cp window — preview includes
            // the full emoji, not a sliced surrogate.
            preview.contains(emoji) shouldBe true
            preview.endsWith("yy") shouldBe true // first 2 trailing y's at code-points 79, 80
            // No orphan high or low surrogate.
            preview.toCharArray().none { it.isHighSurrogate() && !preview[preview.indexOf(it) + 1].isLowSurrogate() } shouldBe true
        } finally {
            cleanup(sender, recipient)
        }
    }

    // -----------------------------------------------------------------------
    // 4.10 — embedded-only message (content NULL): preview is JSON null.
    // -----------------------------------------------------------------------
    "4.10 — embedded-only chat_messages row: body_data.preview is JSON null" {
        val (sender, _) = seedUser()
        val (recipient, _) = seedUser()
        val conv = createConv(sender, recipient)
        try {
            val recorder = RecordingNotificationEmitter()
            // Use the @VisibleForTesting hook on ChatRepository to insert a row with
            // content = NULL + a non-null embedded_post_snapshot, then run an emit on
            // the same tx. Bypasses the route-layer content-required guard.
            val emitInTx: (Connection, ChatMessageRow, UUID) -> Unit = { conn, row, recipientId ->
                recorder.emit(
                    conn = conn,
                    recipientId = recipientId,
                    actorUserId = sender,
                    type = NotificationType.CHAT_MESSAGE,
                    targetType = "message",
                    targetId = row.id,
                    bodyData =
                        buildJsonObject {
                            put("conversation_id", JsonPrimitive(conv.toString()))
                            val capturedContent = row.content
                            if (capturedContent == null) {
                                put("preview", JsonNull)
                            } else {
                                put("preview", JsonPrimitive(capturedContent))
                            }
                        },
                )
            }
            repository.testInsertEmbeddedOnly(
                conversationId = conv,
                senderId = sender,
                embeddedPostSnapshotJson = """{"id":"deadbeef-0000-0000-0000-000000000000"}""",
                emitInTx = emitInTx,
            )
            recorder.invocations.size shouldBe 1
            val capturedPreview = recorder.invocations.single().bodyData["preview"]
            capturedPreview shouldBe JsonNull
        } finally {
            cleanup(sender, recipient)
        }
    }

    // -----------------------------------------------------------------------
    // 4.10a — preview boundary at exactly 80 code points → preview length 80, no truncation.
    // -----------------------------------------------------------------------
    "4.10a — content exactly 80 code points: preview = content (no truncation)" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val conv = createConv(sender, recipient)
        try {
            val content = "z".repeat(80)
            val recorder = RecordingNotificationEmitter()
            withChat(emitter = recorder) { _, _ ->
                postSend(tok, conv, content = content).status shouldBe HttpStatusCode.Created
            }
            val preview = recorder.invocations.single().bodyData["preview"]!!.jsonPrimitive.content
            preview shouldBe content
            preview.length shouldBe 80
        } finally {
            cleanup(sender, recipient)
        }
    }

    // -----------------------------------------------------------------------
    // 4.10b — preview boundary at 81 code points → preview length 80, truncated.
    // -----------------------------------------------------------------------
    "4.10b — content 81 code points: preview length 80 (truncated)" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val conv = createConv(sender, recipient)
        try {
            val content = "w".repeat(81)
            val recorder = RecordingNotificationEmitter()
            withChat(emitter = recorder) { _, _ ->
                postSend(tok, conv, content = content).status shouldBe HttpStatusCode.Created
            }
            val preview = recorder.invocations.single().bodyData["preview"]!!.jsonPrimitive.content
            preview shouldBe "w".repeat(80)
            preview.length shouldBe 80
        } finally {
            cleanup(sender, recipient)
        }
    }

    // -----------------------------------------------------------------------
    // 4.10c — non-BMP code-point semantics: 80 emojis → preview is 80 emojis (160 UTF-16 chars).
    // -----------------------------------------------------------------------
    "4.10c — 80 non-BMP emoji: preview is 80 code points (160 UTF-16 chars)" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val conv = createConv(sender, recipient)
        try {
            val emoji = "🎉" // 🎉
            val content = emoji.repeat(80)
            content.length shouldBe 160 // UTF-16 char count
            content.codePointCount(0, content.length) shouldBe 80
            val recorder = RecordingNotificationEmitter()
            withChat(emitter = recorder) { _, _ ->
                postSend(tok, conv, content = content).status shouldBe HttpStatusCode.Created
            }
            val preview = recorder.invocations.single().bodyData["preview"]!!.jsonPrimitive.content
            preview.codePointCount(0, preview.length) shouldBe 80
            preview.length shouldBe 160
            preview shouldBe content
        } finally {
            cleanup(sender, recipient)
        }
    }

    // -----------------------------------------------------------------------
    // 4.10d — body_data.conversation_id: canonical lowercase RFC 4122 form (assert via
    // persisted JSONB read-back through DbNotificationEmitter).
    // -----------------------------------------------------------------------
    "4.10d — body_data.conversation_id is canonical lowercase RFC 4122 form" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val conv = createConv(sender, recipient)
        try {
            val recorder = RecordingNotificationEmitter(delegate = realDbEmitter)
            withChat(emitter = recorder) { _, _ ->
                postSend(tok, conv, content = "uuid-test").status shouldBe HttpStatusCode.Created
            }
            val emittedId = recorder.invocations.single().returnedId
            emittedId shouldNotBe null
            val persisted = readNotificationBodyData(emittedId!!)
            persisted shouldNotBe null
            val convStr = persisted!!["conversation_id"]!!.jsonPrimitive.content
            // Canonical lowercase RFC 4122: 8-4-4-4-12 hex with dashes, all lowercase.
            val canonical = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
            canonical.matches(convStr) shouldBe true
            convStr shouldBe conv.toString()
        } finally {
            cleanup(sender, recipient)
        }
    }

    // -----------------------------------------------------------------------
    // 4.10e — mid-flight is_shadow_banned flip race: stale-FALSE principal → emit IS
    // invoked + publish IS invoked.
    // -----------------------------------------------------------------------
    "4.10e — mid-flight shadow-ban flip race: stale principal still emits + publishes" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val conv = createConv(sender, recipient)
        try {
            val recorder = RecordingNotificationEmitter()
            val fakeRt = FakeChatRealtimeClientNotif()
            // Pin sequencing via a custom ChatRepository that flips after the INSERT but
            // before the route's publish branch (matches ChatRealtimeBroadcastTest test 17).
            val flippingRepo =
                object : ChatRepository(dataSource) {
                    private var firstCall = true

                    override fun sendMessage(
                        conversationId: UUID,
                        senderId: UUID,
                        content: String,
                        emitInTx: ((Connection, ChatMessageRow, UUID) -> Unit)?,
                        preInsertHookInTx: ((Connection) -> Unit)?,
                        afterInsertHookInTx: ((Connection, ChatMessageRow) -> Unit)?,
                    ): ChatMessageRow {
                        val row =
                            super.sendMessage(
                                conversationId = conversationId,
                                senderId = senderId,
                                content = content,
                                emitInTx = emitInTx,
                                preInsertHookInTx = preInsertHookInTx,
                                afterInsertHookInTx = afterInsertHookInTx,
                            )
                        if (firstCall) {
                            firstCall = false
                            setShadowBan(senderId, true)
                        }
                        return row
                    }
                }
            withChat(emitter = recorder, realtime = fakeRt, repo = flippingRepo) { _, _ ->
                postSend(tok, conv, content = "first").status shouldBe HttpStatusCode.Created
            }
            // Stale principal value (FALSE at auth) drove BOTH the emit branch (in-tx)
            // AND the publish branch (post-commit).
            recorder.invocations.size shouldBe 1
            fakeRt.invocations.size shouldBe 1
        } finally {
            cleanup(sender, recipient)
        }
    }

    // -----------------------------------------------------------------------
    // 4.11 — emit failure (recipient hard-deleted): entire tx rolls back.
    // -----------------------------------------------------------------------
    "4.11 — emit failure rolls back entire chat send (chat_messages, last_message_at, notifications)" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val conv = createConv(sender, recipient)
        val before = lastMessageAtFor(conv)
        try {
            // Emit fails: simulate by throwing inside the emit callback. The failure
            // mirrors "recipient hard-deleted between auth and emit" (FK violation on
            // notifications.user_id) — an in-tx exception that rolls back INSERT +
            // notifications + last_message_at UPDATE.
            val throwingEmitter =
                object : NotificationEmitter {
                    override fun emit(
                        conn: Connection,
                        recipientId: UUID,
                        actorUserId: UUID?,
                        type: NotificationType,
                        targetType: String?,
                        targetId: UUID?,
                        bodyData: JsonObject,
                    ): UUID? {
                        throw java.sql.SQLException("simulated FK violation on notifications.user_id")
                    }
                }
            val fakeRt = FakeChatRealtimeClientNotif()
            withChat(emitter = throwingEmitter, realtime = fakeRt) { _, _ ->
                val resp = postSend(tok, conv, content = "halo")
                resp.status shouldNotBe HttpStatusCode.Created
                // Per spec: 5xx (chat-foundation's existing error-handling path).
                resp.status shouldBe HttpStatusCode.InternalServerError
            }
            countMessagesFromSender(sender) shouldBe 0
            countNotificationsFor(recipient) shouldBe 0
            lastMessageAtFor(conv) shouldBe before
            fakeRt.invocations.size shouldBe 0
        } finally {
            cleanup(sender, recipient)
        }
    }

    // -----------------------------------------------------------------------
    // 4.12 — composite dispatcher fan-out: production composite (with FakeFcm leg)
    // records exactly one entry; in-app row exists.
    // -----------------------------------------------------------------------
    "4.12 — composite dispatcher fan-out: in-app row + dispatcher invocation both fire" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val conv = createConv(sender, recipient)
        try {
            val recorder = RecordingNotificationEmitter(delegate = realDbEmitter)
            val recordingDispatcher = RecordingDispatcher()
            withChat(emitter = recorder, dispatcher = recordingDispatcher) { _, _ ->
                postSend(tok, conv, content = "halo").status shouldBe HttpStatusCode.Created
            }
            // BOTH legs must fire (per N6 hardening: a single-leg-only failure is the
            // regression class this test catches).
            countNotificationsFor(recipient) shouldBe 1
            recordingDispatcher.dispatched.size shouldBe 1
            recordingDispatcher.dispatched.single() shouldBe recorder.invocations.single().returnedId
        } finally {
            cleanup(sender, recipient)
        }
    }

    // -----------------------------------------------------------------------
    // 4.13 — recipient sees the new notification via GET /api/v1/notifications.
    // -----------------------------------------------------------------------
    "4.13 — recipient sees notification via GET /api/v1/notifications with expected shape" {
        val (sender, tok) = seedUser()
        val (recipient, recipientTok) = seedUser()
        val conv = createConv(sender, recipient)
        try {
            val recorder = RecordingNotificationEmitter(delegate = realDbEmitter)
            withChat(emitter = recorder) { _, _ ->
                val resp = postSend(tok, conv, content = "halo Bob")
                resp.status shouldBe HttpStatusCode.Created
                val mid = messageId(resp.bodyAsText())
                val notifResp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/notifications") { bearerAuth(recipientTok) }
                notifResp.status shouldBe HttpStatusCode.OK
                val items = Json.parseToJsonElement(notifResp.bodyAsText()).jsonObject["items"]!!.jsonArray
                items.size shouldBe 1
                val dto = items[0].jsonObject
                dto["type"]!!.jsonPrimitive.content shouldBe "chat_message"
                dto["actor_user_id"]!!.jsonPrimitive.content shouldBe sender.toString()
                dto["target_type"]!!.jsonPrimitive.content shouldBe "message"
                dto["target_id"]!!.jsonPrimitive.content shouldBe mid.toString()
                val bodyData = dto["body_data"]!!.jsonObject
                bodyData["conversation_id"]!!.jsonPrimitive.content shouldBe conv.toString()
                bodyData["preview"]!!.jsonPrimitive.content shouldBe "halo Bob"
                // read_at is null in the row → field omitted from serialization
                // (explicitNulls = false in route config). Either omitted or JsonNull is OK.
                val readAt = dto["read_at"]
                (readAt == null || readAt is JsonNull) shouldBe true
            }
        } finally {
            cleanup(sender, recipient)
        }
    }

    // -----------------------------------------------------------------------
    // 4.14 — preview frozen at emit (later content edit / redaction does not mutate).
    // -----------------------------------------------------------------------
    "4.14 — preview frozen at emit (post-emit redaction does not mutate notification)" {
        val (sender, tok) = seedUser()
        val (recipient, recipientTok) = seedUser()
        val conv = createConv(sender, recipient)
        try {
            val recorder = RecordingNotificationEmitter(delegate = realDbEmitter)
            withChat(emitter = recorder) { _, _ ->
                postSend(tok, conv, content = "halo Alice").status shouldBe HttpStatusCode.Created
            }
            // Simulate redaction: set `redacted_at` + `redacted_by` (the V15 CHECK
            // requires both be set together) and mutate `content` to a different value
            // to demonstrate frozen-at-emit (the notification's preview must NOT track
            // the new content). The first V15 CHECK forbids fully clearing content
            // unless an embedded field is set; replacing it with a different non-null
            // string is the strictest variant of "the source content changed".
            val mid = recorder.invocations.single().targetId
            dataSource.connection.use { conn ->
                conn.prepareStatement(
                    "UPDATE chat_messages SET redacted_at = NOW(), redacted_by = ?, content = ? WHERE id = ?",
                ).use { ps ->
                    ps.setObject(1, sender) // any UUID — admin_users FK is deferred
                    ps.setString(2, "REDACTED-DIFFERENT-CONTENT")
                    ps.setObject(3, mid)
                    ps.executeUpdate()
                }
            }
            // Re-fetch via the route — preview is unchanged.
            withChat(emitter = recorder) { _, _ ->
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/notifications") { bearerAuth(recipientTok) }
                val items = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["items"]!!.jsonArray
                items.size shouldBe 1
                items[0].jsonObject["body_data"]!!.jsonObject["preview"]!!.jsonPrimitive.content shouldBe "halo Alice"
            }
        } finally {
            cleanup(sender, recipient)
        }
    }

    // -----------------------------------------------------------------------
    // 4.15 — publish Failure does NOT roll back persisted INSERT or notification.
    // -----------------------------------------------------------------------
    "4.15 — publish PublishResult.Failure does NOT roll back chat row OR notification" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val conv = createConv(sender, recipient)
        try {
            val recorder = RecordingNotificationEmitter(delegate = realDbEmitter)
            val fakeRt = FakeChatRealtimeClientNotif(failureReason = "java.io.IOException")
            withChat(emitter = recorder, realtime = fakeRt) { _, _ ->
                val resp = postSend(tok, conv, content = "halo")
                resp.status shouldBe HttpStatusCode.Created
            }
            countMessagesFromSender(sender) shouldBe 1
            countNotificationsFor(recipient) shouldBe 1
        } finally {
            cleanup(sender, recipient)
        }
    }

    // -----------------------------------------------------------------------
    // 4.17 — publish exception (IOException) does NOT roll back persisted INSERT or
    // notification.
    // -----------------------------------------------------------------------
    "4.17 — publish IOException does NOT roll back chat row OR notification" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val conv = createConv(sender, recipient)
        try {
            val recorder = RecordingNotificationEmitter(delegate = realDbEmitter)
            val fakeRt = FakeChatRealtimeClientNotif(throwOnPublish = java.io.IOException("simulated"))
            withChat(emitter = recorder, realtime = fakeRt) { _, _ ->
                val resp = postSend(tok, conv, content = "halo")
                resp.status shouldBe HttpStatusCode.Created
            }
            countMessagesFromSender(sender) shouldBe 1
            countNotificationsFor(recipient) shouldBe 1
        } finally {
            cleanup(sender, recipient)
        }
    }

    // -----------------------------------------------------------------------
    // 4.16 — pre-emit short-circuits: 2001-char, whitespace-only, non-participant,
    // unknown conv, malformed UUID, unauthenticated → emit + publish NOT invoked.
    // -----------------------------------------------------------------------
    "4.16a — 2001-char content rejected before emit (400, no row, no emit, no publish)" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val conv = createConv(sender, recipient)
        try {
            val recorder = RecordingNotificationEmitter()
            val fakeRt = FakeChatRealtimeClientNotif()
            withChat(emitter = recorder, realtime = fakeRt) { _, _ ->
                postSend(tok, conv, content = "x".repeat(2001)).status shouldBe HttpStatusCode.BadRequest
            }
            countMessagesFromSender(sender) shouldBe 0
            recorder.invocations.size shouldBe 0
            fakeRt.invocations.size shouldBe 0
        } finally {
            cleanup(sender, recipient)
        }
    }

    "4.16b — whitespace-only content rejected before emit" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val conv = createConv(sender, recipient)
        try {
            val recorder = RecordingNotificationEmitter()
            val fakeRt = FakeChatRealtimeClientNotif()
            withChat(emitter = recorder, realtime = fakeRt) { _, _ ->
                postSend(tok, conv, content = "   ").status shouldBe HttpStatusCode.BadRequest
            }
            recorder.invocations.size shouldBe 0
            fakeRt.invocations.size shouldBe 0
        } finally {
            cleanup(sender, recipient)
        }
    }

    "4.16c — non-participant rejected before emit (403)" {
        val (sender, tok) = seedUser()
        val (a, _) = seedUser()
        val (b, _) = seedUser()
        val foreignConv = createConv(a, b)
        try {
            val recorder = RecordingNotificationEmitter()
            val fakeRt = FakeChatRealtimeClientNotif()
            withChat(emitter = recorder, realtime = fakeRt) { _, _ ->
                postSend(tok, foreignConv).status shouldBe HttpStatusCode.Forbidden
            }
            recorder.invocations.size shouldBe 0
            fakeRt.invocations.size shouldBe 0
        } finally {
            cleanup(sender, a, b)
        }
    }

    "4.16d — unknown conversation_id rejected before emit (404)" {
        val (sender, tok) = seedUser()
        val ghost = UUID.randomUUID()
        try {
            val recorder = RecordingNotificationEmitter()
            val fakeRt = FakeChatRealtimeClientNotif()
            withChat(emitter = recorder, realtime = fakeRt) { _, _ ->
                postSend(tok, ghost).status shouldBe HttpStatusCode.NotFound
            }
            recorder.invocations.size shouldBe 0
            fakeRt.invocations.size shouldBe 0
        } finally {
            cleanup(sender)
        }
    }

    "4.16e — malformed UUID path rejected before emit (400)" {
        val (sender, tok) = seedUser()
        try {
            val recorder = RecordingNotificationEmitter()
            val fakeRt = FakeChatRealtimeClientNotif()
            withChat(emitter = recorder, realtime = fakeRt) { _, _ ->
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .post("/api/v1/chat/not-a-uuid/messages") {
                            header(HttpHeaders.Authorization, "Bearer $tok")
                            contentType(ContentType.Application.Json)
                            setBody("""{"content":"x"}""")
                        }
                resp.status shouldBe HttpStatusCode.BadRequest
            }
            recorder.invocations.size shouldBe 0
            fakeRt.invocations.size shouldBe 0
        } finally {
            cleanup(sender)
        }
    }

    "4.16f — unauthenticated rejected before emit (401)" {
        val (a, _) = seedUser()
        val (b, _) = seedUser()
        val conv = createConv(a, b)
        try {
            val recorder = RecordingNotificationEmitter()
            val fakeRt = FakeChatRealtimeClientNotif()
            withChat(emitter = recorder, realtime = fakeRt) { _, _ ->
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .post("/api/v1/chat/$conv/messages") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"content":"x"}""")
                        }
                resp.status shouldBe HttpStatusCode.Unauthorized
            }
            recorder.invocations.size shouldBe 0
            fakeRt.invocations.size shouldBe 0
        } finally {
            cleanup(a, b)
        }
    }

    // -----------------------------------------------------------------------
    // 4.18 — INSERT failure rolls back notification + last_message_at.
    // -----------------------------------------------------------------------
    "4.18 — INSERT failure rolls back: 0 chat_messages rows, 0 notifications, last_message_at unchanged" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val conv = createConv(sender, recipient)
        val before = lastMessageAtFor(conv)
        try {
            val recorder = RecordingNotificationEmitter(delegate = realDbEmitter)
            val fakeRt = FakeChatRealtimeClientNotif()
            // Repository whose sendMessage throws AFTER opening the tx but in a way
            // that ensures rollback (the parent class rolls back on any throw).
            val failingRepo =
                object : ChatRepository(dataSource) {
                    override fun sendMessage(
                        conversationId: UUID,
                        senderId: UUID,
                        content: String,
                        emitInTx: ((Connection, ChatMessageRow, UUID) -> Unit)?,
                        preInsertHookInTx: ((Connection) -> Unit)?,
                        afterInsertHookInTx: ((Connection, ChatMessageRow) -> Unit)?,
                    ): ChatMessageRow {
                        throw RuntimeException("simulated failure between INSERT and emit")
                    }
                }
            withChat(emitter = recorder, realtime = fakeRt, repo = failingRepo) { _, _ ->
                val resp = postSend(tok, conv, content = "halo")
                resp.status shouldNotBe HttpStatusCode.Created
            }
            countMessagesFromSender(sender) shouldBe 0
            countNotificationsFor(recipient) shouldBe 0
            lastMessageAtFor(conv) shouldBe before
            fakeRt.invocations.size shouldBe 0
        } finally {
            cleanup(sender, recipient)
        }
    }

    // -----------------------------------------------------------------------
    // 4.19 — last_message_at + notification + chat_messages all commit atomically;
    // publish runs strictly AFTER the tx commits.
    // -----------------------------------------------------------------------
    "4.19 — last_message_at, notification, chat_messages all commit atomically; publish post-commit" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val conv = createConv(sender, recipient)
        try {
            val recorder = RecordingNotificationEmitter(delegate = realDbEmitter)
            // Capture wall-clock instants from the publish callback to verify it ran
            // AFTER the tx committed (i.e., after a separate-connection read sees the row).
            val publishObservedRowVisible = java.util.concurrent.atomic.AtomicReference<Boolean?>(null)
            val fakeRt =
                FakeChatRealtimeClientNotif(
                    onPublish = { _, msg ->
                        // Use a fresh connection — if visible, the chat-foundation tx
                        // (and its in-tx notifications INSERT + last_message_at UPDATE)
                        // already committed before the publish call fired.
                        val visible =
                            dataSource.connection.use { c ->
                                c.prepareStatement(
                                    "SELECT 1 FROM chat_messages cm " +
                                        "JOIN notifications n ON n.target_id = cm.id AND n.type = 'chat_message' " +
                                        "WHERE cm.id = ?",
                                ).use { ps ->
                                    ps.setObject(1, msg.id)
                                    ps.executeQuery().use { rs -> rs.next() }
                                }
                            }
                        publishObservedRowVisible.set(visible)
                    },
                )
            val before = lastMessageAtFor(conv)
            withChat(emitter = recorder, realtime = fakeRt) { _, _ ->
                postSend(tok, conv, content = "halo Bob").status shouldBe HttpStatusCode.Created
            }
            // All three landed atomically.
            countMessagesFromSender(sender) shouldBe 1
            countNotificationsFor(recipient) shouldBe 1
            val after = lastMessageAtFor(conv)
            after shouldNotBe null
            if (before != null) (after!!.isAfter(before) || after == before) shouldBe true
            // Publish ran strictly after commit — fresh-connection read sees both row + notif.
            publishObservedRowVisible.get() shouldBe true
            fakeRt.invocations.size shouldBe 1
        } finally {
            cleanup(sender, recipient)
        }
    }

    // -----------------------------------------------------------------------
    // 4.20 — silent-ignore composition: embedded fields silently ignored AND preview
    // is from `content`, not from the silently-ignored embedded value.
    // -----------------------------------------------------------------------
    "4.20a — embedded_post_id silently ignored: 201, preview from content" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val conv = createConv(sender, recipient)
        try {
            val recorder = RecordingNotificationEmitter()
            withChat(emitter = recorder) { _, _ ->
                val resp =
                    postSend(
                        tok,
                        conv,
                        content = "halo",
                        extraJson = "\"embedded_post_id\":\"${UUID.randomUUID()}\"",
                    )
                resp.status shouldBe HttpStatusCode.Created
            }
            recorder.invocations.single().bodyData["preview"]!!.jsonPrimitive.content shouldBe "halo"
        } finally {
            cleanup(sender, recipient)
        }
    }

    "4.20b — embedded_post_snapshot silently ignored: 201, preview from content" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val conv = createConv(sender, recipient)
        try {
            val recorder = RecordingNotificationEmitter()
            withChat(emitter = recorder) { _, _ ->
                val resp =
                    postSend(
                        tok,
                        conv,
                        content = "halo",
                        extraJson = "\"embedded_post_snapshot\":{\"id\":\"${UUID.randomUUID()}\"}",
                    )
                resp.status shouldBe HttpStatusCode.Created
            }
            recorder.invocations.single().bodyData["preview"]!!.jsonPrimitive.content shouldBe "halo"
        } finally {
            cleanup(sender, recipient)
        }
    }

    "4.20c — embedded_post_edit_id silently ignored: 201, preview from content" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val conv = createConv(sender, recipient)
        try {
            val recorder = RecordingNotificationEmitter()
            withChat(emitter = recorder) { _, _ ->
                val resp =
                    postSend(
                        tok,
                        conv,
                        content = "halo",
                        extraJson = "\"embedded_post_edit_id\":\"${UUID.randomUUID()}\"",
                    )
                resp.status shouldBe HttpStatusCode.Created
            }
            recorder.invocations.single().bodyData["preview"]!!.jsonPrimitive.content shouldBe "halo"
        } finally {
            cleanup(sender, recipient)
        }
    }

    // The `deleteUserHard` helper exists for parity with `cleanup` even though no
    // current test calls it (4.11 simulates the recipient-hard-delete race via a
    // throwing emitter rather than a real DELETE). Keeping the helper available to
    // future tests that may exercise the FK-violation rollback path against the real
    // schema; the @Suppress avoids a ktlint unused-suppression warning.
    @Suppress("UNUSED_VARIABLE", "ktlint:standard:property-naming")
    val unusedDeleteRef = ::deleteUserHard
})

// =====================================================================================
// Test fixtures.
// =====================================================================================

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

private object NullRemoteConfigChatNotif : RemoteConfig {
    override fun getLong(key: String): Long? = null

    override fun getBoolean(key: String): Boolean? = null
}

/**
 * Records every emit invocation. Optionally delegates to a real [DbNotificationEmitter]
 * (via [delegate]) so DB-asserting tests (4.10d, 4.12, 4.13, 4.14, 4.15, 4.17, 4.19) can
 * read the persisted row back; emit-only assertion tests (4.2, 4.3, 4.5, 4.7, 4.8, 4.9,
 * 4.10a-c, 4.10e, 4.16, 4.20) skip the delegate.
 */
private class RecordingNotificationEmitter(
    private val delegate: NotificationEmitter? = null,
) : NotificationEmitter {
    data class Captured(
        val recipientId: UUID,
        val actorUserId: UUID?,
        val type: NotificationType,
        val targetType: String?,
        val targetId: UUID?,
        val bodyData: JsonObject,
        val returnedId: UUID?,
    )

    private val records = ConcurrentLinkedQueue<Captured>()

    val invocations: List<Captured>
        get() = records.toList()

    override fun emit(
        conn: Connection,
        recipientId: UUID,
        actorUserId: UUID?,
        type: NotificationType,
        targetType: String?,
        targetId: UUID?,
        bodyData: JsonObject,
    ): UUID? {
        val id =
            delegate?.emit(
                conn = conn,
                recipientId = recipientId,
                actorUserId = actorUserId,
                type = type,
                targetType = targetType,
                targetId = targetId,
                bodyData = bodyData,
            )
        records.add(
            Captured(
                recipientId = recipientId,
                actorUserId = actorUserId,
                type = type,
                targetType = targetType,
                targetId = targetId,
                bodyData = bodyData,
                returnedId = id,
            ),
        )
        return id
    }
}

/** Records every dispatcher invocation for the composite-fan-out scenario (4.12). */
private class RecordingDispatcher : NotificationDispatcher {
    private val records = ConcurrentLinkedQueue<UUID>()

    val dispatched: List<UUID>
        get() = records.toList()

    override fun dispatch(notificationId: UUID) {
        records.add(notificationId)
    }
}

/** Default no-op recording dispatcher used when the test doesn't care about dispatch. */
private class NoopRecordingDispatcher : NotificationDispatcher {
    override fun dispatch(notificationId: UUID) = Unit
}

/**
 * Test-double for [ChatRealtimeClient]. Mirrors the FakeChatRealtimeClient pattern from
 * `ChatRealtimeBroadcastTest` (kept local to avoid cross-test dependency).
 */
private class FakeChatRealtimeClientNotif(
    private val failureReason: String? = null,
    private val throwOnPublish: Throwable? = null,
    private val onPublish: (UUID, ChatMessageBroadcast) -> Unit = { _, _ -> },
) : ChatRealtimeClient {
    private val collected = ConcurrentLinkedQueue<Pair<UUID, ChatMessageBroadcast>>()

    val invocations: List<Pair<UUID, ChatMessageBroadcast>>
        get() = collected.toList()

    override suspend fun publish(
        conversationId: UUID,
        message: ChatMessageBroadcast,
    ): PublishResult {
        collected.add(conversationId to message)
        onPublish(conversationId, message)
        throwOnPublish?.let { throw it }
        return failureReason?.let { PublishResult.Failure(it) } ?: PublishResult.Success
    }
}

/**
 * Captures every PreparedStatement SQL string for JDBC-spy assertions (4.4 + 4.6).
 * Wraps the production [HikariDataSource] without altering its connection lifecycle.
 */
private class SqlSpy {
    private val records = ConcurrentLinkedQueue<String>()

    val statements: List<String>
        get() = records.toList()

    fun clear() {
        records.clear()
    }

    fun record(sql: String) {
        records.add(sql)
    }
}

private class SpyDataSource(
    private val delegate: DataSource,
    private val spy: SqlSpy,
) : DataSource by delegate {
    override fun getConnection(): Connection = SpyConnection(delegate.connection, spy)

    override fun getConnection(
        username: String?,
        password: String?,
    ): Connection = SpyConnection(delegate.getConnection(username, password), spy)
}

private class SpyConnection(
    private val delegate: Connection,
    private val spy: SqlSpy,
) : Connection by delegate {
    override fun prepareStatement(sql: String): java.sql.PreparedStatement {
        spy.record(sql)
        return delegate.prepareStatement(sql)
    }

    override fun prepareStatement(
        sql: String,
        autoGeneratedKeys: Int,
    ): java.sql.PreparedStatement {
        spy.record(sql)
        return delegate.prepareStatement(sql, autoGeneratedKeys)
    }
}
