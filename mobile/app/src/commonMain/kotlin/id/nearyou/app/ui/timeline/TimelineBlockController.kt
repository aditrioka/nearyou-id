package id.nearyou.app.ui.timeline

import id.nearyou.app.data.block.BlockOutcome
import id.nearyou.app.data.block.BlockSubmitter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The block dialog's target (`timeline-card-block-kebab`): [authorUserId] is ONLY the
 *  `POST /api/v1/blocks/{userId}` path param + the removal key — never rendered (PII discipline);
 *  [authorUsername] is the public display handle the shared `BlockConfirmDialog` interpolates. */
data class TimelineBlockTarget(
    val authorUserId: String,
    val authorUsername: String,
)

/** The one-shot timeline block-result message (rendered as a snackbar by `TimelineActionsOverlay`).
 *  Deliberately parallel to [TimelineReportMessage] (same three-member shape, block-scoped copy). */
enum class TimelineBlockMessage {
    /** Block 204 (`Blocked`) → the canonical "Pengguna telah diblokir" success toast. */
    SUCCESS,

    /** Block 429 (`RateLimited`) → the block rate-limit copy. */
    RATE_LIMITED,

    /** A block network/transport failure → a generic retryable "try again" copy. */
    FAILED,
}

/** Maps a [BlockOutcome] to its one-shot timeline message. Pure + exhaustive (no wildcard) so the
 *  mapping is unit-testable — mirrors [timelineReportMessage]. */
fun timelineBlockMessage(outcome: BlockOutcome): TimelineBlockMessage =
    when (outcome) {
        BlockOutcome.Blocked -> TimelineBlockMessage.SUCCESS
        is BlockOutcome.RateLimited -> TimelineBlockMessage.RATE_LIMITED
        BlockOutcome.NetworkError -> TimelineBlockMessage.FAILED
    }

/**
 * The ONE shared timeline-card block lifecycle (`timeline-card-block-kebab`) — the Nearby, Global,
 * and Following feed ViewModels each hold their own instance (per-surface), mirroring the
 * [TimelineReportController] / [InlineLikeController] pattern, so the dialog-target + one-shot-message
 * state machine exists exactly once. Compose-free and commonTest-unit-testable.
 *
 * Both signals are **one-shot state, not event streams** (docs/11 § 2.2):
 * - [blockTarget] — non-null while the shared `BlockConfirmDialog` should be shown for that author;
 *   cleared by [onDialogDismissed] or on confirm.
 * - [blockMessage] — the submission result; cleared by [onMessageShown] after the host renders it.
 *
 * Submission goes through the shared [BlockSubmitter] seam — no second block path. On a `Blocked`
 * outcome the ctor-injected [removeAuthorPosts] fires FIRST (the host filters the blocked author's
 * posts out of its retained loaded outcome — mutual invisibility made immediate, design D4), then
 * [blockMessage] surfaces `SUCCESS`; failed outcomes fire no removal. Authorship gating lives in the
 * host VM ([blockTarget] is only ever set for posts the host offered the action on).
 */
class TimelineBlockController(
    private val blockSubmitter: BlockSubmitter,
    private val scope: CoroutineScope,
    private val removeAuthorPosts: (authorUserId: String) -> Unit,
) {
    private val _blockTarget = MutableStateFlow<TimelineBlockTarget?>(null)

    /** Non-null while the block confirmation dialog should be shown, carrying the target author. */
    val blockTarget: StateFlow<TimelineBlockTarget?> = _blockTarget.asStateFlow()

    private val _blockMessage = MutableStateFlow<TimelineBlockMessage?>(null)

    /** Non-null while the one-shot result message should be shown. */
    val blockMessage: StateFlow<TimelineBlockMessage?> = _blockMessage.asStateFlow()

    /** The card kebab's "Blokir @{username}" tap — opens the dialog targeting the post's author. */
    fun onBlockClicked(
        authorUserId: String,
        authorUsername: String,
    ) {
        _blockTarget.value = TimelineBlockTarget(authorUserId, authorUsername)
    }

    /** Dismiss the block dialog without submitting (clears the target one-shot). */
    fun onDialogDismissed() {
        _blockTarget.value = null
    }

    /** Confirms the block for the currently-targeted author via the shared [BlockSubmitter]. Closes
     *  the dialog immediately; a `Blocked` outcome fires [removeAuthorPosts] then surfaces the
     *  one-shot [blockMessage]. A no-op if no target is set (defensive). */
    fun onConfirmed() {
        val target = _blockTarget.value ?: return
        _blockTarget.value = null
        scope.launch {
            val outcome = blockSubmitter.submit(target.authorUserId)
            if (outcome is BlockOutcome.Blocked) removeAuthorPosts(target.authorUserId)
            _blockMessage.value = timelineBlockMessage(outcome)
        }
    }

    /** Clears the one-shot [blockMessage] after the host has shown it (no re-fire on recomposition). */
    fun onMessageShown() {
        _blockMessage.value = null
    }
}
