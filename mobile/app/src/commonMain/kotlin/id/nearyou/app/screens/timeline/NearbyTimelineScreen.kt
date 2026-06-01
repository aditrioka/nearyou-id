package id.nearyou.app.screens.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import id.nearyou.app.location.LocationConsentModal
import id.nearyou.app.location.LocationGate
import id.nearyou.app.location.LocationGateUiState
import id.nearyou.app.location.LocationPermissionController
import id.nearyou.app.timeline.NearbyTimelineFlow
import id.nearyou.app.timeline.NearbyTimelineOutcome
import id.nearyou.distance.DistanceRenderer
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.cta_retry
import id.nearyou.resources.generated.resources.location_open_settings
import id.nearyou.resources.generated.resources.nearby_location_denied
import id.nearyou.resources.generated.resources.signin_error_network
import id.nearyou.resources.generated.resources.timeline_empty_nearby
import id.nearyou.resources.generated.resources.timeline_limit_hard
import id.nearyou.resources.generated.resources.timeline_limit_soft
import id.nearyou.resources.generated.resources.timeline_loading
import id.nearyou.resources.generated.resources.timeline_nearby_title
import id.nearyou.resources.theme.locationPin
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import kotlin.coroutines.cancellation.CancellationException

/** Test tag on the scrollable post list — lets the screen test target a pull-to-refresh swipe. */
const val NEARBY_TIMELINE_LIST_TAG: String = "nearbyTimelineList"

/**
 * The first product surface — the authenticated Nearby feed, gated on a granted location permission
 * (`mobile-location-permission-flow`). Injects [LocationPermissionController] and drives a
 * [LocationGate] that, on entry, projects the OS permission status to one of: prompt the UU-PDP
 * consent rationale (→ OS prompt), proceed to the fetch, or show the denial fallback. The granted
 * branch ([NearbyFeed]) is the previously-shipped feed: it injects [NearbyTimelineFlow], holds the
 * [NearbyTimelineOutcome] + in-flight flag in `remember`, and (re)loads via a `LaunchedEffect` keyed
 * on a reload counter (pull-to-refresh + error-retry both re-fetch). Renders the six fetch states
 * (loading / content / empty / error / rate-limit-hard / rate-limit-soft) per the screen-state-mapping
 * spec, all copy via `stringResource` (zero literals), under `NearYouTheme` tokens. Holds NO
 * navigation dependency, so `HomeScreen` can embed `Content()` directly.
 *
 * The gate is a **pre-fetch** screen state, orthogonal to the six fetch-outcome states:
 * `NearbyTimelineRepository`'s status→outcome mapping is unchanged (granted-but-no-fix reuses the
 * existing retryable error state, no new outcome member).
 */
class NearbyTimelineScreen : Screen {
    @Composable
    override fun Content() {
        val controller = koinInject<LocationPermissionController>()
        val gate = remember { LocationGate(controller) }
        val gateState by gate.state.collectAsState()
        val scope = rememberCoroutineScope()

        // Query the OS permission on entry / re-entry. refresh() NEVER fires the OS prompt, so a prior
        // denial does not re-nag the rationale on every Nearby visit (the "Buka Pengaturan" CTA is the
        // only re-entry path) — spec § "A prior denial does not re-show the rationale on every Nearby visit".
        LaunchedEffect(Unit) { gate.refresh() }

        when (gateState) {
            LocationGateUiState.Loading -> LocationGateSpinner()
            LocationGateUiState.Rationale -> {
                // Neutral backdrop while the consent rationale modal is up; accepting fires the OS
                // prompt, declining drops to the denial fallback (no OS prompt forced).
                LocationGateSpinner()
                LocationConsentModal(
                    onAccept = { scope.launch { gate.onRationaleAccepted() } },
                    onDecline = { gate.onRationaleDeclined() },
                )
            }
            LocationGateUiState.Denied -> LocationDeniedState(onOpenSettings = { controller.openAppSettings() })
            LocationGateUiState.Granted -> NearbyFeed()
        }
    }
}

/**
 * The granted-permission Nearby feed: the previously-shipped fetch path, unchanged except that a
 * coordinate-acquisition failure (the real provider throwing [id.nearyou.app.location.LocationUnavailableException]
 * when permission is granted but no fix is obtainable) is caught here and mapped to the EXISTING
 * retryable error state — NO new [NearbyTimelineOutcome] member (spec § "Granted but no coordinate
 * obtainable maps to the existing retryable error state"; the repository's sealed outcome is unchanged).
 */
