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
                // Pair lock serializes against a concurrent follow on the same
                // pair (see JdbcUserFollowsRepository.followInTx + UserPairLock
                // KDoc — 2026-06-10 audit, finding 03-#4).
                id.nearyou.app.infra.db.UserPairLock.acquire(conn, blockerId, blockedId)
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
        // LEFT JOIN visible_users + COALESCE placeholders (the chat-conversations partner
        // pattern, byte-matching ChatRepository.listMyConversations): a hidden blocked user
        // keeps their row — the list is the owner's only unblock handle — with masked
        // identity. Masking is driven ONLY by visible_users membership, never block state
        // (rows ARE the owner's blocks; counter-block masking would leak "blocked back").
        // Keyset axis stays (b.created_at, b.blocked_id) — the join feeds projection only.
        val sql =
            buildString {
                append(
                    """
                    SELECT b.blocked_id,
                           COALESCE(u.username, 'akun_dihapus') AS username,
                           COALESCE(u.display_name, 'Akun Dihapus') AS display_name,
                           COALESCE(u.subscription_status = 'premium_active', FALSE) AS is_premium,
                           b.created_at
                      FROM user_blocks b
                      LEFT JOIN visible_users u ON u.id = b.blocked_id
                     WHERE b.blocker_id = ?
                    """.trimIndent(),
                )
                if (cursorCreatedAt != null && cursorBlockedId != null) {
                    append("\n   AND (b.created_at, b.blocked_id) < (?, ?)")
                }
                append("\n ORDER BY b.created_at DESC, b.blocked_id DESC")
                append("\n LIMIT ?")
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
                                username = rs.getString("username"),
                                displayName = rs.getString("display_name"),
                                isPremium = rs.getBoolean("is_premium"),
                                createdAt = rs.getTimestamp("created_at").toInstant(),
                            )
                    }
                    return out
                }
            }
        }
    }
}
