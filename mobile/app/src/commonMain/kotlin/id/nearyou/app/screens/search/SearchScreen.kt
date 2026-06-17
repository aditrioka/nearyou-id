package id.nearyou.app.screens.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import id.nearyou.app.search.SearchFlow
import id.nearyou.app.ui.components.capCountdownMinutes
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.cta_activate_premium
import id.nearyou.resources.generated.resources.cta_retry
import id.nearyou.resources.generated.resources.ic_action_clear
import id.nearyou.resources.generated.resources.ic_action_search
import id.nearyou.resources.generated.resources.ic_nav_back
import id.nearyou.resources.generated.resources.search_back_cd
import id.nearyou.resources.generated.resources.search_clear_cd
import id.nearyou.resources.generated.resources.search_disabled
import id.nearyou.resources.generated.resources.search_empty_results
import id.nearyou.resources.generated.resources.search_hint
import id.nearyou.resources.generated.resources.search_icon_cd
import id.nearyou.resources.generated.resources.search_idle_prompt
import id.nearyou.resources.generated.resources.search_load_more
import id.nearyou.resources.generated.resources.search_premium_gate_body
import id.nearyou.resources.generated.resources.search_rate_limit_reset
import id.nearyou.resources.generated.resources.search_rate_limited
import id.nearyou.resources.generated.resources.signin_error_network
import id.nearyou.resources.generated.resources.timeline_loading
import id.nearyou.resources.generated.resources.timeline_session_redirect
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/** Test tag on the result list — lets the screen test target it. */
const val SEARCH_RESULT_LIST_TAG: String = "searchResultList"

/** Test tag on each result card — lets the screen test target the open-detail tap. */
const val SEARCH_RESULT_CARD_TAG: String = "searchResultCard"

/** Test tag on the search text field. */
const val SEARCH_FIELD_TAG: String = "searchField"

/** Test tag on the back affordance. */
const val SEARCH_BACK_TAG: String = "searchBack"

/** Test tag on the clear affordance (visible while the field is non-empty). */
const val SEARCH_CLEAR_TAG: String = "searchClear"

/** Test tag on the "Lihat lebih banyak" load-more control. */
const val SEARCH_LOAD_MORE_TAG: String = "searchLoadMore"

/** Test tag on the error-state retry control. */
const val SEARCH_RETRY_TAG: String = "searchRetry"

/** Test tag on the Premium-gate "Aktifkan Premium" CTA. */
const val SEARCH_PREMIUM_CTA_TAG: String = "searchPremiumCta"

/**
 * The Premium-gated **Cari** (search) surface (`mobile-search`) — reached from the Home brand app bar's
 * search action icon and pushed onto the ROOT back stack (overlaying the section bar, mirroring
 * `PostDetailScreen`). Injects [SearchFlow] and observes a `SearchRoute`-scoped [SearchViewModel] that
 * holds the query + result state and issues the fetch (500 ms debounce + keyboard submit, only when the
 * `SearchQueryGuard` passes).
 *
 * This screen OWNS its own minimal top bar (a root-stack overlay, like `PostDetailScreen`): a back
 * affordance (hoisted [onBack]) + an M3 search text field + a clear affordance. Below, the result list /
 * state surface fills the remaining space, mapping to exactly one [SearchUiState] (Idle / Loading /
 * Results+load-more / EmptyResults / Error / PremiumGate / RateLimited / Disabled / SessionRedirect), all
 * copy via `stringResource` (zero literals), under `NearYouTheme`. A result tap invokes the hoisted
 * [onOpenPost] (the `appEntryProvider` call site pushes `PostDetailRoute`); the screen stays
 * navigation-free (no back-stack reference). PII discipline: [SearchHit] carries no `author_id`/`rank`.
 */
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOpenPost: (SearchHit) -> Unit = {},
    onActivatePremium: () -> Unit = {},
) {
    val flow = koinInject<SearchFlow>()
    val viewModel = viewModel { SearchViewModel(flow) }
    val query by viewModel.query.collectAsStateWithLifecycle()
    val outcome by viewModel.outcome.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val isLoadingMore by viewModel.isLoadingMore.collectAsStateWithLifecycle()

    val uiState =
        remember(query, outcome, isLoading, isLoadingMore) {
            searchUiState(query, outcome, isLoading, isLoadingMore)
        }

    Scaffold(
        topBar = {
            SearchTopBar(
                query = query,
                onQueryChange = viewModel::onQueryChange,
                onSubmit = viewModel::onSubmit,
                onClear = { viewModel.onQueryChange("") },
                onBack = onBack,
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val state = uiState) {
                SearchUiState.Idle -> CenteredMessage(stringResource(Res.string.search_idle_prompt))
                SearchUiState.Loading -> LoadingState()
                is SearchUiState.Results ->
                    ResultsState(
                        state = state,
                        onOpenPost = onOpenPost,
                        onLoadMore = viewModel::loadMore,
                    )
                is SearchUiState.EmptyResults ->
                    CenteredMessage(stringResource(Res.string.search_empty_results, state.query))
                SearchUiState.Error -> ErrorState(onRetry = viewModel::retry)
                SearchUiState.PremiumGate -> PremiumGateState(onActivatePremium = onActivatePremium)
                is SearchUiState.RateLimited ->
                    RateLimitedState(retryAfterSeconds = state.retryAfterSeconds, onRetry = viewModel::retry)
                SearchUiState.Disabled -> CenteredMessage(stringResource(Res.string.search_disabled))
                SearchUiState.SessionRedirect ->
                    CenteredMessage(stringResource(Res.string.timeline_session_redirect))
            }
        }
    }
}

