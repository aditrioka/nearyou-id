package id.nearyou.app.screens.notifications

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import id.nearyou.app.notifications.NotificationsFlow
import id.nearyou.app.screens.home.PostDetailTarget
import id.nearyou.app.ui.components.ListCenteredMessageState
import id.nearyou.app.ui.components.ListErrorState
import id.nearyou.app.ui.components.ListLoadingState
import id.nearyou.app.ui.components.LoadMoreFooter
import id.nearyou.app.ui.components.LoadMoreOnScrollEnd
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.notif_chat_message
import id.nearyou.resources.generated.resources.notif_followed
import id.nearyou.resources.generated.resources.notif_generic
import id.nearyou.resources.generated.resources.notif_post_auto_hidden
import id.nearyou.resources.generated.resources.notif_post_liked
import id.nearyou.resources.generated.resources.notif_post_replied
import id.nearyou.resources.generated.resources.notifications_empty
import id.nearyou.resources.generated.resources.notifications_loading
import id.nearyou.resources.generated.resources.notifications_mark_all_read
import id.nearyou.resources.generated.resources.notifications_post_unavailable
import id.nearyou.resources.generated.resources.notifications_title
import id.nearyou.resources.generated.resources.timeline_session_redirect
import id.nearyou.resources.theme.locationPin
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/** Test tag on the scrollable notification list — lets the screen test target a pull-to-refresh swipe. */
const val NOTIFICATIONS_LIST_TAG: String = "notificationsList"

/** Test tag on a row's unread indicator dot — present on unread rows, absent on read rows (the
 *  read/unread visual-distinction signal the screen test asserts). */
const val NOTIFICATION_UNREAD_DOT_TAG: String = "notificationUnreadDot"

/** Test tag on the transient "post unavailable" affordance shown when a tapped post-target no longer
 *  resolves (`mobile-notifications-deep-link-targets`). */
const val NOTIFICATION_POST_UNAVAILABLE_TAG: String = "notificationPostUnavailable"

/** Test tag on a row's per-tap resolving spinner (shown while its deep-link by-id fetch is in flight). */
const val NOTIFICATION_RESOLVING_TAG: String = "notificationResolving"

/** How long the transient "post unavailable" affordance stays before it auto-dismisses. */
private const val POST_UNAVAILABLE_DURATION_MS: Long = 3000L

/**
 * The in-app notifications surface (`mobile-notifications-list`) — the Notifikasi bottom-nav section's
 * content. Navigation-free (it holds no back-stack reference) and has **NO back affordance**: the user
 * leaves via the bottom-nav sections, exactly as `GlobalTimelineScreen` is embedded by the Home tab host.
 * Injects [NotificationsFlow] and observes a shell-`NavEntry`-scoped [NotificationsViewModel] that holds
 * the [id.nearyou.app.notifications.NotificationsOutcome] + in-flight flag and (re)loads page 1
 * (pull-to-refresh + error-retry both re-fetch). Because the screen composes under the shell's `HomeRoute`
 * entry (design D7), the `viewModel { }` resolves to that store and the loaded inbox survives section
 * switches with no re-fetch.
 *
 * The four states (loading / content / empty / error) follow the screen-state-mapping spec, all copy via
 * `stringResource` (zero literals), under `NearYouTheme`. Rows render type-keyed GENERIC-actor copy +
 * `body_data` excerpts and NEVER the `actor_user_id`/`target_id` UUID (design D4). Tapping a row marks it
 * read (optimistic; `204`/`404` keep, other revert) AND deep-links to its target
 * (`mobile-notifications-deep-link-targets`): `post` → post detail (via the full-projection by-id fetch;
 * unavailable → the transient [notifications_post_unavailable] affordance, no nav), `followed` → profile,
 * `chat_message` → chat thread. Navigation is hoisted to [onOpenPost] / [onOpenProfile] / [onOpenChatThread]
 * (the shell wires them to root-stack pushes) and is consumed once from the VM's `pendingNavTarget` signal,
 * so it does not re-fire on recomposition. The screen itself stays navigation-free (no back-stack reference).
 */
