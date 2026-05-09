package id.nearyou.app.moderation

import id.nearyou.app.infra.perspective.PerspectiveClient
import id.nearyou.app.infra.perspective.PerspectiveHttpException
import id.nearyou.app.infra.perspective.PerspectiveResponseParseException
import id.nearyou.app.infra.perspective.PerspectiveScore
import id.nearyou.data.repository.PerspectiveModerationWriter
import id.nearyou.data.repository.PerspectiveWriteResult
import id.nearyou.data.repository.ReportTargetType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.UUID
import kotlin.math.max
import kotlin.time.Duration.Companion.milliseconds

/**
 * Layer 3 (Perspective API) orchestrator. Composes [PerspectiveClient],
 * [PerspectiveConfigLoader], and [PerspectiveModerationWriter] into a single
 * [moderate] entrypoint per the `text-moderation-perspective-api-layer` capability.
 *
 * Workflow per [moderate]:
 *  1. Read kill-switch via [PerspectiveConfigLoader.isEnabled]; on `false` return
 *     `Outcome.NoAction` silently.
 *  2. Wrap [PerspectiveClient.analyze] in `withTimeoutOrNull(500.ms)`.
 *  3. Aggregate score: `max(toxicity, severeToxicity, identityAttack, threat)`.
 *  4. Read both thresholds via the loader; check cross-flag invariant
 *     (`flag ≤ high_score`); fall back to BOTH defaults + Sentry ERROR if violated.
 *  5. Map score to `Outcome`:
 *     - `score > high_score_threshold` → `AutoHide`
 *     - `score > flag_threshold` → `FlagOnly`
 *     - else → `NoAction`
 *  6. Apply DB writes via [PerspectiveModerationWriter]; emit success-path Sentry INFO
 *     with `score_bucket` (`high` / `mid` / `low`) per design Decision 7.
 *
 * Failure handling is fail-OPEN: any non-cancellation exception emits a Sentry WARN
 * with the appropriate `failure_kind` and returns `Outcome.NoAction`.
 * `CancellationException` propagates per coroutine convention so structured
 * cancellation works through the dispatcher scope.
 *
 * No-content-on-Sentry: events emitted by this class never carry the raw `content`
 * string OR the raw per-attribute scores — only `score_bucket` (categorical) and
 * `target_type` / `target_id` (per design Decision 7).
 */
interface PerspectiveModerator {
    suspend fun moderate(
        targetType: TargetType,
        targetId: UUID,
        content: String,
    ): Outcome
}

