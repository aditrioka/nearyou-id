package id.nearyou.app.screens.followlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import id.nearyou.app.followlist.FollowListFlow
import id.nearyou.app.followlist.FollowListTab
import id.nearyou.app.followlist.FollowListUser
import id.nearyou.app.ui.components.LetterAvatar
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.cta_close
import id.nearyou.resources.generated.resources.cta_retry
import id.nearyou.resources.generated.resources.follow_list_empty_followers
import id.nearyou.resources.generated.resources.follow_list_empty_following
import id.nearyou.resources.generated.resources.follow_list_load_more_failed
import id.nearyou.resources.generated.resources.follow_list_tab_followers
import id.nearyou.resources.generated.resources.follow_list_tab_following
import id.nearyou.resources.generated.resources.follow_list_title
import id.nearyou.resources.generated.resources.ic_premium_star
import id.nearyou.resources.generated.resources.post_card_handle
import id.nearyou.resources.generated.resources.profile_not_found
import id.nearyou.resources.generated.resources.profile_premium_badge_icon_description
import id.nearyou.resources.generated.resources.signin_error_network
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import androidx.compose.material3.Tab as Material3Tab

/** Test tags for the follow-list surface (Robolectric `FollowListScreenTest`). */
const val FOLLOW_LIST_PAGER_TAG: String = "followListPager"
const val FOLLOW_LIST_BACK_TAG: String = "followListBack"
const val FOLLOW_LIST_LOAD_MORE_RETRY_TAG: String = "followListLoadMoreRetry"

/**
 * The follow-list surface (`mobile-follow-lists`) — a root-stack overlay reached via `FollowListRoute`,
 * opened by tapping the profile's follower / following counts. Renders a single screen with two tabs
 * (Pengikut / Mengikuti) as a Material 3 `PrimaryTabRow` + swipeable [HorizontalPager] synced to it (the
 * shipped Home feed-tabs idiom), opening on [initialTab] (the tapped count's side). Each tab is a paginated
 * `LazyColumn` over the shipped `GET /users/{id}/followers` + `/following`, observing a
 * `FollowListRoute`-scoped [FollowListViewModel]; the deep-linked tab fetches in the VM init, the other on
 * first reveal ([FollowListViewModel.onTabRevealed]). Each row taps through to [onOpenProfile]
 * (`ProfileRoute`). Owns its own back-bar chrome (a `TopAppBar` + back, like the other-user `ProfileScreen`
 * overlay). No hardcoded UI string literal appears here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FollowListScreen(
    userId: String,
    initialTab: FollowListTab,
    onBack: () -> Unit,
    onOpenProfile: (userId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val flow = koinInject<FollowListFlow>()
    val viewModel = viewModel { FollowListViewModel(flow, userId, initialTab) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val tabs = FollowListTab.entries
    val pagerState = rememberPagerState(initialPage = initialTab.ordinal) { tabs.size }
    val scope = rememberCoroutineScope()

    // Lazy-fetch the tab that becomes current (the initial tab fetched in the VM init); a settled swipe or
    // tab tap reveals the page and triggers its first fetch once.
    LaunchedEffect(pagerState.settledPage) {
        viewModel.onTabRevealed(tabs[pagerState.settledPage])
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(Res.string.follow_list_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack, modifier = Modifier.testTag(FOLLOW_LIST_BACK_TAG)) {
                        Text(text = stringResource(Res.string.cta_close))
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(selectedTabIndex = pagerState.currentPage) {
                FollowListTabLabel(
                    selected = pagerState.currentPage == FollowListTab.Followers.ordinal,
                    onSelect = { scope.launch { pagerState.animateScrollToPage(FollowListTab.Followers.ordinal) } },
                    label = stringResource(Res.string.follow_list_tab_followers),
                )
                FollowListTabLabel(
                    selected = pagerState.currentPage == FollowListTab.Following.ordinal,
                    onSelect = { scope.launch { pagerState.animateScrollToPage(FollowListTab.Following.ordinal) } },
                    label = stringResource(Res.string.follow_list_tab_following),
                )
            }
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize().testTag(FOLLOW_LIST_PAGER_TAG),
            ) { page ->
                val tab = tabs[page]
                FollowListTabContent(
                    tab = tab,
                    state = uiState.tab(tab),
                    isRefreshing = uiState.isRefreshing(tab),
                    onRefresh = { viewModel.onRefresh(tab) },
                    onRetry = { viewModel.onRetry(tab) },
                    onLoadMore = { viewModel.onLoadMore(tab) },
                    onRetryLoadMore = { viewModel.onRetryLoadMore(tab) },
                    onOpenProfile = onOpenProfile,
                )
            }
        }
    }
}

@Composable
private fun FollowListTabLabel(
    selected: Boolean,
    onSelect: () -> Unit,
    label: String,
) {
    Material3Tab(selected = selected, onClick = onSelect, text = { Text(text = label) })
}

/**
 * One tab's body: a `PullToRefreshBox` over the tab's state. Every non-`Content` state renders inside a
 * single-item scrollable `LazyColumn` so the pull-to-refresh gesture is recognized from it too
 * (`mobile-design-system` § "Canonical list loading and refresh pattern"); [isRefreshing] drives only the
 * refresh indicator (never the initial-load skeleton).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FollowListTabContent(
    tab: FollowListTab,
    state: FollowListTabUiState,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onRetryLoadMore: () -> Unit,
    onOpenProfile: (userId: String) -> Unit,
) {
    PullToRefreshBox(isRefreshing = isRefreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
        when (state) {
            FollowListTabUiState.Loading -> ScrollableCentered { CircularProgressIndicator() }
            FollowListTabUiState.Empty -> ScrollableCentered { EmptyText(tab) }
            FollowListTabUiState.NotFound ->
                ScrollableCentered { CenteredText(stringResource(Res.string.profile_not_found)) }
            FollowListTabUiState.Error -> ScrollableCentered { ErrorRetry(onRetry) }
            is FollowListTabUiState.Content ->
                FollowListRows(
                    content = state,
                    onLoadMore = onLoadMore,
                    onRetryLoadMore = onRetryLoadMore,
                    onOpenProfile = onOpenProfile,
                )
        }
    }
}

@Composable
private fun FollowListRows(
    content: FollowListTabUiState.Content,
    onLoadMore: () -> Unit,
    onRetryLoadMore: () -> Unit,
    onOpenProfile: (userId: String) -> Unit,
) {
    val listState = rememberLazyListState()
    // Auto load-more: when the last item nears the end and a cursor remains (and no load-more is failed),
    // request the next page. The VM guards against a duplicate in-flight fetch.
    LaunchedEffect(listState, content.canLoadMore, content.loadMoreFailed, content.users.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { lastVisible ->
                if (content.canLoadMore && !content.loadMoreFailed && lastVisible >= content.users.size - LOAD_MORE_THRESHOLD) {
                    onLoadMore()
                }
            }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        items(items = content.users, key = { it.userId }, contentType = { "followUser" }) { user ->
            FollowListRow(user = user, onClick = { onOpenProfile(user.userId) })
        }
        when {
            content.loadMoreFailed -> item { LoadMoreFailedFooter(onRetry = onRetryLoadMore) }
            content.canLoadMore -> item { LoadMoreSpinner() }
        }
    }
}

/**
 * One member row — the identity treatment reused from the profile / post card (letter avatar + display
 * name + `@username` + a Premium badge when applicable). The whole row is the tap target → the row user's
 * profile. The row `userId` (a UUID) is the navigation key only — it is NEVER rendered as a UI string.
 */
