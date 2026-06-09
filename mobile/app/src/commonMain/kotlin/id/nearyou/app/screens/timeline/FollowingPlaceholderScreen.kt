package id.nearyou.app.screens.timeline

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
import id.nearyou.resources.generated.resources.timeline_following_placeholder
import org.jetbrains.compose.resources.stringResource

/**
 * The Following tab's documented empty-state placeholder (design D6 / `mobile-home-tab-host` §
 * "Following tab renders the deferred placeholder and issues no fetch"). Renders the
 * `timeline_following_placeholder` copy ("*Kamu belum mengikuti siapa pun. Lihat Nearby atau Global
 * dulu.*", aligned with `docs/03-UX-Design.md` § Empty State) and issues **NO** network fetch — it
 * wires no Following timeline API client / repository / flow.
 *
 * The real Following feed is **deferred**: there is no follow-action UI on mobile yet, so a live feed
 * would be perpetually empty (wasted surface + an untestable end-to-end path). The deferral is tracked
 * per `docs/08-Roadmap-Risk.md` § Phase 3, which will MODIFY this requirement to
 * introduce the live feed. This is a real, documented state directing the user to Nearby/Global —
 * **not** a dead control.
 */
@Composable
fun FollowingPlaceholderScreen() {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(Res.string.timeline_following_placeholder),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
