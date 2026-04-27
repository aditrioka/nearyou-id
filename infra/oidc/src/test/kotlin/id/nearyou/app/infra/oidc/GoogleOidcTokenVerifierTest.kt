package id.nearyou.app.infra.oidc

import com.auth0.jwk.Jwk
import com.auth0.jwk.JwkException
import com.auth0.jwk.JwkProvider
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import id.nearyou.app.core.domain.oidc.OidcVerificationException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date

private const val TEST_AUDIENCE = "https://api-staging.nearyou.id"
private const val TEST_KID = "test-kid"
private const val TEST_ROTATING_KID = "rotating-kid"

private fun rsaKeypair(): Pair<RSAPublicKey, RSAPrivateKey> {
    val gen = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
    val kp = gen.generateKeyPair()
    return kp.public as RSAPublicKey to kp.private as RSAPrivateKey
}

private class StaticJwkProvider(
    private val mapping: Map<String, Jwk>,
) : JwkProvider {
    override fun get(keyId: String): Jwk = mapping[keyId] ?: throw JwkException("kid not found: $keyId")
}

private class RotatingJwkProvider(
    private val rotatingKid: String,
    private val rotatedJwk: Jwk,
) : JwkProvider {
    var calls: Int = 0
        private set

    override fun get(keyId: String): Jwk {
        calls += 1
        if (keyId != rotatingKid) throw JwkException("kid not found: $keyId")
        return if (calls == 1) {
            // First lookup: simulate cache miss → JwksRsa builder triggers a refresh.
            throw JwkException("kid not found in cache: $keyId")
        } else {
            // Forced refresh resolves the new kid.
            rotatedJwk
        }
    }
}

private class FakeJwk(
    keyId: String,
    private val publicKey: RSAPublicKey,
) : Jwk(keyId, "RSA", "RS256", null, emptyList(), null, null, null, null) {
    override fun getPublicKey(): java.security.PublicKey = publicKey
}

private fun signedJwt(
    privateKey: RSAPrivateKey,
    publicKey: RSAPublicKey,
    audience: String,
    kid: String,
    expiresAt: Instant,
    issuedAt: Instant = Instant.now().minus(1, ChronoUnit.MINUTES),
    subject: String = "scheduler@nearyou-staging.iam.gserviceaccount.com",
): String =
    JWT.create()
        .withKeyId(kid)
        .withSubject(subject)
        .withAudience(audience)
        .withIssuedAt(Date.from(issuedAt))
        .withExpiresAt(Date.from(expiresAt))
        .sign(Algorithm.RSA256(publicKey, privateKey))

