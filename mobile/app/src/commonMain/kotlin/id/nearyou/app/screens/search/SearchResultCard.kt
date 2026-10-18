package id.nearyou.app.screens.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import id.nearyou.app.ui.components.LetterAvatar
import id.nearyou.app.ui.components.PostIdentityText
import id.nearyou.app.ui.components.localDateLabel

/**
 * One search result card. The shipped search wire carries NO city, distance, or like/reply engagement
 * state, so this is a LIGHTER card than the shared timeline `PostCard`: the author display identity
 * (the SAME [LetterAvatar] + [PostIdentityText] the feed card renders, so they cannot drift), the post
 * `content`, and the `created_at` date treatment (the shared [localDateLabel] date
 * helper — true relative formatting stays deferred to `mobile-timeline-relative-timestamp`). NO action
 * row, NO city/distance. PII discipline: the `author_id` UUID and the `rank` score are absent from
 * [SearchHit] (the projection dropped them) so they structurally cannot be rendered. The whole card is
 * the single tap target → [onOpen]; built from `NearYouTheme` tokens only. While the tap's by-id read is
 * in flight ([isResolving], #255) a small trailing spinner marks the card — an action affordance on the
 * tapped card (the `NotificationsScreen` row idiom), not a list loading indicator.
 */
@Composable
fun SearchResultCard(
    hit: SearchHit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    isResolving: Boolean = false,
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
                LetterAvatar(displayName = hit.authorDisplayName, username = hit.authorUsername)
                PostIdentityText(
                    displayName = hit.authorDisplayName,
                    username = hit.authorUsername,
                    createdAtIso = hit.createdAt,
                    modifier = Modifier.weight(1f),
                )
                if (isResolving) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp).testTag(SEARCH_RESULT_RESOLVING_TAG),
                    )
                }
            }
            Text(
                text = hit.content,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}
