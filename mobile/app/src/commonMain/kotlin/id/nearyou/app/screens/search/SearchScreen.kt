package id.nearyou.app.screens.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import id.nearyou.app.auth.SelfUserIdProvider
import id.nearyou.app.profile.ProfileFlow
import id.nearyou.app.screens.home.PostDetailTarget
import id.nearyou.app.search.SearchFlow
import id.nearyou.app.ui.billing.rememberPremiumConfirmed
import id.nearyou.resources.generated.resources.Res
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
import id.nearyou.resources.generated.resources.timeline_session_redirect
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/** Test tag on the result list — lets the screen test target it. */
const val SEARCH_RESULT_LIST_TAG: String = "searchResultList"

/** Test tag on each result card — lets the screen test target the open-detail tap. */
const val SEARCH_RESULT_CARD_TAG: String = "searchResultCard"

/** Test tag on a result card's spinner while its tap's by-id read is in flight (#255). */
const val SEARCH_RESULT_RESOLVING_TAG: String = "searchResultResolving"

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

/** Test tag on the Premium gate's "Coba lagi" (shown while Premium is activating, #517). */
const val SEARCH_GATE_RETRY_TAG: String = "searchGateRetry"

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
 * copy via `stringResource` (zero literals), under `NearYouTheme`. A known-Free viewer sees the PremiumGate
 * upsell in place of the Idle prompt before typing (#253). A result tap is resolved by the ViewModel through
 * the by-id post read (#255) and the resulting [PostDetailTarget] is forwarded to the hoisted [onOpenPost]
 * (the `appEntryProvider` call site pushes `PostDetailRoute`); the screen stays navigation-free (no
 * back-stack reference). PII discipline: [SearchHit] carries no `author_id`/`rank`.
 */
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOpenPost: (PostDetailTarget) -> Unit = {},
    onActivatePremium: () -> Unit = {},
) {
    val flow = koinInject<SearchFlow>()
    // #253: the self-profile read seam for the on-entry Premium gate (the SAME ProfileFlow +
    // SelfUserIdProvider the username / Nearby gates use).
    val profileFlow = koinInject<ProfileFlow>()
    val selfUserIdProvider = koinInject<SelfUserIdProvider>()
    // premium-entitlement-lifecycle: the confirmed-purchase signal (fail-safe: never-confirmed when unbound).
    val premiumConfirmed = rememberPremiumConfirmed()
    val viewModel = viewModel { SearchViewModel(flow, profileFlow, selfUserIdProvider, premiumConfirmed) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // #255: forward the resolved detail target to the host exactly once, then clear it (the consumed-once
    // one-shot pattern — NO Channel/SharedFlow). Leaving composition (covered by the pushed detail) also
    // clears it + cancels a read still in flight, so a tap made mid-transition never opens a second detail.
    LaunchedEffect(uiState.pendingNavTarget) {
        val target = uiState.pendingNavTarget ?: return@LaunchedEffect
        onOpenPost(target)
        viewModel.onNavConsumed()
    }
    DisposableEffect(viewModel) { onDispose(viewModel::onNavConsumed) }

    Scaffold(
        topBar = {
            SearchTopBar(
                query = uiState.query,
                onQueryChange = viewModel::onQueryChange,
                onSubmit = viewModel::onSubmit,
                onClear = { viewModel.onQueryChange("") },
                onBack = onBack,
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val state = uiState.surface) {
                SearchUiState.Idle -> CenteredMessage(stringResource(Res.string.search_idle_prompt))
                SearchUiState.Loading -> LoadingState()
                is SearchUiState.Results ->
                    ResultsState(
                        state = state,
                        resolvingPostId = uiState.resolvingPostId,
                        onOpenPost = viewModel::onResultTap,
                        onLoadMore = viewModel::loadMore,
                    )
                is SearchUiState.EmptyResults ->
                    CenteredMessage(stringResource(Res.string.search_empty_results, state.query))
                SearchUiState.Error -> ErrorState(onRetry = viewModel::retry)
                SearchUiState.PremiumGate -> PremiumGateState(onActivatePremium = onActivatePremium, onRetry = viewModel::retry)
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
    resolvingPostId: String?,
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
                isResolving = hit.postId == resolvingPostId,
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
