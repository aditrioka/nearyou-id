package id.nearyou.app.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64

/**
 * The "self user id" contract consumed by the profile surface (`mobile-profile`): the Profil
 * bottom-nav section renders the **self** profile, which requires the caller's own user id to build
 * `GET /api/v1/users/{self_id}` — but the signin/refresh response carries no user id (it lives only in
 * the access token's `sub` claim, `JwtIssuer.withSubject(userId)`), and [SecureTokenStore] persists only
 * the opaque tokens. This is "resolve the self id from the session/auth layer": read the stored access
 * token and decode its subject. commonTest substitutes a fake so the self-profile path is drivable
 * without a real token.
 */
interface SelfUserIdProvider {
    /** The authenticated caller's own user id (the access token's `sub`), or null when no token is
     *  stored / the token is malformed (an authenticated surface always has one; null degrades to the
     *  profile error state rather than crashing). */
    suspend fun selfUserId(): String?
}

/**
 * Production [SelfUserIdProvider]: reads the persisted access token from the [TokenStore] (the platform
 * `SecureTokenStore` in production, an in-memory fake in tests) and decodes its `sub` claim via
 * [decodeJwtSubject]. The decode is **read-only** (the backend verifies the RS256 signature on every
 * request — the client only needs the subject for its own profile path), so no key material or
 * verification is involved. MUST NOT log the token or the decoded id.
 */
class TokenStoreSelfUserIdProvider(
    private val tokenStore: TokenStore,
) : SelfUserIdProvider {
    override suspend fun selfUserId(): String? = tokenStore.read()?.accessToken?.let(::decodeJwtSubject)
}

@Serializable
private data class JwtClaims(
    @SerialName("sub") val subject: String? = null,
)

private val claimsJson = Json { ignoreUnknownKeys = true }

/**
 * Decodes the `sub` (subject) claim from a JWT WITHOUT verifying its signature — the server is the
 * authority on validity; the client reads `sub` only to address its own profile. Returns null for any
 * malformed input (not three dot-segments, non-base64url payload, non-JSON payload, or absent `sub`) so
 * callers degrade gracefully rather than crash. The payload segment is base64url (RFC 7515) and is
 * normalized to standard base64 + padded before decoding (JWT omits `=` padding).
 */
fun decodeJwtSubject(jwt: String): String? {
    val segments = jwt.split('.')
    if (segments.size != 3) return null
    return try {
        val normalized =
            segments[1]
                .replace('-', '+')
                .replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        val payload = Base64.decode(padded).decodeToString()
        claimsJson.decodeFromString<JwtClaims>(payload).subject?.takeIf { it.isNotBlank() }
    } catch (_: Throwable) {
        // Any malformed segment / payload → null (no token subject available). Never throws.
        null
    }
}
