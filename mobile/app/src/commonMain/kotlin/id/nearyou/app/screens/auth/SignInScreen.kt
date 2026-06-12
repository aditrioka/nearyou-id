package id.nearyou.app.screens.auth

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import id.nearyou.app.auth.AuthFlow
import id.nearyou.app.auth.SignInOutcome
import id.nearyou.app.screens.routing.PendingReturnDestination
import id.nearyou.app.screens.routing.PendingSignupIdentity
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.account_separation_disclosure
import id.nearyou.resources.generated.resources.app_name
import id.nearyou.resources.generated.resources.cta_retry
import id.nearyou.resources.generated.resources.cta_signin_google
import id.nearyou.resources.generated.resources.logo_brand_dark
import id.nearyou.resources.generated.resources.logo_brand_light
import id.nearyou.resources.generated.resources.signin_error_banned
import id.nearyou.resources.generated.resources.signin_error_network
import id.nearyou.resources.generated.resources.signin_error_token_invalid
import id.nearyou.resources.generated.resources.signin_loading
import id.nearyou.resources.generated.resources.signin_session_expired
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/**
 * Unauthenticated entry surface ([SignInRoute][id.nearyou.app.screens.routing.SignInRoute]): brand
 * logo + title + "Masuk dengan Google" CTA + the account-separation disclosure footnote. The CTA
 * drives `AuthFlow.signInWithGoogle()`; the resulting [SignInOutcome] flows through [signInUiState]
 * to set the CTA label / enabled state / error banner per Decision 7.
 *
 * Routing is hoisted into [onSignedIn] (Success → `backStack.replaceAll(HomeRoute)`) and
 * [onNoAccount] (404 no-account → `backStack.add(AgeGateRoute)`), wired by
 * [appEntryProvider][id.nearyou.app.screens.routing.appEntryProvider]. On the 404 path the screen
 * sets [PendingSignupIdentity] with the verified Google `id_token` BEFORE invoking [onNoAccount], so
 * the signup flow reuses the identity without a second Google ceremony — the token is held
 * in-memory only, never carried on `AgeGateRoute` (design Decision 4).
 *
 * Every user-facing string is sourced via `stringResource(Res.string.*)` — no literals.
 */
@Composable
fun SignInScreen(
    onSignedIn: () -> Unit,
    onNoAccount: () -> Unit,
) {
    val authFlow = koinInject<AuthFlow>()
    val pendingSignupIdentity = koinInject<PendingSignupIdentity>()
    val pendingReturnDestination = koinInject<PendingReturnDestination>()
    val scope = rememberCoroutineScope()

    // mobile-session-expiry-and-proactive-refresh (D5) — show the session-expired notice ONLY when this
    // screen was reached via an involuntary terminal-401 re-route (captured by SessionExpiryEffect),
    // never on a fresh launch. Captured ONCE on entry: the holder is cleared on sign-in success (which
    // navigates away), and a non-clearing read keeps the notice stable across recompositions.
    val sessionExpired = remember { pendingReturnDestination.wasInvoluntary() }

    var outcome by remember { mutableStateOf<SignInOutcome?>(null) }
    var inFlight by remember { mutableStateOf(false) }

    val uiState = signInUiState(outcome = outcome, inFlight = inFlight)

    val logo =
        if (isSystemInDarkTheme()) Res.drawable.logo_brand_dark else Res.drawable.logo_brand_light

    val ctaText =
        when (uiState.ctaLabel) {
            SignInCtaLabel.GOOGLE -> stringResource(Res.string.cta_signin_google)
            SignInCtaLabel.RETRY -> stringResource(Res.string.cta_retry)
            SignInCtaLabel.LOADING -> stringResource(Res.string.signin_loading)
        }

    val bannerText: String? =
        uiState.errorBanner?.let { banner ->
            when (banner) {
                SignInErrorBanner.BANNED -> stringResource(Res.string.signin_error_banned)
                SignInErrorBanner.NETWORK -> stringResource(Res.string.signin_error_network)
                SignInErrorBanner.TOKEN_INVALID -> stringResource(Res.string.signin_error_token_invalid)
            }
        }

    // Navigate from an effect (never mutate the back stack during composition):
    //  - Success → replaceAll(HomeRoute) (authenticated terminus, via onSignedIn).
    //  - NoAccount (404) → set the verified id_token in the in-memory holder, then append
    //    AgeGateRoute (via onNoAccount) so the Mobile #4 signup flow reuses it without a second
    //    Google ceremony (no banner shown). The token is NEVER carried on AgeGateRoute.
    LaunchedEffect(outcome) {
        when (val current = outcome) {
            SignInOutcome.Success -> onSignedIn()
            is SignInOutcome.NoAccount -> {
                pendingSignupIdentity.set(current.idToken)
                onNoAccount()
                // Clear the consumed outcome so returning here (system-back from the age gate, which
                // leaves SignInRoute in the back stack with its retained state) cannot re-fire this
                // effect and re-append AgeGateRoute — the user lands on a clean, initial SignInScreen.
                outcome = null
            }
            else -> Unit
        }
    }

    Column(
        modifier =
            Modifier
                .background(MaterialTheme.colorScheme.background)
                .safeContentPadding()
                .fillMaxSize()
                .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Frame 13: the large brand logo is the screen's sole header — no text title
        // (mobile-mockup-visual-conformance; signin_screen_title stays in the catalog).
        Image(
            painter = painterResource(logo),
            contentDescription = stringResource(Res.string.app_name),
            modifier = Modifier.size(96.dp),
        )
        // Involuntary-logout notice (mobile-session-expiry-and-proactive-refresh, D5). Informational tone
        // (onSurfaceVariant, NOT the error color) and a DISTINCT string from signin_error_network — it is
        // not a connectivity failure. Shown only on an involuntary re-route; never on a fresh launch.
        if (sessionExpired) {
            Text(
                text = stringResource(Res.string.signin_session_expired),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        if (bannerText != null) {
            Text(
                text = bannerText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        Button(
            onClick = {
                // Defense-in-depth: reject taps when the CTA is logically disabled
                // (Banned / in-flight), even if a synthetic click slips past `enabled`.
                if (!uiState.ctaEnabled) return@Button
                scope.launch {
                    inFlight = true
                    try {
                        outcome = authFlow.signInWithGoogle()
                    } finally {
                        // Reset even if the launch job is cancelled mid-ceremony (config
                        // change / screen disposal) so the CTA never sticks on "loading".
                        inFlight = false
                    }
                }
            },
            enabled = uiState.ctaEnabled,
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
        ) {
            Text(text = ctaText)
        }
        Text(
            text = stringResource(Res.string.account_separation_disclosure),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 24.dp),
        )
    }
}
