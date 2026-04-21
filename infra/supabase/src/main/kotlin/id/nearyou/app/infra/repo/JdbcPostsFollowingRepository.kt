package id.nearyou.app.infra.repo

import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

interface PostsFollowingRepository {
    /**
     * Canonical Following query — returns up to [limit] rows from `visible_posts` where
     * the author is in the viewer's outbound follows set, with bidirectional `user_blocks`
     * exclusion baked in. Keyset on `(created_at DESC, id DESC)`.
     *
     * Response rows reuse [TimelineRow] but `distanceMeters` is always `0.0` and MUST NOT
     * be surfaced in the HTTP response — Following is chronological-over-follows, not
     * geographic.
     */
    fun following(
        viewerId: UUID,
        cursorCreatedAt: Instant?,
        cursorPostId: UUID?,
        limit: Int,
    ): List<TimelineRow>
}

class JdbcPostsFollowingRepository(
    private val dataSource: DataSource,
) : PostsFollowingRepository {
    override fun following(
        viewerId: UUID,
        cursorCreatedAt: Instant?,
        cursorPostId: UUID?,
        limit: Int,
    ): List<TimelineRow> {
        // Canonical Following query per docs/05-Implementation.md §1057–1067 and design
        // Decision 3. Shape differences from Nearby:
        //  - FROM visible_posts joined against the viewer's `follows` set via IN.
        //  - No ST_DWithin / ST_Distance — no radius, no distance projection.
        //  - lat/lng still come from display_location (jitter invariant preserved).
        //  - LEFT JOIN post_likes for `liked_by_viewer`, PK-scoped to viewer so the
        //    JOIN adds at most one row per page row (keyset / ORDER BY / LIMIT intact).
        //
        // Both user_blocks NOT-IN subqueries are required for BlockExclusionJoinRule.
        val sql =
            buildString {
                append(
                    """
                    SELECT p.id,
                           p.author_id,
                           p.content,
                           ST_Y(p.display_location::geometry) AS lat,
                           ST_X(p.display_location::geometry) AS lng,
                           p.created_at,
                           (pl.user_id IS NOT NULL) AS liked_by_viewer
                      FROM visible_posts p
                      LEFT JOIN post_likes pl ON pl.post_id = p.id AND pl.user_id = ?
                     WHERE p.author_id IN (SELECT followee_id FROM follows WHERE follower_id = ?)
                       AND p.author_id NOT IN (SELECT blocked_id FROM user_blocks WHERE blocker_id = ?)
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
                ps.setObject(i++, viewerId)
                ps.setObject(i++, viewerId)
                ps.setObject(i++, viewerId)
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
                            )
                    }
                    return out
                }
            }
        }
    }
}
