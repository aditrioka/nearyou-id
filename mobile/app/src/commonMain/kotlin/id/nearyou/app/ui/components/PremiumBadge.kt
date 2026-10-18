package id.nearyou.app.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.ic_premium_star
import id.nearyou.resources.generated.resources.profile_premium_badge_icon_description
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * The Premium identity badge shown next to a display name (profile header, follow-list rows, conversation
 * rows): the `ic_premium_star` glyph in the `primary` tint, described "Akun Premium" for accessibility. One
 * shared composable so the identity treatment cannot drift between surfaces (docs/11 § 4 rule of three).
 * Hosts render it only when the user is Premium; [size] follows the adjacent name's type size.
 */
@Composable
fun PremiumBadgeIcon(
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
) {
    Icon(
        painter = painterResource(Res.drawable.ic_premium_star),
        contentDescription = stringResource(Res.string.profile_premium_badge_icon_description),
        tint = MaterialTheme.colorScheme.primary,
        modifier = modifier.size(size),
    )
}
