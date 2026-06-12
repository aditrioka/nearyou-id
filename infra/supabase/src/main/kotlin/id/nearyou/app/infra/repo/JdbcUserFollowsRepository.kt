package id.nearyou.app.infra.repo

import id.nearyou.app.core.domain.lint.AllowMissingBlockJoin
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
 *
 * Lint note: since the 2026-06-11 backend review, `:infra:supabase` IS scanned by the
 * custom ruleset (`nearyou.detekt` convention plugin) — the raw-`users` self probe
 * [SQL_SELF_EXISTS] therefore carries `@AllowMissingBlockJoin` with its own-content
 * justification. Neither `follows` nor `visible_users` matches the rule's
 * protected-table pattern, so the list queries stay annotation-free; the
 * block-exclusion and constant-404 integration scenarios in `FollowEndpointsTest`
 * remain the behavioral guardrail, mirroring the stance `user-profile-read` documents.
 */
class JdbcUserFollowsRepository(
    private val dataSource: DataSource,
) : UserFollowsRepository {
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
            ensureProfileVisibleOn(conn, profileId, viewerId)
            val sql =
                buildString {
                    append(
                        """
                        SELECT f.follower_id AS user_id,
                               vu.username,
                               vu.display_name,
                               (vu.subscription_status = 'premium_active') AS is_premium,
                               f.created_at
                          FROM follows f
                          JOIN visible_users vu ON vu.id = f.follower_id
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
            ensureProfileVisibleOn(conn, profileId, viewerId)
            val sql =
                buildString {
                    append(
                        """
                        SELECT f.followee_id AS user_id,
                               vu.username,
                               vu.display_name,
                               (vu.subscription_status = 'premium_active') AS is_premium,
                               f.created_at
                          FROM follows f
                          JOIN visible_users vu ON vu.id = f.followee_id
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

    override fun ensureProfileVisible(
        profileId: UUID,
        viewerId: UUID,
    ) {
        dataSource.connection.use { conn ->
            ensureProfileVisibleOn(conn, profileId, viewerId)
        }
    }

    override fun followInTx(
        conn: Connection,
        follower: UUID,
        followee: UUID,
    ): Boolean {
        // Serialize against a concurrent block on the same pair: without the
        // pair lock, this SELECT-blocks-then-INSERT-follow can fully interleave
        // with JdbcUserBlockRepository.create's INSERT-block + DELETE-follows
        // under READ COMMITTED, committing a follow edge alongside a block —
        // the stale edge then resurrects on unblock (2026-06-10 audit, 03-#4).
        // Behind the service-level visibility gate this re-check is also the
        // TOCTOU backstop for the BLOCK half only: a block landing after the
        // gate still prevents the edge (mapped to the same constant 404). A
        // target hidden (shadow-banned/soft-deleted) in that same window is
        // NOT re-checked — the edge commits, which is accepted: lists filter
        // hidden users at read time and counts are deliberately raw (D1).
        id.nearyou.app.infra.db.UserPairLock.acquire(conn, follower, followee)
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

    /**
     * Connection-scoped body of [ensureProfileVisible] so the list queries reuse their
     * already-open connection (one pool checkout per list request).
     *
     * Self path reads raw `users` deliberately (own-content path): a shadow-banned
     * viewer must keep their own follower/following lists — `visible_users` would
     * filter their row and 404 their own lists. No block predicate (you cannot block
     * yourself). Precedent: `JdbcUserProfileReader.SQL_SELF`; the matching
     * `@AllowMissingBlockJoin` lives on [SQL_SELF_EXISTS] (this module is scanned by
     * the custom ruleset since 2026-06-11 — see the class-level lint note).
     */
    private fun ensureProfileVisibleOn(
        conn: Connection,
        profileId: UUID,
        viewerId: UUID,
    ) {
        if (profileId == viewerId) {
            conn.prepareStatement(SQL_SELF_EXISTS).use { ps ->
                ps.setObject(1, profileId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) throw ProfileUserNotFoundException()
                }
            }
        } else {
            conn.prepareStatement(SQL_OTHER_VISIBLE).use { ps ->
                ps.setObject(1, profileId)
                ps.setObject(2, viewerId)
                ps.setObject(3, profileId)
                ps.setObject(4, profileId)
                ps.setObject(5, viewerId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) throw ProfileUserNotFoundException()
                }
            }
        }
    }

    private fun Connection.queryFollowListPage(
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

    private companion object {
        /**
         * Self existence probe — raw `users` (own-content path); see
         * [ensureProfileVisibleOn] for the shadow-banned-viewer justification.
         */
        @AllowMissingBlockJoin(
            "own-content existence probe (profileId == viewerId): a shadow-banned viewer " +
                "must keep their own lists, and you cannot block yourself — no viewer-scoped " +
                "content is returned",
        )
        const val SQL_SELF_EXISTS: String = "SELECT 1 FROM users WHERE id = ?"

        /**
         * Other-viewer resolution — `visible_users` (shadow-ban + soft-delete safe) plus
         * the bidirectional `user_blocks` exclusion, mirroring
         * `JdbcUserProfileReader.SQL_OTHER` (the `user-profile-read` constant-404
         * contract). An absent row collapses unknown / soft-deleted / shadow-banned /
         * blocked-either-direction into one [ProfileUserNotFoundException]; the
         * both-direction 404 scenarios are the guardrail (`visible_users` is outside the
         * rule's protected-table pattern — see the class-level lint note).
         */
        val SQL_OTHER_VISIBLE: String =
            """
            SELECT 1
              FROM visible_users vu
             WHERE vu.id = ?
               AND NOT EXISTS (
                   SELECT 1 FROM user_blocks
                    WHERE (blocker_id = ? AND blocked_id = ?)
                       OR (blocker_id = ? AND blocked_id = ?)
               )
            """.trimIndent()
    }
}
