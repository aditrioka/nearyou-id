package id.nearyou.app.auth.session

import id.nearyou.app.infra.repo.RefreshTokenRepository
import id.nearyou.app.infra.repo.RefreshTokenRow
import id.nearyou.app.infra.repo.UserRepository
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID

const val REFRESH_TOKEN_TTL_DAYS = 30L
const val REFRESH_OVERLAP_SECONDS = 30L

data class IssuedRefresh(
    val rawToken: String,
    val row: RefreshTokenRow,
)

class TokenReuseException(val ownerUserId: UUID) : RuntimeException("refresh token reuse detected")

class RefreshTokenInvalidException : RuntimeException("refresh token invalid or revoked")

class RefreshTokenService(
    private val tokens: RefreshTokenRepository,
    private val users: UserRepository,
    private val random: SecureRandom = SecureRandom(),
    private val nowProvider: () -> Instant = Instant::now,
) {
    fun issue(
        userId: UUID,
        deviceFingerprintHash: String?,
        familyId: UUID = UUID.randomUUID(),
    ): IssuedRefresh {
        val raw = newRawToken()
        val now = nowProvider()
        val row =
            RefreshTokenRow(
                id = UUID.randomUUID(),
                familyId = familyId,
                userId = userId,
                deviceFingerprintHash = deviceFingerprintHash,
                tokenHash = sha256Hex(raw),
                createdAt = now,
                usedAt = null,
                lastUsedAt = null,
                revokedAt = null,
                expiresAt = now.plus(REFRESH_TOKEN_TTL_DAYS, ChronoUnit.DAYS),
            )
        tokens.insert(row)
        return IssuedRefresh(raw, row)
    }

    fun rotate(
        rawToken: String,
        deviceFingerprintHash: String?,
    ): IssuedRefresh {
        val now = nowProvider()
        val existing = tokens.findByHash(sha256Hex(rawToken)) ?: throw RefreshTokenInvalidException()

        if (existing.revokedAt != null || existing.expiresAt.isBefore(now)) {
            throw RefreshTokenInvalidException()
        }

        if (existing.usedAt != null) {
            val elapsed = ChronoUnit.SECONDS.between(existing.usedAt, now)
            if (elapsed > REFRESH_OVERLAP_SECONDS) {
                tokens.revokeFamily(existing.familyId, now)
                users.incrementTokenVersion(existing.userId)
                throw TokenReuseException(existing.userId)
            }
        }

        tokens.markUsed(existing.id, now)
        return issue(
            userId = existing.userId,
            deviceFingerprintHash = deviceFingerprintHash,
            familyId = existing.familyId,
        )
    }

    fun revokeSingle(
        callerUserId: UUID,
        rawToken: String,
    ) {
        val now = nowProvider()
        val existing = tokens.findByHash(sha256Hex(rawToken)) ?: return
        if (existing.userId != callerUserId) return
        tokens.revoke(existing.id, now)
    }

    fun revokeAll(userId: UUID) {
        tokens.deleteAllForUser(userId)
        users.incrementTokenVersion(userId)
    }

    private fun newRawToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        fun sha256Hex(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
