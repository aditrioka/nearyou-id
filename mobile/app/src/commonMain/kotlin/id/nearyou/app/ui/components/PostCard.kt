package id.nearyou.app.ui.components

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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
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
import id.nearyou.distance.DistanceRenderer
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.ic_more_vert
import id.nearyou.resources.generated.resources.ic_post_like
import id.nearyou.resources.generated.resources.ic_post_like_filled
import id.nearyou.resources.generated.resources.ic_post_location
import id.nearyou.resources.generated.resources.ic_post_reply
import id.nearyou.resources.generated.resources.post_card_action_like
import id.nearyou.resources.generated.resources.post_card_action_reply
import id.nearyou.resources.generated.resources.post_card_handle
import id.nearyou.resources.generated.resources.post_card_like_state_liked
import id.nearyou.resources.generated.resources.post_card_like_state_not_liked
import id.nearyou.resources.generated.resources.post_card_meta_separator
import id.nearyou.resources.generated.resources.post_image_alt
import id.nearyou.resources.generated.resources.profile_actions_menu_description
import id.nearyou.resources.generated.resources.profile_report_action
import id.nearyou.resources.theme.locationPin
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** Test tag on the avatar region. */
const val POST_CARD_AVATAR_TAG: String = "postCardAvatar"

/** Test tag on the identity header (avatar + name + handle) — the author-profile tap target
 *  (`mobile-profile`). Tapping it fires `onOpenProfile`, NOT the whole-card `onOpen`. */
const val POST_CARD_IDENTITY_TAG: String = "postCardIdentity"

/** Test tag on the location pin — lets tests assert the meta row is omitted when empty. */
const val POST_CARD_PIN_TAG: String = "postCardPin"

/** State-keyed test tags on the like icon — assert the filled/outlined treatment switch (the glyph
 *  itself stays decorative; the interactive affordance node carries the accessible state). */
const val POST_CARD_LIKE_FILLED_TAG: String = "postCardLikeFilled"
const val POST_CARD_LIKE_OUTLINED_TAG: String = "postCardLikeOutlined"

/** Test tag on the like affordance (the interactive toggle target on the action row). */
const val POST_CARD_LIKE_ACTION_TAG: String = "postCardLikeAction"

/** Test tag on the reply affordance (icon + count — the reply-shortcut target on the action row). */
const val POST_CARD_REPLY_ACTION_TAG: String = "postCardReplyAction"

/** Test tag on the attached-image node (`image-attached-posts`) — present only when `imageUrl != null`,
 *  so a test can assert the image renders when supplied and is absent when null. */
const val POST_CARD_IMAGE_TAG: String = "postCardImage"

/** Test tag on the overflow kebab (`timeline-card-report-kebab`) — present only when `onReport != null`,
 *  so a test can assert the kebab renders with a report action and is absent without one. */
const val POST_CARD_KEBAB_TAG: String = "postCardKebab"

/** Test tag on the kebab menu's "Laporkan" item (`timeline-card-report-kebab`). */
const val POST_CARD_REPORT_ITEM_TAG: String = "postCardReportItem"

/**
 * The display-only model the shared post card renders (`mobile-post-card` capability). Carries
 * ONLY display fields: deliberately NO author UUID and NO raw `latitude`/`longitude`, so the card
 * STRUCTURALLY cannot leak them. [distanceM] is nullable — Nearby passes the server-computed
 * meters (rendered via the shared `DistanceRenderer`), Global passes `null` (no spatial filter,
 * no distance rendered).
 */
data class PostCardModel(
    val id: String,
    val authorUsername: String,
    val authorDisplayName: String,
    val content: String,
    val cityName: String,
    val distanceM: Double?,
    val createdAt: String,
    val likedByViewer: Boolean,
    val replyCount: Int,
    // image-attached-posts: the public, coordinate-independent delivery URL of the post's attached image
    // (null = text-only post). NOT PII (the image path carries no location). When non-null the card renders
    // a Coil AsyncImage below the content; when null the card is byte-identical to the pre-image baseline.
    val imageUrl: String? = null,
)

