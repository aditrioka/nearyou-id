package id.nearyou.app.infra.repo

import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class JdbcUserBlockRepository(
    private val dataSource: DataSource,
) : UserBlockRepository {
    /**
     * Transactional cascade: `user_blocks` INSERT + bidirectional `follows` DELETE land
     * together, per `docs/05-Implementation.md §1286–1300` and the `user-blocking`
     * "follow-cascade enforced at block time" requirement. Without the transaction, a
     * crash between INSERT and DELETE would leave a `user_blocks` row + a `follows` row
     * for the same pair — violating the invariant "a block implies no follow in either
     * direction."
     *
     * Pattern mirrors `JdbcPostRepository.createPost()` via `CreatePostService.create`:
     * `autoCommit = false` + explicit `commit()/rollback()`. No generic `@Transactional`
     * helper — one call site does not motivate one.
     */
    override fun create(
        blockerId: UUID,
        blockedId: UUID,
    ): Boolean {
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                val inserted =
                    conn.prepareStatement(
                        "INSERT INTO user_blocks (blocker_id, blocked_id) VALUES (?, ?) ON CONFLICT (blocker_id, blocked_id) DO NOTHING",
                    ).use { ps ->
                        ps.setObject(1, blockerId)
                        ps.setObject(2, blockedId)
                        ps.executeUpdate() == 1
                    }
                conn.prepareStatement(
                    """
                    DELETE FROM follows
                     WHERE (follower_id = ? AND followee_id = ?)
                        OR (follower_id = ? AND followee_id = ?)
                    """.trimIndent(),
                ).use { ps ->
                    ps.setObject(1, blockerId)
                    ps.setObject(2, blockedId)
                    ps.setObject(3, blockedId)
                    ps.setObject(4, blockerId)
                    ps.executeUpdate()
                }
                conn.commit()
                return inserted
            } catch (t: Throwable) {
                conn.rollback()
                throw t
            } finally {
                conn.autoCommit = true
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
