package id.nearyou.app.infra.repo

import id.nearyou.app.core.domain.lint.AllowRawPostsRead
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * One entry in a post's edit history. Content-only by design (spatial-fuzzing
 * invariant #3 / design D10): the projection carries the content snapshot, its
 * 1-based version index (rendered "Versi ke-N" at the route), and the edit
 * timestamp — and NEVER `location_snapshot` / `actual_location`.
 */
data class PostEditHistoryEntry(
    val versionIndex: Int,
    val contentSnapshot: String,
    val editedAt: Instant,
)

/**
 * Read-path component for `GET /api/v1/posts/{post_id}/edits` (design D5 + D10).
 *
 * Resolves the target post through the shadow-ban-safe `visible_posts` view AND a
 * **real** bidirectional `user_blocks` exclusion predicate (the four tokens
 * `BlockExclusionJoinRule` recognizes — `visible_posts`, `user_blocks`,
 * `blocker_id =`, `blocked_id =`), with an own-content self-arm so a shadow-banned
 * author can still read their own post's history — shape-identical to
 * [JdbcPostReplyRepository.resolveVisiblePost]. A viewer who cannot see the post
 * gets a `null` resolution (→ 404 at the route, no existence reveal).
 *
 * The version index is `ROW_NUMBER() OVER (PARTITION BY post_id ORDER BY edited_at)`
 * — chronological, 1-based. A never-edited (but visible) post resolves but has no
 * `post_edits` rows → empty list.
 *
 * `post_edits` is NOT a protected table (no per-viewer content surface beyond the
 * snapshot, which is gated by the post's visibility resolved above); only the
 * `posts` own-content self-arm needs `@AllowRawPostsRead`. The `visible_posts` arm
 * carries no `@AllowRawPostsRead` (it is the view, not raw `posts`) and is NOT
 * annotated with `@AllowMissingBlockJoin` (D10 mandates the real predicate).
 */
class PostEditHistoryQuery(
    private val dataSource: DataSource,
) {
    @AllowRawPostsRead(
        "edit-history own-content self-arm (shadow-ban-feed-self-visibility): the raw posts " +
            "arm is scoped to id = :post_id AND author_id = :viewer so a shadow-banned author " +
            "can still read their own post's edit history; other viewers resolve via the " +
            "visible_posts arm (which carries the real bidirectional block predicate) and keep " +
            "the constant opaque 404",
    )
    fun history(
        postId: UUID,
        viewerId: UUID,
    ): List<PostEditHistoryEntry>? {
        // Two-phase: (1) resolve the post id through visible_posts + the bidirectional
        // user_blocks exclusion (with the own-content self-arm), exactly like
        // resolveVisiblePost; a non-resolving post → null (404, no existence reveal).
        // (2) read its edit history. Done as a single statement: the visibility CTE
        // gates the post_edits join, so an invisible post yields zero rows AND we must
        // still distinguish "visible but unedited" (empty list) from "invisible" (null)
        // — handled by the separate resolvable check below.
        dataSource.connection.use { conn ->
            // Phase 1 — visibility resolution. Shape-identical four-token block predicate
            // + own-content self-arm as JdbcPostReplyRepository.resolveVisiblePost.
            val resolvable =
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
                    ps.executeQuery().use { rs -> rs.next() }
                }
            if (!resolvable) return null

            // Phase 2 — chronological edit history with the 1-based version index.
            // No location column is projected (design D10).
            return conn.prepareStatement(
                """
                SELECT ROW_NUMBER() OVER (PARTITION BY post_id ORDER BY edited_at) AS version_index,
                       content_snapshot,
                       edited_at
                  FROM post_edits
                 WHERE post_id = ?
                 ORDER BY edited_at
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, postId)
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<PostEditHistoryEntry>()
                    while (rs.next()) {
                        out +=
                            PostEditHistoryEntry(
                                versionIndex = rs.getInt("version_index"),
                                contentSnapshot = rs.getString("content_snapshot"),
                                editedAt = rs.getTimestamp("edited_at").toInstant(),
                            )
                    }
                    out
                }
            }
        }
    }
}
