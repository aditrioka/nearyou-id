package id.nearyou.app.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.nearyou.app.auth.TokenStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * `SettingsRoute`-scoped ViewModel owning the logout action. Logout is a CLIENT-side token wipe: it
 * clears [TokenStore] and raises [loggedOut] so the screen routes to sign-in via `replaceAll` (no
 * server call — server-side bearer revoke on logout is a pre-launch hardening follow-up, design D6).
 */
class SettingsViewModel(
    private val tokenStore: TokenStore,
) : ViewModel() {
    private val _loggedOut = MutableStateFlow(false)
    val loggedOut: StateFlow<Boolean> = _loggedOut.asStateFlow()

    fun confirmLogout() {
        viewModelScope.launch {
            tokenStore.clear()
            _loggedOut.value = true
        }
    }
}
