package id.nearyou.app.infra.openaimoderation

/**
 * Vendor-agnostic moderation score returned by [ModerationClient.analyze]. Holds
 * the per-category 0..1 confidence scores returned by an upstream toxicity
 * classifier — `Map<String, Double>` keeps the interface neutral so future
 * vendor swaps (e.g., from OpenAI Moderation → Azure Content Safety) don't
 * require changing the consumer's data shape.
 *
 * For the canonical OpenAI Moderation API binding ([OpenAiModerationClient]),
 * [categoryScores] contains 13 entries with keys matching the OpenAI category
 * names verbatim (slash-separated subcategories like `"harassment/threatening"`
 * are preserved as keys, not normalized).
 *
 * Aggregation policy lives at the orchestrator (`:backend:ktor` `Layer3Moderator`):
 * the orchestrator computes [maxScore] and compares against canonical thresholds.
 * The infra module returns raw scores; aggregation + threshold mapping is
 * consumer-side per the `text-moderation-perspective-api-layer` capability spec.
 */
data class ModerationScore(
    val categoryScores: Map<String, Double>,
) {
    /**
     * Per-call max across all categories — input to the orchestrator's threshold
     * mapping (`>high_score_threshold` → AutoHide; `>flag_threshold` → FlagOnly).
     * Returns `0.0` for an empty map (defensive — vendor returning no categories
     * is treated as NoAction-equivalent).
     */
    fun maxScore(): Double = categoryScores.values.maxOrNull() ?: 0.0
}

/**
 * Public interface for asynchronous content-toxicity classification (Layer 3 of
 * the multi-layer text moderation pipeline). Vendor SDK types (Ktor `HttpClient`,
 * JSON wrappers) are entirely encapsulated by the implementation — this interface
 * returns plain Kotlin types.
 *
 * **Vendor history note:** the original `text-moderation-perspective-api-layer`
 * OpenSpec change planned for Google Perspective API. Mid-implementation Perspective
 * announced sunset (end-of-2026) so the implementation pivoted to OpenAI Moderation
 * API (`omni-moderation-latest` model). The interface stays vendor-agnostic so
 * future swaps don't require consumer changes. The orchestrator class name
 * (`Layer3Moderator`) and the SQL trigger string (`perspective_api_high_score`,
 * fixed in V9 schema CHECK constraint) carry the historical "perspective" naming —
 * those are schema-level artifacts; the Kotlin code surface is vendor-neutral.
 *
 * Failure semantics: implementations SHALL throw a typed exception on HTTP
 * failure (4xx / 5xx), network failure (connect refused, DNS, TLS), or response
 * parse failure. The orchestrator absorbs every non-cancellation failure mode
 * and returns `Outcome.NoAction`. `CancellationException` SHALL propagate per
 * coroutine convention so structured cancellation works through the dispatcher
 * scope.
 */
interface ModerationClient {
    suspend fun analyze(content: String): ModerationScore
}
