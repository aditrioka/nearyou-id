package id.nearyou.app.user

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.auth.configureUserJwt
import id.nearyou.app.auth.jwt.JwtIssuer
import id.nearyou.app.auth.jwt.RsaKeyLoader
import id.nearyou.app.auth.jwt.TestKeys
import id.nearyou.app.config.RemoteConfig
import id.nearyou.app.core.domain.ratelimit.InMemoryRateLimiter
import id.nearyou.app.core.domain.ratelimit.RateLimiter
import id.nearyou.app.infra.repo.JdbcModerationQueueRepository
import id.nearyou.app.infra.repo.JdbcNotificationRepository
import id.nearyou.app.infra.repo.JdbcReservedUsernameRepository
import id.nearyou.app.infra.repo.JdbcUserRepository
import id.nearyou.app.infra.repo.JdbcUsernameFlagOverrideRepository
import id.nearyou.app.moderation.ModerationList
import id.nearyou.app.moderation.ModerationListLoader
import id.nearyou.app.moderation.TextModerator
import id.nearyou.app.notifications.DbNotificationEmitter
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.sql.Date
import java.time.LocalDate
import java.util.UUID

/**
 * Integration tests for `PATCH /api/v1/user/username` + `GET /api/v1/username/check`
 * (`premium-username-customization` § specs). Drives the route → service →
 * repository stack against real Postgres. Mirrors `SearchEndpointsTest`:
 * in-memory limiter, a stub RemoteConfig per test for the feature flag, a
 * `FixedListLoader` to inject a moderation hit. Tagged `database`.
 *
 * Coverage (tasks.md § 6): 6.1–6.14, 6.16, 6.17 (6.15 is the pool-size
 * invariant, held by `hikari()` below). The once-deferred 6.8 (downgrade /
 * re-subscribe), 6.12 (concurrency race) and 6.14 (probe TOCTOU) landed via
 * issue #310.
 */
