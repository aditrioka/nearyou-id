package id.nearyou.app.screens.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import id.nearyou.app.chat.ConversationsFlow
import id.nearyou.app.ui.components.BackNavigationIcon
import id.nearyou.app.ui.components.LetterAvatar
import id.nearyou.app.ui.components.ListCenteredMessageState
import id.nearyou.app.ui.components.ListErrorState
import id.nearyou.app.ui.components.ListLoadingState
import id.nearyou.app.ui.components.PremiumBadgeIcon
import id.nearyou.app.ui.components.localDateLabel
import id.nearyou.app.ui.components.localDaysAgo
import id.nearyou.app.ui.components.localTimeLabel
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.chat_account_deleted
import id.nearyou.resources.generated.resources.chat_date_yesterday
import id.nearyou.resources.generated.resources.chat_list_empty
import id.nearyou.resources.generated.resources.chat_list_loading
import id.nearyou.resources.generated.resources.chat_list_title
import id.nearyou.resources.generated.resources.chat_weekday_fri
import id.nearyou.resources.generated.resources.chat_weekday_mon
import id.nearyou.resources.generated.resources.chat_weekday_sat
import id.nearyou.resources.generated.resources.chat_weekday_sun
import id.nearyou.resources.generated.resources.chat_weekday_thu
import id.nearyou.resources.generated.resources.chat_weekday_tue
import id.nearyou.resources.generated.resources.chat_weekday_wed
import id.nearyou.resources.generated.resources.post_card_handle
import id.nearyou.resources.generated.resources.timeline_session_redirect
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import kotlin.time.Clock
import kotlin.time.Instant

/** Test tag on the scrollable surface (every state renders one so pull-to-refresh works from each). */
const val CONVERSATION_LIST_TAG: String = "conversationList"

/** Test tag on each conversation row — lets the screen test target the open-thread tap. */
const val CONVERSATION_ROW_TAG: String = "conversationRow"

/** Test tag on the back affordance. */
const val CONVERSATION_LIST_BACK_TAG: String = "conversationListBack"

/** Test tag on a row's trailing last-message time. */
const val CONVERSATION_ROW_TIME_TAG: String = "conversationRowTime"

/**
 * The conversation-list surface ([ConversationListRoute][id.nearyou.app.screens.routing.ConversationListRoute],
 * mockup frame 2) — opened from the Home brand app-bar "Pesan" action and overlaid on the section shell
 * via the ROOT back stack (design D3/D5). Injects [ConversationsFlow] and observes a route-scoped
 * [ConversationListViewModel] (its single `uiState` projection + the separate `isRefreshing` flag,
 * mirroring the timelines). A row
 * tap pushes [ChatThreadRoute][id.nearyou.app.screens.routing.ChatThreadRoute] via the hoisted
 * [onOpenThread] (carrying the row's PII-free partner display fields + the conversation id); the screen
 * holds NO back-stack reference (navigation-free). Every string via `stringResource`.
 */
@Composable
fun ConversationListScreen(
    onBack: () -> Unit,
    onOpenThread: (ConversationRow) -> Unit,
) {
    val flow = koinInject<ConversationsFlow>()
    val viewModel = viewModel { ConversationListViewModel(flow) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()

    ConversationListContent(
        uiState = uiState,
        isRefreshing = isRefreshing,
        onRefresh = viewModel::reload,
        onRetry = viewModel::reload,
        onBack = onBack,
        onOpenThread = onOpenThread,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationListContent(
    uiState: ConversationListUiState,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onOpenThread: (ConversationRow) -> Unit,
) {
    Scaffold(
        // Frame 2 app bar: an M3 TopAppBar (64dp, titleLarge) that applies its own status-bar inset. The list
        // overlays the shell via the root stack, so it leads with the back arrow (the frame-7 idiom).
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(Res.string.chat_list_title)) },
                navigationIcon = {
                    BackNavigationIcon(onBack = onBack, modifier = Modifier.testTag(CONVERSATION_LIST_BACK_TAG))
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            // The non-Content states are the shared list-state kit (docs/11 § 2.1), each scrollable so the
            // pull works from it.
            when (uiState) {
                ConversationListUiState.Loading ->
                    ListLoadingState(message = stringResource(Res.string.chat_list_loading), testTag = CONVERSATION_LIST_TAG)
                ConversationListUiState.Empty ->
                    ListCenteredMessageState(message = stringResource(Res.string.chat_list_empty), testTag = CONVERSATION_LIST_TAG)
                ConversationListUiState.Error -> ListErrorState(onRetry = onRetry, testTag = CONVERSATION_LIST_TAG)
                ConversationListUiState.SessionRedirect ->
                    ListCenteredMessageState(
                        message = stringResource(Res.string.timeline_session_redirect),
                        testTag = CONVERSATION_LIST_TAG,
                    )
                is ConversationListUiState.Content -> ConversationRows(uiState.rows, onOpenThread)
            }
        }
    }
}

@Composable
private fun ConversationRows(
    rows: List<ConversationRow>,
    onOpenThread: (ConversationRow) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag(CONVERSATION_LIST_TAG),
        contentPadding = PaddingValues(top = 8.dp),
    ) {
        items(items = rows, key = { it.conversationId }, contentType = { "conversation" }) { row ->
            ConversationRowItem(row = row, rowTag = CONVERSATION_ROW_TAG, onOpen = { onOpenThread(row) })
        }
    }
}

