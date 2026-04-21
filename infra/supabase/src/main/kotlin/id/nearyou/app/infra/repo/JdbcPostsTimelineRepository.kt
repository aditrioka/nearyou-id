package id.nearyou.app.infra.repo

import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class JdbcPostsTimelineRepository(
    private val dataSource: DataSource,
) : PostsTimelineRepository {
    override fun nearby(
        viewerId: UUID,
        viewerLat: Double,
        viewerLng: Double,
        radiusMeters: Int,
        cursorCreatedAt: Instant?,
        cursorPostId: UUID?,
        limit: Int,
    ): List<TimelineRow> {
        // Canonical Nearby query per docs/05-Implementation.md § Timeline Implementation.
        // - FROM visible_posts (NOT raw posts) so the auto-hidden filter applies.
        // - Bidirectional user_blocks exclusion: viewer-blocked authors AND
        //   authors-who-blocked-viewer are both hidden.
        // - Keyset on (created_at, id) using the V4 partial index posts_timeline_cursor_idx.
        // - ST_Distance on geography returns meters; we use that value directly in the
        //   response (Decision 3 in design.md).
        val sql =
            buildString {
                append(
                    """
                    SELECT id,
                           author_id,
                           content,
                           ST_Y(display_location::geometry) AS lat,
                           ST_X(display_location::geometry) AS lng,
                           ST_Distance(display_location, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography) AS distance_m,
                           created_at
                      FROM visible_posts
                     WHERE ST_DWithin(display_location, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?)
                       AND author_id NOT IN (SELECT blocked_id FROM user_blocks WHERE blocker_id = ?)
                       AND author_id NOT IN (SELECT blocker_id FROM user_blocks WHERE blocked_id = ?)
                    """.trimIndent(),
                )
                if (cursorCreatedAt != null && cursorPostId != null) {
                    append("\n   AND (created_at, id) < (?, ?)")
                }
                append("\n ORDER BY created_at DESC, id DESC")
                append("\n LIMIT ?")
            }
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                var i = 1
                // ST_MakePoint(lng, lat) — order matters
                ps.setDouble(i++, viewerLng)
                ps.setDouble(i++, viewerLat)
                ps.setDouble(i++, viewerLng)
                ps.setDouble(i++, viewerLat)
                ps.setInt(i++, radiusMeters)
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
                                distanceMeters = rs.getDouble("distance_m"),
                                createdAt = rs.getTimestamp("created_at").toInstant(),
                            )
                    }
                    return out
                }
            }
        }
    }
}
