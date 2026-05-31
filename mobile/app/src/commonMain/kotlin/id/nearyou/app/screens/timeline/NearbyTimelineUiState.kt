package id.nearyou.app.screens.timeline

import id.nearyou.app.timeline.NearbyPostDto
import id.nearyou.app.timeline.NearbyTimelineOutcome

/**
 * The display-only projection of a Nearby post — the PII-stripped model the cards render. It carries
 * ONLY the fields a card shows: there is deliberately NO `authorUserId` and NO `latitude`/`longitude`,
 * so the rendered state STRUCTURALLY cannot leak author identity or raw coordinates (spec § "No author
 * identifier or coordinate is rendered or logged"). [id] is the post's own UUID (not author PII), kept
 * as a stable `LazyColumn` key. The user-facing distance is produced from [distanceM] via
 * `DistanceRenderer.render` at the card level (not stored pre-rendered).
 */
data class NearbyTimelinePost(
    val id: String,
    val content: String,
    val cityName: String,
    val distanceM: Double,
    val createdAt: String,
    val likedByViewer: Boolean,
    val replyCount: Int,
)

private fun NearbyPostDto.toUi(): NearbyTimelinePost =
    NearbyTimelinePost(
        id = id,
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
    /** Fetch in-flight (incl. the pre-first-load window) → skeleton + loading copy. */
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
}

/**
 * Maps the current [outcome] (null = not yet loaded) + [inFlight] to the screen state. Exhaustive over
 * [NearbyTimelineOutcome] — no generic fallthrough (design D6). The rate-limit presentation is derived
 * from the parsed `upsell` flags on a `Loaded` outcome (design D7 / spec § "Screen state mapping"):
 *
 * - in-flight (or not-yet-loaded) ⇒ [Loading].
 * - `Loaded` empty + `upsell.hard` ⇒ [HardLimit]; `Loaded` empty otherwise ⇒ [Empty] (distinct copy).
 * - `Loaded` non-empty + `upsell.soft` ⇒ [SoftLimit]; `Loaded` non-empty otherwise ⇒ [Content].
 * - `NetworkError` / `Error` ⇒ [Error].
 */
fun nearbyTimelineUiState(
    outcome: NearbyTimelineOutcome?,
    inFlight: Boolean,
): NearbyTimelineUiState {
    if (inFlight) return NearbyTimelineUiState.Loading
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
    }
}
