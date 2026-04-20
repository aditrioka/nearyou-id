package id.nearyou.app.auth.routes

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import id.nearyou.app.auth.provider.JwksCache
import id.nearyou.app.auth.session.InMemoryUsers
import id.nearyou.app.auth.session.userRow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.Date
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientCN

private fun sha256Hex(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}

private class StubJwksCache(
    private val kid: String,
    private val key: RSAPublicKey,
) : JwksCache(io.ktor.client.HttpClient(), "stub://", java.time.Clock.systemUTC()) {
    override suspend fun keyFor(kid: String): RSAPublicKey? = if (kid == this.kid) key else null
}

private fun makeAppleSignedPayload(
    privateKey: RSAPrivateKey,
    publicKey: RSAPublicKey,
    kid: String,
    type: String,
    sub: String?,
    transactionId: String?,
    audience: String,
): String {
    val builder =
        JWT.create()
            .withKeyId(kid)
            .withAudience(audience)
            .withIssuer("https://appleid.apple.com")
            .withIssuedAt(Date())
            .withExpiresAt(Date(System.currentTimeMillis() + 60_000))
            .withClaim("type", type)
    if (sub != null) builder.withClaim("sub", sub)
    if (transactionId != null) builder.withClaim("transaction_id", transactionId)
    return builder.sign(Algorithm.RSA256(publicKey, privateKey))
}

class AppleS2SRoutesTest : StringSpec({
    val kid = "apple-test-kid"
    val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    val priv = keyPair.private as RSAPrivateKey
    val pub = keyPair.public as RSAPublicKey
    val bundleId = "id.nearyou.app"

    suspend fun setup(
        users: InMemoryUsers,
        block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit,
    ) {
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                appleS2SRoutes(StubJwksCache(kid, pub), setOf(bundleId), users)
            }
            block()
        }
    }

    "email-enabled flips users.apple_relay_email to true" {
        val sub = "apple-sub-1"
        val user = userRow(appleIdHash = sha256Hex(sub), appleRelayEmail = false)
        val users = InMemoryUsers(listOf(user))
        setup(users) {
            val signed =
                makeAppleSignedPayload(priv, pub, kid, "email-enabled", sub, "tx-1", bundleId)
            val client = createClient { install(ClientCN) { json() } }
            val response =
                client.post("/internal/apple/s2s-notifications") {
                    contentType(ContentType.Application.Json)
                    setBody(AppleS2SEnvelope(signedPayload = signed))
                }
            response.status shouldBe HttpStatusCode.OK
            users.rows[user.id]!!.appleRelayEmail shouldBe true
        }
    }

    "email-disabled flips it back to false" {
        val sub = "apple-sub-2"
        val user = userRow(appleIdHash = sha256Hex(sub), appleRelayEmail = true)
        val users = InMemoryUsers(listOf(user))
        setup(users) {
            val signed =
                makeAppleSignedPayload(priv, pub, kid, "email-disabled", sub, "tx-2", bundleId)
            val client = createClient { install(ClientCN) { json() } }
            client.post("/internal/apple/s2s-notifications") {
                contentType(ContentType.Application.Json)
                setBody(AppleS2SEnvelope(signedPayload = signed))
            }
            users.rows[user.id]!!.appleRelayEmail shouldBe false
        }
    }

    "consent-revoked → 501 not_implemented" {
        val sub = "apple-sub-3"
        val user = userRow(appleIdHash = sha256Hex(sub))
        setup(InMemoryUsers(listOf(user))) {
            val signed =
                makeAppleSignedPayload(priv, pub, kid, "consent-revoked", sub, "tx-3", bundleId)
            val client = createClient { install(ClientCN) { json() } }
            val response =
                client.post("/internal/apple/s2s-notifications") {
                    contentType(ContentType.Application.Json)
                    setBody(AppleS2SEnvelope(signedPayload = signed))
                }
            response.status shouldBe HttpStatusCode.NotImplemented
            response.bodyAsText() shouldContain "not_implemented"
        }
    }

    "account-delete → 501 not_implemented" {
        val sub = "apple-sub-4"
        val user = userRow(appleIdHash = sha256Hex(sub))
        setup(InMemoryUsers(listOf(user))) {
            val signed =
                makeAppleSignedPayload(priv, pub, kid, "account-delete", sub, "tx-4", bundleId)
            val client = createClient { install(ClientCN) { json() } }
            val response =
                client.post("/internal/apple/s2s-notifications") {
                    contentType(ContentType.Application.Json)
                    setBody(AppleS2SEnvelope(signedPayload = signed))
                }
            response.status shouldBe HttpStatusCode.NotImplemented
        }
    }

    "wrong audience → 401" {
        val sub = "apple-sub-5"
        val user = userRow(appleIdHash = sha256Hex(sub))
        setup(InMemoryUsers(listOf(user))) {
            val signed =
                makeAppleSignedPayload(priv, pub, kid, "email-enabled", sub, "tx-5", "wrong.bundle")
            val client = createClient { install(ClientCN) { json() } }
            val response =
                client.post("/internal/apple/s2s-notifications") {
                    contentType(ContentType.Application.Json)
                    setBody(AppleS2SEnvelope(signedPayload = signed))
                }
            response.status shouldBe HttpStatusCode.Unauthorized
        }
    }
})
