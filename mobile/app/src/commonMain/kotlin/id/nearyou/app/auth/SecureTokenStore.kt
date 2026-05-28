package id.nearyou.app.auth

/**
 * Secure persistence of the backend-issued auth token pair.
 *
 * Round-trip integrity is mandatory: `write(tokens)` followed by `read()` (potentially
 * after process restart) MUST return a `TokenPair` equal to the written one; `clear()`
 * followed by `read()` MUST return `null`. See `openspec/specs/mobile-auth-signin/spec.md`
 * § "SecureTokenStore persists tokens at rest with platform-specific encryption".
 *
 * Platform actuals:
 *  - androidMain: DataStore Preferences (encrypted-at-rest via Tink AEAD; Tink keyset is
 *    wrapped by an Android-Keystore master key at alias `nearyou_auth_tokens_master_key`).
 *  - iosMain: Keychain Services with accessibility `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`.
 */
expect class SecureTokenStore {
    suspend fun read(): TokenPair?

    suspend fun write(tokens: TokenPair)

    suspend fun clear()
}

/**
 * The persisted token triple. `accessExpiresAtEpochMillis` is the absolute wall-clock time
 * (Unix epoch in milliseconds) at which the access token expires — NOT a relative TTL.
 * Stored alongside both tokens so `RootRouterScreen` can route based on access-token
 * expiry vs refresh-token TTL without re-issuing requests.
 */
data class TokenPair(
    val accessToken: String,
    val refreshToken: String,
    val accessExpiresAtEpochMillis: Long,
)
