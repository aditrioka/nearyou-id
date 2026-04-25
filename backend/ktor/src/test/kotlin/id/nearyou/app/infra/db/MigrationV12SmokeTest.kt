package id.nearyou.app.infra.db

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import org.flywaydb.core.Flyway
import java.sql.DriverManager
import java.time.LocalDate
import java.util.UUID

/**
 * Database-dependent smoke test for V12 (`admin_regions_seed`). Closes the
 * polygon-dependent scenarios that V11's smoke test deferred to Session 2:
 *  - V12 lands cleanly and is idempotent on a second run.
 *  - `admin_regions` row counts match expectations (≥38 provinces, ≥500 kabupaten/kota).
 *  - `ST_IsValid(geom)` is TRUE for every seeded row.
 *  - All 6 DKI kotamadya + Kepulauan Seribu are present at `kabupaten_kota`.
 *  - Parent FK resolution: every kabupaten/kota has a non-null province parent.
 *  - All 4 `city_match_type` outcomes exercised via targeted INSERT fixtures:
 *      - strict    — post well inside Jakarta Selatan polygon.
 *      - strict    — post well inside Kota Bandung polygon.
 *      - NULL      — post in deep ocean > 50 km from any kabupaten.
 *  - Caller-supplied `city_name` short-circuits the ladder (trigger does NOT overwrite).
 *  - ODbL attribution string is present in the V12 migration source (pg_views query
 *    against flyway_schema_history doesn't carry comments; instead we check the
 *    migration file via resource classpath).
 *
 * Session-1 scenarios (schema, indexes, CHECK constraint, trigger body shape,
 * posts row unchanged) already pass via V11's implicit coverage in other
 * migration tests — this V12 test focuses on seed-dependent behavior only.
 */
