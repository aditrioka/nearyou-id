package id.nearyou.app.admin.routes

import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.admin.auth.AdminAuthTestSupport
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

/**
 * Data-level coverage of the Operational Dashboard aggregates
 * (`admin-operational-dashboard`). DB-backed; tagged `database`.
 *
 * Determinism: the repository is driven by a fixedNow clock far in the future
 * (`fixedNow`), and every operational widget is time-windowed (last-24h /
 * last-7d) ending at that instant. Real dev-DB rows (created "now", ~decades
 * earlier) fall outside the window, so the windowed counts see ONLY this
 * test's seeded rows — no global truncation needed, immune to seed pollution.
 * Each test cleans up exactly the rows it seeded (posts/reports before users
 * for the `ON DELETE RESTRICT` FK).
 */
@Tags("database")
class AdminIndexStatsRepositoryTest : StringSpec({

    val dataSource: HikariDataSource = AdminAuthTestSupport.hikari()
    afterSpec { dataSource.close() }

    val fixedNow: Instant = Instant.parse("2099-06-15T12:00:00Z")
    val repo = AdminIndexStatsRepository(dataSource, clock = { fixedNow })

    val postIds = mutableListOf<UUID>()
    val reportIds = mutableListOf<UUID>()
    val userIds = mutableListOf<UUID>()
    val rejectedHashes = mutableListOf<String>()
    afterEach {
        cleanup(dataSource, postIds = postIds, reportIds = reportIds, userIds = userIds, rejectedHashes = rejectedHashes)
        postIds.clear()
        reportIds.clear()
        userIds.clear()
        rejectedHashes.clear()
    }

    fun user(
        createdAt: Instant = fixedNow,
        shadowBanned: Boolean = false,
    ): UUID = seedUser(dataSource, createdAt, shadowBanned).also { userIds.add(it) }

    fun post(
        author: UUID,
        createdAt: Instant,
        cityName: String? = null,
        autoHidden: Boolean = false,
    ): UUID = seedPost(dataSource, author, createdAt, cityName, autoHidden).also { postIds.add(it) }

    "posts-volume counts ALL posts in the window incl. shadow-banned authors and auto-hidden" {
        val author = user()
        val shadowAuthor = user(shadowBanned = true)
        repeat(5) { post(author, fixedNow.minusSeconds(3600L * (it + 1))) } // 5 normal, -1h..-5h
        repeat(2) { post(shadowAuthor, fixedNow.minusSeconds(3600L * (it + 1))) } // 2 by shadow author
        post(author, fixedNow.minusSeconds(3600), autoHidden = true) // 1 auto-hidden
        post(author, fixedNow.minusSeconds(25 * 3600)) // 1 outside the 24 h window

        repo.load().postsLast24h shouldBe 8L
    }

    "posts per-hour series is a dense 24-bucket, zero-filled trend" {
        val author = user()
        repeat(2) { post(author, fixedNow.minusSeconds(2 * 3600)) } // bucket 21 (current hour − 2)
        post(author, fixedNow.minusSeconds(5 * 3600)) // bucket 18 (current hour − 5)

        val series = repo.load().postsByHour
        series shouldHaveSize 24
        series[21] shouldBe 2L
        series[18] shouldBe 1L
        series.sum() shouldBe 3L // every other bucket is zero-filled
    }

    "signups and age-gate counts reflect the last-24h window" {
        repeat(4) { user(createdAt = fixedNow.minusSeconds(3600L * (it + 1))) } // 4 in window
        user(createdAt = fixedNow.minusSeconds(25 * 3600)) // 1 outside the window
        repeat(3) { rejected(dataSource, "age_under_18", fixedNow.minusSeconds(3600L * (it + 1))).also { h -> rejectedHashes.add(h) } }
        repeat(2) {
            rejected(dataSource, "attestation_persistent_fail", fixedNow.minusSeconds(3600L * (it + 1))).also {
                    h ->
                rejectedHashes.add(h)
            }
        }
        rejected(dataSource, "age_under_18", fixedNow.minusSeconds(25 * 3600)).also { rejectedHashes.add(it) } // outside window

        val stats = repo.load()
        stats.signupsLast24h shouldBe 4L
        stats.ageGateRejections24h shouldBe 3L // attestation rows + the 25 h-old row excluded
    }

    "reports-volume counts reports created in the window" {
        val reporter = user()
        repeat(6) { report(dataSource, reporter, fixedNow.minusSeconds(3600L * (it + 1))).also { id -> reportIds.add(id) } }
        report(dataSource, reporter, fixedNow.minusSeconds(25 * 3600)).also { reportIds.add(it) } // outside window

        repo.load().reportsLast24h shouldBe 6L
    }

    "top cities ranks by post count with an alphabetical tie-break" {
        val author = user()
        val twoDaysAgo = fixedNow.minusSeconds(2 * 24 * 3600) // inside the 7-day window
        repeat(3) { post(author, twoDaysAgo, cityName = "Jakarta") }
        repeat(2) { post(author, twoDaysAgo, cityName = "Bandung") }
        repeat(2) { post(author, twoDaysAgo, cityName = "Surabaya") }
        post(author, twoDaysAgo, cityName = null) // NULL city_name → excluded

        // Bandung precedes Surabaya: the 2–2 tie breaks alphabetically.
        repo.load().topCities shouldBe
            listOf(
                AdminIndexStatsRepository.CityCount("Jakarta", 3L),
                AdminIndexStatsRepository.CityCount("Bandung", 2L),
                AdminIndexStatsRepository.CityCount("Surabaya", 2L),
            )
    }

    "top cities is empty when no windowed post carries a city_name" {
        val author = user()
        repeat(2) { post(author, fixedNow.minusSeconds(24 * 3600), cityName = null) }

        repo.load().topCities.shouldHaveSize(0)
    }

    "database size is reported as a positive byte count" {
        val size = repo.load().dbSizeBytes
        size.shouldNotBeNull()
        (size > 0L) shouldBe true
    }
})

