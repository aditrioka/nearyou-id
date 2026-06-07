package id.nearyou.app.screens.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import id.nearyou.app.notifications.NotificationsFlow
import id.nearyou.app.screens.home.HomeScreen
import id.nearyou.app.screens.notifications.NotificationsScreen
import id.nearyou.app.screens.profile.ProfilePlaceholderScreen
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.notifications_badge
import id.nearyou.resources.generated.resources.section_home
import id.nearyou.resources.generated.resources.section_home_icon_description
import id.nearyou.resources.generated.resources.section_notifications
import id.nearyou.resources.generated.resources.section_notifications_icon_description
import id.nearyou.resources.generated.resources.section_profile
import id.nearyou.resources.generated.resources.section_profile_icon_description
import id.nearyou.resources.theme.locationPin
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/**
 * The authenticated app **section shell** (`mobile-home-tab-host` § "Bottom navigation is a top-level
 * section shell"). A Material 3 `Scaffold` whose `bottomBar` is a `NavigationBar` of three top-level
 * sections — **Home / Notifikasi / Profil** — over a body that renders the selected section's content via
 * `when(selectedSection)` (design D3): Home → [HomeScreen] (the feed tab host + composer FAB), Notifikasi
 * → [NotificationsScreen], Profil → [ProfilePlaceholderScreen].
 *
 * This is the [HomeRoute][id.nearyou.app.screens.routing.HomeRoute] entry (mapped by `appEntryProvider`):
 * its body composes **directly** under the `HomeRoute` `NavEntry` (no intermediate `NavDisplay`), so every
 * `viewModel { }` inside — the Home feeds' VMs AND the notifications VM — resolves to the `HomeRoute` store
 * and survives **section switches** without re-fetch (design D3/D7), exactly as the feed tabs survive tab
 * switches. `selectedSection` is a `@Serializable` [Section] in `rememberSaveable` (iOS-safe; default
 * [Section.Home]). There is **no** per-section `NavDisplay` and **no** section-root `NavKey`.
 *
 * The composer FAB stays inside [HomeScreen] (the Home section), so it shows on Home only — never on the
 * Notifikasi / Profil sections. [onOpenComposer] is forwarded to [HomeScreen] (wired by `appEntryProvider`
 * to a root-stack `PostCreationRoute` push, above the shell, so the composer overlays the bottom bar).
 *
 * The Notifikasi nav item carries an unread **badge** (design D6) sourced from
 * `GET /api/v1/notifications/unread-count` — fetched **once** on shell composition and refreshed **once**
 * when the user leaves the Notifikasi section (having likely read some). It is shown only when `count > 0`.
 * NO polling timer / push-driven live subscription is wired (live updates deferred — `FOLLOW_UPS.md`
 * `mobile-notifications-live-unread-badge`).
 */
@Composable
fun AppShellScreen(onOpenComposer: () -> Unit) {
    val flow = koinInject<NotificationsFlow>()
    var selectedSection by rememberSaveable { mutableStateOf(Section.Home) }

    val scope = rememberCoroutineScope()
    var unreadCount by remember { mutableStateOf(0L) }
    // One-shot unread-count fetch on shell composition (design D6). A failed/absent count → 0 (no badge).
    LaunchedEffect(Unit) { unreadCount = flow.unreadCount() ?: 0L }

    Scaffold(
        bottomBar = {
            NavigationBar {
                SectionItem(
                    selected = selectedSection == Section.Home,
                    onSelect = { selectedSection = Section.Home },
                    label = stringResource(Res.string.section_home),
                    iconDescription = stringResource(Res.string.section_home_icon_description),
                    badgeContentDescription = null,
                )
                SectionItem(
                    selected = selectedSection == Section.Notifikasi,
                    onSelect = { selectedSection = Section.Notifikasi },
                    label = stringResource(Res.string.section_notifications),
                    iconDescription = stringResource(Res.string.section_notifications_icon_description),
                    // Badge shown only when there are unread notifications (count > 0).
                    badgeContentDescription =
                        if (unreadCount > 0) stringResource(Res.string.notifications_badge) else null,
                )
                SectionItem(
                    selected = selectedSection == Section.Profil,
                    onSelect = { selectedSection = Section.Profil },
                    label = stringResource(Res.string.section_profile),
                    iconDescription = stringResource(Res.string.section_profile_icon_description),
                    badgeContentDescription = null,
                )
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (selectedSection) {
                // The Home section's content (HomeScreen) composes directly under the HomeRoute NavEntry
                // (this shell IS that entry), so the feed viewModel { }s stay HomeRoute-scoped (design D3).
                Section.Home -> HomeScreen(onOpenComposer = onOpenComposer)
                Section.Notifikasi -> {
                    NotificationsScreen()
                    // Refresh the badge once when leaving Notifikasi (the user likely read some). One-shot,
                    // not a live subscription — onDispose fires when the section body leaves composition.
                    DisposableEffect(Unit) {
                        onDispose { scope.launch { unreadCount = flow.unreadCount() ?: 0L } }
                    }
                }
                Section.Profil -> ProfilePlaceholderScreen()
            }
        }
    }
}

/**
 * One bottom-nav section destination. The icon is a brand-tinted dot (coral [locationPin] when selected,
 * muted otherwise) — there is no material-icons dependency on the classpath, matching the post-card +
 * home-tab affordance idiom — carrying its `contentDescription` via `stringResource`. When
 * [badgeContentDescription] is non-null an unread [Badge] is overlaid (its own `contentDescription` via
 * `stringResource`). A `RowScope` extension because `NavigationBarItem` is one.
 */
@Composable
private fun RowScope.SectionItem(
    selected: Boolean,
    onSelect: () -> Unit,
    label: String,
    iconDescription: String,
    badgeContentDescription: String?,
) {
    NavigationBarItem(
        selected = selected,
        onClick = onSelect,
        icon = {
            val dot: @Composable () -> Unit = {
                Box(
                    modifier =
                        Modifier
                            .size(24.dp)
                            .semantics { contentDescription = iconDescription }
                            .background(
                                color =
                                    if (selected) {
                                        MaterialTheme.colorScheme.locationPin
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                shape = CircleShape,
                            ),
                )
            }
            if (badgeContentDescription != null) {
                BadgedBox(
                    badge = { Badge(modifier = Modifier.semantics { contentDescription = badgeContentDescription }) },
                ) {
                    dot()
                }
            } else {
                dot()
            }
        },
        label = { Text(text = label) },
    )
}
