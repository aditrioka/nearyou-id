package id.nearyou.app.moderation

import id.nearyou.app.core.domain.moderation.KeywordMatcher
import org.slf4j.LoggerFactory

/**
 * Verdict produced by [TextModerator.moderate]. Mapped at the route layer to:
 *  - [Allow]: existing INSERT path proceeds, no `moderation_queue` row.
 *  - [Reject]: HTTP 400 with `code = "content_moderated_profanity"` + Bahasa Indonesia
 *    message; NO content INSERT, NO queue row, NO notification, NO broadcast.
 *  - [Flag]: existing INSERT proceeds; a `moderation_queue` row is inserted in the
 *    same SQL transaction with `trigger = 'uu_ite_keyword_match'`. The row is NOT
 *    auto-hidden — Layer 2 is soft-flag only per `docs/06-Security-Privacy.md:158`.
 *
 * `matchedKeywords` is the list of distinct keywords that triggered the verdict
 * (Reject / Flag only). Reject does NOT include this list in the HTTP response —
 * leaking matched profanity terms aids bypass attempts. Sentry breadcrumbs include
 * only `matched_keyword_count`, not the keywords themselves
 * (per `### Requirement: TextModerator Sentry events do NOT carry user content`).
 */
sealed interface Verdict {
    data object Allow : Verdict

    data class Reject(val matchedKeywords: List<String>) : Verdict

    data class Flag(val matchedKeywords: List<String>) : Verdict
}

/**
 * Orchestrates the [ModerationListLoader] + [KeywordMatcher] into a single
 * [moderate] entrypoint. Layer 1 (profanity) short-circuits to [Verdict.Reject]
 * on any single match; Layer 2 (UU ITE) is consulted only if Layer 1 produces
 * no hits, and produces [Verdict.Flag] when distinct-match count meets or exceeds
 * the configured threshold. Otherwise [Verdict.Allow].
 *
 * Sentry breadcrumbs are emitted for Reject + Flag with content-free payloads —
 * the original user content + matched keywords are NEVER in the breadcrumb. This
 * matches the `observability-otel-foundation` forbidden-attributes contract.
 *
 * The orchestrator is a Koin singleton — `moderate(...)` is invoked once per
 * content-write request; the underlying loader handles its own caching.
 */
class TextModerator(
    private val loader: ModerationListLoader,
) {
    private val log = LoggerFactory.getLogger(TextModerator::class.java)

    fun moderate(content: String): Verdict {
        // Layer 1 — profanity blocklist. Single hit short-circuits to Reject.
        val profanityList = loader.load(ModerationList.ProfanityList)
        val profanityResult = KeywordMatcher.match(content, profanityList)
        if (profanityResult.matchCount >= 1) {
            emitBreadcrumb(EVENT_REJECTED, "reject", profanityResult.matchCount)
            return Verdict.Reject(matchedKeywords = profanityResult.matchedKeywords)
        }

        // Layer 2 — UU ITE wordlist + threshold. Layer 2 is consulted ONLY if Layer 1
        // produced no hits (profanity-precedence short-circuit). The loader's Layer 2
        // calls are NOT made when Layer 1 hits, which is verifiable via mock-spy
        // call counts in the test suite.
        val uuIteList = loader.load(ModerationList.UuIteList)
        val threshold = loader.loadThreshold()
        val uuIteResult = KeywordMatcher.match(content, uuIteList)
        if (uuIteResult.matchCount >= threshold) {
            emitBreadcrumb(EVENT_FLAGGED, "flag", uuIteResult.matchCount)
            return Verdict.Flag(matchedKeywords = uuIteResult.matchedKeywords)
        }

        return Verdict.Allow
    }

    /**
     * Emits a Sentry-compatible breadcrumb for a Reject / Flag outcome. Payload
     * contains only the event name, verdict kind, and matched keyword COUNT —
     * never the content or the keyword strings.
     */
    private fun emitBreadcrumb(
        event: String,
        verdictKind: String,
        matchedKeywordCount: Int,
    ) {
        // Sentry events on this platform are surfaced via the SLF4J → logback → Sentry
        // appender pipeline; structured `event=...` fields are picked up as Sentry
        // tags by the appender. The matching pattern is the same as
        // `chat-realtime-broadcast`'s `event=chat_realtime_publish_failed` shape.
        log.info(
            "event={} verdict_kind={} matched_keyword_count={}",
            event,
            verdictKind,
            matchedKeywordCount,
        )
    }

    companion object {
        const val EVENT_REJECTED: String = "content_moderation_rejected"
        const val EVENT_FLAGGED: String = "content_moderation_flagged"
    }
}
