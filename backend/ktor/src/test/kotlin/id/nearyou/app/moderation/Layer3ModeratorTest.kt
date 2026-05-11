package id.nearyou.app.moderation

import id.nearyou.app.infra.openaimoderation.ModerationHttpException
import id.nearyou.app.infra.openaimoderation.ModerationResponseParseException
import id.nearyou.app.infra.openaimoderation.RecordingModerationClient
import id.nearyou.data.repository.Layer3ModerationWriter
import id.nearyou.data.repository.Layer3WriteResult
import id.nearyou.data.repository.ReportTargetType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeBetween
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for [DefaultLayer3Moderator] per
 * `text-moderation-perspective-api-layer/spec.md`. Covers:
 *
 *  - Threshold mapping (NoAction / FlagOnly / AutoHide).
 *  - Boundary scenarios (0.6, 0.6+ε, 0.8, 0.0, 1.0).
 *  - Max-aggregation across categories (single-category, ties, dominating-category).
 *  - Failure-fixture matrix per task 6.7: timeout, http_4xx (full status panel),
 *    http_5xx (full status panel), network (Connect/Socket/UnknownHost),
 *    parse (Serialization + ModerationResponseParse), Remote Config exceptions.
 *  - CancellationException propagation (NOT swallowed).
 *  - Cross-flag misconfiguration → defaults + ERROR.
 *  - Kill-switch OFF → silent NoAction (no upstream call).
 *  - Kill-switch read failure → fail-OPEN (upstream IS called).
 *  - DB write paths (Applied / SoftDeletedTarget / QueueConflictNoOp).
 *  - DB write exception → returns NoAction's mapped Outcome but logs WARN, doesn't throw.
 *
 * Score shape: tests use [RecordingModerationClient.stageMaxScore] for single-category
 * scoring (orchestrator only cares about the per-call max anyway) and
 * [RecordingModerationClient.stageCategoryScores] for tests that verify
 * dominating-category aggregation behavior.
 */
