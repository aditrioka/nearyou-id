package id.nearyou.app.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.ic_arrow_back
import id.nearyou.resources.generated.resources.ic_chevron_right
import id.nearyou.resources.generated.resources.settings_back_description
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * Shared chrome for the `mobile-settings` pushed-overlay surfaces (Settings, block list, consent). A
 * status-bar-inset top bar with an `arrow_back` affordance + a title — these screens own their Scaffold
 * (root-stack overlays, the `PostDetailScreen` precedent), so the bar applies its own
 * [statusBarsPadding] (2026-06-10 audit, finding 06-#4). All copy via `:shared:resources`.
 */
@Composable
fun SettingsTopBar(
    title: String,
    onBack: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                painter = painterResource(Res.drawable.ic_arrow_back),
                contentDescription = stringResource(Res.string.settings_back_description),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/** A grouped-list section header (AKUN / PREMIUM / PRIVASI / LAINNYA), per mockup frame 16. */
@Composable
fun SettingsSectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
    )
}

/**
 * One settings list row: a leading Material vector [icon], a [title] + optional [subtitle], and a
 * trailing affordance — a chevron (navigational rows), an M3 [Switch] (toggle rows), or nothing. The
 * whole row is [onClick]-tappable (the chevron/title hit area). [iconTint]/[enabled] let a deferred
 * row read as muted. No string literal is rendered — all copy arrives as already-resolved `String`s.
 */
@Composable
fun SettingsRow(
    icon: DrawableResource,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: SettingsRowTrailing = SettingsRowTrailing.Chevron,
    switchChecked: Boolean = false,
    onSwitchChange: ((Boolean) -> Unit)? = null,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // The leading icon is decorative; the row title is the label.
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        when (trailing) {
            SettingsRowTrailing.Chevron ->
                Icon(
                    painter = painterResource(Res.drawable.ic_chevron_right),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            SettingsRowTrailing.SwitchControl ->
                Switch(
                    checked = switchChecked,
                    onCheckedChange = onSwitchChange,
                )
            SettingsRowTrailing.None -> Unit
        }
    }
}

/** Which trailing affordance a [SettingsRow] renders. */
enum class SettingsRowTrailing {
    Chevron,
    SwitchControl,
    None,
}

/** A centered single-line message used by the block-list/consent loading-empty-error states. */
@Composable
fun SettingsCenteredMessage(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(24.dp),
    )
}