/**
 * The ONE shared timeline post card (docs/11 § 2.1 `ui/components/` reuse-first; absorbs the
 * post-card half of audit 05-#11). Layout per the canonical mockup frames 1 (light) + 19 (dark):
 *
 * - Identity header: [LetterAvatar] + display name (single-line, ellipsized) + the
 *   `post_card_handle` @-handle (single-line, ellipsized) + the middot separator + the time label
 *   as plain TEXT — the clock glyph is gone (mobile-design-system § Material-icons delta; the
 *   relative "5 mnt" treatment stays deferred to `mobile-timeline-relative-timestamp`, so the
 *   value is still [postDateLabel]).
 * - Content (bodyLarge).
 * - Location meta row: coral `locationPin` pin + `city_name` (when non-empty) + the distance via
 *   `DistanceRenderer.render` (when [PostCardModel.distanceM] is non-null). The whole row
 *   (including the pin) is omitted when there is no city AND no distance — no orphan pin.
 * - The **action row** (`mobile-inline-post-actions`, frame 1): a reply affordance (icon +
 *   `reply_count` as ONE tappable unit → [onReplyShortcut]) and a like affordance (icon only —
 *   filled + `locationPin` when liked, outlined + muted otherwise → [onToggleLike]). Real controls:
 *   ripple, ≥48dp touch targets, `stringResource` contentDescriptions, and the liked state announced
 *   via `stateDescription` (never visual-only). NO numeric like count (the timeline wire carries
 *   none — the known frame-1 divergence) and NO send affordance (kirim pesan is the deferred chat
 *   hook — `mobile-post-card` § "Send-message card action is deferred", issue #238).
 *
 * The whole card opens the detail ([onOpen]); the identity header (avatar + name + handle) opens the
 * author's profile ([onOpenProfile], `mobile-profile`); the two action-row affordances — plus the
 * optional overflow kebab (timeline-card-report-kebab: rendered iff [onReport] is non-null, a
 * `DropdownMenu` with the single "Laporkan" item; hosts supply the action only for non-authored feed
 * posts) — are the other tap targets; activating any of these does NOT fire [onOpen]. The card stays
 * presentation-only: all
 * callbacks are hoisted; it holds no like state machine, no navigation reference, and no author UUID (the
 * host binds the profile target id by closure). Hosts pass their `testTag` via [modifier]. Built from
 * `NearYouTheme` tokens only; renders identically under light/dark.
 */