@Tags("database")
class UsernameCustomizationEndpointsTest : StringSpec({

    val dataSource = autoClose(hikari())
    val keys = RsaKeyLoader(TestKeys.freshEncodedPemPrivateKey(), kid = "test-username")
    val jwtIssuer = JwtIssuer(keys)
    val authUsers = JdbcUserRepository(dataSource)

    fun seedUser(
        subscriptionStatus: String = "premium_active",
        username: String? = null,
        lastChangedDaysAgo: Long? = null,
    ): Triple<UUID, String, String> {
        val id = UUID.randomUUID()
        val short = id.toString().replace("-", "").take(8)
        val name = username ?: "user_$short"
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO users (
                    id, username, display_name, date_of_birth, invite_code_prefix,
                    subscription_status, username_last_changed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ${if (lastChangedDaysAgo == null) "NULL" else "NOW() - INTERVAL '$lastChangedDaysAgo days'"})
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setString(2, name)
                ps.setString(3, "Tester $short")
                ps.setDate(4, Date.valueOf(LocalDate.of(1990, 1, 1)))
                ps.setString(5, "k${short.take(7)}")
                ps.setString(6, subscriptionStatus)
                ps.executeUpdate()
            }
        }
        return Triple(id, jwtIssuer.issueAccessToken(id, tokenVersion = 0), name)
    }

    fun seedReserved(username: String) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "INSERT INTO reserved_usernames (username, reason, source) VALUES (?, 'test', 'admin_added') ON CONFLICT DO NOTHING",
            ).use { ps ->
                ps.setString(1, username)
                ps.executeUpdate()
            }
        }
    }

    fun seedHistoryHold(
        userId: UUID,
        oldUsername: String,
        releasedDaysFromNow: Long,
    ) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "INSERT INTO username_history (user_id, old_username, new_username, released_at) " +
                    "VALUES (?, ?, ?, NOW() + INTERVAL '$releasedDaysFromNow days')",
            ).use { ps ->
                ps.setObject(1, userId)
                ps.setString(2, oldUsername)
                ps.setString(3, "whatever_$oldUsername")
                ps.executeUpdate()
            }
        }
    }

    /**
     * Seed a per-candidate admin override (the admin accept side-effect). `candidate`
     * is stored normalized lowercase to mirror the writer. `consumed` pre-spends it.
     */
    fun seedOverride(
        userId: UUID,
        candidate: String,
        consumed: Boolean = false,
    ) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "INSERT INTO username_flag_overrides (user_id, candidate, approved_by, consumed_at) " +
                    "VALUES (?, LOWER(?), NULL, ${if (consumed) "NOW()" else "NULL"})",
            ).use { ps ->
                ps.setObject(1, userId)
                ps.setString(2, candidate)
                ps.executeUpdate()
            }
        }
    }

    /** notes (the persisted flagged candidate) of the standing username_flagged row, or null. */
    fun flaggedNotes(userId: UUID): String? =
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT notes FROM moderation_queue WHERE target_id = ? AND target_type = 'user' " +
                    "AND trigger = 'username_flagged'",
            ).use { ps ->
                ps.setObject(1, userId)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

    fun currentUsername(userId: UUID): String =
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT username FROM users WHERE id = ?").use { ps ->
                ps.setObject(1, userId)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getString(1)
                }
            }
        }

    fun count(sql: String): Int =
        dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(sql).use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    fun cleanup(vararg ids: UUID) {
        dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                ids.forEach {
                    st.executeUpdate("DELETE FROM notifications WHERE user_id = '$it'")
                    st.executeUpdate("DELETE FROM moderation_queue WHERE target_id = '$it'")
                    st.executeUpdate("DELETE FROM username_history WHERE user_id = '$it'")
                    st.executeUpdate("DELETE FROM username_flag_overrides WHERE user_id = '$it'")
                    st.executeUpdate("DELETE FROM users WHERE id = '$it'")
                }
            }
        }
    }

    suspend fun withApp(
        flagEnabled: Boolean? = null,
        rateLimiter: RateLimiter = InMemoryRateLimiter(),
        profanity: List<String> = emptyList(),
        uuIte: List<String> = emptyList(),
        block: suspend ApplicationTestBuilder.(UsernameRateLimiter) -> Unit,
    ) {
        val limiter = UsernameRateLimiter(rateLimiter = rateLimiter)
        val remoteConfig =
            object : RemoteConfig {
                override fun getLong(key: String): Long? = null

                override fun getBoolean(key: String): Boolean? = if (key == UsernameChangeService.FEATURE_FLAG_KEY) flagEnabled else null
            }
        val service =
            UsernameChangeService(
                dataSource = dataSource,
                users = JdbcUserRepository(dataSource),
                reserved = JdbcReservedUsernameRepository(dataSource),
                history = JdbcUsernameHistoryRepository(),
                moderationQueue = JdbcModerationQueueRepository(),
                flagOverrides = JdbcUsernameFlagOverrideRepository(),
                textModerator = TextModerator(FixedListLoader(profanity, uuIte)),
                notificationEmitter = DbNotificationEmitter(JdbcNotificationRepository(dataSource)),
                remoteConfig = remoteConfig,
                rateLimiter = limiter,
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
                install(Authentication) { configureUserJwt(keys, authUsers, java.time.Instant::now) }
                userUsernameRoutes(service)
            }
            block(limiter)
        }
    }

    suspend fun ApplicationTestBuilder.patchUsername(
        token: String?,
        newUsername: String,
    ): HttpResponse =
        createClient { }.patch("/api/v1/user/username") {
            if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.ContentType, "application/json")
            setBody("""{"new_username":"$newUsername"}""")
        }

    suspend fun ApplicationTestBuilder.checkUsername(
        token: String?,
        candidate: String,
    ): HttpResponse =
        createClient { }.get("/api/v1/username/check?candidate=${URLEncoder.encode(candidate, StandardCharsets.UTF_8)}") {
            if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
        }

    fun parse(body: String): JsonObject = Json.parseToJsonElement(body).jsonObject

    fun JsonObject.error(): String = this["error"]!!.jsonPrimitive.content

    // 6.16 — auth boundary
    "6.16 unauthenticated PATCH + GET → 401" {
        withApp { _ ->
            patchUsername(null, "newhandle").status shouldBe HttpStatusCode.Unauthorized
            checkUsername(null, "newhandle").status shouldBe HttpStatusCode.Unauthorized
        }
    }

    // 6.1 — Free paywall
    "6.1 Free user → 403 premium_required (PATCH + GET)" {
        val (id, tok, _) = seedUser(subscriptionStatus = "free")
        try {
            withApp { _ ->
                val r = patchUsername(tok, "freshhandle")
                r.status shouldBe HttpStatusCode.Forbidden
                parse(r.bodyAsText()).error() shouldBe "premium_required"
                checkUsername(tok, "freshhandle").status shouldBe HttpStatusCode.Forbidden
            }
        } finally {
            cleanup(id)
        }
    }

    // 6.7 — flag kill switch
    "6.7 feature flag OFF → 503 (PATCH + GET)" {
        val (id, tok, _) = seedUser()
        try {
            withApp(flagEnabled = false) { _ ->
                patchUsername(tok, "freshhandle").status shouldBe HttpStatusCode.ServiceUnavailable
                checkUsername(tok, "freshhandle").status shouldBe HttpStatusCode.ServiceUnavailable
            }
        } finally {
            cleanup(id)
        }
    }

    // 6.9 — format matrix
    "6.9 invalid formats → 422 invalid_username; valid → proceeds" {
        val (id, tok, _) = seedUser()
        try {
            withApp { _ ->
                listOf(".abc", "abc.", "a..b", "_abc", "ab", "ABCdef", "x".repeat(31)).forEach { bad ->
                    val r = checkUsername(tok, bad)
                    r.status shouldBe HttpStatusCode.UnprocessableEntity
                    parse(r.bodyAsText()).error() shouldBe "invalid_username"
                }
                // valid forms pass format → probe returns 200 available
                listOf("abc", "a_b.c", "user1.test_2").forEach { ok ->
                    checkUsername(tok, ok).status shouldBe HttpStatusCode.OK
                }
            }
        } finally {
            cleanup(id)
        }
    }

    // 6.2 + 6.11 — success path
    "6.2+6.11 premium change succeeds → 200, username + last_changed updated, history + notification written" {
        val (id, tok, old) = seedUser(subscriptionStatus = "premium_active")
        try {
            withApp { _ ->
                val r = patchUsername(tok, "brandnew.handle")
                r.status shouldBe HttpStatusCode.OK
                parse(r.bodyAsText())["username"]!!.jsonPrimitive.content shouldBe "brandnew.handle"
            }
            currentUsername(id) shouldBe "brandnew.handle"
            count("SELECT COUNT(*) FROM users WHERE id = '$id' AND username_last_changed_at IS NOT NULL") shouldBe 1
            count(
                "SELECT COUNT(*) FROM username_history WHERE user_id = '$id' AND old_username = '$old' " +
                    "AND released_at > NOW() + INTERVAL '29 days'",
            ) shouldBe 1
            count(
                "SELECT COUNT(*) FROM notifications WHERE user_id = '$id' AND type = 'username_release_scheduled'",
            ) shouldBe 1
        } finally {
            cleanup(id)
        }
    }

    "6.2 premium_billing_retry also treated as Premium → 200" {
        val (id, tok, _) = seedUser(subscriptionStatus = "premium_billing_retry")
        try {
            withApp { _ -> patchUsername(tok, "retryhandle").status shouldBe HttpStatusCode.OK }
        } finally {
            cleanup(id)
        }
    }

    // 6.3 — cooldown
    "6.3 change within 30-day cooldown → 429; exactly-30-days-ago → allowed" {
        val (id1, tok1, _) = seedUser(lastChangedDaysAgo = 5)
        val (id2, tok2, _) = seedUser(lastChangedDaysAgo = 30)
        try {
            withApp { _ ->
                val r = patchUsername(tok1, "tooearly")
                r.status shouldBe HttpStatusCode.TooManyRequests
                parse(r.bodyAsText()).error() shouldBe "cooldown_active"
                r.headers[HttpHeaders.RetryAfter]!!.toLong() shouldBeGreaterThan 0
                // exactly 30 days ago → inclusive boundary → allowed
                patchUsername(tok2, "oknow.handle").status shouldBe HttpStatusCode.OK
            }
        } finally {
            cleanup(id1, id2)
        }
    }

    // 6.8 — downgrade keeps the username + blocks changes; re-subscribe re-enables
    // but the cooldown is still measured from the last actual change.
    "6.8 downgrade keeps username, blocks changes (403); re-subscribe re-enables but cooldown persists" {
        val (id, tok, _) = seedUser()

        fun setTier(status: String) {
            dataSource.connection.use { conn ->
                conn.prepareStatement("UPDATE users SET subscription_status = ? WHERE id = ?").use { ps ->
                    ps.setString(1, status)
                    ps.setObject(2, id)
                    ps.executeUpdate()
                }
            }
        }
        try {
            withApp { _ ->
                patchUsername(tok, "keptcustom").status shouldBe HttpStatusCode.OK
                setTier("free")
                // no revert path exists — the custom handle survives the downgrade
                currentUsername(id) shouldBe "keptcustom"
                val r = patchUsername(tok, "freeattempt")
                r.status shouldBe HttpStatusCode.Forbidden
                parse(r.bodyAsText()).error() shouldBe "premium_required"
                setTier("premium_active")
                // re-enabled, but the 30-day cooldown still runs from the change above
                val r2 = patchUsername(tok, "resubattempt")
                r2.status shouldBe HttpStatusCode.TooManyRequests
                parse(r2.bodyAsText()).error() shouldBe "cooldown_active"
                r2.headers[HttpHeaders.RetryAfter]!!.toLong() shouldBeGreaterThan 0
            }
            currentUsername(id) shouldBe "keptcustom"
        } finally {
            cleanup(id)
        }
    }

    // 6.4 — collision (reserved + taken)
    "6.4 reserved candidate + currently-taken candidate → 409 username_unavailable" {
        val (id, tok, _) = seedUser()
        val (other, _, otherName) = seedUser(username = "taken_one")
        seedReserved("reservedword")
        try {
            withApp { _ ->
                val r1 = patchUsername(tok, "reservedword")
                r1.status shouldBe HttpStatusCode.Conflict
                parse(r1.bodyAsText()).error() shouldBe "username_unavailable"
                patchUsername(tok, otherName).status shouldBe HttpStatusCode.Conflict
            }
        } finally {
            cleanup(id, other)
            dataSource.connection.use {
                it.createStatement().executeUpdate(
                    "DELETE FROM reserved_usernames WHERE username = 'reservedword'",
                )
            }
        }
    }

    // 6.12 — concurrency race: two DIFFERENT users PATCH the same free handle at
    // once. Unlike 8.4's same-user race (serialized by the per-user FOR UPDATE
    // lock), these transactions only collide at the final write — the `username`
    // unique index is the backstop that yields exactly one winner.
    "6.12 two users concurrently PATCH the same handle → exactly one 200, the other 409" {
        val (id1, tok1, _) = seedUser()
        val (id2, tok2, _) = seedUser()
        try {
            withApp { _ ->
                val results =
                    coroutineScope {
                        val client = createClient { }
                        listOf(tok1, tok2).map { t ->
                            async(Dispatchers.IO) {
                                val r =
                                    client.patch("/api/v1/user/username") {
                                        header(HttpHeaders.Authorization, "Bearer $t")
                                        header(HttpHeaders.ContentType, "application/json")
                                        setBody("""{"new_username":"duelhandle"}""")
                                    }
                                r.status to r.bodyAsText()
                            }
                        }.awaitAll()
                    }
                results.count { it.first == HttpStatusCode.OK } shouldBe 1
                val loser = results.first { it.first != HttpStatusCode.OK }
                loser.first shouldBe HttpStatusCode.Conflict
                parse(loser.second).error() shouldBe "username_unavailable"
            }
            // exactly one row holds the handle
            count("SELECT COUNT(*) FROM users WHERE username = 'duelhandle'") shouldBe 1
        } finally {
            cleanup(id1, id2)
        }
    }

    // 6.5 + 6.10 — release hold
    "6.5+6.10 candidate on active release hold → 409; expired hold → claimable" {
        val (id, tok, _) = seedUser()
        seedHistoryHold(id, oldUsername = "heldname", releasedDaysFromNow = 10)
        seedHistoryHold(id, oldUsername = "freedname", releasedDaysFromNow = -1)
        try {
            withApp { _ ->
                patchUsername(tok, "heldname").status shouldBe HttpStatusCode.Conflict
                patchUsername(tok, "freedname").status shouldBe HttpStatusCode.OK
            }
        } finally {
            cleanup(id)
        }
    }

    // 6.6 — moderation
    "6.6 moderation hit → 422 username_rejected + idempotent username_flagged queue row" {
        val (id, tok, _) = seedUser()
        try {
            withApp(profanity = listOf("badword")) { _ ->
                val r1 = patchUsername(tok, "badword")
                r1.status shouldBe HttpStatusCode.UnprocessableEntity
                parse(r1.bodyAsText()).error() shouldBe "username_rejected"
                // second flagged candidate → ON CONFLICT DO NOTHING (no 2nd row)
                patchUsername(tok, "badword").status shouldBe HttpStatusCode.UnprocessableEntity
            }
            count(
                "SELECT COUNT(*) FROM moderation_queue WHERE target_id = '$id' " +
                    "AND target_type = 'user' AND trigger = 'username_flagged'",
            ) shouldBe 1
            currentUsername(id).startsWith("user_") shouldBe true // unchanged
        } finally {
            cleanup(id)
        }
    }

    // 6.17 — probe
    "6.17 probe reports available / unavailable" {
        val (id, tok, _) = seedUser()
        seedReserved("probetaken")
        try {
            withApp { _ ->
                parse(checkUsername(tok, "freshprobe").bodyAsText())["available"]!!.jsonPrimitive.content shouldBe "true"
                parse(checkUsername(tok, "probetaken").bodyAsText())["available"]!!.jsonPrimitive.content shouldBe "false"
            }
        } finally {
            cleanup(id)
            dataSource.connection.use { it.createStatement().executeUpdate("DELETE FROM reserved_usernames WHERE username = 'probetaken'") }
        }
    }

    // 6.14 — probe TOCTOU: availability at probe time carries no reservation. A
    // candidate probed available, then taken before the prober's PATCH, fails the
    // authoritative under-lock recheck with 409.
    "6.14 probe says available, another user takes it first → prober's PATCH 409" {
        val (idA, tokA, _) = seedUser()
        val (idB, tokB, _) = seedUser()
        try {
            withApp { _ ->
                parse(checkUsername(tokA, "poachable").bodyAsText())["available"]!!.jsonPrimitive.content shouldBe "true"
                // B claims it between A's probe and A's PATCH
                patchUsername(tokB, "poachable").status shouldBe HttpStatusCode.OK
                val r = patchUsername(tokA, "poachable")
                r.status shouldBe HttpStatusCode.Conflict
                parse(r.bodyAsText()).error() shouldBe "username_unavailable"
            }
            currentUsername(idB) shouldBe "poachable"
            currentUsername(idA).startsWith("user_") shouldBe true // A unchanged
        } finally {
            cleanup(idA, idB)
        }
    }

    // 6.13 — rate limits + key form
    "6.13 keyFor canonical forms" {
        val u = UUID.fromString("12345678-1234-1234-1234-123456789012")
        UsernameRateLimiter.failedChangeKey(u) shouldBe "{scope:rate_username_change}:{user:12345678-1234-1234-1234-123456789012}"
        UsernameRateLimiter.probeKey(u) shouldBe "{scope:rate_username_probe_day}:{user:12345678-1234-1234-1234-123456789012}"
    }

    "6.13 4th probe/day → 429" {
        val (id, tok, _) = seedUser()
        try {
            withApp(rateLimiter = InMemoryRateLimiter()) { _ ->
                repeat(3) { checkUsername(tok, "probe$it").status shouldBe HttpStatusCode.OK }
                checkUsername(tok, "probe4").status shouldBe HttpStatusCode.TooManyRequests
            }
        } finally {
            cleanup(id)
        }
    }

    "6.13 11th failed change attempt/hour → 429; successes do not count" {
        val (id, tok, _) = seedUser()
        try {
            withApp { _ ->
                // 10 failures (taken/reserved-style: use reserved to force failures)
                seedReserved("failword")
                repeat(10) { patchUsername(tok, "failword").status shouldBe HttpStatusCode.Conflict }
                // 11th attempt (even a valid one) → throttled
                patchUsername(tok, "wouldbevalid").status shouldBe HttpStatusCode.TooManyRequests
            }
        } finally {
            cleanup(id)
            dataSource.connection.use { it.createStatement().executeUpdate("DELETE FROM reserved_usernames WHERE username = 'failword'") }
        }
    }

    // ---- 8.4 coupling: admin override (username_flag_overrides) ----

    // A non-consumed override skips moderation, the change succeeds, and the override
    // is consumed inside the successful-change transaction.
    "8.4 non-consumed override → moderation skipped, change succeeds (200), consumed_at set" {
        val (id, tok, _) = seedUser()
        seedOverride(id, "borderlinehandle")
        try {
            withApp(profanity = listOf("borderlinehandle")) { _ ->
                val r = patchUsername(tok, "borderlinehandle")
                r.status shouldBe HttpStatusCode.OK
                parse(r.bodyAsText())["username"]!!.jsonPrimitive.content shouldBe "borderlinehandle"
            }
            currentUsername(id) shouldBe "borderlinehandle"
            // override row consumed
            count(
                "SELECT COUNT(*) FROM username_flag_overrides WHERE user_id = '$id' " +
                    "AND candidate = 'borderlinehandle' AND consumed_at IS NOT NULL",
            ) shouldBe 1
            // a successful override change is NOT a moderation reject → no standing flag
            count(
                "SELECT COUNT(*) FROM moderation_queue WHERE target_id = '$id' AND trigger = 'username_flagged'",
            ) shouldBe 0
        } finally {
            cleanup(id)
        }
    }

    // The override is stored normalized lowercase (the admin writer's LOWER(?)); the
    // gate consults with the lowercased candidate, so a mixed-case approval still
    // matches the user's (necessarily-lowercase) submission.
    "8.4 override stored mixed-case (normalized lowercase) still matches the submission" {
        val (id, tok, _) = seedUser()
        seedOverride(id, "MixedCaseApproved") // stored as 'mixedcaseapproved' via LOWER()
        try {
            withApp(profanity = listOf("mixedcaseapproved")) { _ ->
                patchUsername(tok, "mixedcaseapproved").status shouldBe HttpStatusCode.OK
            }
            count(
                "SELECT COUNT(*) FROM username_flag_overrides WHERE user_id = '$id' " +
                    "AND candidate = 'mixedcaseapproved' AND consumed_at IS NOT NULL",
            ) shouldBe 1
        } finally {
            cleanup(id)
        }
    }

    // A consumed override grants no pass; an absent override grants no pass — both 422.
    "8.4 consumed override OR absent override → still 422 username_rejected" {
        val (idC, tokC, _) = seedUser()
        seedOverride(idC, "spenthandle", consumed = true)
        val (idA, tokA, _) = seedUser()
        try {
            withApp(profanity = listOf("spenthandle", "neverapproved")) { _ ->
                // consumed override → no pass
                val rC = patchUsername(tokC, "spenthandle")
                rC.status shouldBe HttpStatusCode.UnprocessableEntity
                parse(rC.bodyAsText()).error() shouldBe "username_rejected"
                // absent override → no pass
                val rA = patchUsername(tokA, "neverapproved")
                rA.status shouldBe HttpStatusCode.UnprocessableEntity
                parse(rA.bodyAsText()).error() shouldBe "username_rejected"
            }
            currentUsername(idC).startsWith("user_") shouldBe true
            currentUsername(idA).startsWith("user_") shouldBe true
        } finally {
            cleanup(idC, idA)
        }
    }

    // Per-candidate scope: an override for X does NOT let a DIFFERENT flagged Y through.
    "8.4 override for X does not grant a pass to a different flagged Y" {
        val (id, tok, _) = seedUser()
        seedOverride(id, "approvedx")
        try {
            withApp(profanity = listOf("approvedx", "flaggedy")) { _ ->
                // Y is flagged + has NO override → rejected
                val r = patchUsername(tok, "flaggedy")
                r.status shouldBe HttpStatusCode.UnprocessableEntity
                parse(r.bodyAsText()).error() shouldBe "username_rejected"
            }
            // X's override remains unconsumed (Y never touched it)
            count(
                "SELECT COUNT(*) FROM username_flag_overrides WHERE user_id = '$id' " +
                    "AND candidate = 'approvedx' AND consumed_at IS NULL",
            ) shouldBe 1
            // Y re-opened the standing flag with notes = 'flaggedy'
            flaggedNotes(id) shouldBe "flaggedy"
        } finally {
            cleanup(id)
        }
    }

    // A non-consumed override for X does NOT get spent when X is already TAKEN by
    // another user: the collision gate (409) fires BEFORE the override consume inside
    // the FOR UPDATE block, so consumed_at stays NULL and the approved handle remains
    // claimable once it frees up.
    "8.4 override preserved when candidate is taken → 409 + override consumed_at stays NULL" {
        val (id, tok, _) = seedUser()
        val (other, _, otherName) = seedUser(username = "approvedtaken")
        seedOverride(id, otherName) // admin pre-approved 'approvedtaken' for this user
        try {
            withApp(profanity = listOf(otherName)) { _ ->
                val r = patchUsername(tok, otherName)
                r.status shouldBe HttpStatusCode.Conflict
                parse(r.bodyAsText()).error() shouldBe "username_unavailable"
            }
            currentUsername(id).startsWith("user_") shouldBe true // unchanged
            // override survives the collision — still claimable once the handle frees up
            count(
                "SELECT COUNT(*) FROM username_flag_overrides WHERE user_id = '$id' " +
                    "AND candidate = '$otherName' AND consumed_at IS NULL",
            ) shouldBe 1
        } finally {
            cleanup(id, other)
        }
    }

    // The flagged upsert re-opens a resolved standing row with the LATEST candidate.
    "8.4 flagged upsert re-opens a resolved row with the latest candidate in notes" {
        val (id, tok, _) = seedUser()
        try {
            withApp(profanity = listOf("firstbad", "secondbad")) { _ ->
                patchUsername(tok, "firstbad").status shouldBe HttpStatusCode.UnprocessableEntity
            }
            flaggedNotes(id) shouldBe "firstbad"
            // Admin resolves the standing row (simulate an accept/reject resolution).
            dataSource.connection.use { conn ->
                conn.prepareStatement(
                    "UPDATE moderation_queue SET status='resolved', resolution='reject_flagged_username', " +
                        "resolved_at=NOW() WHERE target_id = ? AND trigger='username_flagged'",
                ).use { ps ->
                    ps.setObject(1, id)
                    ps.executeUpdate()
                }
            }
            // A NEW flagged attempt must re-open the row (status=pending, resolution cleared)
            // with the latest candidate — NOT be swallowed by the resolved row.
            withApp(profanity = listOf("firstbad", "secondbad")) { _ ->
                patchUsername(tok, "secondbad").status shouldBe HttpStatusCode.UnprocessableEntity
            }
            flaggedNotes(id) shouldBe "secondbad"
            count(
                "SELECT COUNT(*) FROM moderation_queue WHERE target_id = '$id' AND trigger='username_flagged' " +
                    "AND status='pending' AND resolution IS NULL",
            ) shouldBe 1
            // still exactly one standing row (UNIQUE holds; re-open, not insert)
            count(
                "SELECT COUNT(*) FROM moderation_queue WHERE target_id = '$id' AND trigger='username_flagged'",
            ) shouldBe 1
        } finally {
            cleanup(id)
        }
    }

    // Concurrent same-user double-submit on a single override → at most one pass.
    // The two PATCHes serialize on the per-user FOR UPDATE lock; the conditional
    // rows-affected-gated consume (WHERE consumed_at IS NULL) is what enforces the
    // one-shot. The loser — acquiring the lock AFTER the winner commits — is rejected
    // via whichever gate fires first under the lock: cooldown (429, the winner's
    // successful change just stamped username_last_changed_at), the now-taken handle
    // (409), or re-moderation after a zero-row consume (422). Never a second 200.
    "8.4 concurrent same-user submit → override consumed at most once; loser rejected" {
        val (id, tok, _) = seedUser()
        seedOverride(id, "raceword")
        try {
            withApp(profanity = listOf("raceword")) { _ ->
                val statuses =
                    coroutineScope {
                        val client = createClient { }
                        (1..2).map {
                            async(Dispatchers.IO) {
                                client.patch("/api/v1/user/username") {
                                    header(HttpHeaders.Authorization, "Bearer $tok")
                                    header(HttpHeaders.ContentType, "application/json")
                                    setBody("""{"new_username":"raceword"}""")
                                }.status
                            }
                        }.awaitAll()
                    }
                // exactly one 200 success; the other is rejected (any non-200)
                statuses.count { it == HttpStatusCode.OK } shouldBe 1
                statuses.count { it != HttpStatusCode.OK } shouldBe 1
            }
            currentUsername(id) shouldBe "raceword"
            // the override was consumed AT MOST ONCE — exactly the winner spent it; the
            // loser's WHERE consumed_at IS NULL matched zero rows (one-shot holds).
            count("SELECT COUNT(*) FROM username_flag_overrides WHERE user_id = '$id' AND consumed_at IS NOT NULL") shouldBe 1
        } finally {
            cleanup(id)
        }
    }

    // The repeated-flagged throttle still applies: a flagged attempt counts as a failed
    // attempt against the 10/hour limit, and the standing row reflects the latest candidate.
    "8.4 repeated flagged attempts count against the 10-failed/hour limit; standing row tracks latest" {
        val (id, tok, _) = seedUser()
        val flagged = (0..10).map { "flagged$it" }
        try {
            withApp(profanity = flagged) { _ ->
                // 10 flagged attempts (each a failed attempt) all → 422
                repeat(10) { i ->
                    patchUsername(tok, "flagged$i").status shouldBe HttpStatusCode.UnprocessableEntity
                }
                // 11th attempt → throttled by the failed-attempt limiter (not moderation)
                patchUsername(tok, "flagged10").status shouldBe HttpStatusCode.TooManyRequests
            }
            // standing row reflects the latest candidate that actually ran the gate (flagged9;
            // the 11th was throttled before moderation)
            flaggedNotes(id) shouldBe "flagged9"
        } finally {
            cleanup(id)
        }
    }
})

private class FixedListLoader(
    private val profanity: List<String>,
    private val uuIte: List<String>,
    private val threshold: Int = 3,
) : ModerationListLoader {
    override fun load(list: ModerationList): List<String> =
        when (list) {
            ModerationList.ProfanityList -> profanity
            ModerationList.UuIteList -> uuIte
        }

    override fun loadThreshold(): Int = threshold
}

private fun hikari(): HikariDataSource {
    val url = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5433/nearyou_dev"
    val user = System.getenv("DB_USER") ?: "postgres"
    val password = System.getenv("DB_PASSWORD") ?: "postgres"
    return HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = url
            username = user
            this.password = password
            maximumPoolSize = 2
            initializationFailTimeout = -1
        },
    )
}
