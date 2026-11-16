package id.nearyou.app.infra.db

import id.nearyou.app.admin.auth.AdminAuthTestSupport
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Database-dependent smoke test for V41 (`V41__admin_actions_log_after_user_idx.sql`),
 * the schema half of `admin-user-history-enforcement-rows` (`admin-user-management`
 * § "The action-history view merges admin_actions_log and username_history"):
 *
 *   - V41 present in `flyway_schema_history` with success.
 *   - `admin_actions_after_user_idx` exists on `admin_actions_log`, indexing the
 *     `(after_state ->> 'user_id')` expression, partial on `IS NOT NULL`.
 *   - Re-applying the V41 DDL is a no-op (IF NOT EXISTS — staging branch deploys re-apply).
 *   - The SQL is additive (no DELETE / UPDATE / DROP).
 *
 * The plan-level "index-served" assertion lives with the history read
 * (`UserProfileHistoryTest`). Migrations boot once per JVM via `KotestProjectConfig`.
 */
@Tags("database")
class MigrationV41SmokeTest : StringSpec({

    val dataSource = autoClose(AdminAuthTestSupport.hikari())

    val migrationSql =
        MigrationV41SmokeTest::class.java.classLoader
            .getResourceAsStream("db/migration/V41__admin_actions_log_after_user_idx.sql")!!
            .bufferedReader()
            .use { it.readText() }

    fun indexDef(): String? =
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT indexdef FROM pg_indexes WHERE tablename = 'admin_actions_log' AND indexname = 'admin_actions_after_user_idx'",
            ).use { ps -> ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null } }
        }

    "V41 present in flyway_schema_history with success" {
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT success FROM flyway_schema_history WHERE version = '41'").use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next() shouldBe true
                    rs.getBoolean(1) shouldBe true
                }
            }
        }
    }

    "admin_actions_after_user_idx indexes after_state ->> 'user_id', partial on IS NOT NULL" {
        val def = indexDef()!!
        def shouldContain "ON public.admin_actions_log"
        def shouldContain "((after_state ->> 'user_id'::text))"
        def shouldContain "WHERE ((after_state ->> 'user_id'::text) IS NOT NULL)"
    }

    "re-applying the V41 DDL is a no-op (IF NOT EXISTS)" {
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                conn.createStatement().use { it.execute(migrationSql) }
            } finally {
                conn.rollback()
                conn.autoCommit = true
            }
        }
        indexDef()!! shouldContain "after_state ->> 'user_id'"
    }

    "V41 SQL file is additive — no DELETE/UPDATE/DROP (no data rewrite)" {
        val stripped =
            migrationSql.lineSequence()
                .map { it.replace(Regex("--.*$"), "") }
                .joinToString("\n")
        Regex("(?im)^\\s*DELETE\\b").containsMatchIn(stripped) shouldBe false
        Regex("(?im)^\\s*UPDATE\\b").containsMatchIn(stripped) shouldBe false
        Regex("(?im)\\bDROP\\b").containsMatchIn(stripped) shouldBe false
    }
})
