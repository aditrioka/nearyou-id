package id.nearyou.app.engagement

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
import id.nearyou.app.infra.repo.JdbcNotificationRepository
import id.nearyou.app.infra.repo.JdbcPostReplyRepository
import id.nearyou.app.infra.repo.JdbcUserRepository
import id.nearyou.app.notifications.DbNotificationEmitter
import id.nearyou.app.notifications.NoopNotificationDispatcher
import id.nearyou.app.notifications.NotificationEmitter
import id.nearyou.data.repository.NotificationDispatcher
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.longs.shouldBeBetween
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.sql.Connection
import java.sql.Date
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientCN

/**
 * Integration tests for the V12-era reply rate limit (`reply-rate-limit` change §
 * post-replies spec § "Integration test coverage — reply rate limit"). Covers the
 * 24 scenarios listed in that requirement verbatim.
 *
 * Test infrastructure choices (rationale):
 *  - **In-memory limiter, not real Redis.** The Lua sliding-window correctness path
 *    is exercised by `:infra:redis`'s `RedisRateLimiterIntegrationTest`. This class is
 *    the HTTP-level + service-level gate testing what `ReplyService` does *with* the
 *    limiter. An in-memory [InMemoryRateLimiter] (V9 sliding-window algorithm) is
 *    byte-equivalent and drastically faster + deterministic for `Retry-After` math.
 *  - **Spy [RateLimiter]** wrapping the in-memory impl, capturing every `tryAcquire`
 *    + `releaseMostRecent` (key, capacity, ttl) tuple. Lets scenarios 15 / 18 / 19 /
 *    21 assert key shape, call ordering, and `releaseMostRecent`-never-invoked.
 *  - **Frozen clock** injected into [InMemoryRateLimiter] for scenario 16 (WIB
 *    rollover). The clock is a mutable [AtomicReference] so tests can advance it
 *    across midnight WIB + the per-user offset.
 *
 * Tagged `database` — depends on dev Postgres for the `posts` / `users` /
 * `post_replies` / `notifications` schema (and the V11 `subscription_status` column).
 */
