package id.nearyou.app.admin.postedits

import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.sql.DataSource

/**
 * Read-only repository for one post's edit history — backs the Post Edit History
 * viewer (`admin-post-edit-history` capability, admin mockup frame 8). Reads the
 * live `posts` row plus its `post_edits` snapshots; the FOURTH instance of the
 * read-only-admin-viewer pattern (clones the `admin-block-registry` read path:
 * keyset pagination, opaque cursor, parameterized-only SQL).
 *
 * Design references: `openspec/changes/admin-post-edit-history-viewer/design.md`
 * D1 (compose the live row as the newest version, snapshots below), D2 (keyset
 * pagination over `post_edits (post_id, edited_at DESC)`, served by the existing
 * `post_edits_post_id_idx`; `post_edits_temporal_idx` makes `edited_at` unique
 * per post → a tiebreaker-free cursor), D3 (raw `posts` / `post_edits` read — the
 * admin module is exempt from `RawFromPostsRule` / `BlockExclusionJoinRule` /
 * `display_location`, so a shadow-banned / blocked / auto-hidden post still
 * renders), D4 (NEVER surface `location_snapshot` coordinates — the
 * location-change signal is reduced to a boolean **in SQL** via a `ST_AsBinary`
 * WKB comparison, so no `GEOGRAPHY` / coordinate value ever crosses into Kotlin).
 *
 *  - All queries via JDBC `PreparedStatement`; every value (the `post_id`, the
 *    cursor `edited_at`, the page size) is bound positionally, NEVER
 *    string-interpolated — so a `post_id` carrying SQL metacharacters is a
 *    literal, never executed (the route parses the path segment to a `UUID`
 *    first, so a non-UUID never reaches here at all).
 *  - This repository exposes NO write path. Serving the viewer mutates nothing
 *    and writes no `admin_actions_log` row (design Non-Goals; reads are not
 *    auditable).
 */
class PostEditHistoryRepository(
    private val dataSource: DataSource,
) {
    /**
     * The live post version (design D1 — composed as "Versi terbaru", the newest
     * version above the snapshots). Reads raw `posts` directly (admin
     * lint-exemption); LEFT-JOINs `users` for the author deep-link username so a
     * (referentially-impossible but defensively-handled) missing author row
     * yields a null username rather than dropping the row. The displayed
     * timestamp is `COALESCE(updated_at, created_at)` — `updated_at` is NULL until
     * the first edit. Returns null when the post is absent / hard-deleted (its
     * `post_edits` are `ON DELETE CASCADE`-removed), which the service maps to the
     * not-found empty state (design D5) — never a 500.
     *
     * `actual_location` is read ONLY by [snapshotPage]'s in-SQL comparison; it is
     * never selected into Kotlin here.
     */
    fun liveVersion(postId: UUID): LivePostVersion? {
        val sql =
            """
            SELECT p.id,
                   p.content,
                   p.author_id,
                   u.username AS author_username,
                   p.created_at,
                   p.updated_at
              FROM posts p
              LEFT JOIN users u ON u.id = p.author_id
             WHERE p.id = ?
            """.trimIndent()
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setObject(1, postId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    val createdAt = rs.getTimestamp("created_at")?.toInstant()
                    val updatedAt = rs.getTimestamp("updated_at")?.toInstant()
                    return LivePostVersion(
                        postId = rs.getObject("id", UUID::class.java),
                        content = rs.getString("content"),
                        authorId = rs.getObject("author_id", UUID::class.java),
                        authorUsername = rs.getString("author_username"),
                        // updated_at is NULL until the first edit; fall back to
                        // created_at (and, defensively, to now if both are absent).
                        timestamp = updatedAt ?: createdAt ?: Instant.EPOCH,
                    )
                }
            }
        }
    }

    /**
     * One keyset page of `post_edits` snapshots for [postId], newest-first,
     * over-fetching `pageSize + 1` rows so the extra row (if present) signals
     * "there is an older page" and its predecessor becomes the next cursor — no
     * `OFFSET`, no count query (design D2).
     *
     * Per snapshot the query computes, **entirely in SQL**:
     *  - `version_number` — the presentation "Versi ke-N" number. The composed
     *    list is `[live] + [snapshots newest-first]`, so a snapshot's position is
     *    `1 (live) + 1 + (count of snapshots strictly newer)` = `count_newer + 2`.
     *    Globally correct regardless of page (it counts over the whole post, not
     *    the page window), so page 2 continues the numbering rather than
     *    restarting.
     *  - `location_changed` — whether this snapshot's `location_snapshot` differs
     *    from its **adjacent newer version**'s location: the next-newer snapshot
     *    if one exists, else (for the newest snapshot) the live `posts`
     *    `actual_location` (design D4; the newest snapshot's adjacent newer
     *    version is the live post). Compared as `ST_AsBinary` WKB — an exact
     *    value comparison (the geography `=` operator only compares bounding
     *    boxes, so it is NOT used). The raw geography is reduced to this boolean
     *    in the database; it never materializes in Kotlin.
     */
    fun snapshotPage(
        postId: UUID,
        cursor: PostEditCursor?,
        pageSize: Int = PAGE_SIZE,
    ): PostEditSnapshotPage {
        val params = mutableListOf<Any?>()
        params += postId // pe.post_id = ?
        val cursorClause =
            if (cursor != null) {
                // Keyset "older" predicate aligned with `edited_at DESC`.
                // `post_edits_temporal_idx` (UNIQUE post_id, edited_at) makes
                // `edited_at` unique per post → no secondary tiebreaker needed.
                params += java.sql.Timestamp.from(cursor.editedAt)
                "AND pe.edited_at < ?"
            } else {
                ""
            }

        val sql =
            """
            SELECT pe.id,
                   pe.edited_at,
                   pe.content_snapshot,
                   pe.edited_by,
                   u.username AS editor_username,
                   (
                       SELECT count(*)
                         FROM post_edits nc
                        WHERE nc.post_id = pe.post_id
                          AND nc.edited_at > pe.edited_at
                   ) + 2 AS version_number,
                   (
                       ST_AsBinary(pe.location_snapshot) IS DISTINCT FROM COALESCE(
                           (
                               SELECT ST_AsBinary(nx.location_snapshot)
                                 FROM post_edits nx
                                WHERE nx.post_id = pe.post_id
                                  AND nx.edited_at > pe.edited_at
                                ORDER BY nx.edited_at ASC
                                LIMIT 1
                           ),
                           (
                               SELECT ST_AsBinary(p.actual_location)
                                 FROM posts p
                                WHERE p.id = pe.post_id
                           )
                       )
                   ) AS location_changed
              FROM post_edits pe
              LEFT JOIN users u ON u.id = pe.edited_by
             WHERE pe.post_id = ?
               $cursorClause
             ORDER BY pe.edited_at DESC
             LIMIT ?
            """.trimIndent()

        val fetched = mutableListOf<PostEditSnapshot>()
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                var i = 1
                params.forEach { ps.setObject(i++, it) }
                ps.setInt(i, pageSize + 1)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        fetched +=
                            PostEditSnapshot(
                                versionNumber = rs.getInt("version_number"),
                                content = rs.getString("content_snapshot"),
                                editedAt = rs.getTimestamp("edited_at").toInstant(),
                                editedById = rs.getObject("edited_by", UUID::class.java),
                                editorUsername = rs.getString("editor_username"),
                                locationChanged = rs.getBoolean("location_changed"),
                            )
                    }
                }
            }
        }

        val hasOlder = fetched.size > pageSize
        val display = if (hasOlder) fetched.take(pageSize) else fetched
        val nextCursor =
            if (hasOlder && display.isNotEmpty()) {
                PostEditCursor(display.last().editedAt)
            } else {
                null
            }
        return PostEditSnapshotPage(snapshots = display, nextCursor = nextCursor)
    }

    companion object {
        /** Fixed page size (design D2 — implementation constant, not a client param). */
        const val PAGE_SIZE: Int = 50
    }
}

