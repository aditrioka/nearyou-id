package id.nearyou.app.screens.profile

import id.nearyou.app.profile.ProfileOutcome
import id.nearyou.app.profile.UserProfile

/**
 * The load phase of the profile screen — mutually exclusive states. Initial load (or not-yet-loaded) →
 * [Loading]; a loaded profile → [Content]; the constant `404 user_not_found` → [NotFound] (a single state,
 * no per-cause variation); a `NetworkError` with no content → [Error] (a retry control).
 */
sealed interface ProfilePhase {
    data object Loading : ProfilePhase

    data class Content(val profile: UserProfile) : ProfilePhase

    data object NotFound : ProfilePhase

    data object Error : ProfilePhase
}

/**
 * The user-facing one-shot message keys (each maps to a `:shared:resources` string at the screen). Held
 * as a nullable [ProfileUiState.message] field cleared via `onMessageShown()` — NOT a `Channel`/
 * `SharedFlow` event bus (docs/11 § 2.2; the documented anti-pattern).
 */
enum class ProfileMessage {
    /** Follow `POST` 429 → the follow-churn limit copy. */
    FOLLOW_RATE_LIMITED,

    /** Follow `POST` constant 404 → a neutral, direction-less "user unavailable" copy. */
    TARGET_UNAVAILABLE,

    /** Block 204 → the "user blocked" success toast. */
    BLOCK_SUCCESS,

    /** Block 429 → the block rate-limit copy. */
    BLOCK_RATE_LIMITED,

    /** Report 204 → the "report submitted" success toast. */
    REPORT_SUCCESS,

    /** Report 409 `duplicate_report` → the "already reported" copy. */
    REPORT_DUPLICATE,

    /** Report 429 → the report rate-limit copy. */
    REPORT_RATE_LIMITED,

    /** A block / report network failure → a generic "try again" copy. */
    ACTION_FAILED,
}

/**
 * The Compose-free, PII-free rendered state of the profile screen. [followedByViewer] is the LIVE
 * (optimistic) follow value the toggle renders; [message] + [navigateBack] are one-shots consumed via
 * `onMessageShown()` / `onNavigatedBack()`. The follow/block/report actions are rendered only when the
 * [ProfilePhase.Content] profile's `isSelf` is false (the screen reads that off the profile).
 */
data class ProfileUiState(
    val phase: ProfilePhase,
    val followedByViewer: Boolean,
    val isFollowInFlight: Boolean,
    val message: ProfileMessage?,
    val navigateBack: Boolean,
)

/**
 * Pure projection of the screen state — deterministic, no wall-clock / platform dependency, no PII beyond
 * the display fields the [UserProfile] already carries. [isInitialLoad] (no content yet) → [Loading];
 * otherwise the [outcome] maps to its phase. [optimisticFollowed] (when non-null) overrides the loaded
 * `followedByViewer` so the toggle reflects an in-flight optimistic flip; when null the loaded value (or
 * false) is used. Exhaustive over [ProfileOutcome] — no generic fallthrough.
 */
fun profileUiState(
    outcome: ProfileOutcome?,
    isInitialLoad: Boolean,
    optimisticFollowed: Boolean?,
    isFollowInFlight: Boolean,
    message: ProfileMessage?,
    navigateBack: Boolean,
): ProfileUiState {
    val phase =
        if (isInitialLoad) {
            ProfilePhase.Loading
        } else {
            when (outcome) {
                null -> ProfilePhase.Loading
                is ProfileOutcome.Loaded -> ProfilePhase.Content(outcome.profile)
                ProfileOutcome.NotFound -> ProfilePhase.NotFound
                ProfileOutcome.NetworkError -> ProfilePhase.Error
            }
        }
    val followed =
        optimisticFollowed
            ?: (outcome as? ProfileOutcome.Loaded)?.profile?.followedByViewer
            ?: false
    return ProfileUiState(
        phase = phase,
        followedByViewer = followed,
        isFollowInFlight = isFollowInFlight,
        message = message,
        navigateBack = navigateBack,
    )
}

/** The server's report-note bound (`reports` spec: `reason_note` ≤ 200 Unicode code points). */
const val REPORT_NOTE_MAX_CODE_POINTS: Int = 200

/**
 * Counts the **Unicode code points** in [note] (NOT UTF-16 `length`), so a surrogate-pair emoji counts
 * as one — matching the server's code-point bound. Pure + commonMain so the report-note gate is
 * unit-testable without composing UI (a regression to `.length` would change this count for surrogate
 * input and fail the test).
 */
fun reportNoteCodePointCount(note: String): Int {
    var count = 0
    var i = 0
    while (i < note.length) {
        val c = note[i]
        i += if (c.isHighSurrogate() && i + 1 < note.length && note[i + 1].isLowSurrogate()) 2 else 1
        count++
    }
    return count
}

/** Whether the report note is within the server's ≤200-code-point bound (the submit-enabled gate). */
fun isReportNoteWithinLimit(note: String): Boolean = reportNoteCodePointCount(note) <= REPORT_NOTE_MAX_CODE_POINTS
