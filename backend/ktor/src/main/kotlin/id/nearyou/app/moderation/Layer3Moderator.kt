package id.nearyou.app.moderation

import id.nearyou.app.infra.openaimoderation.ModerationClient
import id.nearyou.app.infra.openaimoderation.ModerationHttpException
import id.nearyou.app.infra.openaimoderation.ModerationResponseParseException
import id.nearyou.app.infra.openaimoderation.ModerationScore
import id.nearyou.data.repository.Layer3ModerationWriter
import id.nearyou.data.repository.Layer3WriteResult
import id.nearyou.data.repository.ReportTargetType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

/**
 * Layer 3 toxicity-classifier orchestrator. Composes [ModerationClient],
 * [Layer3ConfigLoader], and [Layer3ModerationWriter] into a single [moderate]
 * entrypoint per the `text-moderation-perspective-api-layer` capability.
 *
 * Workflow per [moderate]:
 *  1. Read kill-switch via [Layer3ConfigLoader.isEnabled]; on `false` return
 *     `Outcome.NoAction` silently.
 *  2. Wrap [ModerationClient.analyze] in `withTimeoutOrNull(3000.ms)`.
 *  3. Aggregate score: per-call max across all categories
 *     (`ModerationScore.maxScore()`). For OpenAI Moderation `omni-moderation-latest`,
 *     this is the max across 13 categories (harassment, hate, illicit, self-harm,
 *     sexual, violence + sub-categories).
 *  4. Read both thresholds via the loader; check cross-flag invariant
 *     (`flag ≤ high_score`); fall back to BOTH defaults + Sentry ERROR if violated.
 *  5. Map score to `Outcome`:
 *     - `score > high_score_threshold` → `AutoHide`
 *     - `score > flag_threshold` → `FlagOnly`
 *     - else → `NoAction`
 *  6. Apply DB writes via [Layer3ModerationWriter]; emit success-path Sentry INFO
 *     with `score_bucket` (`high` / `mid` / `low`) per design Decision 7.
 *
 * Failure handling is fail-OPEN: any non-cancellation exception emits a Sentry WARN
 * with the appropriate `failure_kind` and returns `Outcome.NoAction`.
 * `CancellationException` propagates per coroutine convention so structured
 * cancellation works through the dispatcher scope.
 *
 * No-content-on-Sentry: events emitted by this class never carry the raw `content`
 * string OR the raw per-category scores — only `score_bucket` (categorical) and
 * `target_type` / `target_id` (per design Decision 7).
 *
 * **Vendor history**: the original spec planned Google Perspective API (4-attribute
 * shape: TOXICITY/SEVERE_TOXICITY/IDENTITY_ATTACK/THREAT). Mid-implementation
 * Perspective announced sunset; the vendor pivoted to OpenAI Moderation. Aggregation
 * semantics (per-call max → threshold mapping) preserved verbatim from design.md
 * Decision 3; only the per-category set changed (4 attributes → 13 categories).
 */
interface Layer3Moderator {
    suspend fun moderate(
        targetType: TargetType,
        targetId: UUID,
        content: String,
    ): Outcome
}