class GoogleOidcTokenVerifierTest : StringSpec({

    "3.5.a valid signed JWT with matching audience + future exp returns VerifiedClaims" {
        val (pub, priv) = rsaKeypair()
        val provider = StaticJwkProvider(mapOf(TEST_KID to FakeJwk(TEST_KID, pub)))
        val verifier = GoogleOidcTokenVerifier(audience = TEST_AUDIENCE, jwkProvider = provider)
        val token =
            signedJwt(
                privateKey = priv,
                publicKey = pub,
                audience = TEST_AUDIENCE,
                kid = TEST_KID,
                expiresAt = Instant.now().plus(1, ChronoUnit.HOURS),
            )

        val claims = runBlocking { verifier.verify(token) }
        claims.aud shouldBe TEST_AUDIENCE
        claims.sub shouldBe "scheduler@nearyou-staging.iam.gserviceaccount.com"
    }

    "3.5.b malformed token throws InvalidToken" {
        val (pub, _) = rsaKeypair()
        val provider = StaticJwkProvider(mapOf(TEST_KID to FakeJwk(TEST_KID, pub)))
        val verifier = GoogleOidcTokenVerifier(audience = TEST_AUDIENCE, jwkProvider = provider)
        shouldThrow<OidcVerificationException.InvalidToken> {
            runBlocking { verifier.verify("not.a.jwt") }
        }
    }

    "3.5.c wrong audience throws AudienceMismatch" {
        val (pub, priv) = rsaKeypair()
        val provider = StaticJwkProvider(mapOf(TEST_KID to FakeJwk(TEST_KID, pub)))
        val verifier = GoogleOidcTokenVerifier(audience = TEST_AUDIENCE, jwkProvider = provider)
        val token =
            signedJwt(
                privateKey = priv,
                publicKey = pub,
                audience = "https://example.com/other-service",
                kid = TEST_KID,
                expiresAt = Instant.now().plus(1, ChronoUnit.HOURS),
            )
        shouldThrow<OidcVerificationException.AudienceMismatch> {
            runBlocking { verifier.verify(token) }
        }
    }

    "3.5.d expired exp throws ExpiredToken" {
        val (pub, priv) = rsaKeypair()
        val provider = StaticJwkProvider(mapOf(TEST_KID to FakeJwk(TEST_KID, pub)))
        val verifier = GoogleOidcTokenVerifier(audience = TEST_AUDIENCE, jwkProvider = provider)
        // 5 minutes in the past, well past the 60-second skew tolerance.
        val token =
            signedJwt(
                privateKey = priv,
                publicKey = pub,
                audience = TEST_AUDIENCE,
                kid = TEST_KID,
                expiresAt = Instant.now().minus(5, ChronoUnit.MINUTES),
                issuedAt = Instant.now().minus(10, ChronoUnit.MINUTES),
            )
        shouldThrow<OidcVerificationException.ExpiredToken> {
            runBlocking { verifier.verify(token) }
        }
    }

    "3.5.e bad signature throws InvalidToken" {
        val (pubA, _) = rsaKeypair()
        val (_, privB) = rsaKeypair()
        // JWKS reports key A; token actually signed with key B → signature mismatch.
        val provider = StaticJwkProvider(mapOf(TEST_KID to FakeJwk(TEST_KID, pubA)))
        val verifier = GoogleOidcTokenVerifier(audience = TEST_AUDIENCE, jwkProvider = provider)
        // Sign using B's private key against B's public key (so the token is well-formed),
        // but the verifier resolves A's public key from JWKS.
        val (pubB, _) = rsaKeypair() // unused — but Algorithm.RSA256 requires a pubkey
        val token =
            JWT.create()
                .withKeyId(TEST_KID)
                .withSubject("scheduler@example")
                .withAudience(TEST_AUDIENCE)
                .withIssuedAt(Date.from(Instant.now()))
                .withExpiresAt(Date.from(Instant.now().plus(1, ChronoUnit.HOURS)))
                .sign(Algorithm.RSA256(pubB, privB))
        shouldThrow<OidcVerificationException.InvalidToken> {
            runBlocking { verifier.verify(token) }
        }
    }

    "3.5.f kid absent from cache: forced refresh resolves it → success" {
        val (pub, priv) = rsaKeypair()
        val rotatedJwk = FakeJwk(TEST_ROTATING_KID, pub)
        val provider = RotatingJwkProvider(rotatingKid = TEST_ROTATING_KID, rotatedJwk = rotatedJwk)
        // Wrap with a JWKS-rsa-style retry wrapper: try once; on JwkException try again.
        val refreshingProvider =
            object : JwkProvider {
                override fun get(keyId: String): Jwk =
                    try {
                        provider.get(keyId)
                    } catch (_: JwkException) {
                        provider.get(keyId)
                    }
            }
        val verifier =
            GoogleOidcTokenVerifier(audience = TEST_AUDIENCE, jwkProvider = refreshingProvider)
        val token =
            signedJwt(
                privateKey = priv,
                publicKey = pub,
                audience = TEST_AUDIENCE,
                kid = TEST_ROTATING_KID,
                expiresAt = Instant.now().plus(1, ChronoUnit.HOURS),
            )

        val claims = runBlocking { verifier.verify(token) }
        claims.aud shouldBe TEST_AUDIENCE
        provider.calls shouldBe 2
    }

    "3.5.g kid still unknown after refresh throws InvalidToken" {
        val provider =
            object : JwkProvider {
                override fun get(keyId: String): Jwk = throw JwkException("kid not found anywhere: $keyId")
            }
        val verifier = GoogleOidcTokenVerifier(audience = TEST_AUDIENCE, jwkProvider = provider)
        val (pub, priv) = rsaKeypair()
        val token =
            signedJwt(
                privateKey = priv,
                publicKey = pub,
                audience = TEST_AUDIENCE,
                kid = "still-unknown",
                expiresAt = Instant.now().plus(1, ChronoUnit.HOURS),
            )
        shouldThrow<OidcVerificationException.InvalidToken> {
            runBlocking { verifier.verify(token) }
        }
    }
})