/**
 * The live post — the newest ("Versi terbaru") version. Carries NO geography or
 * coordinate field: the location-change signal is computed in SQL ([
 * PostEditHistoryRepository.snapshotPage]) and exposed only as a boolean, so
 * coordinates cannot leak through this type (spec: "the view model carries no
 * geography or coordinate field").
 */
data class LivePostVersion(
    val postId: UUID,
    val content: String,
    val authorId: UUID,
    val authorUsername: String?,
    val timestamp: Instant,
)

/**
 * One superseded version (a `post_edits` row). [versionNumber] is the "Versi
 * ke-N" number (≥ 2; 1 is the live version). [locationChanged] is the SQL-derived
 * boolean — there is deliberately NO geography/coordinate field on this type.
 */
data class PostEditSnapshot(
    val versionNumber: Int,
    val content: String,
    val editedAt: Instant,
    val editedById: UUID,
    val editorUsername: String?,
    val locationChanged: Boolean,
)

/** One page of snapshots plus the cursor for the next-older page (null = last page). */
data class PostEditSnapshotPage(
    val snapshots: List<PostEditSnapshot>,
    val nextCursor: PostEditCursor?,
)

/**
 * Opaque keyset cursor over `edited_at`. Encoded as
 * `base64url("<editedAtEpochMicros>")`. Opaque to the user but NOT a security
 * boundary — it only encodes a sort position over data the admin is already
 * authorized to read, and (unlike a geography) carries nothing sensitive. A
 * malformed token decodes to `null` (→ first page), never throws (design D5).
 * `edited_at` alone is a sufficient cursor: `post_edits_temporal_idx` guarantees
 * it is unique per post (design D2).
 */
data class PostEditCursor(
    val editedAt: Instant,
) {
    fun encode(): String {
        val raw = editedAt.toEpochMicros().toString()
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray(Charsets.UTF_8))
    }

    companion object {
        fun decode(token: String?): PostEditCursor? {
            if (token.isNullOrBlank()) return null
            return try {
                val raw = String(Base64.getUrlDecoder().decode(token), Charsets.UTF_8)
                PostEditCursor(instantFromEpochMicros(raw.toLong()))
            } catch (_: IllegalArgumentException) {
                // Not valid base64url or not a parseable Long → first page.
                null
            }
        }

        private fun Instant.toEpochMicros(): Long = epochSecond * 1_000_000 + nano / 1_000

        private fun instantFromEpochMicros(micros: Long): Instant =
            Instant.ofEpochSecond(
                Math.floorDiv(micros, 1_000_000),
                Math.floorMod(micros, 1_000_000) * 1_000L,
            )
    }
}
