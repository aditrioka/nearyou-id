package id.nearyou.app.core.domain.oidc

import java.time.Instant

/**
 * Verifies a Google-issued OIDC bearer token presented to a Cloud-Scheduler-invoked
 * internal endpoint (mounted under the `internal/` route subtree). Implementations
 * live in `infra` modules — the vendor SDK boundary keeps Auth0 / nimbus / etc.
 * out of `core:domain` and `backend:ktor`.
 *
 * Contract:
 * - On success, returns a [VerifiedClaims] value carrying the verified `sub`,
 *   `aud`, `exp`, and (optionally) `iat`.
 * - On failure, throws exactly one [OidcVerificationException] subtype mapped 1:1
 *   to the sanitized response vocabulary in the `internal-endpoint-auth`
 *   capability spec. Implementations MUST NOT return a sentinel value.
 */
interface OidcTokenVerifier {
    suspend fun verify(token: String): VerifiedClaims
}

data class VerifiedClaims(
    val sub: String,
    val aud: String,
    val exp: Instant,
    val iat: Instant?,
)

sealed class OidcVerificationException(message: String) : Exception(message) {
    /** No `Authorization` header on the inbound request. */
    class MissingAuthorization(message: String = "missing_authorization") :
        OidcVerificationException(message)

    /** `Authorization` header present but the scheme is not `Bearer`. */
    class InvalidScheme(message: String = "invalid_scheme") :
        OidcVerificationException(message)

    /**
     * JWT structurally malformed, signature invalid, or any non-`exp` / non-`aud`
     * claim verification failure (including JWKS rotation that still cannot
     * resolve the `kid` after one forced refresh).
     */
    class InvalidToken(message: String = "invalid_token") :
        OidcVerificationException(message)

    /** JWT signature valid but `exp` is in the past. */
    class ExpiredToken(message: String = "expired_token") :
        OidcVerificationException(message)

    /** JWT signature valid but `aud` does not match the configured audience. */
    class AudienceMismatch(message: String = "audience_mismatch") :
        OidcVerificationException(message)
}
