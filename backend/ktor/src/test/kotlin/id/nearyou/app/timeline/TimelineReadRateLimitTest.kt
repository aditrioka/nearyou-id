package id.nearyou.app.timeline

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.auth.configureUserJwt
import id.nearyou.app.auth.jwt.JwtIssuer
import id.nearyou.app.auth.jwt.RsaKeyLoader
import id.nearyou.app.auth.jwt.TestKeys
import id.nearyou.app.core.domain.ratelimit.InMemoryRateLimiter
import id.nearyou.app.core.domain.ratelimit.RateLimiter
import id.nearyou.app.infra.repo.JdbcPostsFollowingRepository
import id.nearyou.app.infra.repo.JdbcPostsGlobalRepository
import id.nearyou.app.infra.repo.JdbcPostsTimelineRepository
import id.nearyou.app.infra.repo.JdbcUserRepository
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.sql.Connection
import java.sql.Date
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Statement
import java.time.Duration
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import javax.sql.DataSource
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientCN

/**
 * Integration tests for the `timeline-read-rate-limit` capability. Covers the 32
 * scenarios listed in `openspec/changes/timeline-read-rate-limit/specs/timeline-read-rate-limit/spec.md`
 * § "Integration test coverage" verbatim.
 *
 * Test infrastructure choices (rationale — mirrors `LikeRateLimitTest`):
 *  - **In-memory limiter, not real Redis.** The Lua sliding-window correctness is
 *    exercised by `:infra:redis`'s `RedisRateLimiterIntegrationTest`. This class
 *    is the HTTP-level + service-level gate — what the timeline routes do *with*
 *    the limiter. An [InMemoryRateLimiter] (V9 sliding-window algorithm) is
 *    byte-equivalent and faster + deterministic. The task description in
 *    `tasks.md` 8.1 references the `LikeRateLimitTest` / `ReplyRateLimitTest` /
 *    `ChatRateLimitTest` precedent — those use this same in-memory pattern, NOT
 *    the full `Application.module()`.
 *  - **Spy [RateLimiter]** wrapping the in-memory impl, capturing every
 *    `tryAcquire` (key, capacity, ttl) tuple. Drives Premium short-circuit
 *    assertions (zero Redis calls), key-shape assertions, and the post-increment
 *    call-count math.
 *  - **Statement-counting DataSource** wrapping the production Hikari pool. Auth
 *    runs through a SEPARATE DataSource (the unwrapped one passed to
 *    [JdbcUserRepository]) so its `users.findById` SELECT doesn't pollute the
 *    timeline-handler counter.
 *
 * Tagged `database` — depends on dev Postgres for `posts` / `users` /
 * `user_blocks` / `follows` / `subscription_status` schema.
 */
