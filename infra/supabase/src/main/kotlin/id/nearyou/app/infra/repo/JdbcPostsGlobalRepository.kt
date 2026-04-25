package id.nearyou.app.infra.repo

import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

interface PostsGlobalRepository {
    /**
     * Canonical Global query — returns up to [limit] rows from `visible_posts` with
     * bidirectional `user_blocks` exclusion baked in. No follows filter, no spatial filter,
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
    override fun global(
        viewerId: UUID,
        cursorCreatedAt: Instant?,
        cursorPostId: UUID?,
        limit: Int,
    ): List<TimelineRow> {
        // Canonical Global query per design Decision 5 + global-timeline spec.
        //  - FROM visible_posts (auto-hidden filter applied by the view).
        //  - No follows filter (Global surfaces every visible author).
        //  - Bidirectional user_blocks NOT-IN (BlockExclusionJoinRule: blocker_id = ? AND
        //    blocked_id = ? both present).
        //  - LEFT JOIN post_likes for `liked_by_viewer` (PK-scoped; cardinality invariant).
        //  - LEFT JOIN LATERAL reply counter; shadow-ban exclusion on the counter; viewer-
        //    block exclusion DELIBERATELY NOT applied (privacy tradeoff, same as Nearby /
        //    Following per post-replies-v8 Decision 5).
        //  - p.city_name projected directly from visible_posts (populated by V11 trigger).
        //    NULL for legacy rows + polygon-coverage gaps; HTTP layer maps to "".
        //  - No ST_Contains / no admin_regions JOIN / no ST_DWithin at read time — the
        //    denormalized column is the hot-path contract (region-polygons capability:
        //    "No read-time reverse-geocoding").
        //  - Keyset on (created_at DESC, id DESC) via posts_timeline_cursor_idx (V4).
        val sql =
            buildString {
                append(
                    """
                    SELECT p.id,
                           p.author_id,
                           p.content,
                           ST_Y(p.display_location::geometry) AS lat,
                           ST_X(p.display_location::geometry) AS lng,
                           p.city_name,
                           p.created_at,
                           (pl.user_id IS NOT NULL) AS liked_by_viewer,
                           c.n AS reply_count
                      FROM visible_posts p
                      LEFT JOIN post_likes pl ON pl.post_id = p.id AND pl.user_id = ?
                      LEFT JOIN LATERAL (
                          SELECT COUNT(*) AS n
                            FROM post_replies pr
                            JOIN visible_users vu ON vu.id = pr.author_id
                           WHERE pr.post_id = p.id
                             AND pr.deleted_at IS NULL
                      ) c ON TRUE
                     WHERE p.author_id NOT IN (SELECT blocked_id FROM user_blocks WHERE blocker_id = ?)
                       AND p.author_id NOT IN (SELECT blocker_id FROM user_blocks WHERE blocked_id = ?)
                    """.trimIndent(),
                )
                if (cursorCreatedAt != null && cursorPostId != null) {
                    append("\n   AND (p.created_at, p.id) < (?, ?)")
                }
                append("\n ORDER BY p.created_at DESC, p.id DESC")
                append("\n LIMIT ?")
            }
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                var i = 1
                ps.setObject(i++, viewerId) // pl.user_id = ?
                ps.setObject(i++, viewerId) // blocker_id = ?
                ps.setObject(i++, viewerId) // blocked_id = ?
                if (cursorCreatedAt != null && cursorPostId != null) {
                    ps.setTimestamp(i++, Timestamp.from(cursorCreatedAt))
                    ps.setObject(i++, cursorPostId)
                }
                ps.setInt(i, limit)
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<TimelineRow>()
                    while (rs.next()) {
                        out +=
                            TimelineRow(
                                id = rs.getObject("id", UUID::class.java),
                                authorId = rs.getObject("author_id", UUID::class.java),
                                content = rs.getString("content"),
                                latitude = rs.getDouble("lat"),
                                longitude = rs.getDouble("lng"),
                                distanceMeters = 0.0,
                                createdAt = rs.getTimestamp("created_at").toInstant(),
                                likedByViewer = rs.getBoolean("liked_by_viewer"),
                                replyCount = rs.getInt("reply_count"),
                                cityName = rs.getString("city_name"),
                            )
                    }
                    return out
                }
            }
        }
    }
}