/**
 * The screen's own top bar (root-stack overlay, so it applies its own status-bar inset — the
 * `PostDetailScreen` precedent): a back [IconButton] (hoisted [onBack]) + a single-line search
 * [TextField] (leading search glyph, trailing clear affordance while non-empty, ime action Search →
 * [onSubmit]).
 */
@Composable
private fun SearchTopBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton(onClick = onBack, modifier = Modifier.testTag(SEARCH_BACK_TAG)) {
            Icon(
                painter = painterResource(Res.drawable.ic_nav_back),
                contentDescription = stringResource(Res.string.search_back_cd),
            )
        }
        TextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f).testTag(SEARCH_FIELD_TAG),
            singleLine = true,
            placeholder = { Text(text = stringResource(Res.string.search_hint)) },
            leadingIcon = {
                Icon(
                    painter = painterResource(Res.drawable.ic_action_search),
                    contentDescription = stringResource(Res.string.search_icon_cd),
                )
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = onClear, modifier = Modifier.testTag(SEARCH_CLEAR_TAG)) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_action_clear),
                            contentDescription = stringResource(Res.string.search_clear_cd),
                        )
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions =
                KeyboardActions(
                    onSearch = {
                        keyboard?.hide()
                        onSubmit()
                    },
                ),
            colors =
                TextFieldDefaults.colors(
                    focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                    unfocusedIndicatorColor = MaterialTheme.colorScheme.outlineVariant,
                ),
        )
    }
}

@Composable
private fun ResultsState(
    state: SearchUiState.Results,
    onOpenPost: (SearchHit) -> Unit,
    onLoadMore: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag(SEARCH_RESULT_LIST_TAG),
        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
    ) {
        items(items = state.hits, key = { it.postId }, contentType = { "hit" }) { hit ->
            SearchResultCard(
                hit = hit,
                onOpen = { onOpenPost(hit) },
                modifier = Modifier.testTag(SEARCH_RESULT_CARD_TAG),
            )
        }
        if (state.showLoadMore) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (state.isLoadingMore) {
                        CircularProgressIndicator()
                    } else {
                        OutlinedButton(onClick = onLoadMore, modifier = Modifier.testTag(SEARCH_LOAD_MORE_TAG)) {
                            Text(text = stringResource(Res.string.search_load_more))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingState() {
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
private fun CenteredMessage(message: String) {
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
private fun ErrorState(onRetry: () -> Unit) {
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
 * reference — `SearchScreen` stays navigation-free.
 */
@Composable
private fun PremiumGateState(onActivatePremium: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(Res.string.search_premium_gate_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Button(
                // mobile-paywall-screen (#254): the CTA now pushes PaywallRoute(SEARCH_GATE) via the host.
                onClick = onActivatePremium,
                modifier = Modifier.padding(top = 16.dp).testTag(SEARCH_PREMIUM_CTA_TAG),
            ) {
                Text(text = stringResource(Res.string.cta_activate_premium))
            }
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
private fun RateLimitedState(
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
