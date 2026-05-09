package id.nearyou.app.infra.perspective

import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * In-memory [PerspectiveClient] test fixture that records every `analyze(content)`
 * call and lets tests stage per-call responses (a [PerspectiveScore], a thrown
 * exception, or a suspending delay).
 *
 * Published via `java-test-fixtures` so `:backend:ktor`'s tests can consume it via
 * `testImplementation(testFixtures(projects.infra.perspective))`.
 *
 * Thread-safe: backed by [ConcurrentLinkedQueue] for both call records and staged
 * responses so concurrent dispatches in integration tests don't race.
 */
class RecordingPerspectiveClient : PerspectiveClient {
    private val staged: ConcurrentLinkedQueue<StagedResponse> = ConcurrentLinkedQueue()
    private val recorded: ConcurrentLinkedQueue<String> = ConcurrentLinkedQueue()
    private var defaultScore: PerspectiveScore =
        PerspectiveScore(toxicity = 0.0, severeToxicity = 0.0, identityAttack = 0.0, threat = 0.0)

    /** Snapshot of every `analyze(content)` invocation, in invocation order. */
    val analyzeCalls: List<String>
        get() = recorded.toList()

    /** Number of recorded `analyze` invocations. */
    val callCount: Int
        get() = recorded.size

    /** Stage a successful score response for the next `analyze` call. */
    fun stageScore(score: PerspectiveScore) {
        staged.add(StagedResponse.Score(score))
    }

    /** Stage a successful score response built inline from individual attributes. */
    fun stageScore(
        toxicity: Double = 0.0,
        severeToxicity: Double = 0.0,
        identityAttack: Double = 0.0,
        threat: Double = 0.0,
    ) {
        staged.add(
            StagedResponse.Score(
                PerspectiveScore(toxicity, severeToxicity, identityAttack, threat),
            ),
        )
    }

    /** Stage a thrown exception for the next `analyze` call. */
    fun stageException(t: Throwable) {
        staged.add(StagedResponse.Throw(t))
    }

    /**
     * Stage a suspending delay (in milliseconds) before the next response resolves.
     * Useful for exercising the orchestrator's `withTimeoutOrNull(500.ms)` budget.
     */
    fun stageDelay(
        delayMillis: Long,
        score: PerspectiveScore =
            PerspectiveScore(toxicity = 0.0, severeToxicity = 0.0, identityAttack = 0.0, threat = 0.0),
    ) {
        staged.add(StagedResponse.Delay(delayMillis, score))
    }

    /**
     * Set the score returned when the staged queue is exhausted. Defaults to
     * `(0,0,0,0)` (NoAction band).
     */
    fun setDefaultScore(score: PerspectiveScore) {
        defaultScore = score
    }

    /** Clear recorded calls AND staged responses. Useful between test scenarios. */
    fun reset() {
        staged.clear()
        recorded.clear()
        defaultScore =
            PerspectiveScore(toxicity = 0.0, severeToxicity = 0.0, identityAttack = 0.0, threat = 0.0)
    }

    override suspend fun analyze(content: String): PerspectiveScore {
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
        data class Score(val score: PerspectiveScore) : StagedResponse

        data class Throw(val cause: Throwable) : StagedResponse

        data class Delay(val delayMillis: Long, val score: PerspectiveScore) : StagedResponse
    }
}
