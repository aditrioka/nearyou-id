package id.nearyou.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import id.nearyou.app.ui.billing.rememberPremiumActivating
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.cta_activate_premium
import id.nearyou.resources.generated.resources.cta_retry
import id.nearyou.resources.generated.resources.premium_activating_body
import id.nearyou.resources.generated.resources.signin_error_network
import id.nearyou.resources.generated.resources.timeline_limit_hard
import id.nearyou.resources.generated.resources.timeline_limit_soft
import org.jetbrains.compose.resources.stringResource

/** Test tag on the soft read-limit banner's "Aktifkan Premium" button. */
const val SOFT_LIMIT_PREMIUM_TAG: String = "softLimitPremium"

/** Test tag on the hard read-limit state's "Aktifkan Premium" button. */
const val HARD_LIMIT_PREMIUM_TAG: String = "hardLimitPremium"

/** Test tag on the hard read-limit state's "Coba lagi" button (shown while Premium is activating). */
const val HARD_LIMIT_RETRY_TAG: String = "hardLimitRetry"

/*
 * The shared list-state kit (`ui/components/`, docs/11 § 2.1 reuse-first + § 4 rule-of-three) — the
 * second structural move of audit 05-#11 after the post-card half (PostCard). Before this, every
 * timeline feed (Nearby / Global / Following) AND the notifications inbox carried near-verbatim private
 * copies of these non-Content list states; the drifting 3rd copy is exactly how the notifications
 * skeleton diverged (audit 05-#3). These are the canonical list states — render each inside a paginated
 * list's `PullToRefreshBox` so the pull gesture is recognized from it too (`mobile-design-system` §
 * "Canonical list loading and refresh pattern"). All copy is hoisted (passed as already-resolved
 * `String`s, except the universal copy ListErrorState and the timeline read-limit states bake in) so the
 * kit stays feature-agnostic; hosts pass their per-surface `testTag` so the existing screen tests target
 * the same swipe surface in every state.
 */

/**
 * A non-`Content` list state rendered inside a single-item `LazyColumn` carrying [testTag], so a
 * `PullToRefreshBox` recognizes the pull gesture from it (the box requires a scrollable child). The single
 * item fills the viewport and centers [content].
 */
@Composable
fun ListScrollableState(
    testTag: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    LazyColumn(modifier = modifier.fillMaxSize().testTag(testTag)) {
        item {
            Box(modifier = Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                content()
            }
        }
    }
}

/**
 * The canonical list **loading** state: a spinner + [message] (e.g. "Sedang memuat postingan…") and, when
 * [showSkeleton] is true, three placeholder surfaces signalling a list is loading (the timeline-feed
 * treatment; the notifications inbox passes `false`). Wrapped in a [ListScrollableState] tagged [testTag]
 * so the initial-load skeleton is swipeable; the refresh indicator is the host's separate `isRefreshing`.
 */
@Composable
fun ListLoadingState(
    message: String,
    testTag: String,
    showSkeleton: Boolean = false,
    modifier: Modifier = Modifier,
) {
    ListScrollableState(testTag = testTag, modifier = modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )
            if (showSkeleton) {
                // Skeleton placeholder cards (no content) to signal a list is loading.
                repeat(3) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(72.dp),
                        content = {},
                    )
                }
            }
        }
    }
}

/**
 * The canonical centered single-[message] list state (the empty copy or the terminal-401 redirect
 * notice), wrapped in a [ListScrollableState] tagged [testTag] so pull-to-refresh works from it.
 */
@Composable
fun ListCenteredMessageState(
    message: String,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    ListScrollableState(testTag = testTag, modifier = modifier) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(24.dp),
        )
    }
}

/**
 * The canonical network-**error** list state: the `signin_error_network` connectivity copy + a `cta_retry`
 * Button that fires [onRetry] (re-fetch). Wrapped in a [ListScrollableState] tagged [testTag] so
 * pull-to-refresh works from the error state too. The copy is the universal one across every list surface,
 * so it is baked in (not a param) — a surface needing different error copy renders its own state.
 */
@Composable
fun ListErrorState(
    onRetry: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    ListScrollableState(testTag = testTag, modifier = modifier) {
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
            Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
                Text(text = stringResource(Res.string.cta_retry))
            }
        }
    }
}

/**
 * The soft read-limit banner — a non-blocking `secondaryContainer` surface shown as the first item of a
 * soft-limited feed (the shared [PostFeedList] renders it). Shared by every timeline feed, so it owns its
 * `timeline_limit_soft` copy like [ListErrorState] owns its own. The end-aligned "Aktifkan Premium" text
 * button fires [onActivatePremium] (the feed opens `PaywallRoute(TIMELINE_CAP)`); the read cap is Free-only,
 * so it never reaches a server-Premium reader.
 *
 * While [premiumActivating] (a purchase is confirmed but the server still flagged the soft cap, #517) it
 * renders nothing: the soft cap blocks no reading, so a buyer needs neither the pitch nor a notice. A test
 * composing it with no Koin started passes [premiumActivating] explicitly.
 */
@Composable
fun SoftLimitBanner(
    onActivatePremium: () -> Unit,
    modifier: Modifier = Modifier,
    premiumActivating: Boolean = rememberPremiumActivating(),
) {
    if (premiumActivating) return
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(modifier = Modifier.padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 4.dp)) {
            Text(
                text = stringResource(Res.string.timeline_limit_soft),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            TextButton(
                onClick = onActivatePremium,
                modifier = Modifier.align(Alignment.End).testTag(SOFT_LIMIT_PREMIUM_TAG),
            ) {
                Text(text = stringResource(Res.string.cta_activate_premium))
            }
        }
    }
}

/**
 * The canonical hard read-limit list state (`timeline_limit_hard`, shared by every timeline feed): the copy
 * + a filled "Aktifkan Premium" button firing [onActivatePremium] (the feed opens
 * `PaywallRoute(TIMELINE_CAP)`), laid out like [ListErrorState] and wrapped in a [ListScrollableState] tagged
 * [testTag] so pull-to-refresh works from it. The read cap is Free-only, so it never reaches a server-Premium
 * reader.
 *
 * While [premiumActivating] (a purchase is confirmed but the server still returned the hard cap, #517) it
 * reads `premium_activating_body` and the button becomes "Coba lagi" firing [onRetry] (the feed's page-1
 * reload), so the buyer re-checks once the webhook lands. A test composing it with no Koin started passes
 * [premiumActivating] explicitly.
 */
@Composable
fun HardLimitState(
    onActivatePremium: () -> Unit,
    onRetry: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
    premiumActivating: Boolean = rememberPremiumActivating(),
) {
    ListScrollableState(testTag = testTag, modifier = modifier) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text =
                    stringResource(
                        if (premiumActivating) Res.string.premium_activating_body else Res.string.timeline_limit_hard,
                    ),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (premiumActivating) {
                Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp).testTag(HARD_LIMIT_RETRY_TAG)) {
                    Text(text = stringResource(Res.string.cta_retry))
                }
            } else {
                Button(
                    onClick = onActivatePremium,
                    modifier = Modifier.padding(top = 16.dp).testTag(HARD_LIMIT_PREMIUM_TAG),
                ) {
                    Text(text = stringResource(Res.string.cta_activate_premium))
                }
            }
        }
    }
}