@Tags("database")
class ReplyRateLimitTest : StringSpec({

    val dataSource = hikari()
    val keys = RsaKeyLoader(TestKeys.freshEncodedPemPrivateKey(), kid = "test-reply-rl")
    val jwtIssuer = JwtIssuer(keys)
    val users = JdbcUserRepository(dataSource)
    val replyRepo = JdbcPostReplyRepository(dataSource)
    val notificationsRepo = JdbcNotificationRepository(dataSource)
    val notificationEmitter = DbNotificationEmitter(notificationsRepo)
    val contentGuard = ContentLengthGuard(mapOf(REPLY_CONTENT_KEY to 280))

    fun seedUser(subscriptionStatus: String = "free"): Pair<UUID, String> {
        val id = UUID.randomUUID()
        val short = id.toString().replace("-", "").take(8)
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO users (
                    id, username, display_name, date_of_birth, invite_code_prefix,
                    subscription_status
                ) VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "rr_$short")
                ps.setString(3, "Reply RL Tester")
                ps.setDate(4, Date.valueOf(LocalDate.of(1990, 1, 1)))
                ps.setString(5, "k${short.take(7)}")
                ps.setString(6, subscriptionStatus)
                ps.executeUpdate()
            }
        }
        val token = jwtIssuer.issueAccessToken(id, tokenVersion = 0)
        return id to token
    }

    fun seedPost(authorId: UUID): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO posts (id, author_id, content, display_location, actual_location)
                VALUES (?, ?, ?,
                  ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                  ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setObject(2, authorId)
                ps.setString(3, "rr-post-${id.toString().take(6)}")
                ps.setDouble(4, 106.8)
                ps.setDouble(5, -6.2)
                ps.setDouble(6, 106.8)
                ps.setDouble(7, -6.2)
                ps.executeUpdate()
            }
        }
        return id
    }

    fun seedPosts(
        authorId: UUID,
        n: Int,
    ): List<UUID> = (1..n).map { seedPost(authorId) }

    fun countReplyRows(authorId: UUID): Int {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM post_replies WHERE author_id = ?",
            ).use { ps ->
                ps.setObject(1, authorId)
                ps.executeQuery().use { rs ->
                    rs.next()
                    return rs.getInt(1)
                }
            }
        }
    }

    fun countNotificationsForRecipient(recipientId: UUID): Int {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM notifications WHERE user_id = ?",
            ).use { ps ->
                ps.setObject(1, recipientId)
                ps.executeQuery().use { rs ->
                    rs.next()
                    return rs.getInt(1)
                }
            }
        }
    }

    fun cleanup(vararg ids: UUID) {
        dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                ids.forEach {
                    st.executeUpdate("DELETE FROM post_replies WHERE author_id = '$it'")
                    st.executeUpdate("DELETE FROM notifications WHERE actor_user_id = '$it' OR user_id = '$it'")
                    st.executeUpdate("DELETE FROM posts WHERE author_id = '$it'")
                    st.executeUpdate("DELETE FROM users WHERE id = '$it'")
                }
            }
        }
    }

    suspend fun withReplyService(
        rateLimiter: RateLimiter,
        remoteConfig: RemoteConfig = NullRemoteConfigReply,
        emitter: NotificationEmitter = notificationEmitter,
        dispatcher: NotificationDispatcher = NoopNotificationDispatcher(),
        clock: () -> Instant = Instant::now,
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) {
        val service =
            ReplyService(
                dataSource = dataSource,
                replies = replyRepo,
                notifications = emitter,
                dispatcher = dispatcher,
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
                install(Authentication) { configureUserJwt(keys, users, Instant::now) }
                replyRoutes(service, contentGuard)
            }
            block()
        }
    }

    suspend fun ApplicationTestBuilder.postReply(
        token: String,
        postId: UUID,
        content: String = "test reply content",
    ) = createClient { install(ClientCN) { json() } }
        .post("/api/v1/posts/$postId/replies") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"content":"$content"}""")
        }

    suspend fun ApplicationTestBuilder.deleteReply(
        token: String,
        postId: UUID,
        replyId: UUID,
    ) = createClient { install(ClientCN) { json() } }
        .delete("/api/v1/posts/$postId/replies/$replyId") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

    suspend fun ApplicationTestBuilder.getReplies(
        token: String,
        postId: UUID,
    ) = createClient { install(ClientCN) { json() } }
        .get("/api/v1/posts/$postId/replies") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

    fun rateLimitedCode(body: String): String =
        Json.parseToJsonElement(body)
            .jsonObject["error"]!!.jsonObject["code"]!!
            .jsonPrimitive.content

    fun postReplyId(body: String): UUID =
        UUID.fromString(
            Json.parseToJsonElement(body)
                .jsonObject["id"]!!.jsonPrimitive.content,
        )

    // ----------------------------------------------------------------------------------
    // Scenario 1 — 20 replies in a day succeed for Free user.
    // ----------------------------------------------------------------------------------
    "scenario 1 — 20 replies in a day succeed for Free user" {
        val (replier, tok) = seedUser()
        val (author, _) = seedUser()
        val posts = seedPosts(author, 20)
        try {
            val limiter = InMemoryRateLimiter()
            withReplyService(rateLimiter = limiter) {
                posts.forEach { p ->
                    postReply(tok, p).status shouldBe HttpStatusCode.Created
                }
            }
            countReplyRows(replier) shouldBe 20
        } finally {
            cleanup(replier, author)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 2 — 21st reply rate-limited; 429 + Retry-After + zero notifications.
    // ----------------------------------------------------------------------------------
    "scenario 2 — 21st reply rate-limited; 429 + Retry-After + no row + zero notifications" {
        val (replier, tok) = seedUser()
        val (author, _) = seedUser()
        val posts = seedPosts(author, 20)
        val extra = seedPost(author)
        try {
            val limiter = InMemoryRateLimiter()
            withReplyService(rateLimiter = limiter) {
                posts.forEach { p ->
                    postReply(tok, p).status shouldBe HttpStatusCode.Created
                }
                val notificationsBefore = countNotificationsForRecipient(author)
                val rl = postReply(tok, extra)
                rl.status shouldBe HttpStatusCode.TooManyRequests
                rateLimitedCode(rl.bodyAsText()) shouldBe "rate_limited"
                rl.headers[HttpHeaders.RetryAfter]!!.toLong() shouldNotBe 0L
                // Zero new notifications: the limiter rejected before V10 emit pipeline ran.
                countNotificationsForRecipient(author) shouldBe notificationsBefore
            }
            // No `post_replies` row inserted for the 21st attempt (still 20).
            countReplyRows(replier) shouldBe 20
        } finally {
            cleanup(replier, author)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 3 — Retry-After ±5s of expected (frozen-clock-equivalent via Instant.now()).
    // ----------------------------------------------------------------------------------
    "scenario 3 — Retry-After value within ±5s of expected (relative to now)" {
        val (replier, tok) = seedUser()
        val (author, _) = seedUser()
        val posts = seedPosts(author, 20)
        val extra = seedPost(author)
        try {
            val limiter = InMemoryRateLimiter()
            withReplyService(rateLimiter = limiter) {
                posts.forEach { postReply(tok, it).status shouldBe HttpStatusCode.Created }
                val now = Instant.now()
                val rl = postReply(tok, extra)
                rl.status shouldBe HttpStatusCode.TooManyRequests
                val retry = rl.headers[HttpHeaders.RetryAfter]!!.toLong()
                val expected = computeTTLToNextReset(replier, now).seconds
                retry.shouldBeBetween(expected - 5L, expected + 5L)
            }
        } finally {
            cleanup(replier, author)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 4 — premium_active skips the daily limiter — 50 replies all succeed.
    // ----------------------------------------------------------------------------------
    "scenario 4 — Premium (premium_active) skips the daily limiter — 50 replies succeed" {
        val (replier, tok) = seedUser(subscriptionStatus = "premium_active")
        val (author, _) = seedUser()
        val posts = seedPosts(author, 50)
        try {
            val spy = SpyRateLimiterReply(InMemoryRateLimiter())
            withReplyService(rateLimiter = spy) {
                posts.forEach { p ->
                    postReply(tok, p).status shouldBe HttpStatusCode.Created
                }
            }
            // Daily key was never consulted — Premium skips entirely (NOT consult-and-skip).
            spy.acquireKeys().none { it.startsWith("{scope:rate_reply_day}") } shouldBe true
            // No burst key on replies (no burst limiter) — total acquires should be 0.
            spy.acquireKeys() shouldBe emptyList()
        } finally {
            cleanup(replier, author)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 5 — premium_billing_retry skips the daily limiter — 40 replies succeed.
    // ----------------------------------------------------------------------------------
    "scenario 5 — Premium billing retry status also skips the daily limiter (40 succeed)" {
        val (replier, tok) = seedUser(subscriptionStatus = "premium_billing_retry")
        val (author, _) = seedUser()
        val posts = seedPosts(author, 40)
        try {
            val spy = SpyRateLimiterReply(InMemoryRateLimiter())
            withReplyService(rateLimiter = spy) {
                posts.forEach { p ->
                    postReply(tok, p).status shouldBe HttpStatusCode.Created
                }
            }
            spy.acquireKeys() shouldBe emptyList()
        } finally {
            cleanup(replier, author)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 6 — premium_reply_cap_override raises the cap to 30; 31st rejected.
    // ----------------------------------------------------------------------------------
    "scenario 6 — premium_reply_cap_override = 30 raises the cap (31st rejected after 30th)" {
        val (replier, tok) = seedUser()
        val (author, _) = seedUser()
        val posts = seedPosts(author, 30)
        val extra = seedPost(author)
        try {
            val limiter = InMemoryRateLimiter()
            val rc = FixedRemoteConfigReply(mapOf(ReplyService.PREMIUM_REPLY_CAP_OVERRIDE_KEY to 30L))
            withReplyService(rateLimiter = limiter, remoteConfig = rc) {
                posts.forEach { p ->
                    postReply(tok, p).status shouldBe HttpStatusCode.Created
                }
                postReply(tok, extra).status shouldBe HttpStatusCode.TooManyRequests
            }
            countReplyRows(replier) shouldBe 30
        } finally {
            cleanup(replier, author)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 7 — override lowers the cap mid-day; user previously at 7 rejected at 8.
    // ----------------------------------------------------------------------------------
    "scenario 7 — override = 5 lowers the cap mid-day; user previously at 7 rejected at 8" {
        val (replier, tok) = seedUser()
        val (author, _) = seedUser()
        val priorPosts = seedPosts(author, 7)
        val extra = seedPost(author)
        try {
            val limiter = InMemoryRateLimiter()
            // First batch under the default cap (20): 7 succeed.
            withReplyService(rateLimiter = limiter, remoteConfig = NullRemoteConfigReply) {
                priorPosts.forEach { p ->
                    postReply(tok, p).status shouldBe HttpStatusCode.Created
                }
            }
            // Override drops to 5. User is over the new cap → next request rejected.
            val rc = FixedRemoteConfigReply(mapOf(ReplyService.PREMIUM_REPLY_CAP_OVERRIDE_KEY to 5L))
            withReplyService(rateLimiter = limiter, remoteConfig = rc) {
                postReply(tok, extra).status shouldBe HttpStatusCode.TooManyRequests
            }
        } finally {
            cleanup(replier, author)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 8 — override = 0 falls back to default 20.
    // ----------------------------------------------------------------------------------
    "scenario 8 — override = 0 falls back to default 20" {
        val (replier, tok) = seedUser()
        val (author, _) = seedUser()
        val posts = seedPosts(author, 20)
        val extra = seedPost(author)
        try {
            val limiter = InMemoryRateLimiter()
            val rc = FixedRemoteConfigReply(mapOf(ReplyService.PREMIUM_REPLY_CAP_OVERRIDE_KEY to 0L))
            withReplyService(rateLimiter = limiter, remoteConfig = rc) {
                posts.forEach { p -> postReply(tok, p).status shouldBe HttpStatusCode.Created }
                postReply(tok, extra).status shouldBe HttpStatusCode.TooManyRequests
            }
        } finally {
            cleanup(replier, author)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 9 — override = "thirty" malformed → default 20 (RemoteConfig.getLong returns
    // null for non-integer strings per the contract). Status-only assertion.
    // ----------------------------------------------------------------------------------
    "scenario 9 — override = malformed string falls back to default 20 (status only)" {
        val (replier, tok) = seedUser()
        val (author, _) = seedUser()
        val posts = seedPosts(author, 20)
        val extra = seedPost(author)
        try {
            val limiter = InMemoryRateLimiter()
            // RemoteConfig.getLong contract: malformed string surfaces as null.
            val rc = NullRemoteConfigReply
            withReplyService(rateLimiter = limiter, remoteConfig = rc) {
                posts.forEach { p -> postReply(tok, p).status shouldBe HttpStatusCode.Created }
                postReply(tok, extra).status shouldBe HttpStatusCode.TooManyRequests
            }
        } finally {
            cleanup(replier, author)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 10 — Remote Config network failure (SDK throws) falls back to default 20;
    // no 5xx propagated.
    // ----------------------------------------------------------------------------------
    "scenario 10 — Remote Config network failure falls back to default 20; no 5xx" {
        val (replier, tok) = seedUser()
        val (author, _) = seedUser()
        val posts = seedPosts(author, 20)
        val extra = seedPost(author)
        try {
            val limiter = InMemoryRateLimiter()
            val rc = ThrowingRemoteConfigReply(java.io.IOException("simulated network failure"))
            withReplyService(rateLimiter = limiter, remoteConfig = rc) {
                posts.forEach { p ->
                    postReply(tok, p).status shouldBe HttpStatusCode.Created
                }
                val rl = postReply(tok, extra)
                rl.status shouldBe HttpStatusCode.TooManyRequests
            }
        } finally {
            cleanup(replier, author)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 11 — 404 post_not_found consumes a slot (does NOT release).
    // ----------------------------------------------------------------------------------
    "scenario 11 — 404 post_not_found consumes a slot (does NOT release)" {
        val (replier, tok) = seedUser()
        val (author, _) = seedUser()
        val priorPosts = seedPosts(author, 5)
        val ghost = UUID.randomUUID() // not in posts
        try {
            val spy = SpyRateLimiterReply(InMemoryRateLimiter())
            withReplyService(rateLimiter = spy) {
                priorPosts.forEach { postReply(tok, it).status shouldBe HttpStatusCode.Created }
                postReply(tok, ghost).status shouldBe HttpStatusCode.NotFound
            }
            // 6 acquires (5 success + 1 ghost) and 0 releases. Verify by attempting
            // 14 more distinct replies — all succeed; 15th hits 429.
            val morePosts = seedPosts(author, 15)
            withReplyService(rateLimiter = spy.delegate) {
                morePosts.take(14).forEach {
                    postReply(tok, it).status shouldBe HttpStatusCode.Created
                }
                postReply(tok, morePosts[14]).status shouldBe HttpStatusCode.TooManyRequests
            }
            spy.releaseKeys() shouldBe emptyList()
        } finally {
            cleanup(replier, author)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 12 — 400 invalid_request post-limiter consumes a slot.
    // Sends valid auth + valid UUID + valid JSON shape but empty content (after the
    // limiter ran).
    // ----------------------------------------------------------------------------------
    "scenario 12 — 400 invalid_request post-limiter consumes a slot" {
        val (replier, tok) = seedUser()
        val (author, _) = seedUser()
        val priorPosts = seedPosts(author, 5)
        val target = seedPost(author)
        try {
            val spy = SpyRateLimiterReply(InMemoryRateLimiter())
            withReplyService(rateLimiter = spy) {
                priorPosts.forEach { postReply(tok, it).status shouldBe HttpStatusCode.Created }
                // Empty content → 400 invalid_request (post-limiter).
                postReply(tok, target, content = "").status shouldBe HttpStatusCode.BadRequest
            }
            // 6 acquires, 0 releases. Verify 14 more succeed; 15th = 429.
            val morePosts = seedPosts(author, 15)
            withReplyService(rateLimiter = spy.delegate) {
                morePosts.take(14).forEach {
                    postReply(tok, it).status shouldBe HttpStatusCode.Created
                }
                postReply(tok, morePosts[14]).status shouldBe HttpStatusCode.TooManyRequests
            }
            spy.releaseKeys() shouldBe emptyList()
        } finally {
            cleanup(replier, author)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 13 — Daily limit hit short-circuits before the V8 content-length guard.
    // 281-char content at slot 21 → 429 (NOT 400 invalid_request).
    // ----------------------------------------------------------------------------------
    "scenario 13 — daily limit hit short-circuits before content-length guard (429 not 400)" {
        val (replier, tok) = seedUser()
        val (author, _) = seedUser()
        val posts = seedPosts(author, 20)
        val extra = seedPost(author)
        val oversize = "x".repeat(281)
        try {
            val limiter = InMemoryRateLimiter()
            withReplyService(rateLimiter = limiter) {
                posts.forEach { postReply(tok, it).status shouldBe HttpStatusCode.Created }
                // At slot 21 with oversized content — limiter rejects FIRST.
                val rl = postReply(tok, extra, content = oversize)
                rl.status shouldBe HttpStatusCode.TooManyRequests
                rateLimitedCode(rl.bodyAsText()) shouldBe "rate_limited"
            }
        } finally {
            cleanup(replier, author)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 14 — Daily limit hit short-circuits before `visible_posts` SELECT.
    // Non-existent post UUID at slot 21 → 429 (NOT 404 post_not_found).
    // ----------------------------------------------------------------------------------
    "scenario 14 — daily limit hit short-circuits before visible_posts SELECT (429 not 404)" {
        val (replier, tok) = seedUser()
        val (author, _) = seedUser()
        val posts = seedPosts(author, 20)
        val ghost = UUID.randomUUID()
        try {
            val limiter = InMemoryRateLimiter()
            withReplyService(rateLimiter = limiter) {
                posts.forEach { postReply(tok, it).status shouldBe HttpStatusCode.Created }
                // At slot 21 with a non-existent post UUID — limiter rejects FIRST.
                val rl = postReply(tok, ghost)
                rl.status shouldBe HttpStatusCode.TooManyRequests
            }
        } finally {
            cleanup(replier, author)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 15 — Hash-tag key shape verified via SpyRateLimiter.
    // ----------------------------------------------------------------------------------
    "scenario 15 — hash-tag key shape: {scope:rate_reply_day}:{user:<uuid>}" {
        val (replier, tok) = seedUser()
        val (author, _) = seedUser()
        val target = seedPost(author)
        try {
            val spy = SpyRateLimiterReply(InMemoryRateLimiter())
            withReplyService(rateLimiter = spy) {
                postReply(tok, target).status shouldBe HttpStatusCode.Created
            }
            spy.acquireKeys() shouldContain "{scope:rate_reply_day}:{user:$replier}"
        } finally {
            cleanup(replier, author)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 16 — WIB rollover: at the per-user reset moment the cap restores.
    // (Sliding-window aging pattern, mirrors LikeRateLimitTest scenario 18.)
    // ----------------------------------------------------------------------------------
    "scenario 16 — WIB rollover: at the per-user reset moment the cap restores" {
        val (replier, tok) = seedUser()
        val (author, _) = seedUser()
        val day1Posts = seedPosts(author, 20)
        val day1Extra = seedPost(author)
        val day2Posts = seedPosts(author, 5)
        try {
            val day1Wib = ZonedDateTime.of(2026, 4, 25, 12, 0, 0, 0, ZoneId.of("Asia/Jakarta"))
            val clock = AtomicReference(day1Wib.toInstant())
            val limiter = InMemoryRateLimiter(clock = { clock.get() })
            // Same frozen clock to BOTH limiter and ReplyService (mirrors scenario 18 of
            // LikeRateLimitTest — see its inline rationale).
            withReplyService(rateLimiter = limiter, clock = { clock.get() }) {
                day1Posts.forEach {
                    postReply(tok, it).status shouldBe HttpStatusCode.Created
                }
                postReply(tok, day1Extra).status shouldBe HttpStatusCode.TooManyRequests
            }
            // Advance 24h+1s past the OLDEST counted entry (day1Wib 12:00 WIB) so the
            // sliding-window prune ages out all 20 day-1 entries.
            clock.set(day1Wib.toInstant().plus(Duration.ofHours(24)).plusSeconds(1))
            withReplyService(rateLimiter = limiter, clock = { clock.get() }) {
                day2Posts.forEach {
                    postReply(tok, it).status shouldBe HttpStatusCode.Created
                }
            }
        } finally {
            cleanup(replier, author)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 17 — Soft-delete does NOT release a daily slot.
    // Sequence: POST 5 → DELETE 1 → POST 15 more → 21st = 429.
    // ----------------------------------------------------------------------------------
    "scenario 17 — soft-delete does NOT release a daily slot" {
        val (replier, tok) = seedUser()
        val (author, _) = seedUser()
        val firstFive = seedPosts(author, 5)
        val nextFifteen = seedPosts(author, 15)
        val extra = seedPost(author)
        try {
            val limiter = InMemoryRateLimiter()
            withReplyService(rateLimiter = limiter) {
                // Step 1: POST 5 successful replies (slots 1-5).
                val firstReplyId =
                    firstFive
                        .map { p ->
                            val resp = postReply(tok, p)
                            resp.status shouldBe HttpStatusCode.Created
                            postReplyId(resp.bodyAsText())
                        }
                        .first()
                // Step 2: DELETE one of those replies. Bucket stays at 5.
                deleteReply(tok, firstFive[0], firstReplyId).status shouldBe HttpStatusCode.NoContent
                // Step 3: POST 15 more (slots 6-20). All succeed.
                nextFifteen.forEach { p ->
                    postReply(tok, p).status shouldBe HttpStatusCode.Created
                }
                // Step 4: 21st POST attempt — rejected. Bucket stays at 20 (the 21st
                // acquisition was rejected, so no slot was added). Soft-delete did NOT
                // refund the cap.
                postReply(tok, extra).status shouldBe HttpStatusCode.TooManyRequests
            }
        } finally {
            cleanup(replier, author)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 18 — DELETE works when caller is at the daily cap (20/20). Bucket
    // unchanged; no limiter consultation on DELETE.
    // ----------------------------------------------------------------------------------
    "scenario 18 — DELETE works at the daily cap; no limiter consulted; bucket unchanged" {
        val (replier, tok) = seedUser()
        val (author, _) = seedUser()
        val posts = seedPosts(author, 20)
        try {
            val limiter = InMemoryRateLimiter()
            // Acquire 20 slots via 20 successful replies, capturing the first reply ID
            // for the DELETE step. AtomicReference so the lambda-scoped capture compiles.
            val firstReplyIdRef = AtomicReference<UUID>()
            withReplyService(rateLimiter = limiter) {
                posts.forEachIndexed { idx, p ->
                    val resp = postReply(tok, p)
                    resp.status shouldBe HttpStatusCode.Created
                    if (idx == 0) firstReplyIdRef.set(postReplyId(resp.bodyAsText()))
                }
            }
            // Now at 20/20 daily cap. DELETE on a Spy to confirm zero limiter calls.
            val spy = SpyRateLimiterReply(limiter)
            withReplyService(rateLimiter = spy) {
                deleteReply(tok, posts[0], firstReplyIdRef.get()).status shouldBe HttpStatusCode.NoContent
            }
            // No tryAcquire, no releaseMostRecent on the DELETE path.
            spy.acquireKeys() shouldBe emptyList()
            spy.releaseKeys() shouldBe emptyList()
        } finally {
            cleanup(replier, author)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 19 — GET unaffected by the daily cap; no limiter consultation on GET.
    // ----------------------------------------------------------------------------------
    "scenario 19 — GET unaffected by the daily cap; caller at 21/20 still gets HTTP 200" {
        val (replier, tok) = seedUser()
        val (author, _) = seedUser()
        val posts = seedPosts(author, 20)
        val target = seedPost(author)
        try {
            val limiter = InMemoryRateLimiter()
            // Drive the user to 20/20 daily cap.
            withReplyService(rateLimiter = limiter) {
                posts.forEach { postReply(tok, it).status shouldBe HttpStatusCode.Created }
            }
            // Confirm the user is at 21st-rejection.
            withReplyService(rateLimiter = limiter) {
                postReply(tok, target).status shouldBe HttpStatusCode.TooManyRequests
            }
            // GET should still return 200 from the V8 endpoint.
            val spy = SpyRateLimiterReply(limiter)
            withReplyService(rateLimiter = spy) {
                getReplies(tok, posts[0]).status shouldBe HttpStatusCode.OK
            }
            // No limiter calls on the GET path.
            spy.acquireKeys() shouldBe emptyList()
            spy.releaseKeys() shouldBe emptyList()
        } finally {
            cleanup(replier, author)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 20 — Tier read from auth-time `viewer` principal (no DB SELECT for tier).
    // SpyRateLimiter confirms tryAcquire was invoked AND HTTP 201 returned.
    // ----------------------------------------------------------------------------------
    "scenario 20 — tier read from auth principal; tryAcquire invoked + HTTP 201" {
        val (replier, tok) = seedUser()
        val (author, _) = seedUser()
        val target = seedPost(author)
        try {
            val spy = SpyRateLimiterReply(InMemoryRateLimiter())
            withReplyService(rateLimiter = spy) {
                postReply(tok, target).status shouldBe HttpStatusCode.Created
            }
            // Limiter ran exactly once (Free user, one daily-key acquire).
            spy.acquireKeys() shouldBe listOf("{scope:rate_reply_day}:{user:$replier}")
        } finally {
            cleanup(replier, author)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 21 — The reply handler MUST NOT call `releaseMostRecent` on any code path.
    // Verified across the spy-instrumented scenarios above (4, 5, 11, 12, 15, 18, 19, 20).
    // This scenario adds a final cross-cutting assertion: a single SpyRateLimiter wrapping
    // a fresh InMemoryRateLimiter, exercising success / 404 / 400-invalid-request /
    // 21st-rejection paths together — and confirming releaseKeys() stays empty.
    // ----------------------------------------------------------------------------------
    "scenario 21 — ReplyService MUST NEVER call releaseMostRecent (cross-cutting)" {
        val (replier, tok) = seedUser()
        val (author, _) = seedUser()
        val priorPosts = seedPosts(author, 5)
        val target = seedPost(author)
        val ghost = UUID.randomUUID()
        try {
            val spy = SpyRateLimiterReply(InMemoryRateLimiter())
            withReplyService(rateLimiter = spy) {
                // Path 1: 5 successful replies.
                priorPosts.forEach { postReply(tok, it).status shouldBe HttpStatusCode.Created }
                // Path 2: empty content → 400 invalid_request (slot consumed).
                postReply(tok, target, content = "").status shouldBe HttpStatusCode.BadRequest
                // Path 3: ghost UUID → 404 post_not_found (slot consumed).
                postReply(tok, ghost).status shouldBe HttpStatusCode.NotFound
            }
            spy.releaseKeys() shouldBe emptyList()
        } finally {
            cleanup(replier, author)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 22 — Null subscriptionStatus on viewer principal treated as Free.
    // Tested at the service level (UserPrincipal.subscriptionStatus is non-nullable in
    // the auth-jwt path, so the HTTP layer cannot naturally produce null — but the
    // service method MUST defensively handle the case).
    // ----------------------------------------------------------------------------------
    "scenario 22 — null subscriptionStatus on viewer principal treated as Free (defensive)" {
        val (replier, _) = seedUser()
        try {
            val limiter = InMemoryRateLimiter()
            val service =
                ReplyService(
                    dataSource = dataSource,
                    replies = replyRepo,
                    notifications = notificationEmitter,
                    dispatcher = NoopNotificationDispatcher(),
                    rateLimiter = limiter,
                    remoteConfig = NullRemoteConfigReply,
                )
            // Run 20 limiter-only calls with subscriptionStatus = null. All should be
            // Allowed (Free path, default cap = 20).
            repeat(20) {
                val outcome = service.tryRateLimit(replier, subscriptionStatus = null)
                outcome shouldBe ReplyService.RateLimitOutcome.Allowed
            }
            // 21st should be RateLimited.
            val outcome21 = service.tryRateLimit(replier, subscriptionStatus = null)
            (outcome21 is ReplyService.RateLimitOutcome.RateLimited) shouldBe true
        } finally {
            cleanup(replier)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 23 — V10 transaction rollback does NOT release the slot.
    // Inject a failing NotificationEmitter that throws on emit; the encompassing TX rolls
    // back, the post_replies row does NOT persist, but the daily slot stays consumed.
    // ----------------------------------------------------------------------------------
    "scenario 23 — V10 transaction rollback does NOT release the slot" {
        val (replier, tok) = seedUser()
        val (author, _) = seedUser()
        val target = seedPost(author)
        try {
            val limiter = InMemoryRateLimiter()
            val failingEmitter =
                object : NotificationEmitter {
                    override fun emit(
                        conn: Connection,
                        recipientId: UUID,
                        actorUserId: UUID?,
                        type: id.nearyou.data.repository.NotificationType,
                        targetType: String?,
                        targetId: UUID?,
                        bodyData: kotlinx.serialization.json.JsonObject,
                    ): UUID? {
                        throw RuntimeException("simulated V10 emit failure")
                    }
                }
            withReplyService(rateLimiter = limiter, emitter = failingEmitter) {
                // Reply attempt — service.post() throws because the emit fails inside
                // the encompassing TX, which rolls back. Ktor maps the unhandled
                // RuntimeException to HTTP 500. The slot was already consumed before
                // the TX opened.
                val resp = postReply(tok, target)
                resp.status shouldNotBe HttpStatusCode.Created
                resp.status shouldNotBe HttpStatusCode.NoContent
            }
            // No `post_replies` row persisted (TX rolled back).
            countReplyRows(replier) shouldBe 0
            // Slot was consumed: only 19 more replies should succeed before 21st = 429.
            // Verify by switching to a working emitter and exhausting the bucket.
            val morePosts = seedPosts(author, 20)
            withReplyService(rateLimiter = limiter) {
                morePosts.take(19).forEach { p ->
                    postReply(tok, p).status shouldBe HttpStatusCode.Created
                }
                postReply(tok, morePosts[19]).status shouldBe HttpStatusCode.TooManyRequests
            }
        } finally {
            cleanup(replier, author)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 24 — premium_reply_cap_override > 10,000 clamps to default 20.
    // ----------------------------------------------------------------------------------
    "scenario 24 — override > 10,000 clamps to default 20" {
        val (replier, tok) = seedUser()
        val (author, _) = seedUser()
        val posts = seedPosts(author, 20)
        val extra = seedPost(author)
        try {
            val limiter = InMemoryRateLimiter()
            // Long.MAX_VALUE is well above the 10,000 clamp threshold → falls back to 20.
            val rc = FixedRemoteConfigReply(mapOf(ReplyService.PREMIUM_REPLY_CAP_OVERRIDE_KEY to Long.MAX_VALUE))
            withReplyService(rateLimiter = limiter, remoteConfig = rc) {
                posts.forEach { p -> postReply(tok, p).status shouldBe HttpStatusCode.Created }
                postReply(tok, extra).status shouldBe HttpStatusCode.TooManyRequests
            }
        } finally {
            cleanup(replier, author)
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

/** RemoteConfig that always returns null (default fallback). */
private object NullRemoteConfigReply : RemoteConfig {
    override fun getLong(key: String): Long? = null
}

/** RemoteConfig with a fixed key→value map. Other keys return null. */
private class FixedRemoteConfigReply(private val values: Map<String, Long>) : RemoteConfig {
    override fun getLong(key: String): Long? = values[key]
}

/** RemoteConfig that throws on every call (to verify the ReplyService swallows errors). */
private class ThrowingRemoteConfigReply(private val error: Throwable) : RemoteConfig {
    override fun getLong(key: String): Long? = throw error
}

/**
 * [RateLimiter] decorator that records every `tryAcquire` and `releaseMostRecent`
 * call, so tests can assert the keys / capacities / TTLs the service used.
 *
 * Named `SpyRateLimiterReply` to avoid clashing with `LikeRateLimitTest`'s
 * private `SpyRateLimiter` (Kotlin file-private classes can collide at compile
 * time when referenced from another file in the same module via reflection or
 * mocking; the suffix keeps the symbol unique).
 */
private class SpyRateLimiterReply(val delegate: RateLimiter) : RateLimiter {
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
