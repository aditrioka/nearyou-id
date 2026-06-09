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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import id.nearyou.app.notifications.NotificationsFlow
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.cta_retry
import id.nearyou.resources.generated.resources.notif_chat_message
import id.nearyou.resources.generated.resources.notif_followed
import id.nearyou.resources.generated.resources.notif_generic
import id.nearyou.resources.generated.resources.notif_post_auto_hidden
import id.nearyou.resources.generated.resources.notif_post_liked
import id.nearyou.resources.generated.resources.notif_post_replied
import id.nearyou.resources.generated.resources.notifications_empty
import id.nearyou.resources.generated.resources.notifications_loading
import id.nearyou.resources.generated.resources.notifications_mark_all_read
import id.nearyou.resources.generated.resources.notifications_title
import id.nearyou.resources.generated.resources.signin_error_network
import id.nearyou.resources.theme.locationPin
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/** Test tag on the scrollable notification list — lets the screen test target a pull-to-refresh swipe. */
const val NOTIFICATIONS_LIST_TAG: String = "notificationsList"

/** Test tag on a row's unread indicator dot — present on unread rows, absent on read rows (the
 *  read/unread visual-distinction signal the screen test asserts). */
const val NOTIFICATION_UNREAD_DOT_TAG: String = "notificationUnreadDot"

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
 * read (optimistic; `204`/`404` keep, other revert) and wires **no** navigation — deep-link tap-through is
 * deferred (follow-up issue #193, `mobile-notifications-deep-link-targets`).
 */
@Composable
fun NotificationsScreen() {
    val flow = koinInject<NotificationsFlow>()
    val viewModel = viewModel { NotificationsViewModel(flow) }
    val outcome by viewModel.outcome.collectAsState()
    val inFlight by viewModel.inFlight.collectAsState()

    NotificationsContent(
        uiState = notificationsUiState(outcome, inFlight),
        isRefreshing = inFlight,
        // Both pull-to-refresh and the error-retry control re-fetch page 1 via the VM (shared reload
        // path). `next_cursor` is retained on Loaded but NOT consumed for load-more (deferred alongside
        // mobile-nearby-timeline-infinite-scroll, extended to cover notifications).
        onRefresh = viewModel::reload,
        onRetry = viewModel::reload,
        // Row tap → optimistic mark-read (204/404 keep, other revert). NO navigation is wired (deep-link
        // tap-through deferred — the destination post/reply/profile screens do not exist yet).
        onRowTap = viewModel::markRead,
        onMarkAllRead = viewModel::markAllRead,
    )
}

/**
 * Stateless render of the notifications surface: a top bar titled `notifications_title` (with the
 * mark-all-read action shown only when there is content) over a pull-to-refresh container that shows one
 * of the four states. Separated from [NotificationsScreen] so the render is a pure function of [uiState]
 * (the screen test drives it via a fake flow).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotificationsContent(
    uiState: NotificationsUiState,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onRowTap: (String) -> Unit,
    onMarkAllRead: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(Res.string.notifications_title)) },
                actions = {
                    if (uiState is NotificationsUiState.Content) {
                        TextButton(onClick = onMarkAllRead) {
                            Text(text = stringResource(Res.string.notifications_mark_all_read))
                        }
                    }
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            when (uiState) {
                NotificationsUiState.Loading -> LoadingState()
                NotificationsUiState.Empty -> CenteredMessage(stringResource(Res.string.notifications_empty))
                NotificationsUiState.Error -> ErrorState(onRetry = onRetry)
                is NotificationsUiState.Content -> NotificationList(rows = uiState.rows, onRowTap = onRowTap)
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
            text = stringResource(Res.string.notifications_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp),
        )
        // Skeleton placeholder rows (no content) to signal a list is loading.
        repeat(3) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(56.dp),
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
private fun NotificationList(
    rows: List<NotificationRow>,
    onRowTap: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag(NOTIFICATIONS_LIST_TAG),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        items(items = rows, key = { it.id }) { row ->
            NotificationRowItem(row = row, onTap = { onRowTap(row.id) })
            HorizontalDivider()
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
        Column(modifier = Modifier.fillMaxWidth()) {
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
