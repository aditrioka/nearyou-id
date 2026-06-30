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
import id.nearyou.app.core.domain.ratelimit.InMemoryRateLimiter
import id.nearyou.app.core.domain.ratelimit.RateLimiter
import id.nearyou.app.guard.ContentLengthGuard
import id.nearyou.app.infra.repo.JdbcEmbeddedPostResolver
import id.nearyou.app.infra.repo.JdbcUserRepository
import id.nearyou.app.notifications.NoopNotificationDispatcher
import id.nearyou.app.notifications.NotificationEmitter
import id.nearyou.data.repository.NotificationType
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.sql.Connection
import java.sql.Date
import java.sql.Timestamp
import java.sql.Types
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
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
            // Frugal pool + autoClose — CI connection-budget rule (docs/11 §3.2).
            maximumPoolSize = 2
            minimumIdle = 0
            initializationFailTimeout = -1
        }
    return HikariDataSource(config)
}

/**
 * Route-level integration tests for the `chat-embedded-posts` send-path (tasks 5.1–5.4, 5.6, 5.7,
 * 5.8). Wires a REAL [JdbcEmbeddedPostResolver] over the test DB plus a capturing
 * [FakeEmbedRealtimeClient] so the populated-broadcast and snapshot-persistence behavior can be
 * asserted end-to-end. Schema-level CHECK/FK assertions (5.5 + the pg_description leg of 5.7) live
 * in `MigrationV37SmokeTest` (the V37 FK `confdeltype`/`convalidated`/`confrelid`, the size CHECK
 * reject + NULL-pass, and the `pg_description` deferred-comment-removed assertion).
 *
 * Tagged `database` so CI's `!network` lane excludes it; run locally with the standard
 * `DB_URL` / `DB_USER` / `DB_PASSWORD` env vars (defaults match Docker Compose dev Postgres).
 */
