package id.nearyou.app.infra.repo

import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class JdbcUserBlockRepository(
    private val dataSource: DataSource,
) : UserBlockRepository {
    override fun create(
        blockerId: UUID,
        blockedId: UUID,
    ): Boolean {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "INSERT INTO user_blocks (blocker_id, blocked_id) VALUES (?, ?) ON CONFLICT (blocker_id, blocked_id) DO NOTHING",
            ).use { ps ->
                ps.setObject(1, blockerId)
                ps.setObject(2, blockedId)
                return ps.executeUpdate() == 1
            }
        }
    }

    override fun delete(
        blockerId: UUID,
        blockedId: UUID,
    ): Int {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "DELETE FROM user_blocks WHERE blocker_id = ? AND blocked_id = ?",
            ).use { ps ->
                ps.setObject(1, blockerId)
                ps.setObject(2, blockedId)
                return ps.executeUpdate()
            }
        }
    }

    override fun listOutbound(
        blockerId: UUID,
        cursorCreatedAt: Instant?,
        cursorBlockedId: UUID?,
        limit: Int,
    ): List<UserBlockRow> {
        val sql =
            buildString {
                append("SELECT blocked_id, created_at FROM user_blocks WHERE blocker_id = ?")
                if (cursorCreatedAt != null && cursorBlockedId != null) {
                    append(" AND (created_at, blocked_id) < (?, ?)")
                }
                append(" ORDER BY created_at DESC, blocked_id DESC LIMIT ?")
            }
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                var i = 1
                ps.setObject(i++, blockerId)
                if (cursorCreatedAt != null && cursorBlockedId != null) {
                    ps.setTimestamp(i++, Timestamp.from(cursorCreatedAt))
                    ps.setObject(i++, cursorBlockedId)
                }
                ps.setInt(i, limit)
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<UserBlockRow>()
                    while (rs.next()) {
                        out +=
                            UserBlockRow(
                                blockedId = rs.getObject("blocked_id", UUID::class.java),
                                createdAt = rs.getTimestamp("created_at").toInstant(),
                            )
                    }
                    return out
                }
            }
        }
    }
}
