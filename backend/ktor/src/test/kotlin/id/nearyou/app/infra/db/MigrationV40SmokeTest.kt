package id.nearyou.app.infra.db

import id.nearyou.app.admin.auth.AdminAuthTestSupport
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.postgresql.util.PSQLException
import java.sql.Connection
import java.time.LocalDate
import java.util.UUID

/**
 * Database-dependent smoke test for V40 (`V40__notifications_type_appeal_decided.sql`),
 * asserting the `in-app-notifications` § "notifications.type enum extended with
 * `appeal_decided` (V40)" delta from `appeal-decision-notification`:
 *
 *   - V40 present in `flyway_schema_history` with success.
 *   - `type = 'appeal_decided'` INSERT succeeds; all FOURTEEN values are accepted.
 *   - An out-of-enum type (`post_shared`) is still rejected (23514).
 *   - Pre-V40 rows with all 13 original values survive re-applying the V40 DDL.
 *   - Exactly ONE CHECK constraint on `notifications` references `type` (the swap
 *     replaced the V10 constraint rather than adding a second, stricter one).
 *   - The SQL is additive (no DELETE / UPDATE / DROP TABLE).
 *
 * The migration set is booted once per JVM by `KotestProjectConfig`; this spec only
 * reads + writes inside rolled-back transactions on the shared test pool (`autoClose`).
 */
@Tags("database")
class MigrationV40SmokeTest : StringSpec({

    val dataSource = autoClose(AdminAuthTestSupport.hikari())

    val originalThirteen =
        listOf(
            "post_liked",
            "post_replied",
            "followed",
            "chat_message",
            "subscription_billing_issue",
            "subscription_expired",
            "post_auto_hidden",
            "account_action_applied",
            "data_export_ready",
            "chat_message_redacted",
            "privacy_flip_warning",
            "username_release_scheduled",
            "apple_relay_email_changed",
        )
    val allFourteen = originalThirteen + "appeal_decided"

    val migrationSql =
        MigrationV40SmokeTest::class.java.classLoader
            .getResourceAsStream("db/migration/V40__notifications_type_appeal_decided.sql")!!
            .bufferedReader()
            .use { it.readText() }

    fun seedUser(conn: Connection): UUID {
        val id = UUID.randomUUID()
        val short = id.toString().replace("-", "").take(8)
        conn.prepareStatement(
            "INSERT INTO users (id, username, display_name, date_of_birth, invite_code_prefix) VALUES (?, ?, ?, ?, ?)",
        ).use { ps ->
            ps.setObject(1, id)
            ps.setString(2, "v40_$short")
            ps.setString(3, "V40 Smoke")
            ps.setDate(4, java.sql.Date.valueOf(LocalDate.of(1990, 1, 1)))
            ps.setString(5, "w${short.take(7)}")
            ps.executeUpdate()
        }
        return id
    }

    fun insertNotification(
        conn: Connection,
        userId: UUID,
        type: String,
    ) {
        conn.prepareStatement("INSERT INTO notifications (user_id, type, body_data) VALUES (?, ?, '{}'::jsonb)").use { ps ->
            ps.setObject(1, userId)
            ps.setString(2, type)
            ps.executeUpdate()
        }
    }

    fun countForUser(
        conn: Connection,
        userId: UUID,
    ): Int =
        conn.prepareStatement("SELECT COUNT(*) FROM notifications WHERE user_id = ?").use { ps ->
            ps.setObject(1, userId)
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }

    /** Runs [block] in a transaction that is always rolled back (nothing persists). */
    fun <T> inRolledBackTx(block: (Connection) -> T): T =
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                block(conn)
            } finally {
                conn.rollback()
                conn.autoCommit = true
            }
        }

    "V40 present in flyway_schema_history with success" {
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT success FROM flyway_schema_history WHERE version = '40'").use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next() shouldBe true
                    rs.getBoolean(1) shouldBe true
                }
            }
        }
    }

    "appeal_decided INSERT succeeds after V40" {
        inRolledBackTx { conn ->
            val u = seedUser(conn)
            insertNotification(conn, u, "appeal_decided")
            countForUser(conn, u) shouldBe 1
        }
    }

    "all fourteen type values are accepted after V40" {
        inRolledBackTx { conn ->
            val u = seedUser(conn)
            allFourteen.forEach { insertNotification(conn, u, it) }
            countForUser(conn, u) shouldBe allFourteen.size
        }
    }

    "out-of-enum type still rejected after V40 (only appeal_decided was added)" {
        inRolledBackTx { conn ->
            val u = seedUser(conn)
            val e = runCatching { insertNotification(conn, u, "post_shared") }.exceptionOrNull()
            (e as? PSQLException)?.sqlState shouldBe "23514"
        }
    }

    "pre-V40 original-value rows survive re-applying the V40 constraint swap" {
        // Seed all 13 original values, THEN re-run the exact V40 DDL in the same tx. Postgres validates
        // every existing row at ADD CONSTRAINT time, so a rejected original value would throw 23514 here.
        inRolledBackTx { conn ->
            val u = seedUser(conn)
            originalThirteen.forEach { insertNotification(conn, u, it) }
            conn.createStatement().use { it.execute(migrationSql) }
            countForUser(conn, u) shouldBe originalThirteen.size
        }
    }

    "exactly one CHECK constraint on notifications references type, and it allows appeal_decided" {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT conname, pg_get_constraintdef(oid) AS def
                  FROM pg_constraint
                 WHERE conrelid = 'notifications'::regclass
                   AND contype = 'c'
                   AND pg_get_constraintdef(oid) ~ '\mtype\M'
                """.trimIndent(),
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    val defs = buildMap { while (rs.next()) put(rs.getString("conname"), rs.getString("def")) }
                    defs.keys shouldBe setOf("notifications_type_check")
                    defs.getValue("notifications_type_check") shouldContain "appeal_decided"
                }
            }
        }
    }

    "V40 SQL file is additive — no DELETE/UPDATE/DROP TABLE (no data rewrite)" {
        val stripped =
            migrationSql.lineSequence()
                .map { it.replace(Regex("--.*$"), "") }
                .joinToString("\n")
        Regex("(?im)^\\s*DELETE\\b").containsMatchIn(stripped) shouldBe false
        Regex("(?im)^\\s*UPDATE\\b").containsMatchIn(stripped) shouldBe false
        Regex("(?im)\\bDROP\\s+TABLE\\b").containsMatchIn(stripped) shouldBe false
    }
})
