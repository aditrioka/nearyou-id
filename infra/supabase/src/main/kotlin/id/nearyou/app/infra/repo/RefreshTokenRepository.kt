package id.nearyou.app.infra.repo

import java.sql.Connection
import java.time.Instant
import java.util.UUID

data class RefreshTokenRow(
    val id: UUID,
    val familyId: UUID,
    val userId: UUID,
    val deviceFingerprintHash: String?,
    val tokenHash: String,
    val createdAt: Instant,
    val usedAt: Instant?,
    val lastUsedAt: Instant?,
    val revokedAt: Instant?,
    val expiresAt: Instant,
)

interface RefreshTokenRepository {
    fun insert(row: RefreshTokenRow)

    fun findByHash(tokenHash: String): RefreshTokenRow?

    fun markUsed(
        id: UUID,
        usedAt: Instant,
    )

    fun revoke(
        id: UUID,
        revokedAt: Instant,
    )

    fun revokeFamily(
        familyId: UUID,
        revokedAt: Instant,
    )

    fun deleteAllForUser(userId: UUID): Int

    /** In-transaction variant (logout-all runs this atomically with the token_version bump + FCM deletes). */
    fun deleteAllForUser(
        conn: Connection,
        userId: UUID,
    ): Int
}
