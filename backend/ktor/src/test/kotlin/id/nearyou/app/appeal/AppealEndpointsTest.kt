package id.nearyou.app.appeal

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.auth.AUTH_PROVIDER_USER
import id.nearyou.app.auth.UserPrincipal
import id.nearyou.app.auth.configureAppealJwt
import id.nearyou.app.auth.configureUserJwt
import id.nearyou.app.auth.jwt.JwtIssuer
import id.nearyou.app.auth.jwt.RsaKeyLoader
import id.nearyou.app.auth.jwt.TestKeys
import id.nearyou.app.guard.ContentLengthGuard
import id.nearyou.app.infra.repo.JdbcUserRepository
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
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
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.sql.Date
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientCN

private fun hikari(): HikariDataSource {
    val url = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5433/nearyou_dev"
    val config =
        HikariConfig().apply {
            jdbcUrl = url
            username = System.getenv("DB_USER") ?: "postgres"
            password = System.getenv("DB_PASSWORD") ?: "postgres"
            maximumPoolSize = 2
            initializationFailTimeout = -1
        }
    return HikariDataSource(config)
}

private fun errorCode(body: String): String {
    val error = Json.parseToJsonElement(body).jsonObject["error"]!!.jsonObject
    return error["code"]!!.jsonPrimitive.content
}

/**
 * Integration tests for the `content-moderation-appeal` route surface + the ban-exempt
 * realm wiring (`POST`/`GET /api/v1/appeals`). Tagged `database` — requires dev Postgres
 * + V34. Per-test `try/finally` cleanup so `autoClose` (pool) is safe.
 */
