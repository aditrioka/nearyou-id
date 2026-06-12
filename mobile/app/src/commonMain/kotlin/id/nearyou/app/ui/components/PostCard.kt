package id.nearyou.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.nearyou.distance.DistanceRenderer
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.ic_post_like
import id.nearyou.resources.generated.resources.ic_post_like_filled
import id.nearyou.resources.generated.resources.ic_post_location
import id.nearyou.resources.generated.resources.ic_post_reply
import id.nearyou.resources.generated.resources.post_card_handle
import id.nearyou.resources.generated.resources.post_card_meta_separator
import id.nearyou.resources.theme.locationPin
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** Test tag on the avatar region — lets tests verify the avatar tap is the whole-card tap. */
const val POST_CARD_AVATAR_TAG: String = "postCardAvatar"

/** Test tag on the location pin — lets tests assert the meta row is omitted when empty. */
const val POST_CARD_PIN_TAG: String = "postCardPin"

/** State-keyed test tags on the like icon — the filled/outlined variant is otherwise opaque to
 *  semantics (the icon is decorative, `contentDescription = null`). */
const val POST_CARD_LIKE_FILLED_TAG: String = "postCardLikeFilled"
const val POST_CARD_LIKE_OUTLINED_TAG: String = "postCardLikeOutlined"

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
 * - Read-only counts row: the like-state icon (filled + accent when liked) + reply icon +
 *   `reply_count`. NOT interactive — no onClick, no button semantics; the inline action row is
 *   deferred (issue #201), and no numeric like count exists on the timeline wire.
 *
 * The whole card is the single tap target ([onOpen]); the avatar/identity region is NOT a separate
 * target (no profile screen yet — issue #196; no dead controls). Hosts pass their `testTag` via
 * [modifier]. Built from `NearYouTheme` tokens only; renders identically under light/dark.
 */
@Composable
fun PostCard(
    model: PostCardModel,
    onOpen: () -> Unit,
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
            // Read-only counts row per mockup frames 1/19: reply group FIRST (icon + count),
            // then the like-state heart, 24dp between groups, 20dp glyphs (the mockup's send
            // affordance belongs to the deferred inline-actions change, issue #201).
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
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
                // Like-state affordance: filled brand-tinted heart when the viewer liked it, else a
                // muted outlined heart. Decorative within a read-only row → no contentDescription.
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

/** ISO-8601 `createdAt` → its date portion ("2026-05-31"). Pure + deterministic (no wall clock). */
fun postDateLabel(createdAt: String): String = createdAt.substringBefore('T')
