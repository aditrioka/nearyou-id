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
import id.nearyou.app.core.domain.ratelimit.InMemoryRateLimiter
import id.nearyou.app.core.domain.ratelimit.RateLimiter
import id.nearyou.app.core.domain.ratelimit.computeTTLToNextReset
import id.nearyou.app.guard.ContentLengthGuard
import id.nearyou.app.infra.repo.JdbcUserRepository
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.longs.shouldBeBetween
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.sql.Date
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import ch.qos.logback.classic.Logger as LogbackLogger
import io.kotest.matchers.string.shouldContain as shouldContainString
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientCN

/**
 * Integration tests for the V12-era chat send rate limit (`chat-rate-limit` change §
 * chat-conversations spec § "Integration test coverage — chat send rate limit"). Covers
 * the 34 scenarios listed in that requirement verbatim.
 *
 * Test infrastructure choices (rationale):
 *  - **In-memory limiter, not real Redis.** The Lua sliding-window correctness path is
 *    exercised by `:infra:redis`'s `RedisRateLimiterIntegrationTest`. This class is the
 *    HTTP-level + service-level gate testing what `ChatService` does *with* the limiter.
 *    An [InMemoryRateLimiter] (V9 sliding-window algorithm) is byte-equivalent and
 *    drastically faster + deterministic for `Retry-After` math. Mirrors the precedent
 *    from `LikeRateLimitTest` and `ReplyRateLimitTest`.
 *  - **Spy [RateLimiter]** ([SpyRateLimiterChat]) wrapping the in-memory impl, capturing
 *    every `tryAcquire` + `releaseMostRecent` (key, capacity, ttl) tuple. Lets scenarios
 *    21 / 23 / 28 / 29 / 34 assert key shape, call ordering, per-user offset, and the
 *    `releaseMostRecent`-never-invoked invariant.
 *  - **Frozen clock** injected into [InMemoryRateLimiter] for scenario 22 (WIB rollover).
 *    The clock is a mutable [AtomicReference] so tests can advance it across midnight WIB
 *    plus the per-user offset.
 *  - **Pinned UUIDs for scenario 23** ([USER_ONE_UUID] / [USER_TWO_UUID]) with a
 *    fixture-load-time defensive assertion that their `hashCode() % 3600` values differ
 *    by at least 1 second; if Kotlin's `UUID.hashCode()` semantics ever shift the assert
 *    catches it on the first test run rather than producing a flake.
 *
 * Tagged `database` — depends on dev Postgres for the `users` / `conversations` /
 * `conversation_participants` / `chat_messages` / `user_blocks` schema (V15) plus the V11
 * `subscription_status` and V11 `is_shadow_banned` columns.
 */
