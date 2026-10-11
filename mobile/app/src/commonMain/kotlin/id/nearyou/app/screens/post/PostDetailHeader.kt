package id.nearyou.app.screens.post

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import id.nearyou.app.ui.components.LetterAvatar
import id.nearyou.app.ui.components.localDateLabel
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.chat_share_to_chat_action
import id.nearyou.resources.generated.resources.cta_back
import id.nearyou.resources.generated.resources.cta_edit_post
import id.nearyou.resources.generated.resources.ic_arrow_back
import id.nearyou.resources.generated.resources.ic_more_vert
import id.nearyou.resources.generated.resources.ic_post_like
import id.nearyou.resources.generated.resources.ic_post_like_filled
import id.nearyou.resources.generated.resources.ic_post_location
import id.nearyou.resources.generated.resources.ic_post_reply
import id.nearyou.resources.generated.resources.ic_send
import id.nearyou.resources.generated.resources.post_card_action_like
import id.nearyou.resources.generated.resources.post_card_handle
import id.nearyou.resources.generated.resources.post_card_like_state_liked
import id.nearyou.resources.generated.resources.post_card_like_state_not_liked
import id.nearyou.resources.generated.resources.post_detail_like_count
import id.nearyou.resources.generated.resources.post_detail_posted_from
import id.nearyou.resources.generated.resources.post_detail_posted_from_no_city
import id.nearyou.resources.generated.resources.post_detail_title
import id.nearyou.resources.generated.resources.post_edit_edited_label
import id.nearyou.resources.generated.resources.post_image_alt
import id.nearyou.resources.generated.resources.profile_actions_menu_description
import id.nearyou.resources.generated.resources.profile_block_action
import id.nearyou.resources.generated.resources.profile_report_action
import id.nearyou.resources.theme.locationPin
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/*
 * The post-detail chrome ABOVE the replies (mockup frame 7 · "Detail postingan + balasan";
 * post-detail-vm-reply-delete-restyle): the top app bar + its post kebab, the post header, and the action
 * row. Presentation-only — every callback is hoisted to PostDetailScreen / PostDetailViewModel.
 */

/** The frame-7 top app bar: a back arrow (the hoisted [onBack] — the screen holds no back-stack reference),
 *  the "Postingan" title, then the Edit affordance (own post within the 30-min window — mobile-post-editing)
 *  and the post kebab. An M3 [TopAppBar] applies its own status-bar inset (this root overlay owns its
 *  Scaffold — the 06-#4 rationale). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PostDetailTopBar(
    onBack: () -> Unit,
    editEligible: Boolean,
    onEdit: () -> Unit,
    reportEligible: Boolean,
    onReportPost: () -> Unit,
    onBlockPost: (() -> Unit)?,
    blockUsername: String,
    onShareToChat: () -> Unit,
) {
    TopAppBar(
        title = { Text(text = stringResource(Res.string.post_detail_title)) },
        navigationIcon = {
            IconButton(onClick = onBack, modifier = Modifier.testTag(POST_DETAIL_BACK_TAG)) {
                Icon(
                    painter = painterResource(Res.drawable.ic_arrow_back),
                    contentDescription = stringResource(Res.string.cta_back),
                )
            }
        },
        actions = {
            if (editEligible) {
                TextButton(onClick = onEdit, modifier = Modifier.testTag(POST_DETAIL_EDIT_TAG)) {
                    Text(text = stringResource(Res.string.cta_edit_post))
                }
            }
            PostActionsMenu(
                onShareToChat = onShareToChat,
                reportEligible = reportEligible,
                onReportPost = onReportPost,
                onBlockPost = onBlockPost,
                blockUsername = blockUsername,
            )
        },
    )
}

/** The post kebab — "Blokir @{username}" (iff [onBlockPost] is non-null: a non-authored post whose freshness
 *  read resolved an `authorUserId` and whose payload carries a username — mobile-block-from-content), then
 *  "Bagikan ke chat" (always — chat-embedded-posts; also the action row's share icon), then "Laporkan"
 *  (non-authored post — mobile-content-report). Always present, so own posts keep their share entry point. */
@Composable
private fun PostActionsMenu(
    onShareToChat: () -> Unit,
    reportEligible: Boolean,
    onReportPost: () -> Unit,
    onBlockPost: (() -> Unit)?,
    blockUsername: String,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.testTag(POST_DETAIL_REPORT_POST_TAG),
        ) {
            Icon(
                painter = painterResource(Res.drawable.ic_more_vert),
                contentDescription = stringResource(Res.string.profile_actions_menu_description),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (onBlockPost != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.profile_block_action, blockUsername)) },
                    onClick = {
                        expanded = false
                        onBlockPost()
                    },
                    modifier = Modifier.testTag(POST_DETAIL_BLOCK_POST_TAG),
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.chat_share_to_chat_action)) },
                onClick = {
                    expanded = false
                    onShareToChat()
                },
                modifier = Modifier.testTag(POST_DETAIL_SHARE_TO_CHAT_TAG),
            )
            if (reportEligible) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.profile_report_action)) },
                    onClick = {
                        expanded = false
                        onReportPost()
                    },
                )
            }
        }
    }
}

/**
 * The post header (frame 7 `.post`): the author identity row — the shared [LetterAvatar] + bold display name
 * over the @handle (the `mobile-post-card` treatments, so they cannot drift; omitted gracefully for an empty
 * legacy payload). Frame 7's meta line also shows a distance, but post-detail stays distance-free
 * (`hide-distance` § "Scope is Nearby only" — spec over mockup). Then the content, the optional attached image, the pin-led "Diposting dari
 * {city}, {date}" line (empty `cityName` → the no-city variant), and the "Diedit" label (opens the history
 * overlay). Built solely from the route payload + the freshness read — no `author_id`, no coordinate. The
 * identity row is a tap target iff [onOpenProfile] is non-null (the freshness read resolved the author).
 */
