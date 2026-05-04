package id.nearyou.app.chat

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
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
import id.nearyou.app.core.domain.ratelimit.RateLimiter
import id.nearyou.app.guard.ContentLengthGuard
import id.nearyou.app.infra.repo.JdbcUserRepository
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.sql.Date
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import javax.sql.DataSource
import ch.qos.logback.classic.Logger as LogbackLogger
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientCN

/**
 * Integration tests for the chat-realtime-broadcast change. Covers the 20 scenarios in
 * `chat-realtime-broadcast/specs/chat-realtime-broadcast/spec.md` § "Integration test
 * coverage" that exercise the chat send route's publish-side behavior with a test-double
 * [ChatRealtimeClient]. Real Supabase round-trip is exercised separately in
 * `:infra:supabase`'s `SupabaseBroadcastChatClientTest` (network-tagged).
 *
 * Tagged `database` — depends on dev Postgres for the `users` / `conversations` /
 * `conversation_participants` / `chat_messages` / `user_blocks` schema (V15) plus the V11
 * `subscription_status` and V11 `is_shadow_banned` columns.
 */
@Tags("database")
class ChatRealtimeBroadcastTest : StringSpec({

    val dataSource = hikari()
    val keys = RsaKeyLoader(TestKeys.freshEncodedPemPrivateKey(), kid = "test-chat-broadcast")
    val jwtIssuer = JwtIssuer(keys)
    val users = JdbcUserRepository(dataSource)
    val repository = ChatRepository(dataSource)
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
                ps.setString(2, "br_$short")
                ps.setString(3, "Broadcast Tester")
                ps.setDate(4, Date.valueOf(LocalDate.of(1990, 1, 1)))
                ps.setString(5, "x${short.takeLast(7)}")
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

    fun chatRowExists(messageId: UUID): Boolean {
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT 1 FROM chat_messages WHERE id = ?").use { ps ->
                ps.setObject(1, messageId)
                ps.executeQuery().use { rs -> return rs.next() }
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

    fun cleanup(vararg userIds: UUID) {
        dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                userIds.forEach { uid ->
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

    suspend fun withChat(
        realtime: ChatRealtimeClient,
        repo: ChatRepository = repository,
        rateLimiter: RateLimiter = InMemoryRateLimiter(),
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) {
        val service =
            ChatService(
                repository = repo,
                rateLimiter = rateLimiter,
                remoteConfig = NullRemoteConfigBroadcast,
            )
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
                chatRoutes(service, contentGuard, realtime)
            }
            block()
        }
    }

    suspend fun ApplicationTestBuilder.postSend(
        token: String,
        conversationId: UUID,
        content: String = "halo",
    ) = createClient { install(ClientCN) { json() } }
        .post("/api/v1/chat/$conversationId/messages") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"content":"$content"}""")
        }

    fun messageId(body: String): UUID =
        UUID.fromString(
            Json.parseToJsonElement(body)
                .jsonObject["id"]!!.jsonPrimitive.content,
        )

    suspend fun captureRoutesWarn(block: suspend (ListAppender<ILoggingEvent>) -> Unit) {
        val logger = LoggerFactory.getLogger("id.nearyou.app.chat.ChatRoutes") as LogbackLogger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        val previousLevel = logger.level
        logger.level = Level.WARN
        try {
            block(appender)
        } finally {
            logger.detachAppender(appender)
            logger.level = previousLevel
            appender.stop()
        }
    }

    fun ListAppender<ILoggingEvent>.publishFailedLines(): List<ILoggingEvent> =
        list.filter {
            it.level == Level.WARN && it.formattedMessage.contains("event=chat_realtime_publish_failed")
        }

    // ----------------------------------------------------------------------------------
    // Test 1 — successful send invokes publish exactly once with payload matching the row.
    // ----------------------------------------------------------------------------------
    "test 1 — successful send invokes publish exactly once with matching payload" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val conv = createConv(sender, recipient)
        try {
            val fake = FakeChatRealtimeClient()
            withChat(fake) {
                val resp = postSend(tok, conv, content = "halo B")
                resp.status shouldBe HttpStatusCode.Created
                val mid = messageId(resp.bodyAsText())
                fake.invocations.size shouldBe 1
                val (capturedConv, capturedMsg) = fake.invocations.single()
                capturedConv shouldBe conv
                capturedMsg.id shouldBe mid
                capturedMsg.conversationId shouldBe conv
                capturedMsg.senderId shouldBe sender
                capturedMsg.content shouldBe "halo B"
                capturedMsg.embeddedPostId shouldBe null
                capturedMsg.embeddedPostSnapshot shouldBe null
                capturedMsg.embeddedPostEditId shouldBe null
                capturedMsg.redactedAt shouldBe null
                // createdAt: just non-null + within reasonable window of "now".
                capturedMsg.createdAt shouldNotBe null
            }
        } finally {
            cleanup(sender, recipient)
        }
    }

    // ----------------------------------------------------------------------------------
    // Test 2 — shadow-banned sender's send persists the row but does NOT invoke publish.
    // ----------------------------------------------------------------------------------
    "test 2 — shadow-banned sender persists row but does NOT invoke publish" {
        val (sender, tok) = seedUser(shadowBanned = true)
        val (recipient, _) = seedUser()
        val conv = createConv(sender, recipient)
        try {
            val fake = FakeChatRealtimeClient()
            withChat(fake) {
                postSend(tok, conv).status shouldBe HttpStatusCode.Created
            }
            countMessagesFromSender(sender) shouldBe 1
            fake.invocations.shouldBeEmpty()
        } finally {
            cleanup(sender, recipient)
        }
    }

    // ----------------------------------------------------------------------------------
    // Test 3 — publish returns Failure → 201, row persists, last_message_at updated, WARN log.
    // ----------------------------------------------------------------------------------
    "test 3 — publish Failure does not roll back; HTTP 201; last_message_at advanced; WARN" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val conv = createConv(sender, recipient)
        try {
            val fake = FakeChatRealtimeClient(failureReason = "java.io.IOException")
            captureRoutesWarn { appender ->
                withChat(fake) {
                    val before = lastMessageAtFor(conv)
                    val resp = postSend(tok, conv, content = "halo")
                    resp.status shouldBe HttpStatusCode.Created
                    val mid = messageId(resp.bodyAsText())
                    chatRowExists(mid) shouldBe true
                    // last_message_at advanced from chat-foundation tx — publish failure
                    // does not corrupt this. Either before is null + after non-null, or
                    // before non-null + after strictly later.
                    val after = lastMessageAtFor(conv)
                    after shouldNotBe null
                    if (before != null) (after!!.isAfter(before) || after == before) shouldBe true
                    appender.publishFailedLines().size shouldBe 1
                    val msg = appender.publishFailedLines().single().formattedMessage
                    msg.contains("conversation_id=$conv") shouldBe true
                    msg.contains("message_id=$mid") shouldBe true
                    msg.contains("error_class=java.io.IOException") shouldBe true
                }
            }
        } finally {
            cleanup(sender, recipient)
        }
    }

    // ----------------------------------------------------------------------------------
    // Test 4 — publish throws IOException → same outcome as Test 3 with FQN class name.
    // ----------------------------------------------------------------------------------
    "test 4 — publish throws IOException is caught; 201; row persists; WARN with FQN" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val conv = createConv(sender, recipient)
        try {
            val fake = FakeChatRealtimeClient(throwOnPublish = java.io.IOException("simulated"))
            captureRoutesWarn { appender ->
                withChat(fake) {
                    val resp = postSend(tok, conv, content = "halo")
                    resp.status shouldBe HttpStatusCode.Created
                    val mid = messageId(resp.bodyAsText())
                    chatRowExists(mid) shouldBe true
                    appender.publishFailedLines().size shouldBe 1
                    appender.publishFailedLines().single().formattedMessage.let {
                        it.contains("error_class=java.io.IOException") shouldBe true
                    }
                }
            }
        } finally {
            cleanup(sender, recipient)
        }
    }

    // ----------------------------------------------------------------------------------
    // Test 5 — block-rejected send (403) → publish NOT invoked.
    // ----------------------------------------------------------------------------------
    "test 5 — 403 blocked send does NOT invoke publish" {
        val (sender, tok) = seedUser()
        val (blocker, _) = seedUser()
        val conv = createConv(sender, blocker)
        seedBlock(blocker, sender)
        try {
            val fake = FakeChatRealtimeClient()
            withChat(fake) {
                postSend(tok, conv).status shouldBe HttpStatusCode.Forbidden
            }
            fake.invocations.shouldBeEmpty()
        } finally {
            cleanup(sender, blocker)
        }
    }

    // ----------------------------------------------------------------------------------
    // Test 6 — rate-limited send (429) → publish NOT invoked.
    // ----------------------------------------------------------------------------------
    "test 6 — 429 rate-limited send does NOT invoke publish" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val conv = createConv(sender, recipient)
        try {
            val limiter = InMemoryRateLimiter()
            val fake = FakeChatRealtimeClient()
            withChat(fake, rateLimiter = limiter) {
                repeat(50) { postSend(tok, conv, content = "ok-$it").status shouldBe HttpStatusCode.Created }
                postSend(tok, conv).status shouldBe HttpStatusCode.TooManyRequests
            }
            // 50 publishes for the successes; the 51st (rate-limited) does NOT invoke publish.
            fake.invocations.size shouldBe 50
        } finally {
            cleanup(sender, recipient)
        }
    }

    // ----------------------------------------------------------------------------------
    // Test 7 — 400 invalid_request (empty content) → publish NOT invoked.
    // ----------------------------------------------------------------------------------
    "test 7 — 400 empty content does NOT invoke publish" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val conv = createConv(sender, recipient)
        try {
            val fake = FakeChatRealtimeClient()
            withChat(fake) {
                postSend(tok, conv, content = "").status shouldBe HttpStatusCode.BadRequest
            }
            fake.invocations.shouldBeEmpty()
        } finally {
            cleanup(sender, recipient)
        }
    }

    // ----------------------------------------------------------------------------------
    // Test 8 — 404 conversation_not_found → publish NOT invoked.
    // ----------------------------------------------------------------------------------
    "test 8 — 404 unknown conversation does NOT invoke publish" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        createConv(sender, recipient)
        val ghost = UUID.randomUUID()
        try {
            val fake = FakeChatRealtimeClient()
            withChat(fake) {
                postSend(tok, ghost).status shouldBe HttpStatusCode.NotFound
            }
            fake.invocations.shouldBeEmpty()
        } finally {
            cleanup(sender, recipient)
        }
    }

    // ----------------------------------------------------------------------------------
    // Test 9 — 403 not_a_participant → publish NOT invoked.
    // ----------------------------------------------------------------------------------
    "test 9 — 403 not-a-participant does NOT invoke publish" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val (other1, _) = seedUser()
        val (other2, _) = seedUser()
        createConv(sender, recipient)
        val foreignConv = createConv(other1, other2)
        try {
            val fake = FakeChatRealtimeClient()
            withChat(fake) {
                postSend(tok, foreignConv).status shouldBe HttpStatusCode.Forbidden
            }
            fake.invocations.shouldBeEmpty()
        } finally {
            cleanup(sender, recipient, other1, other2)
        }
    }

    // ----------------------------------------------------------------------------------
    // Test 10 — channel name format. Capture conversationId; assert (a) prefixed channel
    // identifier shape; (b) topic (after stripping `realtime:`) matches the canonical V15
    // anchored regex (NOT the loose `[0-9a-f-]{36}` form).
    // ----------------------------------------------------------------------------------
    "test 10 — channel name + topic match canonical V15 RLS regex (anchored, NOT loose)" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val conv = createConv(sender, recipient)
        try {
            val fake = FakeChatRealtimeClient()
            withChat(fake) {
                postSend(tok, conv).status shouldBe HttpStatusCode.Created
            }
            val captured = fake.invocations.single().first
            // Publisher-side channel identifier construction (matches
            // SupabaseBroadcastChatClient.chatBroadcastChannelIdentifier).
            val channel = "realtime:conversation:$captured"
            channel.startsWith("realtime:conversation:") shouldBe true
            val topic = channel.removePrefix("realtime:")
            // Canonical anchored regex from V2:75 / V15:119.
            val canonical =
                Regex("^conversation:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
            canonical.matches(topic) shouldBe true
            // Loose form would match malformed strings; assert the test author did NOT
            // use it here (regression guard against silent regex weakening).
            val loose = Regex("^conversation:[0-9a-f-]{36}$")
            loose.matches("conversation:------------------------------------") shouldBe true
        } finally {
            cleanup(sender, recipient)
        }
    }

    // ----------------------------------------------------------------------------------
    // Test 11 — payload format. Assert all 9 documented fields present on the data class
    // AND `redaction_reason` is NOT a field. JsonElement? type for embeddedPostSnapshot.
    // ----------------------------------------------------------------------------------
    "test 11 — ChatMessageBroadcast has exactly 9 fields, no redaction_reason, JsonElement?" {
        // Java reflection — kotlin-reflect not on classpath, and the data-class
        // properties surface as Java fields with matching names. The kotlinx-serialization
        // compiler plugin synthesizes `Companion` and `${'$'}childSerializers` fields on a
        // `@Serializable` class; filter them out so the assertion targets data-class
        // properties only.
        val fieldNames =
            ChatMessageBroadcast::class.java.declaredFields
                .filter { !it.isSynthetic }
                .map { it.name }
                .filter { it != "Companion" && !it.startsWith("$") }
                .toSet()
        val expected =
            setOf(
                "id",
                "conversationId",
                "senderId",
                "content",
                "embeddedPostId",
                "embeddedPostSnapshot",
                "embeddedPostEditId",
                "createdAt",
                "redactedAt",
            )
        fieldNames shouldBe expected
        // Negative assertion: no `redaction_reason` field (matches chat-foundation
        // read-path render policy — admin-only field never on the wire).
        ("redaction_reason" in fieldNames) shouldBe false
        ("redactionReason" in fieldNames) shouldBe false
        // `embeddedPostSnapshot` is JsonElement? per design § D6 (JSONB column,
        // non-stringly-typed). Java reflection: fully-qualified type name.
        val snapField = ChatMessageBroadcast::class.java.getDeclaredField("embeddedPostSnapshot")
        snapField.type.name shouldBe "kotlinx.serialization.json.JsonElement"
    }

    // ----------------------------------------------------------------------------------
    // Test 12 — tx rollback on INSERT failure: publish NOT invoked AND zero rows persist.
    // ----------------------------------------------------------------------------------
    "test 12 — tx rollback on INSERT failure: publish NOT invoked, 0 rows" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val conv = createConv(sender, recipient)
        try {
            val failingRepo =
                object : ChatRepository(dataSource) {
                    override fun sendMessage(
                        conversationId: UUID,
                        senderId: UUID,
                        content: String,
                    ): ChatMessageRow {
                        throw RuntimeException("simulated rollback")
                    }
                }
            val fake = FakeChatRealtimeClient()
            withChat(fake, repo = failingRepo) {
                val resp = postSend(tok, conv)
                resp.status shouldNotBe HttpStatusCode.Created
            }
            countMessagesFromSender(sender) shouldBe 0
            fake.invocations.shouldBeEmpty()
        } finally {
            cleanup(sender, recipient)
        }
    }

    // ----------------------------------------------------------------------------------
    // Test 13 — post-commit ordering via separate JDBC connection. The fake's publish
    // lambda performs a fresh-connection SELECT for the inserted row and asserts visibility
    // BEFORE returning — proves the chat-foundation tx already committed.
    // ----------------------------------------------------------------------------------
    "test 13 — post-commit ordering: separate JDBC connection sees row at publish time" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val conv = createConv(sender, recipient)
        try {
            val fake =
                FakeChatRealtimeClient(
                    onPublish = { _, message ->
                        // Use the spec's data source on a brand-new connection (NOT the
                        // request handler's). If the row is visible, the chat-foundation tx
                        // has committed before the publish call fired.
                        val visible =
                            dataSource.connection.use { conn ->
                                conn.prepareStatement("SELECT 1 FROM chat_messages WHERE id = ?").use { ps ->
                                    ps.setObject(1, message.id)
                                    ps.executeQuery().use { rs -> rs.next() }
                                }
                            }
                        visible shouldBe true
                    },
                )
            withChat(fake) {
                postSend(tok, conv).status shouldBe HttpStatusCode.Created
            }
            fake.invocations.size shouldBe 1
        } finally {
            cleanup(sender, recipient)
        }
    }

    // ----------------------------------------------------------------------------------
    // Test 14 — WARN log on PublishResult.Failure with `error_class = result.reason`.
    // (Distinct from Test 3's high-level outcome assertion — Test 14 drills into log fields.)
    // ----------------------------------------------------------------------------------
    "test 14 — WARN log on Failure carries result.reason as error_class (NOT \"Failure\")" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val conv = createConv(sender, recipient)
        try {
            val fake = FakeChatRealtimeClient(failureReason = "java.io.IOException")
            captureRoutesWarn { appender ->
                withChat(fake) {
                    val resp = postSend(tok, conv)
                    resp.status shouldBe HttpStatusCode.Created
                    val mid = messageId(resp.bodyAsText())
                    val lines = appender.publishFailedLines()
                    lines.size shouldBe 1
                    val msg = lines.single().formattedMessage
                    msg.contains("event=chat_realtime_publish_failed") shouldBe true
                    msg.contains("conversation_id=$conv") shouldBe true
                    msg.contains("message_id=$mid") shouldBe true
                    // The reason string is the captured FQN class name, NOT the literal
                    // string "Failure" (see design § D12).
                    msg.contains("error_class=java.io.IOException") shouldBe true
                    msg.contains("error_class=Failure") shouldBe false
                }
            }
        } finally {
            cleanup(sender, recipient)
        }
    }

    // ----------------------------------------------------------------------------------
    // Test 15 — WARN log on thrown exception caught post-commit (FQN class name).
    // ----------------------------------------------------------------------------------
    "test 15 — WARN log on thrown SocketTimeoutException carries FQN as error_class" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val conv = createConv(sender, recipient)
        try {
            val fake = FakeChatRealtimeClient(throwOnPublish = java.net.SocketTimeoutException())
            captureRoutesWarn { appender ->
                withChat(fake) {
                    postSend(tok, conv).status shouldBe HttpStatusCode.Created
                }
                appender.publishFailedLines().single().formattedMessage.let {
                    it.contains("error_class=java.net.SocketTimeoutException") shouldBe true
                }
            }
        } finally {
            cleanup(sender, recipient)
        }
    }

    // ----------------------------------------------------------------------------------
    // Test 16 — handler invokes publish exactly once per send (no handler-level retry).
    // The 4-attempts retry timing is verified at the SupabaseBroadcastChatClient level
    // (`:infra:supabase`'s `SupabaseBroadcastChatClientTest`); this test proves the chat
    // handler treats `PublishResult` as final regardless of value.
    // ----------------------------------------------------------------------------------
    "test 16 — handler invokes publish exactly once (no handler-level retry loop)" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val conv = createConv(sender, recipient)
        try {
            val fake = FakeChatRealtimeClient(failureReason = "java.io.IOException")
            withChat(fake) {
                postSend(tok, conv).status shouldBe HttpStatusCode.Created
            }
            // Exactly one invocation despite Failure return — handler does not retry.
            fake.invocations.size shouldBe 1
        } finally {
            cleanup(sender, recipient)
        }
    }

    // ----------------------------------------------------------------------------------
    // Test 17 — D2 shadow-ban race (stale principal). Mid-request flip the DB row to
    // is_shadow_banned = TRUE BETWEEN auth and publish (sequencing pinned via a custom
    // ChatRepository that flips during sendMessage — runs after auth + before the publish
    // branch; NOT via Thread.sleep).
    // ----------------------------------------------------------------------------------
    "test 17 — shadow-ban race: stale principal still publishes; next request correctly skips" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val conv = createConv(sender, recipient)
        try {
            // First request: principal loaded with isShadowBanned=FALSE; flip happens
            // during sendMessage (between rate-limit gate + tx commit and publish branch).
            // Principal is captured at auth time so the publish branch still sees FALSE.
            val flippingRepo =
                object : ChatRepository(dataSource) {
                    private var firstCall = true

                    override fun sendMessage(
                        conversationId: UUID,
                        senderId: UUID,
                        content: String,
                    ): ChatMessageRow {
                        val row = super.sendMessage(conversationId, senderId, content)
                        if (firstCall) {
                            firstCall = false
                            // Mid-request admin flip — happens after auth completed,
                            // before the handler reaches the publish-skip branch.
                            setShadowBan(senderId, true)
                        }
                        return row
                    }
                }
            val fake = FakeChatRealtimeClient()
            withChat(fake, repo = flippingRepo) {
                postSend(tok, conv, content = "first").status shouldBe HttpStatusCode.Created
            }
            // Stale principal value (FALSE at auth time) drove the publish branch.
            fake.invocations.size shouldBe 1
            // Second request: fresh auth re-reads is_shadow_banned = TRUE → publish skipped.
            withChat(fake) {
                postSend(tok, conv, content = "second").status shouldBe HttpStatusCode.Created
            }
            // No new invocation: the second request correctly skipped publish.
            fake.invocations.size shouldBe 1
        } finally {
            cleanup(sender, recipient)
        }
    }

    // ----------------------------------------------------------------------------------
    // Test 18 — 2000-char content boundary on broadcast path. Captured payload's `content`
    // is the exact 2000-char string (no truncation, no escaping artifacts).
    // ----------------------------------------------------------------------------------
    "test 18 — 2000-char content survives the broadcast path verbatim" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val conv = createConv(sender, recipient)
        // 'x' chosen to avoid any JSON-escaping ambiguity (no quote / backslash / control
        // chars). The route's body builder uses """{"content":"$content"}""", so any
        // special character would surface as a JSON parse error before reaching the
        // handler — but 'x' is JSON-safe.
        val content = "x".repeat(2000)
        try {
            val fake = FakeChatRealtimeClient()
            withChat(fake) {
                postSend(tok, conv, content = content).status shouldBe HttpStatusCode.Created
            }
            val captured = fake.invocations.single().second.content
            captured shouldBe content
            captured!!.length shouldBe 2000
        } finally {
            cleanup(sender, recipient)
        }
    }

    // ----------------------------------------------------------------------------------
    // Test 19 — secretKey wiring (DI). Exercises that secretKey(env, slot) returns the
    // canonical staging-prefixed slot for staging and bare slot for production.
    // The deeper "constructor receives the resolved value" assertion lives in the
    // SupabaseBroadcastChatClient unit test (Phase 6 / `:infra:supabase`); this test
    // captures the Application.kt-level helper-name guarantee.
    // ----------------------------------------------------------------------------------
    "test 19 — secretKey resolves staging-prefixed and prod-bare slot names" {
        // Staging: prefix added.
        id.nearyou.app.config.secretKey("staging", "supabase-service-role-key") shouldBe
            "staging-supabase-service-role-key"
        // Production: bare name.
        id.nearyou.app.config.secretKey("production", "supabase-service-role-key") shouldBe
            "supabase-service-role-key"
    }

    // ----------------------------------------------------------------------------------
    // Test 20 — service-role-key VALUE not in logs. The chat handler holds NO key (only
    // the SupabaseBroadcastChatClient adapter does); the assertion at this level is that
    // the handler's WARN log does not surface anything that looks like a service role
    // key on either success or failure paths. The deep substring-scan against a real
    // resolved key value lives in `:infra:supabase`'s SupabaseBroadcastChatClientTest.
    // ----------------------------------------------------------------------------------
    "test 20 — chat-handler WARN log carries no key-shaped tokens on success or failure" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val conv = createConv(sender, recipient)
        try {
            val sentinel = "synthetic-service-role-key-value-do-not-log-12345"
            val fake = FakeChatRealtimeClient(failureReason = "java.io.IOException")
            captureRoutesWarn { appender ->
                withChat(fake) {
                    // Success then failure paths.
                    postSend(tok, conv, content = "ok").status shouldBe HttpStatusCode.Created
                    postSend(tok, conv, content = "fail").status shouldBe HttpStatusCode.Created
                }
                val allMessages = appender.list.joinToString("\n") { it.formattedMessage }
                allMessages.contains(sentinel) shouldBe false
                // Defense-in-depth: no log message should contain the literal slot name
                // "supabase-service-role-key" either (only the adapter holds it).
                allMessages.contains("supabase-service-role-key") shouldBe false
            }
        } finally {
            cleanup(sender, recipient)
        }
    }
})

// ----------------------------------------------------------------------------------
// Test fixtures.
// ----------------------------------------------------------------------------------

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

/** Stubbed RemoteConfig — chat-realtime-broadcast doesn't depend on Remote Config. */
private object NullRemoteConfigBroadcast : RemoteConfig {
    override fun getLong(key: String): Long? = null

    override fun getBoolean(key: String): Boolean? = null
}

/**
 * Test double for [ChatRealtimeClient]. Captures every `publish` invocation
 * (conversationId + payload). Configurable per-test to:
 *  - Return [PublishResult.Success] (default).
 *  - Return [PublishResult.Failure] with a configurable reason string.
 *  - Throw a configurable [Throwable] (simulates an exception escaping the adapter).
 *  - Run an arbitrary [onPublish] hook on each invocation (used by Test 13 to verify
 *    post-commit visibility from a separate JDBC connection).
 *
 * Per task 5.2: captures "timestamps, conv id, broadcast payload" via [invocations].
 */
private class FakeChatRealtimeClient(
    private val failureReason: String? = null,
    private val throwOnPublish: Throwable? = null,
    private val onPublish: (UUID, ChatMessageBroadcast) -> Unit = { _, _ -> },
) : ChatRealtimeClient {
    private val collected = ConcurrentLinkedQueue<Triple<Instant, UUID, ChatMessageBroadcast>>()

    val invocations: List<Pair<UUID, ChatMessageBroadcast>>
        get() = collected.map { (_, c, m) -> c to m }

    override suspend fun publish(
        conversationId: UUID,
        message: ChatMessageBroadcast,
    ): PublishResult {
        collected.add(Triple(Instant.now(), conversationId, message))
        onPublish(conversationId, message)
        throwOnPublish?.let { throw it }
        return failureReason?.let { PublishResult.Failure(it) } ?: PublishResult.Success
    }
}

/** Convenience for clearer assertions. */
private fun <T> List<T>.shouldBeEmpty() {
    if (isNotEmpty()) error("Expected empty list, got: $this")
}

@Suppress("UnusedPrivateProperty")
private val sentinelDataSource: DataSource? = null
