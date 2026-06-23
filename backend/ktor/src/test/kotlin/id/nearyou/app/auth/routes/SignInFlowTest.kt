package id.nearyou.app.auth.routes

import id.nearyou.app.auth.configureUserJwt
import id.nearyou.app.auth.jwt.JwtIssuer
import id.nearyou.app.auth.jwt.RsaKeyLoader
import id.nearyou.app.auth.jwt.TestKeys
import id.nearyou.app.auth.loginhistory.InMemoryLoginEvents
import id.nearyou.app.auth.loginhistory.LoginEventRecorder
import id.nearyou.app.auth.loginhistory.LoginEventType
import id.nearyou.app.auth.provider.InvalidIdTokenException
import id.nearyou.app.auth.provider.ProviderIdTokenVerifier
import id.nearyou.app.auth.provider.VerifiedIdToken
import id.nearyou.app.auth.session.InMemoryRefreshTokens
import id.nearyou.app.auth.session.InMemoryUsers
import id.nearyou.app.auth.session.RefreshTokenService
import id.nearyou.app.auth.session.userRow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
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
import java.time.Instant
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientCN

private fun sha256Hex(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}

private class StaticVerifier(
    private val sub: String,
    private val email: String? = null,
) : ProviderIdTokenVerifier {
    override suspend fun verify(idToken: String): VerifiedIdToken {
        if (idToken == "bad") throw InvalidIdTokenException("forced failure")
        return VerifiedIdToken(sub = sub, email = email)
    }
}

