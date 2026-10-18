package id.nearyou.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import id.nearyou.distance.DistanceRenderer
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.ic_post_location
import id.nearyou.resources.generated.resources.post_card_handle
import id.nearyou.resources.generated.resources.post_card_meta_separator
import id.nearyou.resources.theme.locationPin
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * The post author's identity text, shared by every post card (docs/11 § 4 rule of three: [PostCard], the chat
 * shared-post card and the search result card): the bold display name (titleSmall, mockup frames 1/19) over the
 * `@handle · <post date>` meta line. A long handle ellipsizes instead of pushing the date out (`fill = false`).
 * The date is [localDateLabel]; the relative "5 mnt" form is the app-wide "Relative timestamps" item (docs/08 § Phase 3).
 */
@Composable
internal fun PostIdentityText(
    displayName: String,
    username: String,
    createdAtIso: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = displayName,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(Res.string.post_card_handle, username),
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
                text = localDateLabel(createdAtIso),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/**
 * The post location meta row, shared by [PostCard] and the chat shared-post card: the coral `locationPin` pin, the
 * city (when not blank), then the distance via `DistanceRenderer` (when [distanceM] is non-null). The whole row,
 * pin included, is omitted when there is neither — no orphan pin. Coordinates never reach it (fuzzed display only).
 */
@Composable
internal fun PostLocationMeta(
    cityName: String,
    distanceM: Double?,
    modifier: Modifier = Modifier,
    pinSize: Dp = 16.dp,
) {
    val hasCity = cityName.isNotBlank()
    if (!hasCity && distanceM == null) return
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painter = painterResource(Res.drawable.ic_post_location),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.locationPin,
            modifier = Modifier.size(pinSize).testTag(POST_CARD_PIN_TAG),
        )
        if (hasCity) MetaText(cityName)
        if (hasCity && distanceM != null) MetaText(stringResource(Res.string.post_card_meta_separator))
        if (distanceM != null) MetaText(DistanceRenderer.render(distanceM))
    }
}

@Composable
private fun MetaText(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