class DefaultLayer3Moderator(
    private val client: ModerationClient,
    private val configLoader: Layer3ConfigLoader,
    private val writer: Layer3ModerationWriter,
    private val analyzeTimeoutMillis: Long = ANALYZE_TIMEOUT_MILLIS,
) : Layer3Moderator {
    private val log = LoggerFactory.getLogger(DefaultLayer3Moderator::class.java)

    override suspend fun moderate(
        targetType: TargetType,
        targetId: UUID,
        content: String,
    ): Outcome {
        // Phase timing instrumentation (issue #88): emits structured `event=layer3_phase_timing`
        // INFO at the end of each dispatch so operators can pinpoint where time
        // is spent — config load, OpenAI call, writer — without inferring from
        // gaps between unrelated log lines. Uses monotonic clock (System.nanoTime)
        // because wall clock can be skewed by NTP corrections mid-call.
        val tStart = System.nanoTime()

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
                    CachingLayer3ConfigLoader.KILL_SWITCH_PARAM,
                    t.javaClass.simpleName,
                )
                true
            }
        val tAfterConfig = System.nanoTime()
        if (!enabled) {
            return Outcome.NoAction
        }

        // 2. Upstream moderation call with regional-baseline 3000ms budget
        //    (constructor-tunable via analyzeTimeoutMillis; see ANALYZE_TIMEOUT_MILLIS).
        val score: ModerationScore =
            try {
                withTimeoutOrNull(analyzeTimeoutMillis.milliseconds) {
                    client.analyze(content)
                }
            } catch (t: CancellationException) {
                // Per coroutine convention: do NOT swallow. Structured cancellation must
                // propagate through the dispatcher scope so JVM shutdown drain works.
                throw t
            } catch (t: ModerationHttpException) {
                emitPhaseTimings(targetType, targetId, tStart, tAfterConfig, System.nanoTime(), null, outcome = "http_error")
                emitDispatchFailedWarn(targetType, targetId, classifyHttpStatus(t.status.value), t.status.value)
                return Outcome.NoAction
            } catch (t: ModerationResponseParseException) {
                emitPhaseTimings(targetType, targetId, tStart, tAfterConfig, System.nanoTime(), null, outcome = "parse_error")
                emitDispatchFailedWarn(targetType, targetId, FAILURE_KIND_PARSE, statusCode = null)
                return Outcome.NoAction
            } catch (t: SerializationException) {
                emitPhaseTimings(targetType, targetId, tStart, tAfterConfig, System.nanoTime(), null, outcome = "parse_error")
                emitDispatchFailedWarn(targetType, targetId, FAILURE_KIND_PARSE, statusCode = null)
                return Outcome.NoAction
            } catch (t: IOException) {
                emitPhaseTimings(targetType, targetId, tStart, tAfterConfig, System.nanoTime(), null, outcome = "network_error")
                emitDispatchFailedWarn(targetType, targetId, FAILURE_KIND_NETWORK, statusCode = null)
                return Outcome.NoAction
            } catch (t: Throwable) {
                // Treat any other unhandled exception as a network/transport failure
                // (fail-OPEN). Emit the WARN, return NoAction, do NOT propagate.
                emitPhaseTimings(targetType, targetId, tStart, tAfterConfig, System.nanoTime(), null, outcome = "network_error")
                emitDispatchFailedWarn(targetType, targetId, FAILURE_KIND_NETWORK, statusCode = null)
                return Outcome.NoAction
            } ?: run {
                // withTimeoutOrNull returned null — the 3000ms budget elapsed.
                emitPhaseTimings(targetType, targetId, tStart, tAfterConfig, System.nanoTime(), null, outcome = "timeout")
                emitDispatchFailedWarn(targetType, targetId, FAILURE_KIND_TIMEOUT, statusCode = null)
                return Outcome.NoAction
            }
        val tAfterAnalyze = System.nanoTime()

        // 3. Aggregate (per-call max across all categories).
        val aggregate = score.maxScore()

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
                CachingLayer3ConfigLoader.DEFAULT_HIGH_SCORE_THRESHOLD to
                    CachingLayer3ConfigLoader.DEFAULT_FLAG_THRESHOLD
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
        val tEnd = System.nanoTime()

        val outcomeLabel =
            when (outcome) {
                is Outcome.AutoHide -> "auto_hide"
                is Outcome.FlagOnly -> "flag_only"
                Outcome.NoAction -> "no_action"
            }
        emitPhaseTimings(targetType, targetId, tStart, tAfterConfig, tAfterAnalyze, tEnd, outcomeLabel)

        return outcome
    }

    /**
     * Emits a structured phase-timing INFO log so operators can attribute total dispatch
     * latency to: config load, OpenAI analyze, DB writer, and (if applicable) post-analyze
     * threshold resolution. Added in iteration 10 (issue #88) to stop guessing where
     * JVM-side time is spent. Uses monotonic clock deltas in milliseconds.
     *
     * @param tDispatchEnd nullable — null for failure paths that exit before DB writes ran.
     */
    private fun emitPhaseTimings(
        targetType: TargetType,
        targetId: UUID,
        tStart: Long,
        tAfterConfig: Long,
        tAfterAnalyze: Long,
        tDispatchEnd: Long?,
        outcome: String,
    ) {
        val configMs = (tAfterConfig - tStart) / NANOS_PER_MS
        val analyzeMs = (tAfterAnalyze - tAfterConfig) / NANOS_PER_MS
        val writerMs = tDispatchEnd?.let { (it - tAfterAnalyze) / NANOS_PER_MS }
        val totalMs = (tDispatchEnd ?: tAfterAnalyze) - tStart
        log.info(
            "event={} target_type={} target_id={} outcome={} config_ms={} analyze_ms={} writer_ms={} total_ms={}",
            EVENT_PHASE_TIMING,
            targetType.wire,
            targetId,
            outcome,
            configMs,
            analyzeMs,
            writerMs ?: -1,
            totalMs / NANOS_PER_MS,
        )
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
            Layer3WriteResult.Applied ->
                log.info(
                    "event={} target_type={} target_id={} score_bucket={}",
                    EVENT_HIGH_SCORE_APPLIED,
                    targetType.wire,
                    targetId,
                    BUCKET_HIGH,
                )
            Layer3WriteResult.QueueConflictNoOp ->
                log.info(
                    "event={} target_type={} target_id={} score_bucket={} reason=queue_conflict",
                    EVENT_HIGH_SCORE_APPLIED,
                    targetType.wire,
                    targetId,
                    BUCKET_HIGH,
                )
            Layer3WriteResult.SoftDeletedTarget ->
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
            Layer3WriteResult.Applied ->
                log.info(
                    "event={} target_type={} target_id={} score_bucket={}",
                    EVENT_FLAG_APPLIED,
                    targetType.wire,
                    targetId,
                    BUCKET_MID,
                )
            Layer3WriteResult.QueueConflictNoOp ->
                log.info(
                    "event={} target_type={} target_id={} score_bucket={} reason=queue_conflict",
                    EVENT_FLAG_APPLIED,
                    targetType.wire,
                    targetId,
                    BUCKET_MID,
                )
            Layer3WriteResult.SoftDeletedTarget ->
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
        // 3000ms regional baseline for asia-southeast1 → OpenAI US. Empirical TTFB
        // shows a BIMODAL distribution from Cloud Run Singapore (measured 2026-05-11):
        // ~40% fast-path (550-700ms), ~40% slow-path (1500-1550ms), ~20% gateway-
        // timeout outliers (504 at 15s). A 3000ms budget covers the observed slow
        // tail with ~1500ms safety margin; still bails fast on the 504 outliers
        // (which would otherwise hold coroutine resources for 15s+). The bimodal
        // pattern is likely OpenAI's internal scheduling tier (cold vs. warm pool);
        // a tighter budget can be revisited post-launch once production traffic
        // shows a clearer distribution. Fire-and-forget dispatch means user request
        // time is unaffected. Shutdown drain budget (5s) still > this. See
        // design.md Decision 2.
        const val ANALYZE_TIMEOUT_MILLIS: Long = 3000L

        // Sentry event names: vendor-agnostic `layer3_*` (clean break from historical
        // `perspective_*` per the vendor-swap amendment in proposal.md; no operator
        // dashboards exist at pre-launch so renaming is free).
        const val EVENT_DISPATCH_FAILED: String = "layer3_dispatch_failed"
        const val EVENT_HIGH_SCORE_APPLIED: String = "layer3_high_score_applied"
        const val EVENT_FLAG_APPLIED: String = "layer3_flag_applied"
        const val EVENT_KILL_SWITCH_UNAVAILABLE: String = "layer3_kill_switch_unavailable"
        const val EVENT_THRESHOLD_MISCONFIGURED: String = "layer3_threshold_misconfigured"
        const val EVENT_PHASE_TIMING: String = "layer3_phase_timing"

        private const val NANOS_PER_MS: Long = 1_000_000L
        const val EVENT_SOFT_DELETED_TARGET: String = "layer3_soft_deleted_target_race"
        const val EVENT_DB_WRITE_FAILED: String = "layer3_db_write_failed"

        const val FAILURE_KIND_TIMEOUT: String = "timeout"
        const val FAILURE_KIND_HTTP_4XX: String = "http_4xx"
        const val FAILURE_KIND_HTTP_5XX: String = "http_5xx"
        const val FAILURE_KIND_NETWORK: String = "network"
        const val FAILURE_KIND_PARSE: String = "parse"

        const val BUCKET_LOW: String = "low"
        const val BUCKET_MID: String = "mid"
        const val BUCKET_HIGH: String = "high"

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
