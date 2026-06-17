package id.nearyou.app.admin.rejectedidentifiers

import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * Seeding helper for `rejected_identifiers` rows in the rejected-identifiers-
 * viewer integration tests (`admin-rejected-identifiers-viewer` tasks.md
 * Section 6).
 *
 * Unlike `admin_actions_log`, `rejected_identifiers` has NO `admin_id` (rows
 * are system-written at signup), so tests cannot scope-isolate by actor. The
 * table is test-owned in the integration DB (only `SignupFlowTest` + these
 * specs write it; Gradle runs the test JVM single-fork and Kotest specs run
 * sequentially), so isolation is a full-table [deleteAll] in `beforeEach` —
 * mirroring `SignupFlowTest.cleanSlate()`.
 *
 * `rejected_at` is bound as an absolute [Instant] (constructed in the tests
 * with explicit UTC instants) via `Timestamp.from`, matching the production
 * `AdminRejectedIdentifiersRepository` binding — seed + query share the JVM,
 * so the boundary comparisons are deterministic regardless of host timezone.
 */
object RejectedIdentifiersTestSupport {
    fun seedRejectedIdentifier(
        dataSource: DataSource,
        identifierHash: String,
        identifierType: String,
        reason: String,
        rejectedAt: Instant,
    ): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO rejected_identifiers (
                    id, identifier_hash, identifier_type, reason, rejected_at
                ) VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setString(2, identifierHash)
                ps.setString(3, identifierType)
                ps.setString(4, reason)
                ps.setObject(5, Timestamp.from(rejectedAt))
                ps.executeUpdate()
            }
        }
        return id
    }

    /** Wipe the whole table so each test starts from a known-empty state. */
    fun deleteAll(dataSource: DataSource) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("DELETE FROM rejected_identifiers").use { ps ->
                ps.executeUpdate()
            }
        }
    }

    /** Total row count — for the read-only "count unchanged after serving" assertion. */
    fun count(dataSource: DataSource): Int =
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT count(*) FROM rejected_identifiers").use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    /** True when a row with [id] still exists — for the clear-action delete /
     *  not-found / atomic-rollback assertions (`admin-rejected-identifiers-clear-action`). */
    fun existsById(
        dataSource: DataSource,
        id: UUID,
    ): Boolean =
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT 1 FROM rejected_identifiers WHERE id = ?").use { ps ->
                ps.setObject(1, id)
                ps.executeQuery().use { rs -> rs.next() }
            }
        }

    /**
     * Seed one `admin_actions_log` row of `action_type = 'rejected_identifier_cleared'`
     * with a controlled trailing-hour age — the COUNT substrate for the clear
     * 10/hr cap. Seeds by RELATIVE age against DB `NOW()` (not an absolute
     * client-side `Instant`) so the trailing-window comparison is deterministic
     * regardless of the host's clock precision (macOS micros vs Linux nanos).
     * Mirrors `ReservedUsernamesTestSupport.seedReservedAudit`.
     */
    fun seedClearAudit(
        dataSource: DataSource,
        adminId: UUID,
        ageMinutes: Int = 1,
    ) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "INSERT INTO admin_actions_log (admin_id, action_type, target_type, target_id, reason, created_at) " +
                    "VALUES (?, 'rejected_identifier_cleared', 'rejected_identifier', ?, 'seed', NOW() - (? * INTERVAL '1 minute'))",
            ).use { ps ->
                ps.setObject(1, adminId)
                ps.setString(2, UUID.randomUUID().toString())
                ps.setInt(3, ageMinutes)
                ps.executeUpdate()
            }
        }
    }
}
