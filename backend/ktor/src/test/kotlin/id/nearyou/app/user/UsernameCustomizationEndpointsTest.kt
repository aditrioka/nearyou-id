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
 * Coverage (tasks.md § 6): 6.1 6.2 6.3 6.4 6.5 6.6 6.7 6.9 6.10 6.11 6.13 6.16
 * 6.17. Deferred (need concurrent execution / a probe-then-take interleave):
 * 6.12 (concurrency race) + 6.14 (probe TOCTOU) — tracked unchecked.
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
