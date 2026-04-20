package id.nearyou.app.auth.signup

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.auth.configureUserJwt
import id.nearyou.app.auth.jwt.JwtIssuer
import id.nearyou.app.auth.jwt.RsaKeyLoader
import id.nearyou.app.auth.jwt.TestKeys
import id.nearyou.app.auth.provider.InvalidIdTokenException
import id.nearyou.app.auth.provider.ProviderIdTokenVerifier
import id.nearyou.app.auth.provider.VerifiedIdToken
import id.nearyou.app.auth.routes.RefreshRequest
import id.nearyou.app.auth.routes.TokenPairResponse
import id.nearyou.app.auth.routes.authRoutes
import id.nearyou.app.auth.session.RefreshTokenService
import id.nearyou.app.infra.repo.IdentifierType
import id.nearyou.app.infra.repo.JdbcRefreshTokenRepository
import id.nearyou.app.infra.repo.JdbcRejectedIdentifierRepository
import id.nearyou.app.infra.repo.JdbcReservedUsernameRepository
import id.nearyou.app.infra.repo.JdbcUserRepository
import id.nearyou.app.infra.repo.RejectedReason
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import id.nearyou.app.auth.routes.Providers as SigninProviders
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
            maximumPoolSize = 4
            initializationFailTimeout = -1
        }
    return HikariDataSource(config)
}

private fun sha256Hex(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}

private class FixedVerifier(private val sub: String) : ProviderIdTokenVerifier {
    override suspend fun verify(idToken: String): VerifiedIdToken {
        if (idToken == "bad") throw InvalidIdTokenException("forced")
        return VerifiedIdToken(sub = sub, email = null)
    }
}

/**
 * Live-DB integration tests for the signup endpoint. Requires the dev
 * docker-compose Postgres to be running (auth-foundation + V3 applied).
 *
 * Tagged `database` so PR CI excludes it by default.
 */
