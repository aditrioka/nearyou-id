package id.nearyou.app.infra.perspective

/**
 * Plain-Kotlin score returned by [PerspectiveClient.analyze]. The four attributes
 * (TOXICITY, SEVERE_TOXICITY, IDENTITY_ATTACK, THREAT) are the canonical Perspective
 * categories per `docs/06-Security-Privacy.md` Layer 3. Values are the per-attribute
 * confidence in `[0.0, 1.0]`.
 *
 * Aggregation policy lives at the orchestrator (`:backend:ktor` `PerspectiveModerator`):
 * the orchestrator computes `max(toxicity, severeToxicity, identityAttack, threat)` and
 * compares against canonical thresholds. The infra module returns the raw four
 * attributes so the policy is consumer-side.
 */
data class PerspectiveScore(
    val toxicity: Double,
    val severeToxicity: Double,
    val identityAttack: Double,
    val threat: Double,
)

/**
 * Public interface for Google Perspective API toxicity classification. Vendor SDK
 * types (Ktor `HttpClient`, JSON wrappers) are entirely encapsulated by the
 * implementation — this interface returns plain Kotlin types.
 *
 * Failure semantics: implementations SHALL throw a typed exception on HTTP failure
 * (4xx / 5xx), network failure (connect refused, DNS, TLS), or response parse failure.
 * The orchestrator (`PerspectiveModerator`) absorbs every non-cancellation failure
 * mode and returns `Outcome.NoAction`. `CancellationException` SHALL propagate per
 * coroutine convention so structured cancellation works through the dispatcher scope.
 */
interface PerspectiveClient {
    suspend fun analyze(content: String): PerspectiveScore
}
