package id.nearyou.app.referral

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.auth.configureUserJwt
import id.nearyou.app.auth.jwt.JwtIssuer
import id.nearyou.app.auth.jwt.RsaKeyLoader
import id.nearyou.app.auth.jwt.TestKeys
import id.nearyou.app.infra.repo.JdbcUserRepository
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.sql.Date
import java.time.LocalDate
import java.util.UUID
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
            // Frugal pool + autoClose (ConsentRoutesTest precedent / PR #157 CI connection budget).
            maximumPoolSize = 2
            minimumIdle = 0
            initializationFailTimeout = -1
        }
    return HikariDataSource(config)
}

/**
 * DB-tagged coverage of `GET /api/v1/user/referral` (the `referral-read` capability). Mirrors
 * `HideDistanceRoutesTest`: a frugal `autoClose`d pool (docs/11 §3.2 connection budget), JWT minted by
 * the test issuer, and a seed that creates NO posts (so it cannot pollute the Global/Nearby timeline
 * suites). The fail-soft `500` is exercised by registering the route with a THROWING stub repository
 * (not a brittle real-DB fault); the happy paths use the real `JdbcReferralReadRepository`.
 */
@Tags("database")
class ReferralReadRoutesTest : StringSpec({

    val dataSource = autoClose(hikari())
    val keys = RsaKeyLoader(TestKeys.freshEncodedPemPrivateKey(), kid = "test-referral-read")
    val jwtIssuer = JwtIssuer(keys)
    val users = JdbcUserRepository(dataSource)
    val repository = JdbcReferralReadRepository(dataSource)

    /** Seeds a user (no posts) and returns its id + invite-code prefix + a minted access token. */
    fun seedUser(): Triple<UUID, String, String> {
        val id = UUID.randomUUID()
        val short = id.toString().replace("-", "").take(8)
        val invitePrefix = "r${short.take(7)}"
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO users (id, username, display_name, date_of_birth, invite_code_prefix)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "rr_$short")
                ps.setString(3, "Referral Reader")
                ps.setDate(4, Date.valueOf(LocalDate.of(1990, 1, 1)))
                ps.setString(5, invitePrefix)
                ps.executeUpdate()
            }
        }
        return Triple(id, invitePrefix, jwtIssuer.issueAccessToken(id, tokenVersion = 0))
    }

    /** Inserts [count] DISTINCT invitee users + a `granted` ticket each for [inviterId]. Returns the invitees. */
    fun seedGrantedTickets(
        inviterId: UUID,
        count: Int,
    ): List<UUID> =
        buildList {
            repeat(count) {
                val inviteeId = UUID.randomUUID()
                val short = inviteeId.toString().replace("-", "").take(8)
                dataSource.connection.use { conn ->
                    conn.prepareStatement(
                        """
                        INSERT INTO users (id, username, display_name, date_of_birth, invite_code_prefix)
                        VALUES (?, ?, ?, ?, ?)
                        """.trimIndent(),
                    ).use { ps ->
                        ps.setObject(1, inviteeId)
                        ps.setString(2, "iv_$short")
                        ps.setString(3, "Invitee")
                        ps.setDate(4, Date.valueOf(LocalDate.of(1991, 2, 2)))
                        ps.setString(5, "v${short.take(7)}")
                        ps.executeUpdate()
                    }
                    conn.prepareStatement(
                        """
                        INSERT INTO referral_tickets (inviter_user_id, invitee_user_id, status, expires_at)
                        VALUES (?, ?, 'granted', NOW() + INTERVAL '14 days')
                        """.trimIndent(),
                    ).use { ps ->
                        ps.setObject(1, inviterId)
                        ps.setObject(2, inviteeId)
                        ps.executeUpdate()
                    }
                }
                add(inviteeId)
            }
        }

    fun setRewardClaimed(userId: UUID) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("UPDATE users SET inviter_reward_claimed_at = NOW() WHERE id = ?").use { ps ->
                ps.setObject(1, userId)
                ps.executeUpdate()
            }
        }
    }

    fun cleanup(vararg ids: UUID) {
        dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                // referral_tickets cascade on user delete (V23 FK ON DELETE CASCADE).
                ids.forEach { st.executeUpdate("DELETE FROM users WHERE id = '$it'") }
            }
        }
    }

    suspend fun withReferralRoutes(
        repo: ReferralReadRepository = repository,
        block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit,
    ) {
        testApplication {
            application {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                install(Authentication) { configureUserJwt(keys, users, java.time.Instant::now) }
                referralReadRoutes(repo)
            }
            block()
        }
    }

    "authenticated GET returns the caller's code + zero/unclaimed progress" {
        val (uid, prefix, jwt) = seedUser()
        try {
            withReferralRoutes {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/user/referral") { header(HttpHeaders.Authorization, "Bearer $jwt") }
                resp.status shouldBe HttpStatusCode.OK
                val obj = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
                obj["inviteCode"]!!.jsonPrimitive.content shouldBe prefix
                obj["grantedReferrals"]!!.jsonPrimitive.int shouldBe 0
                obj["milestone"]!!.jsonPrimitive.int shouldBe 5
                obj["inviterRewardClaimed"]!!.jsonPrimitive.boolean shouldBe false
            }
        } finally {
            cleanup(uid)
        }
    }

    "the response reflects the seeded granted-ticket count" {
        val (uid, _, jwt) = seedUser()
        val invitees = seedGrantedTickets(uid, count = 3)
        try {
            withReferralRoutes {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/user/referral") { header(HttpHeaders.Authorization, "Bearer $jwt") }
                resp.status shouldBe HttpStatusCode.OK
                val obj = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
                obj["grantedReferrals"]!!.jsonPrimitive.int shouldBe 3
                obj["milestone"]!!.jsonPrimitive.int shouldBe 5
                obj["inviterRewardClaimed"]!!.jsonPrimitive.boolean shouldBe false
            }
        } finally {
            cleanup(uid, *invitees.toTypedArray())
        }
    }

    "a claimed inviter reward is reported as inviterRewardClaimed=true" {
        val (uid, _, jwt) = seedUser()
        val invitees = seedGrantedTickets(uid, count = 5)
        setRewardClaimed(uid)
        try {
            withReferralRoutes {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/user/referral") { header(HttpHeaders.Authorization, "Bearer $jwt") }
                resp.status shouldBe HttpStatusCode.OK
                val obj = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
                obj["grantedReferrals"]!!.jsonPrimitive.int shouldBe 5
                obj["inviterRewardClaimed"]!!.jsonPrimitive.boolean shouldBe true
            }
        } finally {
            cleanup(uid, *invitees.toTypedArray())
        }
    }

    "missing Authorization → 401" {
        withReferralRoutes {
            createClient { install(ClientCN) { json() } }
                .get("/api/v1/user/referral")
                .status shouldBe HttpStatusCode.Unauthorized
        }
    }

    "invalid JWT → 401" {
        withReferralRoutes {
            createClient { install(ClientCN) { json() } }
                .get("/api/v1/user/referral") { header(HttpHeaders.Authorization, "Bearer not-a-real-jwt") }
                .status shouldBe HttpStatusCode.Unauthorized
        }
    }

    "a repository failure fails soft as 500" {
        val (uid, _, jwt) = seedUser()
        val throwing =
            object : ReferralReadRepository {
                override suspend fun getReferralState(userId: UUID): ReferralReadState = throw IllegalStateException("boom")
            }
        try {
            withReferralRoutes(repo = throwing) {
                createClient { install(ClientCN) { json() } }
                    .get("/api/v1/user/referral") { header(HttpHeaders.Authorization, "Bearer $jwt") }
                    .status shouldBe HttpStatusCode.InternalServerError
            }
        } finally {
            cleanup(uid)
        }
    }

    "ReferralReadRoutes source logs no token / Authorization / sub / userId" {
        val src = File("src/main/kotlin/id/nearyou/app/referral/ReferralReadRoutes.kt").readText()
        val stripped =
            src
                .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("//[^\n]*"), "")
        val logCalls =
            Regex("""log\.(?:info|warn|error|debug)\s*\([^)]*\)""", RegexOption.DOT_MATCHES_ALL)
                .findAll(stripped)
                .map { it.value }
                .toList()
        (logCalls.isNotEmpty()) shouldBe true
        logCalls.forEach { callSite ->
            listOf("Authorization", "Bearer", "userId", "principal", ".sub", "inviteCode").forEach { forbidden ->
                callSite.contains(forbidden) shouldBe false
            }
        }
    }
})