@Composable
internal fun PostHeader(
    content: String,
    cityName: String,
    createdAtIso: String,
    editedAtIso: String?,
    onEditedLabelClick: () -> Unit,
    authorUsername: String,
    authorDisplayName: String,
    imageUrl: String?,
    onOpenProfile: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
        if (authorUsername.isNotEmpty() || authorDisplayName.isNotEmpty()) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .then(
                            if (onOpenProfile != null) {
                                Modifier
                                    .clip(MaterialTheme.shapes.small)
                                    .clickable(onClick = onOpenProfile)
                                    .testTag(POST_DETAIL_HEADER_PROFILE_TAG)
                            } else {
                                Modifier
                            },
                        ),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LetterAvatar(displayName = authorDisplayName, username = authorUsername)
                Column {
                    if (authorDisplayName.isNotEmpty()) {
                        Text(
                            text = authorDisplayName,
                            // Bold per the shared card's identity treatment (cannot drift).
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (authorUsername.isNotEmpty()) {
                        Text(
                            text = stringResource(Res.string.post_card_handle, authorUsername),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        Text(
            text = content,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 10.dp),
        )
        // image-attached-posts: the attached image BELOW the content (the shared card's Coil pattern), only for
        // a non-null imageUrl from the tapped card; loads on render and fails gracefully to nothing.
        if (imageUrl != null) {
            AsyncImage(
                model = imageUrl,
                contentDescription = stringResource(Res.string.post_image_alt),
                contentScale = ContentScale.FillWidth,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .testTag(POST_DETAIL_IMAGE_TAG),
            )
        }
        val postedFrom =
            if (cityName.isEmpty()) {
                stringResource(Res.string.post_detail_posted_from_no_city, localDateLabel(createdAtIso))
            } else {
                stringResource(Res.string.post_detail_posted_from, cityName, localDateLabel(createdAtIso))
            }
        // Frame 7's coral `.loc` meta line; the spec'd copy is a place sentence, so the glyph is the pin.
        Row(
            modifier = Modifier.padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(Res.drawable.ic_post_location),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.locationPin,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = postedFrom,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // mobile-post-editing: "Diedit [tanggal]" iff the freshness read reports an edit; opens the history.
        if (editedAtIso != null) {
            Text(
                text = stringResource(Res.string.post_edit_edited_label, localDateLabel(editedAtIso)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier =
                    Modifier
                        .padding(top = 4.dp)
                        .clickable(onClick = onEditedLabelClick)
                        .testTag(POST_DETAIL_EDITED_LABEL_TAG),
            )
        }
    }
}

/**
 * The frame-7 action row, between two dividers: the read-only reply indicator (icon + live count), the
 * share-to-chat affordance ([onShare] — the kebab's "Bagikan ke chat" action), and the like affordance — a
 * `locationPin` filled heart when liked / a muted outlined one otherwise, plus the BARE count when available
 * (announced as "N suka" via its contentDescription). Mirrors the shared card's a11y: the like target carries
 * the action label + a `stateDescription`; every target is ≥48dp. A like banner (post-gone / network) renders
 * below.
 */
@Composable
internal fun PostActionRow(
    liked: Boolean,
    likeCount: Long?,
    replyCount: Int,
    likeInFlight: Boolean,
    banner: PostDetailBanner?,
    onToggleLike: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(horizontal = 16.dp).padding(top = 12.dp)) {
        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.minimumInteractiveComponentSize().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(Res.drawable.ic_post_reply),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = replyCount.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(POST_DETAIL_REPLY_COUNT_TAG),
                )
            }
            IconButton(onClick = onShare, modifier = Modifier.testTag(POST_DETAIL_SHARE_ACTION_TAG)) {
                Icon(
                    painter = painterResource(Res.drawable.ic_send),
                    contentDescription = stringResource(Res.string.chat_share_to_chat_action),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            LikeAffordance(liked = liked, likeCount = likeCount, likeInFlight = likeInFlight, onToggleLike = onToggleLike)
        }
        HorizontalDivider()
        if (banner != null) {
            BannerText(banner = banner, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun LikeAffordance(
    liked: Boolean,
    likeCount: Long?,
    likeInFlight: Boolean,
    onToggleLike: () -> Unit,
) {
    val tint = if (liked) MaterialTheme.colorScheme.locationPin else MaterialTheme.colorScheme.onSurfaceVariant
    val likeDescription = stringResource(Res.string.post_card_action_like)
    val likeState =
        stringResource(if (liked) Res.string.post_card_like_state_liked else Res.string.post_card_like_state_not_liked)
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .testTag(POST_DETAIL_LIKE_TOGGLE_TAG)
                .clip(MaterialTheme.shapes.small)
                .clickable(enabled = !likeInFlight, onClick = onToggleLike)
                .minimumInteractiveComponentSize()
                .semantics {
                    contentDescription = likeDescription
                    stateDescription = likeState
                }.padding(horizontal = 8.dp),
    ) {
        Icon(
            painter = painterResource(if (liked) Res.drawable.ic_post_like_filled else Res.drawable.ic_post_like),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp).testTag(if (liked) POST_DETAIL_LIKE_LIKED_TAG else POST_DETAIL_LIKE_NOT_LIKED_TAG),
        )
        if (likeCount != null) {
            val countDescription = stringResource(Res.string.post_detail_like_count, likeCount)
            Text(
                text = likeCount.toString(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = tint,
                modifier = Modifier.testTag(POST_DETAIL_LIKE_COUNT_TAG).semantics { contentDescription = countDescription },
            )
        }
    }
}
