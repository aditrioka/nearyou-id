package id.nearyou.app.appeal

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.sql.Date
import java.time.LocalDate
import java.util.UUID

private fun hikari(): HikariDataSource {
    val url = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5433/nearyou_dev"
    val config =
        HikariConfig().apply {
            jdbcUrl = url
            username = System.getenv("DB_USER") ?: "postgres"
            password = System.getenv("DB_PASSWORD") ?: "postgres"
            maximumPoolSize = 2
            initializationFailTimeout = -1
        }
    return HikariDataSource(config)
}

/**
 * V34 `appeals` schema invariants (content-moderation-appeal). Tagged `database` —
 * requires dev Postgres + V34 applied. Per-test `try/finally` cleanup (NOT
 * afterTest) so `autoClose` is safe: the pool outlives every cleanup.
 */
@Tags("database")
class AppealsSchemaTest : StringSpec({

    val dataSource = autoClose(hikari())

    fun seedUser(): UUID {
        val id = UUID.randomUUID()
        val short = id.toString().replace("-", "").take(8)
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "INSERT INTO users (id, username, display_name, date_of_birth, invite_code_prefix) VALUES (?, ?, ?, ?, ?)",
            ).use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "apsch_$short")
                ps.setString(3, "Appeal Schema")
                ps.setDate(4, Date.valueOf(LocalDate.of(1990, 1, 1)))
                ps.setString(5, "s${short.take(7)}")
                ps.executeUpdate()
            }
        }
        return id
    }

    fun insertAppeal(
        userId: UUID,
        actionType: String = "suspension",
        text: String = "Please reconsider, this was a mistake.",
    ) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "INSERT INTO appeals (user_id, action_type, appeal_text) VALUES (?, ?, ?)",
            ).use { ps ->
                ps.setObject(1, userId)
                ps.setString(2, actionType)
                ps.setString(3, text)
                ps.executeUpdate()
            }
        }
    }

    fun cleanup(vararg ids: UUID) {
        dataSource.connection.use { conn ->
            ids.forEach { id ->
                conn.prepareStatement("DELETE FROM appeals WHERE user_id = ?").use {
                    it.setObject(1, id)
                    it.executeUpdate()
                }
                conn.prepareStatement("DELETE FROM users WHERE id = ?").use {
                    it.setObject(1, id)
                    it.executeUpdate()
                }
            }
        }
    }

    "valid appeal row persists with default status=pending and a populated created_at" {
        val uid = seedUser()
        try {
            insertAppeal(uid, actionType = "suspension")
            dataSource.connection.use { conn ->
                conn.prepareStatement("SELECT status, created_at FROM appeals WHERE user_id = ?").use { ps ->
                    ps.setObject(1, uid)
                    ps.executeQuery().use { rs ->
                        rs.next() shouldBe true
                        rs.getString("status") shouldBe "pending"
                        (rs.getTimestamp("created_at") != null) shouldBe true
                    }
                }
            }
        } finally {
            cleanup(uid)
        }
    }

    "appeal_text longer than 1000 chars is rejected by the CHECK" {
        val uid = seedUser()
        try {
            shouldThrow<Exception> { insertAppeal(uid, text = "x".repeat(1001)) }
        } finally {
            cleanup(uid)
        }
    }

    "action_type outside {suspension, permanent_ban} is rejected by the CHECK" {
        val uid = seedUser()
        try {
            shouldThrow<Exception> { insertAppeal(uid, actionType = "shadow_ban") }
        } finally {
            cleanup(uid)
        }
    }

    "appeals_one_pending_per_user blocks a second pending appeal for the same user" {
        val uid = seedUser()
        try {
            insertAppeal(uid)
            shouldThrow<Exception> { insertAppeal(uid) } // partial-unique on status='pending'
        } finally {
            cleanup(uid)
        }
    }

    "a new pending appeal is allowed once the prior one is decided" {
        val uid = seedUser()
        try {
            insertAppeal(uid)
            dataSource.connection.use { conn ->
                conn.prepareStatement("UPDATE appeals SET status = 'rejected' WHERE user_id = ?").use {
                    it.setObject(1, uid)
                    it.executeUpdate()
                }
            }
            insertAppeal(uid) // no longer collides with the partial-unique (prior row is 'rejected')
            dataSource.connection.use { conn ->
                conn.prepareStatement("SELECT COUNT(*) FROM appeals WHERE user_id = ?").use { ps ->
                    ps.setObject(1, uid)
                    ps.executeQuery().use { rs ->
                        rs.next()
                        rs.getInt(1) shouldBe 2
                    }
                }
            }
        } finally {
            cleanup(uid)
        }
    }

    "schema: partial indexes are NOW()-free; reviewed_by FK is SET NULL; user_id FK is CASCADE" {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT indexdef FROM pg_indexes WHERE indexname IN ('appeals_one_pending_per_user','appeals_pending_created_idx')",
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    val defs = buildList { while (rs.next()) add(rs.getString("indexdef")) }
                    defs.size shouldBe 2
                    defs.none { it.contains("now()", ignoreCase = true) } shouldBe true
                }
            }
            conn.prepareStatement(
                """
                SELECT kcu.column_name, rc.delete_rule
                  FROM information_schema.referential_constraints rc
                  JOIN information_schema.key_column_usage kcu ON rc.constraint_name = kcu.constraint_name
                 WHERE kcu.table_name = 'appeals' AND kcu.column_name IN ('user_id', 'reviewed_by')
                """.trimIndent(),
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    val rules = buildMap { while (rs.next()) put(rs.getString("column_name"), rs.getString("delete_rule")) }
                    rules["user_id"] shouldBe "CASCADE"
                    rules["reviewed_by"] shouldBe "SET NULL"
                }
            }
        }
    }
})
