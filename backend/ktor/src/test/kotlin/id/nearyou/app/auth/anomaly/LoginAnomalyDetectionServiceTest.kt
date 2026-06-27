package id.nearyou.app.auth.anomaly

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import id.nearyou.app.auth.anomaly.LoginAnomalyTestSupport.anomalyRow
import id.nearyou.app.auth.anomaly.LoginAnomalyTestSupport.anomalyRowCount
import id.nearyou.app.auth.anomaly.LoginAnomalyTestSupport.cleanup
import id.nearyou.app.auth.anomaly.LoginAnomalyTestSupport.hikari
import id.nearyou.app.auth.anomaly.LoginAnomalyTestSupport.reportsRowExistsForTarget
import id.nearyou.app.auth.anomaly.LoginAnomalyTestSupport.seedLoginEvent
import id.nearyou.app.auth.anomaly.LoginAnomalyTestSupport.seedUser
import id.nearyou.app.auth.anomaly.LoginAnomalyTestSupport.subnetIp
import id.nearyou.app.auth.anomaly.LoginAnomalyTestSupport.tag
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.slf4j.LoggerFactory
import java.sql.SQLException
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.sql.DataSource
import ch.qos.logback.classic.Logger as LogbackLogger

/**
 * Detection + recording semantics for the `auth-login-anomaly-detection` worker —
 * every `#### Scenario:` from the detection / idempotency / fail-soft / PII /
 * scope requirements. Real-Postgres (`@Tags("database")`), mirroring
 * `RetentionCleanupRepositoryTest`. Pool `autoClose(hikari())` size 2 (CI
 * connection budget, docs/11 §3.2); each test cleans up only the rows it seeded.
 *
 * Determinism: the service captures ONE evaluation instant via an injected fixed
 * `nowProvider`, so the trailing-window bound is a stable parameter (no DB
 * `NOW()`). A near-future fixed instant places the window well after any
 * sibling-suite real-now `login_events`, and every assertion is scoped to the
 * test's own seeded `user_id` — so concurrent suites cannot perturb a result.
 */
