package id.nearyou.app.screens.timeline

import id.nearyou.app.timeline.GlobalPostDto
import id.nearyou.app.timeline.GlobalTimelineOutcome

/**
 * The display-only projection of a Global post — the PII-stripped model the cards render. It carries
 * ONLY the fields a card shows: there is deliberately NO `authorUserId`, NO `latitude`/`longitude`,
 * and (Global has no spatial filter) **NO distance** — so the rendered state STRUCTURALLY cannot leak
 * author identity, raw coordinates, or a distance. [id] is the post's own UUID (not author PII), kept
 * as a stable `LazyColumn` key.
 */
data class GlobalTimelinePost(
    val id: String,
    val content: String,
    val cityName: String,
    val createdAt: String,
    val likedByViewer: Boolean,
    val replyCount: Int,
)

private fun GlobalPostDto.toUi(): GlobalTimelinePost =
    GlobalTimelinePost(
        id = id,
        content = content,
        cityName = cityName,
        createdAt = createdAt,
        likedByViewer = likedByViewer,
        replyCount = replyCount,
    )

/**
 * Pure, Compose-free projection of the Global screen UI state, mirroring [NearbyTimelineUiState]. The
 * six states are deterministic functions of the [GlobalTimelineOutcome] + in-flight flag (no
 * wall-clock / platform dependency), and carry NO PII — the [Content] / [SoftLimit] posts are
 * [GlobalTimelinePost] (author id + coordinates already dropped; no distance exists).
 */
sealed interface GlobalTimelineUiState {
    /** Fetch in-flight (incl. the pre-first-load window) → skeleton + loading copy. */
    data object Loading : GlobalTimelineUiState

    /** `Loaded`, non-empty posts, no soft upsell → the post-card list. */
    data class Content(val posts: List<GlobalTimelinePost>) : GlobalTimelineUiState

    /**
     * `Loaded`, empty posts, no `upsell` → reuses the loading-skeleton presentation (the existing
     * `timeline_loading` copy), since Global is effectively never empty (design / spec § "Screen
     * state mapping"). Kept DISTINCT from [Loading] at the projection level even though both render
     * the same skeleton — so the mapping stays exhaustive + testable.
     */
    data object Empty : GlobalTimelineUiState

    /** `Loaded`, empty posts, `upsell.hard` → the hourly read-cap copy (distinct from [Empty]). */
    data object HardLimit : GlobalTimelineUiState

    /** `Loaded`, non-empty posts, `upsell.soft` → the post list PLUS a non-blocking soft-cap banner. */
    data class SoftLimit(val posts: List<GlobalTimelinePost>) : GlobalTimelineUiState

    /** `NetworkError` or retryable `Error` → network copy + a retry control. */
    data object Error : GlobalTimelineUiState
}

/**
 * Maps the current [outcome] (null = not yet loaded) + [inFlight] to the screen state. Exhaustive over
 * [GlobalTimelineOutcome] — no generic fallthrough (design D4). The rate-limit presentation is derived
 * from the parsed `upsell` flags on a `Loaded` outcome:
 *
 * - in-flight (or not-yet-loaded) ⇒ [Loading].
 * - `Loaded` empty + `upsell.hard` ⇒ [HardLimit]; `Loaded` empty otherwise ⇒ [Empty] (skeleton copy).
 * - `Loaded` non-empty + `upsell.soft` ⇒ [SoftLimit]; `Loaded` non-empty otherwise ⇒ [Content].
 * - `NetworkError` / `Error` ⇒ [Error].
 */
fun globalTimelineUiState(
    outcome: GlobalTimelineOutcome?,
    inFlight: Boolean,
): GlobalTimelineUiState {
    if (inFlight) return GlobalTimelineUiState.Loading
    return when (outcome) {
        null -> GlobalTimelineUiState.Loading
        is GlobalTimelineOutcome.Loaded -> {
            val posts = outcome.posts.map { it.toUi() }
            when {
                posts.isEmpty() && outcome.upsell?.hard == true -> GlobalTimelineUiState.HardLimit
                posts.isEmpty() -> GlobalTimelineUiState.Empty
                outcome.upsell?.soft == true -> GlobalTimelineUiState.SoftLimit(posts)
                else -> GlobalTimelineUiState.Content(posts)
            }
        }
        GlobalTimelineOutcome.NetworkError, GlobalTimelineOutcome.Error -> GlobalTimelineUiState.Error
    }
}
