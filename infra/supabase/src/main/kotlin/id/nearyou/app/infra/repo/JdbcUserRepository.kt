package id.nearyou.app.infra.repo

import java.sql.ResultSet
import java.util.UUID
import javax.sql.DataSource

class JdbcUserRepository(
    private val dataSource: DataSource,
) : UserRepository {
    private val baseSelect =
        """
        SELECT id, username, display_name, email,
               google_id_hash, apple_id_hash, apple_relay_email,
               is_shadow_banned, is_banned, suspended_until,
               token_version, deleted_at
          FROM users
        """.trimIndent()

    override fun findById(id: UUID): UserRow? = single("$baseSelect WHERE id = ?") { it.setObject(1, id) }

    override fun findByGoogleIdHash(hash: String): UserRow? = single("$baseSelect WHERE google_id_hash = ?") { it.setString(1, hash) }

    override fun findByAppleIdHash(hash: String): UserRow? = single("$baseSelect WHERE apple_id_hash = ?") { it.setString(1, hash) }

    override fun incrementTokenVersion(id: UUID): Int {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "UPDATE users SET token_version = token_version + 1 WHERE id = ? RETURNING token_version",
            ).use { ps ->
                ps.setObject(1, id)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) error("user $id not found")
                    return rs.getInt(1)
                }
            }
        }
    }

    override fun setAppleRelayEmail(
        appleIdHash: String,
        enabled: Boolean,
    ): Int {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "UPDATE users SET apple_relay_email = ? WHERE apple_id_hash = ?",
            ).use { ps ->
                ps.setBoolean(1, enabled)
                ps.setString(2, appleIdHash)
                return ps.executeUpdate()
            }
        }
    }

    private fun single(
        sql: String,
        bind: (java.sql.PreparedStatement) -> Unit,
    ): UserRow? {
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                bind(ps)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    return rs.toUserRow()
                }
            }
        }
    }

    private fun ResultSet.toUserRow(): UserRow =
        UserRow(
            id = getObject("id", UUID::class.java),
            username = getString("username"),
            displayName = getString("display_name"),
            email = getString("email"),
            googleIdHash = getString("google_id_hash"),
            appleIdHash = getString("apple_id_hash"),
            appleRelayEmail = getBoolean("apple_relay_email"),
            isShadowBanned = getBoolean("is_shadow_banned"),
            isBanned = getBoolean("is_banned"),
            suspendedUntil = getTimestamp("suspended_until")?.toInstant(),
            tokenVersion = getInt("token_version"),
            deletedAt = getTimestamp("deleted_at")?.toInstant(),
        )
}
