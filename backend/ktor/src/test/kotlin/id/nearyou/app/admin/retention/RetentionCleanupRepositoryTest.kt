package id.nearyou.app.admin.retention

import id.nearyou.app.admin.retention.RetentionCleanupTestSupport.cleanup
import id.nearyou.app.admin.retention.RetentionCleanupTestSupport.fcmTokenExists
import id.nearyou.app.admin.retention.RetentionCleanupTestSupport.hikari
import id.nearyou.app.admin.retention.RetentionCleanupTestSupport.loginEventExists
import id.nearyou.app.admin.retention.RetentionCleanupTestSupport.notificationExists
import id.nearyou.app.admin.retention.RetentionCleanupTestSupport.refreshTokenExists
import id.nearyou.app.admin.retention.RetentionCleanupTestSupport.seedFcmToken
import id.nearyou.app.admin.retention.RetentionCleanupTestSupport.seedLoginEvent
import id.nearyou.app.admin.retention.RetentionCleanupTestSupport.seedNotification
import id.nearyou.app.admin.retention.RetentionCleanupTestSupport.seedRefreshToken
import id.nearyou.app.admin.retention.RetentionCleanupTestSupport.seedUser
import id.nearyou.app.admin.retention.RetentionCleanupTestSupport.tag
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.sql.SQLException
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Repository-level sweep semantics for the `scheduled-retention-cleanup`
 * worker — every `#### Scenario:` from the new capability spec's three sweep
 * requirements + the worker-orchestration count contract + the two deferred
 * negative-guards. Real-Postgres (`@Tags("database")`), mirroring
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

    "a worker run deletes from all four tables and reports a count for every sweep" {
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
        try {
            val result = worker.execute()
            // Other suites' aged rows may also be reaped in the shared DB, so the
            // floor is >= 1 for each sweep (our seeded eligible row guarantees it).
            result.refreshTokensDeleted shouldBeGreaterThanOrEqual 1
            result.notificationsDeleted shouldBeGreaterThanOrEqual 1
            result.fcmTokensDeleted shouldBeGreaterThanOrEqual 1
            result.loginEventsDeleted shouldBeGreaterThanOrEqual 1
        } finally {
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
        try {
            // First run reclaims exactly the N=1-per-table we just seeded.
            val first = worker.execute()
            first.refreshTokensDeleted shouldBe 1
            first.notificationsDeleted shouldBe 1
            first.fcmTokensDeleted shouldBe 1
            // Immediate re-run: the same rows are gone, nothing newly aged → all zero
            // (proves the threshold DELETEs do not re-count already-reclaimed rows).
            val second = worker.execute()
            second.refreshTokensDeleted shouldBe 0
            second.notificationsDeleted shouldBe 0
            second.fcmTokensDeleted shouldBe 0
        } finally {
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

    // ----- Deferred negative-guards (spec: WebAuthn + moderation/reports out of scope) -----

    "the worker leaves an expired-unconsumed admin_webauthn_challenges row untouched" {
        val challengeId = UUID.randomUUID()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO admin_webauthn_challenges (id, admin_id, challenge, ceremony, expires_at, consumed_at)
                VALUES (?, NULL, ?, 'authentication', NOW() - INTERVAL '2 days', NULL)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, challengeId)
                ps.setBytes(2, byteArrayOf(1, 2, 3, 4))
                ps.executeUpdate()
            }
        }
        try {
            worker.execute()
            val survives =
                dataSource.connection.use { conn ->
                    conn.prepareStatement("SELECT 1 FROM admin_webauthn_challenges WHERE id = ?").use { ps ->
                        ps.setObject(1, challengeId)
                        ps.executeQuery().use { rs -> rs.next() }
                    }
                }
            survives shouldBe true
        } finally {
            dataSource.connection.use { conn ->
                conn.prepareStatement("DELETE FROM admin_webauthn_challenges WHERE id = ?").use { ps ->
                    ps.setObject(1, challengeId)
                    ps.executeUpdate()
                }
            }
        }
    }

    "the worker leaves old resolved moderation_queue and reports rows untouched" {
        val t = tag()
        val u = seedUser(dataSource, t)
        val targetId = UUID.randomUUID()
        val modId = UUID.randomUUID()
        val reportId = UUID.randomUUID()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO moderation_queue (id, target_type, target_id, trigger, status, resolution, created_at, resolved_at)
                VALUES (?, 'post', ?, 'admin_flag', 'resolved', 'keep',
                        NOW() - INTERVAL '400 days', NOW() - INTERVAL '400 days')
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, modId)
                ps.setObject(2, targetId)
                ps.executeUpdate()
            }
            conn.prepareStatement(
                """
                INSERT INTO reports (id, reporter_id, target_type, target_id, reason_category, status, created_at, reviewed_at)
                VALUES (?, ?, 'post', ?, 'spam', 'dismissed',
                        NOW() - INTERVAL '400 days', NOW() - INTERVAL '400 days')
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, reportId)
                ps.setObject(2, u)
                ps.setObject(3, targetId)
                ps.executeUpdate()
            }
        }
        try {
            worker.execute()
            val modSurvives =
                dataSource.connection.use { conn ->
                    conn.prepareStatement("SELECT 1 FROM moderation_queue WHERE id = ?").use { ps ->
                        ps.setObject(1, modId)
                        ps.executeQuery().use { rs -> rs.next() }
                    }
                }
            val reportSurvives =
                dataSource.connection.use { conn ->
                    conn.prepareStatement("SELECT 1 FROM reports WHERE id = ?").use { ps ->
                        ps.setObject(1, reportId)
                        ps.executeQuery().use { rs -> rs.next() }
                    }
                }
            modSurvives shouldBe true
            reportSurvives shouldBe true
        } finally {
            dataSource.connection.use { conn ->
                conn.prepareStatement("DELETE FROM reports WHERE id = ?").use { ps ->
                    ps.setObject(1, reportId)
                    ps.executeUpdate()
                }
                conn.prepareStatement("DELETE FROM moderation_queue WHERE id = ?").use { ps ->
                    ps.setObject(1, modId)
                    ps.executeUpdate()
                }
            }
            cleanup(dataSource, listOf(u))
        }
    }
})
