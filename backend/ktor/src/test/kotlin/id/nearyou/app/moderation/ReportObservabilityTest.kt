package id.nearyou.app.moderation

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.infra.repo.JdbcModerationQueueRepository
import id.nearyou.app.infra.repo.JdbcPostAutoHideRepository
import id.nearyou.app.infra.repo.JdbcReportRepository
import id.nearyou.data.repository.ReportReasonCategory
import id.nearyou.data.repository.ReportTargetType
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.slf4j.LoggerFactory
import java.sql.Date
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import ch.qos.logback.classic.Logger as LogbackLogger

/**
 * Observability non-regression for the V9 `auto_hide_triggered` log line.
 *
 * Pairs the structured-log emit site in [ReportService] with a logback
 * `ListAppender` attached to that service's logger during each scenario. The
 * message format asserted here matches the slf4j call at ReportService:95:
 * `"event=auto_hide_triggered target_type={} target_id={} reporter_count={}"`.
 *
 * Covers spec requirement "Observability — structured log + Sentry breadcrumb
 * on auto-hide fire" scenarios:
 *  - 6.19 Threshold crossing emits the line.
 *  - 6.20 Sub-threshold (1st / 2nd reporter) does NOT emit.
 *  - 6.21 4th+ reporter on already-hidden target does NOT emit.
 *
 * The Sentry-breadcrumb half of the spec is not exercised here — Sentry is not
 * on the JVM classpath yet (it wires up in a later observability change); the
 * `auto_hide_triggered` log line is the emission site that will feed both the
 * structured-log dashboard and, once wired, the Sentry breadcrumb.
 */
