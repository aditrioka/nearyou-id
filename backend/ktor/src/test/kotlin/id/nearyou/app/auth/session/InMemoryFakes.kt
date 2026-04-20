package id.nearyou.app.auth.session

import id.nearyou.app.infra.repo.IdentifierType
import id.nearyou.app.infra.repo.NewUserRow
import id.nearyou.app.infra.repo.RefreshTokenRepository
import id.nearyou.app.infra.repo.RefreshTokenRow
import id.nearyou.app.infra.repo.UserRepository
import id.nearyou.app.infra.repo.UserRow
import java.sql.Connection
import java.time.Instant
import java.util.UUID

class InMemoryRefreshTokens : RefreshTokenRepository {
    val rows = mutableMapOf<UUID, RefreshTokenRow>()

    override fun insert(row: RefreshTokenRow) {
        rows[row.id] = row
    }

    override fun findByHash(tokenHash: String): RefreshTokenRow? = rows.values.firstOrNull { it.tokenHash == tokenHash }

    override fun markUsed(
        id: UUID,
        usedAt: Instant,
    ) {
        val row = rows[id] ?: return
        rows[id] = row.copy(usedAt = row.usedAt ?: usedAt, lastUsedAt = usedAt)
    }

    override fun revoke(
        id: UUID,
        revokedAt: Instant,
    ) {
        val row = rows[id] ?: return
        if (row.revokedAt == null) rows[id] = row.copy(revokedAt = revokedAt)
    }

    override fun revokeFamily(
        familyId: UUID,
        revokedAt: Instant,
    ) {
        rows.values.filter { it.familyId == familyId && it.revokedAt == null }.forEach { row ->
            rows[row.id] = row.copy(revokedAt = revokedAt)
        }
    }

    override fun deleteAllForUser(userId: UUID): Int {
        val toDelete = rows.values.filter { it.userId == userId }.map { it.id }
        toDelete.forEach { rows.remove(it) }
        return toDelete.size
    }
}

class InMemoryUsers(initial: List<UserRow> = emptyList()) : UserRepository {
    val rows = initial.associateBy { it.id }.toMutableMap()

    override fun findById(id: UUID): UserRow? = rows[id]

    override fun findByGoogleIdHash(hash: String): UserRow? = rows.values.firstOrNull { it.googleIdHash == hash }

    override fun findByAppleIdHash(hash: String): UserRow? = rows.values.firstOrNull { it.appleIdHash == hash }

    override fun incrementTokenVersion(id: UUID): Int {
        val row = rows[id] ?: error("user $id not found")
        val next = row.tokenVersion + 1
        rows[id] = row.copy(tokenVersion = next)
        return next
    }

    override fun setAppleRelayEmail(
        appleIdHash: String,
        enabled: Boolean,
    ): Int {
        val row = rows.values.firstOrNull { it.appleIdHash == appleIdHash } ?: return 0
        rows[row.id] = row.copy(appleRelayEmail = enabled)
        return 1
    }

    override fun existsByProviderHash(
        conn: Connection,
        hash: String,
        type: IdentifierType,
    ): Boolean =
        when (type) {
            IdentifierType.GOOGLE -> rows.values.any { it.googleIdHash == hash }
            IdentifierType.APPLE -> rows.values.any { it.appleIdHash == hash }
        }

    val inviteCodePrefixes = mutableSetOf<String>()

    override fun existsByInviteCodePrefix(prefix: String): Boolean = prefix in inviteCodePrefixes

    override fun create(
        conn: Connection,
        row: NewUserRow,
    ): UUID {
        if (rows.values.any { it.username == row.username }) {
            val ex = java.sql.SQLException("duplicate username", "23505")
            throw ex
        }
        rows[row.id] =
            UserRow(
                id = row.id,
                username = row.username,
                displayName = row.displayName,
                email = null,
                googleIdHash = row.googleIdHash,
                appleIdHash = row.appleIdHash,
                appleRelayEmail = false,
                isShadowBanned = false,
                isBanned = false,
                suspendedUntil = null,
                tokenVersion = 0,
                deletedAt = null,
            )
        return row.id
    }
}

fun userRow(
    id: UUID = UUID.randomUUID(),
    googleIdHash: String? = null,
    appleIdHash: String? = null,
    isBanned: Boolean = false,
    suspendedUntil: Instant? = null,
    tokenVersion: Int = 0,
    appleRelayEmail: Boolean = false,
) = UserRow(
    id = id,
    username = "tester",
    displayName = "Tester",
    email = null,
    googleIdHash = googleIdHash,
    appleIdHash = appleIdHash,
    appleRelayEmail = appleRelayEmail,
    isShadowBanned = false,
    isBanned = isBanned,
    suspendedUntil = suspendedUntil,
    tokenVersion = tokenVersion,
    deletedAt = null,
)
