package id.nearyou.app.infra.otel

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Deterministic SHA-256 truncation helper for the `service.account.id` span
 * attribute (sibling of [UserIdHasher] and [IpHasher]).
 *
 * Returns the first 16 hex characters of `SHA-256(sub.toByteArray(UTF-8))`.
 * 16 hex = 64 bits — matches [UserIdHasher] and [IpHasher] so the operator
 * mental model stays unified across `user.id`, `ip`, and `service.account.id`
 * correlation surfaces.
 *
 * The hasher input is the verified OIDC `sub` claim — concretely, the
 * `String`-valued `sub` field of the `VerifiedClaims` data class returned by
 * `OidcTokenVerifier.verify(...)` and stored at `InternalEndpointAuth.kt:79`
 * into `OidcSubjectKey`. For Google-issued OIDC tokens, the `sub` claim is the
 * service account's 21-digit numeric `uniqueId`. **This is NOT the same as the
 * raw token bytes**, which feed the WARN-log per-token correlation id at
 * `openspec/specs/internal-endpoint-auth/spec.md` § "WARN-log token
 * correlation" — that correlation id serves a different purpose (per-token
 * failure correlation on the WARN log surface) and is intentionally different
 * from this per-principal span attribute.
 *
 * **Direct `Span.setAttribute("service.account.id", claims.sub)` is forbidden**
 * by `observability-otel-foundation/spec.md` § "Forbidden span attributes"
 * (Tier 1 Group C blocks raw `jwt.sub`). Every site that sets
 * `service.account.id` MUST go through this helper.
 *
 * **Fail-fast on blank input.** A blank `sub` would produce a deterministic
 * single hash for every blank-sub request — silently collapsing disparate
 * service accounts into one shared correlation id and inverting the
 * per-principal correlation purpose. The `require(sub.isNotBlank())` guard
 * makes such regressions immediately observable. Note: a blank `sub` IS
 * reachable in production because `GoogleOidcTokenVerifier.kt` substitutes
 * `decoded.subject ?: ""` for a missing `sub` claim; the writer at the call
 * site (`InternalEndpointAuth.kt`) wraps the helper invocation in a
 * `try/catch (_: Throwable)` block so the throw becomes a silent no-op on the
 * span (no `service.account.id` attribute set, which is the correct outcome
 * for an unidentifiable principal).
 *
 * Truncation length and digest function are FIXED — changing them is an
 * explicit follow-up change requiring a separate proposal. The full
 * cross-spec contract — including the deterministic, distinct-output,
 * 16-hex-shape, and blank-input-rejection guarantees — lives in
 * `openspec/specs/internal-endpoint-auth/spec.md` § "Requirement: /internal
 * server spans carry `service.account.id` principal-correlation attribute".
 */
object ServiceAccountIdHasher {
    fun hash(sub: String): String {
        require(sub.isNotBlank()) {
            "ServiceAccountIdHasher.hash requires non-blank input (OIDC sub-claim regression guard)"
        }
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(sub.toByteArray(StandardCharsets.UTF_8))
        val sb = StringBuilder(16)
        for (i in 0 until 8) {
            sb.append(String.format("%02x", digest[i]))
        }
        return sb.toString()
    }
}
