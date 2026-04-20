package id.nearyou.app.auth.provider

import com.auth0.jwt.JWT
import java.time.Clock

const val GOOGLE_JWKS_URL_DEFAULT = "https://www.googleapis.com/oauth2/v3/certs"

class GoogleIdTokenVerifier(
    private val jwksCache: JwksCache,
    private val allowedAudiences: Set<String>,
    private val clock: Clock = Clock.systemUTC(),
) : ProviderIdTokenVerifier {
    private val expectedIssuers = setOf("accounts.google.com", "https://accounts.google.com")

    override suspend fun verify(idToken: String): VerifiedIdToken {
        val kid =
            try {
                JWT.decode(idToken).keyId
            } catch (ex: Exception) {
                throw InvalidIdTokenException("malformed id_token: ${ex.message}")
            } ?: throw InvalidIdTokenException("id_token missing kid header")
        val key = jwksCache.keyFor(kid) ?: throw InvalidIdTokenException("unknown kid: $kid")
        return verifyRs256(idToken, expectedIssuers, allowedAudiences, key, clock)
    }
}