/**
 * One frame-2 two-line row (12/16 padding, 16dp gaps): the shared [LetterAvatar] (40dp), the partner display
 * name + [PremiumBadgeIcon] over the `@username`, and the trailing last-message time ([lastMessageTimeLabel]).
 * The list wire carries `last_message_at` but not the message text, so the second line is the handle; the
 * per-conversation unread count waits on #272. Also the share-to-chat picker's row ([ConversationPickerScreen]),
 * so both lists show the same identity treatment.
 */
@Composable
internal fun ConversationRowItem(
    row: ConversationRow,
    rowTag: String,
    onOpen: () -> Unit,
) {
    // The backend COALESCE-masks a shadow-banned / deleted partner to "Akun Dihapus" / akun_dihapus. The mask (or a
    // blank edge value) renders the chat_account_deleted label with no handle or badge beside it.
    val masked = isMaskedPartner(row.partnerUsername, row.partnerDisplayName)
    val name = if (masked) stringResource(Res.string.chat_account_deleted) else row.partnerDisplayName
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .testTag(rowTag)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LetterAvatar(displayName = name, username = row.partnerUsername)
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (row.partnerIsPremium && !masked) PremiumBadgeIcon()
            }
            if (!masked) {
                Text(
                    text = stringResource(Res.string.post_card_handle, row.partnerUsername),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        row.lastMessageAtIso?.let { iso ->
            Text(
                text = lastMessageTimeLabel(iso),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(CONVERSATION_ROW_TIME_TAG),
            )
        }
    }
}

/**
 * The frame-2 trailing time of a conversation's last message, relative to [now] in the device time zone: today
 * → the clock time ("09.12"), yesterday → "Kemarin", the rest of the past week → the short weekday ("Sel"),
 * anything older → the date. A value that is not a full instant renders its date portion.
 */
@Composable
internal fun lastMessageTimeLabel(
    instantIso: String,
    now: Instant = Clock.System.now(),
): String {
    val daysAgo = localDaysAgo(instantIso, now) ?: return localDateLabel(instantIso)
    return when {
        daysAgo <= 0 -> localTimeLabel(instantIso) ?: localDateLabel(instantIso)
        daysAgo == 1 -> stringResource(Res.string.chat_date_yesterday)
        daysAgo < 7 -> stringResource(weekdayShortLabel(instantIso))
        else -> localDateLabel(instantIso)
    }
}

private fun weekdayShortLabel(instantIso: String): StringResource =
    when (Instant.parse(instantIso).toLocalDateTime(TimeZone.currentSystemDefault()).dayOfWeek) {
        DayOfWeek.MONDAY -> Res.string.chat_weekday_mon
        DayOfWeek.TUESDAY -> Res.string.chat_weekday_tue
        DayOfWeek.WEDNESDAY -> Res.string.chat_weekday_wed
        DayOfWeek.THURSDAY -> Res.string.chat_weekday_thu
        DayOfWeek.FRIDAY -> Res.string.chat_weekday_fri
        DayOfWeek.SATURDAY -> Res.string.chat_weekday_sat
        DayOfWeek.SUNDAY -> Res.string.chat_weekday_sun
    }
