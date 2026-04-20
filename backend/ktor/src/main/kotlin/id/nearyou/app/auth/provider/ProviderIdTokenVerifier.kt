package id.nearyou.app.auth.provider

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.math.BigInteger
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference

const val JWKS_DEFAULT_CACHE_SECONDS = 3600L

class InvalidIdTokenException(message: String) : RuntimeException(message)

data class VerifiedIdToken(
    val sub: String,
    val email: String?,
)

interface ProviderIdTokenVerifier {
    suspend fun verify(idToken: String): VerifiedIdToken
}

@Serializable
internal data class JwksDoc(val keys: List<JwksKey>)

@Serializable
internal data class JwksKey(
    val kty: String? = null,
    val kid: String? = null,
    val n: String? = null,
    val e: String? = null,
)

internal data class JwksCacheEntry(
    val keys: Map<String, RSAPublicKey>,
    val expiresAt: Instant,
)

open class JwksCache(
    private val httpClient: HttpClient,
    private val jwksUrl: String,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val cached = AtomicReference<JwksCacheEntry?>(null)

    open suspend fun keyFor(kid: String): RSAPublicKey? {
        val entry = current() ?: refresh()
        return entry.keys[kid] ?: refresh().keys[kid]
    }

    open suspend fun availableKids(): Set<String> = (current() ?: refresh()).keys.keys

    private fun current(): JwksCacheEntry? {
        val entry = cached.get() ?: return null
        return if (entry.expiresAt.isAfter(clock.instant())) entry else null
    }

    private suspend fun refresh(): JwksCacheEntry {
        val response: HttpResponse = httpClient.get(jwksUrl)
        val body = response.body<String>()
        val parsed = jsonParser.decodeFromString(JwksDoc.serializer(), body)
        val keys =
            parsed.keys
                .asSequence()
                .filter { it.kty == "RSA" && it.kid != null && it.n != null && it.e != null }
                .associate { jwk -> jwk.kid!! to rsaKeyFrom(jwk.n!!, jwk.e!!) }
        val ttlSeconds = parseCacheControlMaxAge(response.headers[HttpHeaders.CacheControl])
        val entry = JwksCacheEntry(keys, clock.instant().plus(Duration.ofSeconds(ttlSeconds)))
        cached.set(entry)
        return entry
    }

    companion object {
        private val jsonParser = Json { ignoreUnknownKeys = true }

        internal fun parseCacheControlMaxAge(header: String?): Long {
            if (header.isNullOrBlank()) return JWKS_DEFAULT_CACHE_SECONDS
            val match = Regex("max-age\\s*=\\s*(\\d+)").find(header) ?: return JWKS_DEFAULT_CACHE_SECONDS
            return match.groupValues[1].toLongOrNull() ?: JWKS_DEFAULT_CACHE_SECONDS
        }

        private fun rsaKeyFrom(
            n: String,
            e: String,
        ): RSAPublicKey {
            val urlDecoder = Base64.getUrlDecoder()
            val modulus = BigInteger(1, urlDecoder.decode(n))
            val exponent = BigInteger(1, urlDecoder.decode(e))
            return KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, exponent)) as RSAPublicKey
        }
    }
}

internal fun verifyRs256(
    idToken: String,
    expectedIssuers: Set<String>,
    expectedAudiences: Set<String>,
    publicKey: RSAPublicKey,
    clock: Clock,
): VerifiedIdToken {
    if (expectedAudiences.isEmpty()) {
        throw InvalidIdTokenException("audience allow-list is empty")
    }
    val verifier =
        JWT.require(Algorithm.RSA256(publicKey, null))
            .acceptLeeway(5)
            .build()
    val decoded =
        try {
            verifier.verify(idToken)
        } catch (ex: JWTVerificationException) {
            throw InvalidIdTokenException("verification failed: ${ex.message}")
        }
    if (decoded.issuer !in expectedIssuers) {
        throw InvalidIdTokenException("issuer ${decoded.issuer} not in allow-list")
    }
    val tokenAud = decoded.audience.orEmpty()
    if (tokenAud.none { it in expectedAudiences }) {
        throw InvalidIdTokenException("audience $tokenAud not in allow-list")
    }
    val expiresAt = decoded.expiresAtAsInstant
    if (expiresAt != null && expiresAt.isBefore(clock.instant())) {
        throw InvalidIdTokenException("token expired")
    }
    val sub = decoded.subject ?: throw InvalidIdTokenException("missing sub")
    val email = decoded.getClaim("email").asString()
    return VerifiedIdToken(sub = sub, email = email)
}
