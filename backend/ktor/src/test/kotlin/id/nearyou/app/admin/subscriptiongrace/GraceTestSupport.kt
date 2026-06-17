package id.nearyou.app.admin.subscriptiongrace

import java.sql.Date
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

/**
 * Seeding + assertion helpers for the subscription-grace-monitor integration
 * tests (`admin-subscription-grace-monitor` tasks.md Section 5).
 *
 * Seeds `users` rows with a controllable `subscription_status` + `deleted_at`,
 * `subscription_events` rows (the billing ledger the monitor reads), and
 * `admin_actions_log` rows (for the rate-limit-cap, budget-independence, and
 * already-expedited-indicator cases). Cleanup is per-seeded-id (NOT a full-table
 * wipe): `users` / `subscription_events` are shared with other specs, so each
 * test removes only the ids it created (subscription_events first, to satisfy the
 * `subscription_events.user_id → users(id)` FK).
 */
object GraceTestSupport {
    /** Seed a `users` row with an explicit [subscriptionStatus] + optional [deletedAt]. */
    fun seedUser(
        dataSource: DataSource,
        username: String,
        subscriptionStatus: String = STATUS_BILLING_RETRY,
        displayName: String = "Grace Tester",
        deletedAt: Instant? = null,
    ): UUID {
        val id = UUID.randomUUID()
        val short = id.toString().replace("-", "").take(8)
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO users (
                    id, username, display_name, date_of_birth, invite_code_prefix,
                    subscription_status, deleted_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setString(2, username)
                ps.setString(3, displayName)
                ps.setDate(4, Date.valueOf(LocalDate.of(1995, 3, 14)))
                ps.setString(5, "g${short.take(7)}")
                ps.setString(6, subscriptionStatus)
                ps.setObject(7, deletedAt?.let { Timestamp.from(it) })
                ps.executeUpdate()
            }
        }
        return id
    }

    /** Seed a `subscription_events` row for [userId]. */
    fun seedEvent(
        dataSource: DataSource,
        userId: UUID,
        eventType: String,
        platform: String? = "app_store",
        source: String = "paid",
        createdAt: Instant = Instant.now(),
    ) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO subscription_events (user_id, event_type, source, platform, created_at)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, userId)
                ps.setString(2, eventType)
                ps.setString(3, source)
                ps.setString(4, platform)
                ps.setObject(5, Timestamp.from(createdAt))
                ps.executeUpdate()
            }
        }
    }

    /** Seed an `admin_actions_log` row of an arbitrary [actionType] for the
     *  acting [adminId] — backs the cap + budget-independence tests. [targetId]
     *  is the optional target user id (TEXT column). */
    fun seedAuditRow(
        dataSource: DataSource,
        adminId: UUID,
        actionType: String,
        targetId: UUID? = null,
        createdAt: Instant = Instant.now(),
    ) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO admin_actions_log (admin_id, action_type, target_type, target_id, created_at)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, adminId)
                ps.setString(2, actionType)
                ps.setString(3, if (targetId != null) "user" else null)
                ps.setString(4, targetId?.toString())
                ps.setObject(5, Timestamp.from(createdAt))
                ps.executeUpdate()
            }
        }
    }

    /** Count `subscription_grace_expedite` audit rows targeting [targetUserId]. */
    fun countExpeditesFor(
        dataSource: DataSource,
        targetUserId: UUID,
    ): Int =
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT count(*) FROM admin_actions_log " +
                    "WHERE action_type = 'subscription_grace_expedite' AND target_id = ?",
            ).use { ps ->
                ps.setString(1, targetUserId.toString())
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    /** Count of non-deleted users currently in the billing-retry window — the
     *  baseline for delta-based count assertions (robust to any pre-existing
     *  billing-retry rows in a shared dev DB). */
    fun billingRetryCount(dataSource: DataSource): Long =
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT count(*) FROM users " +
                    "WHERE subscription_status = '$STATUS_BILLING_RETRY' AND deleted_at IS NULL",
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            }
        }

    /** Current `subscription_status` of [userId] — for the "entitlement unchanged" assertion. */
    fun subscriptionStatusOf(
        dataSource: DataSource,
        userId: UUID,
    ): String? =
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT subscription_status FROM users WHERE id = ?").use { ps ->
                ps.setObject(1, userId)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

    /** Remove a seeded user by id (events first, for the FK), per-test cleanup. */
    fun deleteUser(
        dataSource: DataSource,
        id: UUID,
    ) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("DELETE FROM subscription_events WHERE user_id = ?").use { ps ->
                ps.setObject(1, id)
                ps.executeUpdate()
            }
            conn.prepareStatement("DELETE FROM users WHERE id = ?").use { ps ->
                ps.setObject(1, id)
                ps.executeUpdate()
            }
        }
    }
}
