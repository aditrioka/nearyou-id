package id.nearyou.app.screens.profile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.profile_placeholder
import org.jetbrains.compose.resources.stringResource

/**
 * The Profil bottom-nav section's documented deferred placeholder (`mobile-home-tab-host` § "The Profil
 * section renders a deferred placeholder"). Renders the `profile_placeholder` copy ("*Profil segera
 * hadir.*") and issues **NO** network fetch — it wires no profile API client / repository / flow (mirroring
 * `FollowingPlaceholderScreen`).
 *
 * The real profile/settings surface is **deferred** to a separate future change (tracked by
 * `FOLLOW_UPS.md` `mobile-profile-section-screen`, which will MODIFY this requirement to introduce the
 * live surface). This is a real, documented "coming soon" state — **not** a dead control.
 */
@Composable
fun ProfilePlaceholderScreen() {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(Res.string.profile_placeholder),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
