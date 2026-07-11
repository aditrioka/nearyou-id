package id.nearyou.app.admin.retention

import id.nearyou.app.admin.retention.RetentionCleanupTestSupport.cleanup
import id.nearyou.app.admin.retention.RetentionCleanupTestSupport.deleteRows
import id.nearyou.app.admin.retention.RetentionCleanupTestSupport.fcmTokenExists
import id.nearyou.app.admin.retention.RetentionCleanupTestSupport.hikari
import id.nearyou.app.admin.retention.RetentionCleanupTestSupport.loginEventExists
import id.nearyou.app.admin.retention.RetentionCleanupTestSupport.moderationQueueRowExists
import id.nearyou.app.admin.retention.RetentionCleanupTestSupport.notificationExists
import id.nearyou.app.admin.retention.RetentionCleanupTestSupport.refreshTokenExists
import id.nearyou.app.admin.retention.RetentionCleanupTestSupport.reportExists
import id.nearyou.app.admin.retention.RetentionCleanupTestSupport.seedFcmToken
import id.nearyou.app.admin.retention.RetentionCleanupTestSupport.seedLoginEvent
import id.nearyou.app.admin.retention.RetentionCleanupTestSupport.seedModerationQueueRow
import id.nearyou.app.admin.retention.RetentionCleanupTestSupport.seedNotification
import id.nearyou.app.admin.retention.RetentionCleanupTestSupport.seedRefreshToken
import id.nearyou.app.admin.retention.RetentionCleanupTestSupport.seedReport
import id.nearyou.app.admin.retention.RetentionCleanupTestSupport.seedUser
import id.nearyou.app.admin.retention.RetentionCleanupTestSupport.seedWebauthnChallenge
import id.nearyou.app.admin.retention.RetentionCleanupTestSupport.tag
import id.nearyou.app.admin.retention.RetentionCleanupTestSupport.webauthnChallengeExists
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.sql.SQLException
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Repository-level sweep semantics for the `scheduled-retention-cleanup`
 * worker — every `#### Scenario:` from the capability spec's seven sweep
 * requirements + the worker-orchestration count contract. Real-Postgres
 * (`@Tags("database")`), mirroring
 * `PrivacyFlipWorkerTest`. The pool `autoClose(hikari())` is size 2 (CI
 * connection budget); each test cleans up only the rows it seeded (the tables
 * are shared with sibling suites in the full gate).
 */