class SignInFlowTest : StringSpec({
    val keys = RsaKeyLoader(TestKeys.freshEncodedPemPrivateKey(), kid = "test-signin")
    val jwtIssuer = JwtIssuer(keys)
    val now = Instant.parse("2026-04-20T10:00:00Z")

    suspend fun setup(
        users: InMemoryUsers,
        verifier: ProviderIdTokenVerifier,
        loginEvents: InMemoryLoginEvents = InMemoryLoginEvents(),
        block: suspend io.ktor.server.testing.ApplicationTestBuilder.(refreshTokens: InMemoryRefreshTokens) -> Unit,
    ) {
        val tokens = InMemoryRefreshTokens()
        val service = RefreshTokenService(tokens, users, nowProvider = { now })
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                install(Authentication) {
                    configureUserJwt(keys, users, nowProvider = { now })
                }
                authRoutes(
                    Providers(google = verifier, apple = verifier),
                    users,
                    service,
                    jwtIssuer,
                    LoginEventRecorder(loginEvents),
                )
            }
            block(tokens)
        }
    }

    "existing user signs in successfully" {
        val sub = "google-sub-1"
        val user = userRow(googleIdHash = sha256Hex(sub), tokenVersion = 0)
        setup(InMemoryUsers(listOf(user)), StaticVerifier(sub)) { tokens ->
            val client =
                createClient {
                    install(ClientCN) { json() }
                }
            val response =
                client.post("/api/v1/auth/signin") {
                    contentType(ContentType.Application.Json)
                    setBody(SignInRequest(provider = "google", idToken = "ok"))
                }
            response.status shouldBe HttpStatusCode.OK
            val body: TokenPairResponse = response.body()
            body.expiresIn shouldBe 900L
            body.accessToken shouldNotBe ""
            body.refreshToken shouldNotBe ""
            tokens.rows.size shouldBe 1
        }
    }

    "unknown user → 404 user_not_found" {
        setup(InMemoryUsers(), StaticVerifier("google-unknown")) { _ ->
            val client = createClient { install(ClientCN) { json() } }
            val response =
                client.post("/api/v1/auth/signin") {
                    contentType(ContentType.Application.Json)
                    setBody(SignInRequest(provider = "google", idToken = "ok"))
                }
            response.status shouldBe HttpStatusCode.NotFound
            response.bodyAsText() shouldContain "user_not_found"
        }
    }

    "banned user → 403 account_banned" {
        val sub = "google-banned"
        val user = userRow(googleIdHash = sha256Hex(sub), isBanned = true)
        setup(InMemoryUsers(listOf(user)), StaticVerifier(sub)) { _ ->
            val client = createClient { install(ClientCN) { json() } }
            val response =
                client.post("/api/v1/auth/signin") {
                    contentType(ContentType.Application.Json)
                    setBody(SignInRequest(provider = "google", idToken = "ok"))
                }
            response.status shouldBe HttpStatusCode.Forbidden
            response.bodyAsText() shouldContain "account_banned"
        }
    }

    "invalid id_token → 401 invalid_id_token" {
        setup(InMemoryUsers(), StaticVerifier("anything")) { _ ->
            val client = createClient { install(ClientCN) { json() } }
            val response =
                client.post("/api/v1/auth/signin") {
                    contentType(ContentType.Application.Json)
                    setBody(SignInRequest(provider = "google", idToken = "bad"))
                }
            response.status shouldBe HttpStatusCode.Unauthorized
            response.bodyAsText() shouldContain "invalid_id_token"
        }
    }

    "device_fingerprint_hash persisted on refresh row" {
        val sub = "google-fp"
        val user = userRow(googleIdHash = sha256Hex(sub))
        setup(InMemoryUsers(listOf(user)), StaticVerifier(sub)) { tokens ->
            val client = createClient { install(ClientCN) { json() } }
            client.post("/api/v1/auth/signin") {
                contentType(ContentType.Application.Json)
                setBody(SignInRequest(provider = "google", idToken = "ok", deviceFingerprintHash = "fp-abc"))
            }
            tokens.rows.values.single().deviceFingerprintHash shouldBe "fp-abc"
        }
    }

    "refresh endpoint rotates and issues new tokens" {
        val sub = "google-refresh"
        val user = userRow(googleIdHash = sha256Hex(sub))
        setup(InMemoryUsers(listOf(user)), StaticVerifier(sub)) { _ ->
            val client = createClient { install(ClientCN) { json() } }
            val signin: TokenPairResponse =
                client.post("/api/v1/auth/signin") {
                    contentType(ContentType.Application.Json)
                    setBody(SignInRequest(provider = "google", idToken = "ok"))
                }.body()

            val refreshed: TokenPairResponse =
                client.post("/api/v1/auth/refresh") {
                    contentType(ContentType.Application.Json)
                    setBody(RefreshRequest(refreshToken = signin.refreshToken))
                }.body()

            // refresh tokens are random bytes — must differ
            refreshed.refreshToken shouldNotBe signin.refreshToken
            // access tokens may be identical under a fixed-time test clock
            refreshed.expiresIn shouldBe 900L
        }
    }

    "refresh allows an active account → 200 with a new token pair (D8 regression: healthy path unaffected)" {
        val sub = "google-refresh-active-guard"
        val user = userRow(googleIdHash = sha256Hex(sub))
        setup(InMemoryUsers(listOf(user)), StaticVerifier(sub)) { _ ->
            val client = createClient { install(ClientCN) { json() } }
            val signin: TokenPairResponse =
                client.post("/api/v1/auth/signin") {
                    contentType(ContentType.Application.Json)
                    setBody(SignInRequest(provider = "google", idToken = "ok"))
                }.body()

            val response =
                client.post("/api/v1/auth/refresh") {
                    contentType(ContentType.Application.Json)
                    setBody(RefreshRequest(refreshToken = signin.refreshToken))
                }
            response.status shouldBe HttpStatusCode.OK
            val refreshed: TokenPairResponse = response.body()
            refreshed.refreshToken shouldNotBe signin.refreshToken
            refreshed.expiresIn shouldBe 900L
        }
    }

    "refresh denies a permanently-banned account → 403 account_banned, no new access token" {
        val sub = "google-refresh-banned"
        val user = userRow(googleIdHash = sha256Hex(sub))
        val users = InMemoryUsers(listOf(user))
        setup(users, StaticVerifier(sub)) { _ ->
            val client = createClient { install(ClientCN) { json() } }
            val signin: TokenPairResponse =
                client.post("/api/v1/auth/signin") {
                    contentType(ContentType.Application.Json)
                    setBody(SignInRequest(provider = "google", idToken = "ok"))
                }.body()
            // The account is permanently banned AFTER a valid session exists.
            users.rows[user.id] = users.rows[user.id]!!.copy(isBanned = true, suspendedUntil = null)

            val response =
                client.post("/api/v1/auth/refresh") {
                    contentType(ContentType.Application.Json)
                    setBody(RefreshRequest(refreshToken = signin.refreshToken))
                }
            response.status shouldBe HttpStatusCode.Forbidden
            response.bodyAsText() shouldContain "account_banned"
        }
    }

    "refresh denies an actively-suspended account → 403 account_banned" {
        val sub = "google-refresh-suspended"
        val user = userRow(googleIdHash = sha256Hex(sub))
        val users = InMemoryUsers(listOf(user))
        setup(users, StaticVerifier(sub)) { _ ->
            val client = createClient { install(ClientCN) { json() } }
            val signin: TokenPairResponse =
                client.post("/api/v1/auth/signin") {
                    contentType(ContentType.Application.Json)
                    setBody(SignInRequest(provider = "google", idToken = "ok"))
                }.body()
            // A 7-day suspension is is_banned = TRUE with a future suspended_until.
            users.rows[user.id] = users.rows[user.id]!!.copy(isBanned = true, suspendedUntil = now.plusSeconds(7 * 24 * 3600))

            val response =
                client.post("/api/v1/auth/refresh") {
                    contentType(ContentType.Application.Json)
                    setBody(RefreshRequest(refreshToken = signin.refreshToken))
                }
            response.status shouldBe HttpStatusCode.Forbidden
            response.bodyAsText() shouldContain "account_banned"
        }
    }

    "refresh denies a soft-deleted account → 401 token_revoked" {
        val sub = "google-refresh-deleted"
        val user = userRow(googleIdHash = sha256Hex(sub))
        val users = InMemoryUsers(listOf(user))
        setup(users, StaticVerifier(sub)) { _ ->
            val client = createClient { install(ClientCN) { json() } }
            val signin: TokenPairResponse =
                client.post("/api/v1/auth/signin") {
                    contentType(ContentType.Application.Json)
                    setBody(SignInRequest(provider = "google", idToken = "ok"))
                }.body()
            users.rows[user.id] = users.rows[user.id]!!.copy(deletedAt = now)

            val response =
                client.post("/api/v1/auth/refresh") {
                    contentType(ContentType.Application.Json)
                    setBody(RefreshRequest(refreshToken = signin.refreshToken))
                }
            response.status shouldBe HttpStatusCode.Unauthorized
            response.bodyAsText() shouldContain "token_revoked"
        }
    }

    "logout-all bumps token_version" {
        val sub = "google-logout"
        val user = userRow(googleIdHash = sha256Hex(sub))
        val users = InMemoryUsers(listOf(user))
        setup(users, StaticVerifier(sub)) { _ ->
            val client = createClient { install(ClientCN) { json() } }
            val signin: TokenPairResponse =
                client.post("/api/v1/auth/signin") {
                    contentType(ContentType.Application.Json)
                    setBody(SignInRequest(provider = "google", idToken = "ok"))
                }.body()

            val response =
                client.post("/api/v1/auth/logout-all") {
                    bearerAuth(signin.accessToken)
                }
            response.status shouldBe HttpStatusCode.NoContent
            users.rows[user.id]!!.tokenVersion shouldBe 1
        }
    }

    "a successful sign-in records a signin login event (clientIp + hashed identifier)" {
        val sub = "google-le-signin"
        val user = userRow(googleIdHash = sha256Hex(sub))
        val events = InMemoryLoginEvents()
        setup(InMemoryUsers(listOf(user)), StaticVerifier(sub), events) { _ ->
            val client = createClient { install(ClientCN) { json() } }
            client.post("/api/v1/auth/signin") {
                header("CF-Connecting-IP", "203.0.113.9")
                contentType(ContentType.Application.Json)
                setBody(SignInRequest(provider = "google", idToken = "ok", deviceFingerprintHash = "fp-le"))
            }
            events.recorded shouldHaveSize 1
            val e = events.recorded.single()
            e.eventType shouldBe LoginEventType.SIGNIN
            e.userId shouldBe user.id
            // The IP comes from the clientIp ladder (CF-Connecting-IP here), not a raw header read.
            e.ip shouldBe "203.0.113.9"
            e.deviceFingerprintHash shouldBe "fp-le"
            // The identifier is the HASHED provider sub, never the raw subject.
            e.identifierHash shouldBe sha256Hex(sub)
        }
    }

    "a successful refresh records a refresh login event (stored provider identifier)" {
        val sub = "google-le-refresh"
        val user = userRow(googleIdHash = sha256Hex(sub))
        val events = InMemoryLoginEvents()
        setup(InMemoryUsers(listOf(user)), StaticVerifier(sub), events) { _ ->
            val client = createClient { install(ClientCN) { json() } }
            val signin: TokenPairResponse =
                client.post("/api/v1/auth/signin") {
                    contentType(ContentType.Application.Json)
                    setBody(SignInRequest(provider = "google", idToken = "ok"))
                }.body()
            client.post("/api/v1/auth/refresh") {
                contentType(ContentType.Application.Json)
                setBody(RefreshRequest(refreshToken = signin.refreshToken, deviceFingerprintHash = "fp-r"))
            }
            events.recorded shouldHaveSize 2
            val refresh = events.recorded.last()
            refresh.eventType shouldBe LoginEventType.REFRESH
            refresh.deviceFingerprintHash shouldBe "fp-r"
            // On refresh the identifier is looked up from the stored user row (google_id_hash).
            refresh.identifierHash shouldBe sha256Hex(sub)
        }
    }

    "a rejected sign-in (unknown user) records no login event" {
        val events = InMemoryLoginEvents()
        setup(InMemoryUsers(), StaticVerifier("google-le-unknown"), events) { _ ->
            val client = createClient { install(ClientCN) { json() } }
            client.post("/api/v1/auth/signin") {
                contentType(ContentType.Application.Json)
                setBody(SignInRequest(provider = "google", idToken = "ok"))
            }
            events.recorded.shouldBeEmpty()
        }
    }

    "a failing login-event insert breaks neither sign-in nor refresh (fail-soft, both call sites)" {
        val sub = "google-le-failsoft"
        val user = userRow(googleIdHash = sha256Hex(sub))
        setup(InMemoryUsers(listOf(user)), StaticVerifier(sub), InMemoryLoginEvents(failing = true)) { _ ->
            val client = createClient { install(ClientCN) { json() } }
            val response =
                client.post("/api/v1/auth/signin") {
                    contentType(ContentType.Application.Json)
                    setBody(SignInRequest(provider = "google", idToken = "ok"))
                }
            // The swallowed insert error does not fail the sign-in response.
            response.status shouldBe HttpStatusCode.OK
            val signin: TokenPairResponse = response.body()
            val refreshResp =
                client.post("/api/v1/auth/refresh") {
                    contentType(ContentType.Application.Json)
                    setBody(RefreshRequest(refreshToken = signin.refreshToken))
                }
            // ...nor the refresh response.
            refreshResp.status shouldBe HttpStatusCode.OK
        }
    }

    "login events are recorded regardless of analytics consent (security-purpose, UU-PDP)" {
        // The recorder never reads analytics_consent — login history is essential
        // security/anti-abuse data collected under legitimate-interest, so collection
        // is unconditional. This guards against a future regression that gates the
        // login_events write behind the opt-in analytics toggle.
        val sub = "google-le-consent"
        val user = userRow(googleIdHash = sha256Hex(sub))
        val events = InMemoryLoginEvents()
        setup(InMemoryUsers(listOf(user)), StaticVerifier(sub), events) { _ ->
            val client = createClient { install(ClientCN) { json() } }
            client.post("/api/v1/auth/signin") {
                contentType(ContentType.Application.Json)
                setBody(SignInRequest(provider = "google", idToken = "ok"))
            }
            events.recorded shouldHaveSize 1
        }
    }
})
