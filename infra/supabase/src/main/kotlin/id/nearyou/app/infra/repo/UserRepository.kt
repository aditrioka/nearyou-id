package id.nearyou.app.infra.repo

import java.sql.Connection
import java.time.Instant
import java.time.LocalDate
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

/**
 * NOT NULL input needed to insert a new `users` row at signup.
 * Zero-valued defaults in V2 (token_version, analytics_consent, etc.)
 * are left to the DB; anything that might eventually need to be set at
 * signup time (display_name, invite_code_prefix) belongs here explicitly.
 */
data class NewUserRow(
    val id: UUID,
    val username: String,
    val displayName: String,
    val dateOfBirth: LocalDate,
    val googleIdHash: String?,
    val appleIdHash: String?,
    val inviteCodePrefix: String,
    val deviceFingerprintHash: String?,
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

    /** In-transaction provider-hash collision check (used by signup). */
    fun existsByProviderHash(
        conn: Connection,
        hash: String,
        type: IdentifierType,
    ): Boolean

    /** Pre-transaction invite-code-prefix collision probe. */
    fun existsByInviteCodePrefix(prefix: String): Boolean

    /**
     * Insert a new `users` row inside the caller's transaction.
     * Throws `java.sql.SQLException` with SQLState 23505 on unique-violation
     * so the caller can catch and retry username generation.
     * Returns the row id (same as [NewUserRow.id]).
     */
    fun create(
        conn: Connection,
        row: NewUserRow,
    ): UUID
}
