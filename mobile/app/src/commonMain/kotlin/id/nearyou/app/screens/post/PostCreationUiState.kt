package id.nearyou.app.screens.post

import id.nearyou.app.post.PostCreationOutcome

/** The post length limit — 280 code points (`post-creation` spec § "Content length guard"). The
 *  client gate counts RAW Unicode code points (no NFKC) for the live counter + submit-enable; the
 *  server NFKC-normalizes + trims authoritatively (design D5), so the client gate is only a UX nicety
 *  that prevents the obvious doomed round-trip (a rare divergence surfaces as the server's
 *  `content_too_long`/`content_empty` banner). */
const val MAX_POST_CONTENT_CODE_POINTS: Int = 280

/**
 * Which error banner (if any) shows below the field. Deliberately an enum of static message keys —
 * it can NEVER carry the coordinate, the post id, or any PII into the UI (mirrors `AgeGateBanner`).
 * The Compose layer maps each member to a `stringResource`.
 */
enum class PostCreationBanner {
    /** `post_create_error_empty` — 400 `content_empty`. */
    CONTENT_EMPTY,

    /** `post_create_error_too_long` — 400 `content_too_long`. */
    CONTENT_TOO_LONG,

    /** `post_create_error_location` — 400 `location_out_of_bounds`. */
    LOCATION_OUT_OF_BOUNDS,

    /** `post_create_error_moderated` — 400 `content_moderated_profanity` (generic, keyword-free). */
    CONTENT_REJECTED,

    /** `post_create_location_unavailable` + a "Buka Pengaturan" control — permission/no-fix. */
    LOCATION_UNAVAILABLE,

    /** `signin_error_network` + a retry control — `NetworkError` / retryable `Error`. */
    NETWORK,

    /** `post_create_error_rate_limited` — 429 daily cap; no retry control (resets daily). */
    RATE_LIMITED,
}

/**
 * Pure, Compose-free projection of the composer screen state, mirroring `mobile-age-gate`'s
 * `AgeGateUiState`. Carries the always-present editing fields ([charCount], [overLimit],
 * [submitEnabled]) plus the phase overlays ([loading], [success], [banner]). Holds NO PII — the
 * success [PostCreationOutcome.Success] `id` is intentionally dropped (success only pops), and the
 * banner is an enum of static keys.
 */
data class PostCreationUiState(
    val charCount: Int,
    val overLimit: Boolean,
    val submitEnabled: Boolean,
    val loading: Boolean,
    val success: Boolean,
    val banner: PostCreationBanner?,
)

/**
 * Maps the live [content] + the last [outcome] (null = never submitted) + the [inFlight] flag to the
 * screen state. Deterministic — no wall-clock, no platform dependency, no PII. Exhaustive over
 * [PostCreationOutcome] (no generic fallthrough, design parity with `nearbyTimelineUiState`):
 *
 * - [charCount] is the number of **Unicode code points** in [content] (a 280-emoji string counts 280,
 *   NOT 560 — UTF-16 unit counting would wrongly reject it).
 * - submit is enabled iff [content] has ≥1 non-blank code point AND ≤280 code points AND not in-flight.
 * - `inFlight` ⇒ [loading] (submit disabled, no banner — the in-flight phase supersedes any prior
 *   outcome).
 * - otherwise the [outcome] derives [success] / the per-error [banner].
 */
fun postCreationUiState(
    content: String,
    outcome: PostCreationOutcome?,
    inFlight: Boolean,
): PostCreationUiState {
    val charCount = content.codePointLength()
    val overLimit = charCount > MAX_POST_CONTENT_CODE_POINTS
    val withinLength = content.isNotBlank() && charCount <= MAX_POST_CONTENT_CODE_POINTS

    if (inFlight) {
        return PostCreationUiState(
            charCount = charCount,
            overLimit = overLimit,
            submitEnabled = false,
            loading = true,
            success = false,
            banner = null,
        )
    }

    val banner =
        when (outcome) {
            PostCreationOutcome.ContentEmpty -> PostCreationBanner.CONTENT_EMPTY
            PostCreationOutcome.ContentTooLong -> PostCreationBanner.CONTENT_TOO_LONG
            PostCreationOutcome.LocationOutOfBounds -> PostCreationBanner.LOCATION_OUT_OF_BOUNDS
            PostCreationOutcome.ContentRejected -> PostCreationBanner.CONTENT_REJECTED
            PostCreationOutcome.LocationUnavailable -> PostCreationBanner.LOCATION_UNAVAILABLE
            PostCreationOutcome.RateLimited -> PostCreationBanner.RATE_LIMITED
            PostCreationOutcome.NetworkError, PostCreationOutcome.Error -> PostCreationBanner.NETWORK
            is PostCreationOutcome.Success, null -> null
        }
    return PostCreationUiState(
        charCount = charCount,
        overLimit = overLimit,
        submitEnabled = withinLength,
        loading = false,
        success = outcome is PostCreationOutcome.Success,
        banner = banner,
    )
}

/**
 * Counts the **Unicode code points** in this string (commonMain-safe — no `java.lang.Character`):
 * a surrogate pair (a non-BMP emoji, two UTF-16 units) counts as one. A lone/unpaired surrogate
 * counts as one. So a 280-emoji string returns 280, not 560.
 */
internal fun String.codePointLength(): Int {
    var count = 0
    var index = 0
    while (index < length) {
        val isSurrogatePair =
            this[index].isHighSurrogate() &&
                index + 1 < length &&
                this[index + 1].isLowSurrogate()
        index += if (isSurrogatePair) 2 else 1
        count++
    }
    return count
}
