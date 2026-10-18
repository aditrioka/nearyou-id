package id.nearyou.app.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.nearyou.app.auth.SelfUserIdProvider
import id.nearyou.app.profile.ProfileFlow
import id.nearyou.app.profile.ProfileOutcome
import id.nearyou.app.profile.UserProfile
import id.nearyou.app.screens.username.usernameCooldownDaysRemaining
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Instant

/** What the Settings "Ganti username" entry shows (`mobile-premium-username` § "The Settings entry …"). */
sealed interface UsernameEntryState {
    /** The default row: pushes `UsernameCustomizationRoute` (also the no-read / failed-read fallback). */
    data object Open : UsernameEntryState

    /** Premium inside the 30-day cooldown (#333): the row is disabled with the day countdown as its subtitle. */
    data class Cooldown(val days: Int) : UsernameEntryState

    /** A Free user who customized before (#335): the retained-username banner; the row still pushes the route. */
    data class Retained(val username: String) : UsernameEntryState
}

/** Pure projection of the self profile at [now]. The profile's `isPremium` is `premium_active` only, so it is
 *  OR'd with [effectivePremium] — the Settings hide-distance read's `premium`, which also counts
 *  `premium_billing_retry` (the backend's username-change set) — so a billing-retry user is not told to
 *  "aktifkan Premium" and still sees the cooldown (design D3). */
fun usernameEntryState(
    profile: UserProfile,
    effectivePremium: Boolean,
    now: Instant,
): UsernameEntryState {
    if (profile.isPremium || effectivePremium) {
        val days = usernameCooldownDaysRemaining(profile.usernameLastChangedAt, now)
        return if (days != null) UsernameEntryState.Cooldown(days) else UsernameEntryState.Open
    }
    return if (profile.usernameLastChangedAt != null) UsernameEntryState.Retained(profile.username) else UsernameEntryState.Open
}

/**
 * `SettingsRoute`-NavEntry-scoped ViewModel owning the "Ganti username" entry state, alongside the screen's
 * other per-concern ViewModels (logout/toggles, account deletion, data export). It reads the caller's own
 * profile through the existing stateless [ProfileFlow] seam on init, and again on every [refresh] — which
 * the screen forwards from each `ON_RESUME` (docs/11 § 2.3), so a return from a username change or a
 * purchase shows fresh state. A read already in flight makes [refresh] a no-op; a failed read keeps the last
 * state, and an unbound seam (nulls — tests / a DI gap) stays [UsernameEntryState.Open], today's row.
 * [effectivePremium] is the screen's existing hide-distance `premium` flow (no extra request).
 */
class SettingsUsernameEntryViewModel(
    private val profileFlow: ProfileFlow?,
    private val selfUserIdProvider: SelfUserIdProvider?,
    effectivePremium: StateFlow<Boolean> = MutableStateFlow(false),
    private val now: () -> Instant = { Clock.System.now() },
) : ViewModel() {
    private val profile = MutableStateFlow<UserProfile?>(null)

    val uiState: StateFlow<UsernameEntryState> =
        combine(profile, effectivePremium) { p, premium ->
            p?.let { usernameEntryState(it, premium, now()) } ?: UsernameEntryState.Open
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UsernameEntryState.Open)

    private var readJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        val flow = profileFlow ?: return
        val ids = selfUserIdProvider ?: return
        if (readJob?.isActive == true) return
        readJob =
            viewModelScope.launch {
                val id = ids.selfUserId() ?: return@launch
                val outcome = flow.loadProfile(id)
                if (outcome is ProfileOutcome.Loaded) profile.value = outcome.profile
            }
    }
}
