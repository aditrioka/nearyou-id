package id.nearyou.app.auth.routes

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import id.nearyou.app.auth.configureUserJwt
import id.nearyou.app.auth.jwt.JwtIssuer
import id.nearyou.app.auth.jwt.RsaKeyLoader
import id.nearyou.app.auth.jwt.TestKeys
import id.nearyou.app.auth.session.InMemoryUsers
import id.nearyou.app.auth.session.userRow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import java.time.Instant
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientCN

class RealtimeRoutesTest : StringSpec({
    val keys = RsaKeyLoader(TestKeys.freshEncodedPemPrivateKey(), kid = "test-rt")
    val jwtIssuer = JwtIssuer(keys)
    val now = Instant.parse("2026-04-20T10:00:00Z")
    val supabaseSecret = "test-supabase-secret-32-bytes-long!!"

    "authenticated caller receives an HS256 token verifying under the supabase secret" {
        val user = userRow()
        val users = InMemoryUsers(listOf(user))
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                install(Authentication) {
                    configureUserJwt(keys, users) { now }
                }
                realtimeRoutes(RealtimeTokenIssuer(supabaseSecret))
            }
            val client = createClient { install(ClientCN) { json() } }
            val accessToken = jwtIssuer.issueAccessToken(user.id, user.tokenVersion)

            val response = client.get("/api/v1/realtime/token") { bearerAuth(accessToken) }
            response.status shouldBe HttpStatusCode.OK
            val body: RealtimeTokenResponse = response.body()
            body.expiresIn shouldBe 3600L

            val verified =
                JWT.require(Algorithm.HMAC256(supabaseSecret))
                    .acceptLeeway(60)
                    .build()
                    .verify(body.token)
            verified.subject shouldBe user.id.toString()
            verified.getClaim("role").asString() shouldBe "authenticated"
            (verified.expiresAtAsInstant.epochSecond - verified.issuedAtAsInstant.epochSecond) shouldBe 3600L
        }
    }

    "unauthenticated → 401" {
        val users = InMemoryUsers()
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                install(Authentication) {
                    configureUserJwt(keys, users) { now }
                }
                realtimeRoutes(RealtimeTokenIssuer(supabaseSecret) { now })
            }
            val response = createClient { install(ClientCN) { json() } }.get("/api/v1/realtime/token")
            response.status shouldBe HttpStatusCode.Unauthorized
        }
    }
})
