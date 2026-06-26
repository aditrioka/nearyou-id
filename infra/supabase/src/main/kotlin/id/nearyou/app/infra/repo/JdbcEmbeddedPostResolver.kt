package id.nearyou.app.infra.repo

import id.nearyou.app.core.domain.lint.AllowRawPostsRead
import java.util.UUID
import javax.sql.DataSource

/**
 * JDBC [EmbeddedPostResolver]. Shape-identical visibility resolution to
 * [JdbcSinglePostRepository.findById] / [PostEditHistoryQuery.history]: a two-arm UNION over
 * `visible_posts` (with the real bidirectional `user_blocks` exclusion) and an own-content self
 * arm on raw `posts` scoped to `author_id = :sender AND deleted_at IS NULL`, so a shadow-banned
 * author can still share their OWN post while every other viewer keeps the constant opaque
 * `null` (→ 404). The visible arm joins raw `users` for author identity (a tombstoned author
 * renders anonymized via the V24 tombstone surfacing — shadow-ban-safe because `visible_posts`
 * already excludes shadow-banned/soft-deleted-post rows).
 *
 * NO `display_location` / `latitude` / `longitude` / author UUID is projected — the snapshot is a
 * new serialization path that must preserve `display_location`-only (spatial-fuzzing critical
 * invariant). The latest-edit anchor + its timestamp come from a `post_edits` LATERAL keyed on the
 * RESOLVED `p.id`, so an invisible/blocked post never yields an edit-existence signal; `post_edits`
 * is in neither lint rule's pattern and lives in THIS same literal so the four
 * `BlockExclusionJoinRule` tokens stay co-located.
 */
class JdbcEmbeddedPostResolver(
    private val dataSource: DataSource,
) : EmbeddedPostResolver {
    @AllowRawPostsRead(
        "embedded-post share own-content self-arm (shadow-ban-feed-self-visibility): the raw " +
            "posts arm is scoped to id = :post_id AND author_id = :sender so a shadow-banned " +
            "author can still share their own post; other viewers resolve via the visible_posts " +
            "arm (which carries the real bidirectional block predicate) and keep the constant " +
            "opaque 404. The visible arm's author-identity JOIN is raw users (not visible_users) " +
            "so a tombstoned author surfaces anonymized — shadow-ban-safe since visible_posts " +
            "already excludes shadow-banned/soft-deleted-post rows; block exclusion stays via the NOT-IN",
    )
    override fun resolveForSender(
        senderId: UUID,
        postId: UUID,
    ): ResolvedEmbeddedPost? {
        val sql =
            """
            SELECT p.id,
                   p.author_username,
                   p.author_display_name,
                   p.content,
                   p.city_name,
                   p.created_at,
                   e.id AS latest_edit_id,
                   e.edited_at AS edited_at
              FROM (
                  (
                      SELECT p.id, u.username AS author_username,
                             u.display_name AS author_display_name, p.content,
                             p.city_name, p.created_at
                        FROM visible_posts p
                        JOIN users u ON u.id = p.author_id
                       WHERE p.id = ?
                         AND p.author_id NOT IN (SELECT blocked_id FROM user_blocks WHERE blocker_id = ?)
                         AND p.author_id NOT IN (SELECT blocker_id FROM user_blocks WHERE blocked_id = ?)
                  )
                  UNION ALL
                  (
                      SELECT p.id, u.username AS author_username,
                             u.display_name AS author_display_name, p.content,
                             p.city_name, p.created_at
                        FROM posts p
                        JOIN users u ON u.id = p.author_id
                       WHERE p.id = ?
                         AND p.author_id = ?
                         AND p.deleted_at IS NULL
                  )
                  LIMIT 1
              ) p
              LEFT JOIN LATERAL (
                  SELECT pe.id, pe.edited_at
                    FROM post_edits pe
                   WHERE pe.post_id = p.id
                   ORDER BY pe.edited_at DESC
                   LIMIT 1
              ) e ON TRUE
            """.trimIndent()
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                var i = 1
                ps.setObject(i++, postId) // visible arm: p.id = ?
                ps.setObject(i++, senderId) // blocker_id = ?
                ps.setObject(i++, senderId) // blocked_id = ?
                ps.setObject(i++, postId) // self arm: p.id = ?
                ps.setObject(i, senderId) // self arm: p.author_id = ?
                ps.executeQuery().use { rs ->
                    return if (rs.next()) {
                        ResolvedEmbeddedPost(
                            postId = rs.getObject("id", UUID::class.java),
                            authorUsername = rs.getString("author_username"),
                            authorDisplayName = rs.getString("author_display_name"),
                            content = rs.getString("content"),
                            cityName = rs.getString("city_name"),
                            createdAt = rs.getTimestamp("created_at").toInstant(),
                            editedAt = rs.getTimestamp("edited_at")?.toInstant(),
                            latestEditId = rs.getObject("latest_edit_id", UUID::class.java),
                        )
                    } else {
                        null
                    }
                }
            }
        }
    }
}
