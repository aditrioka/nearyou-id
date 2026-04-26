package id.nearyou.data.repository

import java.time.Instant
import java.util.UUID

/**
 * Premium full-text + fuzzy search over `posts` × `users` joined through
 * `visible_posts` and `visible_users`. The single read method [search] runs the
 * canonical FTS query verbatim from `docs/05-Implementation.md:1163-1181`:
 *
 *   - `FROM visible_posts p JOIN visible_users u ON p.author_id = u.id`
 *   - `p.content_tsv @@ plainto_tsquery('simple', :query)
 *      OR p.content % :query
 *      OR u.username % :query`
 *   - `p.is_auto_hidden = FALSE`
 *   - bidirectional `user_blocks` NOT-IN against [viewerId] (mandatory per
 *     `BlockExclusionJoinRule`)
 *   - Premium-private-profile gate (`private_profile_opt_in = FALSE OR
 *     subscription_status NOT IN ('premium_active', 'premium_billing_retry') OR
 *     EXISTS (follow))`
 *   - `ORDER BY rank DESC, p.created_at DESC LIMIT 20 OFFSET :offset`
 *
 * Implementations MUST execute `SET LOCAL pg_trgm.similarity_threshold = 0.3`
 * (or equivalent connection-scoped pin) before each FTS query so the
 * `%` operator's match semantics stay aligned with the canonical default and
 * a future Postgres GUC change does not silently shift behaviour.
 *
 * The implementation lives in `:backend:ktor` (`JdbcSearchRepository`) because
 * it needs JDBC + DataSource. Interface here so domain/test code can depend on
 * the contract without pulling vendor SDKs.
 *
 * Visibility caveat: the canonical query reads through `visible_posts JOIN
 * visible_users` so a shadow-banned VIEWER searching never sees their own posts
 * (their author row is filtered out). This is intentional — search is a
 * discovery surface, not a self-archive surface; the own-content path documented
 * at `docs/05-Implementation.md:1885` is for the user-profile "my posts"
 * endpoint, NOT for search.
 */
interface SearchRepository {
    /**
     * Execute the canonical FTS query for [viewerId] against [query] starting
     * at [offset]. Returns at most [PAGE_SIZE] hits, ranked by `ts_rank` then
     * `created_at DESC`.
     *
     * The caller is responsible for applying the length guard (2..100 code
     * points post-NFKC-trim), the kill-switch check, the Premium gate, and
     * the rate-limit gate BEFORE calling — this repository runs no gates of
     * its own.
     */
    fun search(
        viewerId: UUID,
        query: String,
        offset: Int,
    ): List<SearchHit>

    companion object {
        /** Canonical page size from `docs/05-Implementation.md:1180`. */
        const val PAGE_SIZE: Int = 20
    }
}

/**
 * One row in a search result page. `rank` is the `ts_rank` value so callers
 * can sort or window by score if a future feature wants it; for the V1
 * endpoint the order is already DESC and clients SHOULD treat it as opaque.
 *
 * V1 deliberately does NOT surface post coordinates in the search response
 * — search is a global discovery surface (no location filter, per
 * `docs/02-Product.md:278`), so per-result geo would be unused noise. A
 * future variant that returns nearby-search may extend the result row with
 * fuzzed coordinates from `posts.display_location` (NEVER `actual_location`
 * per the Coordinate Fuzzing rule — see `CLAUDE.md` § Spatial).
 */
data class SearchHit(
    val postId: UUID,
    val authorId: UUID,
    val authorUsername: String,
    val authorDisplayName: String,
    val content: String,
    val createdAt: Instant,
    val rank: Float,
)
