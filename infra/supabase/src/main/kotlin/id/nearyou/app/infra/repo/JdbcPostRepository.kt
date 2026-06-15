package id.nearyou.app.infra.repo

import id.nearyou.app.core.domain.lint.AllowContentWriteWithoutModeration
import id.nearyou.app.core.domain.lint.AllowMissingBlockJoin
import id.nearyou.app.core.domain.lint.AllowRawPostsRead
import java.sql.Connection
import java.util.UUID

class JdbcPostRepository : PostRepository {
    // CreatePostService runs TextModerator.moderate() before invoking this sink
    // (PostCreationModerationIntegrationTest pins the call order).
    @AllowContentWriteWithoutModeration("service_layer_moderated")
    override fun create(
        conn: Connection,
        row: NewPostRow,
    ): UUID {
        // Single-statement INSERT writes both geographies — the author's-own-content
        // INSERT path; no `FROM posts` here so the raw-posts lint rule does not fire.
        conn.prepareStatement(
            """
            INSERT INTO posts (
                id, author_id, content,
                display_location,
                actual_location
            ) VALUES (
                ?, ?, ?,
                ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography
            )
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, row.id)
            ps.setObject(2, row.authorId)
            ps.setString(3, row.content)
            // ST_MakePoint takes (x = longitude, y = latitude)
            ps.setDouble(4, row.displayLng)
            ps.setDouble(5, row.displayLat)
            ps.setDouble(6, row.actualLng)
            ps.setDouble(7, row.actualLat)
            ps.executeUpdate()
        }
        return row.id
    }

    // The edit `FOR UPDATE` read is scoped to the caller's OWN post
    // (author_id = :uid) — a self-content read. RawFromPostsRule: the author editing
    // their own post legitimately reads raw `posts` (shadow-ban filtering would hide
    // an author's own row, and the locked row feeds the snapshot of `actual_location`,
    // which `visible_posts` does not expose). BlockExclusionJoinRule: no block/shadow-
    // ban filtering is meaningful — a user cannot block themselves; the row is theirs.
    @AllowRawPostsRead(
        "edit select-for-edit: scoped to author_id = :uid (the caller's own post); raw " +
            "posts is required because actual_location (not exposed by visible_posts) is " +
            "snapshotted, and an author must be able to edit their own post even if shadow-banned",
    )
    @AllowMissingBlockJoin(
        "self-content edit: the row is scoped to author_id = :uid (the caller's own post); " +
            "block exclusion is meaningless on one's own content (a user cannot block themselves)",
    )
    override fun selectForEdit(
        conn: Connection,
        postId: UUID,
        authorId: UUID,
    ): LockedPostForEdit? {
        // docs/05 § Transactional Atomicity (verbatim): lock the author's own,
        // in-window, non-deleted post. A 0-row result is disambiguated by
        // eligibilityForEdit (design D7).
        conn.prepareStatement(
            """
            SELECT id, content, actual_location, author_id
              FROM posts
             WHERE id = ?
               AND author_id = ?
               AND created_at > NOW() - INTERVAL '30 minutes'
               AND deleted_at IS NULL
             FOR UPDATE
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, postId)
            ps.setObject(2, authorId)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                return LockedPostForEdit(
                    id = rs.getObject("id", UUID::class.java),
                    content = rs.getString("content"),
                    authorId = rs.getObject("author_id", UUID::class.java),
                )
            }
        }
    }

    // Author-scoped disambiguation read (design D7) — scoped to author_id = :uid (the
    // caller's own post), so it can never reveal another user's post. Returns ONLY
    // created_at + deleted_at (no content / no location surface). Same self-content
    // rationale as selectForEdit for both lint rules.
    @AllowRawPostsRead(
        "edit disambiguation: scoped to author_id = :uid (the caller's own post); reads only " +
            "created_at + deleted_at (no content surface) to tell window-expired (409) from a " +
            "uniform 404 without leaking another user's post existence (design D7)",
    )
    @AllowMissingBlockJoin(
        "self-content edit disambiguation: scoped to author_id = :uid; block exclusion is " +
            "meaningless on one's own post and this read returns no content surface",
    )
    override fun eligibilityForEdit(
        conn: Connection,
        postId: UUID,
        authorId: UUID,
    ): PostEditEligibility? {
        conn.prepareStatement(
            "SELECT created_at, deleted_at FROM posts WHERE id = ? AND author_id = ?",
        ).use { ps ->
            ps.setObject(1, postId)
            ps.setObject(2, authorId)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                return PostEditEligibility(
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                    softDeleted = rs.getTimestamp("deleted_at") != null,
                )
            }
        }
    }

    // INSERT ... SELECT copies the post's pre-edit content + actual_location + author
    // into post_edits (docs/05 § Transactional Atomicity, verbatim). The SELECT arm is
    // scoped to id = :post_id — the row is already locked FOR UPDATE by selectForEdit
    // earlier in the same transaction (the caller's own post). RawFromPostsRule needs
    // the annotation because of the `FROM posts`; no block join (self-content, locked
    // row). No content moderation annotation: this writes into post_edits (a snapshot
    // of already-live, already-moderated content), NOT into posts.content, so
    // ContentWriteRequiresModerationRule does not apply.
    @AllowRawPostsRead(
        "edit snapshot INSERT...SELECT: copies the author's own (already locked FOR UPDATE) " +
            "post's content + actual_location into post_edits; scoped to id = :post_id, the " +
            "caller's own row",
    )
    @AllowMissingBlockJoin(
        "self-content edit snapshot: scoped to id = :post_id, the author's own row already " +
            "locked FOR UPDATE; block exclusion is meaningless on one's own content",
    )
    override fun insertEditSnapshot(
        conn: Connection,
        postId: UUID,
    ) {
        conn.prepareStatement(
            """
            INSERT INTO post_edits (post_id, content_snapshot, location_snapshot, edited_at, edited_by)
            SELECT id, content, actual_location, clock_timestamp(), author_id
              FROM posts
             WHERE id = ?
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, postId)
            ps.executeUpdate()
        }
    }

    // PostEditService runs TextModerator.moderate() before invoking this sink
    // (PostEditLayer3DispatchCallSiteTest pins the call order) — same service-layer
    // moderation contract as the create path.
    @AllowContentWriteWithoutModeration("service_layer_moderated")
    override fun updateContent(
        conn: Connection,
        postId: UUID,
        newContent: String,
    ) {
        // No `FROM posts` (UPDATE target table only) — the raw-posts lint rule does
        // not fire on this literal.
        conn.prepareStatement(
            "UPDATE posts SET content = ?, updated_at = NOW() WHERE id = ?",
        ).use { ps ->
            ps.setString(1, newContent)
            ps.setObject(2, postId)
            ps.executeUpdate()
        }
    }
}
