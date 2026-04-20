package id.nearyou.app.infra.db

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import java.sql.DriverManager

/**
 * Database-dependent smoke test. Boots Flyway against a running Postgres
 * (dev/docker-compose.yml) and asserts V3 lands: three new tables + seed count.
 *
 * Tagged `database` so it can be excluded from default PR CI (which sets
 * `-Dkotest.tags=!database`). Run locally against the dev compose stack or
 * explicitly with `./gradlew test -Dkotest.tags=database`.
 *
 * Covers the schema-creation gap left by skipping a container-based harness —
 * parallels JwksReachabilityTest's role for the provider network layer.
 */
@Tags("database")
class MigrationV3SmokeTest : StringSpec({

    val url = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5433/nearyou_dev"
    val user = System.getenv("DB_USER") ?: "postgres"
    val password = System.getenv("DB_PASSWORD") ?: "postgres"

    // Ensure migrations are applied before assertions.
    Flyway
        .configure()
        .dataSource(url, user, password)
        .locations("classpath:db/migration")
        .load()
        .migrate()

    "V3 present in flyway_schema_history" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(
                    "SELECT success FROM flyway_schema_history WHERE version = '3'",
                ).use { rs ->
                    rs.next() shouldBe true
                    rs.getBoolean(1) shouldBe true
                }
            }
        }
    }

    "reserved_usernames seed count at least 1341" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(
                    "SELECT COUNT(*) FROM reserved_usernames WHERE source = 'seed_system'",
                ).use { rs ->
                    rs.next() shouldBe true
                    rs.getInt(1) shouldBeGreaterThanOrEqual 1341
                }
            }
        }
    }

    "rejected_identifiers and username_history tables exist" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(
                    """
                    SELECT COUNT(*)
                    FROM pg_tables
                    WHERE schemaname = 'public'
                      AND tablename IN ('rejected_identifiers', 'username_history')
                    """.trimIndent(),
                ).use { rs ->
                    rs.next() shouldBe true
                    rs.getInt(1) shouldBe 2
                }
            }
        }
    }

    "username_history_released_idx is plain B-tree (NOT partial)" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(
                    "SELECT indexdef FROM pg_indexes WHERE indexname = 'username_history_released_idx'",
                ).use { rs ->
                    rs.next() shouldBe true
                    val def = rs.getString(1)
                    (def.contains("btree (released_at)")) shouldBe true
                    (def.contains("WHERE")) shouldBe false
                }
            }
        }
    }

    "reserved_usernames protect_seed trigger blocks seed deletion" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.autoCommit = false
            try {
                conn.createStatement().use { st ->
                    var raised = false
                    try {
                        st.executeUpdate("DELETE FROM reserved_usernames WHERE username = 'admin'")
                    } catch (_: Exception) {
                        raised = true
                    }
                    raised shouldBe true
                }
            } finally {
                conn.rollback()
            }
        }
    }
})
