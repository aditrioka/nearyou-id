package id.nearyou.app.infra.oidc

import com.auth0.jwk.Jwk
import com.auth0.jwk.JwkException
import com.auth0.jwk.JwkProvider
import com.auth0.jwk.JwkProviderBuilder
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.AlgorithmMismatchException
import com.auth0.jwt.exceptions.InvalidClaimException
import com.auth0.jwt.exceptions.JWTDecodeException
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.exceptions.SignatureVerificationException
import com.auth0.jwt.exceptions.TokenExpiredException
import id.nearyou.app.core.domain.oidc.OidcTokenVerifier
import id.nearyou.app.core.domain.oidc.OidcVerificationException
import id.nearyou.app.core.domain.oidc.VerifiedClaims
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.TimeUnit

/** Google's OIDC discovery JWKS endpoint. */
const val GOOGLE_OIDC_JWKS_URL: String = "https://www.googleapis.com/oauth2/v3/certs"

/** Clock skew tolerance for `iat`/`exp` per design D2 / spec § Signature. */
private const val CLOCK_SKEW_SECONDS: Long = 60L

/**
 * Verifies Google-issued OIDC bearer tokens against the public JWKS at
 * [GOOGLE_OIDC_JWKS_URL]. Used by the `InternalEndpointAuth` Ktor plugin to gate
 * internal endpoints (under the `internal/` subtree) invoked by Cloud Scheduler.
 *
 * Failure mapping (5 classes total — matches the sanitized response vocabulary in
 * the `internal-endpoint-auth` capability):
 *
 *  - structurally malformed JWT, signature failure, or unresolved-after-refresh
 *    `kid` → [OidcVerificationException.InvalidToken]
 *  - `aud` mismatch → [OidcVerificationException.AudienceMismatch]
 *  - `exp` in the past → [OidcVerificationException.ExpiredToken]
 *
 * The `JwkProvider` MUST be constructed with rotation-aware caching — see
 * [googleJwkProvider]. When a token's `kid` is not in cache, the provider's
 * single forced refresh is what gives us the spec-mandated "refresh once before
 * rejecting" behavior.
 */
class GoogleOidcTokenVerifier(
    private val audience: String,
    private val jwkProvider: JwkProvider,
) : OidcTokenVerifier {
    init {
        require(audience.isNotBlank()) { "audience must not be blank" }
    }

    override suspend fun verify(token: String): VerifiedClaims =
        withContext(Dispatchers.IO) {
            val decoded =
                try {
                    JWT.decode(token)
                } catch (_: JWTDecodeException) {
                    throw OidcVerificationException.InvalidToken()
                }

            val jwk = resolveJwk(decoded.keyId)
            val algorithm = algorithmFor(jwk)

            try {
                JWT.require(algorithm)
                    .withAudience(audience)
                    .acceptLeeway(CLOCK_SKEW_SECONDS)
                    .build()
                    .verify(decoded)
            } catch (_: TokenExpiredException) {
                throw OidcVerificationException.ExpiredToken()
            } catch (e: InvalidClaimException) {
                if (e.message?.contains("aud", ignoreCase = true) == true) {
                    throw OidcVerificationException.AudienceMismatch()
                }
                throw OidcVerificationException.InvalidToken()
            } catch (_: SignatureVerificationException) {
                throw OidcVerificationException.InvalidToken()
            } catch (_: AlgorithmMismatchException) {
                throw OidcVerificationException.InvalidToken()
            } catch (_: JWTVerificationException) {
                throw OidcVerificationException.InvalidToken()
            }

            VerifiedClaims(
                sub = decoded.subject ?: "",
                aud = decoded.audience.firstOrNull() ?: audience,
                exp = decoded.expiresAtAsInstant,
                iat = runCatching { decoded.issuedAtAsInstant }.getOrNull(),
            )
        }

    private fun resolveJwk(kid: String?): Jwk {
        if (kid.isNullOrBlank()) throw OidcVerificationException.InvalidToken()
        return try {
            jwkProvider.get(kid)
        } catch (_: JwkException) {
            throw OidcVerificationException.InvalidToken()
        }
    }

    private fun algorithmFor(jwk: Jwk): Algorithm {
        val publicKey =
            try {
                jwk.publicKey as? RSAPublicKey
                    ?: throw OidcVerificationException.InvalidToken()
            } catch (_: JwkException) {
                throw OidcVerificationException.InvalidToken()
            }
        return Algorithm.RSA256(publicKey, null)
    }
}

/**
 * Constructs a [JwkProvider] for Google's OIDC JWKS endpoint with rotation-aware
 * caching. The cache holds up to 10 keys (Google rotates roughly weekly with two
 * keys live concurrently — 10 is generous headroom) for 6 hours; a fresh fetch
 * is rate-limited at 10 requests / minute so a `kid` cache miss triggers exactly
 * one forced refresh per minute regardless of inbound traffic spikes.
 */
fun googleJwkProvider(): JwkProvider =
    JwkProviderBuilder(URI(GOOGLE_OIDC_JWKS_URL).toURL())
        .cached(10, 6, TimeUnit.HOURS)
        .rateLimited(10, 1, TimeUnit.MINUTES)
        .build()