@Tags("database")
class RetentionCleanupRepositoryTest : StringSpec({

    val dataSource = autoClose(hikari())
    val repository = JdbcRetentionCleanupRepository(dataSource)
    val worker = RetentionCleanupWorker(repository)

    // ----- Refresh-token sweep (spec: Scheduled refresh-token retention sweep) -----

    "an expired-by-expires_at refresh token is deleted" {
        val t = tag()
        val u = seedUser(dataSource, t)
        // expires_at 2 days ago (> 1 day), last_used_at recent (within 90d).
        val tok =
            seedRefreshToken(
                dataSource,
                u,
                t,
                expiresAt = Instant.now().minus(2, ChronoUnit.DAYS),
                lastUsedAt = Instant.now().minus(1, ChronoUnit.DAYS),
            )
        try {
            repository.deleteExpiredAndStaleRefreshTokens() shouldBeGreaterThanOrEqual 1
            refreshTokenExists(dataSource, tok) shouldBe false
        } finally {
            cleanup(dataSource, listOf(u))
        }
    }

    "a long-unused refresh token is deleted even if not yet expired" {
        val t = tag()
        val u = seedUser(dataSource, t)
        // expires_at in the FUTURE, but last_used_at 91 days ago (> 90d stale).
        val tok =
            seedRefreshToken(
                dataSource,
                u,
                t,
                expiresAt = Instant.now().plus(7, ChronoUnit.DAYS),
                lastUsedAt = Instant.now().minus(91, ChronoUnit.DAYS),
            )
        try {
            repository.deleteExpiredAndStaleRefreshTokens() shouldBeGreaterThanOrEqual 1
            refreshTokenExists(dataSource, tok) shouldBe false
        } finally {
            cleanup(dataSource, listOf(u))
        }
    }

    "a revoked-and-expired refresh token is deleted (sweep does not exclude revoked)" {
        val t = tag()
        val u = seedUser(dataSource, t)
        val tok =
            seedRefreshToken(
                dataSource,
                u,
                t,
                expiresAt = Instant.now().minus(2, ChronoUnit.DAYS),
                lastUsedAt = Instant.now().minus(1, ChronoUnit.DAYS),
                revokedAt = Instant.now().minus(2, ChronoUnit.DAYS),
            )
        try {
            repository.deleteExpiredAndStaleRefreshTokens() shouldBeGreaterThanOrEqual 1
            refreshTokenExists(dataSource, tok) shouldBe false
        } finally {
            cleanup(dataSource, listOf(u))
        }
    }

    "a still-valid recently-used refresh token survives" {
        val t = tag()
        val u = seedUser(dataSource, t)
        // expires_at future, last_used_at within 90d → inside both windows.
        val tok =
            seedRefreshToken(
                dataSource,
                u,
                t,
                expiresAt = Instant.now().plus(7, ChronoUnit.DAYS),
                lastUsedAt = Instant.now().minus(1, ChronoUnit.DAYS),
            )
        try {
            repository.deleteExpiredAndStaleRefreshTokens()
            refreshTokenExists(dataSource, tok) shouldBe true
        } finally {
            cleanup(dataSource, listOf(u))
        }
    }

    "a never-used token (last_used_at NULL) survives when expires_at is future" {
        val t = tag()
        val u = seedUser(dataSource, t)
        // last_used_at NULL (never used), expires_at future → the stale predicate
        // is UNKNOWN, the expiry branch does not match → row survives.
        val tok =
            seedRefreshToken(
                dataSource,
                u,
                t,
                expiresAt = Instant.now().plus(7, ChronoUnit.DAYS),
                lastUsedAt = null,
            )
        try {
            repository.deleteExpiredAndStaleRefreshTokens()
            refreshTokenExists(dataSource, tok) shouldBe true
        } finally {
            cleanup(dataSource, listOf(u))
        }
    }

    "a never-used token (last_used_at NULL) is deleted when expires_at has passed" {
        val t = tag()
        val u = seedUser(dataSource, t)
        // last_used_at NULL, expires_at 2 days ago → reaped via the expiry branch.
        val tok =
            seedRefreshToken(
                dataSource,
                u,
                t,
                expiresAt = Instant.now().minus(2, ChronoUnit.DAYS),
                lastUsedAt = null,
            )
        try {
            repository.deleteExpiredAndStaleRefreshTokens() shouldBeGreaterThanOrEqual 1
            refreshTokenExists(dataSource, tok) shouldBe false
        } finally {
            cleanup(dataSource, listOf(u))
        }
    }

    // ----- Notifications purge (spec: Scheduled notifications retention purge) -----

    "a notification older than 90 days is purged" {
        val t = tag()
        val u = seedUser(dataSource, t)
        val n = seedNotification(dataSource, u, createdAt = Instant.now().minus(91, ChronoUnit.DAYS))
        try {
            repository.purgeOldNotifications() shouldBeGreaterThanOrEqual 1
            notificationExists(dataSource, n) shouldBe false
        } finally {
            cleanup(dataSource, listOf(u))
        }
    }

    "a notification within 90 days survives" {
        val t = tag()
        val u = seedUser(dataSource, t)
        val n = seedNotification(dataSource, u, createdAt = Instant.now().minus(89, ChronoUnit.DAYS))
        try {
            repository.purgeOldNotifications()
            notificationExists(dataSource, n) shouldBe true
        } finally {
            cleanup(dataSource, listOf(u))
        }
    }

    "the purge does not exempt any notification type" {
        val t = tag()
        val u = seedUser(dataSource, t)
        // A non-default type, >90 days old → still purged (type-agnostic).
        val n =
            seedNotification(
                dataSource,
                u,
                createdAt = Instant.now().minus(91, ChronoUnit.DAYS),
                type = "data_export_ready",
            )
        try {
            repository.purgeOldNotifications() shouldBeGreaterThanOrEqual 1
            notificationExists(dataSource, n) shouldBe false
        } finally {
            cleanup(dataSource, listOf(u))
        }
    }

    // ----- FCM stale sweep (spec: Scheduled FCM stale-token sweep) -----

    "a token unseen for over 30 days is deleted" {
        val t = tag()
        val u = seedUser(dataSource, t)
        val tok = seedFcmToken(dataSource, u, t, lastSeenAt = Instant.now().minus(31, ChronoUnit.DAYS))
        try {
            repository.deleteStaleFcmTokens() shouldBeGreaterThanOrEqual 1
            fcmTokenExists(dataSource, tok) shouldBe false
        } finally {
            cleanup(dataSource, listOf(u))
        }
    }

    "a recently-seen token survives" {
        val t = tag()
        val u = seedUser(dataSource, t)
        val tok = seedFcmToken(dataSource, u, t, lastSeenAt = Instant.now().minus(29, ChronoUnit.DAYS))
        try {
            repository.deleteStaleFcmTokens()
            fcmTokenExists(dataSource, tok) shouldBe true
        } finally {
            cleanup(dataSource, listOf(u))
        }
    }

    // ----- Login-events sweep (spec: Scheduled login-events retention sweep) -----

    "a login event older than 90 days is purged; one within 90 days (and one ~at the boundary) survive" {
        val t = tag()
        val u = seedUser(dataSource, t)
        val old = seedLoginEvent(dataSource, u, occurredAt = Instant.now().minus(91, ChronoUnit.DAYS))
        val recent = seedLoginEvent(dataSource, u, occurredAt = Instant.now().minus(89, ChronoUnit.DAYS))
        // 89d23h ago — just INSIDE the 90-day window; the strict-`<` predicate lets it survive
        // (a true fencepost-exact 90d is avoided per the macOS-micros vs Linux-nanos clock caveat).
        val nearBoundary =
            seedLoginEvent(dataSource, u, occurredAt = Instant.now().minus(90, ChronoUnit.DAYS).plus(1, ChronoUnit.HOURS))
        try {
            repository.deleteOldLoginEvents() shouldBeGreaterThanOrEqual 1
            loginEventExists(dataSource, old) shouldBe false
            loginEventExists(dataSource, recent) shouldBe true
            loginEventExists(dataSource, nearBoundary) shouldBe true
        } finally {
            cleanup(dataSource, listOf(u))
        }
    }

    // ----- Worker: all sweeps run + per-sweep counts (spec: runs all sweeps per invocation) -----

    "a worker run deletes from all seven tables and reports a count for every sweep" {
        val t = tag()
        val u = seedUser(dataSource, t)
        seedRefreshToken(
            dataSource,
            u,
            t,
            expiresAt = Instant.now().minus(2, ChronoUnit.DAYS),
            lastUsedAt = Instant.now().minus(1, ChronoUnit.DAYS),
        )
        seedNotification(dataSource, u, createdAt = Instant.now().minus(91, ChronoUnit.DAYS))
        seedFcmToken(dataSource, u, t, lastSeenAt = Instant.now().minus(31, ChronoUnit.DAYS))
        seedLoginEvent(dataSource, u, occurredAt = Instant.now().minus(91, ChronoUnit.DAYS))
        val challenge = seedWebauthnChallenge(dataSource, expiresAt = Instant.now().minus(2, ChronoUnit.DAYS))
        val mod = seedModerationQueueRow(dataSource, status = "resolved", resolvedAt = Instant.now().minus(400, ChronoUnit.DAYS))
        seedReport(dataSource, u, status = "actioned", reviewedAt = Instant.now().minus(400, ChronoUnit.DAYS))
        try {
            val result = worker.execute()
            // Other suites' aged rows may also be reaped in the shared DB, so the
            // floor is >= 1 for each sweep (our seeded eligible row guarantees it).
            result.refreshTokensDeleted shouldBeGreaterThanOrEqual 1
            result.notificationsDeleted shouldBeGreaterThanOrEqual 1
            result.fcmTokensDeleted shouldBeGreaterThanOrEqual 1
            result.loginEventsDeleted shouldBeGreaterThanOrEqual 1
            result.webauthnChallengesDeleted shouldBeGreaterThanOrEqual 1
            result.moderationQueueDeleted shouldBeGreaterThanOrEqual 1
            result.reportsDeleted shouldBeGreaterThanOrEqual 1
        } finally {
            deleteRows(dataSource, "admin_webauthn_challenges", listOf(challenge))
            deleteRows(dataSource, "moderation_queue", listOf(mod))
            cleanup(dataSource, listOf(u))
        }
    }

    // ----- Idempotency: a re-run does not re-count already-reclaimed rows -----

    "two back-to-back runs reclaim the seeded rows once, then zero" {
        // First clear every currently-eligible row so the post-seed counts are
        // exact (mirrors the route suite's determinism trick; Kotest runs specs
        // single-fork-sequentially, so nothing ages in the sub-second gap).
        worker.execute()
        val t = tag()
        val u = seedUser(dataSource, t)
        seedRefreshToken(
            dataSource,
            u,
            t,
            expiresAt = Instant.now().minus(2, ChronoUnit.DAYS),
            lastUsedAt = Instant.now().minus(1, ChronoUnit.DAYS),
        )
        seedNotification(dataSource, u, createdAt = Instant.now().minus(91, ChronoUnit.DAYS))
        seedFcmToken(dataSource, u, t, lastSeenAt = Instant.now().minus(31, ChronoUnit.DAYS))
        seedLoginEvent(dataSource, u, occurredAt = Instant.now().minus(91, ChronoUnit.DAYS))
        val challenge = seedWebauthnChallenge(dataSource, expiresAt = Instant.now().minus(2, ChronoUnit.DAYS))
        val mod = seedModerationQueueRow(dataSource, status = "resolved", resolvedAt = Instant.now().minus(400, ChronoUnit.DAYS))
        seedReport(dataSource, u, status = "dismissed", reviewedAt = Instant.now().minus(400, ChronoUnit.DAYS))
        try {
            // First run reclaims exactly the N=1-per-table we just seeded.
            val first = worker.execute()
            first.refreshTokensDeleted shouldBe 1
            first.notificationsDeleted shouldBe 1
            first.fcmTokensDeleted shouldBe 1
            first.loginEventsDeleted shouldBe 1
            first.webauthnChallengesDeleted shouldBe 1
            first.moderationQueueDeleted shouldBe 1
            first.reportsDeleted shouldBe 1
            // Immediate re-run: the same rows are gone, nothing newly aged → all zero
            // (proves the threshold DELETEs do not re-count already-reclaimed rows).
            val second = worker.execute()
            second.refreshTokensDeleted shouldBe 0
            second.notificationsDeleted shouldBe 0
            second.fcmTokensDeleted shouldBe 0
            second.loginEventsDeleted shouldBe 0
            second.webauthnChallengesDeleted shouldBe 0
            second.moderationQueueDeleted shouldBe 0
            second.reportsDeleted shouldBe 0
        } finally {
            deleteRows(dataSource, "admin_webauthn_challenges", listOf(challenge))
            deleteRows(dataSource, "moderation_queue", listOf(mod))
            cleanup(dataSource, listOf(u))
        }
    }

    // ----- Sweep-failure isolation (spec/design D4: an independent statement per sweep) -----

    "a later sweep's failure does not roll back an earlier sweep's committed deletes" {
        val t = tag()
        val u = seedUser(dataSource, t)
        val tok =
            seedRefreshToken(
                dataSource,
                u,
                t,
                expiresAt = Instant.now().minus(2, ChronoUnit.DAYS),
                lastUsedAt = Instant.now().minus(1, ChronoUnit.DAYS),
            )
        // Sweep 1 (refresh tokens) delegates to the REAL JDBC repo, so it commits
        // on its own auto-commit connection; sweep 2 (notifications) throws. D4
        // requires sweep 1's reclaimed rows to survive sweep 2's failure.
        val failAfterFirstSweep =
            object : RetentionCleanupRepository {
                override suspend fun deleteExpiredAndStaleRefreshTokens(): Int = repository.deleteExpiredAndStaleRefreshTokens()

                override suspend fun purgeOldNotifications(): Int = throw SQLException("simulated notifications-sweep failure")

                override suspend fun deleteStaleFcmTokens(): Int = error("not reached — sweep 2 failed first")

                override suspend fun deleteOldLoginEvents(): Int = error("not reached — sweep 2 failed first")

                override suspend fun deleteExpiredWebauthnChallenges(): Int = error("not reached — sweep 2 failed first")

                override suspend fun deleteOldResolvedModerationQueueRows(): Int = error("not reached — sweep 2 failed first")

                override suspend fun deleteOldResolvedReports(): Int = error("not reached — sweep 2 failed first")
            }
        val failingWorker = RetentionCleanupWorker(failAfterFirstSweep)
        try {
            var thrown: Throwable? = null
            try {
                failingWorker.execute()
            } catch (e: SQLException) {
                thrown = e
            }
            // The notifications-sweep exception propagated out of execute().
            thrown shouldNotBe null
            // Fresh DB read: the refresh-token sweep committed before sweep 2 blew up.
            refreshTokenExists(dataSource, tok) shouldBe false
        } finally {
            cleanup(dataSource, listOf(u))
        }
    }

    // ----- WebAuthn-challenge sweep (spec: Scheduled WebAuthn-challenge cleanup sweep) -----

    "an expired unconsumed webauthn challenge is deleted" {
        val c = seedWebauthnChallenge(dataSource, expiresAt = Instant.now().minus(2, ChronoUnit.DAYS))
        try {
            repository.deleteExpiredWebauthnChallenges() shouldBeGreaterThanOrEqual 1
            webauthnChallengeExists(dataSource, c) shouldBe false
        } finally {
            deleteRows(dataSource, "admin_webauthn_challenges", listOf(c))
        }
    }

    "a recently-expired unconsumed challenge survives the 1-day grace" {
        // Expired 1 hour ago — inside the 1-day grace window.
        val c = seedWebauthnChallenge(dataSource, expiresAt = Instant.now().minus(1, ChronoUnit.HOURS))
        try {
            repository.deleteExpiredWebauthnChallenges()
            webauthnChallengeExists(dataSource, c) shouldBe true
        } finally {
            deleteRows(dataSource, "admin_webauthn_challenges", listOf(c))
        }
    }

    "a consumed challenge is not touched regardless of age" {
        val c =
            seedWebauthnChallenge(
                dataSource,
                expiresAt = Instant.now().minus(30, ChronoUnit.DAYS),
                consumedAt = Instant.now().minus(30, ChronoUnit.DAYS),
            )
        try {
            repository.deleteExpiredWebauthnChallenges()
            webauthnChallengeExists(dataSource, c) shouldBe true
        } finally {
            deleteRows(dataSource, "admin_webauthn_challenges", listOf(c))
        }
    }

    // ----- Moderation-queue + reports retention sweeps (spec: 1-year resolved-row windows) -----

    "a resolved moderation_queue row older than one year is deleted; a recent and a pending one survive" {
        val old = seedModerationQueueRow(dataSource, status = "resolved", resolvedAt = Instant.now().minus(400, ChronoUnit.DAYS))
        val recent = seedModerationQueueRow(dataSource, status = "resolved", resolvedAt = Instant.now().minus(300, ChronoUnit.DAYS))
        // Pending row far older than a year — the sweep must never touch pending rows.
        val pending =
            seedModerationQueueRow(
                dataSource,
                status = "pending",
                resolvedAt = null,
                createdAt = Instant.now().minus(400, ChronoUnit.DAYS),
            )
        try {
            repository.deleteOldResolvedModerationQueueRows() shouldBeGreaterThanOrEqual 1
            moderationQueueRowExists(dataSource, old) shouldBe false
            moderationQueueRowExists(dataSource, recent) shouldBe true
            moderationQueueRowExists(dataSource, pending) shouldBe true
        } finally {
            deleteRows(dataSource, "moderation_queue", listOf(old, recent, pending))
        }
    }

    "actioned and dismissed reports older than one year are deleted; recent and pending ones survive" {
        val t = tag()
        val u = seedUser(dataSource, t)
        val actioned = seedReport(dataSource, u, status = "actioned", reviewedAt = Instant.now().minus(400, ChronoUnit.DAYS))
        val dismissed = seedReport(dataSource, u, status = "dismissed", reviewedAt = Instant.now().minus(400, ChronoUnit.DAYS))
        val recent = seedReport(dataSource, u, status = "actioned", reviewedAt = Instant.now().minus(300, ChronoUnit.DAYS))
        val pending =
            seedReport(
                dataSource,
                u,
                status = "pending",
                reviewedAt = null,
                createdAt = Instant.now().minus(400, ChronoUnit.DAYS),
            )
        try {
            repository.deleteOldResolvedReports() shouldBeGreaterThanOrEqual 2
            reportExists(dataSource, actioned) shouldBe false
            reportExists(dataSource, dismissed) shouldBe false
            reportExists(dataSource, recent) shouldBe true
            reportExists(dataSource, pending) shouldBe true
        } finally {
            cleanup(dataSource, listOf(u)) // reports cascade with the reporter user
        }
    }

    "a resolved-status row with a NULL resolution timestamp survives (fail-safe)" {
        val t = tag()
        val u = seedUser(dataSource, t)
        // Hypothetically-inconsistent rows: resolved status but NULL timestamp — a NULL
        // comparison is UNKNOWN, so the sweeps must leave them (design D3 fail-safe).
        val mod = seedModerationQueueRow(dataSource, status = "resolved", resolvedAt = null)
        val rep = seedReport(dataSource, u, status = "dismissed", reviewedAt = null)
        try {
            repository.deleteOldResolvedModerationQueueRows()
            repository.deleteOldResolvedReports()
            moderationQueueRowExists(dataSource, mod) shouldBe true
            reportExists(dataSource, rep) shouldBe true
        } finally {
            deleteRows(dataSource, "moderation_queue", listOf(mod))
            cleanup(dataSource, listOf(u))
        }
    }

    "a swept resolved row is removed without any archive copy (no archive table exists)" {
        val old = seedModerationQueueRow(dataSource, status = "resolved", resolvedAt = Instant.now().minus(400, ChronoUnit.DAYS))
        try {
            repository.deleteOldResolvedModerationQueueRows() shouldBeGreaterThanOrEqual 1
            moderationQueueRowExists(dataSource, old) shouldBe false
            // Spec "No archive copy is made": the delete is not a move — the schema has
            // no archive table to move to (audit trail is admin_actions_log). Deliberate
            // tripwire: a future *archive* table for these rows must revisit the spec's
            // no-archive requirement (extend the exclusion list only if it's unrelated).
            val archiveTables =
                dataSource.connection.use { conn ->
                    conn.prepareStatement(
                        // csam_detection_archive is excluded: an unrelated DESIGN-reserved table.
                        "SELECT COUNT(*) FROM information_schema.tables " +
                            "WHERE table_schema = 'public' AND table_name LIKE '%archive%' " +
                            "AND table_name <> 'csam_detection_archive'",
                    ).use { ps ->
                        ps.executeQuery().use { rs ->
                            rs.next()
                            rs.getInt(1)
                        }
                    }
                }
            archiveTables shouldBe 0
        } finally {
            deleteRows(dataSource, "moderation_queue", listOf(old))
        }
    }
})
