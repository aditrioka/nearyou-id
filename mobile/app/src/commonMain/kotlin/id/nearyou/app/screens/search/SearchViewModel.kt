package id.nearyou.app.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.nearyou.app.auth.SelfUserIdProvider
import id.nearyou.app.post.PostTargetResolution
import id.nearyou.app.profile.ProfileFlow
import id.nearyou.app.profile.ProfileOutcome
import id.nearyou.app.screens.home.PostDetailTarget
import id.nearyou.app.screens.home.toPostDetailTarget
import id.nearyou.app.search.SearchFlow
import id.nearyou.app.search.SearchOutcome
import id.nearyou.app.search.SearchQueryGuard
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * The `SearchRoute`-scoped ViewModel owning the search query + result state. Resolved via
 * `viewModel { … }` under the root `NavDisplay`'s entry decorator for `SearchRoute` (the pushed-route
 * precedent `PostDetailScreen` uses), so it survives recomposition + config change and is cleared when
 * `SearchRoute` is popped. Exposes ONE [uiState] (docs/11 §2.2) — one private [VmState] mapped through the
 * pure [searchUiState] projection via `stateIn` (the `UsernameCustomizationViewModel` shape).
 *
 * A query fires on a [DEBOUNCE_MILLIS] debounce after the last keystroke (via [onQueryChange]) AND
 * immediately on the keyboard submit action (via [onSubmit]) — but ONLY when the [SearchQueryGuard]
 * passes (below-2-char queries issue no request). A "Lihat lebih banyak" [loadMore] appends the next page
 * to the retained results.
 *
 * **On-entry Premium gate (#253).** The viewer's tier is resolved once on entry from the self-profile read
 * (the `UsernameCustomizationViewModel` / `NearbyTimelineViewModel` seam), ORed with [premiumConfirmed]. A
 * known-Free viewer sees the upsell panel in place of the Idle prompt before typing; the field stays usable
 * and an eligible query still goes to the server, whose answer governs (the profile `isPremium` is
 * `premium_active` only, while search also admits `premium_billing_retry`). Known Premium is sticky: a
 * `Results` / `RateLimited` answer or a confirmed purchase marks it, and a later read never downgrades it. A
 * pending / failed / id-less read leaves the tier unknown → the Idle prompt (the reactive 403 backstops).
 *
 * **Result tap (#255).** [onResultTap] resolves the post through the shared by-id read and exposes it as the
 * consumed-once [SearchScreenUiState.pendingNavTarget] (`Unavailable` / a throw → the hit's own payload with
 * the documented defaults). Latest tap wins; a new search or [onNavConsumed] cancels an in-flight read.
 *
 * **One in-flight fetch at a time.** [searchJob] holds the current debounce+fetch coroutine; every new
 * keystroke / submit cancels it BEFORE launching the replacement, so a slow stale fetch can never land after
 * — and overwrite — a newer query's result. Each job commits only after [ensureActive] confirms it was not
 * superseded.
 */
class SearchViewModel(
    private val flow: SearchFlow,
    private val profileFlow: ProfileFlow,
    private val selfUserIdProvider: SelfUserIdProvider,
    // premium-entitlement-lifecycle: a purchase confirmed while a gate is shown (the buyer returns from the
    // paywall pushed atop this route) lifts the on-entry gate and re-runs a 403-gated query once; the server 403
    // stays authoritative.
    private val premiumConfirmed: StateFlow<Boolean> = MutableStateFlow(false),
) : ViewModel() {
    private data class VmState(
        val query: String = "",
        val outcome: SearchOutcome? = null,
        val isLoading: Boolean = false,
        val isLoadingMore: Boolean = false,
        // null = the on-entry tier is unknown (read pending / failed / no self id). Writes move only toward
        // true (design D4), so a late Free read never re-gates a viewer the server already answered.
        val viewerPremium: Boolean? = null,
        val resolvingPostId: String? = null,
        val pendingNavTarget: PostDetailTarget? = null,
    )

    private val state = MutableStateFlow(VmState())

    val uiState: StateFlow<SearchScreenUiState> =
        state
            .map { it.toUiState() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), state.value.toUiState())

    /** The raw outcome — a plain read-only white-box test seam (the role `GlobalTimelineViewModel.outcome` plays);
     *  the screen reads [uiState]. */
    val outcome: SearchOutcome? get() = state.value.outcome

    private var searchJob: Job? = null
    private var loadMoreJob: Job? = null
    private var resolveJob: Job? = null

    init {
        resolvePremiumOnEntry()
        viewModelScope.launch {
            premiumConfirmed.first { it }
            // A confirmed purchase lifts an on-entry gate (no request) and re-runs a 403-gated query once.
            state.update { it.copy(viewerPremium = true) }
            if (state.value.outcome == SearchOutcome.PremiumGate) retry()
        }
    }

    private fun VmState.toUiState(): SearchScreenUiState =
        SearchScreenUiState(
            query = query,
            surface = searchUiState(query, outcome, isLoading, isLoadingMore, viewerKnownFree = viewerPremium == false),
            resolvingPostId = resolvingPostId,
            pendingNavTarget = pendingNavTarget,
        )

    /** On-entry self read → the tier. A missing id or a non-`Loaded` read leaves it unknown (the Idle prompt;
     *  the reactive 403 backstops correctness — never an error wall). */
    private fun resolvePremiumOnEntry() {
        viewModelScope.launch {
            val id = selfUserIdProvider.selfUserId() ?: return@launch
            val outcome = profileFlow.loadProfile(id) as? ProfileOutcome.Loaded ?: return@launch
            // OR the confirmed purchase: a read lagging the webhook must not gate a buyer.
            val premium = outcome.profile.isPremium || premiumConfirmed.value
            state.update { if (it.viewerPremium == true) it else it.copy(viewerPremium = premium) }
        }
    }

    /** The text field's change handler: caps the input at 100 code points, then either schedules a
     *  debounced fetch (eligible query) or returns the screen to Idle (below the guard's minimum). */
    fun onQueryChange(raw: String) {
        val capped = SearchQueryGuard.cap(raw)
        if (!SearchQueryGuard.isEligible(capped)) {
            // Below the threshold → Idle: cancel any in-flight fetch / load-more / tap read and clear results.
            cancelInFlight()
            state.update {
                it.copy(query = capped, outcome = null, isLoading = false, isLoadingMore = false, resolvingPostId = null)
            }
            return
        }
        startSearch(capped, debounce = true)
    }

    /** Keyboard submit (ime action): cancel the pending debounce and fetch the current query now. */
    fun onSubmit() {
        val current = state.value.query
        if (!SearchQueryGuard.isEligible(current)) return
        startSearch(current, debounce = false)
    }

    /** Error-retry + the rate-limit "Coba lagi": re-issue the current query's first page now. */
    fun retry() = onSubmit()

    private fun cancelInFlight() {
        searchJob?.cancel()
        loadMoreJob?.cancel()
        // A tap still resolving belongs to the results being replaced — never open a post no longer on screen.
        resolveJob?.cancel()
    }

    /**
     * Cancel the prior debounce+fetch (and any load-more / tap read), then launch a single new one. The
     * cancel runs BEFORE the new job is assigned, so it can never cancel itself; the new job commits its
     * outcome only after [ensureActive], so a superseded (cancelled) fetch never writes stale results.
     */
    private fun startSearch(
        query: String,
        debounce: Boolean,
    ) {
        cancelInFlight()
        state.update {
            it.copy(query = query, outcome = null, isLoading = true, isLoadingMore = false, resolvingPostId = null)
        }
        searchJob =
            viewModelScope.launch {
                if (debounce) delay(DEBOUNCE_MILLIS)
                val result =
                    try {
                        flow.search(SearchQueryGuard.normalize(query), 0)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        SearchOutcome.NetworkError
                    }
                // Commit only if this job is still the active search (a newer query did not supersede it).
                ensureActive()
                // Results / RateLimited (a Premium-tier limit) prove Premium access — sticky (design D4).
                val provesPremium = result is SearchOutcome.Results || result is SearchOutcome.RateLimited
                state.update {
                    it.copy(outcome = result, isLoading = false, viewerPremium = if (provesPremium) true else it.viewerPremium)
                }
            }
    }

    /** "Lihat lebih banyak": fetch the next page and APPEND it to the retained results. */
    fun loadMore() {
        val current = state.value.outcome as? SearchOutcome.Results ?: return
        val nextOffset = current.nextOffset ?: return
        if (state.value.isLoadingMore) return
        state.update { it.copy(isLoadingMore = true) }
        loadMoreJob =
            viewModelScope.launch {
                val next =
                    try {
                        flow.search(SearchQueryGuard.normalize(state.value.query), nextOffset)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        null
                    }
                ensureActive()
                state.update {
                    // Only commit if the retained outcome is STILL `current` (the query didn't change under
                    // us between the request and the response); otherwise a newer Results would be clobbered.
                    if (it.outcome !== current) {
                        it.copy(isLoadingMore = false)
                    } else {
                        val appended =
                            when {
                                // De-duplicated by post id: OFFSET paging over rank ties can repeat a retained
                                // hit when the result set shifts between pages, and the list keys on postId.
                                next is SearchOutcome.Results && next.hits.isNotEmpty() ->
                                    SearchOutcome.Results((current.hits + next.hits).distinctBy { it.postId }, next.nextOffset)
                                // An empty page is terminal even if nextOffset != null (the documented
                                // FTS+OFFSET boundary); a non-Results outcome (e.g. a 429 on the next page)
                                // retains the existing hits and hides the load-more rather than clobbering.
                                else -> current.copy(nextOffset = null)
                            }
                        it.copy(outcome = appended, isLoadingMore = false)
                    }
                }
            }
    }

    /**
     * A result card tap (#255): resolve the post through the shared by-id read and expose the detail target
     * as the consumed-once [SearchScreenUiState.pendingNavTarget]. Ignored while a target is still pending
     * (it is about to navigate) or while this same card is already resolving (a double-tap). Latest tap wins: a new tap cancels an in-flight read, and only the active
     * job writes (after [ensureActive]). `Unavailable` — or a thrown read — opens from the hit's own payload.
     */
    fun onResultTap(hit: SearchHit) {
        // A target is about to navigate, or this very card is already resolving (a double-tap) — nothing to do.
        if (state.value.pendingNavTarget != null || state.value.resolvingPostId == hit.postId) return
        resolveJob?.cancel()
        state.update { it.copy(resolvingPostId = hit.postId) }
        resolveJob =
            viewModelScope.launch {
                val resolution =
                    try {
                        flow.resolvePostTarget(hit.postId)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        PostTargetResolution.Unavailable
                    }
                ensureActive()
                val target =
                    (resolution as? PostTargetResolution.Resolved)?.toPostDetailTarget() ?: hit.toFallbackTarget()
                state.update { it.copy(resolvingPostId = null, pendingNavTarget = target) }
            }
    }

    /** The screen calls this after forwarding [SearchScreenUiState.pendingNavTarget] to the host (and when it
     *  leaves composition): clears the one-shot target and cancels any read still in flight, so a tap made
     *  during the push transition can never open a second detail when the viewer returns. */
    fun onNavConsumed() {
        resolveJob?.cancel()
        state.update { it.copy(resolvingPostId = null, pendingNavTarget = null) }
    }

    private companion object {
        const val DEBOUNCE_MILLIS: Long = 500
    }
}

/** The v1 payload a tap opens with when the by-id read is unavailable: the hit's own display fields plus the
 *  documented defaults (the search wire carries no city / like / reply / image; search has no distance). */
private fun SearchHit.toFallbackTarget(): PostDetailTarget =
    PostDetailTarget(
        postId = postId,
        content = content,
        cityName = "",
        distanceM = null,
        createdAtIso = createdAt,
        likedByViewer = false,
        replyCount = 0,
        authorUsername = authorUsername,
        authorDisplayName = authorDisplayName,
        imageUrl = null,
    )
