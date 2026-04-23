package id.nearyou.app.infra.repo

import id.nearyou.data.repository.FollowBlockedException
import id.nearyou.data.repository.FollowListRow
import id.nearyou.data.repository.ProfileUserNotFoundException
import id.nearyou.data.repository.UserFollowsRepository
import id.nearyou.data.repository.UserNotFoundException
import java.sql.Connection
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * JDBC implementation of [UserFollowsRepository]. All SQL lives here so the interface
 * in `:core:data` stays DB-agnostic.
 *
 * Mutual-block and FK-violation translations — and the list-query block-exclusion
 * subqueries — live here intentionally: the repository owns the mapping from SQL state
 * to typed exceptions that the HTTP layer can translate into error codes.
 */
class JdbcUserFollowsRepository(
    private val dataSource: DataSource,
) : UserFollowsRepository {
    override fun follow(
        follower: UUID,
        followee: UUID,
    ) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT 1 FROM user_blocks
                 WHERE (blocker_id = ? AND blocked_id = ?)
                    OR (blocker_id = ? AND blocked_id = ?)
                 LIMIT 1
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, follower)
                ps.setObject(2, followee)
                ps.setObject(3, followee)
                ps.setObject(4, follower)
                ps.executeQuery().use { rs ->
                    if (rs.next()) throw FollowBlockedException()
                }
            }
            try {
                conn.prepareStatement(
                    "INSERT INTO follows (follower_id, followee_id) VALUES (?, ?) ON CONFLICT (follower_id, followee_id) DO NOTHING",
                ).use { ps ->
                    ps.setObject(1, follower)
                    ps.setObject(2, followee)
                    ps.executeUpdate()
                }
            } catch (ex: SQLException) {
                if (ex.sqlState == "23503") throw UserNotFoundException()
                throw ex
            }
        }
    }

    override fun unfollow(
        follower: UUID,
        followee: UUID,
    ) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "DELETE FROM follows WHERE follower_id = ? AND followee_id = ?",
            ).use { ps ->
                ps.setObject(1, follower)
                ps.setObject(2, followee)
                ps.executeUpdate()
            }
        }
    }

    override fun listFollowers(
        profileId: UUID,
        viewerId: UUID,
        cursorCreatedAt: Instant?,
        cursorUserId: UUID?,
        limit: Int,
    ): List<FollowListRow> {
        dataSource.connection.use { conn ->
            ensureProfileExists(conn, profileId)
            val sql =
                buildString {
                    append(
                        """
                        SELECT f.follower_id AS user_id, f.created_at
                          FROM follows f
                         WHERE f.followee_id = ?
                           AND f.follower_id NOT IN (SELECT blocked_id FROM user_blocks WHERE blocker_id = ?)
                           AND f.follower_id NOT IN (SELECT blocker_id FROM user_blocks WHERE blocked_id = ?)
                        """.trimIndent(),
                    )
                    if (cursorCreatedAt != null && cursorUserId != null) {
                        append("\n   AND (f.created_at, f.follower_id) < (?, ?)")
                    }
                    append("\n ORDER BY f.created_at DESC, f.follower_id DESC")
                    append("\n LIMIT ?")
                }
            return conn.queryFollowListPage(sql, profileId, viewerId, cursorCreatedAt, cursorUserId, limit)
        }
    }

    override fun listFollowing(
        profileId: UUID,
        viewerId: UUID,
        cursorCreatedAt: Instant?,
        cursorUserId: UUID?,
        limit: Int,
    ): List<FollowListRow> {
        dataSource.connection.use { conn ->
            ensureProfileExists(conn, profileId)
            val sql =
                buildString {
                    append(
                        """
                        SELECT f.followee_id AS user_id, f.created_at
                          FROM follows f
                         WHERE f.follower_id = ?
                           AND f.followee_id NOT IN (SELECT blocked_id FROM user_blocks WHERE blocker_id = ?)
                           AND f.followee_id NOT IN (SELECT blocker_id FROM user_blocks WHERE blocked_id = ?)
                        """.trimIndent(),
                    )
                    if (cursorCreatedAt != null && cursorUserId != null) {
                        append("\n   AND (f.created_at, f.followee_id) < (?, ?)")
                    }
                    append("\n ORDER BY f.created_at DESC, f.followee_id DESC")
                    append("\n LIMIT ?")
                }
            return conn.queryFollowListPage(sql, profileId, viewerId, cursorCreatedAt, cursorUserId, limit)
        }
    }

    override fun followInTx(
        conn: Connection,
        follower: UUID,
        followee: UUID,
    ): Boolean {
        conn.prepareStatement(
            """
            SELECT 1 FROM user_blocks
             WHERE (blocker_id = ? AND blocked_id = ?)
                OR (blocker_id = ? AND blocked_id = ?)
             LIMIT 1
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, follower)
            ps.setObject(2, followee)
            ps.setObject(3, followee)
            ps.setObject(4, follower)
            ps.executeQuery().use { rs ->
                if (rs.next()) throw FollowBlockedException()
            }
        }
        try {
            conn.prepareStatement(
                "INSERT INTO follows (follower_id, followee_id) VALUES (?, ?) ON CONFLICT (follower_id, followee_id) DO NOTHING",
            ).use { ps ->
                ps.setObject(1, follower)
                ps.setObject(2, followee)
                return ps.executeUpdate() == 1
            }
        } catch (ex: SQLException) {
            if (ex.sqlState == "23503") throw UserNotFoundException()
            throw ex
        }
    }

    private fun ensureProfileExists(
        conn: java.sql.Connection,
        profileId: UUID,
    ) {
        conn.prepareStatement("SELECT 1 FROM users WHERE id = ?").use { ps ->
            ps.setObject(1, profileId)
            ps.executeQuery().use { rs ->
                if (!rs.next()) throw ProfileUserNotFoundException()
            }
        }
    }

    private fun java.sql.Connection.queryFollowListPage(
        sql: String,
        profileId: UUID,
        viewerId: UUID,
        cursorCreatedAt: Instant?,
        cursorUserId: UUID?,
        limit: Int,
    ): List<FollowListRow> {
        prepareStatement(sql).use { ps ->
            var i = 1
            ps.setObject(i++, profileId)
            ps.setObject(i++, viewerId)
            ps.setObject(i++, viewerId)
            if (cursorCreatedAt != null && cursorUserId != null) {
                ps.setTimestamp(i++, Timestamp.from(cursorCreatedAt))
                ps.setObject(i++, cursorUserId)
            }
            ps.setInt(i, limit)
            ps.executeQuery().use { rs ->
                val out = mutableListOf<FollowListRow>()
                while (rs.next()) {
                    out +=
                        FollowListRow(
                            userId = rs.getObject("user_id", UUID::class.java),
                            createdAt = rs.getTimestamp("created_at").toInstant(),
                        )
                }
                return out
            }
        }
    }
}
