package id.nearyou.app.infra.otel

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/**
 * Deterministic SHA-256 truncation helper for the `user.id` span attribute.
 *
 * Returns the first 16 hex characters of `SHA-256(uuid.bytes)`. 16 hex = 64
 * bits of entropy — sufficient for trace correlation while making rainbow-table
 * reverse-lookup impractical for small userbases.
 *
 * The shape mirrors the existing token-correlation-id pattern from
 * `internal-endpoint-auth/spec.md` ("first 16 hex chars of `SHA-256(raw token bytes)`")
 * so the operator mental model stays unified across the project's
 * pseudonymization surfaces.
 *
 * **Direct `Span.setAttribute("user.id", uuid.toString())` is forbidden** by
 * the `observability-otel-foundation` capability spec § "Forbidden span
 * attributes". Every site that sets `user.id` MUST go through this helper.
 *
 * Truncation length and digest function are FIXED — changing them is an
 * explicit follow-up change requiring a separate proposal.
 */
object UserIdHasher {
    fun hash(userId: UUID): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes =
            ByteBuffer.allocate(16)
                .putLong(userId.mostSignificantBits)
                .putLong(userId.leastSignificantBits)
                .array()
        val digest = md.digest(bytes)
        val sb = StringBuilder(16)
        for (i in 0 until 8) {
            sb.append(String.format("%02x", digest[i]))
        }
        return sb.toString()
    }
}