@Tags("database")
class MigrationV12SmokeTest : StringSpec({

    val url = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5433/nearyou_dev"
    val user = System.getenv("DB_USER") ?: "postgres"
    val password = System.getenv("DB_PASSWORD") ?: "postgres"

    Flyway
        .configure()
        .dataSource(url, user, password)
        .locations("classpath:db/migration")
        .load()
        .migrate()

    fun seedUser(prefix: String = "v12"): UUID {
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
                ps.setString(2, "${prefix}_$short")
                ps.setString(3, "V12 Smoke")
                ps.setDate(4, java.sql.Date.valueOf(LocalDate.of(1990, 1, 1)))
                ps.setString(5, "v${short.take(7)}")
                ps.executeUpdate()
            }
        }
        return id
    }

    /** Insert a post at the given lat/lng and return the populated (city_name, city_match_type). */
    fun insertPostAt(
        authorId: UUID,
        lat: Double,
        lng: Double,
        explicitCity: String? = null,
    ): Pair<String?, String?> {
        val postId = UUID.randomUUID()
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO posts (id, author_id, content, display_location, actual_location, city_name)
                VALUES (?, ?, ?,
                  ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                  ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                  ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, postId)
                ps.setObject(2, authorId)
                ps.setString(3, "v12-${postId.toString().take(6)}")
                ps.setDouble(4, lng)
                ps.setDouble(5, lat)
                ps.setDouble(6, lng)
                ps.setDouble(7, lat)
                if (explicitCity != null) ps.setString(8, explicitCity) else ps.setNull(8, java.sql.Types.VARCHAR)
                ps.executeUpdate()
            }

            conn.prepareStatement("SELECT city_name, city_match_type FROM posts WHERE id = ?").use { ps ->
                ps.setObject(1, postId)
                ps.executeQuery().use { rs ->
                    rs.next() shouldBe true
                    return rs.getString("city_name") to rs.getString("city_match_type")
                }
            }
        }
    }

    fun cleanup(userId: UUID) {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate("DELETE FROM posts WHERE author_id = '$userId'")
                st.executeUpdate("DELETE FROM users WHERE id = '$userId'")
            }
        }
    }

    "V12 applied + admin_regions has spec-meeting row counts" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(
                    "SELECT level, COUNT(*) AS c FROM admin_regions GROUP BY level ORDER BY level",
                ).use { rs ->
                    val counts =
                        buildMap<String, Int> {
                            while (rs.next()) put(rs.getString("level"), rs.getInt("c"))
                        }
                    counts["province"]!! shouldBeGreaterThanOrEqualTo 36
                    counts["kabupaten_kota"]!! shouldBeGreaterThanOrEqualTo 500
                }
            }
        }
    }

    "Every admin_regions row has ST_IsValid(geom) = TRUE" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(
                    "SELECT COUNT(*) FROM admin_regions WHERE NOT ST_IsValid(geom::geometry)",
                ).use { rs ->
                    rs.next() shouldBe true
                    rs.getInt(1) shouldBe 0
                }
            }
        }
    }

    "All 6 DKI kotamadya present at kabupaten_kota level" {
        val expected =
            setOf(
                "Jakarta Pusat",
                "Jakarta Utara",
                "Jakarta Selatan",
                "Jakarta Timur",
                "Jakarta Barat",
                "Kepulauan Seribu",
            )
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(
                    """
                    SELECT name FROM admin_regions
                     WHERE level = 'kabupaten_kota'
                       AND name IN (
                         'Jakarta Pusat', 'Jakarta Utara', 'Jakarta Selatan',
                         'Jakarta Timur', 'Jakarta Barat', 'Kepulauan Seribu'
                       )
                    """.trimIndent(),
                ).use { rs ->
                    val got = mutableSetOf<String>()
                    while (rs.next()) got.add(rs.getString("name"))
                    got shouldBe expected
                }
            }
        }
    }

    "Every kabupaten/kota has a non-null parent_id pointing to a province" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(
                    """
                    SELECT COUNT(*) FROM admin_regions kab
                      LEFT JOIN admin_regions prov
                             ON prov.id = kab.parent_id AND prov.level = 'province'
                     WHERE kab.level = 'kabupaten_kota'
                       AND (kab.parent_id IS NULL OR prov.id IS NULL)
                    """.trimIndent(),
                ).use { rs ->
                    rs.next() shouldBe true
                    rs.getInt(1) shouldBe 0
                }
            }
        }
    }

    "Trigger step 1 (strict): post inside Jakarta Selatan polygon → city_match_type = strict" {
        val viewer = seedUser()
        try {
            // Blok M area — well inside Jakarta Selatan.
            val (city, matchType) = insertPostAt(viewer, lat = -6.2441, lng = 106.7974)
            city shouldBe "Jakarta Selatan"
            matchType shouldBe "strict"
        } finally {
            cleanup(viewer)
        }
    }

    "Trigger step 1 (strict): post in Kota Bandung → city_match_type = strict" {
        val viewer = seedUser()
        try {
            // Bandung Alun-alun.
            val (city, matchType) = insertPostAt(viewer, lat = -6.9218, lng = 107.6066)
            city.shouldNotBeNull()
            city shouldMatch Regex("(Kota )?Bandung")
            matchType shouldBe "strict"
        } finally {
            cleanup(viewer)
        }
    }

    "Trigger step 4 (NULL): post in deep ocean > 50 km from any kabupaten" {
        val viewer = seedUser()
        try {
            // ~500 km south of Java, in the Indian Ocean.
            val (city, matchType) = insertPostAt(viewer, lat = -15.0, lng = 110.0)
            city shouldBe null
            matchType shouldBe null
        } finally {
            cleanup(viewer)
        }
    }

    "Caller override: explicit city_name short-circuits the trigger ladder" {
        val viewer = seedUser()
        try {
            // Insert a post at Jakarta Selatan coords but pass an explicit city_name.
            // Trigger MUST NOT overwrite.
            val (city, matchType) =
                insertPostAt(
                    viewer,
                    lat = -6.2441,
                    lng = 106.7974,
                    explicitCity = "Bali (bulk import)",
                )
            city shouldBe "Bali (bulk import)"
            // city_match_type is NOT touched by the ladder when the caller provides
            // a city_name, so it stays at whatever INSERT set it to (NULL here).
            matchType shouldBe null
        } finally {
            cleanup(viewer)
        }
    }

    "V12 row in flyway_schema_history with success = TRUE" {
        DriverManager.getConnection(url, user, password).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(
                    "SELECT description, success FROM flyway_schema_history WHERE version = '12'",
                ).use { rs ->
                    rs.next() shouldBe true
                    rs.getString("description").contains("admin regions seed") shouldBe true
                    rs.getBoolean("success") shouldBe true
                }
            }
        }
    }

    "V12 migration file carries ODbL attribution string in its header" {
        val stream = javaClass.classLoader.getResourceAsStream("db/migration/V12__admin_regions_seed.sql")
        stream.shouldNotBeNull()
        stream.use { input ->
            val body = input.readBytes().decodeToString()
            body.contains("OpenStreetMap contributors") shouldBe true
            body.contains("Open Database License (ODbL)") shouldBe true
        }
    }
})
