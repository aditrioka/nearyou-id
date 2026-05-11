package id.nearyou.app.infra.openaimoderation

import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * In-memory [ModerationClient] test fixture that records every `analyze(content)`
 * call and lets tests stage per-call responses (a [ModerationScore], a thrown
 * exception, or a suspending delay).
 *
 * Published via `java-test-fixtures` so `:backend:ktor`'s tests can consume it via
 * `testImplementation(testFixtures(projects.infra.openaiModeration))`.
 *
 * Thread-safe: backed by [ConcurrentLinkedQueue] for both call records and staged
 * responses so concurrent dispatches in integration tests don't race.
 *
 * **OpenAI category-shape helpers**: [stageMaxScore] is the most ergonomic shortcut
 * — most orchestrator tests only care about "what's the max across categories".
 * [stageCategoryScores] gives full control when a test needs to verify behavior
 * across specific OpenAI sub-categories (e.g., `"harassment/threatening"`).
 */
class RecordingModerationClient : ModerationClient {
    private val staged: ConcurrentLinkedQueue<StagedResponse> = ConcurrentLinkedQueue()
    private val recorded: ConcurrentLinkedQueue<String> = ConcurrentLinkedQueue()
    private var defaultScore: ModerationScore = ModerationScore(categoryScores = emptyMap())

    /** Snapshot of every `analyze(content)` invocation, in invocation order. */
    val analyzeCalls: List<String>
        get() = recorded.toList()

    /** Number of recorded `analyze` invocations. */
    val callCount: Int
        get() = recorded.size

    /** Stage a successful score response for the next `analyze` call. */
    fun stageScore(score: ModerationScore) {
        staged.add(StagedResponse.Score(score))
    }

    /**
     * Stage a response where ONE category has the supplied score and all other
     * canonical OpenAI categories are zero. The orchestrator's `maxScore()` will
     * return [maxScore]. Most-ergonomic shortcut for tests that only care about
     * the threshold-band outcome (AutoHide vs FlagOnly vs NoAction).
     *
     * Defaults the category to `"harassment"` — a content-neutral OpenAI category
     * that's most likely to show up in real Indonesian harassment scenarios.
     */
    fun stageMaxScore(
        maxScore: Double,
        category: String = "harassment",
    ) {
        staged.add(
            StagedResponse.Score(
                ModerationScore(categoryScores = mapOf(category to maxScore)),
            ),
        )
    }

    /**
     * Stage a response with explicit per-category scores. Use when a test needs
     * to verify behavior across specific OpenAI sub-categories (e.g., tests that
     * assert IDENTITY_ATTACK-equivalent dominates aggregation, or that
     * sexual/minors triggers regardless of other category levels).
     */
    fun stageCategoryScores(scores: Map<String, Double>) {
        staged.add(StagedResponse.Score(ModerationScore(categoryScores = scores)))
    }

    /** Stage a thrown exception for the next `analyze` call. */
    fun stageException(t: Throwable) {
        staged.add(StagedResponse.Throw(t))
    }

    /**
     * Stage a suspending delay (in milliseconds) before the next response resolves.
     * Useful for exercising the orchestrator's `withTimeoutOrNull(3000.ms)` budget
     * (or a test-only shorter override via `analyzeTimeoutMillis` constructor param).
     */
    fun stageDelay(
        delayMillis: Long,
        score: ModerationScore = ModerationScore(categoryScores = emptyMap()),
    ) {
        staged.add(StagedResponse.Delay(delayMillis, score))
    }

    /**
     * Set the score returned when the staged queue is exhausted. Defaults to
     * an empty `categoryScores` map (which yields `maxScore() = 0.0` —
     * NoAction band).
     */
    fun setDefaultScore(score: ModerationScore) {
        defaultScore = score
    }

    /** Clear recorded calls AND staged responses. Useful between test scenarios. */
    fun reset() {
        staged.clear()
        recorded.clear()
        defaultScore = ModerationScore(categoryScores = emptyMap())
    }

    override suspend fun analyze(content: String): ModerationScore {
        recorded.add(content)
        val response = staged.poll()
        return when (response) {
            null -> defaultScore
            is StagedResponse.Score -> response.score
            is StagedResponse.Throw -> throw response.cause
            is StagedResponse.Delay -> {
                delay(response.delayMillis)
                response.score
            }
        }
    }

    private sealed interface StagedResponse {
        data class Score(val score: ModerationScore) : StagedResponse

        data class Throw(val cause: Throwable) : StagedResponse

        data class Delay(val delayMillis: Long, val score: ModerationScore) : StagedResponse
    }
}
