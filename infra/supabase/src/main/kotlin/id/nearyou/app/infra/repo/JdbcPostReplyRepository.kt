package id.nearyou.app.infra.repo

import id.nearyou.app.core.domain.lint.AllowContentWriteWithoutModeration
import id.nearyou.app.core.domain.lint.AllowMissingBlockJoin
import id.nearyou.app.core.domain.lint.AllowRawPostsRead
import id.nearyou.data.repository.PostReplyRepository
import id.nearyou.data.repository.PostReplyRow
import java.sql.Connection
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
 *   `visible_posts`, `user_blocks`, `blocker_id =`, `blocked_id =`, plus the
 *   shadow-ban-feed-self-visibility own-content self arm).
 * - The [listByPost] literal LEFT JOINs `visible_users` with the
 *   `(vu.id IS NOT NULL OR pr.author_id = :viewer)` author bypass (shadow-ban
 *   exclusion except the caller's own replies) AND applies bidirectional
 *   `user_blocks` NOT-IN on `post_replies.author_id` — `BlockExclusionJoinRule`
 *   passes on the single literal.
 * - There is intentionally NO hard delete against `post_replies` anywhere in this
 *   file (post-replies-v8 design Decision 2: soft-delete only). The build-time grep
 *   guard in ReplyEndpointsTest asserts this literal-free invariant.
 */
class JdbcPostReplyRepository(
    private val dataSource: DataSource,
) : PostReplyRepository {
    // ReplyService runs TextModerator.moderate() before either insert sink
    // (PostRepliesModerationIntegrationTest pins the call order).
    @AllowContentWriteWithoutModeration("service_layer_moderated")
    @AllowMissingBlockJoin(
        "self-identity projection of the just-inserted own reply (mobile-block-from-content D7): " +
            "the JOIN users reads only the CALLER's own username/display_name for the 201 body — " +
            "no cross-user surface, nothing to block-exclude",
    )
    override fun insert(
        postId: UUID,
        authorId: UUID,
        content: String,
    ): PostReplyRow {
        dataSource.connection.use { conn ->
            conn.prepareStatement(INSERT_RETURNING_WITH_IDENTITY).use { ps ->
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
        // Canonical reply-list query (post-replies spec Requirement
        // "GET replies — canonical query with block exclusion and auto-hidden filter"):
        //  - LEFT JOIN visible_users + (vu.id IS NOT NULL OR pr.author_id = :viewer) —
        //    shadow-ban exclusion on the reply author EXCEPT the caller's own replies
        //    (shadow-ban-feed-self-visibility: a shadow-banned user still sees their OWN
        //    replies in any thread they can read; for non-author rows the predicate is
        //    exactly the previous INNER JOIN semantics).
        //  - Bidirectional user_blocks NOT-IN on pr.author_id (BlockExclusionJoinRule
        //    passes on the combined literal — four tokens present).
        //  - deleted_at IS NULL excludes soft-deleted rows (including the author's own).
        //  - (is_auto_hidden = FALSE OR author_id = :viewer) — author still sees
        //    their own auto-hidden replies; everyone else does not.
        //  - Author display identity via raw `users au` (mobile-block-from-content D7): every
        //    returned row has already passed the visibility predicates, and the caller's OWN
        //    shadow-banned replies (author bypass) have no visible_users row — so identity MUST
        //    come from raw users; the join changes no visibility predicate (same block tokens,
        //    BlockExclusionJoinRule passes on the single literal).
        //  - Keyset on (created_at DESC, id DESC) via post_replies_post_idx.
        val sql =
            buildString {
                append(
                    """
                    SELECT pr.id,
                           pr.post_id,
                           pr.author_id,
                           au.username AS author_username,
                           au.display_name AS author_display_name,
                           pr.content,
                           pr.is_auto_hidden,
                           pr.created_at,
                           pr.updated_at
                      FROM post_replies pr
                      LEFT JOIN visible_users vu ON vu.id = pr.author_id
                      JOIN users au ON au.id = pr.author_id
                     WHERE pr.post_id = ?
                       AND pr.deleted_at IS NULL
                       AND (vu.id IS NOT NULL OR pr.author_id = ?)
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
                ps.setObject(i++, viewerId) // shadow-ban author bypass (vu.id IS NOT NULL OR ...)
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

    @AllowRawPostsRead(
        "own-content self-arm (shadow-ban-feed-self-visibility): the raw posts arm is scoped " +
            "to id = :post_id AND author_id = :viewer so a shadow-banned author can still " +
            "reply to their own post / read its reply thread; other viewers resolve via the " +
            "visible_posts arm and keep the constant opaque 404",
    )
    override fun resolveVisiblePost(
        postId: UUID,
        viewerId: UUID,
    ): UUID? {
        // Shape-identical to JdbcPostLikeRepository.resolveVisiblePost — same four
        // lint tokens + the same own-content self arm (raw posts, author_id = :viewer,
        // deleted_at IS NULL), same opaque-404 semantics at the HTTP layer.
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT p.id
                  FROM visible_posts p
                 WHERE p.id = ?
                   AND p.author_id NOT IN (SELECT blocked_id FROM user_blocks WHERE blocker_id = ?)
                   AND p.author_id NOT IN (SELECT blocker_id FROM user_blocks WHERE blocked_id = ?)
                UNION ALL
                SELECT p.id
                  FROM posts p
                 WHERE p.id = ?
                   AND p.author_id = ?
                   AND p.deleted_at IS NULL
                 LIMIT 1
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, postId)
                ps.setObject(2, viewerId)
                ps.setObject(3, viewerId)
                ps.setObject(4, postId)
                ps.setObject(5, viewerId)
                ps.executeQuery().use { rs ->
                    return if (rs.next()) rs.getObject("id", UUID::class.java) else null
                }
            }
        }
    }

    @AllowContentWriteWithoutModeration("service_layer_moderated")
    @AllowMissingBlockJoin(
        "self-identity projection of the just-inserted own reply (mobile-block-from-content D7): " +
            "the JOIN users reads only the CALLER's own username/display_name for the 201 body — " +
            "no cross-user surface, nothing to block-exclude",
    )
    override fun insertInTx(
        conn: Connection,
        postId: UUID,
        authorId: UUID,
        content: String,
    ): PostReplyRow {
        conn.prepareStatement(INSERT_RETURNING_WITH_IDENTITY).use { ps ->
            ps.setObject(1, postId)
            ps.setObject(2, authorId)
            ps.setString(3, content)
            ps.executeQuery().use { rs ->
                check(rs.next()) { "INSERT ... RETURNING produced no row" }
                return rs.toRow()
            }
        }
    }

    @AllowRawPostsRead(
        "reply-notification recipient lookup (author_id only) — visibility already " +
            "resolved by resolveVisiblePost earlier in the same service flow",
    )
    @AllowMissingBlockJoin("notification emitter suppresses blocked/self recipients downstream; this read returns no content surface")
    override fun loadParentAuthorId(
        conn: Connection,
        parentPostId: UUID,
    ): UUID? {
        conn.prepareStatement(
            "SELECT author_id FROM posts WHERE id = ? AND deleted_at IS NULL",
        ).use { ps ->
            ps.setObject(1, parentPostId)
            ps.executeQuery().use { rs ->
                return if (rs.next()) rs.getObject("author_id", UUID::class.java) else null
            }
        }
    }

    private fun ResultSet.toRow(): PostReplyRow =
        PostReplyRow(
            id = getObject("id", UUID::class.java),
            postId = getObject("post_id", UUID::class.java),
            authorId = getObject("author_id", UUID::class.java),
            authorUsername = getString("author_username"),
            authorDisplayName = getString("author_display_name"),
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
            authorUsername = getString("author_username"),
            authorDisplayName = getString("author_display_name"),
            content = getString("content"),
            isAutoHidden = getBoolean("is_auto_hidden"),
            createdAt = getTimestamp("created_at").toInstant(),
            updatedAt = getTimestamp("updated_at")?.toInstant(),
            deletedAt = null,
        )

    private companion object {
        // INSERT ... RETURNING wrapped in a CTE so the 201 body carries the CALLER's own display
        // identity (mobile-block-from-content D7) in the same statement — a raw-`users` read of
        // self, so a shadow-banned caller still gets their own identity.
        const val INSERT_RETURNING_WITH_IDENTITY: String =
            """
            WITH ins AS (
                INSERT INTO post_replies (post_id, author_id, content)
                VALUES (?, ?, ?)
                RETURNING id, post_id, author_id, content, is_auto_hidden, created_at, updated_at, deleted_at
            )
            SELECT ins.*, u.username AS author_username, u.display_name AS author_display_name
              FROM ins
              JOIN users u ON u.id = ins.author_id
            """
    }
}