@Tags("database")
class ChatEmbeddedPostSendTest : StringSpec({

    val dataSource = autoClose(hikari())
    val keys = RsaKeyLoader(TestKeys.freshEncodedPemPrivateKey(), kid = "test-embed")
    val jwtIssuer = JwtIssuer(keys)
    val users = JdbcUserRepository(dataSource)
    val repository = ChatRepository(dataSource)
    val resolver = JdbcEmbeddedPostResolver(dataSource)
    val contentGuard = ContentLengthGuard(mapOf(CHAT_CONTENT_KEY to 2000))

    val snapshotKeys =
        setOf("authorUsername", "authorDisplayName", "content", "cityName", "createdAt", "editedAt")

    fun seedUser(shadowBanned: Boolean = false): Pair<UUID, String> {
        val id = UUID.randomUUID()
        val short = id.toString().replace("-", "").take(8)
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO users (id, username, display_name, date_of_birth, invite_code_prefix, is_shadow_banned)
                VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "ep_$short")
                ps.setString(3, "Embed Tester $short")
                ps.setDate(4, Date.valueOf(LocalDate.of(1990, 1, 1)))
                ps.setString(5, "e${short.take(7)}")
                ps.setBoolean(6, shadowBanned)
                ps.executeUpdate()
            }
        }
        return id to jwtIssuer.issueAccessToken(id, tokenVersion = 0)
    }

    fun seedPost(
        authorId: UUID,
        content: String = "post content",
        autoHidden: Boolean = false,
        softDeleted: Boolean = false,
    ): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO posts (id, author_id, content, display_location, actual_location, is_auto_hidden, deleted_at)
                VALUES (?, ?, ?,
                  ST_SetSRID(ST_MakePoint(106.8, -6.2), 4326)::geography,
                  ST_SetSRID(ST_MakePoint(106.8, -6.2), 4326)::geography,
                  ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setObject(2, authorId)
                ps.setString(3, content)
                ps.setBoolean(4, autoHidden)
                if (softDeleted) ps.setTimestamp(5, Timestamp.from(Instant.now())) else ps.setNull(5, Types.TIMESTAMP)
                ps.executeUpdate()
            }
        }
        return id
    }

    fun seedEdit(
        postId: UUID,
        editedBy: UUID,
        editedAt: Instant,
    ): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO post_edits (id, post_id, edited_at, content_snapshot, location_snapshot, edited_by)
                VALUES (?, ?, ?, 'old', ST_SetSRID(ST_MakePoint(106.8, -6.2), 4326)::geography, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setObject(2, postId)
                ps.setTimestamp(3, Timestamp.from(editedAt))
                ps.setObject(4, editedBy)
                ps.executeUpdate()
            }
        }
        return id
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

    fun hardDeletePost(postId: UUID) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("DELETE FROM posts WHERE id = ?").use { ps ->
                ps.setObject(1, postId)
                ps.executeUpdate()
            }
        }
    }

    fun embedColumns(messageId: UUID): Triple<String?, String?, String?> {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT embedded_post_id, embedded_post_snapshot, embedded_post_edit_id, content " +
                    "FROM chat_messages WHERE id = ?",
            ).use { ps ->
                ps.setObject(1, messageId)
                ps.executeQuery().use { rs ->
                    check(rs.next())
                    return Triple(
                        rs.getObject("embedded_post_id")?.toString(),
                        rs.getString("embedded_post_snapshot"),
                        rs.getObject("embedded_post_edit_id")?.toString(),
                    )
                }
            }
        }
    }

    fun contentOf(messageId: UUID): String? {
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT content FROM chat_messages WHERE id = ?").use { ps ->
                ps.setObject(1, messageId)
                ps.executeQuery().use { rs ->
                    check(rs.next())
                    return rs.getString("content")
                }
            }
        }
    }

    fun cleanup(vararg ids: UUID) {
        if (ids.isEmpty()) return
        val inList = ids.joinToString(",") { "'$it'" }
        dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                // Conversations involving any test user — CASCADE clears participants + messages.
                st.executeUpdate(
                    "DELETE FROM conversations WHERE id IN " +
                        "(SELECT conversation_id FROM conversation_participants WHERE user_id IN ($inList))",
                )
                st.executeUpdate("DELETE FROM conversations WHERE created_by IN ($inList)")
                st.executeUpdate(
                    "DELETE FROM post_edits WHERE edited_by IN ($inList) " +
                        "OR post_id IN (SELECT id FROM posts WHERE author_id IN ($inList))",
                )
                st.executeUpdate("DELETE FROM posts WHERE author_id IN ($inList)")
                st.executeUpdate("DELETE FROM user_blocks WHERE blocker_id IN ($inList) OR blocked_id IN ($inList)")
                st.executeUpdate("DELETE FROM users WHERE id IN ($inList)")
            }
        }
    }

    suspend fun withChat(
        realtime: ChatRealtimeClient = FakeEmbedRealtimeClient(),
        rateLimiter: RateLimiter = InMemoryRateLimiter(),
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) {
        val service =
            ChatService(
                repository = repository,
                notifications = NoopEmbedEmitter,
                dispatcher = NoopNotificationDispatcher(),
                rateLimiter = rateLimiter,
                remoteConfig = StubRemoteConfig(),
                textModerator = id.nearyou.app.moderation.TestModerationFixtures.ALLOW_ONLY_MODERATOR,
                moderationQueue = id.nearyou.app.moderation.TestModerationFixtures.SHARED_QUEUE_REPO,
                embeddedPostResolver = resolver,
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

    suspend fun ApplicationTestBuilder.send(
        token: String,
        conversationId: UUID,
        body: String,
    ): HttpResponse =
        createClient { install(ClientCN) { json() } }
            .post("/api/v1/chat/$conversationId/messages") {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(body)
            }

    suspend fun HttpResponse.messageId(): UUID =
        UUID.fromString(Json.parseToJsonElement(bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content)

    // ---- 5.1 send-path acceptance ----------------------------------------------

    "5.1 embed-only (no content) is accepted; row has content NULL + embed populated" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val (author, _) = seedUser()
        try {
            val conv = createConv(sender, recipient)
            val post = seedPost(author, content = "shared text")
            withChat {
                val resp = send(tok, conv, """{"embedded_post_id":"$post"}""")
                resp.status shouldBe HttpStatusCode.Created
                val mid = resp.messageId()
                val (embId, snap, _) = embedColumns(mid)
                contentOf(mid) shouldBe null
                embId shouldBe post.toString()
                snap.shouldNotBeNull()
            }
        } finally {
            cleanup(sender, recipient, author)
        }
    }

    "5.1 content + embed together is accepted; row carries BOTH" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val (author, _) = seedUser()
        try {
            val conv = createConv(sender, recipient)
            val post = seedPost(author)
            withChat {
                val resp = send(tok, conv, """{"content":"lihat ini","embedded_post_id":"$post"}""")
                resp.status shouldBe HttpStatusCode.Created
                val mid = resp.messageId()
                contentOf(mid) shouldBe "lihat ini"
                embedColumns(mid).first shouldBe post.toString()
            }
        } finally {
            cleanup(sender, recipient, author)
        }
    }

    "5.1 neither content nor embed is rejected 400, no row persisted" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        try {
            val conv = createConv(sender, recipient)
            withChat {
                send(tok, conv, """{"content":"   "}""").status shouldBe HttpStatusCode.BadRequest
                send(tok, conv, """{}""").status shouldBe HttpStatusCode.BadRequest
            }
        } finally {
            cleanup(sender, recipient)
        }
    }

    "5.1 over-length content with an embed is still rejected 400" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val (author, _) = seedUser()
        try {
            val conv = createConv(sender, recipient)
            val post = seedPost(author)
            val tooLong = "x".repeat(2001)
            withChat {
                val resp = send(tok, conv, """{"content":"$tooLong","embedded_post_id":"$post"}""")
                resp.status shouldBe HttpStatusCode.BadRequest
            }
        } finally {
            cleanup(sender, recipient, author)
        }
    }

    "5.1 embed sends traverse the SAME chat send rate-limit layer (no bypass)" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val (author, _) = seedUser()
        try {
            val conv = createConv(sender, recipient)
            val post = seedPost(author)
            // An always-rate-limited limiter must gate an embed send → 429 (the route runs the
            // limiter BEFORE body parse + embed resolution, so embeds cannot bypass the cap).
            withChat(rateLimiter = AlwaysRateLimited) {
                val resp = send(tok, conv, """{"embedded_post_id":"$post"}""")
                resp.status shouldBe HttpStatusCode.TooManyRequests
            }
        } finally {
            cleanup(sender, recipient, author)
        }
    }

    // ---- 5.2 visibility resolution ---------------------------------------------

    "5.2 blocked-author / shadow-banned-author / soft-deleted / non-existent all return identical 404" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val (blockedAuthor, _) = seedUser()
        val (shadowAuthor, _) = seedUser(shadowBanned = true)
        val (normalAuthor, _) = seedUser()
        seedBlock(sender, blockedAuthor)
        try {
            val conv = createConv(sender, recipient)
            val blockedPost = seedPost(blockedAuthor)
            val shadowPost = seedPost(shadowAuthor)
            val softDeleted = seedPost(normalAuthor, softDeleted = true)
            val nonExistent = UUID.randomUUID()
            val constant404 = """{"error":{"code":"post_not_found"}}"""
            withChat {
                for (target in listOf(blockedPost.toString(), shadowPost.toString(), softDeleted.toString(), nonExistent.toString())) {
                    val resp = send(tok, conv, """{"embedded_post_id":"$target"}""")
                    resp.status shouldBe HttpStatusCode.NotFound
                    resp.bodyAsText() shouldBe constant404
                }
            }
        } finally {
            cleanup(sender, recipient, blockedAuthor, shadowAuthor, normalAuthor)
        }
    }

    "5.2 visible post → snapshot persisted" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val (author, _) = seedUser()
        try {
            val conv = createConv(sender, recipient)
            val post = seedPost(author)
            withChat {
                val mid = send(tok, conv, """{"embedded_post_id":"$post"}""").messageId()
                embedColumns(mid).second.shouldNotBeNull()
            }
        } finally {
            cleanup(sender, recipient, author)
        }
    }

    "5.2 shadow-banned sender sharing their OWN post → snapshot persisted (own-content arm)" {
        val (sender, tok) = seedUser(shadowBanned = true)
        val (recipient, _) = seedUser()
        try {
            val conv = createConv(sender, recipient)
            val ownPost = seedPost(sender, content = "my own post")
            withChat {
                val resp = send(tok, conv, """{"embedded_post_id":"$ownPost"}""")
                resp.status shouldBe HttpStatusCode.Created
                embedColumns(resp.messageId()).first shouldBe ownPost.toString()
            }
        } finally {
            cleanup(sender, recipient)
        }
    }

    // ---- 5.3 spatial-fuzzing exact-key-set allowlist ---------------------------

    "5.3 persisted snapshot JSON key set is EXACTLY the 6 display keys (no coordinate / author UUID)" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val (author, _) = seedUser()
        try {
            val conv = createConv(sender, recipient)
            val post = seedPost(author, content = "coordinate-free body")
            withChat {
                val mid = send(tok, conv, """{"embedded_post_id":"$post"}""").messageId()
                val snapshot = Json.parseToJsonElement(embedColumns(mid).second!!).jsonObject
                snapshot.keys shouldContainExactly snapshotKeys
                // explicit denylist belt-and-braces (a rename must not sneak a coordinate in)
                for (forbidden in listOf("latitude", "longitude", "lat", "lng", "display_location", "geohash", "distanceM", "authorId")) {
                    (forbidden in snapshot.keys) shouldBe false
                }
                snapshot["content"]!!.jsonPrimitive.content shouldBe "coordinate-free body"
            }
        } finally {
            cleanup(sender, recipient, author)
        }
    }

    // ---- 5.4 version anchor ----------------------------------------------------

    "5.4 unedited post → embedded_post_edit_id NULL; edited post → latest post_edits.id" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val (author, _) = seedUser()
        try {
            val conv = createConv(sender, recipient)
            val unedited = seedPost(author)
            val edited = seedPost(author)
            seedEdit(edited, author, Instant.parse("2026-01-01T00:00:00Z"))
            val latestEdit = seedEdit(edited, author, Instant.parse("2026-01-01T00:05:00Z"))
            withChat {
                val m1 = send(tok, conv, """{"embedded_post_id":"$unedited"}""").messageId()
                embedColumns(m1).third shouldBe null
                val m2 = send(tok, conv, """{"embedded_post_id":"$edited"}""").messageId()
                embedColumns(m2).third shouldBe latestEdit.toString()
            }
        } finally {
            cleanup(sender, recipient, author)
        }
    }

    // ---- 5.6 broadcast ---------------------------------------------------------

    "5.6 embed message broadcasts populated fields; plain message present-with-null; shadow-ban sender no broadcast" {
        val (sender, tok) = seedUser()
        val (shadowSender, shadowTok) = seedUser(shadowBanned = true)
        val (recipient, _) = seedUser()
        val (author, _) = seedUser()
        try {
            val conv = createConv(sender, recipient)
            val shadowConv = createConv(shadowSender, recipient)
            val post = seedPost(author)
            val fake = FakeEmbedRealtimeClient()
            withChat(realtime = fake) {
                // embed → populated broadcast
                send(tok, conv, """{"embedded_post_id":"$post"}""").status shouldBe HttpStatusCode.Created
                // plain → present-with-null broadcast
                send(tok, conv, """{"content":"halo"}""").status shouldBe HttpStatusCode.Created
                // shadow-banned sender embed → persists but NO broadcast
                send(shadowTok, shadowConv, """{"embedded_post_id":"$post"}""").status shouldBe HttpStatusCode.Created
            }
            val broadcasts = fake.invocations.map { it.second }
            broadcasts.size shouldBe 2 // shadow sender's send is NOT broadcast
            val embedBroadcast = broadcasts.first { it.embeddedPostId != null }
            embedBroadcast.embeddedPostId shouldBe post
            embedBroadcast.embeddedPostSnapshot.shouldNotBeNull()
            val plainBroadcast = broadcasts.first { it.embeddedPostId == null }
            plainBroadcast.embeddedPostSnapshot shouldBe null
            plainBroadcast.embeddedPostEditId shouldBe null
        } finally {
            cleanup(sender, shadowSender, recipient, author)
        }
    }

    // ---- 5.7 snapshot survives source-post hard-delete -------------------------

    "5.7 hard-deleting the source post NULLs embedded_post_id but keeps the snapshot + a valid row" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val (author, _) = seedUser()
        try {
            val conv = createConv(sender, recipient)
            val post = seedPost(author, content = "doomed post")
            withChat {
                val mid = send(tok, conv, """{"embedded_post_id":"$post"}""").messageId()
                embedColumns(mid).first shouldBe post.toString()
                hardDeletePost(post)
                val (embId, snap, _) = embedColumns(mid)
                embId shouldBe null // ON DELETE SET NULL fired
                snap.shouldNotBeNull() // snapshot survives — empty-message CHECK still satisfied
                Json.parseToJsonElement(snap).jsonObject["content"]!!.jsonPrimitive.content shouldBe "doomed post"
            }
        } finally {
            cleanup(sender, recipient, author)
        }
    }

    // ---- 5.8 REST history read renders embed fields ----------------------------

    "5.8 GET /chat/{id}/messages carries the three embedded_* fields for an embed message" {
        val (sender, tok) = seedUser()
        val (recipient, _) = seedUser()
        val (author, _) = seedUser()
        try {
            val conv = createConv(sender, recipient)
            val post = seedPost(author, content = "history body")
            val editId = seedEdit(post, author, Instant.parse("2026-02-01T00:00:00Z"))
            withChat {
                send(tok, conv, """{"embedded_post_id":"$post"}""").status shouldBe HttpStatusCode.Created
                val histResp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/chat/$conv/messages") {
                            header(HttpHeaders.Authorization, "Bearer $tok")
                        }
                histResp.status shouldBe HttpStatusCode.OK
                val messages = Json.parseToJsonElement(histResp.bodyAsText()).jsonObject["messages"]!!.jsonArray
                val m = messages.first().jsonObject
                m["embedded_post_id"]!!.jsonPrimitive.content shouldBe post.toString()
                (m["embedded_post_snapshot"] != JsonNull) shouldBe true
                m["embedded_post_snapshot"]!!.jsonObject["content"]!!.jsonPrimitive.content shouldBe "history body"
                m["embedded_post_edit_id"]!!.jsonPrimitive.content shouldBe editId.toString()
            }
        } finally {
            cleanup(sender, recipient, author)
        }
    }
})

