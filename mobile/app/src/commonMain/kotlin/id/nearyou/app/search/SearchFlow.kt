package id.nearyou.app.search

/**
 * The search orchestration contract consumed by `SearchScreen` / `SearchViewModel`. The production
 * binding is [SearchRepository]; commonTest substitutes a `FakeSearchFlow` so the screen + ViewModel
 * tests can drive a specific outcome / assert (re-)invocation without a backend — mirroring the timeline
 * `GlobalTimelineFlow` seam.
 */
interface SearchFlow {
    /**
     * Issues `GET /api/v1/search?q=<query>&offset=<offset>` and maps the result to exactly one
     * [SearchOutcome]. The first page passes `offset = 0`; a load-more passes the retained
     * [SearchOutcome.Results.nextOffset].
     */
    suspend fun search(
        query: String,
        offset: Int,
    ): SearchOutcome
}

/**
 * Every search fetch maps to EXACTLY one member, keyed on the HTTP **status** / transport-failure type —
 * NOT on a parsed `error.code` — with no generic "load failed" fallthrough (design D4). A terminal `401`
 * (one that survived the shipped `Auth` `refreshTokens` because the refresh itself failed) maps to
 * [SessionExpired] — the shipped `Auth` plugin + `SessionInvalidator` still own the actual re-route to
 * sign-in; this member only guarantees the brief pre-re-route render is a neutral redirect placeholder,
 * never the connectivity copy.
 */
sealed interface SearchOutcome {
    /**
     * HTTP 200. Carries the raw parsed [hits] (incl. the `authorId` UUID + `rank` score that the
     * UI-state projection STRIPS — PII / internal-ranking never reaches the rendered state) and the
     * bare [nextOffset] (the next page's `offset`, or `null` at the end of results).
     */
    data class Results(
        val hits: List<SearchResultDto>,
        val nextOffset: Int?,
    ) : SearchOutcome

    /** HTTP 403 `premium_required` — the Free-tier gate (the screen renders the upsell panel). */
    data object PremiumGate : SearchOutcome

    /** HTTP 429 `rate_limited` — carries the parsed `Retry-After` seconds (the screen renders the
     *  rate-limit modal + countdown). An absent/unparseable header maps to `RateLimited(0)`. */
    data class RateLimited(val retryAfterSeconds: Long) : SearchOutcome

    /** HTTP 503 `search_disabled` — the `search_enabled` Remote Config kill switch. */
    data object Disabled : SearchOutcome

    /** HTTP 400 (`invalid_query_length` / `invalid_offset` — not expected given the client-side guard)
     *  — retryable, logged. */
    data object Error : SearchOutcome

    /** Terminal HTTP 401 (survived the `Auth` refresh) — session expired. The screen renders a neutral
     *  redirect placeholder (NO retry, NOT the connectivity copy). MUST NOT map to [NetworkError]/[Error]. */
    data object SessionExpired : SearchOutcome

    /** HTTP 5xx, transport/IO failure, or any unenumerated non-2xx status — retryable (connectivity copy). */
    data object NetworkError : SearchOutcome
}