@Composable
fun PostCard(
    model: PostCardModel,
    onOpen: () -> Unit,
    onToggleLike: () -> Unit,
    onReplyShortcut: () -> Unit,
    onOpenProfile: () -> Unit,
    modifier: Modifier = Modifier,
    onReport: (() -> Unit)? = null,
) {
    OutlinedCard(
        onClick = onOpen,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            // The header row: the identity tap target (weight 1f) + the optional trailing overflow kebab
            // (timeline-card-report-kebab — mockup frame 1 `.post .head .more`). The kebab sits OUTSIDE
            // the identity clickable so activating it fires neither onOpenProfile nor the card onOpen.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The identity header (avatar + name + handle) is a separate tap target → the author's
                // profile (mobile-profile). The inner clickable consumes the tap, so onOpenProfile fires
                // and the whole-card onOpen does NOT. The card still holds no author UUID — the host binds
                // the target id by closure.
                Row(
                    modifier =
                        Modifier
                            .weight(1f)
                            .clip(MaterialTheme.shapes.small)
                            .clickable(onClick = onOpenProfile)
                            .testTag(POST_CARD_IDENTITY_TAG)
                            .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LetterAvatar(
                        displayName = model.authorDisplayName,
                        username = model.authorUsername,
                        modifier = Modifier.testTag(POST_CARD_AVATAR_TAG),
                    )
                    IdentityText(model)
                }
                // Rendered ONLY when the host supplies a report action (non-authored feed posts) — a
                // kebab with zero eligible items would be a dead control (no icon node, no placeholder).
                if (onReport != null) {
                    PostCardKebab(onReport = onReport)
                }
            }
            Text(
                text = model.content,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 12.dp),
            )
            // image-attached-posts: the optional attached image, BELOW the content + ABOVE the location
            // meta row. Rendered ONLY when imageUrl is non-null (text-only posts stay byte-identical to
            // the pre-image baseline). Coil 3 AsyncImage loads on on-screen render (no scroll preload —
            // docs/02 §6 delivery rules) and fails gracefully to nothing (no error chrome). The accessible
            // alt text is resource-backed (post_image_alt) per the CMP-Resources-only invariant.
            if (model.imageUrl != null) {
                AsyncImage(
                    model = model.imageUrl,
                    contentDescription = stringResource(Res.string.post_image_alt),
                    contentScale = ContentScale.FillWidth,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .testTag(POST_CARD_IMAGE_TAG),
                )
            }
            if (model.cityName.isNotEmpty() || model.distanceM != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_post_location),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.locationPin,
                        modifier = Modifier.size(16.dp).testTag(POST_CARD_PIN_TAG),
                    )
                    if (model.cityName.isNotEmpty()) {
                        Text(
                            text = model.cityName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (model.cityName.isNotEmpty() && model.distanceM != null) {
                        Text(
                            text = stringResource(Res.string.post_card_meta_separator),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (model.distanceM != null) {
                        Text(
                            text = DistanceRenderer.render(model.distanceM),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            // The action row per mockup frame 1 (mobile-inline-post-actions): reply affordance FIRST
            // (icon + count, ONE tappable unit), then the like affordance. The mockup's middle `send`
            // action is deliberately ABSENT (chat isn't built — the deferred requirement is the chat
            // change's MODIFY hook, issue #238) and NO numeric like count is rendered (the timeline
            // wire carries none). 20dp glyphs are dp intent; the touch targets are ≥48dp (M3 minimum).
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The count is folded INTO the contentDescription ("Balas, N balasan") so TalkBack
                // announces it — an explicit contentDescription on the clickable otherwise replaces the
                // merged child count text in the spoken label (the visible count Text stays for sighted
                // users). minimumInteractiveComponentSize sits AFTER clickable so the ripple fills the
                // ≥48dp target, not just the 20dp glyph.
                val replyDescription = stringResource(Res.string.post_card_action_reply, model.replyCount)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .testTag(POST_CARD_REPLY_ACTION_TAG)
                            .clip(MaterialTheme.shapes.small)
                            .clickable(onClick = onReplyShortcut)
                            .minimumInteractiveComponentSize()
                            .semantics { contentDescription = replyDescription }
                            .padding(horizontal = 8.dp),
                ) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_post_reply),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = model.replyCount.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Like affordance: filled brand-tinted heart when liked, muted outlined otherwise. The
                // toggled state is announced via stateDescription (not visual-only); the glyph itself
                // stays decorative (the affordance node carries the accessible label + state).
                val likeDescription = stringResource(Res.string.post_card_action_like)
                val likeState =
                    stringResource(
                        if (model.likedByViewer) {
                            Res.string.post_card_like_state_liked
                        } else {
                            Res.string.post_card_like_state_not_liked
                        },
                    )
                Box(
                    contentAlignment = Alignment.Center,
                    modifier =
                        Modifier
                            .testTag(POST_CARD_LIKE_ACTION_TAG)
                            // shapes.small (not a circle clip): the design-system guard pins the
                            // no-CircleShape-dot rule on this file; the rounded ripple matches the
                            // reply affordance's treatment anyway. minimumInteractiveComponentSize sits
                            // AFTER clickable so the ripple fills the ≥48dp target, not just the glyph.
                            .clip(MaterialTheme.shapes.small)
                            .clickable(onClick = onToggleLike)
                            .minimumInteractiveComponentSize()
                            .semantics {
                                contentDescription = likeDescription
                                stateDescription = likeState
                            },
                ) {
                    Icon(
                        painter =
                            painterResource(
                                if (model.likedByViewer) Res.drawable.ic_post_like_filled else Res.drawable.ic_post_like,
                            ),
                        contentDescription = null,
                        tint =
                            if (model.likedByViewer) {
                                MaterialTheme.colorScheme.locationPin
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        modifier =
                            Modifier.size(20.dp).testTag(
                                if (model.likedByViewer) POST_CARD_LIKE_FILLED_TAG else POST_CARD_LIKE_OUTLINED_TAG,
                            ),
                    )
                }
            }
        }
    }
}

/** The identity header's text block: bold display name over the @handle · date meta line. */
@Composable
private fun IdentityText(model: PostCardModel) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = model.authorDisplayName,
            // Mockup frames 1/19: the display name is BOLD (700) — titleSmall's M3
            // default weight (500) reads too light next to the handle line.
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.post_card_handle, model.authorUsername),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // fill = false: a long handle ellipsizes instead of pushing the
                // separator + time label out of the header (maximal-length scenario).
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                text = stringResource(Res.string.post_card_meta_separator),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                // The post date (ISO date portion); relative formatting is the
                // deferred mobile-timeline-relative-timestamp change.
                text = postDateLabel(model.createdAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** The overflow kebab trailing the identity header (timeline-card-report-kebab — mockup frame 1
 *  `.post .head .more`: 20dp `more_vert` glyph, muted `onSurfaceVariant`; the M3 [IconButton] owns the
 *  ≥48dp touch target). Its menu carries the single "Laporkan" item invoking [onReport]; the mockup's
 *  always-present kebab renders here only when a report action applies (see the spec's null-gated
 *  divergence note). */
@Composable
private fun PostCardKebab(onReport: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.testTag(POST_CARD_KEBAB_TAG),
        ) {
            Icon(
                painter = painterResource(Res.drawable.ic_more_vert),
                contentDescription = stringResource(Res.string.profile_actions_menu_description),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.profile_report_action)) },
                onClick = {
                    expanded = false
                    onReport()
                },
                modifier = Modifier.testTag(POST_CARD_REPORT_ITEM_TAG),
            )
        }
    }
}

/** ISO-8601 `createdAt` → its date portion ("2026-05-31"). Pure + deterministic (no wall clock). */
fun postDateLabel(createdAt: String): String = createdAt.substringBefore('T')
