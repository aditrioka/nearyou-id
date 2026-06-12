package id.nearyou.app.infra.repo

import id.nearyou.app.core.domain.lint.AllowMissingBlockJoin
import id.nearyou.app.core.domain.lint.AllowRawPostsRead
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

    @AllowRawPostsRead(
        "like-notification emit input (author_id + excerpt for body_data) — visibility " +
            "already resolved by resolveVisiblePost earlier in the same service flow",
    )
    @AllowMissingBlockJoin("notification emitter suppresses blocked/self recipients downstream; excerpt is the post author's own content")
    override fun loadPostAuthorAndExcerpt(
        conn: Connection,
        postId: UUID,
    ): PostAuthorExcerpt? {
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

    @AllowRawPostsRead(
        "own-content self-arm (shadow-ban-feed-self-visibility): the raw posts arm is scoped " +
            "to id = :post_id AND author_id = :viewer so a shadow-banned author can still " +
            "like their own post / read its like count; other viewers resolve via the " +
            "visible_posts arm and keep the constant opaque 404",
    )
    override fun resolveVisiblePost(
        postId: UUID,
        viewerId: UUID,
    ): UUID? {
        // Visibility gate shared by POST /like and GET /likes/count. Two arms:
        //  - visible_posts + bidirectional user_blocks (the literal carries `visible_posts`
        //    + `user_blocks` + both `blocker_id =` / `blocked_id =` tokens for
        //    BlockExclusionJoinRule compliance) — everyone else's view of the post;
        //  - own-content self arm (raw posts, author_id = :viewer, deleted_at IS NULL) —
        //    the author keeps resolving their own post while shadow-banned or auto-hidden;
        //    their own soft-deleted posts still 404. Arms may overlap for a normal author's
        //    own visible post — LIMIT 1 over identical ids makes that harmless.
        dataSource.connection.use { conn ->
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
                ps.executeQuery().use { rs ->
                    return if (rs.next()) rs.getObject("id", UUID::class.java) else null
                }
            }
        }
    }
}
