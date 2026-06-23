package id.nearyou.app.screens.post

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.nearyou.app.auth.SelfUserIdProvider
import id.nearyou.app.image.ImagePicker
import id.nearyou.app.image.ImageUploadOutcome
import id.nearyou.app.image.ImageUploader
import id.nearyou.app.post.CreatePostFlow
import id.nearyou.app.post.PostCreationOutcome
import id.nearyou.app.profile.ProfileFlow
import id.nearyou.app.profile.ProfileOutcome
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * The `PostCreationRoute`-scoped state holder (docs/11 §2.2): an androidx [ViewModel] in commonMain,
 * obtained via `viewModel { … }` scoped to the Nav3 entry (the pushed-route precedent the composer FAB
 * pushes). It owns the composer's full state so the screen stays a thin renderer:
 *
 *  - the content candidate + the create lifecycle ([content] / [createOutcome] / [createInFlight]) —
 *    the same shape the screen previously held in `remember`, moved here so the image flow can coordinate
 *    with the create submit;
 *  - the **on-entry self-Premium resolution** ([isPremium]) — a self-profile read, mirroring
 *    [id.nearyou.app.screens.username.UsernameCustomizationViewModel]'s gate (the screen owns the gate);
 *  - the **image-attach two-step flow** ([imageAttach]): pick → upload (showing [ImageAttachUiState.Uploading])
 *    → preview ([ImageAttachUiState.Attached]) / mapped error ([ImageAttachUiState.Failed]); and
 *  - a **route-to-paywall one-shot** ([routeToPaywall]) raised when a Free viewer taps the attach
 *    affordance, consumed by the screen (which invokes the hoisted `onActivatePremium`) and cleared.
 *
 * PII / privacy discipline (design D7): this VM NEVER logs the image bytes (the picked [bytes] live only
 * on the stack while the upload runs) and never the coordinate (the coordinate is acquired entirely inside
 * [CreatePostFlow.submit]). The submit re-entrancy guard ([createInFlight]) mirrors the screen's prior
 * synchronous claim; `CancellationException` is rethrown everywhere (structured concurrency).
 */
class PostCreationViewModel(
    private val flow: CreatePostFlow,
    private val imagePicker: ImagePicker,
    private val imageUploader: ImageUploader,
    private val profileFlow: ProfileFlow,
    private val selfUserIdProvider: SelfUserIdProvider,
) : ViewModel() {
    private val _content = MutableStateFlow("")
    val content: StateFlow<String> = _content.asStateFlow()

    private val _createOutcome = MutableStateFlow<PostCreationOutcome?>(null)
    val createOutcome: StateFlow<PostCreationOutcome?> = _createOutcome.asStateFlow()

    private val _createInFlight = MutableStateFlow(false)
    val createInFlight: StateFlow<Boolean> = _createInFlight.asStateFlow()

    /** The viewer's resolved Premium status. `null` while the on-entry self-read is in flight (the attach
     *  affordance is inert until resolved — a tap before resolution does nothing, avoiding a wrong-gate
     *  race). A read failure degrades to `false` (Free): the reactive upload 403 then backstops. */
    private val _isPremium = MutableStateFlow<Boolean?>(null)
    val isPremium: StateFlow<Boolean?> = _isPremium.asStateFlow()

    private val _imageAttach = MutableStateFlow<ImageAttachUiState>(ImageAttachUiState.None)
    val imageAttach: StateFlow<ImageAttachUiState> = _imageAttach.asStateFlow()

    /** One-shot: set true when a Free viewer taps attach. The screen routes to the paywall and calls
     *  [onPaywallRouted] to clear it (docs/11 §2.2 one-shot consumer). */
    private val _routeToPaywall = MutableStateFlow(false)
    val routeToPaywall: StateFlow<Boolean> = _routeToPaywall.asStateFlow()

    private var imageJob: Job? = null

    init {
        resolvePremiumOnEntry()
    }

    /** On-entry: resolve the self profile → seed the Premium gate. A missing id or a read failure degrades
     *  to Free (the reactive upload 403 then governs — never an error wall, mirroring the username gate). */
    private fun resolvePremiumOnEntry() {
        viewModelScope.launch {
            val id = selfUserIdProvider.selfUserId()
            if (id == null) {
                _isPremium.value = false
                return@launch
            }
            val resolved =
                when (val outcome = profileFlow.loadProfile(id)) {
                    is ProfileOutcome.Loaded -> outcome.profile.isPremium
                    // NotFound / NetworkError on the self read → Free (reactive 403 backstops correctness).
                    else -> false
                }
            _isPremium.value = resolved
        }
    }

    /** The content field's change handler. */
    fun onContentChange(raw: String) {
        _content.value = raw
    }

    /**
     * The attach affordance tap. A Premium viewer → open the OS picker, then upload the chosen image
     * (two-step flow). A Free viewer → raise the paywall one-shot WITHOUT invoking the picker (the spec's
     * "Free viewer is upsold, not given the picker"). While Premium is still resolving (`null`) the tap is
     * a no-op. Re-entrancy is blocked while a pick/upload job is already in flight.
     */
    fun onAttachClick() {
        if (_isPremium.value != true) {
            // Free (or a failed/absent self-read → Free): upsell, never the picker.
            _routeToPaywall.value = true
            return
        }
        if (imageJob?.isActive == true) return
        imageJob =
            viewModelScope.launch {
                // 1. OS picker (no runtime permission). A cancelled selection (null) leaves the state at
                //    its current value and attempts no upload.
                val picked =
                    try {
                        imagePicker.pick()
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        // A picker failure is treated as "no image" (never crashes the composer).
                        null
                    }
                if (picked == null) return@launch

                // 2. Upload (the bytes are NEVER logged). Show the in-progress state first.
                _imageAttach.value = ImageAttachUiState.Uploading
                val outcome =
                    try {
                        imageUploader.upload(bytes = picked.bytes, mime = picked.mime)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        // The repository maps transport failures to ImageUploadOutcome.Network already; this
                        // is the last-resort guard so an unexpected throw still resolves to a terminal state.
                        ImageUploadOutcome.Network
                    }
                // 3. Map every outcome → a distinct terminal sub-state (Attached preview / Failed message).
                _imageAttach.value = outcome.toAttachState()
            }
    }

    /** Remove the previewed/failed image: clear the attachment so the next post is text-only. Cancels any
     *  in-flight pick/upload too (the user explicitly abandoned the attachment). */
    fun onRemoveImage() {
        imageJob?.cancel()
        _imageAttach.value = ImageAttachUiState.None
    }

    /**
     * Submit the post (the CTA + the network-error retry both call this). Re-entrancy is blocked while a
     * submit is in flight; the in-flight slot is claimed SYNCHRONOUSLY (a same-frame double-tap must not
     * double-POST the non-idempotent create, 2026-06-10 audit 05-#10). The attached image id (only present
     * on [ImageAttachUiState.Attached]) is threaded into the create request; a failed/missing upload
     * carries no id, so a post can never reference a dangling image.
     */
    fun onSubmit() {
        if (_createInFlight.value) return
        val attachedImageId = (_imageAttach.value as? ImageAttachUiState.Attached)?.imageId
        _createInFlight.value = true
        viewModelScope.launch {
            try {
                _createOutcome.value = flow.submit(content = _content.value, imageId = attachedImageId)
            } finally {
                _createInFlight.value = false
            }
        }
    }

    /** The paywall one-shot consumer: clears [routeToPaywall] after the screen routes to the paywall. */
    fun onPaywallRouted() {
        _routeToPaywall.value = false
    }
}