private fun seedUser(
    dataSource: DataSource,
    createdAt: Instant,
    shadowBanned: Boolean,
): UUID {
    val id = UUID.randomUUID()
    val short = id.toString().replace("-", "").take(8)
    dataSource.connection.use { conn ->
        conn.prepareStatement(
            """
            INSERT INTO users (
                id, username, display_name, date_of_birth, invite_code_prefix, is_shadow_banned, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, id)
            ps.setString(2, "dash_$short")
            ps.setString(3, "Dash $short")
            ps.setObject(4, LocalDate.of(1995, 1, 1))
            ps.setString(5, short)
            ps.setBoolean(6, shadowBanned)
            ps.setTimestamp(7, Timestamp.from(createdAt))
            ps.executeUpdate()
        }
    }
    return id
}

private fun seedPost(
    dataSource: DataSource,
    authorId: UUID,
    createdAt: Instant,
    cityName: String?,
    autoHidden: Boolean,
): UUID {
    val id = UUID.randomUUID()
    dataSource.connection.use { conn ->
        conn.prepareStatement(
            """
            INSERT INTO posts (id, author_id, content, display_location, actual_location, is_auto_hidden, created_at)
            VALUES (?, ?, 'dashboard test post',
                    ST_GeographyFromText('SRID=4326;POINT(106.8 -6.2)'),
                    ST_GeographyFromText('SRID=4326;POINT(106.8 -6.2)'), ?, ?)
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, id)
            ps.setObject(2, authorId)
            ps.setBoolean(3, autoHidden)
            ps.setTimestamp(4, Timestamp.from(createdAt))
            ps.executeUpdate()
        }
        // The BEFORE INSERT `posts_set_city_tg` trigger derives city_name from
        // the location; override it here so the test is independent of the
        // polygon seed (UPDATE does not re-fire the INSERT-only trigger).
        conn.prepareStatement("UPDATE posts SET city_name = ? WHERE id = ?").use { ps ->
            ps.setString(1, cityName)
            ps.setObject(2, id)
            ps.executeUpdate()
        }
    }
    return id
}

private fun report(
    dataSource: DataSource,
    reporterId: UUID,
    createdAt: Instant,
): UUID {
    val id = UUID.randomUUID()
    dataSource.connection.use { conn ->
        conn.prepareStatement(
            """
            INSERT INTO reports (id, reporter_id, target_type, target_id, reason_category, status, created_at)
            VALUES (?, ?, 'post', ?, 'spam', 'pending', ?)
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, id)
            ps.setObject(2, reporterId)
            ps.setObject(3, UUID.randomUUID())
            ps.setTimestamp(4, Timestamp.from(createdAt))
            ps.executeUpdate()
        }
    }
    return id
}

private fun rejected(
    dataSource: DataSource,
    reason: String,
    rejectedAt: Instant,
): String {
    val hash = "dash-rep-${UUID.randomUUID()}"
    dataSource.connection.use { conn ->
        conn.prepareStatement(
            """
            INSERT INTO rejected_identifiers (identifier_hash, identifier_type, reason, rejected_at)
            VALUES (?, 'google', ?, ?)
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, hash)
            ps.setString(2, reason)
            ps.setTimestamp(3, Timestamp.from(rejectedAt))
            ps.executeUpdate()
        }
    }
    return hash
}

private fun cleanup(
    dataSource: DataSource,
    postIds: List<UUID>,
    reportIds: List<UUID>,
    userIds: List<UUID>,
    rejectedHashes: List<String>,
) {
    dataSource.connection.use { conn ->
        // posts + reports reference users (ON DELETE RESTRICT / FK) → delete them first.
        postIds.forEach { id ->
            conn.prepareStatement("DELETE FROM posts WHERE id = ?").use { ps ->
                ps.setObject(1, id)
                ps.executeUpdate()
            }
        }
        reportIds.forEach { id ->
            conn.prepareStatement("DELETE FROM reports WHERE id = ?").use { ps ->
                ps.setObject(1, id)
                ps.executeUpdate()
            }
        }
        rejectedHashes.forEach { h ->
            conn.prepareStatement("DELETE FROM rejected_identifiers WHERE identifier_hash = ?").use { ps ->
                ps.setString(1, h)
                ps.executeUpdate()
            }
        }
        userIds.forEach { id ->
            conn.prepareStatement("DELETE FROM users WHERE id = ?").use { ps ->
                ps.setObject(1, id)
                ps.executeUpdate()
            }
        }
    }
}
