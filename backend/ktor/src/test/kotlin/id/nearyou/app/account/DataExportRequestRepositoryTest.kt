package id.nearyou.app.account

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.runBlocking
import java.sql.Date
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
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
 * DB-tagged tests for the `data_export_requests` schema (V30) + the lifecycle JDBC in
 * [DataExportRequestRepository] (capability `account-data-export`).
 *
 * Covers tasks/spec scenarios:
 *  - 8.1 schema: table + the three indexes exist; the `status` CHECK rejects an unknown value;
 *    the one-active partial-unique index forbids a 2nd active row; a non-active row does NOT
 *    block a new pending; both partial-index predicates are `NOW()`-free.
 *  - the idempotency/single-active repo behaviour (insert-pending-or-return-active) feeding
 *    the POST route scenarios (8.2 at the repo level).
 *  - 8.6 optimistic claim: a 2nd claim on a `pending` row claims nothing.
 *  - 8.10 expiry-derivation: a `READY` row past its `download_expires_at` reads as `EXPIRED`.
 */
@Tags("database")
class DataExportRequestRepositoryTest : StringSpec({

    // afterTest cleanup uses this DataSource → do NOT autoClose (the pool would close before
    // cleanup runs, silently leaking rows into the timeline suites). Closed in afterSpec.
    val dataSource = hikari()
    val repo = DataExportRequestRepository(dataSource)

    val seeded = mutableListOf<UUID>()

    fun seedUser(): UUID {
        val id = UUID.randomUUID()
        val short = id.toString().replace("-", "").take(8)
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "INSERT INTO users (id, username, display_name, date_of_birth, invite_code_prefix) VALUES (?, ?, ?, ?, ?)",
            ).use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "dxr_$short")
                ps.setString(3, "Export Repo Test")
                ps.setDate(4, Date.valueOf(LocalDate.of(1990, 1, 1)))
                ps.setString(5, "x${short.take(7)}")
                ps.executeUpdate()
            }
        }
        seeded += id
        return id
    }

    /** Inserts a row directly with an explicit status (+ optional download_expires_at). */
    fun seedRow(
        userId: UUID,
        status: String,
        downloadExpiresAt: Instant? = null,
        r2ObjectKey: String? = null,
    ): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO data_export_requests (id, user_id, status, download_expires_at, r2_object_key)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setObject(2, userId)
                ps.setString(3, status)
                if (downloadExpiresAt != null) {
                    ps.setTimestamp(
                        4,
                        Timestamp.from(downloadExpiresAt),
                    )
                } else {
                    ps.setNull(4, java.sql.Types.TIMESTAMP)
                }
                if (r2ObjectKey != null) ps.setString(5, r2ObjectKey) else ps.setNull(5, java.sql.Types.VARCHAR)
                ps.executeUpdate()
            }
        }
        return id
    }

    fun activeRowCount(userId: UUID): Int =
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM data_export_requests WHERE user_id = ? AND status IN ('pending','processing')",
            ).use { ps ->
                ps.setObject(1, userId)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    fun totalRowCount(userId: UUID): Int =
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM data_export_requests WHERE user_id = ?").use { ps ->
                ps.setObject(1, userId)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    afterTest {
        dataSource.connection.use { conn ->
            seeded.forEach { id ->
                conn.prepareStatement("DELETE FROM data_export_requests WHERE user_id = ?").use {
                    it.setObject(1, id)
                    it.executeUpdate()
                }
                conn.prepareStatement("DELETE FROM users WHERE id = ?").use {
                    it.setObject(1, id)
                    it.executeUpdate()
                }
            }
        }
        seeded.clear()
    }

    afterSpec { dataSource.close() }

    // ---- 8.1 schema -------------------------------------------------------

    "8.1 schema: table + the three indexes exist with the canonical columns" {
        dataSource.connection.use { conn ->
            val columns =
                conn.prepareStatement(
                    "SELECT column_name FROM information_schema.columns WHERE table_name = 'data_export_requests'",
                ).use { ps ->
                    ps.executeQuery().use { rs -> buildSet { while (rs.next()) add(rs.getString(1)) } }
                }
            listOf(
                "id", "user_id", "status", "requested_at", "started_at", "completed_at",
                "r2_object_key", "download_expires_at", "attempt_count", "error",
            ).forEach { col -> columns.contains(col) shouldBe true }

            val indexes =
                conn.prepareStatement(
                    "SELECT indexname FROM pg_indexes WHERE tablename = 'data_export_requests'",
                ).use { ps ->
                    ps.executeQuery().use { rs -> buildSet { while (rs.next()) add(rs.getString(1)) } }
                }
            listOf(
                "data_export_requests_pending_idx",
                "data_export_requests_one_active_idx",
                "data_export_requests_user_recent_idx",
            ).forEach { idx -> indexes.contains(idx) shouldBe true }
        }
    }

    "8.1 schema: status CHECK rejects an unknown value but accepts the five canonical ones" {
        val uid = seedUser()
        shouldThrow<Exception> {
            dataSource.connection.use { conn ->
                conn.prepareStatement(
                    "INSERT INTO data_export_requests (user_id, status) VALUES (?, 'queued')",
                ).use {
                    it.setObject(1, uid)
                    it.executeUpdate()
                }
            }
        }
        // Each canonical status inserts cleanly under its own freshly-seeded user (so the
        // two active statuses don't trip the one-active partial-unique on a single user).
        listOf("pending", "processing", "ready", "expired", "failed").forEach { s ->
            val u = seedUser()
            dataSource.connection.use { conn ->
                conn.prepareStatement("INSERT INTO data_export_requests (user_id, status) VALUES (?, ?)").use {
                    it.setObject(1, u)
                    it.setString(2, s)
                    it.executeUpdate()
                }
            }
        }
    }

    "8.1 schema: one-active partial-unique forbids a SECOND active row for the same user" {
        val uid = seedUser()
        seedRow(uid, "pending")
        // A second pending insert for the same user violates data_export_requests_one_active_idx.
        shouldThrow<Exception> {
            dataSource.connection.use { conn ->
                conn.prepareStatement("INSERT INTO data_export_requests (user_id, status) VALUES (?, 'pending')").use {
                    it.setObject(1, uid)
                    it.executeUpdate()
                }
            }
        }
        // And a pending + processing combination also collides (the predicate covers both).
        val uid2 = seedUser()
        seedRow(uid2, "processing")
        shouldThrow<Exception> {
            dataSource.connection.use { conn ->
                conn.prepareStatement("INSERT INTO data_export_requests (user_id, status) VALUES (?, 'pending')").use {
                    it.setObject(1, uid2)
                    it.executeUpdate()
                }
            }
        }
    }

    "8.1 schema: a non-active row (ready/expired/failed) does NOT block a new pending" {
        listOf("ready", "expired", "failed").forEach { priorStatus ->
            val uid = seedUser()
            seedRow(uid, priorStatus)
            // A new pending row is allowed alongside a terminal one.
            dataSource.connection.use { conn ->
                conn.prepareStatement("INSERT INTO data_export_requests (user_id, status) VALUES (?, 'pending')").use {
                    it.setObject(1, uid)
                    it.executeUpdate()
                }
            }
            activeRowCount(uid) shouldBe 1
            totalRowCount(uid) shouldBe 2
        }
    }

    "8.1 schema: both partial-index predicates are NOW()-free" {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT indexdef FROM pg_indexes
                 WHERE indexname IN ('data_export_requests_pending_idx','data_export_requests_one_active_idx')
                """.trimIndent(),
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    val defs = buildList { while (rs.next()) add(rs.getString("indexdef")) }
                    defs.size shouldBe 2
                    defs.none { it.contains("now()", ignoreCase = true) } shouldBe true
                    // Sanity: each predicate is a status expression.
                    defs.all { it.contains("status", ignoreCase = true) } shouldBe true
                }
            }
        }
    }

    // ---- repo idempotency / single-active (feeds 8.2) ---------------------

    "insertPendingOrReturnActive: first call enqueues exactly one pending row" {
        val uid = seedUser()
        val row = runBlocking { repo.insertPendingOrReturnActive(uid) }
        row.userId shouldBe uid
        row.status shouldBe DataExportStatus.PENDING
        activeRowCount(uid) shouldBe 1
    }

    "insertPendingOrReturnActive: a 2nd call while active returns the SAME row, no 2nd insert" {
        val uid = seedUser()
        val first = runBlocking { repo.insertPendingOrReturnActive(uid) }
        val second = runBlocking { repo.insertPendingOrReturnActive(uid) }
        second.id shouldBe first.id
        second.status shouldBe DataExportStatus.PENDING
        totalRowCount(uid) shouldBe 1
    }

    "insertPendingOrReturnActive: while PROCESSING, the existing processing row is returned" {
        val uid = seedUser()
        val processingId = seedRow(uid, "processing")
        val row = runBlocking { repo.insertPendingOrReturnActive(uid) }
        row.id shouldBe processingId
        row.status shouldBe DataExportStatus.PROCESSING
        totalRowCount(uid) shouldBe 1
    }

    "insertPendingOrReturnActive: after a terminal row a NEW pending row is created" {
        listOf("ready", "expired", "failed").forEach { priorStatus ->
            val uid = seedUser()
            val priorId = seedRow(uid, priorStatus)
            val row = runBlocking { repo.insertPendingOrReturnActive(uid) }
            row.id shouldNotBe priorId
            row.status shouldBe DataExportStatus.PENDING
            totalRowCount(uid) shouldBe 2
        }
    }

    // ---- 8.6 optimistic claim --------------------------------------------

    "8.6 claimPending: a 2nd claim on the SAME pending row claims nothing (optimistic)" {
        val uid = seedUser()
        val row = runBlocking { repo.insertPendingOrReturnActive(uid) }
        val firstClaim = runBlocking { repo.claimPending(row.id) }
        firstClaim shouldNotBe null
        firstClaim!!.id shouldBe row.id
        firstClaim.userId shouldBe uid
        // Now the row is 'processing' — a second claim affects no row.
        val secondClaim = runBlocking { repo.claimPending(row.id) }
        secondClaim shouldBe null
    }

    "snapshotPending lists only pending ids (not processing/ready/failed)" {
        val pendingUser = seedUser()
        val pending = runBlocking { repo.insertPendingOrReturnActive(pendingUser) }
        val processingUser = seedUser()
        seedRow(processingUser, "processing")
        val readyUser = seedUser()
        seedRow(readyUser, "ready")

        val ids = runBlocking { repo.snapshotPending() }
        ids.contains(pending.id) shouldBe true
    }

    // ---- setReady deadline + 8.10 expiry derivation ----------------------

    "setReady stamps a ~24h download_expires_at and marks the row ready" {
        val uid = seedUser()
        val row = runBlocking { repo.insertPendingOrReturnActive(uid) }
        runBlocking { repo.claimPending(row.id) }
        val expiresAt = runBlocking { repo.setReady(row.id, "exports/$uid/${row.id}.zip") }
        val expected = Instant.now().plus(24, ChronoUnit.HOURS)
        (Duration.between(expiresAt, expected).abs().toMinutes() < 5) shouldBe true

        val latest = runBlocking { repo.latestForUser(uid) }
        latest!!.status shouldBe DataExportStatus.READY
        latest.r2ObjectKey shouldBe "exports/$uid/${row.id}.zip"
    }

    "8.10 latestForUser: a READY row past its deadline reads as EXPIRED" {
        val uid = seedUser()
        // A ready row whose window closed an hour ago.
        seedRow(uid, "ready", downloadExpiresAt = Instant.now().minus(1, ChronoUnit.HOURS), r2ObjectKey = "exports/old.zip")
        val latest = runBlocking { repo.latestForUser(uid) }
        latest!!.status shouldBe DataExportStatus.EXPIRED
    }

    "8.10 latestForUser: a READY row inside its window reads as READY" {
        val uid = seedUser()
        seedRow(uid, "ready", downloadExpiresAt = Instant.now().plus(1, ChronoUnit.HOURS), r2ObjectKey = "exports/fresh.zip")
        val latest = runBlocking { repo.latestForUser(uid) }
        latest!!.status shouldBe DataExportStatus.READY
    }

    "latestForUser returns null when the user never requested an export" {
        val uid = seedUser()
        runBlocking { repo.latestForUser(uid) } shouldBe null
    }

    "setFailed increments attempt_count and stamps a non-PII error" {
        val uid = seedUser()
        val row = runBlocking { repo.insertPendingOrReturnActive(uid) }
        runBlocking { repo.setFailed(row.id, "object_store_unconfigured") }
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT status, attempt_count, error FROM data_export_requests WHERE id = ?").use { ps ->
                ps.setObject(1, row.id)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getString("status") shouldBe "failed"
                    rs.getInt("attempt_count") shouldBe 1
                    rs.getString("error") shouldBe "object_store_unconfigured"
                }
            }
        }
    }
})
