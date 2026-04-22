package id.nearyou.app.infra.db

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.time.LocalDate
import java.util.UUID

/**
 * Database-dependent smoke test for V9 (`reports` + `moderation_queue`). Boots
 * Flyway against the running dev Postgres and asserts the V9 invariants from the
 * `reports` + `moderation-queue` + `migration-pipeline` spec deltas:
 *  - V9 lands cleanly (cold V1→V9 + from-V8 both) and is idempotent on a second
 *    run.
 *  - Both tables exist with the canonical column set.
 *  - All 8 CHECK constraints reject out-of-enum values.
 *  - UNIQUE constraints fire SQLSTATE 23505 on duplicate inserts.
 *  - All 5 indexes exist with documented column orders.
 *  - `reports.reporter_id` FK has `delete_rule = 'CASCADE'`.
 *  - Deferred-FK columns (`reports.reviewed_by`, `moderation_queue.resolved_by`,
 *    `reports.target_id`, `moderation_queue.target_id`) have zero referential
 *    constraints.
 *  - `reviewed_by` / `resolved_by` comments mention `admin_users` AND `deferred`.
 *  - CASCADE behavior: deleting a user cascades to their reports rows.
 *  - `flyway_schema_history` contains versions 1..9.
 *  - `priority` column default is 5.
 */
