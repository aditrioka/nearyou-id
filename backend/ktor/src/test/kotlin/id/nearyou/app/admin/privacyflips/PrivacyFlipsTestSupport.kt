package id.nearyou.app.admin.privacyflips

import java.sql.Date
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

/**
 * Seeding helper for `users` rows in the privacy-flip-monitor integration
 * tests (`admin-privacy-flip-monitor` tasks.md Section 6).
 *
 * Seeds a `users` row with a controllable `privacy_flip_scheduled_at`,
 * `private_profile_opt_in`, and `deleted_at` so the classification (IN_WINDOW /
 * OVERDUE), the soft-deleted-inclusion case (design D6), and the boundary
 * fencepost (design D3) can all be exercised deterministically.
 *
 * `privacy_flip_scheduled_at` is bound as an absolute [Instant] via
 * `Timestamp.from`, matching the production `AdminPrivacyFlipsRepository`
 * binding — seed + query share the JVM, so the boundary comparisons against the
 * injected `evalInstant` are deterministic regardless of host timezone.
 *
 * Cleanup is per-seeded-id (NOT a full-table wipe): `users` is shared with many
 * other specs, so each test removes only the ids it created.
 */
object PrivacyFlipsTestSupport {
    fun seedUser(
        dataSource: DataSource,
        username: String,
        displayName: String = "Flip Tester",
        scheduledAt: Instant?,
        isPrivate: Boolean = true,
        deletedAt: Instant? = null,
    ): UUID {
        val id = UUID.randomUUID()
        val short = id.toString().replace("-", "").take(8)
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO users (
                    id, username, display_name, date_of_birth, invite_code_prefix,
                    private_profile_opt_in, privacy_flip_scheduled_at, deleted_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setString(2, username)
                ps.setString(3, displayName)
                ps.setDate(4, Date.valueOf(LocalDate.of(1995, 3, 14)))
                ps.setString(5, "p${short.take(7)}")
                ps.setBoolean(6, isPrivate)
                ps.setObject(7, scheduledAt?.let { Timestamp.from(it) })
                ps.setObject(8, deletedAt?.let { Timestamp.from(it) })
                ps.executeUpdate()
            }
        }
        return id
    }

    /** Remove a seeded user by id (per-test cleanup; the table is shared). */
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

    /** Count of users currently carrying a pending flip — for the read-only "unchanged" assertion. */
    fun pendingFlipCount(dataSource: DataSource): Int =
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT count(*) FROM users WHERE privacy_flip_scheduled_at IS NOT NULL",
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }
}