@Tags("database")
class AppealEndpointsTest : StringSpec({

    val dataSource = autoClose(hikari())
    val keys = RsaKeyLoader(TestKeys.freshEncodedPemPrivateKey(), kid = "test-appeal")
    val jwtIssuer = JwtIssuer(keys)
    val users = JdbcUserRepository(dataSource)

    fun seedUser(
        banned: Boolean = false,
        suspendedUntil: Instant? = null,
        shadowBanned: Boolean = false,
        deletedAt: Instant? = null,
    ): UUID {
        val id = UUID.randomUUID()
        val short = id.toString().replace("-", "").take(8)
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO users (id, username, display_name, date_of_birth, invite_code_prefix,
                                   is_banned, suspended_until, is_shadow_banned, deleted_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "apend_$short")
                ps.setString(3, "Appeal End")
                ps.setDate(4, Date.valueOf(LocalDate.of(1990, 1, 1)))
                ps.setString(5, "e${short.take(7)}")
                ps.setBoolean(6, banned)
                if (suspendedUntil != null) {
                    ps.setTimestamp(7, Timestamp.from(suspendedUntil))
                } else {
                    ps.setNull(7, Types.TIMESTAMP_WITH_TIMEZONE)
                }
                ps.setBoolean(8, shadowBanned)
                if (deletedAt != null) {
                    ps.setTimestamp(9, Timestamp.from(deletedAt))
                } else {
                    ps.setNull(9, Types.TIMESTAMP_WITH_TIMEZONE)
                }
                ps.executeUpdate()
            }
        }
        return id
    }

    fun actionTypeOf(userId: UUID): String? =
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT action_type FROM appeals WHERE user_id = ? ORDER BY created_at DESC LIMIT 1").use { ps ->
                ps.setObject(1, userId)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getString("action_type") else null }
            }
        }

    fun decideLatest(
        userId: UUID,
        status: String,
        reason: String? = null,
    ) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "UPDATE appeals SET status = ?, decision_reason = ?, reviewed_at = NOW() WHERE user_id = ?",
            ).use { ps ->
                ps.setString(1, status)
                if (reason != null) ps.setString(2, reason) else ps.setNull(2, Types.VARCHAR)
                ps.setObject(3, userId)
                ps.executeUpdate()
            }
        }
    }

    fun cleanup(vararg ids: UUID) {
        dataSource.connection.use { conn ->
            ids.forEach { id ->
                conn.prepareStatement("DELETE FROM appeals WHERE user_id = ?").use {
                    it.setObject(1, id)
                    it.executeUpdate()
                }
                conn.prepareStatement("DELETE FROM users WHERE id = ?").use {
                    it.setObject(1, id)
                    it.executeUpdate()
                }
            }
        }
    }

    suspend fun withAppeals(
        rateLimiter: AppealRateLimiter = AppealRateLimiter(),
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) {
        val service = AppealService(users, JdbcAppealRepository(dataSource), rateLimiter)
        val guard = ContentLengthGuard(mapOf("appeal.text" to 1000))
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
                install(Authentication) {
                    configureUserJwt(keys, users, Instant::now)
                    configureAppealJwt(keys, users, Instant::now)
                }
                // A trivial user-realm probe used only to assert scope-confinement.
                routing {
                    authenticate(AUTH_PROVIDER_USER) {
                        get(
                            "/test/user-realm",
                        ) { call.respond(HttpStatusCode.OK, mapOf("ok" to call.principal<UserPrincipal>()!!.userId.toString())) }
                    }
                }
                appealRoutes(service, guard)
            }
            block()
        }
    }

    fun ApplicationTestBuilder.client() = createClient { install(ClientCN) { json() } }

    suspend fun ApplicationTestBuilder.postAppeal(
        token: String,
        text: String,
    ) = client().post("/api/v1/appeals") {
        header(HttpHeaders.Authorization, "Bearer $token")
        contentType(ContentType.Application.Json)
        setBody("""{"appeal_text":"$text"}""")
    }

    suspend fun ApplicationTestBuilder.getAppeal(token: String) =
        client().get("/api/v1/appeals") { header(HttpHeaders.Authorization, "Bearer $token") }

    // ---- Submission ----------------------------------------------------------------

    "suspended user submits → 201, action_type='suspension'" {
        val uid = seedUser(banned = true, suspendedUntil = Instant.now().plus(7, ChronoUnit.DAYS))
        try {
            withAppeals {
                val resp = postAppeal(jwtIssuer.issueAppealToken(uid, 0), "Saya rasa ini keliru, mohon ditinjau.")
                resp.status shouldBe HttpStatusCode.Created
            }
            actionTypeOf(uid) shouldBe "suspension"
        } finally {
            cleanup(uid)
        }
    }

    "permanently-banned user submits → 201, action_type='permanent_ban'" {
        val uid = seedUser(banned = true, suspendedUntil = null)
        try {
            withAppeals {
                val resp = postAppeal(jwtIssuer.issueAppealToken(uid, 0), "Mohon banding atas pemblokiran permanen.")
                resp.status shouldBe HttpStatusCode.Created
            }
            actionTypeOf(uid) shouldBe "permanent_ban"
        } finally {
            cleanup(uid)
        }
    }

    "non-banned user → 409 no_actionable_moderation; shadow-banned-only returns the BYTE-IDENTICAL envelope" {
        val normal = seedUser(banned = false)
        val shadow = seedUser(banned = false, shadowBanned = true)
        try {
            withAppeals {
                val normalResp = postAppeal(jwtIssuer.issueAppealToken(normal, 0), "halo")
                val shadowResp = postAppeal(jwtIssuer.issueAppealToken(shadow, 0), "halo")
                normalResp.status shouldBe HttpStatusCode.Conflict
                shadowResp.status shouldBe HttpStatusCode.Conflict
                val normalBody = normalResp.bodyAsText()
                // No state leak: the shadow-banned-only response is identical to the normal one.
                shadowResp.bodyAsText() shouldBe normalBody
                errorCode(normalBody) shouldBe "no_actionable_moderation"
            }
        } finally {
            cleanup(normal, shadow)
        }
    }

    "second submission while one is pending → 409 appeal_already_pending" {
        val uid = seedUser(banned = true, suspendedUntil = Instant.now().plus(7, ChronoUnit.DAYS))
        try {
            withAppeals {
                postAppeal(jwtIssuer.issueAppealToken(uid, 0), "pertama").status shouldBe HttpStatusCode.Created
                val second = postAppeal(jwtIssuer.issueAppealToken(uid, 0), "kedua")
                second.status shouldBe HttpStatusCode.Conflict
                errorCode(second.bodyAsText()) shouldBe "appeal_already_pending"
            }
        } finally {
            cleanup(uid)
        }
    }

    "concurrent race past the app pre-check: the partial-unique yields exactly one Inserted + one PendingConflict (never 5xx)" {
        val uid = seedUser(banned = true, suspendedUntil = Instant.now().plus(7, ChronoUnit.DAYS))
        try {
            // Two direct inserts model two submissions that both passed the service's
            // app-level one-pending pre-check; the appeals_one_pending_per_user index
            // is the race-safe backstop (23505 → PendingConflict, mapped to 409).
            val repo = JdbcAppealRepository(dataSource)
            val r1 = runBlocking { repo.insertPending(uid, "suspension", "a") }
            val r2 = runBlocking { repo.insertPending(uid, "suspension", "b") }
            val results = listOf(r1, r2)
            results.count { it is JdbcAppealRepository.InsertResult.Inserted } shouldBe 1
            results.count { it is JdbcAppealRepository.InsertResult.PendingConflict } shouldBe 1
        } finally {
            cleanup(uid)
        }
    }

    "client cannot spoof action_type — server derives it from the users row" {
        val uid = seedUser(banned = true, suspendedUntil = Instant.now().plus(7, ChronoUnit.DAYS))
        try {
            withAppeals {
                // Body carries a bogus action_type; the @Serializable DTO ignores it (no such field).
                client().post("/api/v1/appeals") {
                    header(HttpHeaders.Authorization, "Bearer ${jwtIssuer.issueAppealToken(uid, 0)}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"appeal_text":"spoof","action_type":"permanent_ban"}""")
                }.status shouldBe HttpStatusCode.Created
            }
            actionTypeOf(uid) shouldBe "suspension" // server-derived, NOT the client's permanent_ban
        } finally {
            cleanup(uid)
        }
    }

    "daily submission cap → 429 + Retry-After once exceeded (after a prior appeal is decided)" {
        val uid = seedUser(banned = true, suspendedUntil = Instant.now().plus(7, ChronoUnit.DAYS))
        try {
            withAppeals(rateLimiter = AppealRateLimiter(cap = 1)) {
                postAppeal(jwtIssuer.issueAppealToken(uid, 0), "first").status shouldBe HttpStatusCode.Created
                decideLatest(uid, "rejected") // clears the one-pending guard so the next reaches the limiter
                val capped = postAppeal(jwtIssuer.issueAppealToken(uid, 0), "second")
                capped.status shouldBe HttpStatusCode.TooManyRequests
                // The spec's "conveys time-to-reset" half: Retry-After is present + positive.
                val retryAfter = capped.headers[HttpHeaders.RetryAfter]
                (retryAfter != null && retryAfter.toLong() > 0L) shouldBe true
            }
        } finally {
            cleanup(uid)
        }
    }

    // ---- Own-status read -----------------------------------------------------------

    "own-status: no appeal → has_appeal=false; pending → status=pending; rejected → reason + reviewed_at" {
        val none = seedUser(banned = true, suspendedUntil = Instant.now().plus(7, ChronoUnit.DAYS))
        val pending = seedUser(banned = true, suspendedUntil = Instant.now().plus(7, ChronoUnit.DAYS))
        val rejected = seedUser(banned = true, suspendedUntil = Instant.now().plus(7, ChronoUnit.DAYS))
        try {
            withAppeals {
                Json.parseToJsonElement(getAppeal(jwtIssuer.issueAppealToken(none, 0)).bodyAsText())
                    .jsonObject["has_appeal"]!!.jsonPrimitive.content shouldBe "false"

                postAppeal(jwtIssuer.issueAppealToken(pending, 0), "pending appeal")
                Json.parseToJsonElement(getAppeal(jwtIssuer.issueAppealToken(pending, 0)).bodyAsText())
                    .jsonObject["status"]!!.jsonPrimitive.content shouldBe "pending"

                postAppeal(jwtIssuer.issueAppealToken(rejected, 0), "to be rejected")
                decideLatest(rejected, "rejected", reason = "Insufficient grounds.")
                val body = Json.parseToJsonElement(getAppeal(jwtIssuer.issueAppealToken(rejected, 0)).bodyAsText()).jsonObject
                body["status"]!!.jsonPrimitive.content shouldBe "rejected"
                body["decision_reason"]!!.jsonPrimitive.content shouldBe "Insufficient grounds."
            }
        } finally {
            cleanup(none, pending, rejected)
        }
    }

    // ---- Ban-exempt realm (auth-jwt MODIFIED) --------------------------------------

    "appeal realm rejects a stale token_version → 401 token_revoked (the revocation check is NOT relaxed)" {
        val uid = seedUser(banned = true, suspendedUntil = Instant.now().plus(7, ChronoUnit.DAYS))
        try {
            withAppeals {
                // user.token_version defaults to 0; an appeal token claiming v99 is revoked.
                val resp = postAppeal(jwtIssuer.issueAppealToken(uid, 99), "stale")
                resp.status shouldBe HttpStatusCode.Unauthorized
                errorCode(resp.bodyAsText()) shouldBe "token_revoked"
            }
        } finally {
            cleanup(uid)
        }
    }

    "appeal realm rejects a soft-deleted subject → 401 (the deleted_at gate is retained on the ban-exempt realm)" {
        // The appeal realm relaxes ONLY the is_banned/suspended_until short-circuit; the soft-delete gate
        // is the last line of defense against a deleted-but-not-token-bumped account reaching the write
        // path. A matching token_version (0) must NOT be sufficient on the relaxed realm.
        val uid =
            seedUser(
                banned = true,
                suspendedUntil = Instant.now().plus(7, ChronoUnit.DAYS),
                deletedAt = Instant.now().minus(1, ChronoUnit.DAYS),
            )
        try {
            withAppeals {
                postAppeal(jwtIssuer.issueAppealToken(uid, 0), "deleted").status shouldBe HttpStatusCode.Unauthorized
            }
            actionTypeOf(uid) shouldBe null
        } finally {
            cleanup(uid)
        }
    }

    "appeal realm rejects an unauthenticated request → 401, no appeal row" {
        val uid = seedUser(banned = true, suspendedUntil = Instant.now().plus(7, ChronoUnit.DAYS))
        try {
            withAppeals {
                // No Authorization header → the realm's challenge fires before validate.
                client().post("/api/v1/appeals") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"appeal_text":"no token"}""")
                }.status shouldBe HttpStatusCode.Unauthorized
            }
            actionTypeOf(uid) shouldBe null
        } finally {
            cleanup(uid)
        }
    }

    "appeal realm rejects a token signed by a foreign key → 401, no appeal row" {
        val uid = seedUser(banned = true, suspendedUntil = Instant.now().plus(7, ChronoUnit.DAYS))
        try {
            // A different RSA keypair: the realm's RS256 verifier rejects the forged signature before validate runs.
            val foreign = JwtIssuer(RsaKeyLoader(TestKeys.freshEncodedPemPrivateKey(), kid = "foreign"))
            withAppeals {
                postAppeal(foreign.issueAppealToken(uid, 0), "forged").status shouldBe HttpStatusCode.Unauthorized
            }
            actionTypeOf(uid) shouldBe null
        } finally {
            cleanup(uid)
        }
    }

    "scope=appeal token is confined: rejected (401 token_revoked) on a standard realm route, normal token accepted" {
        val uid = seedUser(banned = false)
        try {
            withAppeals {
                val confined =
                    client().get("/test/user-realm") {
                        header(HttpHeaders.Authorization, "Bearer ${jwtIssuer.issueAppealToken(uid, 0)}")
                    }
                confined.status shouldBe HttpStatusCode.Unauthorized
                errorCode(confined.bodyAsText()) shouldBe "token_revoked"
                client().get("/test/user-realm") {
                    header(HttpHeaders.Authorization, "Bearer ${jwtIssuer.issueAccessToken(uid, 0)}")
                }.status shouldBe HttpStatusCode.OK
            }
        } finally {
            cleanup(uid)
        }
    }
})
