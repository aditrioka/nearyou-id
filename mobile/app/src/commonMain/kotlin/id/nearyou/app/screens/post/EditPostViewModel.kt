package id.nearyou.app.screens.post

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.nearyou.app.post.PostEditFlow
import id.nearyou.app.post.PostEditOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * The `EditPostRoute`-scoped ViewModel owning the editor text + submit state for the `mobile-post-editing`
 * edit screen. Resolved via `viewModel { EditPostViewModel(flow, postId, initialContent) }` under the root
 * `NavDisplay` entry decorator (the pushed-route precedent `SearchScreen` / `ProfileScreen` use), so it
 * survives recomposition + config change and is cleared when `EditPostRoute` pops. Exposes ONE
 * `StateFlow<EditPostUiState>` (design § 2.2).
 *
 * Reactive premium gate (design D2): the screen does NOT read a client premium flag — it submits the PATCH
 * and reacts to the outcome ([PostEditOutcome.PremiumRequired] → the upsell). One in-flight submit at a
 * time: [submit] claims the in-flight slot synchronously (the post-detail double-tap precedent) so a
 * same-frame double-tap can't double-PATCH; the result is committed when the coroutine returns.
 * `CancellationException` is rethrown (structured-concurrency unwind); any other throwable maps to
 * [PostEditOutcome.Network] (never a crash). No PII is held or logged.
 */
class EditPostViewModel(
    private val flow: PostEditFlow,
    private val postId: String,
    initialContent: String,
) : ViewModel() {
    private var content: String = initialContent

    private val _uiState = MutableStateFlow(editPostUiState(content = initialContent, isSubmitting = false))
    val uiState: StateFlow<EditPostUiState> = _uiState.asStateFlow()

    /** The editor field change handler — re-projects the gate (counter + submit-enable), preserving any
     *  in-flight flag and the current inline [EditPostError] banner (the text survives a failure). */
    fun onContentChange(raw: String) {
        content = raw
        _uiState.value =
            editPostUiState(
                content = raw,
                isSubmitting = _uiState.value.isSubmitting,
                error = _uiState.value.error,
            )
    }

    /** Submit the edit. No-op when the gate is closed (empty / over-limit / in-flight). Clears prior
     *  one-shots, sets in-flight synchronously, then commits the mapped outcome. */
    fun submit() {
        if (!editPostUiState(content = content, isSubmitting = false).submitEnabled) return
        _uiState.value = editPostUiState(content = content, isSubmitting = true)
        viewModelScope.launch {
            val outcome =
                try {
                    flow.editPost(postId, content)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    PostEditOutcome.Network
                }
            _uiState.value = project(outcome)
        }
    }

    private fun project(outcome: PostEditOutcome): EditPostUiState =
        when (outcome) {
            is PostEditOutcome.Success ->
                editPostUiState(content = outcome.content, isSubmitting = false, editedContent = outcome.content)
            PostEditOutcome.PremiumRequired ->
                editPostUiState(content = content, isSubmitting = false, showPremiumUpsell = true)
            PostEditOutcome.WindowExpired -> errorState(EditPostError.WINDOW_EXPIRED)
            PostEditOutcome.NoChanges -> errorState(EditPostError.NO_CHANGES)
            PostEditOutcome.ContentModerated -> errorState(EditPostError.CONTENT_MODERATED)
            PostEditOutcome.Conflict -> errorState(EditPostError.CONFLICT)
            is PostEditOutcome.RateLimited -> errorState(EditPostError.RATE_LIMITED)
            PostEditOutcome.NotFound -> errorState(EditPostError.NOT_FOUND)
            PostEditOutcome.Network -> errorState(EditPostError.NETWORK)
        }

    private fun errorState(error: EditPostError): EditPostUiState = editPostUiState(content = content, isSubmitting = false, error = error)

    /** Dismiss the inline error banner (keeps the editor text). */
    fun onErrorShown() {
        _uiState.value = editPostUiState(content = content, isSubmitting = _uiState.value.isSubmitting)
    }

    /** Dismiss the "Aktifkan Premium" upsell (keeps the editor text — the user can adjust + retry). */
    fun onPremiumUpsellDismissed() {
        _uiState.value = editPostUiState(content = content, isSubmitting = _uiState.value.isSubmitting)
    }
}
