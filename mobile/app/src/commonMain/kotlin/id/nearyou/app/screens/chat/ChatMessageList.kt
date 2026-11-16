package id.nearyou.app.screens.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import id.nearyou.app.infra.supabaserealtime.EmbeddedPostSnapshot
import id.nearyou.app.ui.components.localTimeLabel
import id.nearyou.app.ui.components.relativeDayLabel
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.chat_message_redacted
import id.nearyou.resources.generated.resources.profile_report_action
import kotlinx.coroutines.flow.distinctUntilChanged
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock

/**
 * The frame-5 message list of [ChatThreadScreen]'s Content state: the bubbles plus a day chip heading each day, the
 * #565 tail follow and the load-older trigger. [clock] is the day chips' "today" (the screen's test seam).
 */
@Composable
internal fun ChatMessageList(
    rows: List<ChatMessageRow>,
    clock: Clock,
    onLoadOlder: () -> Unit,
    onReportMessage: (String) -> Unit,
    onOpenSharedPost: (postId: String, snapshot: EmbeddedPostSnapshot) -> Unit,
) {
    val listState = rememberLazyListState()
    // #565: follow the tail only when the NEWEST row changes (an own send, a realtime inbound, a resync). An
    // older page prepends rows but keeps the last id, so it never yanks the reader down; the id-keyed items
    // keep their position. The first positioning is an instant scroll. Keyed on the row count too, so a
    // prepend landing mid-animation restarts the follow against the CURRENT last index (the followed id is
    // recorded only once a scroll completes). Both values are saveable: a config change (the VM keeps its
    // rows) keeps the reader's place. After process death the new VM reloads only the newest page, so the
    // rows come back fewer than saved and the stale restored index is dropped for a fresh pin to the newest.
    var followedLastId by rememberSaveable { mutableStateOf<String?>(null) }
    var followedCount by rememberSaveable { mutableIntStateOf(0) }
    // Per composition (never saved): load-older waits for this composition's first positioning, so a
    // restored index 0 cannot page before the restore is reconciled.
    var positioned by remember { mutableStateOf(false) }
    val lastId = rows.lastOrNull()?.id
    LaunchedEffect(lastId, rows.size) {
        val shrank = rows.size < followedCount
        followedCount = rows.size
        if (lastId == null) return@LaunchedEffect
        // The LazyColumn's last index: one item per message plus one per day chip.
        val lastItemIndex = rows.size + rows.count { it.startsDay } - 1
        when {
            followedLastId == null || shrank -> listState.scrollToItem(lastItemIndex)
            lastId != followedLastId -> listState.animateScrollToItem(lastItemIndex)
        }
        followedLastId = lastId
        positioned = true
    }
    // Load-older when the reader reaches the top — only once the thread has been positioned (the list starts
    // at index 0, which must not page before that first scroll).
    LaunchedEffect(listState) {
        snapshotFlow { positioned && listState.firstVisibleItemIndex == 0 }
            .distinctUntilChanged()
            .collect { atTop -> if (atTop) onLoadOlder() }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().testTag(CHAT_THREAD_LIST_TAG),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        rows.forEach { row ->
            // Frame 5 day chip (the VM's ChatMessageRow.startsDay) is its OWN keyed item above the row. A chip that
            // appears when an older page of an earlier day lands is inserted above the reader's anchored row instead
            // of growing it, so the #565 prepend position stays exact across days too.
            if (row.startsDay) {
                item(key = "day-" + row.id, contentType = "dayChip") {
                    DayChip(instantIso = row.createdAtIso, clock = clock, modifier = Modifier.animateItem())
                }
            }
            // Realtime/optimistic rows animate in (mobile-design-system motion).
            item(key = row.id, contentType = "message") {
                MessageBubble(
                    row = row,
                    onReport = onReportMessage,
                    onOpenSharedPost = onOpenSharedPost,
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}

/**
 * The frame-5 day chip (`datechip`: surfaceContainerHigh, 8dp corners, 12/5 padding, 6dp above / 10dp below):
 * the [relativeDayLabel] of [instantIso] ("Hari ini", "Kemarin", or the date in the device time zone), relative to
 * [clock]'s now.
 */
@Composable
private fun DayChip(
    instantIso: String,
    clock: Clock,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth().padding(top = 6.dp, bottom = 10.dp), contentAlignment = Alignment.Center) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(8.dp)) {
            Text(
                text = relativeDayLabel(instantIso, clock.now()),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp).testTag(CHAT_THREAD_DAY_CHIP_TAG),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    row: ChatMessageRow,
    onReport: (String) -> Unit,
    onOpenSharedPost: (postId: String, snapshot: EmbeddedPostSnapshot) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val alignment = if (row.isOwn) Alignment.CenterEnd else Alignment.CenterStart
    // Frame 5: own = primaryContainer, received (and the redacted placeholder) = surfaceContainerHigh; 18dp
    // corners with a 6dp tail corner on the sender's side.
    val ownBubble = row.isOwn && !row.isRedacted
    val bubbleColor = if (ownBubble) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val onBubbleColor = if (ownBubble) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    val bubbleShape =
        if (row.isOwn) {
            RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 6.dp, bottomStart = 18.dp)
        } else {
            RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 6.dp)
        }
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = alignment) {
        Surface(
            color = bubbleColor,
            shape = bubbleShape,
            // Long-press opens the "Laporkan" menu — ONLY for a reportable (other-party, non-redacted)
            // message. The gate is `row.isReportable`; `senderId` never reaches this row (PII discipline).
            modifier =
                Modifier.widthIn(max = 300.dp).then(
                    if (row.isReportable) {
                        Modifier.combinedClickable(
                            onClick = {},
                            onLongClick = { menuExpanded = true },
                        )
                    } else {
                        Modifier
                    },
                ),
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                if (row.isRedacted) {
                    // Redaction precedence (design D9): the placeholder replaces BOTH the text and the
                    // context card. The row projection already nulled the embed fields when redacted.
                    Text(
                        text = stringResource(Res.string.chat_message_redacted),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag(CHAT_THREAD_REDACTED_TAG),
                    )
                } else {
                    // chat-embedded-posts: the shared-post context card (embed-only or content+embed), drawn
                    // ABOVE the message text as in frame 5.
                    // embeddedPostId == null + a present snapshot = the source post was hard-deleted
                    // ("post telah dihapus", not tappable). Tapping a live card navigates to its detail.
                    row.embeddedPostSnapshot?.let { snapshot ->
                        EmbeddedPostCard(
                            snapshot = snapshot,
                            isDeleted = row.embeddedPostId == null,
                            editedSinceShared = row.editedSinceShared,
                            onTap = { row.embeddedPostId?.let { id -> onOpenSharedPost(id, snapshot) } },
                        )
                    }
                    if (!row.content.isNullOrEmpty()) {
                        Text(
                            text = row.content,
                            style = MaterialTheme.typography.bodyLarge,
                            color = onBubbleColor,
                            modifier = if (row.embeddedPostSnapshot == null) Modifier else Modifier.padding(top = 8.dp),
                        )
                    }
                }
                // Frame 5 `.bt`: the send time ("09.12") under the text, right-aligned, 4dp above, at 75% of the
                // bubble's content color.
                localTimeLabel(row.createdAtIso)?.let { time ->
                    Text(
                        text = time,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Normal,
                        color = onBubbleColor.copy(alpha = 0.75f),
                        modifier = Modifier.align(Alignment.End).padding(top = 4.dp).testTag(CHAT_THREAD_MESSAGE_TIME_TAG),
                    )
                }
            }
        }
        if (row.isReportable) {
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.profile_report_action)) },
                    onClick = {
                        menuExpanded = false
                        onReport(row.id)
                    },
                    modifier = Modifier.testTag(CHAT_MESSAGE_REPORT_ITEM_TAG),
                )
            }
        }
    }
}
