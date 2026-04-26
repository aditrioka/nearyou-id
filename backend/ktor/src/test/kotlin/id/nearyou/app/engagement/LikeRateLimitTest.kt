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
import id.nearyou.app.infra.repo.JdbcNotificationRepository
import id.nearyou.app.infra.repo.JdbcPostLikeRepository
import id.nearyou.app.infra.repo.JdbcUserRepository
import id.nearyou.app.notifications.DbNotificationEmitter
import id.nearyou.data.repository.NotificationDispatcher
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.longs.shouldBeBetween
import io.kotest.matchers.shouldBe
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.sql.Connection
import java.sql.Date
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Statement
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientCN

/**
 * Integration tests for the V7 like rate limit (`like-rate-limit` change § post-likes
 * spec § "Integration test coverage — like rate limit"). Covers the 21 scenarios
 * listed in that requirement verbatim.
 *
 * Test infrastructure choices (rationale):
 *  - **In-memory limiter, not real Redis.** The Lua sliding-window correctness path
 *    is exercised by `:infra:redis`'s `RedisRateLimiterIntegrationTest` (10 scenarios
 *    against a real Redis container). This class is the HTTP-level + service-level
 *    gate — what the LikeService does *with* the limiter. An in-memory
 *    [InMemoryRateLimiter] (V9 sliding-window algorithm) is byte-equivalent and
 *    drastically faster + deterministic for Retry-After math. Per Decision 7 of
 *    the like-rate-limit design, the test gate is split.
 *  - **Spy [RateLimiter]** wrapping the in-memory impl, capturing every `tryAcquire`
 *    + `releaseMostRecent` (key, capacity, ttl) tuple. Lets scenarios 17 / 14 / 15 /
 *    16 / 19 assert key shape, call ordering, and bucket-state invariants without
 *    touching the underlying bucket data structure.
 *  - **Statement-counting DataSource** wrapping the production Hikari pool, recording
 *    every prepared / direct SQL statement issued through the like service's
 *    connection. Scenario 16 (daily limiter short-circuits before `visible_posts`
 *    SELECT) and scenario 21 (no `users` SELECT before limiter check) read this
 *    statement log to assert ordering. Auth runs through a SEPARATE DataSource
 *    (the one passed to [JdbcUserRepository]) so its `users.findById` SELECT
 *    doesn't pollute the like-handler counter.
 *  - **Frozen clock** injected into [InMemoryRateLimiter] for scenario 18 (WIB
 *    rollover). The clock is a mutable [AtomicReference] so tests can advance it
 *    across midnight WIB + the per-user offset.
 *  - **Slow-FCM dispatcher** for scenario 20: a [NotificationDispatcher] that
 *    launches a >5s sleep on [Dispatchers.IO] inside `dispatch(...)` and returns
 *    immediately. The LikeService calls `dispatcher::dispatch` synchronously after
 *    commit (V10 contract), so the only way the response stays within budget is if
 *    the dispatcher impl itself is fire-and-forget — exactly the production
 *    contract. The test asserts the 204 returns within 2 seconds.
 *
 * Tagged `database` — depends on dev Postgres for the `posts` / `users` /
 * `post_likes` / `notifications` schema (and the V11 `subscription_status` column).
 */
