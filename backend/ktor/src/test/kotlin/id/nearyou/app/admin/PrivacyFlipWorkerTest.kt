package id.nearyou.app.admin

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.sql.Date
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Worker-logic tests for [PrivacyFlipWorker] (privacy-flip-worker capability):
 * eligibility (elapsed vs in-window vs soft-deleted), the per-flip system-actor
 * audit row, idempotent re-run, audit-failure atomicity, and the closed `<= NOW()`
 * boundary. Real-Postgres (`@Tags("database")`), mirroring `UnbanWorkerRouteTest`.
 */
@Tags("database")
class PrivacyFlipWorkerTest : StringSpec({

    val dataSource = autoClose(hikari())
    val worker = PrivacyFlipWorker(dataSource)

    // Each test's cleanup() deletes its own users + their system_privacy_flip_applied
    // audit rows (by target_id), so no global afterSpec scrub is needed — and an
    // afterSpec touching `dataSource` would race the autoClose pool shutdown.

    fun seedUser(
        privateProfile: Boolean = true,
        scheduledAt: Instant?,
        deletedAt: Instant? = null,
    ): UUID {
        val id = UUID.randomUUID()
        val short = id.toString().replace("-", "").take(8)
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO users (
                    id, username, display_name, date_of_birth, invite_code_prefix,
                    subscription_status, private_profile_opt_in, privacy_flip_scheduled_at, deleted_at
                ) VALUES (?, ?, ?, ?, ?, 'free', ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "pfw_$short")
                ps.setString(3, "PFW $short")
                ps.setDate(4, Date.valueOf(LocalDate.of(1990, 1, 1)))
                ps.setString(5, "p${short.take(7)}")
                ps.setBoolean(6, privateProfile)
                if (scheduledAt != null) ps.setTimestamp(7, Timestamp.from(scheduledAt)) else ps.setNull(7, java.sql.Types.TIMESTAMP)
                if (deletedAt != null) ps.setTimestamp(8, Timestamp.from(deletedAt)) else ps.setNull(8, java.sql.Types.TIMESTAMP)
                ps.executeUpdate()
            }
        }
        return id
    }

    fun cleanup(vararg ids: UUID) {
        if (ids.isEmpty()) return
        dataSource.connection.use { conn ->
            conn.prepareStatement("DELETE FROM admin_actions_log WHERE target_id = ANY(?)").use { ps ->
                ps.setArray(1, conn.createArrayOf("text", ids.map { it.toString() }.toTypedArray()))
                ps.executeUpdate()
            }
            conn.prepareStatement("DELETE FROM users WHERE id = ANY(?)").use { ps ->
                ps.setArray(1, conn.createArrayOf("uuid", ids.toList().toTypedArray()))
                ps.executeUpdate()
            }
        }
    }

    fun loadPrivate(id: UUID): Boolean =
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT private_profile_opt_in FROM users WHERE id = ?").use { ps ->
                ps.setObject(1, id)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getBoolean(1)
                }
            }
        }

    fun loadScheduledIsNull(id: UUID): Boolean =
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT privacy_flip_scheduled_at FROM users WHERE id = ?").use { ps ->
                ps.setObject(1, id)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getTimestamp(1) == null
                }
            }
        }

    fun countAudit(id: UUID): Int =
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM admin_actions_log WHERE target_id = ? AND action_type = 'system_privacy_flip_applied'",
            ).use { ps ->
                ps.setString(1, id.toString())
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    "3.5 elapsed private user is flipped + one system-attributed audit row with valued before/after" {
        val deadline = Instant.now().minusSeconds(3600)
        val u = seedUser(privateProfile = true, scheduledAt = deadline)
        try {
            val result = worker.execute()
            result.flippedUserIds.contains(u) shouldBe true
            loadPrivate(u) shouldBe false
            loadScheduledIsNull(u) shouldBe true
            countAudit(u) shouldBe 1
            dataSource.connection.use { conn ->
                conn.prepareStatement(
                    """
                    SELECT admin_id,
                           before_state ->> 'private_profile_opt_in'                    AS b_priv,
                           (before_state ->> 'privacy_flip_scheduled_at')::timestamptz  AS b_at,
                           after_state  ->> 'private_profile_opt_in'                    AS a_priv,
                           after_state  ->> 'privacy_flip_scheduled_at'                 AS a_at,
                           reason, target_type
                      FROM admin_actions_log
                     WHERE target_id = ? AND action_type = 'system_privacy_flip_applied'
                    """.trimIndent(),
                ).use { ps ->
                    ps.setString(1, u.toString())
                    ps.executeQuery().use { rs ->
                        rs.next()
                        rs.getString("admin_id") shouldBe SuspensionUnbanWorker.SYSTEM_ACTOR_ID.toString()
                        rs.getString("b_priv") shouldBe "true"
                        rs.getTimestamp("b_at").toInstant() shouldBe deadline // ::timestamptz value-equality
                        rs.getString("a_priv") shouldBe "false"
                        rs.getString("a_at") shouldBe null // after_state timestamp is JSON null
                        rs.getString("reason") shouldBe "premium_lapsed_grace_elapsed"
                        rs.getString("target_type") shouldBe "user"
                    }
                }
            }
        } finally {
            cleanup(u)
        }
    }

    "3.6 in-window (future) and soft-deleted elapsed rows are both left untouched + uncounted" {
        val future = seedUser(privateProfile = true, scheduledAt = Instant.now().plusSeconds(3600))
        val softDeleted = seedUser(privateProfile = true, scheduledAt = Instant.now().minusSeconds(3600), deletedAt = Instant.now())
        try {
            val result = worker.execute()
            result.flippedUserIds.contains(future) shouldBe false
            result.flippedUserIds.contains(softDeleted) shouldBe false
            loadPrivate(future) shouldBe true
            loadScheduledIsNull(future) shouldBe false
            loadPrivate(softDeleted) shouldBe true
            loadScheduledIsNull(softDeleted) shouldBe false
            countAudit(future) shouldBe 0
            countAudit(softDeleted) shouldBe 0
        } finally {
            cleanup(future, softDeleted)
        }
    }

    "3.7 a second run with no new elapsed rows is a no-op (idempotent)" {
        val u = seedUser(privateProfile = true, scheduledAt = Instant.now().minusSeconds(3600))
        try {
            worker.execute().flippedUserIds.contains(u) shouldBe true
            countAudit(u) shouldBe 1
            // Re-run: the flip cleared privacy_flip_scheduled_at, so the row no longer matches.
            val second = worker.execute()
            second.flippedUserIds.contains(u) shouldBe false
            countAudit(u) shouldBe 1 // still exactly one — no second audit row
        } finally {
            cleanup(u)
        }
    }

    "3.7 a failing audit INSERT rolls back the flip (atomic)" {
        val u = seedUser(privateProfile = true, scheduledAt = Instant.now().minusSeconds(3600))
        // A BEFORE-INSERT trigger that rejects exactly this worker's audit rows, so the
        // single CTE throws and the whole transaction (flip + audit) rolls back. Scoped +
        // dropped in finally so it cannot leak into other specs.
        dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                st.execute(
                    """
                    CREATE OR REPLACE FUNCTION test_block_privacy_audit() RETURNS trigger AS ${'$'}${'$'}
                    BEGIN
                      IF NEW.action_type = 'system_privacy_flip_applied' THEN
                        RAISE EXCEPTION 'test: blocking privacy audit insert';
                      END IF;
                      RETURN NEW;
                    END; ${'$'}${'$'} LANGUAGE plpgsql;
                    """.trimIndent(),
                )
                st.execute(
                    "CREATE TRIGGER test_block_privacy_audit_trg BEFORE INSERT ON admin_actions_log " +
                        "FOR EACH ROW EXECUTE FUNCTION test_block_privacy_audit();",
                )
            }
        }
        try {
            shouldThrowAny { worker.execute() }
            loadPrivate(u) shouldBe true // flip rolled back
            loadScheduledIsNull(u) shouldBe false // deadline still set
            countAudit(u) shouldBe 0
        } finally {
            dataSource.connection.use { conn ->
                conn.createStatement().use { st ->
                    st.execute("DROP TRIGGER IF EXISTS test_block_privacy_audit_trg ON admin_actions_log;")
                    st.execute("DROP FUNCTION IF EXISTS test_block_privacy_audit();")
                }
            }
            cleanup(u)
        }
    }

    "3.11 a deadline at/just-before NOW() is flipped (closed <= boundary)" {
        // Seeded at the DB's NOW(); by the time the worker runs, NOW() has advanced, so
        // the row sits at-or-before the worker's evaluation instant — the closed boundary.
        // (The literal `=` knife-edge is unreachable without freezing NOW(); this pins
        // that a deadline at the current instant is included, distinct from the read
        // short-circuit's strict `> now()`.)
        val u =
            dataSource.connection.use { conn ->
                val id = UUID.randomUUID()
                val short = id.toString().replace("-", "").take(8)
                conn.prepareStatement(
                    """
                    INSERT INTO users (id, username, display_name, date_of_birth, invite_code_prefix,
                                       subscription_status, private_profile_opt_in, privacy_flip_scheduled_at)
                    VALUES (?, ?, ?, ?, ?, 'free', TRUE, NOW())
                    """.trimIndent(),
                ).use { ps ->
                    ps.setObject(1, id)
                    ps.setString(2, "pfwb_$short")
                    ps.setString(3, "PFWB $short")
                    ps.setDate(4, Date.valueOf(LocalDate.of(1990, 1, 1)))
                    ps.setString(5, "b${short.take(7)}")
                    ps.executeUpdate()
                }
                id
            }
        try {
            worker.execute().flippedUserIds.contains(u) shouldBe true
            loadPrivate(u) shouldBe false
            loadScheduledIsNull(u) shouldBe true
        } finally {
            cleanup(u)
        }
    }
})

private fun hikari(): HikariDataSource {
    val url = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5433/nearyou_dev"
    val user = System.getenv("DB_USER") ?: "postgres"
    val password = System.getenv("DB_PASSWORD") ?: "postgres"
    return HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = url
            username = user
            this.password = password
            maximumPoolSize = 2
            initializationFailTimeout = -1
        },
    )
}
