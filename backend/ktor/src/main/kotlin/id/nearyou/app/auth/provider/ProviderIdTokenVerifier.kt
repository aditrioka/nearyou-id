package id.nearyou.app.auth.provider

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

/** Minimum age of the cached JWKS before an unknown `kid` may force an upstream
 *  re-fetch (01-#6: bogus-kid floods otherwise amplify into upstream load). */
internal val FORCED_REFRESH_COOLDOWN: Duration = Duration.ofSeconds(60)

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

    // Single-flight + forced-refresh cooldown (2026-06-10 audit, finding 01-#6):
    // previously any unauthenticated signin bearing a bogus `kid` forced a fresh
    // upstream JWKS fetch (no negative caching, no rate limit), and N concurrent
    // cold-cache callers fetched N times. One refresh flies at a time; a miss
    // after a refresh newer than the cooldown returns null WITHOUT another fetch
    // (legitimate rotations re-fetch on the next cooldown lapse). Mirrors the
    // intent of `infra/oidc`'s `.cached(...).rateLimited(...)` JwkProvider.
    private val refreshMutex = Mutex()

    @Volatile
    private var lastRefreshAt: Instant = Instant.EPOCH

    open suspend fun keyFor(kid: String): RSAPublicKey? {
        val entry = current() ?: refresh()
        entry.keys[kid]?.let { return it }
        // Unknown kid: only force an upstream re-fetch if the cache is older than
        // the cooldown — a bogus-kid flood otherwise amplifies into upstream load.
        if (Duration.between(lastRefreshAt, clock.instant()) < FORCED_REFRESH_COOLDOWN) return null
        return forceRefresh().keys[kid]
    }

    open suspend fun availableKids(): Set<String> = (current() ?: refresh()).keys.keys

    private fun current(): JwksCacheEntry? {
        val entry = cached.get() ?: return null
        return if (entry.expiresAt.isAfter(clock.instant())) entry else null
    }

    /** Lazy refresh: a TTL-valid cache entry short-circuits (single-flight re-check under the lock). */
    private suspend fun refresh(): JwksCacheEntry =
        refreshMutex.withLock {
            // A concurrent caller may have refreshed while this one waited on the
            // lock — re-check before paying another upstream round-trip.
            current()?.let { return it }
            fetchLocked()
        }

    /**
     * Forced refresh for an unknown `kid`: bypasses the TTL short-circuit, because the lazy
     * path would hand back the still-valid STALE document without fetching — after a mid-TTL
     * provider key rotation that 401s every new-kid sign-in until `max-age` expiry (Google
     * serves multi-hour max-age). Single-flight collapse is preserved differently here: a
     * caller that waited on the lock while a peer completed a fetch observes the bumped
     * [lastRefreshAt] and reuses the peer's fresh document instead of re-fetching. The
     * [FORCED_REFRESH_COOLDOWN] gate in [keyFor] still bounds a bogus-kid flood to one
     * upstream fetch per cooldown window.
     */
    private suspend fun forceRefresh(): JwksCacheEntry {
        val observedRefreshAt = lastRefreshAt
        refreshMutex.withLock {
            if (lastRefreshAt != observedRefreshAt) {
                cached.get()?.let { return it }
            }
            return fetchLocked()
        }
    }

    /** Upstream fetch + cache replacement. MUST be called while holding [refreshMutex]. */
    private suspend fun fetchLocked(): JwksCacheEntry {
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
        lastRefreshAt = clock.instant()
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
