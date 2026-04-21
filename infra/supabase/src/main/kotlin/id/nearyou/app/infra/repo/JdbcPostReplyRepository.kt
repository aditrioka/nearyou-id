package id.nearyou.app.infra.repo

import id.nearyou.data.repository.PostReplyRepository
import id.nearyou.data.repository.PostReplyRow
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * JDBC implementation of [PostReplyRepository]. SQL lives here so the interface in
 * `:core:data` stays DB-agnostic.
 *
 * - The [resolveVisiblePost] literal is byte-identical in shape to
 *   [JdbcPostLikeRepository.resolveVisiblePost] (four lint-required tokens:
 *   `visible_posts`, `user_blocks`, `blocker_id =`, `blocked_id =`).
 * - The [listByPost] literal JOINs `visible_users` (shadow-ban exclusion) AND
 *   applies bidirectional `user_blocks` NOT-IN on `post_replies.author_id` —
 *   `BlockExclusionJoinRule` passes on the single literal.
 * - There is intentionally NO hard delete against `post_replies` anywhere in this
 *   file (post-replies-v8 design Decision 2: soft-delete only). The build-time grep
 *   guard in ReplyEndpointsTest asserts this literal-free invariant.
 */
class JdbcPostReplyRepository(
    private val dataSource: DataSource,
) : PostReplyRepository {
    override fun insert(
        postId: UUID,
        authorId: UUID,
        content: String,
    ): PostReplyRow {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO post_replies (post_id, author_id, content)
                VALUES (?, ?, ?)
                RETURNING id, post_id, author_id, content, is_auto_hidden, created_at, updated_at, deleted_at
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, postId)
                ps.setObject(2, authorId)
                ps.setString(3, content)
                ps.executeQuery().use { rs ->
                    check(rs.next()) { "INSERT ... RETURNING produced no row" }
                    return rs.toRow()
                }
            }
        }
    }

    override fun listByPost(
        postId: UUID,
        viewerId: UUID,
        cursorCreatedAt: Instant?,
        cursorReplyId: UUID?,
        limit: Int,
    ): List<PostReplyRow> {
        // Canonical reply-list query (post-replies-v8 spec Requirement
        // "GET replies — canonical query with block exclusion and auto-hidden filter"):
        //  - JOIN visible_users for shadow-ban exclusion on the reply author.
        //  - Bidirectional user_blocks NOT-IN on pr.author_id (BlockExclusionJoinRule
        //    passes on the combined literal — four tokens present).
        //  - deleted_at IS NULL excludes soft-deleted rows.
        //  - (is_auto_hidden = FALSE OR author_id = :viewer) — author still sees
        //    their own auto-hidden replies; everyone else does not.
        //  - Keyset on (created_at DESC, id DESC) via post_replies_post_idx.
        val sql =
            buildString {
                append(
                    """
                    SELECT pr.id,
                           pr.post_id,
                           pr.author_id,
                           pr.content,
                           pr.is_auto_hidden,
                           pr.created_at,
                           pr.updated_at
                      FROM post_replies pr
                      JOIN visible_users vu ON vu.id = pr.author_id
                     WHERE pr.post_id = ?
                       AND pr.deleted_at IS NULL
                       AND (pr.is_auto_hidden = FALSE OR pr.author_id = ?)
                       AND pr.author_id NOT IN (SELECT blocked_id FROM user_blocks WHERE blocker_id = ?)
                       AND pr.author_id NOT IN (SELECT blocker_id FROM user_blocks WHERE blocked_id = ?)
                    """.trimIndent(),
                )
                if (cursorCreatedAt != null && cursorReplyId != null) {
                    append("\n   AND (pr.created_at, pr.id) < (?, ?)")
                }
                append("\n ORDER BY pr.created_at DESC, pr.id DESC")
                append("\n LIMIT ?")
            }
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                var i = 1
                ps.setObject(i++, postId)
                ps.setObject(i++, viewerId) // is_auto_hidden author bypass
                ps.setObject(i++, viewerId) // blocker_id direction
                ps.setObject(i++, viewerId) // blocked_id direction
                if (cursorCreatedAt != null && cursorReplyId != null) {
                    ps.setTimestamp(i++, Timestamp.from(cursorCreatedAt))
                    ps.setObject(i++, cursorReplyId)
                }
                ps.setInt(i, limit)
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<PostReplyRow>()
                    while (rs.next()) out += rs.toRowListShape()
                    return out
                }
            }
        }
    }

    override fun softDeleteOwn(
        replyId: UUID,
        authorId: UUID,
    ) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "UPDATE post_replies SET deleted_at = NOW() WHERE id = ? AND author_id = ? AND deleted_at IS NULL",
            ).use { ps ->
                ps.setObject(1, replyId)
                ps.setObject(2, authorId)
                ps.executeUpdate()
            }
        }
    }

    override fun resolveVisiblePost(
        postId: UUID,
        viewerId: UUID,
    ): UUID? {
        // Shape-identical to JdbcPostLikeRepository.resolveVisiblePost — same four
        // lint tokens, same opaque-404 semantics at the HTTP layer.
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT p.id
                  FROM visible_posts p
                 WHERE p.id = ?
                   AND p.author_id NOT IN (SELECT blocked_id FROM user_blocks WHERE blocker_id = ?)
                   AND p.author_id NOT IN (SELECT blocker_id FROM user_blocks WHERE blocked_id = ?)
                 LIMIT 1
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, postId)
                ps.setObject(2, viewerId)
                ps.setObject(3, viewerId)
                ps.executeQuery().use { rs ->
                    return if (rs.next()) rs.getObject("id", UUID::class.java) else null
                }
            }
        }
    }

    private fun ResultSet.toRow(): PostReplyRow =
        PostReplyRow(
            id = getObject("id", UUID::class.java),
            postId = getObject("post_id", UUID::class.java),
            authorId = getObject("author_id", UUID::class.java),
            content = getString("content"),
            isAutoHidden = getBoolean("is_auto_hidden"),
            createdAt = getTimestamp("created_at").toInstant(),
            updatedAt = getTimestamp("updated_at")?.toInstant(),
            deletedAt = getTimestamp("deleted_at")?.toInstant(),
        )

    // Row shape used by listByPost — deleted_at is always NULL by construction (the
    // query filters soft-deleted rows), so we don't read the column. Kept as a
    // separate helper so the INSERT RETURNING path still exercises every column.
    private fun ResultSet.toRowListShape(): PostReplyRow =
        PostReplyRow(
            id = getObject("id", UUID::class.java),
            postId = getObject("post_id", UUID::class.java),
            authorId = getObject("author_id", UUID::class.java),
            content = getString("content"),
            isAutoHidden = getBoolean("is_auto_hidden"),
            createdAt = getTimestamp("created_at").toInstant(),
            updatedAt = getTimestamp("updated_at")?.toInstant(),
            deletedAt = null,
        )
}