@Tags("database")
class LoginAnomalyDetectionServiceTest : StringSpec({

    val dataSource = autoClose(hikari())
    val repository = JdbcLoginAnomalyRepository(dataSource)

    // Fixed evaluation instant: window = [NOW-1h, NOW). Near-future so no real-now
    // sibling-suite login_events fall inside it.
    val now: Instant = Instant.parse("2027-01-01T00:00:00Z")
    val inWindow: Instant = now.minus(30, ChronoUnit.MINUTES)
    val expired: Instant = now.minus(90, ChronoUnit.MINUTES)

    fun service(repo: LoginAnomalyRepository = repository) = LoginAnomalyDetectionService(repo, nowProvider = { now })

    suspend fun flaggedFor(userId: UUID): FlaggedUser? =
        repository.findUsersExceedingSubnetThreshold(
            now.minus(LoginAnomalyDetectionService.DETECTION_WINDOW),
            LoginAnomalyDetectionService.DISTINCT_SUBNET_THRESHOLD,
        ).find { it.userId == userId }

    fun setStatus(
        ds: DataSource,
        userId: UUID,
        status: String,
    ) {
        ds.connection.use { conn ->
            conn.prepareStatement(
                "UPDATE moderation_queue SET status = ? WHERE target_type='user' AND target_id = ? AND trigger='anomaly_detection'",
            ).use { ps ->
                ps.setString(1, status)
                ps.setObject(2, userId)
                ps.executeUpdate()
            }
        }
    }

    suspend fun withLogCapture(
        loggerName: String,
        block: suspend (ListAppender<ILoggingEvent>) -> Unit,
    ) {
        val logger = LoggerFactory.getLogger(loggerName) as LogbackLogger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        val previous = logger.level
        logger.level = Level.TRACE
        try {
            block(appender)
        } finally {
            logger.detachAppender(appender)
            logger.level = previous
            appender.stop()
        }
    }

    // ----- 5.1 Threshold boundary + COUNT(DISTINCT)-not-COUNT(*) -----

    "six distinct subnets in the window flags the user (6 > 5)" {
        val u = seedUser(dataSource, tag())
        repeat(6) { i -> seedLoginEvent(dataSource, u, inWindow, subnetIp(i)) }
        try {
            val flagged = flaggedFor(u)
            flagged.shouldNotBeNull()
            flagged.distinctSubnets shouldBe 6
        } finally {
            cleanup(dataSource, listOf(u))
        }
    }

    "exactly five distinct subnets does NOT flag the user (boundary; extra repeats ignored)" {
        val u = seedUser(dataSource, tag())
        // 5 distinct subnets + extra rows repeating those same 5 → COUNT(DISTINCT)=5.
        repeat(5) { i -> seedLoginEvent(dataSource, u, inWindow, subnetIp(i)) }
        repeat(4) { i -> seedLoginEvent(dataSource, u, inWindow, subnetIp(i % 5)) }
        try {
            flaggedFor(u) shouldBe null
        } finally {
            cleanup(dataSource, listOf(u))
        }
    }

    "many rows repeating only three distinct subnets → count is 3 → NOT flagged (COUNT DISTINCT, not COUNT *)" {
        val u = seedUser(dataSource, tag())
        repeat(12) { i -> seedLoginEvent(dataSource, u, inWindow, subnetIp(i % 3)) }
        try {
            flaggedFor(u) shouldBe null
        } finally {
            cleanup(dataSource, listOf(u))
        }
    }

    // ----- 5.2 NULL-subnet exclusion -----

    "five non-NULL distinct subnets plus NULL-subnet rows → count is 5 → NOT flagged" {
        val u = seedUser(dataSource, tag())
        repeat(5) { i -> seedLoginEvent(dataSource, u, inWindow, subnetIp(i)) }
        repeat(3) { seedLoginEvent(dataSource, u, inWindow, ip = null) }
        try {
            flaggedFor(u) shouldBe null
        } finally {
            cleanup(dataSource, listOf(u))
        }
    }

    "six non-NULL distinct subnets plus NULL-subnet rows → count is 6 (NULLs ignored) → flagged" {
        val u = seedUser(dataSource, tag())
        repeat(6) { i -> seedLoginEvent(dataSource, u, inWindow, subnetIp(i)) }
        repeat(3) { seedLoginEvent(dataSource, u, inWindow, ip = null) }
        try {
            flaggedFor(u)?.distinctSubnets shouldBe 6
        } finally {
            cleanup(dataSource, listOf(u))
        }
    }

    // ----- 5.3 Window correctness -----

    "distinct-subnet rows older than the trailing hour are not counted" {
        val u = seedUser(dataSource, tag())
        // 6 distinct subnets but expired (outside the window) + 2 distinct in-window.
        repeat(6) { i -> seedLoginEvent(dataSource, u, expired, subnetIp(i)) }
        seedLoginEvent(dataSource, u, inWindow, subnetIp(100))
        seedLoginEvent(dataSource, u, inWindow, subnetIp(101))
        try {
            flaggedFor(u) shouldBe null
        } finally {
            cleanup(dataSource, listOf(u))
        }
    }

    "boundary-spread: 4 distinct in-window + 3 distinct expired = 4 counted → NOT flagged" {
        val u = seedUser(dataSource, tag())
        repeat(4) { i -> seedLoginEvent(dataSource, u, inWindow, subnetIp(i)) }
        repeat(3) { i -> seedLoginEvent(dataSource, u, expired, subnetIp(200 + i)) }
        try {
            flaggedFor(u) shouldBe null
        } finally {
            cleanup(dataSource, listOf(u))
        }
    }

    "events exactly at the window-start boundary are excluded (strict greater-than)" {
        val u = seedUser(dataSource, tag())
        // 6 distinct subnets all at EXACTLY windowStart (now - 1h). The predicate is
        // `occurred_at > windowStart` (strict), so a row AT the boundary is excluded →
        // distinct count 0 → NOT flagged. Pins `>` (not `>=`). The window start is a
        // whole-second instant, so seed and bound param compare exactly (no sub-second
        // clock skew).
        val windowStart = now.minus(LoginAnomalyDetectionService.DETECTION_WINDOW)
        repeat(6) { i -> seedLoginEvent(dataSource, u, windowStart, subnetIp(i)) }
        try {
            flaggedFor(u) shouldBe null
        } finally {
            cleanup(dataSource, listOf(u))
        }
    }

    // ----- 5.5 Recorded-row shape -----

    "a flagged user yields exactly one pending user/anomaly_detection moderation_queue row" {
        val u = seedUser(dataSource, tag())
        repeat(6) { i -> seedLoginEvent(dataSource, u, inWindow, subnetIp(i)) }
        try {
            // Sibling suites may also flag unrelated users (the global sweep records
            // them too), so assert on OUR user's row only, not the aggregate counts.
            service().sweep()
            anomalyRowCount(dataSource, u) shouldBe 1
            val row = anomalyRow(dataSource, u)
            row.shouldNotBeNull()
            row.status shouldBe "pending"
        } finally {
            cleanup(dataSource, listOf(u))
        }
    }

    // ----- 5.4 Idempotency -----

    "two consecutive sweeps over a still-flagged user produce exactly one row" {
        val u = seedUser(dataSource, tag())
        repeat(6) { i -> seedLoginEvent(dataSource, u, inWindow, subnetIp(i)) }
        try {
            service().sweep()
            service().sweep()
            anomalyRowCount(dataSource, u) shouldBe 1
        } finally {
            cleanup(dataSource, listOf(u))
        }
    }

    "an already-resolved anomaly row suppresses re-enqueue and is left resolved" {
        val u = seedUser(dataSource, tag())
        repeat(6) { i -> seedLoginEvent(dataSource, u, inWindow, subnetIp(i)) }
        try {
            service().sweep()
            setStatus(dataSource, u, "resolved")
            service().sweep()
            anomalyRowCount(dataSource, u) shouldBe 1
            anomalyRow(dataSource, u)?.status shouldBe "resolved"
        } finally {
            cleanup(dataSource, listOf(u))
        }
    }

    // ----- 5.6 No-PII discipline (notes + success/error log lines) -----

    "notes and the success-path log line carry no IP / subnet / identifier-hash value" {
        val u = seedUser(dataSource, tag())
        // Seed a recognizable identifier_hash sentinel alongside the distinct subnets;
        // the detector never reads it, so it must never reach notes or any log line.
        val hashSentinel = "IDHASH_SENTINEL_DEADBEEF"
        repeat(6) { i -> seedLoginEvent(dataSource, u, inWindow, subnetIp(i), identifierHash = hashSentinel) }
        try {
            withLogCapture("id.nearyou.app.auth.anomaly.LoginAnomalyDetectionService") { appender ->
                service().sweep()
                val logs = appender.list.joinToString("\n") { it.formattedMessage }
                // Neither the seeded subnet prefix nor the identifier hash may appear.
                logs shouldNotContain "10.0."
                logs shouldNotContain hashSentinel
            }
            val notes = anomalyRow(dataSource, u)?.notes
            notes.shouldNotBeNull()
            notes shouldNotContain "10.0."
            notes shouldNotContain hashSentinel
        } finally {
            cleanup(dataSource, listOf(u))
        }
    }

    "the fail-soft error-path log line carries no IP / subnet value" {
        val u = seedUser(dataSource, tag())
        val throwingRepo =
            object : LoginAnomalyRepository {
                override suspend fun findUsersExceedingSubnetThreshold(
                    windowStart: Instant,
                    threshold: Int,
                ): List<FlaggedUser> = listOf(FlaggedUser(u, distinctSubnets = 7))

                override suspend fun recordAnomaly(
                    userId: UUID,
                    notes: String,
                ): Boolean = throw SQLException("simulated insert failure 10.0.7.1 must-not-leak")
            }
        withLogCapture("id.nearyou.app.auth.anomaly.LoginAnomalyDetectionService") { appender ->
            val result = service(throwingRepo).sweep()
            result.failed shouldBe 1
            result.recorded shouldBe 0
            val logs = appender.list.joinToString("\n") { it.formattedMessage }
            // The formatted MESSAGE must carry no IP/subnet (the exception cause is
            // attached as a throwable, not interpolated into the message).
            logs shouldNotContain "10.0."
        }
    }

    // ----- 5.7 Fail-soft sweep -----

    "with three flagged users where the second insert throws, the first and third are still recorded" {
        val ta = tag()
        val tb = tag()
        val tc = tag()
        val a = seedUser(dataSource, ta)
        val b = seedUser(dataSource, tb)
        val c = seedUser(dataSource, tc)
        // Deterministic fixed flagged-set (no DB read) so only a/b/c are processed;
        // recordAnomaly delegates to the REAL repo except it throws for user b.
        val failingRepo =
            object : LoginAnomalyRepository {
                override suspend fun findUsersExceedingSubnetThreshold(
                    windowStart: Instant,
                    threshold: Int,
                ): List<FlaggedUser> = listOf(FlaggedUser(a, 6), FlaggedUser(b, 6), FlaggedUser(c, 6))

                override suspend fun recordAnomaly(
                    userId: UUID,
                    notes: String,
                ): Boolean {
                    if (userId == b) throw SQLException("simulated insert failure for user b")
                    return repository.recordAnomaly(userId, notes)
                }
            }
        try {
            val result = service(failingRepo).sweep()
            result.flagged shouldBe 3
            result.recorded shouldBe 2
            result.failed shouldBe 1
            anomalyRowCount(dataSource, a) shouldBe 1
            anomalyRowCount(dataSource, b) shouldBe 0
            anomalyRowCount(dataSource, c) shouldBe 1
        } finally {
            cleanup(dataSource, listOf(a, b, c))
        }
    }

    // ----- 5.11 Scope + output guard -----

    "the sweep's only persisted anomaly output is the moderation_queue row (no reports row)" {
        val u = seedUser(dataSource, tag())
        repeat(6) { i -> seedLoginEvent(dataSource, u, inWindow, subnetIp(i)) }
        try {
            service().sweep()
            anomalyRowCount(dataSource, u) shouldBe 1
            // The service has NO Sentry/Slack/alert-dispatch collaborator (structural —
            // no such constructor param exists) and writes NO reports row for the anomaly.
            reportsRowExistsForTarget(dataSource, u) shouldBe false
        } finally {
            cleanup(dataSource, listOf(u))
        }
    }
})