@Composable
fun NotificationsScreen(
    onOpenPost: (PostDetailTarget) -> Unit = {},
    onOpenProfile: (userId: String) -> Unit = {},
    onOpenChatThread: (conversationId: String, partnerUsername: String, partnerDisplayName: String) -> Unit =
        { _, _, _ -> },
) {
    val flow = koinInject<NotificationsFlow>()
    val viewModel = viewModel { NotificationsViewModel(flow) }
    val outcome by viewModel.outcome.collectAsStateWithLifecycle()
    val isInitialLoad by viewModel.isInitialLoad.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val isLoadingMore by viewModel.isLoadingMore.collectAsStateWithLifecycle()
    val loadMoreError by viewModel.loadMoreError.collectAsStateWithLifecycle()
    val pendingNavTarget by viewModel.pendingNavTarget.collectAsStateWithLifecycle()
    val postUnavailable by viewModel.postUnavailable.collectAsStateWithLifecycle()
    val resolvingRowId by viewModel.resolvingRowId.collectAsStateWithLifecycle()

    // Consume the one-shot nav signal: invoke the matching hoisted callback exactly once, then clear it
    // via onNavConsumed() so it does not re-fire on recomposition (the EditPostUiState consumed-marker
    // pattern; NO Channel/SharedFlow). actor/target/conversation UUIDs are route payload only — never rendered.
    LaunchedEffect(pendingNavTarget) {
        when (val target = pendingNavTarget) {
            is NotificationNavTarget.Post -> onOpenPost(target.target)
            is NotificationNavTarget.Profile -> onOpenProfile(target.userId)
            is NotificationNavTarget.ChatThread ->
                onOpenChatThread(target.conversationId, target.partnerUsername, target.partnerDisplayName)
            null -> return@LaunchedEffect
        }
        viewModel.onNavConsumed()
    }

    // The post-unavailable affordance is transient + non-blocking: auto-dismiss after a short delay.
    LaunchedEffect(postUnavailable) {
        if (postUnavailable) {
            delay(POST_UNAVAILABLE_DURATION_MS)
            viewModel.onPostUnavailableConsumed()
        }
    }

    NotificationsContent(
        uiState = remember(outcome, isInitialLoad) { notificationsUiState(outcome, isInitialLoad) },
        isRefreshing = isRefreshing,
        // Load-more (infinite scroll): the footer flags + the scroll-end/retry callbacks. The shared
        // LoadMoreController appends pages into the retained Loaded outcome, reusing the page-1 unread filter.
        isLoadingMore = isLoadingMore,
        loadMoreError = loadMoreError,
        onLoadMore = viewModel::onLoadMore,
        onRetryLoadMore = viewModel::onRetryLoadMore,
        // Both pull-to-refresh and the error-retry control re-fetch page 1 via the VM (shared reload path).
        onRefresh = viewModel::reload,
        onRetry = viewModel::reload,
        // Row tap → optimistic mark-read (204/404 keep, other revert) + deep-link resolution (the VM sets
        // pendingNavTarget / postUnavailable, consumed above).
        onRowTap = viewModel::onRowTap,
        onMarkAllRead = viewModel::markAllRead,
        resolvingRowId = resolvingRowId,
        postUnavailable = postUnavailable,
    )
}

