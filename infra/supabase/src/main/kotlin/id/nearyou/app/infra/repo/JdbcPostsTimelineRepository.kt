package id.nearyou.app.infra.repo

import id.nearyou.app.core.domain.lint.AllowRawPostsRead
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class JdbcPostsTimelineRepository(
    private val dataSource: DataSource,
) : PostsTimelineRepository {
    @AllowRawPostsRead(
        "own-content self-arm (shadow-ban-feed-self-visibility): the raw posts/users reads are " +
            "scoped to author_id = :viewer so a shadow-banned author keeps seeing their own posts " +
            "in Nearby; every other viewer's rows come from the visible_posts arm",
    )
    override fun nearby(
        viewerId: UUID,
        viewerLat: Double,
        viewerLng: Double,
        radiusMeters: Int,
        cursorCreatedAt: Instant?,
        cursorPostId: UUID?,
        limit: Int,
    ): List<TimelineRow> {
        // Canonical Nearby query per docs/05-Implementation.md § Timeline Implementation —
        // viewer-aware two-arm UNION ALL since shadow-ban-feed-self-visibility:
        // - Visible arm: FROM visible_posts (auto-hide + post-soft-delete + author shadow-ban
        //   via the V24 view; V24 surfaces tombstoned authors) restricted to author_id <> :viewer,
        //   with bidirectional user_blocks exclusion (viewer-blocked authors AND
        //   authors-who-blocked-viewer both hidden). Author identity via raw `users` so a
        //   tombstoned author renders anonymized ('Akun Dihapus') — shadow-ban-safe since
        //   visible_posts already excluded shadow-banned authors (account-deletion-tombstone).
        // - Self arm: the docs/05 own-content exception at the feed layer — raw posts scoped to
        //   author_id = :viewer AND deleted_at IS NULL (no is_auto_hidden filter: auto-hide is
        //   transparent to the author, reply-list parity; no shadow-ban filter: self-visibility
        //   is the point; soft-deleted stays hidden). No user_blocks subqueries: the arm only
        //   returns the viewer's own rows and self-blocks are CHECK-impossible. Identity via raw
        //   users (a shadow-banned author has no visible_users row).
        // - Arms are disjoint (author_id <> :viewer on the visible arm), each parenthesized with
        //   its own keyset predicate + ORDER BY + LIMIT so each stays independently
        //   index-serviceable (V4 partial indexes / posts_author_idx) with top-N early exit; the
        //   outer query merges <= 2 * limit rows. Keyset on (created_at, id).
        // - ST_Distance/ST_Y/ST_X computed in the outer SELECT from the display_location both
        //   arms project (spatial fuzzing invariant: display_location only, never
        //   actual_location). Distance returns meters, used directly (Decision 3 in design.md).
        // - LEFT JOIN post_likes on (post_id, :viewer) projects `liked_by_viewer`. PK on
        //   post_likes keeps at most one row per page row — cardinality unchanged.
        // - LEFT JOIN LATERAL reply-counter projects `reply_count`. One row per outer row.
        //   JOIN visible_users for shadow-ban exclusion on the reply author — INCLUDING on the
        //   viewer's own self-arm rows (counts are public and viewer-independent). Viewer-block
        //   exclusion DELIBERATELY NOT applied (per-viewer count would leak block state;
        //   post-replies-v8 design Decision 5 + spec privacy tradeoff).
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
                   ST_Distance(p.display_location, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography) AS distance_m,
                   p.city_name,
                   p.author_hides_distance,
                   p.created_at,
                   (pl.user_id IS NOT NULL) AS liked_by_viewer,
                   c.n AS reply_count
              FROM (
                  (
                      SELECT p.id, p.author_id, u.username AS author_username,
                             u.display_name AS author_display_name, p.content,
                             p.display_location, p.city_name, p.created_at,
                             (u.hide_distance_opt_in AND u.subscription_status IN ('premium_active', 'premium_billing_retry')) AS author_hides_distance
                        FROM visible_posts p
                        JOIN users u ON u.id = p.author_id
                       WHERE p.author_id <> ?
                         AND ST_DWithin(p.display_location, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?)
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
                             p.display_location, p.city_name, p.created_at,
                             (u.hide_distance_opt_in AND u.subscription_status IN ('premium_active', 'premium_billing_retry')) AS author_hides_distance
                        FROM posts p
                        JOIN users u ON u.id = p.author_id
                       WHERE p.author_id = ?
                         AND p.deleted_at IS NULL
                         AND ST_DWithin(p.display_location, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?)
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
                // Outer ST_Distance — ST_MakePoint(lng, lat): order matters
                ps.setDouble(i++, viewerLng)
                ps.setDouble(i++, viewerLat)
                // Visible arm
                ps.setObject(i++, viewerId) // p.author_id <> ?
                ps.setDouble(i++, viewerLng)
                ps.setDouble(i++, viewerLat)
                ps.setInt(i++, radiusMeters)
                ps.setObject(i++, viewerId) // blocker_id = ?
                ps.setObject(i++, viewerId) // blocked_id = ?
                if (cursorCreatedAt != null && cursorPostId != null) {
                    ps.setTimestamp(i++, Timestamp.from(cursorCreatedAt))
                    ps.setObject(i++, cursorPostId)
                }
                ps.setInt(i++, limit)
                // Self arm
                ps.setObject(i++, viewerId) // p.author_id = ?
                ps.setDouble(i++, viewerLng)
                ps.setDouble(i++, viewerLat)
                ps.setInt(i++, radiusMeters)
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
                                distanceMeters = rs.getDouble("distance_m"),
                                createdAt = rs.getTimestamp("created_at").toInstant(),
                                likedByViewer = rs.getBoolean("liked_by_viewer"),
                                replyCount = rs.getInt("reply_count"),
                                cityName = rs.getString("city_name"),
                                authorHidesDistance = rs.getBoolean("author_hides_distance"),
                            )
                    }
                    return out
                }
            }
        }
    }
}
