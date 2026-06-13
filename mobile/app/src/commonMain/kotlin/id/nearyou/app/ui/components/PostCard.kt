package id.nearyou.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.nearyou.distance.DistanceRenderer
import id.nearyou.resources.generated.resources.Res
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
import id.nearyou.resources.theme.locationPin
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** Test tag on the avatar region — lets tests verify the avatar tap is the whole-card tap. */
const val POST_CARD_AVATAR_TAG: String = "postCardAvatar"

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
 * The whole card opens the detail ([onOpen]); the two action-row affordances are the ONLY other tap
 * targets — activating them does NOT fire [onOpen]. The avatar/identity region is NOT a separate
 * target (no profile screen yet — issue #196; no dead controls). The card stays presentation-only:
 * both callbacks are hoisted; it holds no like state machine and no navigation reference. Hosts pass
 * their `testTag` via [modifier]. Built from `NearYouTheme` tokens only; renders identically under
 * light/dark.
 */
@Composable
fun PostCard(
    model: PostCardModel,
    onOpen: () -> Unit,
    onToggleLike: () -> Unit,
    onReplyShortcut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedCard(
        onClick = onOpen,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LetterAvatar(
                    displayName = model.authorDisplayName,
                    username = model.authorUsername,
                    modifier = Modifier.testTag(POST_CARD_AVATAR_TAG),
                )
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
            Text(
                text = model.content,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 12.dp),
            )
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

/** ISO-8601 `createdAt` → its date portion ("2026-05-31"). Pure + deterministic (no wall clock). */
fun postDateLabel(createdAt: String): String = createdAt.substringBefore('T')
