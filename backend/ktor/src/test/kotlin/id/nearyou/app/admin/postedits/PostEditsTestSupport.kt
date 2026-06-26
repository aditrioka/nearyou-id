package id.nearyou.app.admin.postedits

import java.sql.Date
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

/**
 * Seeding helper for the Post Edit History viewer integration tests
 * (`admin-post-edit-history` tasks.md Section 4).
 *
 * `posts` / `post_edits` / `users` are shared tables, so isolation is per-test:
 * each test seeds its own users + posts and tracks them for `afterEach` cleanup.
 * Deleting a post cascades its `post_edits` rows (`ON DELETE CASCADE`); the
 * author `users` row is deleted after its posts (`posts.author_id` is
 * `ON DELETE RESTRICT`).
 *
 * Timestamps bind as absolute [Instant]s via `Timestamp.from` — the same binding
 * the production [PostEditHistoryRepository] uses for the keyset cursor — so the
 * page boundaries are deterministic regardless of host timezone.
 *
 * Locations are PostGIS `GEOGRAPHY(POINT, 4326)` built from explicit lon/lat so a
 * test can make a snapshot's `location_snapshot` match or differ from the live
 * post's `actual_location` and exercise the SQL-derived `location_changed`
 * boolean.
 */
object PostEditsTestSupport {
    /** Default Jakarta-ish point used for the live post + matching snapshots. */
    const val LON: Double = 106.8
    const val LAT: Double = -6.2

    /** Insert a `users` row with the minimum NOT-NULL columns and return its id. */
    fun seedUser(
        dataSource: DataSource,
        username: String? = null,
        shadowBanned: Boolean = false,
    ): UUID {
        val id = UUID.randomUUID()
        val short = id.toString().replace("-", "").take(8)
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO users (
                    id, username, display_name, date_of_birth, invite_code_prefix, is_shadow_banned
                ) VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setString(2, username ?: "pe_$short")
                ps.setString(3, "Post Edit History Test User")
                ps.setDate(4, Date.valueOf(LocalDate.of(1990, 1, 1)))
                ps.setString(5, "p${short.take(7)}")
                ps.setBoolean(6, shadowBanned)
                ps.executeUpdate()
            }
        }
        return id
    }

    /**
     * Insert a `posts` row (the live version) and return its id. [createdAt]
     * defaults to a fixed base; [updatedAt] is NULL by default (never edited).
     * [lon]/[lat] seed BOTH `display_location` and `actual_location`.
     */
    fun seedPost(
        dataSource: DataSource,
        authorId: UUID,
        content: String = "live content",
        lon: Double = LON,
        lat: Double = LAT,
        createdAt: Instant = Instant.parse("2026-06-10T19:00:00Z"),
        updatedAt: Instant? = null,
    ): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO posts (id, author_id, content, display_location, actual_location, created_at, updated_at)
                VALUES (?, ?, ?,
                  ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                  ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                  ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setObject(2, authorId)
                ps.setString(3, content)
                ps.setDouble(4, lon)
                ps.setDouble(5, lat)
                ps.setDouble(6, lon)
                ps.setDouble(7, lat)
                ps.setTimestamp(8, Timestamp.from(createdAt))
                if (updatedAt == null) ps.setNull(9, Types.TIMESTAMP) else ps.setTimestamp(9, Timestamp.from(updatedAt))
                ps.executeUpdate()
            }
        }
        return id
    }

    /** Insert one before-edit snapshot (`post_edits`) at [editedAt]. */
    fun seedEdit(
        dataSource: DataSource,
        postId: UUID,
        editedBy: UUID,
        snapshot: String,
        editedAt: Instant,
        lon: Double = LON,
        lat: Double = LAT,
    ) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO post_edits (post_id, content_snapshot, location_snapshot, edited_at, edited_by)
                VALUES (?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, postId)
                ps.setString(2, snapshot)
                ps.setDouble(3, lon)
                ps.setDouble(4, lat)
                ps.setTimestamp(5, Timestamp.from(editedAt))
                ps.setObject(6, editedBy)
                ps.executeUpdate()
            }
        }
    }

    /** Delete a seeded post (cascades its `post_edits` rows). */
    fun deletePost(
        dataSource: DataSource,
        postId: UUID,
    ) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("DELETE FROM posts WHERE id = ?").use { ps ->
                ps.setObject(1, postId)
                ps.executeUpdate()
            }
        }
    }

    /** Delete all posts authored by [authorId] (cascades their `post_edits`). */
    fun deletePostsByAuthor(
        dataSource: DataSource,
        authorId: UUID,
    ) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("DELETE FROM posts WHERE author_id = ?").use { ps ->
                ps.setObject(1, authorId)
                ps.executeUpdate()
            }
        }
    }

    /** Delete a seeded `users` row (call after its posts are removed). */
    fun deleteUser(
        dataSource: DataSource,
        id: UUID,
    ) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("DELETE FROM users WHERE id = ?").use { ps ->
                ps.setObject(1, id)
                ps.executeUpdate()
            }
        }
    }

    /** Total post count — for the read-only "count unchanged after serving" assertion. */
    fun countPosts(dataSource: DataSource): Int =
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT count(*) FROM posts").use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    /** Total post_edits count — for the read-only "no snapshot written" assertion. */
    fun countEdits(dataSource: DataSource): Int =
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT count(*) FROM post_edits").use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }
}
