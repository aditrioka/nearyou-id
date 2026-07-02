package id.nearyou.app.auth.anomaly

import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant

private val logger = LoggerFactory.getLogger("id.nearyou.app.auth.anomaly.LoginAnomalyDetectionService")

/**
 * Summary of one [LoginAnomalyDetectionService.sweep] run — the counts the route
 * ([loginAnomalyCheckRoutes]) returns in its `200` body.
 *
 * - [flagged]: users over the distinct-subnet threshold this sweep evaluated for
 *   recording (the detection query result size — kept bounded by the `HAVING`
 *   filter, so this is NOT a full active-user count; design Sweep-cost note).
 * - [recorded]: NEW `moderation_queue` rows actually inserted (a user already
 *   carrying an `anomaly_detection` row is suppressed by ON CONFLICT and counts
 *   toward neither [recorded] nor [failed]).
 * - [failed]: flagged users whose record step threw and was fail-soft-skipped.
 */
data class LoginAnomalySweepResult(
    val flagged: Int,
    val recorded: Int,
    val failed: Int,
)

/**
 * Periodic login-source-spread anomaly detector (Cloud-Scheduler-invoked via
 * `POST /internal/login-anomaly-check`). Operationalizes
 * `docs/05-Implementation.md` § Anomaly Detection Metrics ("same `sub` issued
 * from >5 geographic locations in 1h"), mapping "geographic location" to the
 * coarse `/24` subnet `login_events` actually stores (design Decision 1).
 *
 * Each sweep:
 *  1. Captures ONE evaluation instant ([nowProvider]) and derives the trailing
 *     window bound ([window]); the single captured instant makes the window
 *     boundary deterministic across the whole sweep (spec window-correctness
 *     scenario).
 *  2. Reads the flagged users (`> [threshold]` distinct non-NULL subnets) via the
 *     repository.
 *  3. Records each as an idempotent, non-PII `moderation_queue` row — **fail-soft
 *     per user**: one user's record failure is logged (PII-free) and skipped, the
 *     sweep continues, and the run does not fail (spec "The sweep is fail-soft per
 *     user"). Only counts + `user_id` ever leave this boundary.
 *
 * Scope guard: this service computes ONLY the subnet-spread signal and emits ONLY
 * the `moderation_queue` row — it has no Sentry/Slack/alert-dispatch dependency
 * (a structural guarantee: no such collaborator is constructor-injected) and
 * writes no `reports` row (spec "Detection scope is the login-source-spread leg
 * only" + "admin anomaly-review surface is explicitly deferred"). The real-time
 * alert hook and the admin review UI are declared-deferred follow-ups.
 *
 * Thread-safe: holds no state across calls.
 */
class LoginAnomalyDetectionService(
    private val repository: LoginAnomalyRepository,
    private val threshold: Int = DISTINCT_SUBNET_THRESHOLD,
    private val window: Duration = DETECTION_WINDOW,
    private val nowProvider: () -> Instant = Instant::now,
) {
    suspend fun sweep(): LoginAnomalySweepResult {
        val evaluationInstant = nowProvider()
        val windowStart = evaluationInstant.minus(window)
        val flaggedUsers = repository.findUsersExceedingSubnetThreshold(windowStart, threshold)

        var recorded = 0
        var failed = 0
        for (user in flaggedUsers) {
            try {
                // Non-PII notes: distinct-subnet count + window label ONLY (no IP /
                // subnet value / identifier hash). See LoginAnomalyRepository.recordAnomaly.
                val inserted = repository.recordAnomaly(user.userId, notesFor(user.distinctSubnets))
                if (inserted) recorded++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed++
                // PII-free: only the user_id (non-secret PK) + exception class. No
                // subnet/IP value is in scope here to leak.
                logger.warn(
                    "event=login_anomaly_record_failed user_id={} error_class={}",
                    user.userId,
                    e::class.simpleName,
                    e,
                )
            }
        }

        logger.info(
            "event=login_anomaly_sweep flagged={} recorded={} failed={} window_minutes={}",
            flaggedUsers.size,
            recorded,
            failed,
            window.toMinutes(),
        )

        return LoginAnomalySweepResult(flagged = flaggedUsers.size, recorded = recorded, failed = failed)
    }

    /** Non-PII `moderation_queue.notes` descriptor: the aggregate count + window label only. */
    private fun notesFor(distinctSubnets: Int): String =
        "login-source-spread: $distinctSubnets distinct /24 subnets within ${window.toMinutes()}m window"

    companion object {
        /**
         * docs/05 § Anomaly Detection: ">5 geographic locations in 1h". The detector
         * flags a user with STRICTLY more than this many distinct `/24` subnets, so a
         * flag fires at 6+. Named (not inline) so it is tunable from operational data
         * without touching query construction (design Decision 1 / Open Questions).
         */
        const val DISTINCT_SUBNET_THRESHOLD: Int = 5

        /** docs/05 § Anomaly Detection: the trailing "1h" window. Tunable; bound as a query parameter. */
        val DETECTION_WINDOW: Duration = Duration.ofHours(1)
    }
}
