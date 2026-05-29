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
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import id.nearyou.app.auth.AuthFlow
import id.nearyou.app.auth.SignInOutcome
import id.nearyou.app.screens.home.HomeScreen
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.account_separation_disclosure
import id.nearyou.resources.generated.resources.app_name
import id.nearyou.resources.generated.resources.cta_retry
import id.nearyou.resources.generated.resources.cta_signin_google
import id.nearyou.resources.generated.resources.logo_brand_dark
import id.nearyou.resources.generated.resources.logo_brand_light
import id.nearyou.resources.generated.resources.signin_error_banned
import id.nearyou.resources.generated.resources.signin_error_network
import id.nearyou.resources.generated.resources.signin_error_no_account
import id.nearyou.resources.generated.resources.signin_error_token_invalid
import id.nearyou.resources.generated.resources.signin_loading
import id.nearyou.resources.generated.resources.signin_screen_title
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/**
 * Unauthenticated entry surface: brand logo + title + "Masuk dengan Google" CTA + the
 * account-separation disclosure footnote. The CTA drives `AuthFlow.signInWithGoogle()`; the
 * resulting [SignInOutcome] flows through [signInUiState] to set the CTA label / enabled
 * state / error banner per Decision 7. On [SignInOutcome.Success] the navigator replaces the
 * stack with `HomeScreen`.
 *
 * Every user-facing string is sourced via `stringResource(Res.string.*)` — no literals.
 */
class SignInScreen : Screen {
    @Composable
    override fun Content() {
        val authFlow = koinInject<AuthFlow>()
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()

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
                    SignInErrorBanner.NO_ACCOUNT -> stringResource(Res.string.signin_error_no_account)
                    SignInErrorBanner.BANNED -> stringResource(Res.string.signin_error_banned)
                    SignInErrorBanner.NETWORK -> stringResource(Res.string.signin_error_network)
                    SignInErrorBanner.TOKEN_INVALID -> stringResource(Res.string.signin_error_token_invalid)
                }
            }

        // Navigate on Success from an effect (never mutate the navigator during composition).
        LaunchedEffect(outcome) {
            if (outcome == SignInOutcome.Success) {
                navigator.replaceAll(HomeScreen())
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
            Image(
                painter = painterResource(logo),
                contentDescription = stringResource(Res.string.app_name),
                modifier = Modifier.size(120.dp),
            )
            Text(
                text = stringResource(Res.string.signin_screen_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 24.dp),
            )
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
                        val result = authFlow.signInWithGoogle()
                        inFlight = false
                        outcome = result
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
}
