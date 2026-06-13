package id.nearyou.app.screens.search

import id.nearyou.app.search.SearchOutcome
import id.nearyou.app.search.SearchQueryGuard
import id.nearyou.app.search.SearchResultDto

/**
 * The display-only projection of a search hit — the PII-stripped model the result cards render. It
 * carries ONLY the fields a card shows: deliberately NO `authorId` (UUID) and NO `rank` (internal FTS
 * score), so the rendered state STRUCTURALLY cannot leak them. [postId] is the post's own UUID (not
 * author PII), kept as a stable `LazyColumn` key and carried up to the detail push.
 */
data class SearchHit(
    val postId: String,
    val authorUsername: String,
    val authorDisplayName: String,
    val content: String,
    val createdAt: String,
)

private fun SearchResultDto.toUi(): SearchHit =
    SearchHit(
        postId = postId,
        authorUsername = authorUsername,
        authorDisplayName = authorDisplayName,
        content = content,
        createdAt = createdAt,
    )

/**
 * Pure, Compose-free projection of the search screen UI state, mirroring the timeline `*UiState`
 * pattern. The states are deterministic functions of the trimmed query + the [SearchOutcome] + the
 * in-flight flags (no wall-clock / platform dependency), and carry NO PII — [Results] hits are
 * [SearchHit] (authorId + rank already dropped).
 */
sealed interface SearchUiState {
    /** The trimmed query is below the 2-code-point threshold (incl. the empty initial state) → a
     *  directive prompt; no request is issued. */
    data object Idle : SearchUiState

    /** A first-page query in flight with no prior results → a single loading indicator. */
    data object Loading : SearchUiState

    /** `Results` with non-empty hits → the result-card list. [showLoadMore] is true while
     *  `nextOffset != null`; [isLoadingMore] drives the load-more affordance's in-flight indicator. */
    data class Results(
        val hits: List<SearchHit>,
        val showLoadMore: Boolean,
        val isLoadingMore: Boolean,
    ) : SearchUiState

    /** `Results` with empty hits → "Tidak ada hasil untuk '{query}'…" (the query is carried for formatting). */
    data class EmptyResults(val query: String) : SearchUiState

    /** `NetworkError` or retryable `Error` → network copy + a retry control. */
    data object Error : SearchUiState

    /** `PremiumGate` (403) → the Free-tier upsell panel. */
    data object PremiumGate : SearchUiState

    /** `RateLimited` (429) → the rate-limit modal + a live countdown from [retryAfterSeconds]. */
    data class RateLimited(val retryAfterSeconds: Long) : SearchUiState

    /** `Disabled` (503 kill switch) → the search-disabled copy (no retry). */
    data object Disabled : SearchUiState

    /** `SessionExpired` (terminal 401) → a neutral redirect placeholder (NO retry, NOT the connectivity
     *  copy) for the sub-second window before the `SessionInvalidator` re-route reaches `SignInScreen`. */
    data object SessionRedirect : SearchUiState
}

/**
 * Maps the trimmed [query] + the current [outcome] (null = nothing fetched yet) + the in-flight flags to
 * the screen state. Exhaustive over [SearchOutcome] — no generic fallthrough (design D4):
 *
 * - trimmed query below the guard's minimum ⇒ [Idle] (regardless of outcome — typing back below 2
 *   returns to the prompt).
 * - [isLoading] (first-page fetch in flight, no results yet) ⇒ [Loading].
 * - `Results` empty ⇒ [EmptyResults]; non-empty ⇒ [Results] (load-more shown when `nextOffset != null`).
 * - `PremiumGate` ⇒ [PremiumGate]; `RateLimited` ⇒ [RateLimited]; `Disabled` ⇒ [Disabled].
 * - `Error` / `NetworkError` ⇒ [Error]; `SessionExpired` ⇒ [SessionRedirect].
 * - a valid query with no outcome yet (the brief pre-fetch window) ⇒ [Loading].
 */
fun searchUiState(
    query: String,
    outcome: SearchOutcome?,
    isLoading: Boolean,
    isLoadingMore: Boolean,
): SearchUiState {
    if (!SearchQueryGuard.isEligible(query)) return SearchUiState.Idle
    if (isLoading) return SearchUiState.Loading
    val trimmed = SearchQueryGuard.normalize(query)
    return when (outcome) {
        null -> SearchUiState.Loading
        is SearchOutcome.Results ->
            if (outcome.hits.isEmpty()) {
                SearchUiState.EmptyResults(trimmed)
            } else {
                SearchUiState.Results(
                    hits = outcome.hits.map { it.toUi() },
                    showLoadMore = outcome.nextOffset != null,
                    isLoadingMore = isLoadingMore,
                )
            }
        SearchOutcome.PremiumGate -> SearchUiState.PremiumGate
        is SearchOutcome.RateLimited -> SearchUiState.RateLimited(outcome.retryAfterSeconds)
        SearchOutcome.Disabled -> SearchUiState.Disabled
        SearchOutcome.Error, SearchOutcome.NetworkError -> SearchUiState.Error
        SearchOutcome.SessionExpired -> SearchUiState.SessionRedirect
    }
}
