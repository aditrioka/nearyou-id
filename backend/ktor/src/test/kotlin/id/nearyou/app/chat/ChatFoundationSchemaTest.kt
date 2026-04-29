package id.nearyou.app.chat

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.postgresql.util.PGobject
import java.sql.Connection
import java.sql.Date
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Database-dependent CHECK + partial-unique enforcement tests for the V15
 * `chat-foundation` schema. Covers tasks 5.1–5.7 of `openspec/changes/chat-foundation`.
 *
 * Tagged `database` so CI excludes it by default. Run locally with the standard
 * `DB_URL` / `DB_USER` / `DB_PASSWORD` env vars (defaults match the Docker
 * Compose dev Postgres at `localhost:5433`).
 */
@Tags("database")
class ChatFoundationSchemaTest : StringSpec({

    val url = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5433/nearyou_dev"
    val dbUser = System.getenv("DB_USER") ?: "postgres"
    val password = System.getenv("DB_PASSWORD") ?: "postgres"
    val dataSource: HikariDataSource =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = url
                username = dbUser
                this.password = password
                maximumPoolSize = 2
                initializationFailTimeout = -1
            },
        )

    afterSpec { dataSource.close() }

    fun seedUser(): UUID {
        val uid = UUID.randomUUID()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO users (id, username, display_name, date_of_birth, invite_code_prefix)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, uid)
                val short = uid.toString().replace("-", "").take(8)
                ps.setString(2, "cs_$short")
                ps.setString(3, "Chat Schema Tester")
                ps.setDate(4, Date.valueOf(LocalDate.of(1995, 3, 14)))
                ps.setString(5, "c${short.take(7)}")
                ps.executeUpdate()
            }
        }
        return uid
    }

    fun insertConversation(
        conn: Connection,
        createdBy: UUID,
    ): UUID =
        conn.prepareStatement(
            "INSERT INTO conversations (created_by) VALUES (?) RETURNING id",
        ).use { ps ->
            ps.setObject(1, createdBy)
            ps.executeQuery().use { rs ->
                check(rs.next())
                rs.getObject(1, UUID::class.java)
            }
        }

    fun insertParticipant(
        conn: Connection,
        conversationId: UUID,
        userId: UUID,
        slot: Int,
        leftAt: Instant? = null,
    ) {
        conn.prepareStatement(
            "INSERT INTO conversation_participants (conversation_id, user_id, slot, left_at) VALUES (?, ?, ?, ?)",
        ).use { ps ->
            ps.setObject(1, conversationId)
            ps.setObject(2, userId)
            ps.setInt(3, slot)
            ps.setTimestamp(4, leftAt?.let { Timestamp.from(it) })
            ps.executeUpdate()
        }
    }

    fun cleanupConversation(conversationId: UUID) {
        dataSource.connection.use { conn ->
            // CASCADE deletes participants + messages.
            conn.prepareStatement("DELETE FROM conversations WHERE id = ?").use { ps ->
                ps.setObject(1, conversationId)
                ps.executeUpdate()
            }
        }
    }

    fun cleanupUsers(vararg userIds: UUID) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("DELETE FROM users WHERE id = ANY (?)").use { ps ->
                ps.setArray(1, conn.createArrayOf("uuid", userIds.map { it }.toTypedArray()))
                ps.executeUpdate()
            }
        }
    }

    "5.1: empty-message CHECK rejects fully-empty INSERT" {
        val u1 = seedUser()
        val u2 = seedUser()
        var convId: UUID? = null
        try {
            dataSource.connection.use { conn ->
                convId = insertConversation(conn, u1)
                insertParticipant(conn, convId!!, u1, slot = 1)
                insertParticipant(conn, convId!!, u2, slot = 2)
            }
            val ex =
                shouldThrow<SQLException> {
                    dataSource.connection.use { conn ->
                        conn.prepareStatement(
                            """
                            INSERT INTO chat_messages
                                (conversation_id, sender_id, content, embedded_post_id, embedded_post_snapshot)
                            VALUES (?, ?, NULL, NULL, NULL)
                            """.trimIndent(),
                        ).use { ps ->
                            ps.setObject(1, convId)
                            ps.setObject(2, u1)
                            ps.executeUpdate()
                        }
                    }
                }
            ex.sqlState shouldBe "23514"
        } finally {
            convId?.let { cleanupConversation(it) }
            cleanupUsers(u1, u2)
        }
    }

    "5.2: empty-message CHECK accepts snapshot-only INSERT" {
        val u1 = seedUser()
        val u2 = seedUser()
        var convId: UUID? = null
        try {
            dataSource.connection.use { conn ->
                convId = insertConversation(conn, u1)
                insertParticipant(conn, convId!!, u1, slot = 1)
                insertParticipant(conn, convId!!, u2, slot = 2)

                val snapshot =
                    PGobject().apply {
                        type = "jsonb"
                        value = "{\"post_id\":\"deadbeef\",\"content\":\"hi\"}"
                    }
                conn.prepareStatement(
                    """
                    INSERT INTO chat_messages
                        (conversation_id, sender_id, content, embedded_post_id, embedded_post_snapshot)
                    VALUES (?, ?, NULL, NULL, ?)
                    """.trimIndent(),
                ).use { ps ->
                    ps.setObject(1, convId)
                    ps.setObject(2, u1)
                    ps.setObject(3, snapshot)
                    ps.executeUpdate() shouldBe 1
                }
                conn.prepareStatement(
                    "SELECT COUNT(*) FROM chat_messages WHERE conversation_id = ? AND embedded_post_snapshot IS NOT NULL",
                ).use { ps ->
                    ps.setObject(1, convId)
                    ps.executeQuery().use { rs ->
                        rs.next() shouldBe true
                        rs.getInt(1) shouldBe 1
                    }
                }
            }
        } finally {
            convId?.let { cleanupConversation(it) }
            cleanupUsers(u1, u2)
        }
    }

    "5.3: redaction atomicity CHECK rejects half-set state (redacted_at set, redacted_by NULL)" {
        val u1 = seedUser()
        val u2 = seedUser()
        var convId: UUID? = null
        try {
            val msgId: UUID
            dataSource.connection.use { conn ->
                convId = insertConversation(conn, u1)
                insertParticipant(conn, convId!!, u1, slot = 1)
                insertParticipant(conn, convId!!, u2, slot = 2)
                msgId =
                    conn.prepareStatement(
                        "INSERT INTO chat_messages (conversation_id, sender_id, content) VALUES (?, ?, ?) RETURNING id",
                    ).use { ps ->
                        ps.setObject(1, convId)
                        ps.setObject(2, u1)
                        ps.setString(3, "halo")
                        ps.executeQuery().use { rs ->
                            check(rs.next())
                            rs.getObject(1, UUID::class.java)
                        }
                    }
            }
            val ex =
                shouldThrow<SQLException> {
                    dataSource.connection.use { conn ->
                        conn.prepareStatement(
                            "UPDATE chat_messages SET redacted_at = NOW(), redacted_by = NULL, redaction_reason = NULL WHERE id = ?",
                        ).use { ps ->
                            ps.setObject(1, msgId)
                            ps.executeUpdate()
                        }
                    }
                }
            ex.sqlState shouldBe "23514"
        } finally {
            convId?.let { cleanupConversation(it) }
            cleanupUsers(u1, u2)
        }
    }

    "5.4: redaction atomicity CHECK accepts redacted_at + redacted_by + NULL reason" {
        val u1 = seedUser()
        val u2 = seedUser()
        // Borrow u2 as the surrogate "admin" id since `redacted_by` ships without an FK in V15.
        var convId: UUID? = null
        try {
            val msgId: UUID
            dataSource.connection.use { conn ->
                convId = insertConversation(conn, u1)
                insertParticipant(conn, convId!!, u1, slot = 1)
                insertParticipant(conn, convId!!, u2, slot = 2)
                msgId =
                    conn.prepareStatement(
                        "INSERT INTO chat_messages (conversation_id, sender_id, content) VALUES (?, ?, ?) RETURNING id",
                    ).use { ps ->
                        ps.setObject(1, convId)
                        ps.setObject(2, u1)
                        ps.setString(3, "halo")
                        ps.executeQuery().use { rs ->
                            check(rs.next())
                            rs.getObject(1, UUID::class.java)
                        }
                    }
                val updated =
                    conn.prepareStatement(
                        "UPDATE chat_messages SET redacted_at = NOW(), redacted_by = ?, redaction_reason = NULL WHERE id = ?",
                    ).use { ps ->
                        ps.setObject(1, u2)
                        ps.setObject(2, msgId)
                        ps.executeUpdate()
                    }
                updated shouldBe 1
                conn.prepareStatement(
                    "SELECT redacted_at, redacted_by, redaction_reason FROM chat_messages WHERE id = ?",
                ).use { ps ->
                    ps.setObject(1, msgId)
                    ps.executeQuery().use { rs ->
                        rs.next() shouldBe true
                        (rs.getTimestamp(1) != null) shouldBe true
                        rs.getObject(2, UUID::class.java) shouldBe u2
                        rs.getString(3) shouldBe null
                    }
                }
            }
        } finally {
            convId?.let { cleanupConversation(it) }
            cleanupUsers(u1, u2)
        }
    }

    "5.5: slot CHECK rejects slot=0, slot=3, NULL rejected by NOT NULL" {
        val u1 = seedUser()
        val u2 = seedUser()
        val u3 = seedUser()
        var convId: UUID? = null
        try {
            dataSource.connection.use { conn -> convId = insertConversation(conn, u1) }

            // slot = 0 => CHECK violation (sqlState 23514)
            shouldThrow<SQLException> {
                dataSource.connection.use { conn ->
                    insertParticipant(conn, convId!!, u1, slot = 0)
                }
            }.sqlState shouldBe "23514"

            // slot = 3 => CHECK violation
            shouldThrow<SQLException> {
                dataSource.connection.use { conn ->
                    insertParticipant(conn, convId!!, u2, slot = 3)
                }
            }.sqlState shouldBe "23514"

            // slot = NULL => NOT NULL violation (sqlState 23502)
            val nullEx =
                shouldThrow<SQLException> {
                    dataSource.connection.use { conn ->
                        conn.prepareStatement(
                            "INSERT INTO conversation_participants (conversation_id, user_id, slot) VALUES (?, ?, NULL)",
                        ).use { ps ->
                            ps.setObject(1, convId)
                            ps.setObject(2, u3)
                            ps.executeUpdate()
                        }
                    }
                }
            nullEx.sqlState shouldBe "23502"
        } finally {
            convId?.let { cleanupConversation(it) }
            cleanupUsers(u1, u2, u3)
        }
    }

    "5.6: conv_slot_unique blocks a third active row with slot=1" {
        val u1 = seedUser()
        val u2 = seedUser()
        val u3 = seedUser()
        var convId: UUID? = null
        try {
            dataSource.connection.use { conn ->
                convId = insertConversation(conn, u1)
                insertParticipant(conn, convId!!, u1, slot = 1)
                insertParticipant(conn, convId!!, u2, slot = 2)
            }
            // Third active participant with slot=1 (a different user_id so PK doesn't trip first).
            val ex =
                shouldThrow<SQLException> {
                    dataSource.connection.use { conn ->
                        insertParticipant(conn, convId!!, u3, slot = 1)
                    }
                }
            // 23505 = unique_violation
            ex.sqlState shouldBe "23505"
        } finally {
            convId?.let { cleanupConversation(it) }
            cleanupUsers(u1, u2, u3)
        }
    }

    "5.7: slot=1 left_at IS NOT NULL + slot=1 left_at IS NULL coexist (partial unique)" {
        val u1 = seedUser()
        val u2 = seedUser()
        var convId: UUID? = null
        try {
            dataSource.connection.use { conn ->
                convId = insertConversation(conn, u1)
                // Historical row: user u1 in slot 1, but has left.
                insertParticipant(
                    conn,
                    convId!!,
                    u1,
                    slot = 1,
                    leftAt = Instant.now().minusSeconds(60),
                )
                // Active row: u2 takes slot 1 (no other active slot=1 row exists).
                insertParticipant(conn, convId!!, u2, slot = 1)
                conn.prepareStatement(
                    """
                    SELECT
                        SUM(CASE WHEN left_at IS NULL THEN 1 ELSE 0 END) AS active,
                        SUM(CASE WHEN left_at IS NOT NULL THEN 1 ELSE 0 END) AS historical
                      FROM conversation_participants
                     WHERE conversation_id = ? AND slot = 1
                    """.trimIndent(),
                ).use { ps ->
                    ps.setObject(1, convId)
                    ps.executeQuery().use { rs ->
                        rs.next() shouldBe true
                        rs.getInt("active") shouldBe 1
                        rs.getInt("historical") shouldBe 1
                    }
                }
            }
        } finally {
            convId?.let { cleanupConversation(it) }
            cleanupUsers(u1, u2)
        }
    }
})
