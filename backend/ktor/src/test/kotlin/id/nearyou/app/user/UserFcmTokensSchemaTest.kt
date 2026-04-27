package id.nearyou.app.user

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.postgresql.util.PSQLException
import java.sql.Date
import java.sql.DriverManager
import java.time.LocalDate
import java.util.UUID

/**
 * Schema-level smoke for V14 (`user_fcm_tokens`):
 *  - platform CHECK rejects non-enum values (e.g. 'web').
 *  - UNIQUE (user_id, platform, token) rejects a duplicate triple insert.
 *  - ON DELETE CASCADE removes tokens when their user is deleted.
 *  - token CHECK rejects > 4096 chars (D9 additive defense-in-depth).
 *  - app_version CHECK rejects > 64 chars (D9 additive defense-in-depth).
 */
@Tags("database")
class UserFcmTokensSchemaTest : StringSpec({

    val url = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5433/nearyou_dev"
    val user = System.getenv("DB_USER") ?: "postgres"
    val password = System.getenv("DB_PASSWORD") ?: "postgres"

    fun seedUser(): UUID {
        val id = UUID.randomUUID()
        val short = id.toString().replace("-", "").take(8)
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO users (
                    id, username, display_name, date_of_birth, invite_code_prefix
                ) VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "fcm_$short")
                ps.setString(3, "FCM Schema Tester")
                ps.setDate(4, Date.valueOf(LocalDate.of(1990, 1, 1)))
                ps.setString(5, "f${short.take(7)}")
                ps.executeUpdate()
            }
        }
        return id
    }

    fun cleanupUser(userId: UUID) {
        DriverManager.getConnection(url, user, password).use { conn ->
            // ON DELETE CASCADE on user_fcm_tokens.user_id removes tokens too.
            conn.createStatement().use { st ->
                st.executeUpdate("DELETE FROM users WHERE id = '$userId'")
            }
        }
    }

    fun insertToken(
        userId: UUID,
        platform: String,
        token: String,
        appVersion: String? = null,
    ) {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.prepareStatement(
                "INSERT INTO user_fcm_tokens (user_id, platform, token, app_version) VALUES (?, ?, ?, ?)",
            ).use { ps ->
                ps.setObject(1, userId)
                ps.setString(2, platform)
                ps.setString(3, token)
                if (appVersion != null) ps.setString(4, appVersion) else ps.setNull(4, java.sql.Types.VARCHAR)
                ps.executeUpdate()
            }
        }
    }

    fun countTokens(userId: UUID): Int =
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM user_fcm_tokens WHERE user_id = ?").use { ps ->
                ps.setObject(1, userId)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    "platform CHECK rejects 'web' with a Postgres CHECK violation" {
        val u = seedUser()
        try {
            val ex =
                shouldThrow<PSQLException> {
                    insertToken(u, platform = "web", token = "tok-web")
                }
            // SQLState 23514 = check_violation
            ex.sqlState shouldBe "23514"
        } finally {
            cleanupUser(u)
        }
    }

    "UNIQUE (user_id, platform, token) rejects a duplicate triple insert" {
        val u = seedUser()
        try {
            insertToken(u, platform = "android", token = "dup-tok")
            val ex =
                shouldThrow<PSQLException> {
                    insertToken(u, platform = "android", token = "dup-tok")
                }
            // SQLState 23505 = unique_violation
            ex.sqlState shouldBe "23505"
        } finally {
            cleanupUser(u)
        }
    }

    "ON DELETE CASCADE removes tokens when their user is deleted" {
        val u = seedUser()
        insertToken(u, platform = "android", token = "cascade-tok-a")
        insertToken(u, platform = "ios", token = "cascade-tok-b")
        countTokens(u) shouldBe 2
        cleanupUser(u)
        countTokens(u) shouldBe 0
    }

    "token CHECK rejects 4097-char string (D9)" {
        val u = seedUser()
        try {
            val long = "a".repeat(4097)
            val ex =
                shouldThrow<PSQLException> {
                    insertToken(u, platform = "android", token = long)
                }
            ex.sqlState shouldBe "23514"
            ex.message!!.shouldContain("token")
        } finally {
            cleanupUser(u)
        }
    }

    "app_version CHECK rejects 65-char string (D9)" {
        val u = seedUser()
        try {
            val long = "v".repeat(65)
            val ex =
                shouldThrow<PSQLException> {
                    insertToken(u, platform = "android", token = "av-tok", appVersion = long)
                }
            ex.sqlState shouldBe "23514"
            ex.message!!.shouldContain("app_version")
        } finally {
            cleanupUser(u)
        }
    }
})
