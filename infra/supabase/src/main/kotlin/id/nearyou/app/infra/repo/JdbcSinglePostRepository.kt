package id.nearyou.app.infra.repo

import id.nearyou.app.core.domain.lint.AllowRawPostsRead
import java.util.UUID
import javax.sql.DataSource

class JdbcSinglePostRepository(
    private val dataSource: DataSource,
) : SinglePostRepository {
    @AllowRawPostsRead(
        "own-content self-arm (shadow-ban-feed-self-visibility): the raw posts/users reads are " +
            "scoped to id = :post_id AND author_id = :viewer so a shadow-banned author can still " +
            "read their own post by id; every other viewer resolves via the visible_posts arm and " +
            "keeps the constant opaque 404. The visible arm's author-identity JOIN is raw `users` " +
            "(not visible_users) so a tombstoned author's post surfaces anonymized as 'Akun Dihapus' " +
            "(account-deletion-tombstone V24) — shadow-ban-safe because visible_posts already " +
            "excludes shadow-banned/soft-deleted-post rows; block exclusion stays via the NOT-IN",
    )
    override fun findById(
        viewerId: UUID,
        postId: UUID,
    ): SinglePostRow? {
        // Single-post visibility gate + no-coordinate projection. Two arms (resolveVisiblePost shape,
        // Global projection), keyed on `p.id = ?` instead of a keyset:
        //  - Visible arm: FROM visible_posts (V24 author shadow-ban / post-soft-delete / auto-hide
        //    filters; V24 surfaces tombstoned authors) with bidirectional user_blocks NOT-IN
        //    (BlockExclusionJoinRule: the literal carries `visible_posts` + `user_blocks` + both
        //    `blocker_id =` / `blocked_id =` tokens). Author identity via raw `users` so a
        //    tombstoned author renders anonymized ('Akun Dihapus') — shadow-ban-safe since
        //    visible_posts already excluded shadow-banned authors.
        //  - Self arm: docs/05 own-content exception — raw posts scoped to author_id = :viewer
        //    AND deleted_at IS NULL (a shadow-banned/auto-hidden author still reads their OWN
        //    post; own soft-deleted stays 404). No user_blocks subqueries (self-blocks
        //    CHECK-impossible). Identity via raw users (a shadow-banned author has no
        //    visible_users row).
        //  - Arms may overlap for a normal author's own visible post — LIMIT 1 over identical
        //    ids makes that harmless (resolveVisiblePost precedent).
        //  - LEFT JOIN post_likes for `liked_by_viewer` (PK-scoped; <=1 match).
        //  - LEFT JOIN LATERAL reply counter with the shadow-ban exclusion (JOIN visible_users);
        //    viewer-block exclusion DELIBERATELY NOT applied (public viewer-independent counter,
        //    the documented post-replies-v8 Decision 5 tradeoff).
        //  - LEFT JOIN LATERAL MAX(post_edits.edited_at) for `edited_at` (mobile-post-editing
        //    "Diedit" label), keyed on the RESOLVED p.id so an invisible/blocked post yields no
        //    edit-existence signal; post_edits is in neither lint rule's pattern and lives in THIS
        //    same literal so the four block tokens stay co-located (BlockExclusionJoinRule integrity).
        //  - No display_location / coordinates projected at all (the #202 coordinate discipline).
        //  - p.author_id backs the per-viewer is_author flag AND (mobile-block-from-content D1) the
        //    row's authorUserId — timeline-wire parity, never rendered client-side, block-action-only.
        val sql =
            """
            SELECT p.id,
                   p.author_id,
                   p.author_username,
                   p.author_display_name,
                   p.content,
                   p.city_name,
                   p.image_id,
                   p.created_at,
                   (pl.user_id IS NOT NULL) AS liked_by_viewer,
                   c.n AS reply_count,
                   e.edited_at AS edited_at
              FROM (
                  (
                      SELECT p.id, p.author_id, u.username AS author_username,
                             u.display_name AS author_display_name, p.content,
                             p.city_name, p.image_id, p.created_at
                        FROM visible_posts p
                        JOIN users u ON u.id = p.author_id
                       WHERE p.id = ?
                         AND p.author_id NOT IN (SELECT blocked_id FROM user_blocks WHERE blocker_id = ?)
                         AND p.author_id NOT IN (SELECT blocker_id FROM user_blocks WHERE blocked_id = ?)
                  )
                  UNION ALL
                  (
                      SELECT p.id, p.author_id, u.username AS author_username,
                             u.display_name AS author_display_name, p.content,
                             p.city_name, p.image_id, p.created_at
                        FROM posts p
                        JOIN users u ON u.id = p.author_id
                       WHERE p.id = ?
                         AND p.author_id = ?
                         AND p.deleted_at IS NULL
                  )
                  LIMIT 1
              ) p
              LEFT JOIN post_likes pl ON pl.post_id = p.id AND pl.user_id = ?
              LEFT JOIN LATERAL (
                  SELECT COUNT(*) AS n
                    FROM post_replies pr
                    JOIN visible_users vu ON vu.id = pr.author_id
                   WHERE pr.post_id = p.id
                     AND pr.deleted_at IS NULL
              ) c ON TRUE
              LEFT JOIN LATERAL (
                  SELECT MAX(pe.edited_at) AS edited_at
                    FROM post_edits pe
                   WHERE pe.post_id = p.id
              ) e ON TRUE
            """.trimIndent()
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                var i = 1
                ps.setObject(i++, postId) // visible arm: p.id = ?
                ps.setObject(i++, viewerId) // blocker_id = ?
                ps.setObject(i++, viewerId) // blocked_id = ?
                ps.setObject(i++, postId) // self arm: p.id = ?
                ps.setObject(i++, viewerId) // self arm: p.author_id = ?
                ps.setObject(i, viewerId) // pl.user_id = ?
                ps.executeQuery().use { rs ->
                    return if (rs.next()) {
                        val authorId = rs.getObject("author_id", UUID::class.java)
                        SinglePostRow(
                            id = rs.getObject("id", UUID::class.java),
                            // author_id backs the per-viewer authorship flag AND (as of
                            // mobile-block-from-content) the authorUserId wire field — timeline-wire
                            // parity, never rendered client-side, block-action-only (design D1).
                            isAuthor = authorId == viewerId,
                            authorUserId = authorId,
                            authorUsername = rs.getString("author_username"),
                            authorDisplayName = rs.getString("author_display_name"),
                            content = rs.getString("content"),
                            cityName = rs.getString("city_name"),
                            imageId = rs.getString("image_id"),
                            createdAt = rs.getTimestamp("created_at").toInstant(),
                            likedByViewer = rs.getBoolean("liked_by_viewer"),
                            replyCount = rs.getInt("reply_count"),
                            editedAt = rs.getTimestamp("edited_at")?.toInstant(),
                        )
                    } else {
                        null
                    }
                }
            }
        }
    }
}
