package id.nearyou.app.moderation

import id.nearyou.data.repository.ModerationQueueRepository
import id.nearyou.data.repository.PostAutoHideRepository
import id.nearyou.data.repository.ReportReasonCategory
import id.nearyou.data.repository.ReportRepository
import id.nearyou.data.repository.ReportTargetType
import org.slf4j.LoggerFactory
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource

/**
 * Orchestrates `POST /api/v1/reports`: rate-limit → self-report check → target
 * existence → BEGIN TX → INSERT reports → COUNT DISTINCT aged reporters →
 * conditional `is_auto_hidden` flip → moderation_queue upsert → COMMIT.
 *
 * The rate-limit check runs BEFORE any DB work (spec Requirement). The
 * self-report check runs BEFORE target existence so a caller posting
 * `target_type = "user", target_id = caller.id` gets 400 without a DB round
 * trip. Target-existence runs BEFORE opening the transaction — 404 for missing
 * targets must not leave an open connection.
 *
 * The auto-hide side-effects (is_auto_hidden flip + moderation_queue row) run
 * INSIDE the same transaction as the reports INSERT — spec Decision 1. No
 * out-of-band worker. No post-commit hook. Concurrent races are handled by the
 * queue's UNIQUE `(target_type, target_id, trigger)` + ON CONFLICT DO NOTHING.
 */
class ReportService(
    private val dataSource: DataSource,
    private val reports: ReportRepository,
    private val moderationQueue: ModerationQueueRepository,
    private val postAutoHide: PostAutoHideRepository,
    private val rateLimiter: ReportRateLimiter,
) {
    /**
     * Runs the full report-submission flow. Returns a typed [Result] that the
     * HTTP layer maps to a status code + error envelope.
     */
    fun submit(
        reporterId: UUID,
        targetType: ReportTargetType,
        targetId: UUID,
        reasonCategory: ReportReasonCategory,
        reasonNote: String?,
    ): Result {
        // 1. Self-report guard — runs before rate-limit so the caller doesn't
        //    burn quota on an always-rejected request.
        if (targetType == ReportTargetType.USER && targetId == reporterId) {
            return Result.SelfReportRejected
        }

        // 2. Rate-limit — BEFORE any DB work. 10/hour/user sliding window.
        val slot =
            when (val outcome = rateLimiter.tryAcquire(reporterId)) {
                is ReportRateLimiter.Outcome.Allowed -> outcome
                is ReportRateLimiter.Outcome.RateLimited ->
                    return Result.RateLimited(retryAfterSeconds = outcome.retryAfterSeconds)
            }

        // 3. Target existence — outside the TX so 404 doesn't open a connection.
        dataSource.connection.use { conn ->
            if (!reports.targetExists(conn, targetType, targetId)) {
                rateLimiter.releaseMostRecent(reporterId) // 404 does not consume a slot.
                return Result.TargetNotFound
            }
        }

        // 4. Single transaction: INSERT → COUNT → conditional UPDATE + queue INSERT.
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                try {
                    reports.insertReport(
                        conn = conn,
                        reporterId = reporterId,
                        targetType = targetType,
                        targetId = targetId,
                        reasonCategory = reasonCategory,
                        reasonNote = reasonNote,
                    )
                } catch (ex: SQLException) {
                    if (ex.sqlState == UNIQUE_VIOLATION) {
                        conn.rollback()
                        rateLimiter.releaseMostRecent(reporterId) // 409 does not consume a slot.
                        return Result.Duplicate
                    }
                    throw ex
                }

                val count = reports.countDistinctAgedReporters(conn, targetType, targetId)
                if (count >= AUTO_HIDE_THRESHOLD) {
                    val insertedQueue = moderationQueue.upsertAutoHideRow(conn, targetType, targetId)
                    postAutoHide.flipIsAutoHidden(conn, targetType, targetId)
                    // Observability — emit only on a fresh queue-row insert (the
                    // threshold crossing itself, not the 4th/5th reporter on an
                    // already-hidden target).
                    if (insertedQueue) {
                        log.info(
                            "event=auto_hide_triggered target_type={} target_id={} reporter_count={}",
                            targetType.wire,
                            targetId,
                            count,
                        )
                        // TODO: notifications-api-v??
                        //   Emit a `post_auto_hidden` notification once the
                        //   notifications API lands (Phase 2 item 5).
                    }
                }
                conn.commit()
            } catch (t: Throwable) {
                conn.rollback()
                throw t
            } finally {
                conn.autoCommit = true
            }
        }

        return Result.Success(remainingQuota = slot.remaining)
    }

    sealed interface Result {
        data class Success(val remainingQuota: Int) : Result

        data object SelfReportRejected : Result

        data object TargetNotFound : Result

        data object Duplicate : Result

        data class RateLimited(val retryAfterSeconds: Long) : Result
    }

    companion object {
        const val AUTO_HIDE_THRESHOLD: Int = 3
        const val UNIQUE_VIOLATION: String = "23505"
        private val log = LoggerFactory.getLogger(ReportService::class.java)
    }
}