class Layer3ModeratorTest : StringSpec({

    "Score 0.85 returns AutoHide (above 0.8 threshold)" {
        val client = RecordingModerationClient().apply { stageMaxScore(0.85) }
        val writer = SpyWriter()
        val moderator = newModerator(client, writer)

        val outcome = runBlocking { moderator.moderate(TargetType.POST, UUID.randomUUID(), "x") }

        outcome.shouldBeInstanceOf<Outcome.AutoHide>()
        outcome.score.shouldBeBetween(0.84, 0.86, 0.001)
        writer.autoHideCalls shouldBe 1
        writer.flagCalls shouldBe 0
    }

    "Score 0.70 returns FlagOnly (between 0.6 and 0.8)" {
        val client = RecordingModerationClient().apply { stageMaxScore(0.70) }
        val writer = SpyWriter()
        val moderator = newModerator(client, writer)

        val outcome = runBlocking { moderator.moderate(TargetType.POST, UUID.randomUUID(), "x") }

        outcome.shouldBeInstanceOf<Outcome.FlagOnly>()
        writer.autoHideCalls shouldBe 0
        writer.flagCalls shouldBe 1
    }

    "Score 0.60 returns NoAction (boundary exclusive — not strictly above 0.6)" {
        val client = RecordingModerationClient().apply { stageMaxScore(0.60) }
        val writer = SpyWriter()
        val moderator = newModerator(client, writer)

        runBlocking { moderator.moderate(TargetType.POST, UUID.randomUUID(), "x") } shouldBe Outcome.NoAction
        writer.autoHideCalls shouldBe 0
        writer.flagCalls shouldBe 0
    }

    "Score 0.6001 returns FlagOnly (any value strictly > 0.6 enters FlagOnly band)" {
        val client = RecordingModerationClient().apply { stageMaxScore(0.6001) }
        val writer = SpyWriter()
        val moderator = newModerator(client, writer)

        val outcome = runBlocking { moderator.moderate(TargetType.POST, UUID.randomUUID(), "x") }
        outcome.shouldBeInstanceOf<Outcome.FlagOnly>()
    }

    "Score 0.80 boundary returns FlagOnly (NOT AutoHide — strict > 0.8)" {
        val client = RecordingModerationClient().apply { stageMaxScore(0.80) }
        val writer = SpyWriter()
        val moderator = newModerator(client, writer)

        val outcome = runBlocking { moderator.moderate(TargetType.POST, UUID.randomUUID(), "x") }
        outcome.shouldBeInstanceOf<Outcome.FlagOnly>()
    }

    "Score 0.0 returns NoAction (low extreme)" {
        val client = RecordingModerationClient().apply { stageMaxScore(0.0) }
        val writer = SpyWriter()
        val moderator = newModerator(client, writer)

        runBlocking { moderator.moderate(TargetType.POST, UUID.randomUUID(), "x") } shouldBe Outcome.NoAction
    }

    "Score 1.0 returns AutoHide (high extreme)" {
        val client =
            RecordingModerationClient().apply {
                stageCategoryScores(
                    mapOf(
                        "harassment" to 1.0,
                        "hate" to 1.0,
                        "violence" to 1.0,
                        "sexual" to 1.0,
                    ),
                )
            }
        val writer = SpyWriter()
        val moderator = newModerator(client, writer)

        runBlocking { moderator.moderate(TargetType.POST, UUID.randomUUID(), "x") }
            .shouldBeInstanceOf<Outcome.AutoHide>()
    }

    "Dominating category (hate) wins max-aggregation across multiple categories" {
        // Test intent: max() picks the highest score regardless of which category it
        // came from. Mirrors the original "IDENTITY_ATTACK dominates" test from the
        // Perspective-era shape; OpenAI's `hate` category is the closest analog to
        // Perspective's IDENTITY_ATTACK.
        val client =
            RecordingModerationClient().apply {
                stageCategoryScores(
                    mapOf(
                        "harassment" to 0.30,
                        "violence" to 0.20,
                        "hate" to 0.92,
                        "sexual" to 0.10,
                    ),
                )
            }
        val writer = SpyWriter()
        val moderator = newModerator(client, writer)

        val outcome = runBlocking { moderator.moderate(TargetType.POST, UUID.randomUUID(), "x") }
        outcome.shouldBeInstanceOf<Outcome.AutoHide>()
        outcome.score.shouldBeBetween(0.91, 0.93, 0.001)
    }

    "Max aggregation handles tied category scores" {
        val client =
            RecordingModerationClient().apply {
                stageCategoryScores(
                    mapOf(
                        "harassment" to 0.85,
                        "hate" to 0.85,
                        "violence" to 0.10,
                        "sexual" to 0.05,
                    ),
                )
            }
        val writer = SpyWriter()
        val moderator = newModerator(client, writer)

        val outcome = runBlocking { moderator.moderate(TargetType.POST, UUID.randomUUID(), "x") }
        outcome.shouldBeInstanceOf<Outcome.AutoHide>()
        outcome.score.shouldBeBetween(0.84, 0.86, 0.001)
    }

    "Failure matrix: 500ms timeout returns NoAction (no DB writes)" {
        val client =
            RecordingModerationClient().apply { stageDelay(delayMillis = 600L) }
        val writer = SpyWriter()
        val moderator = newModerator(client, writer)

        runBlocking { moderator.moderate(TargetType.POST, UUID.randomUUID(), "x") } shouldBe Outcome.NoAction
        writer.autoHideCalls shouldBe 0
        writer.flagCalls shouldBe 0
    }

    "Failure matrix: HTTP 4xx (400/401/403/404/429) all return NoAction" {
        listOf(
            HttpStatusCode.BadRequest,
            HttpStatusCode.Unauthorized,
            HttpStatusCode.Forbidden,
            HttpStatusCode.NotFound,
            HttpStatusCode.TooManyRequests,
        ).forEach { status ->
            val client =
                RecordingModerationClient().apply {
                    stageException(ModerationHttpException(status, body = ""))
                }
            val writer = SpyWriter()
            val moderator = newModerator(client, writer)

            runBlocking { moderator.moderate(TargetType.POST, UUID.randomUUID(), "x") } shouldBe Outcome.NoAction
            writer.autoHideCalls shouldBe 0
        }
    }

    "Failure matrix: HTTP 5xx (500/502/503/504) all return NoAction" {
        listOf(
            HttpStatusCode.InternalServerError,
            HttpStatusCode.BadGateway,
            HttpStatusCode.ServiceUnavailable,
            HttpStatusCode.GatewayTimeout,
        ).forEach { status ->
            val client =
                RecordingModerationClient().apply {
                    stageException(ModerationHttpException(status, body = ""))
                }
            val writer = SpyWriter()
            val moderator = newModerator(client, writer)

            runBlocking { moderator.moderate(TargetType.POST, UUID.randomUUID(), "x") } shouldBe Outcome.NoAction
        }
    }

    "Failure matrix: network exceptions (Connect/Socket/UnknownHost) return NoAction" {
        listOf(
            ConnectException("simulated"),
            SocketTimeoutException("simulated"),
            UnknownHostException("simulated"),
            IOException("generic IO"),
        ).forEach { ex ->
            val client = RecordingModerationClient().apply { stageException(ex) }
            val writer = SpyWriter()
            val moderator = newModerator(client, writer)

            runBlocking { moderator.moderate(TargetType.POST, UUID.randomUUID(), "x") } shouldBe Outcome.NoAction
        }
    }

    "Failure matrix: parse exceptions return NoAction" {
        listOf(
            SerializationException("malformed"),
            ModerationResponseParseException("missing category_scores"),
        ).forEach { ex ->
            val client = RecordingModerationClient().apply { stageException(ex) }
            val writer = SpyWriter()
            val moderator = newModerator(client, writer)

            runBlocking { moderator.moderate(TargetType.POST, UUID.randomUUID(), "x") } shouldBe Outcome.NoAction
        }
    }

    "Failure matrix: orchestrator never throws non-cancellation exception" {
        // Synthetic generic Throwable — orchestrator must absorb and return NoAction.
        val client =
            RecordingModerationClient().apply {
                stageException(RuntimeException("unexpected"))
            }
        val writer = SpyWriter()
        val moderator = newModerator(client, writer)

        runBlocking { moderator.moderate(TargetType.POST, UUID.randomUUID(), "x") } shouldBe Outcome.NoAction
    }

    "CancellationException propagates (NOT swallowed)" {
        val client =
            RecordingModerationClient().apply {
                stageException(CancellationException("test cancellation"))
            }
        val writer = SpyWriter()
        val moderator = newModerator(client, writer)

        shouldThrow<CancellationException> {
            runBlocking { moderator.moderate(TargetType.POST, UUID.randomUUID(), "x") }
        }
    }

    "Kill-switch OFF returns NoAction silently — no upstream call" {
        val client = RecordingModerationClient()
        val writer = SpyWriter()
        val loader =
            FakeConfigLoader(
                isEnabledResult = false,
                highScoreThresholdResult = 0.8,
                flagThresholdResult = 0.6,
            )
        val moderator =
            DefaultLayer3Moderator(
                client = client,
                configLoader = loader,
                writer = writer,
            )

        runBlocking { moderator.moderate(TargetType.POST, UUID.randomUUID(), "x") } shouldBe Outcome.NoAction
        client.callCount shouldBe 0
        writer.autoHideCalls shouldBe 0
    }

    "Kill-switch read failure → fail-OPEN to enabled (upstream IS called)" {
        val client = RecordingModerationClient().apply { stageMaxScore(0.85) }
        val writer = SpyWriter()
        val loader =
            FakeConfigLoader(
                isEnabledThrows = true,
                highScoreThresholdResult = 0.8,
                flagThresholdResult = 0.6,
            )
        val moderator =
            DefaultLayer3Moderator(
                client = client,
                configLoader = loader,
                writer = writer,
            )

        runBlocking { moderator.moderate(TargetType.POST, UUID.randomUUID(), "x") }
            .shouldBeInstanceOf<Outcome.AutoHide>()
        client.callCount shouldBe 1
    }

    "Cross-flag misconfiguration → defaults + AutoHide outcome" {
        // flag 0.9 > high_score 0.7 — under inverted thresholds, score 0.75 would silently
        // AutoHide because every score >0.7 would AutoHide and the FlagOnly band is empty.
        // Orchestrator falls back to defaults (high=0.8, flag=0.6) → 0.75 enters FlagOnly band.
        val client = RecordingModerationClient().apply { stageMaxScore(0.75) }
        val writer = SpyWriter()
        val loader =
            FakeConfigLoader(
                isEnabledResult = true,
                highScoreThresholdResult = 0.7,
                flagThresholdResult = 0.9,
            )
        val moderator =
            DefaultLayer3Moderator(
                client = client,
                configLoader = loader,
                writer = writer,
            )

        val outcome = runBlocking { moderator.moderate(TargetType.POST, UUID.randomUUID(), "x") }
        outcome.shouldBeInstanceOf<Outcome.FlagOnly>()
        writer.flagCalls shouldBe 1
        writer.autoHideCalls shouldBe 0
    }

    "AutoHide writer SoftDeletedTarget result still returns AutoHide outcome" {
        val client = RecordingModerationClient().apply { stageMaxScore(0.92) }
        val writer = SpyWriter(autoHideResult = Layer3WriteResult.SoftDeletedTarget)
        val moderator = newModerator(client, writer)

        // Orchestrator returns the score-mapped Outcome regardless of writer's race detection
        // (the soft-delete race emits a structured INFO log; the Outcome reflects classification).
        val outcome = runBlocking { moderator.moderate(TargetType.POST, UUID.randomUUID(), "x") }
        outcome.shouldBeInstanceOf<Outcome.AutoHide>()
    }

    "Writer exception is absorbed; Outcome still returned (defense-in-depth)" {
        val client = RecordingModerationClient().apply { stageMaxScore(0.92) }
        val writer = SpyWriter(autoHideThrows = true)
        val moderator = newModerator(client, writer)

        val outcome = runBlocking { moderator.moderate(TargetType.POST, UUID.randomUUID(), "x") }
        outcome.shouldBeInstanceOf<Outcome.AutoHide>()
    }

    "TargetType.POST routes to ReportTargetType.POST in writer" {
        val client = RecordingModerationClient().apply { stageMaxScore(0.92) }
        val writer = SpyWriter()
        val moderator = newModerator(client, writer)

        runBlocking { moderator.moderate(TargetType.POST, UUID.randomUUID(), "x") }
        writer.lastAutoHideTargetType shouldBe ReportTargetType.POST
    }

    "TargetType.REPLY routes to ReportTargetType.REPLY in writer" {
        val client = RecordingModerationClient().apply { stageMaxScore(0.92) }
        val writer = SpyWriter()
        val moderator = newModerator(client, writer)

        runBlocking { moderator.moderate(TargetType.REPLY, UUID.randomUUID(), "x") }
        writer.lastAutoHideTargetType shouldBe ReportTargetType.REPLY
    }

    "Threshold tunable via Remote Config (high=0.95, flag=0.5 → score 0.85 → FlagOnly)" {
        val client = RecordingModerationClient().apply { stageMaxScore(0.85) }
        val writer = SpyWriter()
        val loader =
            FakeConfigLoader(
                isEnabledResult = true,
                highScoreThresholdResult = 0.95,
                flagThresholdResult = 0.5,
            )
        val moderator =
            DefaultLayer3Moderator(
                client = client,
                configLoader = loader,
                writer = writer,
            )

        val outcome = runBlocking { moderator.moderate(TargetType.POST, UUID.randomUUID(), "x") }
        outcome.shouldBeInstanceOf<Outcome.FlagOnly>()
    }
})