@Composable
private fun FollowListRow(
    user: FollowListUser,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LetterAvatar(displayName = user.displayName, username = user.username, modifier = Modifier.size(44.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = user.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (user.isPremium) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_premium_star),
                        contentDescription = stringResource(Res.string.profile_premium_badge_icon_description),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Text(
                text = stringResource(Res.string.post_card_handle, user.username),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A non-`Content` state rendered inside a single-item `LazyColumn` so `PullToRefreshBox` recognizes the
 *  pull gesture from it (a `PullToRefreshBox` requires a scrollable child). The single item fills the
 *  viewport and centers [content]. */
@Composable
private fun ScrollableCentered(content: @Composable () -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Box(modifier = Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                content()
            }
        }
    }
}

@Composable
private fun EmptyText(tab: FollowListTab) {
    val message =
        when (tab) {
            FollowListTab.Followers -> stringResource(Res.string.follow_list_empty_followers)
            FollowListTab.Following -> stringResource(Res.string.follow_list_empty_following)
        }
    CenteredText(message)
}

@Composable
private fun CenteredText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(24.dp),
    )
}

@Composable
private fun ErrorRetry(onRetry: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
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

/** The auto-load-more in-progress footer (a small centered spinner under the last loaded row). */
@Composable
private fun LoadMoreSpinner() {
    Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp))
    }
}

/** The load-more-failed footer: the retained rows stay above; tapping this re-issues the next-page fetch
 *  (the load-more-failure-retains-rows contract — never a full-screen error). */
@Composable
private fun LoadMoreFailedFooter(onRetry: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onRetry).padding(16.dp).testTag(FOLLOW_LIST_LOAD_MORE_RETRY_TAG),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(Res.string.follow_list_load_more_failed),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** How close to the end (in rows) auto-load-more fires. */
private const val LOAD_MORE_THRESHOLD: Int = 3