@Tags("database")
class SignupFlowTest : StringSpec({

    val dataSource = hikari()
    val keys = RsaKeyLoader(TestKeys.freshEncodedPemPrivateKey(), kid = "test-signup")
    val jwtIssuer = JwtIssuer(keys)
    val fixedClock = Clock.fixed(Instant.parse("2026-04-20T10:00:00Z"), ZoneId.of("UTC"))

    fun cleanSlate() {
        dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate("DELETE FROM refresh_tokens")
                st.executeUpdate("DELETE FROM users")
                st.executeUpdate("DELETE FROM rejected_identifiers")
            }
        }
    }

    fun buildSignupService(
        googleSub: String = "g-sub-default",
        appleSub: String = "a-sub-default",
    ): SignupService {
        val users = JdbcUserRepository(dataSource)
        val refreshRepo = JdbcRefreshTokenRepository(dataSource)
        val refreshService = RefreshTokenService(refreshRepo, users, nowProvider = { fixedClock.instant() })
        val reserved = JdbcReservedUsernameRepository(dataSource)
        val rejected = JdbcRejectedIdentifierRepository(dataSource)
        val words = WordPairResource.loadFromClasspath()
        val generator =
            UsernameGenerator(
                words = words,
                reserved = reserved,
                history = NoopUsernameHistoryRepository(),
                users = users,
            )
        val deriver = InviteCodePrefixDeriver("test-invite-secret-32bytes-random".toByteArray())
        return SignupService(
            dataSource = dataSource,
            providers =
                SignupService.SignupProviders(
                    google = FixedVerifier(googleSub),
                    apple = FixedVerifier(appleSub),
                ),
            users = users,
            rejected = rejected,
            usernameGenerator = generator,
            inviteDeriver = deriver,
            refreshTokens = refreshService,
            jwtIssuer = jwtIssuer,
            clock = fixedClock,
        )
    }

    suspend fun withSignup(block: suspend io.ktor.server.testing.ApplicationTestBuilder.(svc: SignupService) -> Unit) {
        val svc = buildSignupService()
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                signupRoutes(svc)
            }
            block(svc)
        }
    }

    beforeEach { cleanSlate() }

    "successful Google signup populates every NOT NULL column" {
        withSignup { _ ->
            val client = createClient { install(ClientCN) { json() } }
            val response =
                client.post("/api/v1/auth/signup") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        SignupRequestDto(
                            provider = "google",
                            idToken = "ok",
                            dateOfBirth = "1995-03-14",
                            deviceFingerprintHash = "fp-123",
                        ),
                    )
                }
            response.status shouldBe HttpStatusCode.Created
            val body: SignupTokenPairResponse = response.body()
            body.expiresIn shouldBe 900L
            body.accessToken shouldNotBe ""
            body.refreshToken shouldNotBe ""

            // Verify the row has every NOT NULL column populated.
            dataSource.connection.use { conn ->
                conn.prepareStatement(
                    """
                    SELECT username, display_name, date_of_birth, google_id_hash,
                           invite_code_prefix, token_version, subscription_status
                    FROM users
                    """.trimIndent(),
                ).use { ps ->
                    ps.executeQuery().use { rs ->
                        rs.next() shouldBe true
                        rs.getString("username").length shouldBeLessThanOrEqual 60
                        rs.getString("display_name") shouldNotBe ""
                        rs.getDate("date_of_birth").toLocalDate() shouldBe LocalDate.of(1995, 3, 14)
                        rs.getString("google_id_hash") shouldBe sha256Hex("g-sub-default")
                        rs.getString("invite_code_prefix").shouldMatch(Regex("^[a-z2-7]{8}$"))
                        rs.getInt("token_version") shouldBe 0
                        rs.getString("subscription_status") shouldBe "free"
                    }
                }
            }
        }
    }

    "Apple signup stores apple_id_hash and empty google_id_hash" {
        withSignup { _ ->
            val client = createClient { install(ClientCN) { json() } }
            val response =
                client.post("/api/v1/auth/signup") {
                    contentType(ContentType.Application.Json)
                    setBody(SignupRequestDto("apple", "ok", "1995-03-14"))
                }
            response.status shouldBe HttpStatusCode.Created
            dataSource.connection.use { conn ->
                conn.prepareStatement("SELECT google_id_hash, apple_id_hash FROM users").use { ps ->
                    ps.executeQuery().use { rs ->
                        rs.next() shouldBe true
                        rs.getString("google_id_hash") shouldBe null
                        rs.getString("apple_id_hash") shouldBe sha256Hex("a-sub-default")
                    }
                }
            }
        }
    }

    "refresh_token round-trips through /refresh" {
        // Compose with authRoutes so we can also hit /refresh on the same app.
        val svc = buildSignupService()
        val users = JdbcUserRepository(dataSource)
        val refreshRepo = JdbcRefreshTokenRepository(dataSource)
        val refreshService = RefreshTokenService(refreshRepo, users, nowProvider = { fixedClock.instant() })

        testApplication {
            application {
                install(ContentNegotiation) { json() }
                install(Authentication) {
                    configureUserJwt(keys, users) { fixedClock.instant() }
                }
                signupRoutes(svc)
                authRoutes(
                    SigninProviders(FixedVerifier("unused"), FixedVerifier("unused")),
                    users,
                    refreshService,
                    jwtIssuer,
                )
            }
            val client = createClient { install(ClientCN) { json() } }
            val signup: SignupTokenPairResponse =
                client.post("/api/v1/auth/signup") {
                    contentType(ContentType.Application.Json)
                    setBody(SignupRequestDto("google", "ok", "1995-03-14"))
                }.body()

            val refreshed: TokenPairResponse =
                client.post("/api/v1/auth/refresh") {
                    contentType(ContentType.Application.Json)
                    setBody(RefreshRequest(refreshToken = signup.refreshToken))
                }.body()

            refreshed.refreshToken shouldNotBe signup.refreshToken
            refreshed.expiresIn shouldBe 900L
        }
    }

    "under-18 → 403 user_blocked + rejected_identifiers row written" {
        withSignup { _ ->
            val client = createClient { install(ClientCN) { json() } }
            val response =
                client.post("/api/v1/auth/signup") {
                    contentType(ContentType.Application.Json)
                    // 17 years, 11 months ago — strictly under 18.
                    setBody(SignupRequestDto("google", "ok", "2008-05-01"))
                }
            response.status shouldBe HttpStatusCode.Forbidden
            response.bodyAsText() shouldBe SIGNUP_USER_BLOCKED_BODY

            dataSource.connection.use { conn ->
                conn.prepareStatement(
                    "SELECT identifier_hash, identifier_type, reason FROM rejected_identifiers",
                ).use { ps ->
                    ps.executeQuery().use { rs ->
                        rs.next() shouldBe true
                        rs.getString("identifier_hash") shouldBe sha256Hex("g-sub-default")
                        rs.getString("identifier_type") shouldBe IdentifierType.GOOGLE.sql
                        rs.getString("reason") shouldBe RejectedReason.AGE_UNDER_18.sql
                    }
                }
                conn.prepareStatement("SELECT COUNT(*) FROM users").use { ps ->
                    ps.executeQuery().use { rs ->
                        rs.next() shouldBe true
                        rs.getInt(1) shouldBe 0
                    }
                }
            }
        }
    }

    "exactly 18 today → accepted" {
        // fixedClock is 2026-04-20 UTC. DOB 2008-04-20 is exactly 18.
        withSignup { _ ->
            val client = createClient { install(ClientCN) { json() } }
            val response =
                client.post("/api/v1/auth/signup") {
                    contentType(ContentType.Application.Json)
                    setBody(SignupRequestDto("google", "ok", "2008-04-20"))
                }
            response.status shouldBe HttpStatusCode.Created
        }
    }

    "malformed DOB → 400 invalid_request (NOT 403)" {
        withSignup { _ ->
            val client = createClient { install(ClientCN) { json() } }
            val response =
                client.post("/api/v1/auth/signup") {
                    contentType(ContentType.Application.Json)
                    setBody(SignupRequestDto("google", "ok", "nope"))
                }
            response.status shouldBe HttpStatusCode.BadRequest
            response.bodyAsText() shouldContain "invalid_request"
        }
    }

    "pre-seeded rejected identifier → 403 user_blocked, NO new rejected row" {
        // Seed a rejected identifier first.
        val subHash = sha256Hex("g-sub-default")
        dataSource.connection.use { conn ->
            JdbcRejectedIdentifierRepository(dataSource).insert(
                conn,
                subHash,
                IdentifierType.GOOGLE,
                RejectedReason.AGE_UNDER_18,
            )
        }
        withSignup { _ ->
            val client = createClient { install(ClientCN) { json() } }
            val response =
                client.post("/api/v1/auth/signup") {
                    contentType(ContentType.Application.Json)
                    // A valid adult DOB — pre-check should still win.
                    setBody(SignupRequestDto("google", "ok", "1990-01-01"))
                }
            response.status shouldBe HttpStatusCode.Forbidden
            response.bodyAsText() shouldBe SIGNUP_USER_BLOCKED_BODY

            // Still exactly one rejected_identifiers row (no duplicate insert).
            dataSource.connection.use { conn ->
                conn.prepareStatement("SELECT COUNT(*) FROM rejected_identifiers").use { ps ->
                    ps.executeQuery().use { rs ->
                        rs.next() shouldBe true
                        rs.getInt(1) shouldBe 1
                    }
                }
                conn.prepareStatement("SELECT COUNT(*) FROM users").use { ps ->
                    ps.executeQuery().use { rs ->
                        rs.next() shouldBe true
                        rs.getInt(1) shouldBe 0
                    }
                }
            }
        }
    }

    "existing user → 409 user_exists" {
        withSignup { _ ->
            val client = createClient { install(ClientCN) { json() } }
            // First signup creates the user.
            client.post("/api/v1/auth/signup") {
                contentType(ContentType.Application.Json)
                setBody(SignupRequestDto("google", "ok", "1995-03-14"))
            }.status shouldBe HttpStatusCode.Created
            // Second signup with the same provider sub → 409.
            val response =
                client.post("/api/v1/auth/signup") {
                    contentType(ContentType.Application.Json)
                    setBody(SignupRequestDto("google", "ok", "1995-03-14"))
                }
            response.status shouldBe HttpStatusCode.Conflict
            response.bodyAsText() shouldContain "user_exists"

            dataSource.connection.use { conn ->
                conn.prepareStatement("SELECT COUNT(*) FROM users").use { ps ->
                    ps.executeQuery().use { rs ->
                        rs.next() shouldBe true
                        rs.getInt(1) shouldBe 1
                    }
                }
            }
        }
    }

    "invalid id_token → 401 invalid_id_token" {
        withSignup { _ ->
            val client = createClient { install(ClientCN) { json() } }
            val response =
                client.post("/api/v1/auth/signup") {
                    contentType(ContentType.Application.Json)
                    setBody(SignupRequestDto("google", "bad", "1995-03-14"))
                }
            response.status shouldBe HttpStatusCode.Unauthorized
            response.bodyAsText() shouldContain "invalid_id_token"
        }
    }

    "rejected-precheck body is byte-identical to under-18 body" {
        // Branch 1: pre-seed rejected_identifiers and signup with an adult DOB.
        val svcPre = buildSignupService(googleSub = "sub-pre")
        val subHashPre = sha256Hex("sub-pre")
        dataSource.connection.use { conn ->
            JdbcRejectedIdentifierRepository(dataSource).insert(
                conn,
                subHashPre,
                IdentifierType.GOOGLE,
                RejectedReason.AGE_UNDER_18,
            )
        }
        var bodyPre = ""
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                signupRoutes(svcPre)
            }
            val client = createClient { install(ClientCN) { json() } }
            bodyPre =
                client.post("/api/v1/auth/signup") {
                    contentType(ContentType.Application.Json)
                    setBody(SignupRequestDto("google", "ok", "1990-01-01"))
                }.bodyAsText()
        }

        cleanSlate()

        // Branch 2: fresh identifier, under-18 DOB.
        val svcUnder = buildSignupService(googleSub = "sub-under")
        var bodyUnder = ""
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                signupRoutes(svcUnder)
            }
            val client = createClient { install(ClientCN) { json() } }
            bodyUnder =
                client.post("/api/v1/auth/signup") {
                    contentType(ContentType.Application.Json)
                    setBody(SignupRequestDto("google", "ok", "2012-01-01"))
                }.bodyAsText()
        }

        bodyPre shouldBe bodyUnder
        bodyPre shouldBe SIGNUP_USER_BLOCKED_BODY
    }

    "provider bad string → 400 invalid_request" {
        withSignup { _ ->
            val client = createClient { install(ClientCN) { json() } }
            val response =
                client.post("/api/v1/auth/signup") {
                    contentType(ContentType.Application.Json)
                    setBody(SignupRequestDto("facebook", "ok", "1995-03-14"))
                }
            response.status shouldBe HttpStatusCode.BadRequest
            response.bodyAsText() shouldContain "invalid_request"
        }
    }

    "generated username is not a reserved word" {
        withSignup { _ ->
            val client = createClient { install(ClientCN) { json() } }
            client.post("/api/v1/auth/signup") {
                contentType(ContentType.Application.Json)
                setBody(SignupRequestDto("google", "ok", "1995-03-14"))
            }
            dataSource.connection.use { conn ->
                conn.prepareStatement(
                    """
                    SELECT u.username
                    FROM users u
                    LEFT JOIN reserved_usernames r ON r.username = LOWER(u.username)
                    """.trimIndent(),
                ).use { ps ->
                    ps.executeQuery().use { rs ->
                        rs.next() shouldBe true
                        // Check the LEFT JOIN matched nothing (reserved column would be populated).
                        val username = rs.getString("username")
                        // Re-query separately to assert it's not in reserved_usernames.
                        conn.prepareStatement(
                            "SELECT 1 FROM reserved_usernames WHERE username = LOWER(?)",
                        ).use { ps2 ->
                            ps2.setString(1, username)
                            ps2.executeQuery().use { rs2 ->
                                rs2.next() shouldBe false
                            }
                        }
                    }
                }
            }
        }
    }

    "two sequential signups from different providers both succeed" {
        withSignup { _ ->
            val client = createClient { install(ClientCN) { json() } }
            client.post("/api/v1/auth/signup") {
                contentType(ContentType.Application.Json)
                setBody(SignupRequestDto("google", "ok", "1995-03-14"))
            }.status shouldBe HttpStatusCode.Created
            client.post("/api/v1/auth/signup") {
                contentType(ContentType.Application.Json)
                setBody(SignupRequestDto("apple", "ok", "1995-03-14"))
            }.status shouldBe HttpStatusCode.Created

            dataSource.connection.use { conn ->
                conn.prepareStatement(
                    "SELECT COUNT(*) FROM users",
                ).use { ps ->
                    ps.executeQuery().use { rs ->
                        rs.next() shouldBe true
                        rs.getInt(1) shouldBe 2
                    }
                }
                conn.prepareStatement(
                    "SELECT DISTINCT invite_code_prefix FROM users",
                ).use { ps ->
                    ps.executeQuery().use { rs ->
                        val prefixes = mutableListOf<String>()
                        while (rs.next()) prefixes.add(rs.getString(1))
                        prefixes.size shouldBe 2
                        prefixes.forEach { it.shouldMatch(Regex("^[a-z2-7]{8}$")) }
                        // sanity — both should be distinct
                        prefixes.distinct().size shouldBe 2
                    }
                }
            }
        }
    }

    afterSpec {
        dataSource.close()
    }
})