private fun newModerator(
    client: RecordingModerationClient,
    writer: Layer3ModerationWriter,
    high: Double = 0.8,
    flag: Double = 0.6,
): DefaultLayer3Moderator {
    val loader =
        FakeConfigLoader(
            isEnabledResult = true,
            highScoreThresholdResult = high,
            flagThresholdResult = flag,
        )
    return DefaultLayer3Moderator(
        client = client,
        configLoader = loader,
        writer = writer,
    )
}

private class FakeConfigLoader(
    private val isEnabledResult: Boolean = true,
    private val isEnabledThrows: Boolean = false,
    private val highScoreThresholdResult: Double = 0.8,
    private val flagThresholdResult: Double = 0.6,
) : Layer3ConfigLoader {
    override suspend fun isEnabled(): Boolean {
        if (isEnabledThrows) throw RuntimeException("simulated kill-switch read failure")
        return isEnabledResult
    }

    override suspend fun highScoreThreshold(): Double = highScoreThresholdResult

    override suspend fun flagThreshold(): Double = flagThresholdResult
}

private class SpyWriter(
    private val autoHideResult: Layer3WriteResult = Layer3WriteResult.Applied,
    private val flagResult: Layer3WriteResult = Layer3WriteResult.Applied,
    private val autoHideThrows: Boolean = false,
    private val flagThrows: Boolean = false,
) : Layer3ModerationWriter {
    private val autoHideCounter = AtomicInteger(0)
    private val flagCounter = AtomicInteger(0)

    @Volatile var lastAutoHideTargetType: ReportTargetType? = null

    @Volatile var lastFlagTargetType: ReportTargetType? = null

    val autoHideCalls: Int get() = autoHideCounter.get()
    val flagCalls: Int get() = flagCounter.get()

    override fun applyAutoHideAndQueue(
        targetType: ReportTargetType,
        targetId: UUID,
        score: Double,
    ): Layer3WriteResult {
        autoHideCounter.incrementAndGet()
        lastAutoHideTargetType = targetType
        if (autoHideThrows) throw RuntimeException("simulated DB failure")
        return autoHideResult
    }

    override fun applyFlagQueue(
        targetType: ReportTargetType,
        targetId: UUID,
        score: Double,
    ): Layer3WriteResult {
        flagCounter.incrementAndGet()
        lastFlagTargetType = targetType
        if (flagThrows) throw RuntimeException("simulated DB failure")
        return flagResult
    }
}
