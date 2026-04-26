package id.nearyou.app.infra.db

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.flywaydb.core.Flyway
import java.sql.DriverManager

/**
 * Database-dependent smoke test. Boots Flyway against the running dev Postgres
 * (dev/docker-compose.yml) and asserts V4 lands cleanly: posts table + 4 indexes +
 * visible_posts view + author_id RESTRICT FK.
 *
 * Tagged `database` so CI excludes it by default. Run locally with:
 *   `./gradlew :backend:ktor:test -Dkotest.tags=database`
 */
@Tags("database")
class MigrationV4SmokeTest : StringSpec({

    val url = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5433/nearyou_dev"
    val user = System.getenv("DB_USER") ?: "postgres"
    val password = System.getenv("DB_PASSWORD") ?: "postgres"

    Flyway
        .configure()
        .dataSource(url, user, password)
        .locations("classpath:db/migration")
        .load()
        .migrate()

    "V4 present in flyway_schema_history with success" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(
                    "SELECT success FROM flyway_schema_history WHERE version = '4'",
                ).use { rs ->
                    rs.next() shouldBe true
                    rs.getBoolean(1) shouldBe true
                }
            }
        }
    }

    "posts table has every canonical column" {
        val expected =
            setOf(
                "id", "author_id", "content", "display_location", "actual_location",
                "city_name", "city_match_type", "image_id", "is_auto_hidden",
                "created_at", "updated_at", "deleted_at",
            )
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(
                    "SELECT column_name FROM information_schema.columns WHERE table_name = 'posts'",
                ).use { rs ->
                    val present = mutableSetOf<String>()
                    while (rs.next()) present += rs.getString(1)
                    present.containsAll(expected) shouldBe true
                }
            }
        }
    }

    // FTS infrastructure (content_tsv column + GIN indexes + pg_trgm extension)
    // shipped in V13 (`premium-search` change); see MigrationV13SmokeTest.
    // The V4 smoke test no longer asserts content_tsv is absent — V13 adds it,
    // and Flyway runs both migrations during the test JVM bootstrap.

    "all four V4 indexes exist and the geography pair uses gist" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(
                    """
                    SELECT i.relname, am.amname
                      FROM pg_class i
                      JOIN pg_index ix ON ix.indexrelid = i.oid
                      JOIN pg_class t ON ix.indrelid = t.oid
                      JOIN pg_am am ON i.relam = am.oid
                     WHERE t.relname = 'posts'
                    """.trimIndent(),
                ).use { rs ->
                    val byName = mutableMapOf<String, String>()
                    while (rs.next()) byName[rs.getString(1)] = rs.getString(2)
                    byName.keys shouldContainAll
                        setOf(
                            "posts_display_location_idx",
                            "posts_actual_location_idx",
                            "posts_timeline_cursor_idx",
                            "posts_nearby_cursor_idx",
                        )
                    byName["posts_display_location_idx"] shouldBe "gist"
                    byName["posts_actual_location_idx"] shouldBe "gist"
                    // nearby cursor is composite GIST via btree_gist extension
                    byName["posts_nearby_cursor_idx"] shouldBe "gist"
                    // timeline cursor is plain btree
                    byName["posts_timeline_cursor_idx"] shouldBe "btree"
                }
            }
        }
    }

    "visible_posts view exists and filters is_auto_hidden" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(
                    "SELECT definition FROM pg_views WHERE viewname = 'visible_posts'",
                ).use { rs ->
                    rs.next() shouldBe true
                    val def = rs.getString(1).lowercase()
                    def shouldContain "from posts"
                    def shouldContain "is_auto_hidden = false"
                }
            }
        }
    }

    "author_id FK uses ON DELETE RESTRICT" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(
                    """
                    SELECT confdeltype
                      FROM pg_constraint
                     WHERE conrelid = 'posts'::regclass
                       AND contype = 'f'
                    """.trimIndent(),
                ).use { rs ->
                    rs.next() shouldBe true
                    // 'r' = RESTRICT per pg_constraint docs
                    rs.getString(1) shouldBe "r"
                }
            }
        }
    }
})
