package id.nearyou.app.screens.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import id.nearyou.app.ui.billing.rememberPremiumActivating
import id.nearyou.app.ui.components.PremiumGateAction
import id.nearyou.app.ui.components.capCountdownMinutes
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.cta_retry
import id.nearyou.resources.generated.resources.premium_activating_body
import id.nearyou.resources.generated.resources.search_premium_gate_body
import id.nearyou.resources.generated.resources.search_rate_limit_reset
import id.nearyou.resources.generated.resources.search_rate_limited
import id.nearyou.resources.generated.resources.signin_error_network
import id.nearyou.resources.generated.resources.timeline_loading
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/*
 * The Cari surface's non-result state composables (`mobile-search` § "Screen state mapping"), split out of
 * `SearchScreen.kt` to keep that file under the docs/11 ~400-line soft cap. Rendered only by `SearchScreen`.
 */

@Composable
internal fun LoadingState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text(
                text = stringResource(Res.string.timeline_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

@Composable
internal fun CenteredMessage(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(24.dp),
        )
    }
}

@Composable
internal fun ErrorState(onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(Res.string.signin_error_network),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp).testTag(SEARCH_RETRY_TAG)) {
                Text(text = stringResource(Res.string.cta_retry))
            }
        }
    }
}

/**
 * The Free-tier upsell panel (the reactive 403 gate). An informational body + an "Aktifkan Premium" CTA
 * that invokes the hoisted [onActivatePremium]; the host (`appEntryProvider`) pushes
 * `PaywallRoute(SEARCH_GATE)` (mobile-paywall-screen, #254). The panel itself holds no back-stack
 * reference — `SearchScreen` stays navigation-free. After a confirmed purchase the 403 is the server tier
 * lagging the webhook, so the panel shows the activating notice and a "Coba lagi" ([onRetry], the same
 * query again) in place of the paywall CTA (#517).
 */
@Composable
internal fun PremiumGateState(
    onActivatePremium: () -> Unit,
    onRetry: () -> Unit,
) {
    val activating = rememberPremiumActivating()
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text =
                    stringResource(
                        if (activating) Res.string.premium_activating_body else Res.string.search_premium_gate_body,
                    ),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            // mobile-paywall-screen (#254): the CTA pushes PaywallRoute(SEARCH_GATE) via the host; while
            // activating (#517) it is "Coba lagi" re-running the query.
            PremiumGateAction(
                premiumActivating = activating,
                onActivatePremium = onActivatePremium,
                onRetry = onRetry,
                premiumTag = SEARCH_PREMIUM_CTA_TAG,
                retryTag = SEARCH_GATE_RETRY_TAG,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

/**
 * The rate-limit surface (429): the `docs/03:245` copy with a live countdown derived from
 * [retryAfterSeconds] (floored to one minute via [capCountdownMinutes] — never a flash-clear), ticking
 * down per minute via monotonic [delay] (no wall-clock API). When the countdown reaches zero the cap has
 * reset: the countdown is replaced by a "Coba lagi" retry control so the user MAY re-issue the query
 * ([onRetry]) — a deliberate user action, NOT an auto-fetch (which would silently re-consume the hourly
 * quota and, on a same-value re-limit, could stall on the conflated `RateLimited` state).
 */
@Composable
internal fun RateLimitedState(
    retryAfterSeconds: Long,
    onRetry: () -> Unit,
) {
    var remainingMinutes by remember(retryAfterSeconds) {
        mutableStateOf(capCountdownMinutes(retryAfterSeconds))
    }
    LaunchedEffect(retryAfterSeconds) {
        while (remainingMinutes > 0) {
            delay(60_000)
            remainingMinutes -= 1
        }
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (remainingMinutes > 0) {
                Text(
                    text = stringResource(Res.string.search_rate_limited, remainingMinutes),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            } else {
                // The cap window has elapsed — the user may re-issue the query.
                Text(
                    text = stringResource(Res.string.search_rate_limit_reset),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp).testTag(SEARCH_RETRY_TAG)) {
                    Text(text = stringResource(Res.string.cta_retry))
                }
            }
        }
    }
}
