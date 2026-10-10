package id.nearyou.app.screens.post

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.nearyou.app.ui.components.LetterAvatar
import id.nearyou.app.ui.components.localDateLabel
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.cta_cancel
import id.nearyou.resources.generated.resources.cta_delete
import id.nearyou.resources.generated.resources.cta_retry
import id.nearyou.resources.generated.resources.ic_more_vert
import id.nearyou.resources.generated.resources.post_card_handle
import id.nearyou.resources.generated.resources.post_card_meta_separator
import id.nearyou.resources.generated.resources.post_detail_replies_empty
import id.nearyou.resources.generated.resources.post_detail_replies_header
import id.nearyou.resources.generated.resources.post_detail_reply_delete_action
import id.nearyou.resources.generated.resources.post_detail_reply_delete_body
import id.nearyou.resources.generated.resources.post_detail_reply_delete_title
import id.nearyou.resources.generated.resources.profile_actions_menu_description
import id.nearyou.resources.generated.resources.profile_block_action
import id.nearyou.resources.generated.resources.profile_report_action
import id.nearyou.resources.generated.resources.signin_error_network
import id.nearyou.resources.generated.resources.timeline_loading
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/*
 * The post-detail replies section (mockup frame 7 · "Detail postingan + balasan";
 * post-detail-vm-reply-delete-restyle): the "N balasan" subhead, the full-bleed reply list items with their
 * overflow kebab (own-reply delete / block / report), the replies loading / empty / error states, and the
 * own-reply delete confirmation. Presentation-only — every action is hoisted to PostDetailViewModel.
 */

/** The frame-7 `.subhead` — "N balasan" over the reply list, keyed off the live (VM) reply count. */
@Composable
internal fun RepliesSubhead(
    replyCount: Int,
    modifier: Modifier = Modifier,
) {
    Text(
        text = stringResource(Res.string.post_detail_replies_header, replyCount),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
    )
}

/**
 * One reply as a frame-7 list item: the leading shared [LetterAvatar], then a column of the identity line
 * (bold display name — the @handle when the name is blank — · the `created_at` date) over the content, and a
 * trailing kebab. Without a wire identity (an older-backend body) the avatar + name are omitted gracefully
 * and only the date + content render. The `author_id` UUID is NEVER rendered. The avatar and the name both
 * open the reply author's profile ([onOpenProfile]); the name line carries the stable tag. Menu actions:
 * [onDelete] (non-null iff the reply is the viewer's own), [onBlock] (non-null iff another user's reply with
 * a wire username and a resolved session), and [onReport] (every reply).
 */
@Composable
internal fun ReplyRow(
    reply: ReplyUi,
    onReport: () -> Unit,
    onBlock: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    onOpenProfile: () -> Unit,
) {
    val displayName = reply.authorDisplayName.orEmpty()
    val username = reply.authorUsername.orEmpty()
    val hasIdentity = displayName.isNotEmpty() || username.isNotEmpty()
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (hasIdentity) {
            LetterAvatar(
                displayName = displayName,
                username = username,
                // The name line is the accessible profile target; the avatar is a touch-only alias (one focus stop).
                modifier = Modifier.clip(CircleShape).clickable(onClick = onOpenProfile).clearAndSetSemantics {},
            )
        }
        Column(modifier = Modifier.weight(1f).padding(top = 2.dp)) {
            Row(
                modifier =
                    if (hasIdentity) {
                        Modifier.clickable(onClick = onOpenProfile).testTag(POST_DETAIL_REPLY_PROFILE_TAG)
                    } else {
                        Modifier
                    },
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (hasIdentity) {
                    Text(
                        text = displayName.ifEmpty { stringResource(Res.string.post_card_handle, username) },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text(
                        text = stringResource(Res.string.post_card_meta_separator),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = localDateLabel(reply.createdAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Text(
                text = reply.content,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        ReplyActionsMenu(onReport = onReport, onBlock = onBlock, onDelete = onDelete, blockUsername = username)
    }
}

/** A reply row's kebab: "Hapus balasan" first (iff [onDelete] — the viewer's own reply), then
 *  "Blokir @{username}" (iff [onBlock] — mobile-block-from-content), then "Laporkan" (every reply, ungated by
 *  authorship — mobile-content-report). Mirrors the post kebab's block-above-report order. */
@Composable
private fun ReplyActionsMenu(
    onReport: () -> Unit,
    onBlock: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    blockUsername: String,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.testTag(POST_DETAIL_REPORT_REPLY_TAG),
        ) {
            Icon(
                painter = painterResource(Res.drawable.ic_more_vert),
                contentDescription = stringResource(Res.string.profile_actions_menu_description),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (onDelete != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.post_detail_reply_delete_action)) },
                    onClick = {
                        expanded = false
                        onDelete()
                    },
                    modifier = Modifier.testTag(POST_DETAIL_DELETE_REPLY_TAG),
                )
            }
            if (onBlock != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.profile_block_action, blockUsername)) },
                    onClick = {
                        expanded = false
                        onBlock()
                    },
                    modifier = Modifier.testTag(POST_DETAIL_BLOCK_REPLY_TAG),
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.profile_report_action)) },
                onClick = {
                    expanded = false
                    onReport()
                },
            )
        }
    }
}

/** The own-reply delete confirmation: "Hapus" confirms ([onConfirm]); "Batal" / scrim / back dismiss with no
 *  request ([onDismiss]). */
@Composable
internal fun DeleteReplyDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(POST_DETAIL_DELETE_REPLY_DIALOG_TAG),
        title = { Text(text = stringResource(Res.string.post_detail_reply_delete_title)) },
        text = { Text(text = stringResource(Res.string.post_detail_reply_delete_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(Res.string.cta_delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = stringResource(Res.string.cta_cancel)) }
        },
    )
}

@Composable
internal fun RepliesLoading() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text(
            text = stringResource(Res.string.timeline_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
internal fun RepliesEmpty() {
    Text(
        text = stringResource(Res.string.post_detail_replies_empty),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
    )
}

@Composable
internal fun RepliesError(onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(Res.string.signin_error_network),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        OutlinedButton(onClick = onRetry, modifier = Modifier.padding(top = 12.dp).testTag(POST_DETAIL_REPLIES_RETRY_TAG)) {
            Text(text = stringResource(Res.string.cta_retry))
        }
    }
}
