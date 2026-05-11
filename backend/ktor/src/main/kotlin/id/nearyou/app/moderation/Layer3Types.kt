package id.nearyou.app.moderation

/**
 * Outcome of a single [Layer3Moderator.moderate] call. Mirrors the canonical
 * thresholds from `docs/06-Security-Privacy.md` and the
 * `text-moderation-perspective-api-layer` capability:
 *
 *  - [NoAction]: score ≤ flag threshold, OR kill-switch off, OR fail-open path
 *    (timeout, HTTP error, network exception, parse exception). No DB writes.
 *  - [FlagOnly]: flag threshold < score ≤ high-score threshold. Writes a
 *    `moderation_queue` row only — does NOT flip `is_auto_hidden`.
 *  - [AutoHide]: score > high-score threshold. Writes a `moderation_queue` row
 *    AND flips `is_auto_hidden = TRUE` in the same SQL transaction.
 *
 * The `score` field on [FlagOnly] / [AutoHide] is the per-call max across all
 * moderation categories returned by the upstream classifier (per
 * `id.nearyou.app.infra.openaimoderation.ModerationScore.maxScore`). For OpenAI
 * Moderation `omni-moderation-latest`, this is the max across 13 categories
 * (harassment, hate, illicit, self-harm, sexual, violence + sub-categories).
 *
 * **Vendor history**: the original `text-moderation-perspective-api-layer`
 * OpenSpec change planned for Google Perspective API (4 categories: toxicity,
 * severe-toxicity, identity-attack, threat). Mid-implementation Perspective
 * announced sunset (end-of-2026) so the vendor pivoted to OpenAI Moderation.
 * Aggregation semantics (per-call max → threshold mapping) preserved verbatim
 * from design.md Decision 3.
 */
sealed interface Outcome {
    data object NoAction : Outcome

    data class FlagOnly(val score: Double) : Outcome

    data class AutoHide(val score: Double) : Outcome
}

/** Target row family for a Layer 3 dispatch. */
enum class TargetType(val wire: String) {
    POST("post"),
    REPLY("reply"),
}
