package id.nearyou.app.referral

import com.auth0.jwk.Jwk
import com.auth0.jwk.JwkException
import com.auth0.jwk.JwkProvider
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.core.domain.oidc.OidcTokenVerifier
import id.nearyou.app.infra.oidc.GoogleOidcTokenVerifier
import id.nearyou.app.infra.revenuecatapi.NoOpReferralEntitlementGranter
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.Date as UtilDate

private const val RAC_AUDIENCE = "https://api-staging.nearyou.id"
private const val RAC_KID = "test-rac-kid"

private fun racHikari(): HikariDataSource {
    val url = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5433/nearyou_dev"
    return HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = url
            username = System.getenv("DB_USER") ?: "postgres"
            password = System.getenv("DB_PASSWORD") ?: "postgres"
            maximumPoolSize = 2 // CI connection budget
            initializationFailTimeout = -1
        },
    )
}

private class RacFakeJwk(keyId: String, private val pubKey: RSAPublicKey) :
    Jwk(keyId, "RSA", "RS256", null, emptyList(), null, null, null, null) {
    override fun getPublicKey(): java.security.PublicKey = pubKey
}

private class RacStaticJwkProvider(private val mapping: Map<String, Jwk>) : JwkProvider {
    override fun get(keyId: String): Jwk = mapping[keyId] ?: throw JwkException("kid not found: $keyId")
}

/**
 * Authenticated-route test for [referralActivityCheckRoute] (spec scenario
 * "Authenticated invocation scans pending tickets and returns a summary"). The
 * unauthenticated-401 + vendor-isolation cases live in `InternalRoutingIsolationTest`.
 */
@Tags("database")
class ReferralActivityCheckRouteTest : StringSpec({

    val dataSource = racHikari()
    val gen = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
    val kp = gen.generateKeyPair()
    val pubKey = kp.public as RSAPublicKey
    val privKey = kp.private as RSAPrivateKey
    val verifier: OidcTokenVerifier =
        GoogleOidcTokenVerifier(
            audience = RAC_AUDIENCE,
            jwkProvider = RacStaticJwkProvider(mapOf(RAC_KID to RacFakeJwk(RAC_KID, pubKey))),
        )
    val worker = ReferralActivityCheckWorker(dataSource, ReferralGrantRepository(), NoOpReferralEntitlementGranter)

    fun token(): String =
        JWT.create()
            .withKeyId(RAC_KID)
            .withSubject("scheduler@nearyou-staging.iam.gserviceaccount.com")
            .withAudience(RAC_AUDIENCE)
            .withIssuedAt(UtilDate.from(Instant.now()))
            .withExpiresAt(UtilDate.from(Instant.now().plus(1, ChronoUnit.HOURS)))
            .sign(Algorithm.RSA256(pubKey, privKey))

    // Scope to THIS spec's users (rac%) so a full multi-spec run never FK-violates on
    // another spec's users. The users delete cascades referral_tickets + grants.
    fun cleanSlate() {
        dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate("DELETE FROM posts WHERE author_id IN (SELECT id FROM users WHERE username LIKE 'rac%')")
                st.executeUpdate("DELETE FROM users WHERE username LIKE 'rac%'")
            }
        }
    }

    /** Seed an inviter + invitee with 2 posts + one pending ticket. Returns the invitee id. */
    fun seedPassingTicket(): UUID {
        val now = Instant.now()
        return dataSource.connection.use { conn ->
            fun user(n: Int): UUID {
                val id = UUID.randomUUID()
                conn.prepareStatement(
                    "INSERT INTO users (id, username, display_name, date_of_birth, invite_code_prefix) VALUES (?, ?, ?, ?, ?)",
                ).use { ps ->
                    ps.setObject(1, id)
                    ps.setString(2, "rac_${n}_${id.toString().take(6)}")
                    ps.setString(3, "RAC $n")
                    ps.setObject(4, LocalDate.of(1990, 1, 1))
                    ps.setString(5, "rac%05d".format(n))
                    ps.executeUpdate()
                }
                return id
            }
            val inviter = user(1)
            val invitee = user(2)
            repeat(2) {
                conn.prepareStatement(
                    """
                    INSERT INTO posts (author_id, content, display_location, actual_location)
                    VALUES (?, ?, ST_SetSRID(ST_MakePoint(106.8, -6.2), 4326)::geography,
                            ST_SetSRID(ST_MakePoint(106.8, -6.2), 4326)::geography)
                    """.trimIndent(),
                ).use { ps ->
                    ps.setObject(1, invitee)
                    ps.setString(2, "p$it")
                    ps.executeUpdate()
                }
            }
            conn.prepareStatement(
                """
                INSERT INTO referral_tickets (inviter_user_id, invitee_user_id, status, created_at, expires_at)
                VALUES (?, ?, 'pending_activity', ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, inviter)
                ps.setObject(2, invitee)
                // 8 days old so the invitee's seeded activity below can span >= 3 distinct login-days
                ps.setObject(3, OffsetDateTime.ofInstant(now.minus(java.time.Duration.ofDays(8)), ZoneOffset.UTC))
                ps.setObject(4, OffsetDateTime.ofInstant(now.plus(java.time.Duration.ofDays(12)), ZoneOffset.UTC))
                ps.executeUpdate()
            }
            // Seed login-history so the invitee clears the engagement legs (>= 3 login-days,
            // >= 5 sessions): 5 events 60/36/12/2/1h before now — the 24h-apart first three
            // guarantee 3 distinct Asia/Jakarta days, each > 30 min apart → 5 sessions. The
            // (history-less) inviter never matches, so the anti-collision legs stay clean.
            listOf(60L, 36L, 12L, 2L, 1L).forEach { h ->
                conn.prepareStatement(
                    "INSERT INTO login_events (user_id, occurred_at, event_type) VALUES (?, ?, 'signin')",
                ).use { ps ->
                    ps.setObject(1, invitee)
                    ps.setObject(2, OffsetDateTime.ofInstant(now.minus(java.time.Duration.ofHours(h)), ZoneOffset.UTC))
                    ps.executeUpdate()
                }
            }
            invitee
        }
    }

    beforeEach { cleanSlate() }
    afterSpec {
        cleanSlate()
        dataSource.close()
    }

    suspend fun withRoute(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) {
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
                routing { route("/internal") { referralActivityCheckRoute(worker, verifier) } }
            }
            block()
        }
    }

    "an authenticated invocation runs the worker, returns a summary, and grants the seeded ticket" {
        val invitee = seedPassingTicket()
        withRoute {
            val resp =
                client.post("/internal/referral-activity-check") {
                    header(HttpHeaders.Authorization, "Bearer ${token()}")
                }
            resp.status shouldBe HttpStatusCode.OK
            val body = resp.bodyAsText()
            // A summary is returned. Exact counts are NOT asserted: the worker scans ALL
            // pending tickets, so a full multi-spec run may carry unrelated pending rows.
            body shouldContain "\"granted\":"
            body shouldContain "\"expired\":"
            body shouldContain "\"pending\":"
        }
        // The seeded passing ticket WAS granted (scoped to this spec's invitee).
        val granted =
            dataSource.connection.use { c ->
                c.prepareStatement(
                    "SELECT COUNT(*) FROM granted_entitlements WHERE user_id = ? AND grant_role = 'invitee'",
                ).use { ps ->
                    ps.setObject(1, invitee)
                    ps.executeQuery().use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
            }
        granted shouldBe 1
    }
})
