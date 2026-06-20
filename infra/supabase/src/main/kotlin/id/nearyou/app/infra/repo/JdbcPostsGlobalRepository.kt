package id.nearyou.app.infra.repo

import id.nearyou.app.core.domain.lint.AllowRawPostsRead
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

interface PostsGlobalRepository {
    /**
     * Canonical Global query — returns up to [limit] rows: `visible_posts` with
     * bidirectional `user_blocks` exclusion for everyone else's posts, UNION ALL'd with the
     * viewer's own-content self arm (shadow-ban-feed-self-visibility — a shadow-banned
     * author still sees their own posts). No follows filter, no spatial filter,
     * no `distance_m`. Projects the pre-existing `posts.city_name` column populated by the
     * V11 `posts_set_city_tg` trigger. Keyset on `(created_at DESC, id DESC)`.
     *
     * [TimelineRow.distanceMeters] is always `0.0` (no geo reference point); the HTTP route
     * MUST NOT surface `distance_m` in the response — Global is chronological-over-every-
     * visible-author, not geographic.
     */
    fun global(
        viewerId: UUID,
        cursorCreatedAt: Instant?,
        cursorPostId: UUID?,
        limit: Int,
    ): List<TimelineRow>
}

class JdbcPostsGlobalRepository(
    private val dataSource: DataSource,
) : PostsGlobalRepository {
    @AllowRawPostsRead(
        "own-content self-arm (shadow-ban-feed-self-visibility): the raw posts/users reads are " +
            "scoped to author_id = :viewer so a shadow-banned author keeps seeing their own posts " +
            "in Global; every other viewer's rows come from the visible_posts arm",
    )
    override fun global(
        viewerId: UUID,
        cursorCreatedAt: Instant?,
        cursorPostId: UUID?,
        limit: Int,
    ): List<TimelineRow> {
        // Canonical Global query per global-timeline spec — viewer-aware two-arm UNION ALL
        // since shadow-ban-feed-self-visibility:
        //  - Visible arm: FROM visible_posts (V24 filters; surfaces tombstoned authors) restricted
        //    to author_id <> :viewer, bidirectional user_blocks NOT-IN (BlockExclusionJoinRule:
        //    blocker_id = ? AND blocked_id = ? both present). Author identity via raw `users` so a
        //    tombstoned author renders anonymized ('Akun Dihapus') — shadow-ban-safe since
        //    visible_posts already excluded shadow-banned authors (account-deletion-tombstone).
        //  - Self arm: docs/05 own-content exception at the feed layer — raw posts scoped to
        //    author_id = :viewer AND deleted_at IS NULL (no is_auto_hidden / shadow-ban filter:
        //    both are invisible-to-the-author moderation states; soft-deleted stays hidden).
        //    No user_blocks subqueries (self-blocks CHECK-impossible). Identity via raw users
        //    (a shadow-banned author has no visible_users row).
        //  - Arms disjoint (author_id <> :viewer), each parenthesized with its own keyset +
        //    ORDER BY + LIMIT (posts_timeline_cursor_idx / posts_author_idx, top-N early exit);
        //    outer merge over <= 2 * limit rows.
        //  - No follows filter (Global surfaces every visible author).
        //  - LEFT JOIN post_likes for `liked_by_viewer` (PK-scoped; cardinality invariant).
        //  - LEFT JOIN LATERAL reply counter; shadow-ban exclusion on the counter — INCLUDING
        //    the viewer's own self-arm rows (counts are public + viewer-independent); viewer-
        //    block exclusion DELIBERATELY NOT applied (privacy tradeoff, same as Nearby /
        //    Following per post-replies-v8 Decision 5).
        //  - p.city_name projected through each arm (populated by V11 trigger). NULL for
        //    legacy rows + polygon-coverage gaps; HTTP layer maps to "".
        //  - No ST_Contains / no admin_regions JOIN / no ST_DWithin at read time — the
        //    denormalized column is the hot-path contract (region-polygons capability:
        //    "No read-time reverse-geocoding").
        //
        // Lint literal-structure pin (design Decision 6): BlockExclusionJoinRule checks each
        // string template in isolation, so the self arm MUST live in this same template as the
        // four block-exclusion tokens — the conditional keyset fragment is interpolated
        // ($cursorPredicate), NOT appended as a separate literal.
        val cursorPredicate =
            if (cursorCreatedAt != null && cursorPostId != null) {
                "AND (p.created_at, p.id) < (?, ?)"
            } else {
                ""
            }
        val sql =
            """
            SELECT p.id,
                   p.author_id,
                   p.author_username,
                   p.author_display_name,
                   p.content,
                   ST_Y(p.display_location::geometry) AS lat,
                   ST_X(p.display_location::geometry) AS lng,
                   p.city_name,
                   p.image_id,
                   p.created_at,
                   (pl.user_id IS NOT NULL) AS liked_by_viewer,
                   c.n AS reply_count
              FROM (
                  (
                      SELECT p.id, p.author_id, u.username AS author_username,
                             u.display_name AS author_display_name, p.content,
                             p.display_location, p.city_name, p.image_id, p.created_at
                        FROM visible_posts p
                        JOIN users u ON u.id = p.author_id
                       WHERE p.author_id <> ?
                         AND p.author_id NOT IN (SELECT blocked_id FROM user_blocks WHERE blocker_id = ?)
                         AND p.author_id NOT IN (SELECT blocker_id FROM user_blocks WHERE blocked_id = ?)
                         $cursorPredicate
                       ORDER BY p.created_at DESC, p.id DESC
                       LIMIT ?
                  )
                  UNION ALL
                  (
                      SELECT p.id, p.author_id, u.username AS author_username,
                             u.display_name AS author_display_name, p.content,
                             p.display_location, p.city_name, p.image_id, p.created_at
                        FROM posts p
                        JOIN users u ON u.id = p.author_id
                       WHERE p.author_id = ?
                         AND p.deleted_at IS NULL
                         $cursorPredicate
                       ORDER BY p.created_at DESC, p.id DESC
                       LIMIT ?
                  )
              ) p
              LEFT JOIN post_likes pl ON pl.post_id = p.id AND pl.user_id = ?
              LEFT JOIN LATERAL (
                  SELECT COUNT(*) AS n
                    FROM post_replies pr
                    JOIN visible_users vu ON vu.id = pr.author_id
                   WHERE pr.post_id = p.id
                     AND pr.deleted_at IS NULL
              ) c ON TRUE
             ORDER BY p.created_at DESC, p.id DESC
             LIMIT ?
            """.trimIndent()
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                var i = 1
                // Visible arm
                ps.setObject(i++, viewerId) // p.author_id <> ?
                ps.setObject(i++, viewerId) // blocker_id = ?
                ps.setObject(i++, viewerId) // blocked_id = ?
                if (cursorCreatedAt != null && cursorPostId != null) {
                    ps.setTimestamp(i++, Timestamp.from(cursorCreatedAt))
                    ps.setObject(i++, cursorPostId)
                }
                ps.setInt(i++, limit)
                // Self arm
                ps.setObject(i++, viewerId) // p.author_id = ?
                if (cursorCreatedAt != null && cursorPostId != null) {
                    ps.setTimestamp(i++, Timestamp.from(cursorCreatedAt))
                    ps.setObject(i++, cursorPostId)
                }
                ps.setInt(i++, limit)
                // Outer joins + final LIMIT
                ps.setObject(i++, viewerId) // pl.user_id = ?
                ps.setInt(i, limit)
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<TimelineRow>()
                    while (rs.next()) {
                        out +=
                            TimelineRow(
                                id = rs.getObject("id", UUID::class.java),
                                authorId = rs.getObject("author_id", UUID::class.java),
                                authorUsername = rs.getString("author_username"),
                                authorDisplayName = rs.getString("author_display_name"),
                                content = rs.getString("content"),
                                latitude = rs.getDouble("lat"),
                                longitude = rs.getDouble("lng"),
                                distanceMeters = 0.0,
                                createdAt = rs.getTimestamp("created_at").toInstant(),
                                likedByViewer = rs.getBoolean("liked_by_viewer"),
                                replyCount = rs.getInt("reply_count"),
                                cityName = rs.getString("city_name"),
                                imageId = rs.getString("image_id"),
                            )
                    }
                    return out
                }
            }
        }
    }
}
