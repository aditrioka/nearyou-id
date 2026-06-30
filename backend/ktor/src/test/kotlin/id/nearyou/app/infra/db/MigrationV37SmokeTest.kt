package id.nearyou.app.infra.db

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.flywaydb.core.Flyway
import org.postgresql.util.PSQLException
import java.sql.Connection
import java.sql.Date
import java.time.LocalDate
import java.util.UUID

/**
 * Database-dependent smoke test for V37 (`V37__chat_embedded_post_edit_fk_and_snapshot_size.sql`,
 * `chat-embedded-posts`). Boots Flyway against the running dev Postgres (or the CI service
 * container) and asserts the two deferred schema pieces this change ships:
 *
 *   - V37 present in `flyway_schema_history` with success.
 *   - FK backfill `chat_messages_embedded_post_edit_id_fkey` exists, is validated
 *     (convalidated=true), references `post_edits`, and is ON DELETE SET NULL (confdeltype='n').
 *   - Comment-replacement: `embedded_post_edit_id`'s pg_description mentions `post_edits(id)` +
 *     `SET NULL` and no longer describes the FK as deferred (the chat-conversations MODIFIED
 *     delta + tasks 5.7 pg_description assertion).
 *   - Snapshot size CHECK rejects an oversized (`octet_length(::text) >= 4096`) snapshot INSERT
 *     and accepts a NULL snapshot (the `IS NULL OR …` guard).
 *
 * Tagged `database` so CI's `!network` lane excludes it by default. Run locally with:
 *   `./gradlew :backend:ktor:test -Dkotest.tags=database`
 */
@Tags("database")
class MigrationV37SmokeTest : StringSpec({

    val url = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5433/nearyou_dev"
    val user = System.getenv("DB_USER") ?: "postgres"
    val password = System.getenv("DB_PASSWORD") ?: "postgres"

    Flyway
        .configure()
        .dataSource(url, user, password)
        .locations("classpath:db/migration")
        .load()
        .migrate()

    fun connect(): Connection = java.sql.DriverManager.getConnection(url, user, password)

    fun seedUser(conn: Connection): UUID {
        val id = UUID.randomUUID()
        val short = id.toString().replace("-", "").take(8)
        conn.prepareStatement(
            """
            INSERT INTO users (id, username, display_name, date_of_birth, invite_code_prefix)
            VALUES (?, ?, 'V37 Tester', ?, ?)
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, id)
            ps.setString(2, "v37_$short")
            ps.setDate(3, Date.valueOf(LocalDate.of(1990, 1, 1)))
            ps.setString(4, "v${short.take(7)}")
            ps.executeUpdate()
        }
        return id
    }

    fun seedConversation(
        conn: Connection,
        createdBy: UUID,
    ): UUID {
        val id = UUID.randomUUID()
        conn.prepareStatement("INSERT INTO conversations (id, created_by) VALUES (?, ?)").use { ps ->
            ps.setObject(1, id)
            ps.setObject(2, createdBy)
            ps.executeUpdate()
        }
        return id
    }

    fun cleanup(
        conn: Connection,
        userId: UUID,
    ) {
        conn.createStatement().use { st ->
            st.executeUpdate("DELETE FROM conversations WHERE created_by = '$userId'")
            st.executeUpdate("DELETE FROM users WHERE id = '$userId'")
        }
    }

    "V37 — recorded in flyway_schema_history with success" {
        connect().use { conn ->
            conn.prepareStatement(
                "SELECT success FROM flyway_schema_history WHERE version = '37'",
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next() shouldBe true
                    rs.getBoolean("success") shouldBe true
                }
            }
        }
    }

    "FK backfill — chat_messages.embedded_post_edit_id references post_edits, validated, ON DELETE SET NULL" {
        connect().use { conn ->
            conn.prepareStatement(
                """
                SELECT confdeltype, convalidated, confrelid::regclass::text AS target
                  FROM pg_constraint
                 WHERE contype = 'f' AND conname = 'chat_messages_embedded_post_edit_id_fkey'
                """.trimIndent(),
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next() shouldBe true
                    rs.getString("confdeltype") shouldBe "n"
                    rs.getBoolean("convalidated") shouldBe true
                    rs.getString("target") shouldBe "post_edits"
                }
            }
        }
    }

    "Comment replacement — embedded_post_edit_id mentions post_edits(id) + SET NULL, no longer deferred" {
        connect().use { conn ->
            conn.prepareStatement(
                """
                SELECT col_description(c.oid, a.attnum)
                  FROM pg_class c JOIN pg_attribute a ON a.attrelid = c.oid
                 WHERE c.relname = 'chat_messages' AND a.attname = 'embedded_post_edit_id'
                """.trimIndent(),
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next() shouldBe true
                    val comment = rs.getString(1)
                    comment shouldNotBe null
                    comment shouldContain "post_edits(id)"
                    comment shouldContain "SET NULL"
                    comment.lowercase().contains("deferred") shouldBe false
                }
            }
        }
    }

    "Snapshot size CHECK — rejects an oversized (>= 4096 octets) snapshot INSERT" {
        connect().use { conn ->
            val userId = seedUser(conn)
            try {
                val convId = seedConversation(conn, userId)
                val oversized = """{"x":"${"a".repeat(4096)}"}""" // octet_length(::text) >> 4096
                val ex =
                    shouldThrow<PSQLException> {
                        conn.prepareStatement(
                            "INSERT INTO chat_messages (conversation_id, sender_id, embedded_post_snapshot) " +
                                "VALUES (?, ?, ?::jsonb)",
                        ).use { ps ->
                            ps.setObject(1, convId)
                            ps.setObject(2, userId)
                            ps.setString(3, oversized)
                            ps.executeUpdate()
                        }
                    }
                ex.message!! shouldContain "chat_messages_embedded_snapshot_size_check"
            } finally {
                cleanup(conn, userId)
            }
        }
    }

    "Snapshot size CHECK — NULL snapshot passes (the IS NULL OR guard short-circuits)" {
        connect().use { conn ->
            val userId = seedUser(conn)
            try {
                val convId = seedConversation(conn, userId)
                // A plain content message with embedded_post_snapshot NULL must insert cleanly.
                conn.prepareStatement(
                    "INSERT INTO chat_messages (conversation_id, sender_id, content) VALUES (?, ?, 'halo')",
                ).use { ps ->
                    ps.setObject(1, convId)
                    ps.setObject(2, userId)
                    ps.executeUpdate() shouldBe 1
                }
            } finally {
                cleanup(conn, userId)
            }
        }
    }
})