@Tags("database")
class ChatSendRateLimitTest : StringSpec({

    val dataSource = hikari()
    val keys = RsaKeyLoader(TestKeys.freshEncodedPemPrivateKey(), kid = "test-chat-rl")
    val jwtIssuer = JwtIssuer(keys)
    val users = JdbcUserRepository(dataSource)
    val repository = ChatRepository(dataSource)
    val contentGuard = ContentLengthGuard(mapOf(CHAT_CONTENT_KEY to 2000))

    afterSpec { dataSource.close() }

    fun seedUser(
        subscriptionStatus: String = "free",
        shadowBanned: Boolean = false,
        idOverride: UUID? = null,
    ): Pair<UUID, String> {
        val id = idOverride ?: UUID.randomUUID()
        // takeLast(8) — pinned UUIDs share their first 8 chars (`00000000`) so deriving
        // from the prefix would collide on the username + invite_code_prefix uniques.
        // The suffix differs: `00000001` vs `00000002`. Random UUIDs still differ in
        // their tail with overwhelming probability so this works for the unpinned case
        // too.
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
                ps.setString(2, "cr_$short")
                ps.setString(3, "Chat RL Tester")
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

    fun countMessagesFromSender(senderId: UUID): Int {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM chat_messages WHERE sender_id = ?",
            ).use { ps ->
                ps.setObject(1, senderId)
                ps.executeQuery().use { rs ->
                    rs.next()
                    return rs.getInt(1)
                }
            }
        }
    }

    fun chatRowEmbedColumns(messageId: UUID): Triple<UUID?, String?, UUID?> {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT embedded_post_id, embedded_post_snapshot, embedded_post_edit_id FROM chat_messages WHERE id = ?",
            ).use { ps ->
                ps.setObject(1, messageId)
                ps.executeQuery().use { rs ->
                    check(rs.next())
                    val embId = rs.getObject("embedded_post_id") as? UUID
                    val snap = rs.getString("embedded_post_snapshot")
                    val embEditId = rs.getObject("embedded_post_edit_id") as? UUID
                    return Triple(embId, snap, embEditId)
                }
            }
        }
    }

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
        rateLimiter: RateLimiter,
        remoteConfig: RemoteConfig = NullRemoteConfigChat,
        repo: ChatRepository = repository,
        clock: () -> Instant = Instant::now,
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) {
        val service =
            ChatService(
                repository = repo,
                rateLimiter = rateLimiter,
                remoteConfig = remoteConfig,
                clock = clock,
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
                chatRoutes(service, contentGuard)
            }
            block()
        }
    }

    suspend fun ApplicationTestBuilder.postSend(
        token: String,
        conversationId: UUID,
        content: String = "halo",
        rawBody: String? = null,
    ) = createClient { install(ClientCN) { json() } }
        .post("/api/v1/chat/$conversationId/messages") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(rawBody ?: """{"content":"$content"}""")
        }

    suspend fun ApplicationTestBuilder.getMessages(
        token: String,
        conversationId: UUID,
    ) = createClient { install(ClientCN) { json() } }
        .get("/api/v1/chat/$conversationId/messages") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

    suspend fun ApplicationTestBuilder.getConversations(token: String) =
        createClient { install(ClientCN) { json() } }
            .get("/api/v1/conversations") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }

    suspend fun ApplicationTestBuilder.postConversation(
        token: String,
        recipientId: UUID,
    ) = createClient { install(ClientCN) { json() } }
        .post("/api/v1/conversations") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"recipient_user_id":"$recipientId"}""")
        }

    fun rateLimitedCode(body: String): String =
        Json.parseToJsonElement(body)
            .jsonObject["error"]!!.jsonObject["code"]!!
            .jsonPrimitive.content

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

    fun ListAppender<ILoggingEvent>.embedIgnoredCount(): Int =
        list.count { it.level == Level.WARN && it.formattedMessage.contains("event=chat_send_embedded_field_ignored") }

    // ----------------------------------------------------------------------------------
    // Scenario 1 — 50 chat sends in a day succeed for Free user.
    // ----------------------------------------------------------------------------------
    "scenario 1 — 50 chat sends in a day succeed for Free user" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val conv = createConv(sender, recipient)
        try {
            val limiter = InMemoryRateLimiter()
            withChat(rateLimiter = limiter) {
                repeat(50) { i ->
                    postSend(tok, conv, content = "msg $i").status shouldBe HttpStatusCode.Created
                }
            }
            countMessagesFromSender(sender) shouldBe 50
        } finally {
            cleanup(sender, recipient)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 2 — 51st rate-limited; 429 + Retry-After + no row + last_message_at unchanged.
    // ----------------------------------------------------------------------------------
    "scenario 2 — 51st rate-limited; 429 + Retry-After + no row + last_message_at unchanged" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val conv = createConv(sender, recipient)
        try {
            val limiter = InMemoryRateLimiter()
            withChat(rateLimiter = limiter) {
                repeat(50) { postSend(tok, conv, content = "m$it").status shouldBe HttpStatusCode.Created }
                val lastBefore = lastMessageAtFor(conv)
                val rl = postSend(tok, conv, content = "x")
                rl.status shouldBe HttpStatusCode.TooManyRequests
                rateLimitedCode(rl.bodyAsText()) shouldBe "rate_limited"
                rl.headers[HttpHeaders.RetryAfter]!!.toLong() shouldNotBe 0L
                lastMessageAtFor(conv) shouldBe lastBefore
            }
            countMessagesFromSender(sender) shouldBe 50
        } finally {
            cleanup(sender, recipient)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 3 — Retry-After ±5s of expected (relative to now).
    // ----------------------------------------------------------------------------------
    "scenario 3 — Retry-After value within ±5s of expected" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val conv = createConv(sender, recipient)
        try {
            val limiter = InMemoryRateLimiter()
            withChat(rateLimiter = limiter) {
                repeat(50) { postSend(tok, conv).status shouldBe HttpStatusCode.Created }
                val now = Instant.now()
                val rl = postSend(tok, conv)
                rl.status shouldBe HttpStatusCode.TooManyRequests
                val retry = rl.headers[HttpHeaders.RetryAfter]!!.toLong()
                val expected = computeTTLToNextReset(sender, now).seconds
                retry.shouldBeBetween(expected - 5L, expected + 5L)
            }
        } finally {
            cleanup(sender, recipient)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 4 — Premium (premium_active) skips the daily limiter — 100 sends succeed.
    // ----------------------------------------------------------------------------------
    "scenario 4 — Premium (premium_active) skips the daily limiter — 100 sends succeed" {
        val (sender, tok) = seedUser(subscriptionStatus = "premium_active")
        val (recipient, _) = seedUser()
        val conv = createConv(sender, recipient)
        try {
            val spy = SpyRateLimiterChat(InMemoryRateLimiter())
            withChat(rateLimiter = spy) {
                repeat(100) { postSend(tok, conv, content = "p$it").status shouldBe HttpStatusCode.Created }
            }
            // Daily key was never consulted — Premium skips entirely (NOT consult-and-skip).
            spy.acquireKeys() shouldBe emptyList()
        } finally {
            cleanup(sender, recipient)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 5 — Premium billing retry status also skips the daily limiter (75 succeed).
    // ----------------------------------------------------------------------------------
    "scenario 5 — Premium billing retry status also skips the daily limiter (75 succeed)" {
        val (sender, tok) = seedUser(subscriptionStatus = "premium_billing_retry")
        val (recipient, _) = seedUser()
        val conv = createConv(sender, recipient)
        try {
            val spy = SpyRateLimiterChat(InMemoryRateLimiter())
            withChat(rateLimiter = spy) {
                repeat(75) { postSend(tok, conv, content = "g$it").status shouldBe HttpStatusCode.Created }
            }
            spy.acquireKeys() shouldBe emptyList()
        } finally {
            cleanup(sender, recipient)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 6 — premium_chat_send_cap_override = 60 raises the cap (61st rejected).
    // ----------------------------------------------------------------------------------
    "scenario 6 — premium_chat_send_cap_override = 60 raises the cap (61st rejected after 60th)" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val conv = createConv(sender, recipient)
        try {
            val limiter = InMemoryRateLimiter()
            val rc = FixedRemoteConfigChat(mapOf(ChatService.PREMIUM_CHAT_SEND_CAP_OVERRIDE_KEY to 60L))
            withChat(rateLimiter = limiter, remoteConfig = rc) {
                repeat(60) { postSend(tok, conv, content = "o$it").status shouldBe HttpStatusCode.Created }
                postSend(tok, conv).status shouldBe HttpStatusCode.TooManyRequests
            }
            countMessagesFromSender(sender) shouldBe 60
        } finally {
            cleanup(sender, recipient)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 7 — override = 10 lowers the cap mid-day; user previously at 15 rejected at 16.
    // ----------------------------------------------------------------------------------
    "scenario 7 — override = 10 lowers the cap mid-day; user previously at 15 rejected at 16" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val conv = createConv(sender, recipient)
        try {
            val limiter = InMemoryRateLimiter()
            withChat(rateLimiter = limiter, remoteConfig = NullRemoteConfigChat) {
                repeat(15) { postSend(tok, conv, content = "p$it").status shouldBe HttpStatusCode.Created }
            }
            // Override drops to 10. User is over the new cap → next request rejected.
            val rc = FixedRemoteConfigChat(mapOf(ChatService.PREMIUM_CHAT_SEND_CAP_OVERRIDE_KEY to 10L))
            withChat(rateLimiter = limiter, remoteConfig = rc) {
                postSend(tok, conv).status shouldBe HttpStatusCode.TooManyRequests
            }
        } finally {
            cleanup(sender, recipient)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 8 — override = 0 falls back to default 50.
    // ----------------------------------------------------------------------------------
    "scenario 8 — override = 0 falls back to default 50" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val conv = createConv(sender, recipient)
        try {
            val limiter = InMemoryRateLimiter()
            val rc = FixedRemoteConfigChat(mapOf(ChatService.PREMIUM_CHAT_SEND_CAP_OVERRIDE_KEY to 0L))
            withChat(rateLimiter = limiter, remoteConfig = rc) {
                repeat(50) { postSend(tok, conv, content = "z$it").status shouldBe HttpStatusCode.Created }
                postSend(tok, conv).status shouldBe HttpStatusCode.TooManyRequests
            }
        } finally {
            cleanup(sender, recipient)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 9 — override = "fifty" malformed → default 50 (RemoteConfig.getLong returns
    // null for non-integer strings per the contract). Status-only assertion.
    // ----------------------------------------------------------------------------------
    "scenario 9 — override = malformed string falls back to default 50 (status only)" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val conv = createConv(sender, recipient)
        try {
            val limiter = InMemoryRateLimiter()
            // RemoteConfig.getLong contract: malformed string surfaces as null.
            withChat(rateLimiter = limiter, remoteConfig = NullRemoteConfigChat) {
                repeat(50) { postSend(tok, conv, content = "m$it").status shouldBe HttpStatusCode.Created }
                postSend(tok, conv).status shouldBe HttpStatusCode.TooManyRequests
            }
        } finally {
            cleanup(sender, recipient)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 10 — Remote Config network failure (SDK throws) falls back to default 50.
    // ----------------------------------------------------------------------------------
    "scenario 10 — Remote Config network failure falls back to default 50; no 5xx" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val conv = createConv(sender, recipient)
        try {
            val limiter = InMemoryRateLimiter()
            val rc = ThrowingRemoteConfigChat(java.io.IOException("simulated network failure"))
            withChat(rateLimiter = limiter, remoteConfig = rc) {
                repeat(50) { postSend(tok, conv, content = "t$it").status shouldBe HttpStatusCode.Created }
                postSend(tok, conv).status shouldBe HttpStatusCode.TooManyRequests
            }
        } finally {
            cleanup(sender, recipient)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 11 — 404 conversation_not_found consumes a slot (does NOT release).
    // ----------------------------------------------------------------------------------
    "scenario 11 — 404 conversation_not_found consumes a slot (does NOT release)" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val conv = createConv(sender, recipient)
        val ghost = UUID.randomUUID()
        try {
            val spy = SpyRateLimiterChat(InMemoryRateLimiter())
            withChat(rateLimiter = spy) {
                repeat(5) { postSend(tok, conv, content = "p$it").status shouldBe HttpStatusCode.Created }
                postSend(tok, ghost).status shouldBe HttpStatusCode.NotFound
            }
            // 6 acquires (5 success + 1 ghost), 0 releases. Bucket size is 6: 44 more will succeed; 51st = 429.
            withChat(rateLimiter = spy.delegate) {
                repeat(44) { postSend(tok, conv, content = "q$it").status shouldBe HttpStatusCode.Created }
                postSend(tok, conv).status shouldBe HttpStatusCode.TooManyRequests
            }
            spy.releaseKeys() shouldBe emptyList()
        } finally {
            cleanup(sender, recipient)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 12 — 403 not_a_participant consumes a slot (does NOT release).
    // ----------------------------------------------------------------------------------
    "scenario 12 — 403 not_a_participant post-limiter consumes a slot" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val (other1, _) = seedUser()
        val (other2, _) = seedUser()
        val ownConv = createConv(sender, recipient)
        val foreignConv = createConv(other1, other2) // sender is NOT a participant
        try {
            val spy = SpyRateLimiterChat(InMemoryRateLimiter())
            withChat(rateLimiter = spy) {
                repeat(5) { postSend(tok, ownConv, content = "x$it").status shouldBe HttpStatusCode.Created }
                postSend(tok, foreignConv).status shouldBe HttpStatusCode.Forbidden
            }
            // 6 acquires (5 success + 1 forbidden), 0 releases.
            withChat(rateLimiter = spy.delegate) {
                repeat(44) { postSend(tok, ownConv, content = "y$it").status shouldBe HttpStatusCode.Created }
                postSend(tok, ownConv).status shouldBe HttpStatusCode.TooManyRequests
            }
            spy.releaseKeys() shouldBe emptyList()
        } finally {
            cleanup(sender, recipient, other1, other2)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 13 — 403 blocked post-limiter consumes a slot.
    // ----------------------------------------------------------------------------------
    "scenario 13 — 403 blocked post-limiter consumes a slot" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val (blocker, _) = seedUser()
        val openConv = createConv(sender, recipient)
        val blockedConv = createConv(sender, blocker)
        // After the conversation exists, recipient blocks sender (blocks the SEND only,
        // history visibility is preserved per chat-foundation).
        seedBlock(blocker, sender)
        try {
            val spy = SpyRateLimiterChat(InMemoryRateLimiter())
            withChat(rateLimiter = spy) {
                repeat(5) { postSend(tok, openConv, content = "h$it").status shouldBe HttpStatusCode.Created }
                val rl = postSend(tok, blockedConv, content = "blocked attempt")
                rl.status shouldBe HttpStatusCode.Forbidden
                rl.bodyAsText() shouldContainString "Tidak dapat mengirim pesan"
            }
            withChat(rateLimiter = spy.delegate) {
                repeat(44) { postSend(tok, openConv, content = "k$it").status shouldBe HttpStatusCode.Created }
                postSend(tok, openConv).status shouldBe HttpStatusCode.TooManyRequests
            }
            spy.releaseKeys() shouldBe emptyList()
        } finally {
            cleanup(sender, recipient, blocker)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 14 — 400 invalid_request on EMPTY content consumes a slot.
    // ----------------------------------------------------------------------------------
    "scenario 14 — 400 invalid_request on empty content consumes a slot" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val conv = createConv(sender, recipient)
        try {
            val spy = SpyRateLimiterChat(InMemoryRateLimiter())
            withChat(rateLimiter = spy) {
                repeat(5) { postSend(tok, conv, content = "p$it").status shouldBe HttpStatusCode.Created }
                postSend(tok, conv, content = "").status shouldBe HttpStatusCode.BadRequest
            }
            withChat(rateLimiter = spy.delegate) {
                repeat(44) { postSend(tok, conv, content = "q$it").status shouldBe HttpStatusCode.Created }
                postSend(tok, conv).status shouldBe HttpStatusCode.TooManyRequests
            }
            spy.releaseKeys() shouldBe emptyList()
        } finally {
            cleanup(sender, recipient)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 15 — 400 invalid_request on WHITESPACE-ONLY content consumes a slot.
    // ----------------------------------------------------------------------------------
    "scenario 15 — 400 invalid_request on whitespace-only content consumes a slot" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val conv = createConv(sender, recipient)
        try {
            val spy = SpyRateLimiterChat(InMemoryRateLimiter())
            withChat(rateLimiter = spy) {
                repeat(5) { postSend(tok, conv, content = "p$it").status shouldBe HttpStatusCode.Created }
                postSend(tok, conv, content = "   ").status shouldBe HttpStatusCode.BadRequest
            }
            withChat(rateLimiter = spy.delegate) {
                repeat(44) { postSend(tok, conv, content = "q$it").status shouldBe HttpStatusCode.Created }
                postSend(tok, conv).status shouldBe HttpStatusCode.TooManyRequests
            }
            spy.releaseKeys() shouldBe emptyList()
        } finally {
            cleanup(sender, recipient)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 16 — 400 invalid_request on 2001-CHARACTER content consumes a slot.
    // ----------------------------------------------------------------------------------
    "scenario 16 — 400 invalid_request on 2001-char content consumes a slot" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val conv = createConv(sender, recipient)
        val oversize = "x".repeat(2001)
        try {
            val spy = SpyRateLimiterChat(InMemoryRateLimiter())
            withChat(rateLimiter = spy) {
                repeat(5) { postSend(tok, conv, content = "p$it").status shouldBe HttpStatusCode.Created }
                postSend(tok, conv, content = oversize).status shouldBe HttpStatusCode.BadRequest
            }
            withChat(rateLimiter = spy.delegate) {
                repeat(44) { postSend(tok, conv, content = "q$it").status shouldBe HttpStatusCode.Created }
                postSend(tok, conv).status shouldBe HttpStatusCode.TooManyRequests
            }
            spy.releaseKeys() shouldBe emptyList()
        } finally {
            cleanup(sender, recipient)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 17 — Daily limit hit short-circuits before participant check (429 not 403).
    // ----------------------------------------------------------------------------------
    "scenario 17 — at slot 51, POST to non-participant conversation returns 429 (not 403)" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val (other1, _) = seedUser()
        val (other2, _) = seedUser()
        val ownConv = createConv(sender, recipient)
        val foreignConv = createConv(other1, other2)
        try {
            val limiter = InMemoryRateLimiter()
            withChat(rateLimiter = limiter) {
                repeat(50) { postSend(tok, ownConv, content = "p$it").status shouldBe HttpStatusCode.Created }
                // At slot 51 against a foreign conversation — limiter rejects FIRST.
                val rl = postSend(tok, foreignConv)
                rl.status shouldBe HttpStatusCode.TooManyRequests
                rateLimitedCode(rl.bodyAsText()) shouldBe "rate_limited"
            }
        } finally {
            cleanup(sender, recipient, other1, other2)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 18 — Daily limit hit short-circuits before block check (429 not 403).
    // ----------------------------------------------------------------------------------
    "scenario 18 — at slot 51, POST to blocked conversation returns 429 (not 403)" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val (blocker, _) = seedUser()
        val openConv = createConv(sender, recipient)
        val blockedConv = createConv(sender, blocker)
        seedBlock(blocker, sender)
        try {
            val limiter = InMemoryRateLimiter()
            withChat(rateLimiter = limiter) {
                repeat(50) { postSend(tok, openConv, content = "h$it").status shouldBe HttpStatusCode.Created }
                val rl = postSend(tok, blockedConv)
                rl.status shouldBe HttpStatusCode.TooManyRequests
            }
        } finally {
            cleanup(sender, recipient, blocker)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 19 — Daily limit hit short-circuits before content-length guard (429 not 400).
    // ----------------------------------------------------------------------------------
    "scenario 19 — at slot 51, POST 2001-char content returns 429 (not 400)" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val conv = createConv(sender, recipient)
        val oversize = "x".repeat(2001)
        try {
            val limiter = InMemoryRateLimiter()
            withChat(rateLimiter = limiter) {
                repeat(50) { postSend(tok, conv, content = "p$it").status shouldBe HttpStatusCode.Created }
                val rl = postSend(tok, conv, content = oversize)
                rl.status shouldBe HttpStatusCode.TooManyRequests
                rateLimitedCode(rl.bodyAsText()) shouldBe "rate_limited"
            }
        } finally {
            cleanup(sender, recipient)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 20 — Daily limit hit short-circuits before unknown-conversation 404.
    // ----------------------------------------------------------------------------------
    "scenario 20 — at slot 51, POST to non-existent conversation returns 429 (not 404)" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val conv = createConv(sender, recipient)
        val ghost = UUID.randomUUID()
        try {
            val limiter = InMemoryRateLimiter()
            withChat(rateLimiter = limiter) {
                repeat(50) { postSend(tok, conv, content = "p$it").status shouldBe HttpStatusCode.Created }
                postSend(tok, ghost).status shouldBe HttpStatusCode.TooManyRequests
            }
        } finally {
            cleanup(sender, recipient)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 21 — Hash-tag key shape: {scope:rate_chat_send_day}:{user:<uuid>}.
    // ----------------------------------------------------------------------------------
    "scenario 21 — hash-tag key shape: {scope:rate_chat_send_day}:{user:<uuid>}" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val conv = createConv(sender, recipient)
        try {
            val spy = SpyRateLimiterChat(InMemoryRateLimiter())
            withChat(rateLimiter = spy) {
                postSend(tok, conv).status shouldBe HttpStatusCode.Created
            }
            spy.acquireKeys() shouldContain "{scope:rate_chat_send_day}:{user:$sender}"
        } finally {
            cleanup(sender, recipient)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 22 — WIB rollover (single user): at the per-user reset moment the cap restores.
    // ----------------------------------------------------------------------------------
    "scenario 22 — WIB rollover (single user): at the per-user reset moment the cap restores" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val conv = createConv(sender, recipient)
        try {
            val day1Wib = ZonedDateTime.of(2026, 4, 25, 12, 0, 0, 0, ZoneId.of("Asia/Jakarta"))
            val clock = AtomicReference(day1Wib.toInstant())
            val limiter = InMemoryRateLimiter(clock = { clock.get() })
            withChat(rateLimiter = limiter, clock = { clock.get() }) {
                repeat(50) { postSend(tok, conv, content = "d$it").status shouldBe HttpStatusCode.Created }
                postSend(tok, conv).status shouldBe HttpStatusCode.TooManyRequests
            }
            // Advance 24h+1s past the OLDEST counted entry so the sliding-window prune
            // ages out all 50 day-1 entries.
            clock.set(day1Wib.toInstant().plus(Duration.ofHours(24)).plusSeconds(1))
            withChat(rateLimiter = limiter, clock = { clock.get() }) {
                repeat(5) { postSend(tok, conv, content = "n$it").status shouldBe HttpStatusCode.Created }
            }
        } finally {
            cleanup(sender, recipient)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 23 — WIB rollover (per-user offset is genuinely per-user): two distinct
    // synthetic users with different `hashCode() % 3600` offsets exhaust their caps at
    // the same wall-clock moment; their `Retry-After` values (captured via SpyRateLimiter)
    // differ by ≥ 1 second — proving the offset is per-user, not a global constant.
    // ----------------------------------------------------------------------------------
    "scenario 23 — per-user WIB offset: two users get different Retry-After ≥ 1s apart" {
        // Defensive fixture-load-time guard: if Kotlin's UUID.hashCode() semantics ever
        // shift across JVM versions, catch the silent collision on the first test run.
        val offset1 = (USER_ONE_UUID.hashCode().let { if (it == Int.MIN_VALUE) 0 else Math.abs(it) }) % 3600
        val offset2 = (USER_TWO_UUID.hashCode().let { if (it == Int.MIN_VALUE) 0 else Math.abs(it) }) % 3600
        check(offset1 != offset2) {
            "Pinned UUIDs USER_ONE_UUID and USER_TWO_UUID must hash to different offsets % 3600 " +
                "(currently both = $offset1). Choose new UUIDs whose hashCode() % 3600 differ."
        }
        // Defensive pre-cleanup: a prior failed run may have left rows under the pinned
        // UUIDs (the seed-throws-mid-test path skips the try/finally cleanup). Self-heal.
        cleanup(USER_ONE_UUID, USER_TWO_UUID)
        val recipientRef = AtomicReference<UUID>()
        try {
            val (u1, t1) = seedUser(idOverride = USER_ONE_UUID)
            val (u2, t2) = seedUser(idOverride = USER_TWO_UUID)
            val (recipient, _) = seedUser()
            recipientRef.set(recipient)
            val conv1 = createConv(u1, recipient)
            val conv2 = createConv(u2, recipient)
            // Same wall-clock moment for both users via the frozen-clock pattern.
            val frozen = AtomicReference(Instant.now())
            val limiter = InMemoryRateLimiter(clock = { frozen.get() })
            val spy = SpyRateLimiterChat(limiter)
            withChat(rateLimiter = spy, clock = { frozen.get() }) {
                repeat(50) { postSend(t1, conv1, content = "u1-$it").status shouldBe HttpStatusCode.Created }
                repeat(50) { postSend(t2, conv2, content = "u2-$it").status shouldBe HttpStatusCode.Created }
                val rl1 = postSend(t1, conv1)
                val rl2 = postSend(t2, conv2)
                rl1.status shouldBe HttpStatusCode.TooManyRequests
                rl2.status shouldBe HttpStatusCode.TooManyRequests
                val ra1 = rl1.headers[HttpHeaders.RetryAfter]!!.toLong()
                val ra2 = rl2.headers[HttpHeaders.RetryAfter]!!.toLong()
                Math.abs(ra1 - ra2).shouldBeGreaterThanOrEqual(1L)
            }
        } finally {
            recipientRef.get()?.let { cleanup(USER_ONE_UUID, USER_TWO_UUID, it) }
                ?: cleanup(USER_ONE_UUID, USER_TWO_UUID)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 24 — Admin redaction does NOT release a daily slot.
    // POST 5 → admin-style direct UPDATE on one row → POST 45 more → 51st = 429.
    // ----------------------------------------------------------------------------------
    "scenario 24 — admin redaction does NOT release a daily slot" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val (adminUid, _) = seedUser() // stand-in admin user (FK on `users` is satisfied)
        val conv = createConv(sender, recipient)
        try {
            val limiter = InMemoryRateLimiter()
            val firstId = AtomicReference<UUID>()
            withChat(rateLimiter = limiter) {
                // Step 1: 5 successful sends.
                repeat(5) {
                    val resp = postSend(tok, conv, content = "first-$it")
                    resp.status shouldBe HttpStatusCode.Created
                    if (it == 0) firstId.set(messageId(resp.bodyAsText()))
                }
            }
            // Step 2: admin-style redaction (chat-foundation atomicity CHECK satisfied —
            // both `redacted_at` and `redacted_by` set together; reason optional).
            dataSource.connection.use { conn ->
                conn.prepareStatement(
                    "UPDATE chat_messages SET redacted_at = NOW(), redacted_by = ? WHERE id = ?",
                ).use { ps ->
                    ps.setObject(1, adminUid)
                    ps.setObject(2, firstId.get())
                    ps.executeUpdate()
                }
            }
            // Step 3: POST 45 more (slots 6-50). All succeed.
            withChat(rateLimiter = limiter) {
                repeat(45) { postSend(tok, conv, content = "next-$it").status shouldBe HttpStatusCode.Created }
                // Step 4: 51st rejected. Slot stayed consumed across the redaction.
                postSend(tok, conv).status shouldBe HttpStatusCode.TooManyRequests
            }
        } finally {
            cleanup(sender, recipient, adminUid)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 25 — GET /messages unaffected by daily cap; no limiter consultation.
    // ----------------------------------------------------------------------------------
    "scenario 25 — GET /messages unaffected by the daily cap; caller at 51/50 still gets 200" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val conv = createConv(sender, recipient)
        try {
            val limiter = InMemoryRateLimiter()
            withChat(rateLimiter = limiter) {
                repeat(50) { postSend(tok, conv, content = "p$it").status shouldBe HttpStatusCode.Created }
            }
            withChat(rateLimiter = limiter) {
                postSend(tok, conv).status shouldBe HttpStatusCode.TooManyRequests
            }
            val spy = SpyRateLimiterChat(limiter)
            withChat(rateLimiter = spy) {
                getMessages(tok, conv).status shouldBe HttpStatusCode.OK
            }
            spy.acquireKeys() shouldBe emptyList()
            spy.releaseKeys() shouldBe emptyList()
        } finally {
            cleanup(sender, recipient)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 26 — GET /conversations unaffected by daily cap; no limiter consultation.
    // ----------------------------------------------------------------------------------
    "scenario 26 — GET /conversations unaffected by the daily cap; caller at 51/50 still gets 200" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val conv = createConv(sender, recipient)
        try {
            val limiter = InMemoryRateLimiter()
            withChat(rateLimiter = limiter) {
                repeat(50) { postSend(tok, conv, content = "p$it").status shouldBe HttpStatusCode.Created }
            }
            withChat(rateLimiter = limiter) {
                postSend(tok, conv).status shouldBe HttpStatusCode.TooManyRequests
            }
            val spy = SpyRateLimiterChat(limiter)
            withChat(rateLimiter = spy) {
                getConversations(tok).status shouldBe HttpStatusCode.OK
            }
            spy.acquireKeys() shouldBe emptyList()
            spy.releaseKeys() shouldBe emptyList()
        } finally {
            cleanup(sender, recipient)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 27 — POST /conversations unaffected by daily cap; no limiter consultation.
    // ----------------------------------------------------------------------------------
    "scenario 27 — POST /conversations unaffected by the daily cap; caller at 51/50 still gets 201" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val (newRecipient, _) = seedUser()
        val conv = createConv(sender, recipient)
        try {
            val limiter = InMemoryRateLimiter()
            withChat(rateLimiter = limiter) {
                repeat(50) { postSend(tok, conv, content = "p$it").status shouldBe HttpStatusCode.Created }
            }
            withChat(rateLimiter = limiter) {
                postSend(tok, conv).status shouldBe HttpStatusCode.TooManyRequests
            }
            val spy = SpyRateLimiterChat(limiter)
            withChat(rateLimiter = spy) {
                postConversation(tok, newRecipient).status shouldBe HttpStatusCode.Created
            }
            spy.acquireKeys() shouldBe emptyList()
            spy.releaseKeys() shouldBe emptyList()
        } finally {
            cleanup(sender, recipient, newRecipient)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 28 — Tier read from auth-time `viewer` principal: SpyRateLimiter confirms
    // tryAcquire was invoked AND HTTP 201 returned (combined with scenario 17 proves no
    // DB read between auth and limiter).
    // ----------------------------------------------------------------------------------
    "scenario 28 — tier read from auth principal; tryAcquire invoked + HTTP 201" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val conv = createConv(sender, recipient)
        try {
            val spy = SpyRateLimiterChat(InMemoryRateLimiter())
            withChat(rateLimiter = spy) {
                postSend(tok, conv).status shouldBe HttpStatusCode.Created
            }
            spy.acquireKeys() shouldBe listOf("{scope:rate_chat_send_day}:{user:$sender}")
        } finally {
            cleanup(sender, recipient)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 29 — ChatService MUST NEVER call releaseMostRecent (cross-cutting).
    // Exercises success / 404 / 403 not-a-participant / 403 blocked / 400 invalid_request /
    // 51st-rejection paths together — releaseKeys() stays empty.
    // ----------------------------------------------------------------------------------
    "scenario 29 — ChatService MUST NEVER call releaseMostRecent (cross-cutting)" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val (blocker, _) = seedUser()
        val (other1, _) = seedUser()
        val (other2, _) = seedUser()
        val ownConv = createConv(sender, recipient)
        val blockedConv = createConv(sender, blocker)
        val foreignConv = createConv(other1, other2)
        seedBlock(blocker, sender)
        val ghost = UUID.randomUUID()
        try {
            val spy = SpyRateLimiterChat(InMemoryRateLimiter())
            withChat(rateLimiter = spy) {
                // Success path.
                repeat(2) { postSend(tok, ownConv, content = "ok-$it").status shouldBe HttpStatusCode.Created }
                // 404 path.
                postSend(tok, ghost).status shouldBe HttpStatusCode.NotFound
                // 403 not-a-participant path.
                postSend(tok, foreignConv).status shouldBe HttpStatusCode.Forbidden
                // 403 blocked path.
                postSend(tok, blockedConv).status shouldBe HttpStatusCode.Forbidden
                // 400 invalid_request path.
                postSend(tok, ownConv, content = "").status shouldBe HttpStatusCode.BadRequest
            }
            spy.releaseKeys() shouldBe emptyList()
        } finally {
            cleanup(sender, recipient, blocker, other1, other2)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 30 — Null subscriptionStatus on viewer principal treated as Free (defensive).
    // Tested at the service level (UserPrincipal.subscriptionStatus is non-nullable in
    // the auth-jwt path; the service method MUST defensively handle the null case).
    // ----------------------------------------------------------------------------------
    "scenario 30 — null subscriptionStatus on viewer principal treated as Free (defensive)" {
        val (sender, _) = seedUser()
        try {
            val limiter = InMemoryRateLimiter()
            val service =
                ChatService(
                    repository = repository,
                    rateLimiter = limiter,
                    remoteConfig = NullRemoteConfigChat,
                )
            // Run 50 limiter-only calls with subscriptionStatus = null. All should be
            // Allowed (Free path, default cap = 50).
            repeat(50) {
                val outcome = service.tryRateLimit(sender, subscriptionStatus = null)
                outcome shouldBe ChatService.RateLimitOutcome.Allowed
            }
            // 51st should be RateLimited.
            val outcome51 = service.tryRateLimit(sender, subscriptionStatus = null)
            (outcome51 is ChatService.RateLimitOutcome.RateLimited) shouldBe true
        } finally {
            cleanup(sender)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 31 — Transaction rollback does NOT release the slot.
    // Inject a failing repository whose sendMessage() throws AFTER the limiter passes;
    // verify zero chat_messages rows persisted, last_message_at unchanged, AND the daily
    // bucket size still grew by 1 (no releaseMostRecent was called).
    // ----------------------------------------------------------------------------------
    "scenario 31 — transaction rollback does NOT release the slot" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val conv = createConv(sender, recipient)
        try {
            val limiter = InMemoryRateLimiter()
            val failingRepo =
                object : ChatRepository(dataSource) {
                    override fun sendMessage(
                        conversationId: UUID,
                        senderId: UUID,
                        content: String,
                    ): ChatMessageRow {
                        throw RuntimeException("simulated chat-foundation TX rollback")
                    }
                }
            val lastBefore = lastMessageAtFor(conv)
            withChat(rateLimiter = limiter, repo = failingRepo) {
                val resp = postSend(tok, conv)
                resp.status shouldNotBe HttpStatusCode.Created
                resp.status shouldNotBe HttpStatusCode.NoContent
            }
            // No chat_messages row persisted (TX rolled back).
            countMessagesFromSender(sender) shouldBe 0
            // last_message_at unchanged from before the failed send.
            lastMessageAtFor(conv) shouldBe lastBefore
            // Slot was consumed: only 49 more sends should succeed before 51st = 429.
            withChat(rateLimiter = limiter) {
                repeat(49) { postSend(tok, conv, content = "a$it").status shouldBe HttpStatusCode.Created }
                postSend(tok, conv).status shouldBe HttpStatusCode.TooManyRequests
            }
        } finally {
            cleanup(sender, recipient)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 32 — premium_chat_send_cap_override > 10,000 falls back to default 50.
    // ----------------------------------------------------------------------------------
    "scenario 32 — override > 10,000 falls back to default 50" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val conv = createConv(sender, recipient)
        try {
            val limiter = InMemoryRateLimiter()
            // Long.MAX_VALUE is well above the 10,000 threshold → falls back to 50.
            val rc = FixedRemoteConfigChat(mapOf(ChatService.PREMIUM_CHAT_SEND_CAP_OVERRIDE_KEY to Long.MAX_VALUE))
            withChat(rateLimiter = limiter, remoteConfig = rc) {
                repeat(50) { postSend(tok, conv, content = "p$it").status shouldBe HttpStatusCode.Created }
                postSend(tok, conv).status shouldBe HttpStatusCode.TooManyRequests
            }
        } finally {
            cleanup(sender, recipient)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 33 — Shadow-banned sender's send consumes a slot identically.
    // ----------------------------------------------------------------------------------
    "scenario 33 — shadow-banned sender's send consumes a slot identically" {
        val (sender, tok) = seedUser(shadowBanned = true)
        val (recipient, _) = seedUser()
        val conv = createConv(sender, recipient)
        try {
            val spy = SpyRateLimiterChat(InMemoryRateLimiter())
            withChat(rateLimiter = spy) {
                repeat(5) { postSend(tok, conv, content = "s$it").status shouldBe HttpStatusCode.Created }
            }
            countMessagesFromSender(sender) shouldBe 5
            // Five acquires, all daily-key. Rate limit accounting is symmetric for shadow-banned senders.
            spy.acquireKeys() shouldBe List(5) { "{scope:rate_chat_send_day}:{user:$sender}" }
        } finally {
            cleanup(sender, recipient)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 34 — Embedded-field silent-ignore + slot consumption.
    // Success path: slot 5 → slot 6, 201, embedded_post_id IS NULL, WARN log fired.
    // Rejection path: slot 51 → 429 specifically (NOT 422, NOT 400, NOT 201),
    // tryAcquireInvocations == 1, zero `chat_send_embedded_field_ignored` WARNs emitted.
    // ----------------------------------------------------------------------------------
    "scenario 34 — embedded-field silent-ignore + slot consumption (success and rejection paths)" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val conv = createConv(sender, recipient)
        try {
            val limiter = InMemoryRateLimiter()
            // Success path: slot 5 → 6, 201 with embedded_post_id ignored + WARN log.
            repeat(5) {
                withChat(rateLimiter = limiter) {
                    postSend(tok, conv, content = "warm-$it").status shouldBe HttpStatusCode.Created
                }
            }
            captureRoutesWarn { appender ->
                withChat(rateLimiter = limiter) {
                    val embedUuid = UUID.randomUUID()
                    val rawBody = """{"content":"with-embed","embedded_post_id":"$embedUuid"}"""
                    val resp = postSend(tok, conv, rawBody = rawBody)
                    resp.status shouldBe HttpStatusCode.Created
                    val mid = messageId(resp.bodyAsText())
                    val (embId, _, _) = chatRowEmbedColumns(mid)
                    embId shouldBe null
                }
                appender.embedIgnoredCount() shouldBe 1
            }
            countMessagesFromSender(sender) shouldBe 6
            // Rejection path: drive to 50 successful, then 51st with embed → 429 specifically.
            // (Already at 6 successful; 44 more then attempt 51st.)
            withChat(rateLimiter = limiter) {
                repeat(44) { postSend(tok, conv, content = "f$it").status shouldBe HttpStatusCode.Created }
            }
            captureRoutesWarn { appender ->
                val spy = SpyRateLimiterChat(limiter)
                withChat(rateLimiter = spy) {
                    val rawBody = """{"content":"reject-with-embed","embedded_post_id":"${UUID.randomUUID()}"}"""
                    val resp = postSend(tok, conv, rawBody = rawBody)
                    resp.status shouldBe HttpStatusCode.TooManyRequests
                    rateLimitedCode(resp.bodyAsText()) shouldBe "rate_limited"
                }
                // Limiter was invoked exactly once (the reject path).
                spy.acquireKeys().size shouldBe 1
                // Zero WARN log lines for the rejected request — the body was never parsed.
                appender.embedIgnoredCount() shouldBe 0
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

/**
 * Pinned UUIDs for scenario 23's per-user WIB offset assertion. Their `hashCode() % 3600`
 * values must differ by at least 1 second; the scenario itself runs a fixture-load-time
 * defensive `check {}` so a future JVM-version drift on `UUID.hashCode` semantics surfaces
 * as a test-time error rather than a flake.
 */
private val USER_ONE_UUID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
private val USER_TWO_UUID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000002")

/** RemoteConfig that always returns null (default fallback). */
private object NullRemoteConfigChat : RemoteConfig {
    override fun getLong(key: String): Long? = null

    override fun getBoolean(key: String): Boolean? = null
}

/** RemoteConfig with a fixed key→value map. Other keys return null. */
private class FixedRemoteConfigChat(private val values: Map<String, Long>) : RemoteConfig {
    override fun getLong(key: String): Long? = values[key]

    override fun getBoolean(key: String): Boolean? = null
}

/** RemoteConfig that throws on every call (verifies ChatService swallows errors). */
private class ThrowingRemoteConfigChat(private val error: Throwable) : RemoteConfig {
    override fun getLong(key: String): Long? = throw error

    override fun getBoolean(key: String): Boolean? = throw error
}

/**
 * [RateLimiter] decorator that records every `tryAcquire` and `releaseMostRecent` call,
 * so tests can assert the keys / capacities / TTLs the service used.
 *
 * Named `SpyRateLimiterChat` to avoid clashing with `LikeRateLimitTest`'s and
 * `ReplyRateLimitTest`'s file-private `SpyRateLimiter` symbols across the engagement
 * + chat test packages.
 */
private class SpyRateLimiterChat(val delegate: RateLimiter) : RateLimiter {
    private val acquires = ConcurrentLinkedQueue<AcquireCall>()
    private val releases = ConcurrentLinkedQueue<ReleaseCall>()

    override fun tryAcquire(
        userId: UUID,
        key: String,
        capacity: Int,
        ttl: Duration,
    ): RateLimiter.Outcome {
        acquires.add(AcquireCall(userId, key, capacity, ttl))
        return delegate.tryAcquire(userId, key, capacity, ttl)
    }

    override fun tryAcquireByKey(
        key: String,
        capacity: Int,
        ttl: Duration,
    ): RateLimiter.Outcome = delegate.tryAcquireByKey(key, capacity, ttl)

    override fun releaseMostRecent(
        userId: UUID,
        key: String,
    ) {
        releases.add(ReleaseCall(userId, key))
        delegate.releaseMostRecent(userId, key)
    }

    fun acquireKeys(): List<String> = acquires.map { it.key }

    fun releaseKeys(): List<String> = releases.map { it.key }

    private data class AcquireCall(val userId: UUID, val key: String, val capacity: Int, val ttl: Duration)

    private data class ReleaseCall(val userId: UUID, val key: String)
}
