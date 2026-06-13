package id.nearyou.app.screens.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.nearyou.app.ui.components.LetterAvatar
import id.nearyou.app.ui.components.postDateLabel
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.post_card_handle
import id.nearyou.resources.generated.resources.post_card_meta_separator
import org.jetbrains.compose.resources.stringResource

/**
 * One search result card. The shipped search wire carries NO city, distance, or like/reply engagement
 * state, so this is a LIGHTER card than the shared timeline `PostCard`: the author display identity
 * (reusing the SAME `LetterAvatar` + display-name + `@handle` treatments so they cannot drift from the
 * feed card), the post `content`, and the `created_at` date treatment (the existing [postDateLabel] ISO
 * helper — true relative formatting stays deferred to `mobile-timeline-relative-timestamp`). NO action
 * row, NO city/distance. PII discipline: the `author_id` UUID and the `rank` score are absent from
 * [SearchHit] (the projection dropped them) so they structurally cannot be rendered. The whole card is
 * the single tap target → [onOpen]; built from `NearYouTheme` tokens only.
 */
@Composable
fun SearchResultCard(
    hit: SearchHit,
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
                LetterAvatar(displayName = hit.authorDisplayName, username = hit.authorUsername)
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = hit.authorDisplayName,
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
                            text = stringResource(Res.string.post_card_handle, hit.authorUsername),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Text(
                            text = stringResource(Res.string.post_card_meta_separator),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = postDateLabel(hit.createdAt),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
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
