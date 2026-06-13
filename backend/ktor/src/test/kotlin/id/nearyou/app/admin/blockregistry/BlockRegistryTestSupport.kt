package id.nearyou.app.admin.blockregistry

import java.sql.Date
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

/**
 * Seeding helper for the Block User Registry integration tests
 * (`admin-block-registry` tasks.md Section 7).
 *
 * `user_blocks` + `users` are shared tables (not test-owned like
 * `rejected_identifiers`), so isolation is per-test: each test starts from a
 * [deleteAllBlocks] clean slate (block rows are cheap test artifacts) and
 * tracks the `users` rows it seeds for cleanup in `afterEach`. Deleting a
 * seeded user cascades its `user_blocks` rows (`ON DELETE CASCADE`).
 *
 * `created_at` is bound as an absolute [Instant] via `Timestamp.from` — the
 * same binding the production [AdminBlockRegistryRepository] uses — so the
 * keyset boundary comparisons are deterministic regardless of host timezone.
 */
object BlockRegistryTestSupport {
    /**
     * Insert a `users` row with the minimum NOT-NULL columns and return its id.
     * `username` defaults to a per-id unique value; pass an explicit one when a
     * test asserts on the rendered username.
     */
    fun seedUser(
        dataSource: DataSource,
        username: String? = null,
    ): UUID {
        val id = UUID.randomUUID()
        val short = id.toString().replace("-", "").take(8)
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO users (
                    id, username, display_name, date_of_birth, invite_code_prefix
                ) VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setString(2, username ?: "blk_$short")
                ps.setString(3, "Block Registry Test User")
                ps.setDate(4, Date.valueOf(LocalDate.of(1990, 1, 1)))
                ps.setString(5, "b${short.take(7)}")
                ps.executeUpdate()
            }
        }
        return id
    }

    /** Insert a directed block pair `(blocker → blocked)` at the given instant. */
    fun seedBlock(
        dataSource: DataSource,
        blockerId: UUID,
        blockedId: UUID,
        createdAt: Instant,
    ) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO user_blocks (blocker_id, blocked_id, created_at)
                VALUES (?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, blockerId)
                ps.setObject(2, blockedId)
                ps.setObject(3, Timestamp.from(createdAt))
                ps.executeUpdate()
            }
        }
    }

    /** Wipe all block rows so each test starts from a known-empty registry. */
    fun deleteAllBlocks(dataSource: DataSource) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("DELETE FROM user_blocks").use { it.executeUpdate() }
        }
    }

    /** Delete a seeded `users` row (cascades its `user_blocks` rows). */
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

    /** Total block-row count — for the read-only "count unchanged after serving" assertion. */
    fun countBlocks(dataSource: DataSource): Int =
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT count(*) FROM user_blocks").use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    /** Total notification count — for the "serving the viewer notifies no one" assertion. */
    fun countNotifications(dataSource: DataSource): Int =
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT count(*) FROM notifications").use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }
}
