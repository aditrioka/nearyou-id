package id.nearyou.app.infra.repo

import java.time.Instant
import java.util.UUID

data class UserRow(
    val id: UUID,
    val username: String,
    val displayName: String,
    val email: String?,
    val googleIdHash: String?,
    val appleIdHash: String?,
    val appleRelayEmail: Boolean,
    val isShadowBanned: Boolean,
    val isBanned: Boolean,
    val suspendedUntil: Instant?,
    val tokenVersion: Int,
    val deletedAt: Instant?,
)

interface UserRepository {
    fun findById(id: UUID): UserRow?

    fun findByGoogleIdHash(hash: String): UserRow?

    fun findByAppleIdHash(hash: String): UserRow?

    fun incrementTokenVersion(id: UUID): Int

    fun setAppleRelayEmail(
        appleIdHash: String,
        enabled: Boolean,
    ): Int
}
