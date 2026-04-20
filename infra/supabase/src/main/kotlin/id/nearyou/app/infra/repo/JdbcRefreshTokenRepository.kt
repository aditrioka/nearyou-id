package id.nearyou.app.infra.repo

import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class JdbcRefreshTokenRepository(
    private val dataSource: DataSource,
) : RefreshTokenRepository {
    override fun insert(row: RefreshTokenRow) {
        dataSource.connection.use { conn ->
            // TODO(attestation): tighten device_fingerprint_hash to NOT NULL once Play Integrity / App Attest lands (see openspec/changes/auth-foundation/design.md Open Questions)
            conn.prepareStatement(
                """
                INSERT INTO refresh_tokens
                    (id, family_id, user_id, device_fingerprint_hash, token_hash,
                     created_at, used_at, last_used_at, revoked_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, row.id)
                ps.setObject(2, row.familyId)
                ps.setObject(3, row.userId)
                ps.setString(4, row.deviceFingerprintHash)
                ps.setString(5, row.tokenHash)
                ps.setTimestamp(6, Timestamp.from(row.createdAt))
                ps.setTimestamp(7, row.usedAt?.let(Timestamp::from))
                ps.setTimestamp(8, row.lastUsedAt?.let(Timestamp::from))
                ps.setTimestamp(9, row.revokedAt?.let(Timestamp::from))
                ps.setTimestamp(10, Timestamp.from(row.expiresAt))
                ps.executeUpdate()
            }
        }
    }

    override fun findByHash(tokenHash: String): RefreshTokenRow? {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT id, family_id, user_id, device_fingerprint_hash, token_hash,
                       created_at, used_at, last_used_at, revoked_at, expires_at
                FROM refresh_tokens WHERE token_hash = ?
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, tokenHash)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    return RefreshTokenRow(
                        id = rs.getObject("id", UUID::class.java),
                        familyId = rs.getObject("family_id", UUID::class.java),
                        userId = rs.getObject("user_id", UUID::class.java),
                        deviceFingerprintHash = rs.getString("device_fingerprint_hash"),
                        tokenHash = rs.getString("token_hash"),
                        createdAt = rs.getTimestamp("created_at").toInstant(),
                        usedAt = rs.getTimestamp("used_at")?.toInstant(),
                        lastUsedAt = rs.getTimestamp("last_used_at")?.toInstant(),
                        revokedAt = rs.getTimestamp("revoked_at")?.toInstant(),
                        expiresAt = rs.getTimestamp("expires_at").toInstant(),
                    )
                }
            }
        }
    }

    override fun markUsed(
        id: UUID,
        usedAt: Instant,
    ) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                UPDATE refresh_tokens
                   SET used_at = COALESCE(used_at, ?),
                       last_used_at = ?
                 WHERE id = ?
                """.trimIndent(),
            ).use { ps ->
                ps.setTimestamp(1, Timestamp.from(usedAt))
                ps.setTimestamp(2, Timestamp.from(usedAt))
                ps.setObject(3, id)
                ps.executeUpdate()
            }
        }
    }

    override fun revoke(
        id: UUID,
        revokedAt: Instant,
    ) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "UPDATE refresh_tokens SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL",
            ).use { ps ->
                ps.setTimestamp(1, Timestamp.from(revokedAt))
                ps.setObject(2, id)
                ps.executeUpdate()
            }
        }
    }

    override fun revokeFamily(
        familyId: UUID,
        revokedAt: Instant,
    ) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "UPDATE refresh_tokens SET revoked_at = ? WHERE family_id = ? AND revoked_at IS NULL",
            ).use { ps ->
                ps.setTimestamp(1, Timestamp.from(revokedAt))
                ps.setObject(2, familyId)
                ps.executeUpdate()
            }
        }
    }

    override fun deleteAllForUser(userId: UUID): Int {
        dataSource.connection.use { conn ->
            conn.prepareStatement("DELETE FROM refresh_tokens WHERE user_id = ?").use { ps ->
                ps.setObject(1, userId)
                return ps.executeUpdate()
            }
        }
    }
}
