package id.nearyou.app.moderation

/**
 * Outcome of a single [PerspectiveModerator.moderate] call. Mirrors the canonical
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
 * The `score` field on [FlagOnly] / [AutoHide] is the per-call max across the four
 * Perspective attributes (TOXICITY, SEVERE_TOXICITY, IDENTITY_ATTACK, THREAT) — the
 * canonical aggregation per design.md Decision 3.
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
