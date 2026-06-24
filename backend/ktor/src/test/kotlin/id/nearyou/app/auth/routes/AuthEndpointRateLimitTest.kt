package id.nearyou.app.auth.routes

import id.nearyou.app.auth.AuthRateLimiter
import id.nearyou.app.auth.configureUserJwt
import id.nearyou.app.auth.jwt.JwtIssuer
import id.nearyou.app.auth.jwt.RsaKeyLoader
import id.nearyou.app.auth.jwt.TestKeys
import id.nearyou.app.auth.loginhistory.InMemoryLoginEvents
import id.nearyou.app.auth.loginhistory.LoginEventRecorder
import id.nearyou.app.auth.provider.ProviderIdTokenVerifier
import id.nearyou.app.auth.provider.VerifiedIdToken
import id.nearyou.app.auth.session.InMemoryRefreshTokens
import id.nearyou.app.auth.session.InMemoryUsers
import id.nearyou.app.auth.session.RefreshTokenService
import id.nearyou.app.auth.session.userRow
import id.nearyou.app.core.domain.ratelimit.InMemoryRateLimiter
import id.nearyou.app.infra.redis.NoOpRateLimiter
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.security.MessageDigest
import java.time.Instant
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientCN

private fun sha256Hex(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}

/** Always-success verifier that also counts invocations (to prove a 429'd request never verifies). */
private class CountingVerifier(private val sub: String) : ProviderIdTokenVerifier {
    var count = 0

    override suspend fun verify(idToken: String): VerifiedIdToken {
        count++
        return VerifiedIdToken(sub = sub, email = null)
    }
}

/**
 * Route-level coverage for the audit-#214 auth-endpoint limiter on `/signin` + `/refresh`:
 * a rate-limited outcome returns 429 + `Retry-After` and performs none of the endpoint's work
 * (the guard precedes `call.receive`, proven by a malformed body still yielding 429 not 400),
 * NoOp fails soft, and a banned user is still served the appeal token within the cap. The signup
 * endpoint's 429 lives in the DB-tagged `SignupFlowTest` (its `SignupService` needs a DataSource).
 */
class AuthEndpointRateLimitTest : StringSpec({
    val keys = RsaKeyLoader(TestKeys.freshEncodedPemPrivateKey(), kid = "test-authrl")
    val jwtIssuer = JwtIssuer(keys)
    val now = Instant.parse("2026-04-20T10:00:00Z")

    suspend fun appWith(
        users: InMemoryUsers,
        verifier: ProviderIdTokenVerifier,
        limiter: AuthRateLimiter,
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) {
        val service = RefreshTokenService(InMemoryRefreshTokens(), users, nowProvider = { now })
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                install(Authentication) { configureUserJwt(keys, users, nowProvider = { now }) }
                authRoutes(
                    Providers(google = verifier, apple = verifier),
                    users,
                    service,
                    jwtIssuer,
                    LoginEventRecorder(InMemoryLoginEvents()),
                    limiter,
                )
            }
            block()
        }
    }

    "signin over the IP cap returns 429 + Retry-After and performs no verification or lookup" {
        val sub = "google-rl"
        val users = InMemoryUsers(listOf(userRow(googleIdHash = sha256Hex(sub))))
        val verifier = CountingVerifier(sub)
        appWith(users, verifier, AuthRateLimiter(InMemoryRateLimiter(), signinCap = 0)) {
            val client = createClient { install(ClientCN) { json() } }
            // Malformed body: if the guard did NOT precede call.receive we'd get 400, not 429 — so a
            // 429 proves no parse / no id-token verification / no users lookup happened.
            val resp =
                client.post("/api/v1/auth/signin") {
                    contentType(ContentType.Application.Json)
                    setBody("not json")
                }
            resp.status shouldBe HttpStatusCode.TooManyRequests
            resp.headers[HttpHeaders.RetryAfter]!!.toLong() shouldBeGreaterThanOrEqualTo 1L
            verifier.count shouldBe 0
        }
    }

    "signin under the cap is admitted (existing user 200)" {
        val sub = "google-ok"
        val users = InMemoryUsers(listOf(userRow(googleIdHash = sha256Hex(sub))))
        appWith(users, CountingVerifier(sub), AuthRateLimiter(InMemoryRateLimiter(), signinCap = 10)) {
            val client = createClient { install(ClientCN) { json() } }
            val resp =
                client.post("/api/v1/auth/signin") {
                    contentType(ContentType.Application.Json)
                    setBody(SignInRequest(provider = "google", idToken = "ok"))
                }
            resp.status shouldBe HttpStatusCode.OK
        }
    }

    "refresh over the IP cap returns 429 + Retry-After before any rotation" {
        val users = InMemoryUsers()
        appWith(users, CountingVerifier("x"), AuthRateLimiter(InMemoryRateLimiter(), refreshCap = 0)) {
            val client = createClient { install(ClientCN) { json() } }
            val resp =
                client.post("/api/v1/auth/refresh") {
                    contentType(ContentType.Application.Json)
                    setBody("not json")
                }
            resp.status shouldBe HttpStatusCode.TooManyRequests
            resp.headers[HttpHeaders.RetryAfter]!!.toLong() shouldBeGreaterThanOrEqualTo 1L
        }
    }

    "NoOp limiter fail-soft: repeated signins are all admitted (never 429)" {
        val sub = "google-noop"
        val users = InMemoryUsers(listOf(userRow(googleIdHash = sha256Hex(sub))))
        appWith(users, CountingVerifier(sub), AuthRateLimiter(NoOpRateLimiter(), signinCap = 1)) {
            val client = createClient { install(ClientCN) { json() } }
            repeat(5) {
                val resp =
                    client.post("/api/v1/auth/signin") {
                        contentType(ContentType.Application.Json)
                        setBody(SignInRequest(provider = "google", idToken = "ok"))
                    }
                resp.status shouldBe HttpStatusCode.OK
            }
        }
    }

    "a banned user is still served the appeal token within the signin cap" {
        val sub = "google-banned-rl"
        val users = InMemoryUsers(listOf(userRow(googleIdHash = sha256Hex(sub), isBanned = true, tokenVersion = 3)))
        appWith(users, CountingVerifier(sub), AuthRateLimiter(InMemoryRateLimiter(), signinCap = 10)) {
            val client = createClient { install(ClientCN) { json() } }
            val resp =
                client.post("/api/v1/auth/signin") {
                    contentType(ContentType.Application.Json)
                    setBody(SignInRequest(provider = "google", idToken = "ok"))
                }
            resp.status shouldBe HttpStatusCode.Forbidden
            val body = resp.bodyAsText()
            body shouldContain "account_banned"
            body shouldContain "appeal_token"
        }
    }
})