@Composable
private fun NearbyFeed() {
    val flow = koinInject<NearbyTimelineFlow>()
    var outcome by remember { mutableStateOf<NearbyTimelineOutcome?>(null) }
    var inFlight by remember { mutableStateOf(false) }
    // Incrementing this re-keys the LaunchedEffect → re-fetches page 1. Both pull-to-refresh and the
    // error-retry control increment it (shared reload path). `next_cursor` is retained on Loaded but
    // NOT consumed for load-more (deferred to mobile-nearby-timeline-infinite-scroll).
    var reloadKey by remember { mutableStateOf(0) }

    LaunchedEffect(reloadKey) {
        inFlight = true
        try {
            outcome = flow.loadFirstPage()
        } catch (cancellation: CancellationException) {
            // Never swallow cancellation — let structured concurrency unwind (mirrors AuthApiClient).
            throw cancellation
        } catch (_: Throwable) {
            // Granted-but-no-fix: the provider could not acquire a coordinate → existing retryable
            // error state (network copy + retry). No new NearbyTimelineOutcome member is introduced.
            outcome = NearbyTimelineOutcome.NetworkError
        } finally {
            inFlight = false
        }
    }

    NearbyTimelineContent(
        uiState = nearbyTimelineUiState(outcome, inFlight),
        isRefreshing = inFlight,
        onRefresh = { reloadKey++ },
        onRetry = { reloadKey++ },
    )
}

/** Neutral centered spinner shown while the OS permission status is being queried (or behind the modal). */
@Composable
private fun LocationGateSpinner() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/**
 * Pre-fetch location-permission-denied state: the "Aktifkan lokasi…" copy + a "Buka Pengaturan" CTA
 * that deep-links to the OS app-settings screen. No posts are fetched in this state (spec § "Nearby
 * feed is gated on location permission with a denial fallback").
 */
@Composable
private fun LocationDeniedState(onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(Res.string.nearby_location_denied),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onOpenSettings, modifier = Modifier.padding(top = 16.dp)) {
            Text(text = stringResource(Res.string.location_open_settings))
        }
    }
}

/**
 * Stateless render of the Nearby surface: a top bar titled `timeline_nearby_title` over a
 * pull-to-refresh container that shows one of the six states. Separated from [NearbyTimelineScreen]
 * so the render is a pure function of [uiState] (the screen test drives it via a fake flow).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NearbyTimelineContent(
    uiState: NearbyTimelineUiState,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(text = stringResource(Res.string.timeline_nearby_title)) })
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            when (uiState) {
                NearbyTimelineUiState.Loading -> LoadingState()
                NearbyTimelineUiState.Empty -> CenteredMessage(stringResource(Res.string.timeline_empty_nearby))
                NearbyTimelineUiState.HardLimit -> CenteredMessage(stringResource(Res.string.timeline_limit_hard))
                NearbyTimelineUiState.Error -> ErrorState(onRetry = onRetry)
                is NearbyTimelineUiState.Content -> PostList(posts = uiState.posts)
                is NearbyTimelineUiState.SoftLimit ->
                    PostList(
                        posts = uiState.posts,
                        banner = stringResource(Res.string.timeline_limit_soft),
                    )
            }
        }
    }
}

@Composable
private fun LoadingState() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Text(
            text = stringResource(Res.string.timeline_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp),
        )
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

@Composable
private fun CenteredMessage(message: String) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ErrorState(onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
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

@Composable
private fun PostList(
    posts: List<NearbyTimelinePost>,
    banner: String? = null,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag(NEARBY_TIMELINE_LIST_TAG),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        if (banner != null) {
            item {
                SoftLimitBanner(text = banner)
            }
        }
        items(items = posts, key = { it.id }) { post ->
            NearbyPostCard(post = post)
        }
    }
}

@Composable
private fun SoftLimitBanner(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(12.dp),
        )
    }
}

/**
 * Read-only post card (design D9): `content`, a metadata row (coral location-pin dot + `city_name`
 * when non-empty + the shared `DistanceRenderer` string + the post date), and a read-only counts row
 * (a like-state dot + `reply_count`). Renders NO `author_user_id` and NO raw `latitude`/`longitude`
 * (those fields are not even present on [NearbyTimelinePost]). Built entirely from `NearYouTheme`
 * tokens; the coral accent is the brand `locationPin` extension.
 */
@Composable
private fun NearbyPostCard(post: NearbyTimelinePost) {
    OutlinedCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = post.content,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Coral location-pin affordance (no material-icons dependency — a brand-tinted dot).
                Box(modifier = Modifier.size(8.dp).background(MaterialTheme.colorScheme.locationPin, CircleShape))
                if (post.cityName.isNotEmpty()) {
                    Text(
                        text = post.cityName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = DistanceRenderer.render(post.distanceM),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    // The post date (ISO date portion). Richer relative formatting ("2j lalu") is a
                    // deferred refinement — it needs a localized unit-string set (beyond this change's
                    // declared timeline strings) + an injected clock; tracked as a FOLLOW_UP.
                    text = postDateLabel(post.createdAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Like-state affordance: filled brand-tinted dot when the viewer liked it, else a
                // muted outline-tinted dot. Decorative (the row conveys read-only counts) → no
                // contentDescription literal.
                Box(
                    modifier =
                        Modifier.size(10.dp).background(
                            color =
                                if (post.likedByViewer) {
                                    MaterialTheme.colorScheme.locationPin
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                            shape = CircleShape,
                        ),
                )
                Text(
                    text = post.replyCount.toString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** ISO-8601 `createdAt` → its date portion ("2026-05-31"). Pure + deterministic (no wall clock). */
private fun postDateLabel(createdAt: String): String = createdAt.substringBefore('T')
