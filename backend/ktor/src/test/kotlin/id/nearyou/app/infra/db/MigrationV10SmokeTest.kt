package id.nearyou.app.infra.db

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.postgresql.util.PGobject
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.time.LocalDate
import java.util.UUID

/**
 * Database-dependent smoke test for V10 (`notifications`). Asserts the
 * in-app-notifications schema invariants from the `in-app-notifications` +
 * `users-schema` + `migration-pipeline` spec deltas:
 *  - V10 lands cleanly (cold + from-V9) and is idempotent on a second run.
 *  - Table exists with the canonical 9-column shape.
 *  - `type` CHECK accepts all 13 documented enum values and rejects unknowns.
 *  - Both indexes exist with correct columns + ordering + partial predicate.
 *  - `user_id` FK has `delete_rule = 'CASCADE'`.
 *  - `actor_user_id` FK has `delete_rule = 'SET NULL'`.
 *  - CASCADE + SET NULL actually behave as declared.
 *  - `flyway_schema_history` contains versions 1..10.
 */
@Tags("database")
class MigrationV10SmokeTest : StringSpec({

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
        shortPrefix: String = "v10",
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
            ps.setString(3, "V10 Smoke")
            ps.setDate(4, java.sql.Date.valueOf(LocalDate.of(1990, 1, 1)))
            ps.setString(5, "v${short.take(7)}")
            ps.executeUpdate()
        }
        return id
    }

    fun insertNotification(
        conn: Connection,
        userId: UUID,
        type: String,
        actorId: UUID? = null,
    ): UUID {
        val bodyData =
            PGobject().apply {
                this.type = "jsonb"
                this.value = "{}"
            }
        conn.prepareStatement(
            """
            INSERT INTO notifications (user_id, type, actor_user_id, body_data)
            VALUES (?, ?, ?, ?)
            RETURNING id
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, userId)
            ps.setString(2, type)
            if (actorId != null) ps.setObject(3, actorId) else ps.setNull(3, java.sql.Types.OTHER)
            ps.setObject(4, bodyData)
            ps.executeQuery().use { rs ->
                rs.next()
                return rs.getObject(1, UUID::class.java)
            }
        }
    }

    "V10 present in flyway_schema_history with success" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(
                    "SELECT success FROM flyway_schema_history WHERE version = '10'",
                ).use { rs ->
                    rs.next() shouldBe true
                    rs.getBoolean(1) shouldBe true
                }
            }
        }
    }

    "flyway_schema_history contains versions 1..10 all successful" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(
                    "SELECT version FROM flyway_schema_history WHERE success = TRUE",
                ).use { rs ->
                    val versions = mutableSetOf<String>()
                    while (rs.next()) versions += rs.getString(1)
                    (1..10).forEach { v -> versions.contains(v.toString()) shouldBe true }
                }
            }
        }
    }

    "re-running flywayMigrate against a DB already at V10 is a no-op" {
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

    "notifications has every canonical column" {
        val expected =
            setOf(
                "id", "user_id", "type", "actor_user_id", "target_type",
                "target_id", "body_data", "created_at", "read_at",
            )
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(
                    "SELECT column_name FROM information_schema.columns WHERE table_name = 'notifications'",
                ).use { rs ->
                    val present = mutableSetOf<String>()
                    while (rs.next()) present += rs.getString(1)
                    present.containsAll(expected) shouldBe true
                }
            }
        }
    }

    "notifications.type CHECK rejects out-of-enum value" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.autoCommit = false
            try {
                val u = seedUser(conn)
                var sqlState: String? = null
                val sp = conn.setSavepoint()
                try {
                    insertNotification(conn, u, "bogus_type")
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

    "notifications.type CHECK accepts all 13 enum values" {
        val allTypes =
            listOf(
                "post_liked",
                "post_replied",
                "followed",
                "post_auto_hidden",
                "chat_message",
                "chat_message_redacted",
                "subscription_purchased",
                "subscription_expiring",
                "subscription_expired",
                "account_action_applied",
                "data_export_ready",
                "privacy_flip_warning",
                "username_release_scheduled",
                "apple_relay_email_changed",
            )
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.autoCommit = false
            try {
                val u = seedUser(conn)
                allTypes.forEach { t -> insertNotification(conn, u, t) }
            } finally {
                conn.rollback()
            }
        }
    }

    "notifications_user_unread_idx is a partial index with read_at IS NULL predicate" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.prepareStatement(
                """
                SELECT pg_get_indexdef(ix.indexrelid) AS def,
                       ix.indpred IS NOT NULL AS is_partial
                  FROM pg_index ix
                  JOIN pg_class i ON ix.indexrelid = i.oid
                 WHERE i.relname = 'notifications_user_unread_idx'
                """.trimIndent(),
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next() shouldBe true
                    val def = rs.getString("def")
                    rs.getBoolean("is_partial") shouldBe true
                    def.contains("(user_id, created_at DESC)") shouldBe true
                    def.contains("read_at IS NULL") shouldBe true
                }
            }
        }
    }

    "notifications_user_all_idx covers (user_id, created_at DESC)" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.prepareStatement(
                """
                SELECT pg_get_indexdef(ix.indexrelid) AS def,
                       ix.indpred IS NOT NULL AS is_partial
                  FROM pg_index ix
                  JOIN pg_class i ON ix.indexrelid = i.oid
                 WHERE i.relname = 'notifications_user_all_idx'
                """.trimIndent(),
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next() shouldBe true
                    val def = rs.getString("def")
                    rs.getBoolean("is_partial") shouldBe false
                    def.contains("(user_id, created_at DESC)") shouldBe true
                }
            }
        }
    }

    "both indexes exist on notifications" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(
                    """
                    SELECT i.relname
                      FROM pg_class i
                      JOIN pg_index ix ON ix.indexrelid = i.oid
                      JOIN pg_class t  ON ix.indrelid = t.oid
                     WHERE t.relname = 'notifications'
                    """.trimIndent(),
                ).use { rs ->
                    val names = mutableSetOf<String>()
                    while (rs.next()) names += rs.getString(1)
                    names shouldContainAll setOf("notifications_user_unread_idx", "notifications_user_all_idx")
                }
            }
        }
    }

    "notifications.user_id FK delete rule is CASCADE" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.prepareStatement(
                """
                SELECT rc.delete_rule
                  FROM information_schema.table_constraints tc
                  JOIN information_schema.key_column_usage kcu
                    ON tc.constraint_name = kcu.constraint_name
                  JOIN information_schema.referential_constraints rc
                    ON tc.constraint_name = rc.constraint_name
                 WHERE tc.table_name = 'notifications'
                   AND tc.constraint_type = 'FOREIGN KEY'
                   AND kcu.column_name = 'user_id'
                """.trimIndent(),
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next() shouldBe true
                    rs.getString(1) shouldBe "CASCADE"
                }
            }
        }
    }

    "notifications.actor_user_id FK delete rule is SET NULL" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.prepareStatement(
                """
                SELECT rc.delete_rule
                  FROM information_schema.table_constraints tc
                  JOIN information_schema.key_column_usage kcu
                    ON tc.constraint_name = kcu.constraint_name
                  JOIN information_schema.referential_constraints rc
                    ON tc.constraint_name = rc.constraint_name
                 WHERE tc.table_name = 'notifications'
                   AND tc.constraint_type = 'FOREIGN KEY'
                   AND kcu.column_name = 'actor_user_id'
                """.trimIndent(),
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next() shouldBe true
                    rs.getString(1) shouldBe "SET NULL"
                }
            }
        }
    }

    "CASCADE: DELETE FROM users removes the recipient's notifications" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.autoCommit = false
            try {
                val recipient = seedUser(conn)
                insertNotification(conn, recipient, "followed")
                conn.prepareStatement("DELETE FROM users WHERE id = ?").use { ps ->
                    ps.setObject(1, recipient)
                    ps.executeUpdate()
                }
                conn.prepareStatement("SELECT COUNT(*) FROM notifications WHERE user_id = ?").use { ps ->
                    ps.setObject(1, recipient)
                    ps.executeQuery().use { rs ->
                        rs.next()
                        rs.getInt(1) shouldBe 0
                    }
                }
            } finally {
                conn.rollback()
            }
        }
    }

    "SET NULL: DELETE FROM users nullifies actor_user_id on existing notifications" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.autoCommit = false
            try {
                val recipient = seedUser(conn, shortPrefix = "v10r")
                val actor = seedUser(conn, shortPrefix = "v10a")
                val notificationId = insertNotification(conn, recipient, "post_liked", actorId = actor)
                conn.prepareStatement("DELETE FROM users WHERE id = ?").use { ps ->
                    ps.setObject(1, actor)
                    ps.executeUpdate()
                }
                conn.prepareStatement(
                    "SELECT actor_user_id FROM notifications WHERE id = ?",
                ).use { ps ->
                    ps.setObject(1, notificationId)
                    ps.executeQuery().use { rs ->
                        rs.next() shouldBe true
                        rs.getObject(1, UUID::class.java) shouldBe null
                    }
                }
            } finally {
                conn.rollback()
            }
        }
    }
})
