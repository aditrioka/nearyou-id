package id.nearyou.app.search

import id.nearyou.app.config.RemoteConfig
import id.nearyou.data.repository.SearchHit
import id.nearyou.data.repository.SearchRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.text.Normalizer
import java.util.UUID

/**
 * Premium full-text + fuzzy search orchestrator. The HTTP layer wraps each
 * outcome in the route's response shape; this service owns the gate ordering
 * and the DB call:
 *
 *   1. **Length guard** — URL-decode → NFKC-normalize → trim Unicode whitespace
 *      → reject `length < 2` or `length > 100` Unicode code points with
 *      [Result.InvalidQueryLength]. Pre-normalization length is NOT considered.
 *   2. **Kill switch** — read the `search_enabled` Remote Config flag. When
 *      `false`, return [Result.KillSwitchActive] WITHOUT consuming a rate-limit
 *      slot (probe-polling MUST NOT burn user quota during incidents).
 *      When `null` (unset / malformed / SDK error), default to `true` (the
 *      canonical default per `docs/05-Implementation.md:1413`).
 *   3. **Premium gate** — trust the principal's `subscription_status` claim
 *      (consistent with project convention; the JWT-TTL window risk is bounded
 *      by the access-token lifetime and is closed by the future RevenueCat
 *      webhook's `users.token_version` bump). Reject Free callers with
 *      [Result.PremiumRequired].
 *   4. **Rate limit** — [SearchRateLimiter.tryAcquire]. On exhausted, return
 *      [Result.RateLimited] with the `Retry-After` value taken from the
 *      limiter's `retryAfterSeconds`.
 *   5. **Repository call** — [SearchRepository.search] runs the canonical FTS
 *      query under `SET LOCAL pg_trgm.similarity_threshold = 0.3`. Returns
 *      `[SearchResult]` with the hits + the `nextOffset` cursor.
 *
 * The kill switch is checked BEFORE the Premium gate so a misconfigured
 * Premium check (e.g., flag flipped during a Premium auth incident) does not
 * gate ahead of the kill switch. The Premium gate runs BEFORE the rate limit
 * so a Free caller probing the endpoint does not consume the (non-existent)
 * Free rate-limit budget — the 403 path is cheaper than the 429 path.
 *
 * Lettuce's sync API blocks the calling thread on Netty I/O; per the
 * `RateLimiter` contract (interface non-suspending to preserve V9
 * compatibility), the wrap belongs at this call site. The [tryAcquire]
 * call runs inside `withContext(Dispatchers.IO)` to keep the Ktor coroutine
 * dispatcher unblocked, mirroring `LikeService` precedent.
 */
class SearchService(
    private val repository: SearchRepository,
    private val rateLimiter: SearchRateLimiter,
    private val remoteConfig: RemoteConfig,
) {
    /**
     * Outcome of a search attempt. The HTTP layer maps each variant to a
     * status code + envelope. Mirrors `LikeService.Result` precedent so error
     * mapping stays a single declarative `when` in the route handler.
     */
    sealed interface Result {
        data class Success(val hits: List<SearchHit>, val nextOffset: Int?) : Result

        /** Query failed length guard (post-NFKC-trim length not in `2..100`). */
        data object InvalidQueryLength : Result

        /** `search_enabled` flag is `false`; no DB or Redis call performed. */
        data object KillSwitchActive : Result

        /** Caller is not Premium; no DB or Redis call performed. */
        data object PremiumRequired : Result

        /** Hourly cap exhausted. [retryAfterSeconds] is the suggested wait. */
        data class RateLimited(val retryAfterSeconds: Long) : Result
    }

    suspend fun search(
        viewerId: UUID,
        viewerSubscriptionStatus: String,
        query: String,
        offset: Int,
    ): Result {
        require(offset >= 0) { "offset must be non-negative — route layer validates" }

        // 1. Length guard. NFKC-normalize then trim — rejects whitespace-only
        //    queries (post-trim length 0) AND single-char queries (length 1)
        //    BEFORE any Redis or DB call so probe-polling burns nothing.
        val normalized = Normalizer.normalize(query, Normalizer.Form.NFKC).trim()
        val codePoints = normalized.codePointCount(0, normalized.length)
        if (codePoints < MIN_QUERY_LENGTH || codePoints > MAX_QUERY_LENGTH) {
            return Result.InvalidQueryLength
        }

        // 2. Kill switch — `search_enabled` Remote Config flag, default TRUE.
        //    null (unset / malformed / SDK error) coerces to TRUE per the
        //    canonical default at docs/05-Implementation.md:1413. Defense in
        //    depth: catch any rogue throwable (the RemoteConfig contract says
        //    impls catch + return null, but a misbehaving binding could throw).
        val enabled =
            try {
                remoteConfig.getBoolean(SEARCH_ENABLED_KEY) ?: true
            } catch (t: Throwable) {
                log.warn(
                    "event=remote_config_error key={} fallback=default_true viewer={} message={}",
                    SEARCH_ENABLED_KEY,
                    viewerId,
                    t.message,
                )
                true
            }
        if (!enabled) {
            return Result.KillSwitchActive
        }

        // 3. Premium gate. Trust the principal claim — see class kdoc on the
        //    JWT-TTL window risk + the closure mechanism via token_version.
        if (viewerSubscriptionStatus !in PREMIUM_STATES) {
            return Result.PremiumRequired
        }

        // 4. Rate limit (60/hour, sliding window). The canonical Layer-2 path:
        //    SearchRateLimiter wraps the shared `RateLimiter` interface (Redis-
        //    backed in production, InMemory in tests).
        val outcome =
            withContext(Dispatchers.IO) {
                rateLimiter.tryAcquire(viewerId)
            }
        when (outcome) {
            is SearchRateLimiter.Outcome.RateLimited ->
                return Result.RateLimited(retryAfterSeconds = outcome.retryAfterSeconds)
            is SearchRateLimiter.Outcome.Allowed -> Unit
        }

        // 5. Repository call — runs SET LOCAL pg_trgm.similarity_threshold + the
        //    canonical FTS query. Wrapped in withContext(IO) so JDBC blocking
        //    does not stall Ktor's coroutine dispatcher. The repository owns
        //    its own connection.use {} so the pool is returned promptly.
        val hits =
            withContext(Dispatchers.IO) {
                repository.search(viewerId, normalized, offset)
            }
        // next_offset: when the page is full (size == PAGE_SIZE), there MAY be
        // more results, so emit `offset + size`. When the page is short, the
        // corpus is exhausted at this offset — emit null. Note: a corpus of
        // exactly 20 hits will produce next_offset = 20, then the follow-up
        // page returns 0 hits + next_offset = null. Spec scenario "Exactly-20-
        // match boundary" documents this behaviour.
        val nextOffset =
            if (hits.size == SearchRepository.PAGE_SIZE) {
                offset + hits.size
            } else {
                null
            }
        return Result.Success(hits = hits, nextOffset = nextOffset)
    }

    companion object {
        const val MIN_QUERY_LENGTH: Int = 2
        const val MAX_QUERY_LENGTH: Int = 100
        const val MAX_OFFSET: Int = 10_000
        const val SEARCH_ENABLED_KEY: String = "search_enabled"

        /** Subscription statuses that are allowed Premium access. */
        internal val PREMIUM_STATES = setOf("premium_active", "premium_billing_retry")

        private val log = LoggerFactory.getLogger(SearchService::class.java)
    }
}