class DefaultPerspectiveModerator(
    private val client: PerspectiveClient,
    private val configLoader: PerspectiveConfigLoader,
    private val writer: PerspectiveModerationWriter,
    private val analyzeTimeoutMillis: Long = ANALYZE_TIMEOUT_MILLIS,
) : PerspectiveModerator {
    private val log = LoggerFactory.getLogger(DefaultPerspectiveModerator::class.java)

    override suspend fun moderate(
        targetType: TargetType,
        targetId: UUID,
        content: String,
    ): Outcome {
        // 1. Kill-switch check. The loader handles its own ERROR emission on Tier-2
        // throw (fail-OPEN to true per design.md Decision 12); the orchestrator just
        // observes the boolean and short-circuits silently when disabled.
        val enabled =
            try {
                configLoader.isEnabled()
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                // Defense in depth: the production loader catches its own errors and
                // returns true (fail-OPEN). If a custom impl throws unexpectedly, treat
                // as fail-OPEN here too AND emit the kill-switch ERROR ourselves so
                // operators are paged.
                log.error(
                    "event={} key={} reason={} fail_mode=open path=orchestrator",
                    EVENT_KILL_SWITCH_UNAVAILABLE,
                    CachingPerspectiveConfigLoader.KILL_SWITCH_PARAM,
                    t.javaClass.simpleName,
                )
                true
            }
        if (!enabled) {
            return Outcome.NoAction
        }

        // 2. Perspective call with 500ms budget.
        val score: PerspectiveScore =
            try {
                withTimeoutOrNull(analyzeTimeoutMillis.milliseconds) {
                    client.analyze(content)
                }
            } catch (t: CancellationException) {
                // Per coroutine convention: do NOT swallow. Structured cancellation must
                // propagate through the dispatcher scope so JVM shutdown drain works.
                throw t
            } catch (t: PerspectiveHttpException) {
                emitDispatchFailedWarn(targetType, targetId, classifyHttpStatus(t.status.value), t.status.value)
                return Outcome.NoAction
            } catch (t: PerspectiveResponseParseException) {
                emitDispatchFailedWarn(targetType, targetId, FAILURE_KIND_PARSE, statusCode = null)
                return Outcome.NoAction
            } catch (t: SerializationException) {
                emitDispatchFailedWarn(targetType, targetId, FAILURE_KIND_PARSE, statusCode = null)
                return Outcome.NoAction
            } catch (t: IOException) {
                emitDispatchFailedWarn(targetType, targetId, FAILURE_KIND_NETWORK, statusCode = null)
                return Outcome.NoAction
            } catch (t: Throwable) {
                // Treat any other unhandled exception as a network/transport failure
                // (fail-OPEN). Emit the WARN, return NoAction, do NOT propagate.
                emitDispatchFailedWarn(targetType, targetId, FAILURE_KIND_NETWORK, statusCode = null)
                return Outcome.NoAction
            } ?: run {
                // withTimeoutOrNull returned null — the 500ms budget elapsed.
                emitDispatchFailedWarn(targetType, targetId, FAILURE_KIND_TIMEOUT, statusCode = null)
                return Outcome.NoAction
            }

        // 3. Aggregate.
        val aggregate = aggregateMax(score)

        // 4. Resolve thresholds + cross-flag invariant check.
        val rawHigh = configLoader.highScoreThreshold()
        val rawFlag = configLoader.flagThreshold()
        val (effectiveHigh, effectiveFlag) =
            if (rawFlag > rawHigh) {
                // Cross-flag misconfiguration per design.md Decision 11. Fall back to
                // BOTH defaults AND emit Sentry ERROR (operator-actionable).
                log.error(
                    "event={} flag_threshold={} high_score_threshold={}",
                    EVENT_THRESHOLD_MISCONFIGURED,
                    rawFlag,
                    rawHigh,
                )
                CachingPerspectiveConfigLoader.DEFAULT_HIGH_SCORE_THRESHOLD to
                    CachingPerspectiveConfigLoader.DEFAULT_FLAG_THRESHOLD
            } else {
                rawHigh to rawFlag
            }

        // 5. Outcome mapping.
        val outcome: Outcome =
            when {
                aggregate > effectiveHigh -> Outcome.AutoHide(aggregate)
                aggregate > effectiveFlag -> Outcome.FlagOnly(aggregate)
                else -> Outcome.NoAction
            }

        // 6. DB writes.
        when (outcome) {
            is Outcome.AutoHide -> applyAutoHide(targetType, targetId, outcome.score)
            is Outcome.FlagOnly -> applyFlag(targetType, targetId, outcome.score)
            Outcome.NoAction -> Unit
        }

        return outcome
    }

    private fun applyAutoHide(
        targetType: TargetType,
        targetId: UUID,
        score: Double,
    ) {
        val mapped = targetType.toReportTargetType()
        val result =
            try {
                writer.applyAutoHideAndQueue(mapped, targetId, score)
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                log.warn(
                    "event={} target_type={} target_id={} score_bucket={} reason={}",
                    EVENT_DB_WRITE_FAILED,
                    targetType.wire,
                    targetId,
                    BUCKET_HIGH,
                    t.javaClass.simpleName,
                )
                return
            }
        when (result) {
            PerspectiveWriteResult.Applied ->
                log.info(
                    "event={} target_type={} target_id={} score_bucket={}",
                    EVENT_HIGH_SCORE_APPLIED,
                    targetType.wire,
                    targetId,
                    BUCKET_HIGH,
                )
            PerspectiveWriteResult.QueueConflictNoOp ->
                log.info(
                    "event={} target_type={} target_id={} score_bucket={} reason=queue_conflict",
                    EVENT_HIGH_SCORE_APPLIED,
                    targetType.wire,
                    targetId,
                    BUCKET_HIGH,
                )
            PerspectiveWriteResult.SoftDeletedTarget ->
                log.info(
                    "event={} target_type={} target_id={} score_bucket={}",
                    EVENT_SOFT_DELETED_TARGET,
                    targetType.wire,
                    targetId,
                    BUCKET_HIGH,
                )
        }
    }

    private fun applyFlag(
        targetType: TargetType,
        targetId: UUID,
        score: Double,
    ) {
        val mapped = targetType.toReportTargetType()
        val result =
            try {
                writer.applyFlagQueue(mapped, targetId, score)
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                log.warn(
                    "event={} target_type={} target_id={} score_bucket={} reason={}",
                    EVENT_DB_WRITE_FAILED,
                    targetType.wire,
                    targetId,
                    BUCKET_MID,
                    t.javaClass.simpleName,
                )
                return
            }
        when (result) {
            PerspectiveWriteResult.Applied ->
                log.info(
                    "event={} target_type={} target_id={} score_bucket={}",
                    EVENT_FLAG_APPLIED,
                    targetType.wire,
                    targetId,
                    BUCKET_MID,
                )
            PerspectiveWriteResult.QueueConflictNoOp ->
                log.info(
                    "event={} target_type={} target_id={} score_bucket={} reason=queue_conflict",
                    EVENT_FLAG_APPLIED,
                    targetType.wire,
                    targetId,
                    BUCKET_MID,
                )
            PerspectiveWriteResult.SoftDeletedTarget ->
                // FlagOnly path doesn't run an UPDATE — soft-delete race not detectable
                // here (the impl returns Applied or QueueConflictNoOp). Defense in depth.
                log.info(
                    "event={} target_type={} target_id={} score_bucket={}",
                    EVENT_SOFT_DELETED_TARGET,
                    targetType.wire,
                    targetId,
                    BUCKET_MID,
                )
        }
    }

    private fun emitDispatchFailedWarn(
        targetType: TargetType,
        targetId: UUID,
        failureKind: String,
        statusCode: Int?,
    ) {
        if (statusCode != null) {
            log.warn(
                "event={} target_type={} target_id={} failure_kind={} status_code={}",
                EVENT_DISPATCH_FAILED,
                targetType.wire,
                targetId,
                failureKind,
                statusCode,
            )
        } else {
            log.warn(
                "event={} target_type={} target_id={} failure_kind={}",
                EVENT_DISPATCH_FAILED,
                targetType.wire,
                targetId,
                failureKind,
            )
        }
    }

    companion object {
        const val ANALYZE_TIMEOUT_MILLIS: Long = 500L

        const val EVENT_DISPATCH_FAILED: String = "perspective_dispatch_failed"
        const val EVENT_HIGH_SCORE_APPLIED: String = "perspective_high_score_applied"
        const val EVENT_FLAG_APPLIED: String = "perspective_flag_applied"
        const val EVENT_KILL_SWITCH_UNAVAILABLE: String = "perspective_kill_switch_unavailable"
        const val EVENT_THRESHOLD_MISCONFIGURED: String = "perspective_threshold_misconfigured"
        const val EVENT_SOFT_DELETED_TARGET: String = "perspective_soft_deleted_target_race"
        const val EVENT_DB_WRITE_FAILED: String = "perspective_db_write_failed"

        const val FAILURE_KIND_TIMEOUT: String = "timeout"
        const val FAILURE_KIND_HTTP_4XX: String = "http_4xx"
        const val FAILURE_KIND_HTTP_5XX: String = "http_5xx"
        const val FAILURE_KIND_NETWORK: String = "network"
        const val FAILURE_KIND_PARSE: String = "parse"

        const val BUCKET_LOW: String = "low"
        const val BUCKET_MID: String = "mid"
        const val BUCKET_HIGH: String = "high"

        internal fun aggregateMax(score: PerspectiveScore): Double =
            max(
                max(score.toxicity, score.severeToxicity),
                max(score.identityAttack, score.threat),
            )

        internal fun classifyHttpStatus(statusCode: Int): String =
            when {
                statusCode in 400..499 -> FAILURE_KIND_HTTP_4XX
                statusCode in 500..599 -> FAILURE_KIND_HTTP_5XX
                else -> FAILURE_KIND_NETWORK
            }

        internal fun TargetType.toReportTargetType(): ReportTargetType =
            when (this) {
                TargetType.POST -> ReportTargetType.POST
                TargetType.REPLY -> ReportTargetType.REPLY
            }
    }
}
