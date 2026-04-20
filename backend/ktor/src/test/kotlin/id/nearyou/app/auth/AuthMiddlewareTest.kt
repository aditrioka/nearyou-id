package id.nearyou.app.auth

import id.nearyou.app.auth.jwt.JwtIssuer
import id.nearyou.app.auth.jwt.RsaKeyLoader
import id.nearyou.app.auth.jwt.TestKeys
import id.nearyou.app.auth.session.InMemoryUsers
import id.nearyou.app.auth.session.userRow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.time.Instant
import java.util.UUID

class AuthMiddlewareTest : StringSpec({
    val keys = RsaKeyLoader(TestKeys.freshEncodedPemPrivateKey(), kid = "test-mw")
    val issuer = JwtIssuer(keys)
    val now = Instant.parse("2026-04-20T10:00:00Z")

    suspend fun setup(
        users: InMemoryUsers,
        block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit,
    ) {
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                install(io.ktor.server.auth.Authentication) {
                    configureUserJwt(keys, users) { now }
                }
                routing {
                    authenticate(AUTH_PROVIDER_USER) {
                        get("/secret") {
                            val principal = call.principal<UserPrincipal>()!!
                            call.respondText("hi ${principal.userId}")
                        }
                    }
                }
            }
            block()
        }
    }

    "valid token + matching version returns 200" {
        val user = userRow(tokenVersion = 3)
        setup(InMemoryUsers(listOf(user))) {
            val token = issuer.issueAccessToken(user.id, tokenVersion = 3)
            val response = client.get("/secret") { bearerAuth(token) }
            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldContain user.id.toString()
        }
    }

    "stale token_version → 401 token_revoked" {
        val user = userRow(tokenVersion = 5)
        setup(InMemoryUsers(listOf(user))) {
            val staleToken = issuer.issueAccessToken(user.id, tokenVersion = 4)
            val response = client.get("/secret") { bearerAuth(staleToken) }
            response.status shouldBe HttpStatusCode.Unauthorized
            response.bodyAsText() shouldContain "token_revoked"
        }
    }

    "banned user → 403 account_banned" {
        val user = userRow(isBanned = true)
        setup(InMemoryUsers(listOf(user))) {
            val token = issuer.issueAccessToken(user.id, tokenVersion = user.tokenVersion)
            val response = client.get("/secret") { bearerAuth(token) }
            response.status shouldBe HttpStatusCode.Forbidden
            response.bodyAsText() shouldContain "account_banned"
        }
    }

    "active suspension → 403 account_suspended" {
        val user = userRow(suspendedUntil = now.plusSeconds(3600))
        setup(InMemoryUsers(listOf(user))) {
            val token = issuer.issueAccessToken(user.id, tokenVersion = user.tokenVersion)
            val response = client.get("/secret") { bearerAuth(token) }
            response.status shouldBe HttpStatusCode.Forbidden
            response.bodyAsText() shouldContain "account_suspended"
        }
    }

    "missing Authorization header → 401 token_revoked (default fallback)" {
        setup(InMemoryUsers()) {
            val response = client.get("/secret")
            response.status shouldBe HttpStatusCode.Unauthorized
            response.bodyAsText() shouldContain "token_revoked"
        }
    }

    "unknown user (sub not in users) → 401 token_revoked" {
        setup(InMemoryUsers()) {
            val token = issuer.issueAccessToken(UUID.randomUUID(), tokenVersion = 0)
            val response = client.get("/secret") { bearerAuth(token) }
            response.status shouldBe HttpStatusCode.Unauthorized
            response.bodyAsText() shouldContain "token_revoked"
        }
    }
})
