package id.nearyou.app.screens.timeline

import id.nearyou.app.timeline.NearbyPostDto
import id.nearyou.app.timeline.NearbyTimelineOutcome

/**
 * The display-only projection of a Nearby post — the PII-stripped model the cards render. It carries
 * ONLY the fields a card shows: there is deliberately NO `authorUserId` (UUID) and NO
 * `latitude`/`longitude`, so the rendered state STRUCTURALLY cannot leak the author identifier or raw
 * coordinates (spec § "No author identifier or coordinate is rendered or logged"). As of
 * `mobile-timeline-card-redesign` it carries the author **display** identity ([authorUsername] +
 * [authorDisplayName]) — the product-spec'd card header — which is display data, not the banned UUID.
 * [id] is the post's own UUID (not author PII), kept as a stable `LazyColumn` key. The user-facing
 * distance is produced from [distanceM] via `DistanceRenderer.render` at the card level WHEN non-null;
 * [distanceM] is null when the backend suppressed it under the hide-distance rule (city renders alone).
 */
data class NearbyTimelinePost(
    val id: String,
    val authorUsername: String,
    val authorDisplayName: String,
    val content: String,
    val cityName: String,
    val distanceM: Double?,
    val createdAt: String,
    val likedByViewer: Boolean,
    val replyCount: Int,
)

private fun NearbyPostDto.toUi(): NearbyTimelinePost =
    NearbyTimelinePost(
        id = id,
        authorUsername = authorUsername,
        authorDisplayName = authorDisplayName,
        content = content,
        cityName = cityName,
        distanceM = distanceM,
        createdAt = createdAt,
        likedByViewer = likedByViewer,
        replyCount = replyCount,
    )

/**
 * Pure, Compose-free projection of the Nearby screen UI state, mirroring `mobile-age-gate`'s
 * `AgeGateUiState`. The six states are deterministic functions of the [NearbyTimelineOutcome] +
 * in-flight flag (no wall-clock / platform dependency), and carry NO PII — the [Content] / [SoftLimit]
 * posts are [NearbyTimelinePost] (author id + coordinates already dropped).
 */
sealed interface NearbyTimelineUiState {
    /** Initial load (no content yet) → skeleton + loading copy. NOT used during a refresh of loaded
     *  content (that keeps rendering [Content]; the refresh indicator is the `PullToRefreshBox` only). */
    data object Loading : NearbyTimelineUiState

    /** `Loaded`, non-empty posts, no soft upsell → the post-card list. */
    data class Content(val posts: List<NearbyTimelinePost>) : NearbyTimelineUiState

    /** `Loaded`, empty posts, no `upsell` → the sparse-area copy. */
    data object Empty : NearbyTimelineUiState

    /** `Loaded`, empty posts, `upsell.hard` → the hourly read-cap copy (distinct from [Empty]). */
    data object HardLimit : NearbyTimelineUiState

    /** `Loaded`, non-empty posts, `upsell.soft` → the post list PLUS a non-blocking soft-cap banner. */
    data class SoftLimit(val posts: List<NearbyTimelinePost>) : NearbyTimelineUiState

    /** `NetworkError` or retryable `Error` → network copy + a retry control. */
    data object Error : NearbyTimelineUiState

    /** `SessionExpired` (terminal 401) → a neutral redirect placeholder (NO retry, NOT the connectivity
     *  copy) for the sub-second window before the `SessionInvalidator` re-route reaches `SignInScreen`. */
    data object SessionRedirect : NearbyTimelineUiState
}

/**
 * Maps the current [outcome] (null = not yet loaded) + [isInitialLoad] to the screen state. Exhaustive
 * over [NearbyTimelineOutcome] — no generic fallthrough (design D6). The rate-limit presentation is
 * derived from the parsed `upsell` flags on a `Loaded` outcome (design D7 / spec § "Screen state
 * mapping"). The [isInitialLoad] flag (NOT a generic in-flight flag) distinguishes the initial skeleton
 * from a refresh: during a refresh of loaded content [isInitialLoad] is false and the retained `Loaded`
 * outcome projects to [Content] (the list stays mounted) — the refresh indicator is conveyed separately
 * via the `PullToRefreshBox` `isRefreshing` value, never by flipping back to [Loading] (design D3):
 *
 * - initial load (or not-yet-loaded) ⇒ [Loading].
 * - `Loaded` empty + `upsell.hard` ⇒ [HardLimit]; `Loaded` empty otherwise ⇒ [Empty] (distinct copy).
 * - `Loaded` non-empty + `upsell.soft` ⇒ [SoftLimit]; `Loaded` non-empty otherwise ⇒ [Content].
 * - `NetworkError` / `Error` ⇒ [Error]; `SessionExpired` ⇒ [SessionRedirect] (neutral, no retry).
 */
fun nearbyTimelineUiState(
    outcome: NearbyTimelineOutcome?,
    isInitialLoad: Boolean,
): NearbyTimelineUiState {
    if (isInitialLoad) return NearbyTimelineUiState.Loading
    return when (outcome) {
        null -> NearbyTimelineUiState.Loading
        is NearbyTimelineOutcome.Loaded -> {
            val posts = outcome.posts.map { it.toUi() }
            when {
                posts.isEmpty() && outcome.upsell?.hard == true -> NearbyTimelineUiState.HardLimit
                posts.isEmpty() -> NearbyTimelineUiState.Empty
                outcome.upsell?.soft == true -> NearbyTimelineUiState.SoftLimit(posts)
                else -> NearbyTimelineUiState.Content(posts)
            }
        }
        NearbyTimelineOutcome.NetworkError, NearbyTimelineOutcome.Error -> NearbyTimelineUiState.Error
        NearbyTimelineOutcome.SessionExpired -> NearbyTimelineUiState.SessionRedirect
    }
}