/**
 * Stateless render of the notifications surface: a plain title row (`notifications_title` + the
 * mark-all-read action when there is content) over a pull-to-refresh container that shows one of the
 * four states. Separated from [NotificationsScreen] so the render is a pure function of [uiState]
 * (the screen test drives it via a fake flow).
 *
 * Shell-body contract (mobile-design-system § "The app shell owns a single Scaffold"; 2026-06-10 audit,
 * finding 05-#1): this composable renders INSIDE `AppShellScreen`'s Scaffold body, so it is inset-free —
 * no own `Scaffold`, no `TopAppBar` (the previous nested Scaffold only looked right because the shell's
 * `consumeWindowInsets` happened to zero its insets). The title is an ordinary header row.
 *
 * Every non-Content state renders inside a scrollable (single-item LazyColumn, the timelines' idiom) so
 * the pull-to-refresh gesture works from Loading/Empty/Error too (finding 05-#3); the PTR indicator is
 * driven ONLY by [isRefreshing] while content stays mounted (finding 05-#2).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotificationsContent(
    uiState: NotificationsUiState,
    isRefreshing: Boolean,
    isLoadingMore: Boolean,
    loadMoreError: Boolean,
    onLoadMore: () -> Unit,
    onRetryLoadMore: () -> Unit,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onRowTap: (String) -> Unit,
    onMarkAllRead: () -> Unit,
    resolvingRowId: String? = null,
    postUnavailable: Boolean = false,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.notifications_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            if (uiState is NotificationsUiState.Content) {
                TextButton(onClick = onMarkAllRead) {
                    Text(text = stringResource(Res.string.notifications_mark_all_read))
                }
            }
        }
        // Transient non-blocking affordance: a tapped post-target that no longer resolves (404). It does
        // NOT replace content or block the list; it auto-dismisses (the screen's LaunchedEffect).
        if (postUnavailable) {
            Text(
                text = stringResource(Res.string.notifications_post_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .testTag(NOTIFICATION_POST_UNAVAILABLE_TAG),
            )
        }
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            when (uiState) {
                NotificationsUiState.Loading ->
                    ListLoadingState(
                        message = stringResource(Res.string.notifications_loading),
                        testTag = NOTIFICATIONS_LIST_TAG,
                    )
                NotificationsUiState.Empty ->
                    ListCenteredMessageState(
                        message = stringResource(Res.string.notifications_empty),
                        testTag = NOTIFICATIONS_LIST_TAG,
                    )
                NotificationsUiState.Error -> ListErrorState(onRetry = onRetry, testTag = NOTIFICATIONS_LIST_TAG)
                // Terminal 401 → neutral redirect placeholder (no retry, not the connectivity
                // copy) — the SessionInvalidator re-route is already in flight (06-#3).
                NotificationsUiState.SessionRedirect ->
                    ListCenteredMessageState(
                        message = stringResource(Res.string.timeline_session_redirect),
                        testTag = NOTIFICATIONS_LIST_TAG,
                    )
                is NotificationsUiState.Content ->
                    NotificationList(
                        rows = uiState.rows,
                        isLoadingMore = isLoadingMore,
                        loadMoreError = loadMoreError,
                        onLoadMore = onLoadMore,
                        onRetryLoadMore = onRetryLoadMore,
                        onRowTap = onRowTap,
                        resolvingRowId = resolvingRowId,
                    )
            }
        }
    }
}

@Composable
private fun NotificationList(
    rows: List<NotificationRow>,
    isLoadingMore: Boolean,
    loadMoreError: Boolean,
    onLoadMore: () -> Unit,
    onRetryLoadMore: () -> Unit,
    onRowTap: (String) -> Unit,
    resolvingRowId: String?,
) {
    val listState = rememberLazyListState()
    LoadMoreOnScrollEnd(listState = listState, onLoadMore = onLoadMore)
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().testTag(NOTIFICATIONS_LIST_TAG),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        items(items = rows, key = { it.id }, contentType = { "notification" }) { row ->
            NotificationRowItem(
                row = row,
                isResolving = row.id == resolvingRowId,
                onTap = { onRowTap(row.id) },
            )
            HorizontalDivider()
        }
        // Load-more footer: spinner while a page loads, non-destructive retry on error, nothing at end
        // (mobile-design-system § "Canonical list load-more pattern"). The scroll-end detector above
        // drives onLoadMore; the LoadMoreController's guards make an eager trigger on a short list a no-op.
        item(key = "loadMoreFooter", contentType = "footer") {
            LoadMoreFooter(
                isLoadingMore = isLoadingMore,
                loadMoreError = loadMoreError,
                onRetry = onRetryLoadMore,
            )
        }
    }
}

/**
 * One notification row: an unread dot (present only when unread — the read/unread visual distinction),
 * the type-keyed generic-actor copy, and the optional `body_data` excerpt. Tapping the row invokes
 * [onTap] (mark-read). Renders NO `actor_user_id`/`target_id` UUID (they are not on [NotificationRow]).
 * Tolerates any `type` (unknown → generic fallback) and a missing excerpt (base copy only) without crash.
 */
@Composable
private fun NotificationRowItem(
    row: NotificationRow,
    isResolving: Boolean,
    onTap: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onTap)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Unread indicator: a brand-coral dot present only on unread rows (absent once read). Decorative
        // (the read/unread state is conveyed by the dot's presence) → no contentDescription literal.
        if (!row.read) {
            Box(
                modifier =
                    Modifier
                        .size(8.dp)
                        .testTag(NOTIFICATION_UNREAD_DOT_TAG)
                        .background(MaterialTheme.colorScheme.locationPin, CircleShape),
            )
        } else {
            // Keep the copy left-aligned with unread rows by reserving the dot's width.
            Box(modifier = Modifier.size(8.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = notificationCopy(row.type),
                style = MaterialTheme.typography.bodyLarge,
                color = if (row.read) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            if (row.excerpt != null) {
                Text(
                    text = row.excerpt,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        // Per-tap resolving spinner: shown on the tapped row while its deep-link by-id fetch is in flight.
        if (isResolving) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                modifier = Modifier.size(16.dp).testTag(NOTIFICATION_RESOLVING_TAG),
            )
        }
    }
}

/**
 * Maps a notification [type] wire string to its Bahasa Indonesia copy. The five distinct types
 * (`post_liked` / `post_replied` / `followed` / `post_auto_hidden` / `chat_message`) get specific copy;
 * every other reserved `NotificationType` value AND any unknown/future `type` falls back to the generic
 * copy (no crash). These are wire-protocol keys matched against `row.type`, NOT rendered UI literals —
 * the rendered text is always a `stringResource`.
 */
@Composable
private fun notificationCopy(type: String): String =
    when (type) {
        "post_liked" -> stringResource(Res.string.notif_post_liked)
        "post_replied" -> stringResource(Res.string.notif_post_replied)
        "followed" -> stringResource(Res.string.notif_followed)
        "post_auto_hidden" -> stringResource(Res.string.notif_post_auto_hidden)
        "chat_message" -> stringResource(Res.string.notif_chat_message)
        else -> stringResource(Res.string.notif_generic)
    }