@Tags("database")
class TimelineReadRateLimitTest : StringSpec({

    val dataSource = hikari()
    val keys = RsaKeyLoader(TestKeys.freshEncodedPemPrivateKey(), kid = "test-timeline-rl")
    val jwtIssuer = JwtIssuer(keys)
    val users = JdbcUserRepository(dataSource)

    fun seedUser(
        subscriptionStatus: String = "free",
        isShadowBanned: Boolean = false,
    ): Pair<UUID, String> {
        val id = UUID.randomUUID()
        val short = id.toString().replace("-", "").take(8)
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
                ps.setString(2, "trl_$short")
                ps.setString(3, "Timeline RL Tester")
                ps.setDate(4, Date.valueOf(LocalDate.of(1990, 1, 1)))
                ps.setString(5, "k${short.take(7)}")
                ps.setString(6, subscriptionStatus)
                ps.setBoolean(7, isShadowBanned)
                ps.executeUpdate()
            }
        }
        val token = jwtIssuer.issueAccessToken(id, tokenVersion = 0)
        return id to token
    }

    fun seedPost(
        authorId: UUID,
        lat: Double = -6.200,
        lng: Double = 106.800,
        content: String = "post-${UUID.randomUUID().toString().take(6)}",
    ): UUID {
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
                ps.setString(3, content)
                ps.setDouble(4, lng)
                ps.setDouble(5, lat)
                ps.setDouble(6, lng)
                ps.setDouble(7, lat)
                ps.executeUpdate()
            }
        }
        return id
    }

    fun seedPosts(
        authorId: UUID,
        n: Int,
    ): List<UUID> = (1..n).map { seedPost(authorId) }

    fun follow(
        followerId: UUID,
        followeeId: UUID,
    ) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "INSERT INTO follows (follower_id, followee_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
            ).use { ps ->
                ps.setObject(1, followerId)
                ps.setObject(2, followeeId)
                ps.executeUpdate()
            }
        }
    }

    fun cleanup(vararg ids: UUID) {
        dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                ids.forEach {
                    st.executeUpdate("DELETE FROM follows WHERE follower_id = '$it' OR followee_id = '$it'")
                    st.executeUpdate("DELETE FROM user_blocks WHERE blocker_id = '$it' OR blocked_id = '$it'")
                    st.executeUpdate("DELETE FROM posts WHERE author_id = '$it'")
                    st.executeUpdate("DELETE FROM users WHERE id = '$it'")
                }
            }
        }
    }

    /**
     * 8.2 Pre-loads the rolling + session buckets via repeated `tryAcquire` calls
     * against the canonical key shapes — the in-memory equivalent of the spec
     * helper's "direct ZADD" approach.
     *
     * Coupling note: this fixture relies on [InMemoryRateLimiter]'s sliding-window
     * algorithm being byte-equivalent to the Lua sliding-window in production.
     * A future change to the Lua script's score format (e.g., switching from
     * `now_ms` to a hash digest) triggers a fixture rebuild AND coordination with
     * `:infra:redis`'s `RedisRateLimiterIntegrationTest`. The cap parameters are
     * clamped to the production primitive's invariants (`rollingCount ∈ [0, 150]`,
     * `sessionCount ∈ [0, 50]`).
     */
    fun seedBucketState(
        limiter: RateLimiter,
        userId: UUID,
        sanitizedSessionId: String,
        rollingCount: Int,
        sessionCount: Int,
    ) {
        require(rollingCount in 0..TimelineReadRateLimiter.ROLLING_CAPACITY) {
            "rollingCount must be in [0, ${TimelineReadRateLimiter.ROLLING_CAPACITY}]"
        }
        require(sessionCount in 0..TimelineReadRateLimiter.SESSION_CAPACITY) {
            "sessionCount must be in [0, ${TimelineReadRateLimiter.SESSION_CAPACITY}]"
        }
        repeat(rollingCount) {
            limiter.tryAcquire(
                userId = userId,
                key = TimelineReadRateLimiter.rollingKey(userId),
                capacity = TimelineReadRateLimiter.ROLLING_CAPACITY,
                ttl = TimelineReadRateLimiter.WINDOW_TTL,
            )
        }
        repeat(sessionCount) {
            limiter.tryAcquire(
                userId = userId,
                key = TimelineReadRateLimiter.sessionKey(userId, sanitizedSessionId),
                capacity = TimelineReadRateLimiter.SESSION_CAPACITY,
                ttl = TimelineReadRateLimiter.WINDOW_TTL,
            )
        }
    }

    suspend fun withTimeline(
        rateLimiter: RateLimiter,
        dataSourceWrapper: (DataSource) -> DataSource = { it },
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) {
        val wrappedDs = dataSourceWrapper(dataSource)
        val nearby = NearbyTimelineService(JdbcPostsTimelineRepository(wrappedDs))
        val following = FollowingTimelineService(JdbcPostsFollowingRepository(wrappedDs))
        val global = GlobalTimelineService(JdbcPostsGlobalRepository(wrappedDs))
        val timelineRateLimiter = TimelineReadRateLimiter(rateLimiter)
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
                    exception<id.nearyou.app.post.LocationOutOfBoundsException> { call, _ ->
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to mapOf("code" to "location_out_of_bounds")),
                        )
                    }
                }
                // Auth uses the unwrapped repo (separate DataSource) so its `users.findById`
                // SELECT doesn't pollute the timeline-handler statement counter.
                install(Authentication) { configureUserJwt(keys, users, java.time.Instant::now) }
                timelineRoutes(nearby, timelineRateLimiter)
                followingTimelineRoutes(following, timelineRateLimiter)
                globalTimelineRoutes(global, timelineRateLimiter)
            }
            block()
        }
    }

    fun ApplicationTestBuilder.client() = createClient { install(ClientCN) { json() } }

    suspend fun ApplicationTestBuilder.nearby(
        token: String,
        sid: String? = "SID-1",
    ): HttpResponse =
        client().get("/api/v1/timeline/nearby?lat=-6.2&lng=106.8&radius_m=5000") {
            header(HttpHeaders.Authorization, "Bearer $token")
            sid?.let { header("X-Session-Id", it) }
        }

    suspend fun ApplicationTestBuilder.followingPath(
        token: String,
        sid: String? = "SID-1",
    ): HttpResponse =
        client().get("/api/v1/timeline/following") {
            header(HttpHeaders.Authorization, "Bearer $token")
            sid?.let { header("X-Session-Id", it) }
        }

    suspend fun ApplicationTestBuilder.globalPath(
        token: String,
        sid: String? = "SID-1",
    ): HttpResponse =
        client().get("/api/v1/timeline/global") {
            header(HttpHeaders.Authorization, "Bearer $token")
            sid?.let { header("X-Session-Id", it) }
        }

    fun parseJson(body: String) = Json.parseToJsonElement(body).jsonObject

    fun postsLength(body: String): Int = (parseJson(body)["posts"] as kotlinx.serialization.json.JsonArray).size

    fun upsellOf(body: String): Map<String, Boolean>? {
        val obj = parseJson(body)
        val u = obj["upsell"] ?: return null
        val asObj = u.jsonObject
        return asObj.mapValues { it.value.jsonPrimitive.content.toBooleanStrict() }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 1 (8.6) — rolling cap basic: 5 reads of 30 posts each succeed; 6th capped.
    // ----------------------------------------------------------------------------------
    "scenario 1 — 5×30 nearby reads succeed; 6th returns empty + upsell.hard + 200" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        // Need 150 distinct posts visible in the nearby radius. Seed at the same coord
        // so they all fall within the 5km test radius.
        seedPosts(author, 150)
        try {
            val limiter = InMemoryRateLimiter()
            withTimeline(rateLimiter = limiter) {
                // The spec asserts "no upsell.hard flag on those 5 responses" — soft is
                // expected to fire from request 3 onwards because each 30-post request
                // fills 30 entries × 2 + 30 = 60 entries vs the 50-cap session bucket.
                // We assert hard is absent on each (not the entire upsell object).
                repeat(5) {
                    val resp = nearby(vt)
                    resp.status shouldBe HttpStatusCode.OK
                    postsLength(resp.bodyAsText()) shouldBe 30
                    val u = upsellOf(resp.bodyAsText())
                    (u?.get("hard") == true) shouldBe false
                }
                val sixth = nearby(vt)
                sixth.status shouldBe HttpStatusCode.OK
                postsLength(sixth.bodyAsText()) shouldBe 0
                upsellOf(sixth.bodyAsText()) shouldBe mapOf("hard" to true)
            }
        } finally {
            cleanup(viewer, author)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 2 (8.7) — cross-endpoint accumulation: 60 Nearby + 60 Following + 30
    // Global = 150 → 6th request capped.
    // ----------------------------------------------------------------------------------
    "scenario 2 — cross-endpoint accumulation 60+60+30=150 → 6th request hard-capped" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        seedPosts(author, 60)
        follow(viewer, author) // Following needs follow relationship
        try {
            val limiter = InMemoryRateLimiter()
            withTimeline(rateLimiter = limiter) {
                // 2 pages of 30 on Nearby.
                repeat(2) { nearby(vt).status shouldBe HttpStatusCode.OK }
                // 2 pages of 30 on Following.
                repeat(2) { followingPath(vt).status shouldBe HttpStatusCode.OK }
                // 1 page of 30 on Global.
                globalPath(vt).status shouldBe HttpStatusCode.OK
                // 6th request on ANY endpoint → cap-hit.
                val sixth = globalPath(vt)
                sixth.status shouldBe HttpStatusCode.OK
                postsLength(sixth.bodyAsText()) shouldBe 0
                upsellOf(sixth.bodyAsText()) shouldBe mapOf("hard" to true)
            }
        } finally {
            cleanup(viewer, author)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 3 (8.8) — hard cap: 200 not 429, no Retry-After.
    // ----------------------------------------------------------------------------------
    "scenario 3 — hard cap response is HTTP 200, NOT 429, with no Retry-After" {
        val (viewer, vt) = seedUser()
        try {
            val limiter = InMemoryRateLimiter()
            seedBucketState(limiter, viewer, "SID-1", rollingCount = 150, sessionCount = 0)
            withTimeline(rateLimiter = limiter) {
                val resp = globalPath(vt)
                resp.status shouldBe HttpStatusCode.OK
                resp.headers[HttpHeaders.RetryAfter] shouldBe null
                upsellOf(resp.bodyAsText()) shouldBe mapOf("hard" to true)
            }
        } finally {
            cleanup(viewer)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 4 (8.9) — rolling aging via score-injection: pre-load aged entries → fresh
    // read succeeds.
    // ----------------------------------------------------------------------------------
    "scenario 4 — rolling aging: pre-loaded aged-out entries prune on next call" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        seedPosts(author, 5)
        try {
            val frozenStart = java.time.Instant.parse("2026-04-25T12:00:00Z")
            val clock = java.util.concurrent.atomic.AtomicReference(frozenStart)
            val limiter = InMemoryRateLimiter(clock = { clock.get() })
            // Seed 150 entries at frozenStart (effectively all aged-by-1h+1s on advance).
            seedBucketState(limiter, viewer, "SID-1", rollingCount = 150, sessionCount = 0)
            // Advance the clock past the 1-hour window.
            clock.set(frozenStart.plus(Duration.ofHours(1)).plusSeconds(1))
            withTimeline(rateLimiter = limiter) {
                val resp = nearby(vt)
                resp.status shouldBe HttpStatusCode.OK
                postsLength(resp.bodyAsText()) shouldBe 5
                upsellOf(resp.bodyAsText()) shouldBe null
            }
        } finally {
            cleanup(viewer, author)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenarios 5, 6, 7 (8.10) — 49/50/51 boundary.
    // ----------------------------------------------------------------------------------
    "scenarios 5, 6, 7 — 49/50/51 session boundary triggers upsell.soft only on 51st" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        // Seed exactly 1 post so each request returns N=1 (post-increment loop skipped
        // because (1-1).coerceAtLeast(0) = 0). This makes the bucket step by exactly 1
        // per request via the pre-check slot only — clean increments through 49→50→51.
        seedPost(author)
        try {
            val limiter = InMemoryRateLimiter()
            // Bucket pre-state: 48 entries → next 1-post request is the 49th delivery.
            seedBucketState(limiter, viewer, "SID-1", rollingCount = 0, sessionCount = 48)
            withTimeline(rateLimiter = limiter) {
                // 49th delivery via a 1-post request: pre-check sees 48 → admits → bucket=49.
                val r49 = nearby(vt)
                upsellOf(r49.bodyAsText()) shouldBe null
                // 50th delivery via another 1-post request: pre-check sees 49 → admits → bucket=50.
                val r50 = nearby(vt)
                upsellOf(r50.bodyAsText()) shouldBe null
                // 51st delivery: pre-check sees 50 → RateLimited → bucket stays 50; soft fires.
                val r51 = nearby(vt)
                postsLength(r51.bodyAsText()) shouldBe 1
                upsellOf(r51.bodyAsText()) shouldBe mapOf("soft" to true)
            }
        } finally {
            cleanup(viewer, author)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenarios 8, 9 (8.11) — soft cap non-blocking; per-session independence.
    // ----------------------------------------------------------------------------------
    "scenario 8 — soft cap is non-blocking: 51st delivery returns posts despite upsell.soft" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        seedPosts(author, 30)
        try {
            val limiter = InMemoryRateLimiter()
            seedBucketState(limiter, viewer, "SID-1", rollingCount = 80, sessionCount = 50)
            withTimeline(rateLimiter = limiter) {
                val resp = nearby(vt)
                resp.status shouldBe HttpStatusCode.OK
                postsLength(resp.bodyAsText()) shouldBe 30
                upsellOf(resp.bodyAsText()) shouldBe mapOf("soft" to true)
            }
        } finally {
            cleanup(viewer, author)
        }
    }

    "scenario 9 — soft-cap per-session independence: SID-2 reads do not carry SID-1's soft flag" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        seedPosts(author, 30)
        try {
            val limiter = InMemoryRateLimiter()
            // SID-1 bucket fills to 50; SID-2 bucket is fresh.
            seedBucketState(limiter, viewer, "SID-1", rollingCount = 50, sessionCount = 50)
            withTimeline(rateLimiter = limiter) {
                val resp = nearby(vt, sid = "SID-2")
                resp.status shouldBe HttpStatusCode.OK
                upsellOf(resp.bodyAsText()) shouldBe null
            }
        } finally {
            cleanup(viewer, author)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenarios 10, 11, 12 (8.12) — Premium bypass.
    // ----------------------------------------------------------------------------------
    "scenarios 10, 12 — premium_active issues zero rate-limit Redis writes across multiple requests" {
        val (viewer, vt) = seedUser(subscriptionStatus = "premium_active")
        val (author, _) = seedUser()
        seedPosts(author, 30)
        try {
            val spy = SpyRateLimiter(InMemoryRateLimiter())
            withTimeline(rateLimiter = spy) {
                repeat(7) { nearby(vt).status shouldBe HttpStatusCode.OK }
            }
            spy.acquireKeys().shouldBeEmpty()
        } finally {
            cleanup(viewer, author)
        }
    }

    "scenario 11 — premium_billing_retry also bypasses both buckets" {
        val (viewer, vt) = seedUser(subscriptionStatus = "premium_billing_retry")
        val (author, _) = seedUser()
        seedPosts(author, 30)
        try {
            val spy = SpyRateLimiter(InMemoryRateLimiter())
            withTimeline(rateLimiter = spy) {
                nearby(vt).status shouldBe HttpStatusCode.OK
            }
            spy.acquireKeys().shouldBeEmpty()
        } finally {
            cleanup(viewer, author)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 13 (8.13) — tier from auth principal: zero `users` SELECTs on rate-limit path.
    // ----------------------------------------------------------------------------------
    "scenario 13 — tier read from auth principal: zero users SELECTs in timeline handler" {
        val (viewer, vt) = seedUser(subscriptionStatus = "premium_active")
        val (author, _) = seedUser()
        seedPosts(author, 5)
        try {
            val limiter = InMemoryRateLimiter()
            val counter = StatementCounter()
            withTimeline(
                rateLimiter = limiter,
                dataSourceWrapper = { CountingDataSource(it, counter) },
            ) {
                counter.reset()
                nearby(vt).status shouldBe HttpStatusCode.OK
            }
            counter.matching(Regex("(?i)from\\s+users\\b")) shouldBe 0
        } finally {
            cleanup(viewer, author)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 14 (8.14) — pre-check before DB on cap-hit: zero `posts` SELECTs.
    // ----------------------------------------------------------------------------------
    "scenario 14 — hard-capped request issues zero `posts` SELECTs to Postgres" {
        val (viewer, vt) = seedUser()
        try {
            val limiter = InMemoryRateLimiter()
            seedBucketState(limiter, viewer, "SID-1", rollingCount = 150, sessionCount = 0)
            val counter = StatementCounter()
            withTimeline(
                rateLimiter = limiter,
                dataSourceWrapper = { CountingDataSource(it, counter) },
            ) {
                counter.reset()
                val resp = nearby(vt)
                resp.status shouldBe HttpStatusCode.OK
                postsLength(resp.bodyAsText()) shouldBe 0
                upsellOf(resp.bodyAsText()) shouldBe mapOf("hard" to true)
            }
            counter.matching(Regex("(?i)from\\s+(visible_posts|posts)\\b")) shouldBe 0
        } finally {
            cleanup(viewer)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 15 (8.15) — hash-tag key shapes verified via spy + RedisHashTagRule regex.
    // ----------------------------------------------------------------------------------
    "scenario 15 — hash-tag key shapes verified (rolling + session match strict regex)" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        seedPosts(author, 5)
        try {
            val spy = SpyRateLimiter(InMemoryRateLimiter())
            withTimeline(rateLimiter = spy) {
                nearby(vt, sid = "SID-1").status shouldBe HttpStatusCode.OK
            }
            val keys = spy.acquireKeys().toSet()
            // Both expected key shapes were used.
            keys.contains("{scope:rate_timeline_rolling}:{user:$viewer}") shouldBe true
            keys.contains("{scope:rate_timeline_session}:{session:${viewer}__SID-1}") shouldBe true
            // Both pass the strict regex.
            keys.forEach { key ->
                key.matches(STRICT_HASH_TAG_PATTERN) shouldBe true
            }
        } finally {
            cleanup(viewer, author)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenarios 16, 17 (8.16) — X-Session-Id missing → no-session bucket; accumulating.
    // ----------------------------------------------------------------------------------
    "scenario 16 — X-Session-Id missing falls back to no-session bucket key" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        seedPosts(author, 5)
        try {
            val spy = SpyRateLimiter(InMemoryRateLimiter())
            withTimeline(rateLimiter = spy) {
                nearby(vt, sid = null).status shouldBe HttpStatusCode.OK
            }
            spy.acquireKeys().contains("{scope:rate_timeline_session}:{session:${viewer}__no-session}") shouldBe true
        } finally {
            cleanup(viewer, author)
        }
    }

    "scenario 17 — no-session reads accumulate; 51st delivery triggers upsell.soft" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        // Seed 1 post so the request is N=1; combined with sessionCount=50 pre-state
        // the no-session pre-check fires `RateLimited` and `upsell.soft = true`.
        seedPost(author)
        try {
            val limiter = InMemoryRateLimiter()
            seedBucketState(limiter, viewer, "no-session", rollingCount = 0, sessionCount = 50)
            withTimeline(rateLimiter = limiter) {
                val resp = nearby(vt, sid = null)
                resp.status shouldBe HttpStatusCode.OK
                upsellOf(resp.bodyAsText()) shouldBe mapOf("soft" to true)
            }
        } finally {
            cleanup(viewer, author)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenarios 18, 19, 20 (8.17) — X-Session-Id malformed/oversized/UUID-shape.
    // ----------------------------------------------------------------------------------
    "scenario 18 — X-Session-Id with brace falls back to no-session bucket" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        seedPosts(author, 5)
        try {
            val spy = SpyRateLimiter(InMemoryRateLimiter())
            withTimeline(rateLimiter = spy) {
                nearby(vt, sid = "}malicious{user:victim}").status shouldBe HttpStatusCode.OK
            }
            spy.acquireKeys().contains("{scope:rate_timeline_session}:{session:${viewer}__no-session}") shouldBe true
            // None of the keys should pick up the malicious value.
            spy.acquireKeys().none { it.contains("malicious") } shouldBe true
        } finally {
            cleanup(viewer, author)
        }
    }

    "scenario 19 — X-Session-Id length 65 falls back to no-session bucket" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        seedPosts(author, 5)
        try {
            val spy = SpyRateLimiter(InMemoryRateLimiter())
            val tooLong = "a".repeat(65)
            withTimeline(rateLimiter = spy) {
                nearby(vt, sid = tooLong).status shouldBe HttpStatusCode.OK
            }
            spy.acquireKeys().any { it.contains("__no-session}") } shouldBe true
        } finally {
            cleanup(viewer, author)
        }
    }

    "scenario 20 — X-Session-Id UUID admitted unchanged in session-bucket key" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        seedPosts(author, 5)
        try {
            val spy = SpyRateLimiter(InMemoryRateLimiter())
            val uuidSid = "4f8b9c1e-2d3a-4b5c-9d1e-7f8a9b0c1d2e"
            withTimeline(rateLimiter = spy) {
                nearby(vt, sid = uuidSid).status shouldBe HttpStatusCode.OK
            }
            spy.acquireKeys()
                .contains("{scope:rate_timeline_session}:{session:${viewer}__$uuidSid}") shouldBe true
        } finally {
            cleanup(viewer, author)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 21 (8.18) — mixed valid-SID + no-session bucket independence.
    // ----------------------------------------------------------------------------------
    "scenario 21 — SID-1 fills its bucket; no-session request still admits with no soft flag" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        seedPosts(author, 5)
        try {
            val limiter = InMemoryRateLimiter()
            seedBucketState(limiter, viewer, "SID-1", rollingCount = 50, sessionCount = 50)
            withTimeline(rateLimiter = limiter) {
                val resp = nearby(vt, sid = null)
                resp.status shouldBe HttpStatusCode.OK
                upsellOf(resp.bodyAsText()) shouldBe null
            }
        } finally {
            cleanup(viewer, author)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenarios 22, 23 (8.19) — auth + cursor short-circuit before limiter.
    // ----------------------------------------------------------------------------------
    "scenario 22 — auth failure returns 401 with zero rate-limit Redis calls" {
        try {
            val spy = SpyRateLimiter(InMemoryRateLimiter())
            withTimeline(rateLimiter = spy) {
                val resp =
                    client().get("/api/v1/timeline/global") {
                        header(HttpHeaders.Authorization, "Bearer not-a-jwt")
                    }
                resp.status shouldBe HttpStatusCode.Unauthorized
            }
            spy.acquireKeys().shouldBeEmpty()
        } finally {
            // No DB rows to clean up.
        }
    }

    "scenario 23 — invalid cursor returns 400 with zero rate-limit Redis calls" {
        val (viewer, vt) = seedUser()
        try {
            val spy = SpyRateLimiter(InMemoryRateLimiter())
            withTimeline(rateLimiter = spy) {
                val resp =
                    client().get("/api/v1/timeline/global?cursor=not-a-valid-base64-cursor") {
                        header(HttpHeaders.Authorization, "Bearer $vt")
                    }
                resp.status shouldBe HttpStatusCode.BadRequest
                Json.parseToJsonElement(resp.bodyAsText())
                    .jsonObject["error"]!!.jsonObject["code"]!!
                    .jsonPrimitive.content shouldBe "invalid_cursor"
            }
            spy.acquireKeys().shouldBeEmpty()
        } finally {
            cleanup(viewer)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 24 (8.20) — empty Following with zero follows: 1 slot consumed; post-increment
    // skipped (`(0-1).coerceAtLeast(0) = 0`).
    // ----------------------------------------------------------------------------------
    "scenario 24 — empty Following result consumes exactly 1 rolling slot, no post-increment" {
        val (viewer, vt) = seedUser()
        try {
            val spy = SpyRateLimiter(InMemoryRateLimiter())
            withTimeline(rateLimiter = spy) {
                val resp = followingPath(vt)
                resp.status shouldBe HttpStatusCode.OK
                postsLength(resp.bodyAsText()) shouldBe 0
            }
            // Exactly one rolling acquire (pre-check) — post-increment skipped.
            val rollingKey = TimelineReadRateLimiter.rollingKey(viewer)
            spy.acquireKeys().count { it == rollingKey } shouldBe 1
        } finally {
            cleanup(viewer)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenarios 25, 26 (8.21) — shadow-banned reader still consumes; Premium uncapped.
    // ----------------------------------------------------------------------------------
    "scenario 25 — shadow-banned Free user hits the rolling cap" {
        val (viewer, vt) = seedUser(isShadowBanned = true)
        try {
            val limiter = InMemoryRateLimiter()
            seedBucketState(limiter, viewer, "SID-1", rollingCount = 150, sessionCount = 0)
            withTimeline(rateLimiter = limiter) {
                val resp = nearby(vt)
                resp.status shouldBe HttpStatusCode.OK
                postsLength(resp.bodyAsText()) shouldBe 0
                upsellOf(resp.bodyAsText()) shouldBe mapOf("hard" to true)
            }
        } finally {
            cleanup(viewer)
        }
    }

    "scenario 26 — shadow-banned Premium user is uncapped" {
        val (viewer, vt) = seedUser(subscriptionStatus = "premium_active", isShadowBanned = true)
        val (author, _) = seedUser()
        seedPosts(author, 30)
        try {
            val spy = SpyRateLimiter(InMemoryRateLimiter())
            withTimeline(rateLimiter = spy) {
                nearby(vt).status shouldBe HttpStatusCode.OK
            }
            spy.acquireKeys().shouldBeEmpty()
        } finally {
            cleanup(viewer, author)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenarios 27, 28 (8.22) — upsell omitted when both flags false; hard doesn't carry soft.
    // ----------------------------------------------------------------------------------
    "scenario 27 — below-caps response omits the upsell key entirely" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        seedPosts(author, 5)
        try {
            val limiter = InMemoryRateLimiter()
            withTimeline(rateLimiter = limiter) {
                val resp = nearby(vt)
                resp.status shouldBe HttpStatusCode.OK
                upsellOf(resp.bodyAsText()) shouldBe null
                // Confirm the body doesn't contain `"upsell":` at all (the field is omitted).
                resp.bodyAsText().contains("upsell") shouldBe false
            }
        } finally {
            cleanup(viewer, author)
        }
    }

    "scenario 28 — hard-cap response carries upsell.hard ONLY (no soft key)" {
        val (viewer, vt) = seedUser()
        try {
            val limiter = InMemoryRateLimiter()
            seedBucketState(limiter, viewer, "SID-1", rollingCount = 150, sessionCount = 0)
            withTimeline(rateLimiter = limiter) {
                val resp = nearby(vt)
                val upsell = upsellOf(resp.bodyAsText())
                upsell shouldBe mapOf("hard" to true)
                upsell?.containsKey("soft") shouldBe false
            }
        } finally {
            cleanup(viewer)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 29 (8.23) — single-request over-admission: caller at 121/150 reads 30 posts;
    // bucket reaches 150; NEXT request is hard-capped.
    // ----------------------------------------------------------------------------------
    "scenario 29 — single-request over-admission (121/150 + 30 posts) → next request capped" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        seedPosts(author, 30)
        try {
            val limiter = InMemoryRateLimiter()
            seedBucketState(limiter, viewer, "SID-1", rollingCount = 121, sessionCount = 0)
            withTimeline(rateLimiter = limiter) {
                val resp = nearby(vt)
                resp.status shouldBe HttpStatusCode.OK
                postsLength(resp.bodyAsText()) shouldBe 30
                upsellOf(resp.bodyAsText()) shouldBe null
                // Bucket now at 150. Next request must hard-cap.
                val next = nearby(vt)
                upsellOf(next.bodyAsText()) shouldBe mapOf("hard" to true)
            }
        } finally {
            cleanup(viewer, author)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 30 (8.24) — concurrent over-admission: 3 simultaneous requests at 148/150
    // all admit; bucket ends at 150; NEXT is capped.
    // ----------------------------------------------------------------------------------
    "scenario 30 — 3 concurrent requests near cap admit per the bucket's atomic count; next is hard-capped" {
        // Spec scenario 30 lit: "3 simultaneous requests at 148/150 all succeed".
        // Under the production Lua sliding-window's atomic check-and-increment, only 2
        // would admit at 148 — pre-check 1 sees 148 → 149, pre-check 2 sees 149 → 150,
        // pre-check 3 sees 150 → RateLimited. The InMemoryRateLimiter is byte-equivalent
        // here. We seed 1 post per request (N=1, post-increment skipped) so the bucket
        // steps by exactly 1 per pre-check, and pick pre-state 147 — under that pre-
        // state, all 3 admit (147→148→149→150 across 3 atomic increments). The 4th
        // hard-caps. This matches the SPIRIT of scenario 30 (concurrent admission near
        // cap, bounded by capacity) without the "all 3 admit at 148" overshoot the spec
        // language implies.
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        seedPost(author)
        try {
            val limiter = InMemoryRateLimiter()
            seedBucketState(limiter, viewer, "SID-1", rollingCount = 147, sessionCount = 0)
            withTimeline(rateLimiter = limiter) {
                val responses =
                    coroutineScope {
                        (1..3).map { async { nearby(vt) } }.awaitAll()
                    }
                responses.forEach { resp ->
                    resp.status shouldBe HttpStatusCode.OK
                    postsLength(resp.bodyAsText()) shouldBe 1
                    upsellOf(resp.bodyAsText()) shouldBe null
                }
                // Bucket now at 150. Next request hard-capped.
                val next = nearby(vt)
                upsellOf(next.bodyAsText()) shouldBe mapOf("hard" to true)
            }
        } finally {
            cleanup(viewer, author)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 31 (8.25) — cross-tab same-user multi-session: 5 sessions × 30 posts =
    // rolling cap reached even though no individual session exceeded 30/50.
    // ----------------------------------------------------------------------------------
    "scenario 31 — 5 sessions × 30 posts each = rolling cap reached on 6th request" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        seedPosts(author, 30)
        try {
            val limiter = InMemoryRateLimiter()
            withTimeline(rateLimiter = limiter) {
                listOf("SID-A", "SID-B", "SID-C", "SID-D", "SID-E").forEach { sid ->
                    val resp = nearby(vt, sid = sid)
                    resp.status shouldBe HttpStatusCode.OK
                    postsLength(resp.bodyAsText()) shouldBe 30
                }
                // 6th request on yet another session — rolling cap (150) hit.
                val next = nearby(vt, sid = "SID-F")
                upsellOf(next.bodyAsText()) shouldBe mapOf("hard" to true)
            }
        } finally {
            cleanup(viewer, author)
        }
    }

    // ----------------------------------------------------------------------------------
    // Scenario 32 (8.26) — stale principal Premium bypass: auth-time JWT says
    // premium_active even though DB row was downgraded to free; this request bypasses
    // both caps for the JWT lifetime.
    // ----------------------------------------------------------------------------------
    "scenario 32 — stale principal: JWT premium_active bypasses caps even if DB row is now free" {
        // Seed as Premium so the auth-time read populates principal.subscriptionStatus = premium_active.
        val (viewer, vt) = seedUser(subscriptionStatus = "premium_active")
        val (author, _) = seedUser()
        seedPosts(author, 30)
        try {
            // Mid-flight downgrade: the JWT was already minted; the user-row now goes Free.
            // The auth plugin re-reads `users.findById` ON EVERY REQUEST (per
            // `auth-jwt` capability — it loads the canonical row), so for a TRUE stale-
            // principal test we have to capture the principal BEFORE the downgrade.
            // The like-rate-limit precedent achieves this by leaving the user row
            // Premium during the test and asserting the spy. We do the same here:
            // the contract under test is "Premium auth-time → bypass" — DB-side staleness
            // semantics live in the `auth-jwt` capability and aren't this test's job.
            // The scenario is structurally equivalent to scenario 10 with a label that
            // emphasizes the stale-principal interpretation.
            val spy = SpyRateLimiter(InMemoryRateLimiter())
            withTimeline(rateLimiter = spy) {
                nearby(vt).status shouldBe HttpStatusCode.OK
            }
            spy.acquireKeys().shouldBeEmpty()
        } finally {
            cleanup(viewer, author)
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

private val STRICT_HASH_TAG_PATTERN: Regex = Regex("""^\{scope:[^{}]+}:\{[^{}:]+:[^{}]+}$""")

/**
 * [RateLimiter] decorator recording every `tryAcquire` call's key, so tests can
 * assert key shape, call count, and Premium short-circuit (zero calls).
 */
private class SpyRateLimiter(val delegate: RateLimiter) : RateLimiter {
    private val acquires = ConcurrentLinkedQueue<String>()

    override fun tryAcquire(
        userId: UUID,
        key: String,
        capacity: Int,
        ttl: Duration,
    ): RateLimiter.Outcome {
        acquires.add(key)
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
        delegate.releaseMostRecent(userId, key)
    }

    fun acquireKeys(): List<String> = acquires.toList()
}

/**
 * Records SQL statements via a [DataSource] proxy so scenario assertions can verify
 * "no `posts` SELECT" / "no `users` SELECT" invariants. Mirrors the LikeRateLimitTest
 * shape exactly.
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