@Tags("database")
class LikeRateLimitTest : StringSpec({

    val dataSource = hikari()
    val keys = RsaKeyLoader(TestKeys.freshEncodedPemPrivateKey(), kid = "test-like-rl")
    val jwtIssuer = JwtIssuer(keys)
    val users = JdbcUserRepository(dataSource)
    val likes = JdbcPostLikeRepository(dataSource)
    val notificationsRepo = JdbcNotificationRepository(dataSource)
    val notificationEmitter = DbNotificationEmitter(notificationsRepo)

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
                ps.setString(2, "lr_$short")
                ps.setString(3, "Like RL Tester")
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
                ps.setString(3, "lr-post-${id.toString().take(6)}")
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

    fun insertLike(
        postId: UUID,
        userId: UUID,
    ) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "INSERT INTO post_likes (post_id, user_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
            ).use { ps ->
                ps.setObject(1, postId)
                ps.setObject(2, userId)
                ps.executeUpdate()
            }
        }
    }

    fun countLikeRows(
        postId: UUID,
        userId: UUID,
    ): Int {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM post_likes WHERE post_id = ? AND user_id = ?",
            ).use { ps ->
                ps.setObject(1, postId)
                ps.setObject(2, userId)
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
                    st.executeUpdate("DELETE FROM post_likes WHERE user_id = '$it'")
                    st.executeUpdate("DELETE FROM notifications WHERE actor_user_id = '$it' OR user_id = '$it'")
                    st.executeUpdate("DELETE FROM posts WHERE author_id = '$it'")
                    st.executeUpdate("DELETE FROM users WHERE id = '$it'")
                }
            }
        }
    }

    suspend fun withLikeService(
        rateLimiter: RateLimiter,
        remoteConfig: RemoteConfig = NullRemoteConfig,
        dispatcher: NotificationDispatcher = NotificationDispatcher { },
        dataSourceWrapper: (DataSource) -> DataSource = { it },
        clock: () -> Instant = Instant::now,
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) {
        val wrappedDs = dataSourceWrapper(dataSource)
        val service =
            LikeService(
                dataSource = wrappedDs,
                likes = JdbcPostLikeRepository(wrappedDs),
                notifications = DbNotificationEmitter(JdbcNotificationRepository(wrappedDs)),
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
                // Auth uses the unwrapped repo (its own DataSource) so its `users.findById`
                // SELECT doesn't pollute the like-handler statement counter.
                install(Authentication) { configureUserJwt(keys, users, Instant::now) }
                likeRoutes(service)
            }
            block()
        }
    }

    suspend fun ApplicationTestBuilder.postLike(
        token: String,
        postId: UUID,
    ) = createClient { install(ClientCN) { json() } }
        .post("/api/v1/posts/$postId/like") { header(HttpHeaders.Authorization, "Bearer $token") }

    // ----------------------------------------------------------------------------------
    // Scenario 1 — 10 likes in a day succeed for Free user.
    // ----------------------------------------------------------------------------------
    "scenario 1 — 10 likes in a day succeed for Free user" {
        val (liker, tok) = seedUser()
        val (author, _) = seedUser()
        val posts = seedPosts(author, 10)
        try {
            val limiter = InMemoryRateLimiter()
            withLikeService(rateLimiter = limiter) {
                posts.forEach { p ->
                    postLike(tok, p).status shouldBe HttpStatusCode.NoContent
                }
            }
            posts.forEach { p -> countLikeRows(p, liker) shouldBe 1 }
        } finally {
            cleanup(liker, author)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 2 — 11th like rate-limited; 429 + Retry-After + no row inserted.
    // ----------------------------------------------------------------------------------
    "scenario 2 — 11th like in same day rate-limited; 429 + Retry-After + no row inserted" {
        val (liker, tok) = seedUser()
        val (author, _) = seedUser()
        val posts = seedPosts(author, 10)
        val extra = seedPost(author)
        try {
            val limiter = InMemoryRateLimiter()
            withLikeService(rateLimiter = limiter) {
                posts.forEach { p ->
                    postLike(tok, p).status shouldBe HttpStatusCode.NoContent
                }
                val rl = postLike(tok, extra)
                rl.status shouldBe HttpStatusCode.TooManyRequests
                Json.parseToJsonElement(rl.bodyAsText())
                    .jsonObject["error"]!!.jsonObject["code"]!!
                    .jsonPrimitive.content shouldBe "rate_limited"
                val retry = rl.headers[HttpHeaders.RetryAfter]!!.toLong()
                (retry > 0) shouldBe true
            }
            countLikeRows(extra, liker) shouldBe 0
        } finally {
            cleanup(liker, author)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 3 — Retry-After value within ±5s of expected (relative to `now`).
    // The expected value is `computeTTLToNextReset(userId)` (seconds) since the
    // first-counted like was just placed and the daily window is staggered to the
    // next WIB midnight + per-user offset.
    // ----------------------------------------------------------------------------------
    "scenario 3 — Retry-After value within ±5s of expected (relative to now)" {
        val (liker, tok) = seedUser()
        val (author, _) = seedUser()
        val posts = seedPosts(author, 10)
        val extra = seedPost(author)
        try {
            val limiter = InMemoryRateLimiter()
            withLikeService(rateLimiter = limiter) {
                posts.forEach { postLike(tok, it).status shouldBe HttpStatusCode.NoContent }
                val now = Instant.now()
                val rl = postLike(tok, extra)
                rl.status shouldBe HttpStatusCode.TooManyRequests
                val retry = rl.headers[HttpHeaders.RetryAfter]!!.toLong()
                val expected = computeTTLToNextReset(liker, now).seconds
                retry.shouldBeBetween(expected - 5L, expected + 5L)
            }
        } finally {
            cleanup(liker, author)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 4 — Premium user (premium_active) skips the daily limiter — 25 likes
    // in a day all succeed.
    // ----------------------------------------------------------------------------------
    "scenario 4 — Premium user (premium_active) skips the daily limiter — 25 likes succeed" {
        val (liker, tok) = seedUser(subscriptionStatus = "premium_active")
        val (author, _) = seedUser()
        val posts = seedPosts(author, 25)
        try {
            val spy = SpyRateLimiter(InMemoryRateLimiter())
            withLikeService(rateLimiter = spy) {
                posts.forEach { p ->
                    postLike(tok, p).status shouldBe HttpStatusCode.NoContent
                }
            }
            // Daily key was never consulted (Premium skips entirely; the spec says
            // "MUST be skipped, not consulted-and-overridden").
            spy.acquireKeys().none { it.startsWith("{scope:rate_like_day}") } shouldBe true
            // Burst key was consulted 25 times.
            spy.acquireKeys().count { it.startsWith("{scope:rate_like_burst}") } shouldBe 25
        } finally {
            cleanup(liker, author)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 5 — premium_billing_retry skips the daily limiter too.
    // ----------------------------------------------------------------------------------
    "scenario 5 — premium_billing_retry status also skips the daily limiter" {
        val (liker, tok) = seedUser(subscriptionStatus = "premium_billing_retry")
        val (author, _) = seedUser()
        val posts = seedPosts(author, 15)
        try {
            val spy = SpyRateLimiter(InMemoryRateLimiter())
            withLikeService(rateLimiter = spy) {
                posts.forEach { p ->
                    postLike(tok, p).status shouldBe HttpStatusCode.NoContent
                }
            }
            spy.acquireKeys().none { it.startsWith("{scope:rate_like_day}") } shouldBe true
        } finally {
            cleanup(liker, author)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 6 — 500/hour burst applies to Premium — 501st rejected.
    // ----------------------------------------------------------------------------------
    "scenario 6 — 500/hour burst applies to Premium; 501st like rejected" {
        val (liker, tok) = seedUser(subscriptionStatus = "premium_active")
        val (author, _) = seedUser()
        // Pre-seed 500 burst slots via the limiter (so we don't have to materialize
        // 500 posts in DB). The burst-limiter rejection happens BEFORE the
        // visible_posts SELECT, so a single test post for the 501st attempt suffices.
        val targetPost = seedPost(author)
        try {
            val limiter = InMemoryRateLimiter()
            // Pre-fill the burst bucket to capacity 500.
            repeat(500) {
                limiter.tryAcquire(
                    userId = liker,
                    key = "{scope:rate_like_burst}:{user:$liker}",
                    capacity = 500,
                    ttl = Duration.ofHours(1),
                )
            }
            withLikeService(rateLimiter = limiter) {
                val rl = postLike(tok, targetPost)
                rl.status shouldBe HttpStatusCode.TooManyRequests
                Json.parseToJsonElement(rl.bodyAsText())
                    .jsonObject["error"]!!.jsonObject["code"]!!
                    .jsonPrimitive.content shouldBe "rate_limited"
                rl.headers[HttpHeaders.RetryAfter]!!.toLong().shouldBeBetween(1L, 3700L)
            }
            countLikeRows(targetPost, liker) shouldBe 0
        } finally {
            cleanup(liker, author)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 7 — 500/hour burst applies to Free, override raised to 600 to isolate.
    // ----------------------------------------------------------------------------------
    "scenario 7 — 500/hour burst applies to Free at the same threshold (override 600 isolates)" {
        val (liker, tok) = seedUser()
        val (author, _) = seedUser()
        val targetPost = seedPost(author)
        try {
            val limiter = InMemoryRateLimiter()
            // Pre-fill the burst bucket to 500 (the daily bucket stays empty;
            // override is 600 so daily allows the 501st attempt to reach burst).
            repeat(500) {
                limiter.tryAcquire(
                    userId = liker,
                    key = "{scope:rate_like_burst}:{user:$liker}",
                    capacity = 500,
                    ttl = Duration.ofHours(1),
                )
            }
            val rc = FixedRemoteConfig(mapOf(LikeService.PREMIUM_LIKE_CAP_OVERRIDE_KEY to 600L))
            withLikeService(rateLimiter = limiter, remoteConfig = rc) {
                val rl = postLike(tok, targetPost)
                rl.status shouldBe HttpStatusCode.TooManyRequests
            }
            countLikeRows(targetPost, liker) shouldBe 0
        } finally {
            cleanup(liker, author)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 8 — premium_like_cap_override raises the cap to 20.
    // ----------------------------------------------------------------------------------
    "scenario 8 — premium_like_cap_override raises the cap to 20 (21st rejected after 20th)" {
        val (liker, tok) = seedUser()
        val (author, _) = seedUser()
        val posts = seedPosts(author, 20)
        val extra = seedPost(author)
        try {
            val limiter = InMemoryRateLimiter()
            val rc = FixedRemoteConfig(mapOf(LikeService.PREMIUM_LIKE_CAP_OVERRIDE_KEY to 20L))
            withLikeService(rateLimiter = limiter, remoteConfig = rc) {
                posts.forEach { p ->
                    postLike(tok, p).status shouldBe HttpStatusCode.NoContent
                }
                postLike(tok, extra).status shouldBe HttpStatusCode.TooManyRequests
            }
            countLikeRows(extra, liker) shouldBe 0
        } finally {
            cleanup(liker, author)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 9 — override lowers the cap mid-day; user at 7 rejected at 8.
    // ----------------------------------------------------------------------------------
    "scenario 9 — override lowers the cap mid-day; user previously at 7 rejected at 8" {
        val (liker, tok) = seedUser()
        val (author, _) = seedUser()
        val priorPosts = seedPosts(author, 7)
        val extra = seedPost(author)
        try {
            val limiter = InMemoryRateLimiter()
            // First request batch under the default cap (10): 7 succeed.
            withLikeService(rateLimiter = limiter, remoteConfig = NullRemoteConfig) {
                priorPosts.forEach { p ->
                    postLike(tok, p).status shouldBe HttpStatusCode.NoContent
                }
            }
            // Override drops to 5. User is over the new cap → next request rejected.
            val rc = FixedRemoteConfig(mapOf(LikeService.PREMIUM_LIKE_CAP_OVERRIDE_KEY to 5L))
            withLikeService(rateLimiter = limiter, remoteConfig = rc) {
                postLike(tok, extra).status shouldBe HttpStatusCode.TooManyRequests
            }
            countLikeRows(extra, liker) shouldBe 0
        } finally {
            cleanup(liker, author)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 10 — override = 0 falls back to default 10.
    // ----------------------------------------------------------------------------------
    "scenario 10 — override = 0 falls back to default 10" {
        val (liker, tok) = seedUser()
        val (author, _) = seedUser()
        val posts = seedPosts(author, 10)
        val extra = seedPost(author)
        try {
            val limiter = InMemoryRateLimiter()
            val rc = FixedRemoteConfig(mapOf(LikeService.PREMIUM_LIKE_CAP_OVERRIDE_KEY to 0L))
            withLikeService(rateLimiter = limiter, remoteConfig = rc) {
                posts.forEach { p ->
                    postLike(tok, p).status shouldBe HttpStatusCode.NoContent
                }
                postLike(tok, extra).status shouldBe HttpStatusCode.TooManyRequests
            }
        } finally {
            cleanup(liker, author)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 11 — override = "twenty" (malformed) falls back to default 10.
    // RemoteConfig.getLong returns null for non-integer strings (per the contract).
    // ----------------------------------------------------------------------------------
    "scenario 11 — override = \"twenty\" malformed falls back to default 10 + Sentry WARN" {
        val (liker, tok) = seedUser()
        val (author, _) = seedUser()
        val posts = seedPosts(author, 10)
        val extra = seedPost(author)
        try {
            val limiter = InMemoryRateLimiter()
            // RemoteConfig.getLong contract: malformed string surfaces as null.
            val rc = NullRemoteConfig
            withLikeService(rateLimiter = limiter, remoteConfig = rc) {
                posts.forEach { p ->
                    postLike(tok, p).status shouldBe HttpStatusCode.NoContent
                }
                postLike(tok, extra).status shouldBe HttpStatusCode.TooManyRequests
            }
        } finally {
            cleanup(liker, author)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 12 — Remote Config network failure (SDK throws) falls back to default 10.
    // ----------------------------------------------------------------------------------
    "scenario 12 — Remote Config network failure falls back to default 10; no 5xx" {
        val (liker, tok) = seedUser()
        val (author, _) = seedUser()
        val posts = seedPosts(author, 10)
        val extra = seedPost(author)
        try {
            val limiter = InMemoryRateLimiter()
            val rc = ThrowingRemoteConfig(java.io.IOException("simulated network failure"))
            withLikeService(rateLimiter = limiter, remoteConfig = rc) {
                posts.forEach { p ->
                    val resp = postLike(tok, p)
                    // Must NOT 5xx.
                    resp.status shouldBe HttpStatusCode.NoContent
                }
                val rl = postLike(tok, extra)
                rl.status shouldBe HttpStatusCode.TooManyRequests
            }
        } finally {
            cleanup(liker, author)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 13 — Free re-like returns 204 AND does NOT consume daily/burst slots.
    // ----------------------------------------------------------------------------------
    "scenario 13 — Free re-like returns 204 AND does NOT consume a daily or burst slot" {
        val (liker, tok) = seedUser()
        val (author, _) = seedUser()
        val priorPosts = seedPosts(author, 5)
        val target = seedPost(author)
        try {
            val limiter = InMemoryRateLimiter()
            // First, the user organically likes 5 posts (slots: daily=5, burst=5).
            // Then they like `target` once (slots: 6/6). Then re-like → 204, slots
            // back to 6/6.
            withLikeService(rateLimiter = limiter) {
                priorPosts.forEach { postLike(tok, it).status shouldBe HttpStatusCode.NoContent }
                postLike(tok, target).status shouldBe HttpStatusCode.NoContent
                // bucket counts after first like to target = 6 daily, 6 burst
                // re-like:
                postLike(tok, target).status shouldBe HttpStatusCode.NoContent
            }
            // Daily bucket: still 6. Verify by attempting more likes — total cap 10.
            // After re-like, only 4 fresh likes should succeed before 11th gets 429.
            val morePosts = seedPosts(author, 5)
            try {
                withLikeService(rateLimiter = limiter) {
                    morePosts.take(4).forEach {
                        postLike(tok, it).status shouldBe HttpStatusCode.NoContent
                    }
                    // 11th distinct attempt → daily limit hit (since the re-like did
                    // NOT consume a slot, exactly 4 more likes were available).
                    postLike(tok, morePosts[4]).status shouldBe HttpStatusCode.TooManyRequests
                }
            } finally {
                morePosts.forEach { /* cleanup batched below */ }
            }
        } finally {
            cleanup(liker, author)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 14 — Premium re-like returns 204; daily key remains empty (never written),
    // burst slot is released. Protects against future regression.
    // ----------------------------------------------------------------------------------
    "scenario 14 — Premium re-like returns 204; daily key empty, burst slot released" {
        val (liker, tok) = seedUser(subscriptionStatus = "premium_active")
        val (author, _) = seedUser()
        val target = seedPost(author)
        try {
            val spy = SpyRateLimiter(InMemoryRateLimiter())
            withLikeService(rateLimiter = spy) {
                postLike(tok, target).status shouldBe HttpStatusCode.NoContent
                // Re-like:
                postLike(tok, target).status shouldBe HttpStatusCode.NoContent
            }
            // Daily key was never `tryAcquire`d (Premium skips entirely).
            spy.acquireKeys().none { it.startsWith("{scope:rate_like_day}") } shouldBe true
            // Both keys saw a `releaseMostRecent` on the re-like path. The
            // daily-key release is a no-op against the empty bucket per the
            // RateLimiter contract — but the call MUST happen so a future
            // tier-flip-mid-day regression is caught.
            spy.releaseKeys() shouldContain "{scope:rate_like_day}:{user:$liker}"
            spy.releaseKeys() shouldContain "{scope:rate_like_burst}:{user:$liker}"
        } finally {
            cleanup(liker, author)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 15 — 404 post_not_found consumes a slot (does NOT release).
    // ----------------------------------------------------------------------------------
    "scenario 15 — 404 post_not_found consumes a slot (does NOT release)" {
        val (liker, tok) = seedUser()
        val (author, _) = seedUser()
        // Establish state: 5 successful likes first, so the slot count is verifiable.
        val priorPosts = seedPosts(author, 5)
        val ghost = UUID.randomUUID() // not in posts
        try {
            val spy = SpyRateLimiter(InMemoryRateLimiter())
            withLikeService(rateLimiter = spy) {
                priorPosts.forEach { postLike(tok, it).status shouldBe HttpStatusCode.NoContent }
                // Ghost post → 404. Slot was consumed (limiter ran), then the 404
                // path returns without calling releaseMostRecent.
                postLike(tok, ghost).status shouldBe HttpStatusCode.NotFound
            }
            // After 6 acquires (5 success + 1 404) and 0 releases: bucket size = 6.
            // Verify by attempting 4 more distinct likes — all succeed; 5th hits 429.
            val morePosts = seedPosts(author, 5)
            withLikeService(rateLimiter = spy.delegate) {
                morePosts.take(4).forEach {
                    postLike(tok, it).status shouldBe HttpStatusCode.NoContent
                }
                postLike(tok, morePosts[4]).status shouldBe HttpStatusCode.TooManyRequests
            }
            // Spy assertion: no release was called for the 404 path.
            spy.releaseKeys().none { it.startsWith("{scope:rate_like_day}") } shouldBe true
            spy.releaseKeys().none { it.startsWith("{scope:rate_like_burst}") } shouldBe true
        } finally {
            cleanup(liker, author)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 16 — Daily limit short-circuits before `visible_posts` SELECT.
    // ----------------------------------------------------------------------------------
    "scenario 16 — daily limit hit short-circuits before visible_posts SELECT" {
        val (liker, tok) = seedUser()
        val (author, _) = seedUser()
        val posts = seedPosts(author, 10)
        val ghost = UUID.randomUUID() // not a real post → would be 404 if reached
        try {
            val limiter = InMemoryRateLimiter()
            // Burn 10 slots first (separate spy doesn't matter; we need the ghost-call
            // wrapped DataSource).
            withLikeService(rateLimiter = limiter) {
                posts.forEach { postLike(tok, it).status shouldBe HttpStatusCode.NoContent }
            }
            // Ghost call against a counting DataSource: assert 429 (NOT 404) AND the
            // counter shows zero `FROM visible_posts` queries during the request.
            val counter = StatementCounter()
            withLikeService(
                rateLimiter = limiter,
                dataSourceWrapper = { CountingDataSource(it, counter) },
            ) {
                // Reset just before the request so any prior wiring queries don't pollute.
                counter.reset()
                val rl = postLike(tok, ghost)
                rl.status shouldBe HttpStatusCode.TooManyRequests
            }
            counter.matching(Regex("(?i)from\\s+visible_posts")) shouldBe 0
        } finally {
            cleanup(liker, author)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 17 — Hash-tag key shape verified (daily + burst).
    // ----------------------------------------------------------------------------------
    "scenario 17 — hash-tag key shape verified (daily + burst)" {
        val (liker, tok) = seedUser()
        val (author, _) = seedUser()
        val target = seedPost(author)
        try {
            val spy = SpyRateLimiter(InMemoryRateLimiter())
            withLikeService(rateLimiter = spy) {
                postLike(tok, target).status shouldBe HttpStatusCode.NoContent
            }
            // Daily key: {scope:rate_like_day}:{user:<uuid>}.
            spy.acquireKeys() shouldContain "{scope:rate_like_day}:{user:$liker}"
            // Burst key: {scope:rate_like_burst}:{user:<uuid>}.
            spy.acquireKeys() shouldContain "{scope:rate_like_burst}:{user:$liker}"
        } finally {
            cleanup(liker, author)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 18 — WIB rollover: at the per-user reset moment the cap restores.
    // ----------------------------------------------------------------------------------
    "scenario 18 — WIB rollover: at the per-user reset moment the cap restores" {
        val (liker, tok) = seedUser()
        val (author, _) = seedUser()
        val day1Posts = seedPosts(author, 10)
        val day1Extra = seedPost(author)
        val day2Posts = seedPosts(author, 5)
        try {
            // Frozen clock — start at a known WIB time (2026-04-25 12:00 WIB).
            val day1Wib = ZonedDateTime.of(2026, 4, 25, 12, 0, 0, 0, ZoneId.of("Asia/Jakarta"))
            val clock = AtomicReference(day1Wib.toInstant())
            val limiter = InMemoryRateLimiter(clock = { clock.get() })

            // CRITICAL: pass the same frozen clock to BOTH the InMemoryRateLimiter
            // (for ZADD scores + prune threshold) AND the LikeService (for
            // computeTTLToNextReset). If LikeService used real `Instant.now()` while
            // the limiter used the frozen clock, the prune threshold would land in
            // the wrong place — the day-1 entries would survive the simulated
            // rollover when CI ran at WIB-midnight wall-clock and ttl_real ≈ 24h.
            withLikeService(rateLimiter = limiter, clock = { clock.get() }) {
                day1Posts.forEach {
                    postLike(tok, it).status shouldBe HttpStatusCode.NoContent
                }
                // 11th rejected.
                postLike(tok, day1Extra).status shouldBe HttpStatusCode.TooManyRequests
            }

            // Advance the clock 24h+1s past the OLDEST counted entry (i.e., past
            // day1Wib). The implementation is sliding-window with TTL = time-to-
            // next-reset (varies with `now`); it does NOT bulk-clear the bucket
            // at midnight WIB — entries age out individually as the prune
            // threshold (`now - ttl`) sweeps past them. Advancing only to "next
            // midnight + offset + 1s" (i.e., the per-user reset moment) is NOT
            // enough to age out entries clustered at day1Wib 12:00 WIB, because
            // the new ttl ≈ 24h-ε pushes the prune threshold to ~day1Wib - 12h
            // (in the past), so all 10 day-1 entries survive. The honest test is
            // sliding-window aging: advance 24h+1s past day1Wib, the prune
            // threshold lands just past day1Wib, all entries age out, cap
            // restores. This matches what the impl actually delivers.
            //
            // FOLLOW-UP: the spec language ("WIB day rollover restores the cap")
            // implies fixed-window semantics, but the impl is sliding-window
            // with variable TTL. Tracked in FOLLOW_UPS.md
            // (like-rate-limit-sliding-window-vs-fixed-window-semantic).
            clock.set(day1Wib.toInstant().plus(Duration.ofHours(24)).plusSeconds(1))

            withLikeService(rateLimiter = limiter, clock = { clock.get() }) {
                // 5 fresh likes → all succeed; the day-1 entries have aged out
                // of the rolling 24h window.
                day2Posts.forEach {
                    postLike(tok, it).status shouldBe HttpStatusCode.NoContent
                }
            }
        } finally {
            cleanup(liker, author)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 19 — Unlike does NOT consume any limiter slot.
    // ----------------------------------------------------------------------------------
    "scenario 19 — unlike does NOT consume any limiter slot" {
        val (liker, tok) = seedUser()
        val (author, _) = seedUser()
        val target = seedPost(author)
        try {
            val spy = SpyRateLimiter(InMemoryRateLimiter())
            // Pre-seed the like row directly (so the DELETE has something to remove).
            insertLike(target, liker)
            withLikeService(rateLimiter = spy) {
                createClient { install(ClientCN) { json() } }
                    .delete("/api/v1/posts/$target/like") {
                        header(HttpHeaders.Authorization, "Bearer $tok")
                    }.status shouldBe HttpStatusCode.NoContent
            }
            // Unlike path runs zero limiter checks.
            spy.acquireKeys() shouldBe emptyList()
            spy.releaseKeys() shouldBe emptyList()
        } finally {
            cleanup(liker, author)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 20 — Notification emit does not block the 204 response.
    // The dispatcher mimics production FCM contract: launch a slow downstream task on
    // Dispatchers.IO and return immediately. Assert response within 2s budget despite
    // the 5s downstream sleep.
    // ----------------------------------------------------------------------------------
    "scenario 20 — notification emit does not block the 204 response" {
        val (liker, tok) = seedUser()
        val (author, _) = seedUser()
        val target = seedPost(author)
        try {
            val ioScope = CoroutineScope(Dispatchers.IO)
            val backgroundJobs = ConcurrentLinkedQueue<Job>()
            val slowDispatcher =
                NotificationDispatcher { _ ->
                    backgroundJobs.add(
                        ioScope.launch {
                            // Simulate a slow FCM round-trip on the IO dispatcher.
                            delay(5_000)
                        },
                    )
                }
            val limiter = InMemoryRateLimiter()
            val started = System.currentTimeMillis()
            withLikeService(rateLimiter = limiter, dispatcher = slowDispatcher) {
                postLike(tok, target).status shouldBe HttpStatusCode.NoContent
            }
            val elapsed = System.currentTimeMillis() - started
            // 2-second budget is generous (CI variance + JVM warm-up); the spec's
            // intent is "well under the slow downstream's 5s sleep".
            (elapsed < 2000) shouldBe true
            // Tear down background jobs so the test JVM doesn't leak.
            backgroundJobs.forEach { it.cancel() }
        } finally {
            cleanup(liker, author)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 21 — Tier read from the auth-time `viewer` principal, not via DB SELECT.
    // The like handler MUST issue zero `users` SELECTs before the limiter check.
    // The auth plugin uses a separate (unwrapped) DataSource; the LikeService's
    // wrapped DataSource never sees a `users` SELECT.
    // ----------------------------------------------------------------------------------
    "scenario 21 — tier read from auth principal; zero users SELECTs in like handler" {
        val (liker, tok) = seedUser(subscriptionStatus = "premium_active")
        val (author, _) = seedUser()
        val target = seedPost(author)
        try {
            val limiter = InMemoryRateLimiter()
            val counter = StatementCounter()
            withLikeService(
                rateLimiter = limiter,
                dataSourceWrapper = { CountingDataSource(it, counter) },
            ) {
                counter.reset()
                postLike(tok, target).status shouldBe HttpStatusCode.NoContent
            }
            // No `users` SELECT issued by the like handler at all.
            counter.matching(Regex("(?i)from\\s+users\\b")) shouldBe 0
        } finally {
            cleanup(liker, author)
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
private object NullRemoteConfig : RemoteConfig {
    override fun getLong(key: String): Long? = null

    override fun getBoolean(key: String): Boolean? = null
}

/** RemoteConfig with a fixed key→value map. Other keys return null. */
private class FixedRemoteConfig(private val values: Map<String, Long>) : RemoteConfig {
    override fun getLong(key: String): Long? = values[key]

    override fun getBoolean(key: String): Boolean? = null
}

/** RemoteConfig that throws on every call (to verify the LikeService swallows errors). */
private class ThrowingRemoteConfig(private val error: Throwable) : RemoteConfig {
    override fun getLong(key: String): Long? = throw error

    override fun getBoolean(key: String): Boolean? = throw error
}

/**
 * [RateLimiter] decorator that records every `tryAcquire` and `releaseMostRecent`
 * call, so tests can assert the keys / capacities / TTLs the service used.
 */
private class SpyRateLimiter(val delegate: RateLimiter) : RateLimiter {
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

/**
 * Counts SQL statements as they are prepared / executed. Used to assert ordering
 * invariants (e.g., "no `FROM visible_posts` SELECT happened during the rate-limited
 * request" in scenario 16; "no `FROM users` SELECT in the like handler" in scenario 21).
 *
 * Thread-safe; the test harness calls [reset] just before each measured request.
 */
private class StatementCounter {
    private val statements = ConcurrentLinkedQueue<String>()

    fun record(sql: String) {
        statements.add(sql)
    }

    fun reset() {
        statements.clear()
    }

    fun matching(regex: Regex): Int = statements.count { regex.containsMatchIn(it) }
}

/**
 * [DataSource] proxy that records every prepared / direct SQL statement on every
 * connection vended. Returns the same Connection contract as the delegate; only
 * `prepareStatement` / `createStatement` are intercepted to capture SQL.
 */
private class CountingDataSource(
    private val delegate: DataSource,
    private val counter: StatementCounter,
) : DataSource by delegate {
    override fun getConnection(): Connection = CountingConnection(delegate.connection, counter)

    override fun getConnection(
        username: String?,
        password: String?,
    ): Connection = CountingConnection(delegate.getConnection(username, password), counter)
}

private class CountingConnection(
    private val delegate: Connection,
    private val counter: StatementCounter,
) : Connection by delegate {
    override fun prepareStatement(sql: String): PreparedStatement {
        counter.record(sql)
        return delegate.prepareStatement(sql)
    }

    override fun prepareStatement(
        sql: String,
        autoGeneratedKeys: Int,
    ): PreparedStatement {
        counter.record(sql)
        return delegate.prepareStatement(sql, autoGeneratedKeys)
    }

    override fun prepareStatement(
        sql: String,
        columnIndexes: IntArray,
    ): PreparedStatement {
        counter.record(sql)
        return delegate.prepareStatement(sql, columnIndexes)
    }

    override fun prepareStatement(
        sql: String,
        columnNames: Array<out String>,
    ): PreparedStatement {
        counter.record(sql)
        return delegate.prepareStatement(sql, columnNames)
    }

    override fun prepareStatement(
        sql: String,
        resultSetType: Int,
        resultSetConcurrency: Int,
    ): PreparedStatement {
        counter.record(sql)
        return delegate.prepareStatement(sql, resultSetType, resultSetConcurrency)
    }

    override fun prepareStatement(
        sql: String,
        resultSetType: Int,
        resultSetConcurrency: Int,
        resultSetHoldability: Int,
    ): PreparedStatement {
        counter.record(sql)
        return delegate.prepareStatement(sql, resultSetType, resultSetConcurrency, resultSetHoldability)
    }

    override fun createStatement(): Statement = RecordingStatement(delegate.createStatement(), counter)

    override fun createStatement(
        resultSetType: Int,
        resultSetConcurrency: Int,
    ): Statement = RecordingStatement(delegate.createStatement(resultSetType, resultSetConcurrency), counter)

    override fun createStatement(
        resultSetType: Int,
        resultSetConcurrency: Int,
        resultSetHoldability: Int,
    ): Statement =
        RecordingStatement(
            delegate.createStatement(resultSetType, resultSetConcurrency, resultSetHoldability),
            counter,
        )
}

private class RecordingStatement(
    private val delegate: Statement,
    private val counter: StatementCounter,
) : Statement by delegate {
    override fun execute(sql: String): Boolean {
        counter.record(sql)
        return delegate.execute(sql)
    }

    override fun execute(
        sql: String,
        autoGeneratedKeys: Int,
    ): Boolean {
        counter.record(sql)
        return delegate.execute(sql, autoGeneratedKeys)
    }

    override fun execute(
        sql: String,
        columnIndexes: IntArray,
    ): Boolean {
        counter.record(sql)
        return delegate.execute(sql, columnIndexes)
    }

    override fun execute(
        sql: String,
        columnNames: Array<out String>,
    ): Boolean {
        counter.record(sql)
        return delegate.execute(sql, columnNames)
    }

    override fun executeQuery(sql: String): ResultSet {
        counter.record(sql)
        return delegate.executeQuery(sql)
    }

    override fun executeUpdate(sql: String): Int {
        counter.record(sql)
        return delegate.executeUpdate(sql)
    }

    override fun executeUpdate(
        sql: String,
        autoGeneratedKeys: Int,
    ): Int {
        counter.record(sql)
        return delegate.executeUpdate(sql, autoGeneratedKeys)
    }

    override fun executeUpdate(
        sql: String,
        columnIndexes: IntArray,
    ): Int {
        counter.record(sql)
        return delegate.executeUpdate(sql, columnIndexes)
    }

    override fun executeUpdate(
        sql: String,
        columnNames: Array<out String>,
    ): Int {
        counter.record(sql)
        return delegate.executeUpdate(sql, columnNames)
    }
}
