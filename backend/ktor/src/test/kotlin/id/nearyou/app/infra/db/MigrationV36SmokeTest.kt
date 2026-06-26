package id.nearyou.app.infra.db

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.postgresql.util.PSQLException
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/**
 * Database-dependent smoke test for V36
 * (`V36__moderation_queue_area_spam_trigger.sql`). Boots Flyway against the
 * running dev Postgres (or the CI service container) and asserts the
 * `moderation-queue` MODIFIED delta from `post-area-density-cap`:
 *
 *   - V36 present in `flyway_schema_history` with success.
 *   - `trigger = 'area_spam'` INSERT succeeds (the new value passes the CHECK).
 *   - All EIGHT trigger values are accepted (the seven originals remain valid —
 *     proving the constraint change is additive / no data rewrite).
 *   - An out-of-enum trigger (`spam_pattern_detected`) is still rejected (23514) —
 *     the extension added only `area_spam`, not arbitrary values.
 *
 * `moderation_queue.target_id` carries no FK (V9), so the inserts use synthetic
 * UUID targets and are swept in a `finally`. Tagged `database` so CI excludes it
 * by default; run locally with `./gradlew :backend:ktor:test -Dkotest.tags=database`.
 */
@Tags("database")
class MigrationV36SmokeTest : StringSpec({

    val url = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5433/nearyou_dev"
    val user = System.getenv("DB_USER") ?: "postgres"
    val password = System.getenv("DB_PASSWORD") ?: "postgres"

    Flyway
        .configure()
        .dataSource(url, user, password)
        .locations("classpath:db/migration")
        .load()
        .migrate()

    val allEightTriggers =
        listOf(
            "auto_hide_3_reports",
            "perspective_api_high_score",
            "uu_ite_keyword_match",
            "admin_flag",
            "csam_detected",
            "anomaly_detection",
            "username_flagged",
            "area_spam",
        )

    fun insertQueueRow(
        conn: Connection,
        trigger: String,
    ): UUID {
        val targetId = UUID.randomUUID()
        conn.prepareStatement(
            "INSERT INTO moderation_queue (target_type, target_id, trigger) VALUES ('post', ?, ?)",
        ).use { ps ->
            ps.setObject(1, targetId)
            ps.setString(2, trigger)
            ps.executeUpdate()
        }
        return targetId
    }

    fun sweep(
        conn: Connection,
        targetIds: List<UUID>,
    ) {
        conn.prepareStatement("DELETE FROM moderation_queue WHERE target_id = ANY(?)").use { ps ->
            ps.setArray(1, conn.createArrayOf("uuid", targetIds.toTypedArray()))
            ps.executeUpdate()
        }
    }

    "V36 present in flyway_schema_history with success" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.prepareStatement(
                "SELECT success FROM flyway_schema_history WHERE version = '36'",
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next() shouldBe true
                    rs.getBoolean(1) shouldBe true
                }
            }
        }
    }

    "area_spam trigger INSERT succeeds after V36" {
        DriverManager.getConnection(url, user, password).use { conn ->
            val id = insertQueueRow(conn, "area_spam")
            try {
                conn.prepareStatement(
                    "SELECT COUNT(*) FROM moderation_queue WHERE target_id = ? AND trigger = 'area_spam'",
                ).use { ps ->
                    ps.setObject(1, id)
                    ps.executeQuery().use { rs ->
                        rs.next()
                        rs.getInt(1) shouldBe 1
                    }
                }
            } finally {
                sweep(conn, listOf(id))
            }
        }
    }

    "all eight trigger values are accepted after V36 (additive — seven originals still valid)" {
        DriverManager.getConnection(url, user, password).use { conn ->
            val ids = allEightTriggers.map { insertQueueRow(conn, it) }
            try {
                conn.prepareStatement(
                    "SELECT COUNT(*) FROM moderation_queue WHERE target_id = ANY(?)",
                ).use { ps ->
                    ps.setArray(1, conn.createArrayOf("uuid", ids.toTypedArray()))
                    ps.executeQuery().use { rs ->
                        rs.next()
                        rs.getInt(1) shouldBe allEightTriggers.size
                    }
                }
            } finally {
                sweep(conn, ids)
            }
        }
    }

    "out-of-enum trigger still rejected after V36 (only area_spam was added)" {
        DriverManager.getConnection(url, user, password).use { conn ->
            try {
                conn.prepareStatement(
                    "INSERT INTO moderation_queue (target_type, target_id, trigger) VALUES ('post', ?, 'spam_pattern_detected')",
                ).use { ps ->
                    ps.setObject(1, UUID.randomUUID())
                    ps.executeUpdate()
                }
                error("CHECK constraint should have rejected trigger='spam_pattern_detected'")
            } catch (e: PSQLException) {
                e.sqlState shouldBe "23514"
            }
        }
    }

    "V36 SQL file is additive — no DELETE/UPDATE/DROP TABLE (no data rewrite)" {
        val sql =
            this::class.java.classLoader
                .getResourceAsStream("db/migration/V36__moderation_queue_area_spam_trigger.sql")!!
                .bufferedReader()
                .use { it.readText() }
        val stripped =
            sql.lineSequence()
                .map { it.replace(Regex("--.*$"), "") }
                .joinToString("\n")
        Regex("(?im)^\\s*DELETE\\b").containsMatchIn(stripped) shouldBe false
        Regex("(?im)^\\s*UPDATE\\b").containsMatchIn(stripped) shouldBe false
        Regex("(?im)\\bDROP\\s+TABLE\\b").containsMatchIn(stripped) shouldBe false
    }
})
