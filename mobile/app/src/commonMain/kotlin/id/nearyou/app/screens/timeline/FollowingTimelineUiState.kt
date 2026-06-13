package id.nearyou.app.screens.timeline

import id.nearyou.app.timeline.FollowingPostDto
import id.nearyou.app.timeline.FollowingTimelineOutcome

/**
 * The display-only projection of a Following post — the PII-stripped model the cards render. It carries
 * ONLY the fields a card shows: there is deliberately NO `authorUserId` (UUID), NO `latitude`/`longitude`,
 * and (Following has no spatial filter) **NO distance** — so the rendered state STRUCTURALLY cannot leak
 * the author identifier, raw coordinates, or a distance. It carries the author **display** identity
 * ([authorUsername] + [authorDisplayName]) — display data, not the banned UUID. [id] is the post's own
 * UUID (not author PII), kept as a stable `LazyColumn` key. Mirrors [GlobalTimelinePost] (Following is
 * Global minus the spatial filter).
 */
data class FollowingTimelinePost(
    val id: String,
    val authorUsername: String,
    val authorDisplayName: String,
    val content: String,
    val cityName: String,
    val createdAt: String,
    val likedByViewer: Boolean,
    val replyCount: Int,
)

private fun FollowingPostDto.toUi(): FollowingTimelinePost =
    FollowingTimelinePost(
        id = id,
        authorUsername = authorUsername,
        authorDisplayName = authorDisplayName,
        content = content,
        cityName = cityName,
        createdAt = createdAt,
        likedByViewer = likedByViewer,
        replyCount = replyCount,
    )

/**
 * Pure, Compose-free projection of the Following screen UI state, mirroring [GlobalTimelineUiState]. The
 * six states are deterministic functions of the [FollowingTimelineOutcome] + in-flight flag (no
 * wall-clock / platform dependency), and carry NO PII — the [Content] / [SoftLimit] posts are
 * [FollowingTimelinePost] (author id + coordinates already dropped; no distance exists).
 *
 * **The one divergence from Global:** Following-empty is a REAL, expected state (the caller follows
 * nobody, or follows people with no recent posts), so — unlike Global-empty (which reuses the loading
 * skeleton because Global is effectively never empty) — [Empty] is a directive state that the screen
 * renders with the `timeline_following_placeholder` copy + a "Lihat Global" CTA (per
 * `docs/03-UX-Design.md` § Empty State "Following empty → direct user to Nearby/Global"), exactly like
 * the Nearby sparse-area empty. [Empty] therefore stays distinct from [Loading] in BOTH the projection
 * and the render.
 */
sealed interface FollowingTimelineUiState {
    /** Initial load (no content yet) → skeleton + loading copy. NOT used during a refresh of loaded
     *  content (that keeps rendering [Content]; the refresh indicator is the `PullToRefreshBox` only). */
    data object Loading : FollowingTimelineUiState

    /** `Loaded`, non-empty posts, no soft upsell → the post-card list. */
    data class Content(val posts: List<FollowingTimelinePost>) : FollowingTimelineUiState

    /** `Loaded`, empty posts, no `upsell` → the DIRECTIVE empty state: `timeline_following_placeholder`
     *  copy + the "Lihat Global" CTA (NOT the loading skeleton Global-empty uses). */
    data object Empty : FollowingTimelineUiState

    /** `Loaded`, empty posts, `upsell.hard` → the hourly read-cap copy (distinct from [Empty]). */
    data object HardLimit : FollowingTimelineUiState

    /** `Loaded`, non-empty posts, `upsell.soft` → the post list PLUS a non-blocking soft-cap banner. */
    data class SoftLimit(val posts: List<FollowingTimelinePost>) : FollowingTimelineUiState

    /** `NetworkError` or retryable `Error` → network copy + a retry control. */
    data object Error : FollowingTimelineUiState

    /** `SessionExpired` (terminal 401) → a neutral redirect placeholder (NO retry, NOT the connectivity
     *  copy) for the sub-second window before the `SessionInvalidator` re-route reaches `SignInScreen`. */
    data object SessionRedirect : FollowingTimelineUiState
}

/**
 * Maps the current [outcome] (null = not yet loaded) + [isInitialLoad] to the screen state. Exhaustive
 * over [FollowingTimelineOutcome] — no generic fallthrough (design D1). The rate-limit presentation is
 * derived from the parsed `upsell` flags on a `Loaded` outcome. The [isInitialLoad] flag (NOT a generic
 * in-flight flag) distinguishes the initial skeleton from a refresh: during a refresh of loaded content
 * [isInitialLoad] is false and the retained `Loaded` outcome projects to [Content] (the list stays
 * mounted); the refresh indicator is conveyed separately via the `PullToRefreshBox` `isRefreshing` value,
 * never by flipping back to [Loading] (design D3):
 *
 * - initial load (or not-yet-loaded) ⇒ [Loading].
 * - `Loaded` empty + `upsell.hard` ⇒ [HardLimit]; `Loaded` empty otherwise ⇒ [Empty] (directive copy + CTA).
 * - `Loaded` non-empty + `upsell.soft` ⇒ [SoftLimit]; `Loaded` non-empty otherwise ⇒ [Content].
 * - `NetworkError` / `Error` ⇒ [Error]; `SessionExpired` ⇒ [SessionRedirect] (neutral, no retry).
 */
fun followingTimelineUiState(
    outcome: FollowingTimelineOutcome?,
    isInitialLoad: Boolean,
): FollowingTimelineUiState {
    if (isInitialLoad) return FollowingTimelineUiState.Loading
    return when (outcome) {
        null -> FollowingTimelineUiState.Loading
        is FollowingTimelineOutcome.Loaded -> {
            val posts = outcome.posts.map { it.toUi() }
            when {
                posts.isEmpty() && outcome.upsell?.hard == true -> FollowingTimelineUiState.HardLimit
                posts.isEmpty() -> FollowingTimelineUiState.Empty
                outcome.upsell?.soft == true -> FollowingTimelineUiState.SoftLimit(posts)
                else -> FollowingTimelineUiState.Content(posts)
            }
        }
        FollowingTimelineOutcome.NetworkError, FollowingTimelineOutcome.Error -> FollowingTimelineUiState.Error
        FollowingTimelineOutcome.SessionExpired -> FollowingTimelineUiState.SessionRedirect
    }
}
