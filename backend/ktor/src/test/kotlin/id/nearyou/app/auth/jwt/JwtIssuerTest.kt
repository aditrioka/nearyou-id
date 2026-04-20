package id.nearyou.app.auth.jwt

import com.auth0.jwt.exceptions.TokenExpiredException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.util.UUID

class JwtIssuerTest : StringSpec({
    val keys = RsaKeyLoader(TestKeys.freshEncodedPemPrivateKey(), kid = "test-1")
    val issuer = JwtIssuer(keys)

    "round-trip: claims and kid header preserved" {
        val userId = UUID.randomUUID()
        val token = issuer.issueAccessToken(userId, tokenVersion = 7)

        val decoded = issuer.verifier().verify(token)
        decoded.subject shouldBe userId.toString()
        decoded.getClaim("token_version").asInt() shouldBe 7
        decoded.keyId shouldBe "test-1"
        (decoded.expiresAtAsInstant.epochSecond - decoded.issuedAtAsInstant.epochSecond) shouldBe 900L
    }

    "expired token rejected" {
        val past = Instant.now().minusSeconds(3600)
        val expiredIssuer = JwtIssuer(keys, nowProvider = { past })
        val token = expiredIssuer.issueAccessToken(UUID.randomUUID(), tokenVersion = 1)

        shouldThrow<TokenExpiredException> {
            issuer.verifier().verify(token)
        }
    }
})
