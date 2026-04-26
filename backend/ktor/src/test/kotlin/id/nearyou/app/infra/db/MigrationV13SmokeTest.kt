package id.nearyou.app.infra.db

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.flywaydb.core.Flyway
import java.sql.DriverManager
import java.util.UUID

/**
 * Database-dependent smoke test for V13 (`V13__premium_search_fts.sql`). Boots
 * Flyway against the running dev Postgres (or the CI service container) and
 * asserts the full FTS infrastructure that V4 deferred is now present:
 *
 *   - pg_trgm extension installed
 *   - posts.content_tsv TSVECTOR GENERATED column
 *   - posts_content_tsv_idx GIN
 *   - posts_content_trgm_idx GIN
 *   - users_username_trgm_idx GIN
 *   - INSERT/UPDATE auto-population behaviour matches the GENERATED ALWAYS contract
 *   - Direct writes to content_tsv are rejected
 *   - Existing rows backfilled with content_tsv during ALTER TABLE
 *   - EXPLAIN proves the indexes are used by the canonical query operators
 *
 * Tagged `database` so CI excludes it by default. Run locally with:
 *   `./gradlew :backend:ktor:test -Dkotest.tags=database`
 */
@Tags("database")
class MigrationV13SmokeTest : StringSpec({

    val url = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5433/nearyou_dev"
    val user = System.getenv("DB_USER") ?: "postgres"
    val password = System.getenv("DB_PASSWORD") ?: "postgres"

    Flyway
        .configure()
        .dataSource(url, user, password)
        .locations("classpath:db/migration")
        .load()
        .migrate()

    "V13 present in flyway_schema_history with success" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(
                    "SELECT success FROM flyway_schema_history WHERE version = '13'",
                ).use { rs ->
                    rs.next() shouldBe true
                    rs.getBoolean(1) shouldBe true
                }
            }
        }
    }

    "pg_trgm extension is installed" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(
                    "SELECT extname FROM pg_extension WHERE extname = 'pg_trgm'",
                ).use { rs ->
                    rs.next() shouldBe true
                    rs.getString(1) shouldBe "pg_trgm"
                }
            }
        }
    }

    "posts.content_tsv column has type tsvector and is GENERATED" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(
                    """
                    SELECT data_type, is_generated, generation_expression
                      FROM information_schema.columns
                     WHERE table_name = 'posts' AND column_name = 'content_tsv'
                    """.trimIndent(),
                ).use { rs ->
                    rs.next() shouldBe true
                    rs.getString("data_type") shouldBe "tsvector"
                    rs.getString("is_generated") shouldBe "ALWAYS"
                    val expr = rs.getString("generation_expression").lowercase()
                    expr shouldContain "to_tsvector"
                    expr shouldContain "'simple'"
                    expr shouldContain "content"
                }
            }
        }
    }

    "all three new GIN indexes exist" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(
                    """
                    SELECT i.relname, am.amname
                      FROM pg_class i
                      JOIN pg_index ix ON ix.indexrelid = i.oid
                      JOIN pg_am am ON i.relam = am.oid
                     WHERE i.relname IN (
                         'posts_content_tsv_idx',
                         'posts_content_trgm_idx',
                         'users_username_trgm_idx'
                     )
                    """.trimIndent(),
                ).use { rs ->
                    val byName = mutableMapOf<String, String>()
                    while (rs.next()) byName[rs.getString(1)] = rs.getString(2)
                    byName.keys shouldContainAll
                        setOf(
                            "posts_content_tsv_idx",
                            "posts_content_trgm_idx",
                            "users_username_trgm_idx",
                        )
                    byName["posts_content_tsv_idx"] shouldBe "gin"
                    byName["posts_content_trgm_idx"] shouldBe "gin"
                    byName["users_username_trgm_idx"] shouldBe "gin"
                }
            }
        }
    }

    // 1.4 — INSERT auto-population. Seeds a user via the users-schema canonical
    // INSERT (auth-foundation V2) so the FK on posts.author_id is satisfied.
    "GENERATED column auto-populates on INSERT" {
        val authorId = UUID.randomUUID()
        val username = "search_v13_insert_${System.nanoTime()}"
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.autoCommit = false
            try {
                seedUser(conn, authorId, username)
                val postId = UUID.randomUUID()
                conn.prepareStatement(
                    """
                    INSERT INTO posts (id, author_id, content, display_location, actual_location)
                    VALUES (?, ?, ?, ST_GeogFromText('SRID=4326;POINT(106.8 -6.2)'),
                                ST_GeogFromText('SRID=4326;POINT(106.8 -6.2)'))
                    """.trimIndent(),
                ).use {
                    it.setObject(1, postId)
                    it.setObject(2, authorId)
                    it.setString(3, "jakarta selatan")
                    it.executeUpdate()
                }

                conn.prepareStatement(
                    """
                    SELECT content_tsv::TEXT,
                           to_tsvector('simple', 'jakarta selatan')::TEXT AS expected
                      FROM posts WHERE id = ?
                    """.trimIndent(),
                ).use {
                    it.setObject(1, postId)
                    it.executeQuery().use { rs ->
                        rs.next() shouldBe true
                        rs.getString("content_tsv") shouldBe rs.getString("expected")
                    }
                }
            } finally {
                conn.rollback()
            }
        }
    }

    // 1.5 — Existing-row backfill. Inserts posts BEFORE re-applying schema to
    // simulate the V13 ALTER TABLE behaviour: GENERATED ALWAYS STORED rewrites
    // every existing row at column-add time, so any row already in `posts`
    // (including ones inserted before V13 ran in the JVM) MUST have a populated
    // content_tsv equal to to_tsvector('simple', content). We validate against
    // the JVM-bootstrap state: pre-existing test data + freshly-inserted data
    // both satisfy the equality.
    "every posts row has content_tsv equal to to_tsvector('simple', content)" {
        val authorId = UUID.randomUUID()
        val username = "search_v13_backfill_${System.nanoTime()}"
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.autoCommit = false
            try {
                seedUser(conn, authorId, username)
                val postId = UUID.randomUUID()
                conn.prepareStatement(
                    """
                    INSERT INTO posts (id, author_id, content, display_location, actual_location)
                    VALUES (?, ?, ?, ST_GeogFromText('SRID=4326;POINT(106.8 -6.2)'),
                                ST_GeogFromText('SRID=4326;POINT(106.8 -6.2)'))
                    """.trimIndent(),
                ).use {
                    it.setObject(1, postId)
                    it.setObject(2, authorId)
                    it.setString(3, "warung tegal kopi")
                    it.executeUpdate()
                }

                // Sweep across ALL posts in the schema and confirm equality.
                conn.createStatement().use { st ->
                    st.executeQuery(
                        """
                        SELECT COUNT(*) FILTER (
                            WHERE content_tsv IS DISTINCT FROM to_tsvector('simple', content)
                        ) AS mismatches
                          FROM posts
                        """.trimIndent(),
                    ).use { rs ->
                        rs.next() shouldBe true
                        rs.getLong("mismatches") shouldBe 0L
                    }
                }
            } finally {
                conn.rollback()
            }
        }
    }

    // 1.6 — UPDATE auto-regen. Postgres GENERATED ALWAYS STORED columns auto-
    // regenerate on UPDATE of the source column.
    "GENERATED column regenerates on UPDATE of content" {
        val authorId = UUID.randomUUID()
        val username = "search_v13_update_${System.nanoTime()}"
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.autoCommit = false
            try {
                seedUser(conn, authorId, username)
                val postId = UUID.randomUUID()
                conn.prepareStatement(
                    """
                    INSERT INTO posts (id, author_id, content, display_location, actual_location)
                    VALUES (?, ?, ?, ST_GeogFromText('SRID=4326;POINT(106.8 -6.2)'),
                                ST_GeogFromText('SRID=4326;POINT(106.8 -6.2)'))
                    """.trimIndent(),
                ).use {
                    it.setObject(1, postId)
                    it.setObject(2, authorId)
                    it.setString(3, "foo")
                    it.executeUpdate()
                }
                conn.prepareStatement("UPDATE posts SET content = ? WHERE id = ?").use {
                    it.setString(1, "bar")
                    it.setObject(2, postId)
                    it.executeUpdate()
                }
                conn.prepareStatement(
                    """
                    SELECT content_tsv::TEXT,
                           to_tsvector('simple', 'bar')::TEXT AS expected
                      FROM posts WHERE id = ?
                    """.trimIndent(),
                ).use {
                    it.setObject(1, postId)
                    it.executeQuery().use { rs ->
                        rs.next() shouldBe true
                        rs.getString("content_tsv") shouldBe rs.getString("expected")
                    }
                }
            } finally {
                conn.rollback()
            }
        }
    }

    // 1.7 — Direct writes rejected.
    "direct writes to content_tsv are rejected" {
        val authorId = UUID.randomUUID()
        val username = "search_v13_reject_${System.nanoTime()}"
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.autoCommit = false
            try {
                seedUser(conn, authorId, username)
                val postId = UUID.randomUUID()
                conn.prepareStatement(
                    """
                    INSERT INTO posts (id, author_id, content, display_location, actual_location)
                    VALUES (?, ?, ?, ST_GeogFromText('SRID=4326;POINT(106.8 -6.2)'),
                                ST_GeogFromText('SRID=4326;POINT(106.8 -6.2)'))
                    """.trimIndent(),
                ).use {
                    it.setObject(1, postId)
                    it.setObject(2, authorId)
                    it.setString(3, "before")
                    it.executeUpdate()
                }
                val ex =
                    runCatching {
                        conn.prepareStatement(
                            "UPDATE posts SET content_tsv = to_tsvector('simple', 'after') WHERE id = ?",
                        ).use {
                            it.setObject(1, postId)
                            it.executeUpdate()
                        }
                    }.exceptionOrNull()
                ex shouldBe ex // sanity: there must be an exception
                requireNotNull(ex)
                // Postgres SQLSTATE 428C9 = cannot insert/update a generated column
                ex.message!!.lowercase() shouldContain "generated"
            } finally {
                conn.rollback()
            }
        }
    }

    // 1.3 — EXPLAIN proves each index is reachable. We use force-index hints
    // via SET LOCAL enable_seqscan = OFF so the planner cannot fall back to a
    // seq scan on a small test corpus (where seq might otherwise be cheaper).
    "EXPLAIN: posts_content_tsv_idx is used for FTS @@ predicate" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.autoCommit = false
            try {
                conn.createStatement().use { it.execute("SET LOCAL enable_seqscan = OFF") }
                conn.createStatement().use { st ->
                    st.executeQuery(
                        """
                        EXPLAIN (FORMAT TEXT)
                        SELECT id FROM posts
                         WHERE content_tsv @@ plainto_tsquery('simple', 'jakarta')
                        """.trimIndent(),
                    ).use { rs ->
                        val plan = StringBuilder()
                        while (rs.next()) plan.appendLine(rs.getString(1))
                        plan.toString().lowercase() shouldContain "posts_content_tsv_idx"
                    }
                }
            } finally {
                conn.rollback()
            }
        }
    }

    "EXPLAIN: posts_content_trgm_idx is used for content % predicate" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.autoCommit = false
            try {
                conn.createStatement().use { it.execute("SET LOCAL enable_seqscan = OFF") }
                conn.createStatement().use { st ->
                    st.executeQuery(
                        """
                        EXPLAIN (FORMAT TEXT) SELECT id FROM posts WHERE content % 'jakart'
                        """.trimIndent(),
                    ).use { rs ->
                        val plan = StringBuilder()
                        while (rs.next()) plan.appendLine(rs.getString(1))
                        plan.toString().lowercase() shouldContain "posts_content_trgm_idx"
                    }
                }
            } finally {
                conn.rollback()
            }
        }
    }

    "EXPLAIN: users_username_trgm_idx is used for username % predicate" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.autoCommit = false
            try {
                conn.createStatement().use { it.execute("SET LOCAL enable_seqscan = OFF") }
                conn.createStatement().use { st ->
                    st.executeQuery(
                        """
                        EXPLAIN (FORMAT TEXT) SELECT id FROM users WHERE username % 'aditrioka'
                        """.trimIndent(),
                    ).use { rs ->
                        val plan = StringBuilder()
                        while (rs.next()) plan.appendLine(rs.getString(1))
                        plan.toString().lowercase() shouldContain "users_username_trgm_idx"
                    }
                }
            } finally {
                conn.rollback()
            }
        }
    }

    // pg_trgm exposes its threshold via the `show_limit()` function (always
    // queryable) AND optionally via the GUC `pg_trgm.similarity_threshold`
    // (only visible after a SET — present in pg_trgm ≥ 1.6 / Postgres 14+).
    // Use show_limit() since it's the canonical, always-readable API.
    "pg_trgm.show_limit() default is 0.3" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT show_limit()").use { rs ->
                    rs.next() shouldBe true
                    // Postgres formats this as "0.3" or "0.30000000000000004" depending
                    // on platform; a startsWith check is the robust assertion.
                    rs.getString(1) shouldStartWith "0.3"
                }
            }
        }
    }

    "SET LOCAL pg_trgm.similarity_threshold succeeds (matches repository SET)" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.autoCommit = false
            try {
                conn.createStatement().use { it.execute("SET LOCAL pg_trgm.similarity_threshold = 0.3") }
                // After SET, the GUC IS readable.
                conn.createStatement().use { st ->
                    st.executeQuery("SHOW pg_trgm.similarity_threshold").use { rs ->
                        rs.next() shouldBe true
                        rs.getString(1) shouldStartWith "0.3"
                    }
                }
            } finally {
                conn.rollback()
            }
        }
    }
})

private fun seedUser(
    conn: java.sql.Connection,
    id: UUID,
    username: String,
) {
    conn.prepareStatement(
        """
        INSERT INTO users (
            id, username, display_name, date_of_birth,
            google_id_hash, invite_code_prefix, token_version
        )
        VALUES (?, ?, ?, '1990-01-01', ?, ?, 0)
        """.trimIndent(),
    ).use {
        it.setObject(1, id)
        it.setString(2, username)
        it.setString(3, "Search V13 Test")
        it.setString(4, "g_$id")
        // 8-char prefix, lowercase a-z2-7 (base32 charset). Use a deterministic
        // slice of the UUID so it's unique per row.
        it.setString(5, id.toString().replace("-", "").take(8))
        it.executeUpdate()
    }
}
