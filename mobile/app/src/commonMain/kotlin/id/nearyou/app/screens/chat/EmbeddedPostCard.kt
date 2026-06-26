package id.nearyou.app.screens.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import id.nearyou.app.infra.supabaserealtime.EmbeddedPostSnapshot
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.chat_shared_post_deleted
import id.nearyou.resources.generated.resources.chat_shared_post_edited_banner
import id.nearyou.resources.generated.resources.chat_shared_post_label
import org.jetbrains.compose.resources.stringResource

/** Test tag on the shared-post context card (a live, tappable card). */
const val EMBEDDED_POST_CARD_TAG: String = "embeddedPostCard"

/** Test tag on the hard-deleted-source "post telah dihapus" state (NOT tappable). */
const val EMBEDDED_POST_DELETED_TAG: String = "embeddedPostDeleted"

/** Test tag on the "diedit sejak dibagikan" banner. */
const val EMBEDDED_POST_EDITED_BANNER_TAG: String = "embeddedPostEditedBanner"

/**
 * The in-thread shared-post **context card** (`mobile-chat-embedded-posts`). Rendered from the
 * immutable [snapshot] alone — never a live re-fetch (design D1). It shows the author handle +
 * display name, the post content, and the `cityName` label — NEVER a coordinate (the snapshot
 * carries none; the spatial-fuzzing invariant holds by construction).
 *
 * Two non-default states:
 *  - [isDeleted] (the source post was hard-deleted → `embeddedPostId` is null but the snapshot
 *    survives): renders the "post telah dihapus" state and is NOT tappable ([onTap] is not wired).
 *  - [editedSinceShared] (the live post moved past the share-time anchor): shows the
 *    "diedit sejak dibagikan" banner. The card otherwise still renders the snapshot.
 *
 * Tapping a live (non-deleted) card invokes [onTap] (the thread navigates to the live post-detail,
 * where the tapping viewer's own visibility rules apply). All chrome strings come from
 * `Res.string.*` (the no-hardcoded-UI-strings invariant).
 */
@Composable
fun EmbeddedPostCard(
    snapshot: EmbeddedPostSnapshot,
    isDeleted: Boolean,
    editedSinceShared: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cardModifier =
        modifier
            .fillMaxWidth()
            .testTag(EMBEDDED_POST_CARD_TAG)
            .then(if (isDeleted) Modifier else Modifier.clickable(onClick = onTap))
    OutlinedCard(modifier = cardModifier) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                text = stringResource(Res.string.chat_shared_post_label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (isDeleted) {
                Text(
                    text = stringResource(Res.string.chat_shared_post_deleted),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp).testTag(EMBEDDED_POST_DELETED_TAG),
                )
            } else {
                Text(
                    text = snapshot.authorDisplayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    text = "@${snapshot.authorUsername}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = snapshot.content,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (snapshot.cityName.isNotBlank()) {
                    Text(
                        text = snapshot.cityName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                if (editedSinceShared) {
                    Text(
                        text = stringResource(Res.string.chat_shared_post_edited_banner),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 6.dp).testTag(EMBEDDED_POST_EDITED_BANNER_TAG),
                    )
                }
            }
        }
    }
}