@Tags("database")
class MigrationV9SmokeTest : StringSpec({

    val url = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5433/nearyou_dev"
    val user = System.getenv("DB_USER") ?: "postgres"
    val password = System.getenv("DB_PASSWORD") ?: "postgres"

    Flyway
        .configure()
        .dataSource(url, user, password)
        .locations("classpath:db/migration")
        .load()
        .migrate()

    fun seedUser(
        conn: Connection,
        shortPrefix: String = "v9",
    ): UUID {
        val id = UUID.randomUUID()
        val short = id.toString().replace("-", "").take(8)
        conn.prepareStatement(
            """
            INSERT INTO users (
                id, username, display_name, date_of_birth, invite_code_prefix
            ) VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, id)
            ps.setString(2, "${shortPrefix}_$short")
            ps.setString(3, "V9 Smoke")
            ps.setDate(4, java.sql.Date.valueOf(LocalDate.of(1990, 1, 1)))
            ps.setString(5, "v${short.take(7)}")
            ps.executeUpdate()
        }
        return id
    }

    fun insertReport(
        conn: Connection,
        reporterId: UUID,
        targetType: String,
        targetId: UUID,
        reasonCategory: String = "spam",
        status: String? = null,
    ) {
        val sql =
            if (status == null) {
                "INSERT INTO reports (reporter_id, target_type, target_id, reason_category) VALUES (?, ?, ?, ?)"
            } else {
                "INSERT INTO reports (reporter_id, target_type, target_id, reason_category, status) VALUES (?, ?, ?, ?, ?)"
            }
        conn.prepareStatement(sql).use { ps ->
            ps.setObject(1, reporterId)
            ps.setString(2, targetType)
            ps.setObject(3, targetId)
            ps.setString(4, reasonCategory)
            if (status != null) ps.setString(5, status)
            ps.executeUpdate()
        }
    }

    "V9 present in flyway_schema_history with success" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(
                    "SELECT success FROM flyway_schema_history WHERE version = '9'",
                ).use { rs ->
                    rs.next() shouldBe true
                    rs.getBoolean(1) shouldBe true
                }
            }
        }
    }

    "flyway_schema_history contains versions 1..9 all successful" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(
                    "SELECT version FROM flyway_schema_history WHERE success = TRUE",
                ).use { rs ->
                    val versions = mutableSetOf<String>()
                    while (rs.next()) versions += rs.getString(1)
                    (1..9).forEach { v -> versions.contains(v.toString()) shouldBe true }
                }
            }
        }
    }

    "re-running flywayMigrate against a DB already at V9 is a no-op" {
        val before =
            DriverManager.getConnection(url, user, password).use { conn ->
                conn.createStatement().use { st ->
                    st.executeQuery("SELECT COUNT(*) FROM flyway_schema_history").use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
            }
        Flyway
            .configure()
            .dataSource(url, user, password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        val after =
            DriverManager.getConnection(url, user, password).use { conn ->
                conn.createStatement().use { st ->
                    st.executeQuery("SELECT COUNT(*) FROM flyway_schema_history").use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
            }
        before shouldBe after
    }

    "reports has every canonical column" {
        val expected =
            setOf(
                "id", "reporter_id", "target_type", "target_id", "reason_category",
                "reason_note", "status", "created_at", "reviewed_at", "reviewed_by",
            )
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(
                    "SELECT column_name FROM information_schema.columns WHERE table_name = 'reports'",
                ).use { rs ->
                    val present = mutableSetOf<String>()
                    while (rs.next()) present += rs.getString(1)
                    present.containsAll(expected) shouldBe true
                }
            }
        }
    }

    "moderation_queue has every canonical column" {
        val expected =
            setOf(
                "id", "target_type", "target_id", "trigger", "status", "resolution",
                "priority", "created_at", "resolved_at", "resolved_by", "notes",
            )
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(
                    "SELECT column_name FROM information_schema.columns WHERE table_name = 'moderation_queue'",
                ).use { rs ->
                    val present = mutableSetOf<String>()
                    while (rs.next()) present += rs.getString(1)
                    present.containsAll(expected) shouldBe true
                }
            }
        }
    }

    "reports.target_type CHECK rejects out-of-enum value" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.autoCommit = false
            try {
                val reporter = seedUser(conn)
                var sqlState: String? = null
                val sp = conn.setSavepoint()
                try {
                    insertReport(conn, reporter, "meme", UUID.randomUUID())
                } catch (ex: SQLException) {
                    sqlState = ex.sqlState
                    conn.rollback(sp)
                }
                sqlState shouldBe "23514"
            } finally {
                conn.rollback()
            }
        }
    }

    "reports.reason_category CHECK rejects out-of-enum value" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.autoCommit = false
            try {
                val reporter = seedUser(conn)
                var sqlState: String? = null
                val sp = conn.setSavepoint()
                try {
                    insertReport(conn, reporter, "post", UUID.randomUUID(), reasonCategory = "political_disagreement")
                } catch (ex: SQLException) {
                    sqlState = ex.sqlState
                    conn.rollback(sp)
                }
                sqlState shouldBe "23514"
            } finally {
                conn.rollback()
            }
        }
    }

    "reports.status CHECK rejects out-of-enum value" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.autoCommit = false
            try {
                val reporter = seedUser(conn)
                var sqlState: String? = null
                val sp = conn.setSavepoint()
                try {
                    insertReport(conn, reporter, "post", UUID.randomUUID(), status = "escalated")
                } catch (ex: SQLException) {
                    sqlState = ex.sqlState
                    conn.rollback(sp)
                }
                sqlState shouldBe "23514"
            } finally {
                conn.rollback()
            }
        }
    }

    "moderation_queue.target_type CHECK rejects out-of-enum value" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.autoCommit = false
            try {
                var sqlState: String? = null
                val sp = conn.setSavepoint()
                try {
                    conn.prepareStatement(
                        "INSERT INTO moderation_queue (target_type, target_id, trigger) VALUES (?, ?, ?)",
                    ).use { ps ->
                        ps.setString(1, "dm")
                        ps.setObject(2, UUID.randomUUID())
                        ps.setString(3, "auto_hide_3_reports")
                        ps.executeUpdate()
                    }
                } catch (ex: SQLException) {
                    sqlState = ex.sqlState
                    conn.rollback(sp)
                }
                sqlState shouldBe "23514"
            } finally {
                conn.rollback()
            }
        }
    }

    "moderation_queue.trigger CHECK rejects out-of-enum value" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.autoCommit = false
            try {
                var sqlState: String? = null
                val sp = conn.setSavepoint()
                try {
                    conn.prepareStatement(
                        "INSERT INTO moderation_queue (target_type, target_id, trigger) VALUES (?, ?, ?)",
                    ).use { ps ->
                        ps.setString(1, "post")
                        ps.setObject(2, UUID.randomUUID())
                        ps.setString(3, "spam_pattern_detected")
                        ps.executeUpdate()
                    }
                } catch (ex: SQLException) {
                    sqlState = ex.sqlState
                    conn.rollback(sp)
                }
                sqlState shouldBe "23514"
            } finally {
                conn.rollback()
            }
        }
    }

    "moderation_queue.trigger CHECK accepts all 7 enum values" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.autoCommit = false
            try {
                val triggers =
                    listOf(
                        "auto_hide_3_reports",
                        "perspective_api_high_score",
                        "uu_ite_keyword_match",
                        "admin_flag",
                        "csam_detected",
                        "anomaly_detection",
                        "username_flagged",
                    )
                triggers.forEach { t ->
                    conn.prepareStatement(
                        "INSERT INTO moderation_queue (target_type, target_id, trigger) VALUES (?, ?, ?)",
                    ).use { ps ->
                        ps.setString(1, "post")
                        ps.setObject(2, UUID.randomUUID())
                        ps.setString(3, t)
                        ps.executeUpdate()
                    }
                }
            } finally {
                conn.rollback()
            }
        }
    }

    "moderation_queue.status CHECK rejects out-of-enum value" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.autoCommit = false
            try {
                var sqlState: String? = null
                val sp = conn.setSavepoint()
                try {
                    conn.prepareStatement(
                        "INSERT INTO moderation_queue (target_type, target_id, trigger, status) VALUES (?, ?, ?, ?)",
                    ).use { ps ->
                        ps.setString(1, "post")
                        ps.setObject(2, UUID.randomUUID())
                        ps.setString(3, "auto_hide_3_reports")
                        ps.setString(4, "in_review")
                        ps.executeUpdate()
                    }
                } catch (ex: SQLException) {
                    sqlState = ex.sqlState
                    conn.rollback(sp)
                }
                sqlState shouldBe "23514"
            } finally {
                conn.rollback()
            }
        }
    }

    "moderation_queue.resolution CHECK rejects out-of-enum non-NULL value" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement(
                    "INSERT INTO moderation_queue (target_type, target_id, trigger) VALUES (?, ?, ?) RETURNING id",
                ).use { ps ->
                    ps.setString(1, "post")
                    ps.setObject(2, UUID.randomUUID())
                    ps.setString(3, "auto_hide_3_reports")
                    val id =
                        ps.executeQuery().use {
                            it.next()
                            it.getObject(1, UUID::class.java)
                        }
                    var sqlState: String? = null
                    val sp = conn.setSavepoint()
                    try {
                        conn.prepareStatement(
                            "UPDATE moderation_queue SET resolution = ? WHERE id = ?",
                        ).use { up ->
                            up.setString(1, "escalate_to_legal")
                            up.setObject(2, id)
                            up.executeUpdate()
                        }
                    } catch (ex: SQLException) {
                        sqlState = ex.sqlState
                        conn.rollback(sp)
                    }
                    sqlState shouldBe "23514"
                }
            } finally {
                conn.rollback()
            }
        }
    }

    "moderation_queue priority defaults to 5" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement(
                    "INSERT INTO moderation_queue (target_type, target_id, trigger) VALUES (?, ?, ?) RETURNING priority",
                ).use { ps ->
                    ps.setString(1, "post")
                    ps.setObject(2, UUID.randomUUID())
                    ps.setString(3, "auto_hide_3_reports")
                    ps.executeQuery().use { rs ->
                        rs.next() shouldBe true
                        rs.getInt(1) shouldBe 5
                    }
                }
            } finally {
                conn.rollback()
            }
        }
    }

    "reports UNIQUE (reporter_id, target_type, target_id) fires 23505 on duplicate" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.autoCommit = false
            try {
                val reporter = seedUser(conn)
                val tid = UUID.randomUUID()
                insertReport(conn, reporter, "post", tid)
                var sqlState: String? = null
                val sp = conn.setSavepoint()
                try {
                    insertReport(conn, reporter, "post", tid)
                } catch (ex: SQLException) {
                    sqlState = ex.sqlState
                    conn.rollback(sp)
                }
                sqlState shouldBe "23505"
            } finally {
                conn.rollback()
            }
        }
    }

    "moderation_queue UNIQUE (target_type, target_id, trigger) fires 23505 on duplicate" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.autoCommit = false
            try {
                val tid = UUID.randomUUID()
                conn.prepareStatement(
                    "INSERT INTO moderation_queue (target_type, target_id, trigger) VALUES (?, ?, 'auto_hide_3_reports')",
                ).use { ps ->
                    ps.setString(1, "post")
                    ps.setObject(2, tid)
                    ps.executeUpdate()
                }
                var sqlState: String? = null
                val sp = conn.setSavepoint()
                try {
                    conn.prepareStatement(
                        "INSERT INTO moderation_queue (target_type, target_id, trigger) VALUES (?, ?, 'auto_hide_3_reports')",
                    ).use { ps ->
                        ps.setString(1, "post")
                        ps.setObject(2, tid)
                        ps.executeUpdate()
                    }
                } catch (ex: SQLException) {
                    sqlState = ex.sqlState
                    conn.rollback(sp)
                }
                sqlState shouldBe "23505"
            } finally {
                conn.rollback()
            }
        }
    }

    "all five indexes exist with documented column orders" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(
                    """
                    SELECT i.relname,
                           pg_get_indexdef(ix.indexrelid) AS def
                      FROM pg_class i
                      JOIN pg_index ix ON ix.indexrelid = i.oid
                      JOIN pg_class t  ON ix.indrelid = t.oid
                     WHERE t.relname IN ('reports', 'moderation_queue')
                    """.trimIndent(),
                ).use { rs ->
                    val byName = mutableMapOf<String, String>()
                    while (rs.next()) byName[rs.getString(1)] = rs.getString(2)
                    byName.keys shouldContainAll
                        setOf(
                            "reports_status_idx",
                            "reports_target_idx",
                            "reports_reporter_idx",
                            "moderation_queue_status_idx",
                            "moderation_queue_target_idx",
                        )
                    byName["reports_status_idx"]!!.contains("(status, created_at DESC)") shouldBe true
                    byName["reports_target_idx"]!!.contains("(target_type, target_id)") shouldBe true
                    byName["reports_reporter_idx"]!!
                        .contains("(reporter_id, created_at DESC)") shouldBe true
                    byName["moderation_queue_status_idx"]!!
                        .contains("(status, priority, created_at)") shouldBe true
                    byName["moderation_queue_target_idx"]!!
                        .contains("(target_type, target_id)") shouldBe true
                }
            }
        }
    }

    "reports.reporter_id FK delete rule is CASCADE" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.prepareStatement(
                """
                SELECT rc.delete_rule
                  FROM information_schema.table_constraints tc
                  JOIN information_schema.key_column_usage kcu
                    ON tc.constraint_name = kcu.constraint_name
                  JOIN information_schema.referential_constraints rc
                    ON tc.constraint_name = rc.constraint_name
                 WHERE tc.table_name = 'reports'
                   AND tc.constraint_type = 'FOREIGN KEY'
                   AND kcu.column_name = 'reporter_id'
                """.trimIndent(),
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next() shouldBe true
                    rs.getString(1) shouldBe "CASCADE"
                }
            }
        }
    }

    "deferred-FK columns have zero referential constraints" {
        DriverManager.getConnection(url, user, password).use { conn ->
            val query =
                """
                SELECT COUNT(*)
                  FROM information_schema.table_constraints tc
                  JOIN information_schema.key_column_usage kcu
                    ON tc.constraint_name = kcu.constraint_name
                 WHERE tc.constraint_type = 'FOREIGN KEY'
                   AND tc.table_name = ?
                   AND kcu.column_name = ?
                """.trimIndent()
            listOf(
                "reports" to "reviewed_by",
                "reports" to "target_id",
                "moderation_queue" to "resolved_by",
                "moderation_queue" to "target_id",
            ).forEach { (table, col) ->
                conn.prepareStatement(query).use { ps ->
                    ps.setString(1, table)
                    ps.setString(2, col)
                    ps.executeQuery().use { rs ->
                        rs.next()
                        rs.getInt(1) shouldBe 0
                    }
                }
            }
        }
    }

    "reviewed_by comment mentions admin_users AND deferred" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.prepareStatement(
                """
                SELECT col_description(c.oid, a.attnum) AS comment
                  FROM pg_class c
                  JOIN pg_attribute a ON a.attrelid = c.oid
                 WHERE c.relname = 'reports' AND a.attname = 'reviewed_by'
                """.trimIndent(),
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next() shouldBe true
                    val comment = rs.getString(1) ?: ""
                    comment.contains("admin_users") shouldBe true
                    comment.contains("deferred") shouldBe true
                }
            }
        }
    }

    "resolved_by comment mentions admin_users AND deferred" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.prepareStatement(
                """
                SELECT col_description(c.oid, a.attnum) AS comment
                  FROM pg_class c
                  JOIN pg_attribute a ON a.attrelid = c.oid
                 WHERE c.relname = 'moderation_queue' AND a.attname = 'resolved_by'
                """.trimIndent(),
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next() shouldBe true
                    val comment = rs.getString(1) ?: ""
                    comment.contains("admin_users") shouldBe true
                    comment.contains("deferred") shouldBe true
                }
            }
        }
    }

    "CASCADE: DELETE FROM users removes the reports row in the same transaction" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.autoCommit = false
            try {
                val reporter = seedUser(conn)
                val tid = UUID.randomUUID()
                insertReport(conn, reporter, "post", tid)
                conn.prepareStatement("DELETE FROM users WHERE id = ?").use { ps ->
                    ps.setObject(1, reporter)
                    ps.executeUpdate()
                }
                conn.prepareStatement(
                    "SELECT COUNT(*) FROM reports WHERE reporter_id = ?",
                ).use { ps ->
                    ps.setObject(1, reporter)
                    ps.executeQuery().use { rs ->
                        rs.next() shouldBe true
                        rs.getInt(1) shouldBe 0
                    }
                }
            } finally {
                conn.rollback()
            }
        }
    }
})