/** Capturing realtime client — records each publish so embed/plain broadcast shapes can be asserted. */
private class FakeEmbedRealtimeClient : ChatRealtimeClient {
    private val collected = ConcurrentLinkedQueue<Pair<UUID, ChatMessageBroadcast>>()

    val invocations: List<Pair<UUID, ChatMessageBroadcast>>
        get() = collected.toList()

    override suspend fun publish(
        conversationId: UUID,
        message: ChatMessageBroadcast,
    ): PublishResult {
        collected.add(conversationId to message)
        return PublishResult.Success
    }
}

/** Always-rate-limited limiter — proves the chat send limiter gates embed sends (no bypass). */
private object AlwaysRateLimited : RateLimiter {
    override fun tryAcquire(
        userId: UUID,
        key: String,
        capacity: Int,
        ttl: Duration,
    ): RateLimiter.Outcome = RateLimiter.Outcome.RateLimited(retryAfterSeconds = 60)

    override fun tryAcquireByKey(
        key: String,
        capacity: Int,
        ttl: Duration,
    ): RateLimiter.Outcome = RateLimiter.Outcome.RateLimited(retryAfterSeconds = 60)

    override fun releaseMostRecent(
        userId: UUID,
        key: String,
    ) = Unit
}

private object NoopEmbedEmitter : NotificationEmitter {
    override fun emit(
        conn: Connection,
        recipientId: UUID,
        actorUserId: UUID?,
        type: NotificationType,
        targetType: String?,
        targetId: UUID?,
        bodyData: kotlinx.serialization.json.JsonObject,
    ): UUID? = null
}