@Tags("database")
class ReportObservabilityTest : StringSpec({

    val dataSource = hikari()

    fun seedUser(createdAt: Instant? = null): UUID {
        val id = UUID.randomUUID()
        val short = id.toString().replace("-", "").take(8)
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO users (id, username, display_name, date_of_birth, invite_code_prefix, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "ob_$short")
                ps.setString(3, "Observability Tester")
                ps.setDate(4, Date.valueOf(LocalDate.of(1990, 1, 1)))
                ps.setString(5, "o${short.take(7)}")
                ps.setTimestamp(6, Timestamp.from(createdAt ?: Instant.now()))
                ps.executeUpdate()
            }
        }
        return id
    }

    fun seedAgedUser() = seedUser(createdAt = Instant.now().minus(Duration.ofDays(30)))

    fun seedPost(
        author: UUID,
        autoHidden: Boolean = false,
    ): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO posts (
                    id, author_id, content, display_location, actual_location, is_auto_hidden
                ) VALUES (?, ?, ?,
                  ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                  ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                  ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setObject(2, author)
                ps.setString(3, "ob-${id.toString().take(6)}")
                ps.setDouble(4, 106.8)
                ps.setDouble(5, -6.2)
                ps.setDouble(6, 106.8)
                ps.setDouble(7, -6.2)
                ps.setBoolean(8, autoHidden)
                ps.executeUpdate()
            }
        }
        return id
    }

    fun directInsertReport(
        reporter: UUID,
        targetType: String,
        targetId: UUID,
    ) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "INSERT INTO reports (reporter_id, target_type, target_id, reason_category) VALUES (?, ?, ?, 'spam')",
            ).use { ps ->
                ps.setObject(1, reporter)
                ps.setString(2, targetType)
                ps.setObject(3, targetId)
                ps.executeUpdate()
            }
        }
    }

    fun cleanup(vararg ids: UUID) {
        dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                ids.forEach {
                    st.executeUpdate("DELETE FROM moderation_queue WHERE target_id = '$it'")
                    st.executeUpdate("DELETE FROM reports WHERE target_id = '$it'")
                    st.executeUpdate("DELETE FROM posts WHERE id = '$it' OR author_id = '$it'")
                    st.executeUpdate("DELETE FROM users WHERE id = '$it'")
                }
            }
        }
    }

    fun buildService(): ReportService =
        ReportService(
            dataSource = dataSource,
            reports = JdbcReportRepository(),
            moderationQueue = JdbcModerationQueueRepository(),
            postAutoHide = JdbcPostAutoHideRepository(),
            rateLimiter = ReportRateLimiter(),
        )

    fun withLogCapture(block: (ListAppender<ILoggingEvent>) -> Unit) {
        val logger = LoggerFactory.getLogger(ReportService::class.java) as LogbackLogger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        // Ensure INFO is propagated — root may be set higher in some test envs.
        val previousLevel = logger.level
        logger.level = Level.INFO
        try {
            block(appender)
        } finally {
            logger.detachAppender(appender)
            logger.level = previousLevel
            appender.stop()
        }
    }

    fun ListAppender<ILoggingEvent>.autoHideLines(): List<String> =
        list.map { it.formattedMessage }
            .filter { it.contains("event=auto_hide_triggered") }

    // --- 6.19 threshold crossing ---

    "6.19 threshold crossing — log line emitted with target_type, target_id, reporter_count>=3" {
        val r1 = seedAgedUser()
        val r2 = seedAgedUser()
        val r3 = seedAgedUser()
        val author = seedUser()
        val p = seedPost(author)
        try {
            directInsertReport(r1, "post", p)
            directInsertReport(r2, "post", p)
            val service = buildService()
            withLogCapture { appender ->
                service.submit(
                    reporterId = r3,
                    targetType = ReportTargetType.POST,
                    targetId = p,
                    reasonCategory = ReportReasonCategory.SPAM,
                    reasonNote = null,
                )
                val hits = appender.autoHideLines()
                hits.size shouldBe 1
                val msg = hits.single()
                msg.contains("target_type=post") shouldBe true
                msg.contains("target_id=$p") shouldBe true
                // reporter_count should be >= 3 (the COUNT-DISTINCT result that crossed the threshold)
                val count = Regex("""reporter_count=(\d+)""").find(msg)!!.groupValues[1].toInt()
                (count >= 3) shouldBe true
            }
        } finally {
            cleanup(r1, r2, r3, author)
        }
    }

    // --- 6.20 sub-threshold (1st / 2nd reporter) ---

    "6.20 sub-threshold — 1st and 2nd aged reporter emit no auto_hide_triggered line" {
        val r1 = seedAgedUser()
        val r2 = seedAgedUser()
        val author = seedUser()
        val p = seedPost(author)
        try {
            val service = buildService()
            withLogCapture { appender ->
                service.submit(
                    reporterId = r1,
                    targetType = ReportTargetType.POST,
                    targetId = p,
                    reasonCategory = ReportReasonCategory.SPAM,
                    reasonNote = null,
                )
                service.submit(
                    reporterId = r2,
                    targetType = ReportTargetType.POST,
                    targetId = p,
                    reasonCategory = ReportReasonCategory.SPAM,
                    reasonNote = null,
                )
                appender.autoHideLines().size shouldBe 0
            }
        } finally {
            cleanup(r1, r2, author)
        }
    }

    // --- 6.21 4th+ reporter on already-hidden target ---

    "6.21 4th+ reporter on already-hidden target emits no new auto_hide_triggered line" {
        val r1 = seedAgedUser()
        val r2 = seedAgedUser()
        val r3 = seedAgedUser()
        val r4 = seedAgedUser()
        val author = seedUser()
        val p = seedPost(author, autoHidden = true)
        try {
            // pre-seed 3 reports + queue row so the target is already at "threshold already crossed".
            directInsertReport(r1, "post", p)
            directInsertReport(r2, "post", p)
            directInsertReport(r3, "post", p)
            dataSource.connection.use { c ->
                c.prepareStatement(
                    "INSERT INTO moderation_queue (target_type, target_id, trigger) VALUES ('post', ?, 'auto_hide_3_reports')",
                ).use { ps ->
                    ps.setObject(1, p)
                    ps.executeUpdate()
                }
            }
            val service = buildService()
            withLogCapture { appender ->
                service.submit(
                    reporterId = r4,
                    targetType = ReportTargetType.POST,
                    targetId = p,
                    reasonCategory = ReportReasonCategory.SPAM,
                    reasonNote = null,
                )
                // Service only logs on FRESH queue-row insert — the 4th reporter's
                // INSERT ... ON CONFLICT DO NOTHING hits the conflict branch, so
                // `insertedQueue == false` and the log line does not fire.
                appender.autoHideLines().size shouldBe 0
            }
        } finally {
            cleanup(r1, r2, r3, r4, author)
        }
    }
})

private fun hikari(): HikariDataSource {
    val url = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5433/nearyou_dev"
    val user = System.getenv("DB_USER") ?: "postgres"
    val password = System.getenv("DB_PASSWORD") ?: "postgres"
    val config =
        HikariConfig().apply {
            jdbcUrl = url
            username = user
            this.password = password
            maximumPoolSize = 4
            initializationFailTimeout = -1
        }
    return HikariDataSource(config)
}
