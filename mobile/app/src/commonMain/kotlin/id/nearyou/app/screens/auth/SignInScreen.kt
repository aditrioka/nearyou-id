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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import id.nearyou.app.auth.AuthFlow
import id.nearyou.app.screens.routing.PendingReturnDestination
import id.nearyou.app.screens.routing.PendingSignupIdentity
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.account_separation_disclosure
import id.nearyou.resources.generated.resources.app_name
import id.nearyou.resources.generated.resources.appeal_title
import id.nearyou.resources.generated.resources.cta_retry
import id.nearyou.resources.generated.resources.cta_signin_google
import id.nearyou.resources.generated.resources.logo_brand_dark
import id.nearyou.resources.generated.resources.logo_brand_light
import id.nearyou.resources.generated.resources.signin_error_banned
import id.nearyou.resources.generated.resources.signin_error_network
import id.nearyou.resources.generated.resources.signin_error_rate_limited
import id.nearyou.resources.generated.resources.signin_error_suspended
import id.nearyou.resources.generated.resources.signin_error_token_invalid
import id.nearyou.resources.generated.resources.signin_loading
import id.nearyou.resources.generated.resources.signin_session_expired
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/** Test tag for the banned-state "Ajukan banding" entry (content-moderation-appeal). */
const val SIGNIN_APPEAL_TAG: String = "signinAppeal"

/**
 * Unauthenticated entry surface ([SignInRoute][id.nearyou.app.screens.routing.SignInRoute]): large
 * brand logo (sole header — no text title per mockup frame 13, mobile-mockup-visual-conformance)
 * + "Masuk dengan Google" CTA + the account-separation disclosure footnote. State lives in the
 * entry-scoped [SignInViewModel] (docs/11 §2.2): the CTA drives `viewModel.onSignInClick()`, and the
 * `SignInOutcome` it resolves flows through the pure [signInUiState] projection (inside the VM) to the
 * CTA label / enabled state / error banner per Decision 7 — surviving configuration change.
 *
 * Routing is hoisted into [onSignedIn] (Success → `backStack.replaceAll(HomeRoute)`) and
 * [onNoAccount] (404 no-account → `backStack.add(AgeGateRoute)`), wired by
 * [appEntryProvider][id.nearyou.app.screens.routing.appEntryProvider], and driven by the VM's one-shot
 * `navigation` field. On the 404 path the VM sets [PendingSignupIdentity] with the verified Google
 * `id_token` BEFORE raising the age-gate one-shot, so the signup flow reuses the identity without a
 * second Google ceremony — the token is held in-memory only, never carried on `AgeGateRoute` (Decision 4).
 *
 * Every user-facing string is sourced via `stringResource(Res.string.*)` — no literals.
 */
@Composable
fun SignInScreen(
    onSignedIn: () -> Unit,
    onNoAccount: () -> Unit,
    // content-moderation-appeal: opens the appeal surface from the banned/suspended state. Defaulted so
    // existing call sites / tests are unaffected; appEntryProvider wires it to `add(AppealRoute)`.
    onOpenAppeal: () -> Unit = {},
) {
    val authFlow = koinInject<AuthFlow>()
    val pendingSignupIdentity = koinInject<PendingSignupIdentity>()
    val pendingReturnDestination = koinInject<PendingReturnDestination>()

    // Entry-scoped state holder (docs/11 §2.2): the sign-in outcome / in-flight flag / the
    // mobile-session-expiry-and-proactive-refresh (D5) involuntary notice now live in the ViewModel, so
    // they survive a configuration change and the Google ceremony (on viewModelScope) is not cancelled by
    // recomposition. The notice is captured ONCE in the VM's initial state (non-clearing read of
    // PendingReturnDestination, cleared on sign-in success by appEntryProvider).
    val viewModel = viewModel { SignInViewModel(authFlow, pendingSignupIdentity, pendingReturnDestination) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

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
                SignInErrorBanner.SUSPENDED -> stringResource(Res.string.signin_error_suspended)
                SignInErrorBanner.NETWORK -> stringResource(Res.string.signin_error_network)
                SignInErrorBanner.TOKEN_INVALID -> stringResource(Res.string.signin_error_token_invalid)
                SignInErrorBanner.RATE_LIMITED -> stringResource(Res.string.signin_error_rate_limited)
            }
        }

    // Navigate from an effect (never mutate the back stack during composition). The VM owns the decision:
    //  - Home  → replaceAll(HomeRoute) (authenticated terminus, via onSignedIn).
    //  - AgeGate → the VM has already stashed the verified id_token in PendingSignupIdentity and cleared
    //    the consumed outcome; we append AgeGateRoute (via onNoAccount) so the Mobile #4 signup flow reuses
    //    the identity without a second Google ceremony (no banner shown). The token is NEVER on AgeGateRoute.
    LaunchedEffect(uiState.navigation) {
        when (uiState.navigation) {
            SignInNavTarget.Home -> onSignedIn()
            SignInNavTarget.AgeGate -> onNoAccount()
            null -> Unit
        }
        if (uiState.navigation != null) viewModel.onNavigationHandled()
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
        if (uiState.sessionExpired) {
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
        // content-moderation-appeal: a banned/suspended user gets an "Ajukan banding" entry that opens the
        // appeal surface (which reads the limited appeal token AuthRepository stashed from the 403 body).
        if (uiState.showAppealEntry) {
            TextButton(
                onClick = onOpenAppeal,
                modifier = Modifier.padding(top = 8.dp).testTag(SIGNIN_APPEAL_TAG),
            ) {
                Text(text = stringResource(Res.string.appeal_title))
            }
        }
        Button(
            onClick = {
                // Defense-in-depth: reject taps when the CTA is logically disabled
                // (Banned / in-flight), even if a synthetic click slips past `enabled`.
                if (!uiState.ctaEnabled) return@Button
                viewModel.onSignInClick()
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
