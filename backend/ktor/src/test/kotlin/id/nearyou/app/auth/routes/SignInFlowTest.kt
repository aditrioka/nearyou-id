package id.nearyou.app.auth.routes

import id.nearyou.app.auth.configureUserJwt
import id.nearyou.app.auth.jwt.JwtIssuer
import id.nearyou.app.auth.jwt.RsaKeyLoader
import id.nearyou.app.auth.jwt.TestKeys
import id.nearyou.app.auth.provider.InvalidIdTokenException
import id.nearyou.app.auth.provider.ProviderIdTokenVerifier
import id.nearyou.app.auth.provider.VerifiedIdToken
import id.nearyou.app.auth.session.InMemoryRefreshTokens
import id.nearyou.app.auth.session.InMemoryUsers
import id.nearyou.app.auth.session.RefreshTokenService
import id.nearyou.app.auth.session.userRow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
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
        block: suspend io.ktor.server.testing.ApplicationTestBuilder.(refreshTokens: InMemoryRefreshTokens) -> Unit,
    ) {
        val tokens = InMemoryRefreshTokens()
        val service = RefreshTokenService(tokens, users, nowProvider = { now })
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                install(Authentication) {
                    configureUserJwt(keys, users) { now }
                }
                authRoutes(
                    Providers(google = verifier, apple = verifier),
                    users,
                    service,
                    jwtIssuer,
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
})
