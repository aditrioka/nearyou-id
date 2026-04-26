package id.nearyou.app.infra.repo

import id.nearyou.data.repository.SearchHit
import id.nearyou.data.repository.SearchRepository
import java.util.UUID
import javax.sql.DataSource

/**
 * JDBC-backed [SearchRepository] running the canonical FTS query verbatim from
 * `docs/05-Implementation.md:1163-1181`.
 *
 * Per-query session steps (one open connection per call):
 *   1. `BEGIN` (autoCommit OFF) — `SET LOCAL` is a no-op outside a transaction.
 *   2. `SET LOCAL pg_trgm.similarity_threshold = 0.3` — pins the canonical
 *      `%` operator threshold so a future Postgres GUC change does not silently
 *      shift behaviour. The value matches the canonical default; the explicit
 *      pin is a silent-drift guard.
 *   3. The canonical FTS SELECT — see [CANONICAL_FTS_QUERY].
 *   4. `COMMIT`.
 *
 * **Lint compliance:**
 *  - Reads `FROM visible_posts JOIN visible_users` (passes `RawFromPostsRule` —
 *    the rule allows view reads).
 *  - Includes the bidirectional `user_blocks` NOT-IN clauses against the viewer
 *    (passes `BlockExclusionJoinRule`).
 *  - All bound parameters go through `PreparedStatement.setX` — no string
 *    interpolation into SQL.
 *
 * Returns at most [SearchRepository.PAGE_SIZE] rows; the caller stitches the
 * `next_offset` cursor based on `result.size == PAGE_SIZE`.
 */
class JdbcSearchRepository(
    private val dataSource: DataSource,
) : SearchRepository {
    override fun search(
        viewerId: UUID,
        query: String,
        offset: Int,
    ): List<SearchHit> {
        require(offset >= 0) { "offset must be non-negative" }
        require(query.isNotBlank()) { "query must be non-blank" }

        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                conn.createStatement().use { st ->
                    st.execute("SET LOCAL pg_trgm.similarity_threshold = 0.3")
                }
                val hits = mutableListOf<SearchHit>()
                conn.prepareStatement(CANONICAL_FTS_QUERY).use { ps ->
                    // The canonical query references :query 4×, :viewer_id 3×,
                    // :offset 1× — bind positionally in the order they appear.
                    ps.setString(1, query) // ts_rank in SELECT
                    ps.setString(2, query) // content_tsv @@ plainto_tsquery
                    ps.setString(3, query) // p.content % :query
                    ps.setString(4, query) // u.username % :query
                    ps.setObject(5, viewerId) // user_blocks.blocker_id
                    ps.setObject(6, viewerId) // user_blocks.blocked_id
                    ps.setObject(7, viewerId) // follows.follower_id
                    ps.setInt(8, offset)
                    ps.executeQuery().use { rs ->
                        while (rs.next()) {
                            hits +=
                                SearchHit(
                                    postId = rs.getObject("id", UUID::class.java),
                                    authorId = rs.getObject("author_id", UUID::class.java),
                                    authorUsername = rs.getString("username"),
                                    authorDisplayName = rs.getString("display_name"),
                                    content = rs.getString("content"),
                                    createdAt = rs.getTimestamp("created_at").toInstant(),
                                    rank = rs.getFloat("rank"),
                                )
                        }
                    }
                }
                conn.commit()
                return hits
            } catch (t: Throwable) {
                conn.rollback()
                throw t
            } finally {
                conn.autoCommit = true
            }
        }
    }

    companion object {
        // Verbatim from docs/05-Implementation.md:1163-1181 — DO NOT paraphrase.
        // Any change here MUST also amend the canonical doc and the spec scenario.
        // Hard-bound LIMIT 20 (PAGE_SIZE).
        internal val CANONICAL_FTS_QUERY: String =
            """
            SELECT p.id,
                   p.author_id,
                   u.username,
                   u.display_name,
                   p.content,
                   p.created_at,
                   ts_rank(p.content_tsv, plainto_tsquery('simple', ?)) AS rank
              FROM visible_posts p
              JOIN visible_users u ON p.author_id = u.id
             WHERE (
                       p.content_tsv @@ plainto_tsquery('simple', ?)
                    OR p.content % ?
                    OR u.username % ?
                   )
               AND p.is_auto_hidden = FALSE
               AND p.author_id NOT IN (
                       SELECT blocked_id FROM user_blocks WHERE blocker_id = ?
                   )
               AND p.author_id NOT IN (
                       SELECT blocker_id FROM user_blocks WHERE blocked_id = ?
                   )
               AND (
                       u.private_profile_opt_in = FALSE
                    OR u.subscription_status NOT IN ('premium_active', 'premium_billing_retry')
                    OR EXISTS (
                           SELECT 1 FROM follows f
                            WHERE f.follower_id = ? AND f.followee_id = u.id
                       )
                   )
             ORDER BY rank DESC, p.created_at DESC
             LIMIT 20 OFFSET ?
            """.trimIndent()
    }
}
