package id.nearyou.app.infra.repo

import id.nearyou.data.repository.PostAuthorExcerpt
import id.nearyou.data.repository.PostLikeRepository
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

/**
 * JDBC implementation of [PostLikeRepository]. All SQL lives here so the interface in
 * `:core:data` stays DB-agnostic.
 *
 * Query literals carry the four tokens `visible_posts`, `user_blocks`, `blocker_id =`,
 * `blocked_id =` on the visibility check so `BlockExclusionJoinRule` passes. The
 * `post_likes`-only queries (like/unlike/count) do NOT carry those tokens because
 * `post_likes` is deliberately NOT a protected table (see BlockExclusionJoinRule KDoc
 * + block-exclusion-lint spec).
 */
class JdbcPostLikeRepository(
    private val dataSource: DataSource,
) : PostLikeRepository {
    override fun like(
        postId: UUID,
        userId: UUID,
    ) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "INSERT INTO post_likes (post_id, user_id) VALUES (?, ?) ON CONFLICT (post_id, user_id) DO NOTHING",
            ).use { ps ->
                ps.setObject(1, postId)
                ps.setObject(2, userId)
                ps.executeUpdate()
            }
        }
    }

    override fun unlike(
        postId: UUID,
        userId: UUID,
    ) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "DELETE FROM post_likes WHERE post_id = ? AND user_id = ?",
            ).use { ps ->
                ps.setObject(1, postId)
                ps.setObject(2, userId)
                ps.executeUpdate()
            }
        }
    }

    override fun countVisibleLikes(postId: UUID): Long {
        // Viewer-block exclusion is deliberately NOT applied here — the count must be
        // a function of (post_id, shadow-ban state) only. Per-viewer variance would
        // leak the caller's private `user_blocks` set (post-likes spec requirement
        // "Count endpoint does NOT apply viewer-block exclusion").
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM post_likes pl JOIN visible_users vu ON vu.id = pl.user_id WHERE pl.post_id = ?",
            ).use { ps ->
                ps.setObject(1, postId)
                ps.executeQuery().use { rs ->
                    rs.next()
                    return rs.getLong(1)
                }
            }
        }
    }

    override fun likeInTx(
        conn: Connection,
        postId: UUID,
        userId: UUID,
    ): Boolean {
        conn.prepareStatement(
            "INSERT INTO post_likes (post_id, user_id) VALUES (?, ?) ON CONFLICT (post_id, user_id) DO NOTHING",
        ).use { ps ->
            ps.setObject(1, postId)
            ps.setObject(2, userId)
            return ps.executeUpdate() == 1
        }
    }

    override fun loadPostAuthorAndExcerpt(
        conn: Connection,
        postId: UUID,
    ): PostAuthorExcerpt? {
        // Raw `posts` read is scoped to infra/supabase (outside the :backend:ktor
        // RawFromPostsRule scan). The caller (LikeService) already gated visibility
        // via `resolveVisiblePost`; this is a tight author-id + excerpt fetch for
        // the notification body_data.
        conn.prepareStatement(
            "SELECT author_id, content FROM posts WHERE id = ? AND deleted_at IS NULL",
        ).use { ps ->
            ps.setObject(1, postId)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                val author = rs.getObject("author_id", UUID::class.java)
                val content = rs.getString("content") ?: ""
                return PostAuthorExcerpt(
                    authorId = author,
                    excerpt = content.firstCodePoints(80),
                )
            }
        }
    }

    override fun resolveVisiblePost(
        postId: UUID,
        viewerId: UUID,
    ): UUID? {
        // Visibility gate shared by POST /like and GET /likes/count. Literal carries
        // `visible_posts` + `user_blocks` + both `blocker_id =` / `blocked_id =`
        // tokens for BlockExclusionJoinRule compliance.
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT p.id
                  FROM visible_posts p
                 WHERE p.id = ?
                   AND p.author_id NOT IN (SELECT blocked_id FROM user_blocks WHERE blocker_id = ?)
                   AND p.author_id NOT IN (SELECT blocker_id FROM user_blocks WHERE blocked_id = ?)
                 LIMIT 1
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, postId)
                ps.setObject(2, viewerId)
                ps.setObject(3, viewerId)
                ps.executeQuery().use { rs ->
                    return if (rs.next()) rs.getObject("id", UUID::class.java) else null
                }
            }
        }
    }
}
