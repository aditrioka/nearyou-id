package id.nearyou.app.infra.otel

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Deterministic SHA-256 truncation helper for client-IP-derived rate-limit
 * Redis keys (sibling of [UserIdHasher]).
 *
 * Returns the first 16 hex characters of `SHA-256(ip.toByteArray(UTF-8))`.
 * 16 hex = 64 bits — matches [UserIdHasher] and the token correlation-id
 * pattern from `internal-endpoint-auth/spec.md`. Operator mental model
 * unified across `user.id`, `ip`, and token correlation surfaces.
 *
 * **Direct embedding of the raw client IP in IP-axis rate-limit Lua keys is
 * forbidden** by `observability-otel-foundation/spec.md` § "Forbidden span
 * attributes" — raw IP literals leak via the Lettuce `EVALSHA` span's
 * `db.statement` attribute (Tempo) AND the `key=` field of structured logs
 * mandated by `rate-limit-infrastructure/spec.md`. Every IP-axis call site
 * MUST go through this helper before constructing the key.
 *
 * **Fail-fast on blank input.** A blank `ip` is a `ClientIpExtractor`
 * regression — silently hashing `""` would collapse disparate requests to
 * a single shared rate-limit bucket, inverting the limiter's intent. The
 * `require(ip.isNotBlank())` guard makes such regressions immediately
 * observable.
 *
 * **No normalization.** This helper does NOT trim whitespace and does NOT
 * canonicalize IPv6 forms (`::1` vs `0:0:0:0:0:0:0:1` vs `2001:DB8::1`
 * vs `2001:db8::1` all hash differently). Cloudflare emits a deterministic
 * shape per request-edge; semantically-equivalent forms reaching this
 * helper from the same client is not a real-world concern. If a future
 * IPv6 audit shows form-drift causing rate-limit-bypass, normalization can
 * be added without breaking the existing IPv4 surface.
 *
 * Truncation length and digest function are FIXED — changing them is an
 * explicit follow-up change requiring a separate proposal.
 */
object IpHasher {
    fun hash(ip: String): String {
        require(ip.isNotBlank()) {
            "IpHasher.hash requires non-blank input (ClientIpExtractor regression guard)"
        }
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(ip.toByteArray(StandardCharsets.UTF_8))
        val sb = StringBuilder(16)
        for (i in 0 until 8) {
            sb.append(String.format("%02x", digest[i]))
        }
        return sb.toString()
    }
}
