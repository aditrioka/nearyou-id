package id.nearyou.app.screens.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemColors
import androidx.compose.material3.NavigationBarItemDefaults
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
import id.nearyou.app.notifications.NotificationsFlow
import id.nearyou.app.screens.home.HomeScreen
import id.nearyou.app.screens.home.PostDetailTarget
import id.nearyou.app.screens.notifications.NotificationsScreen
import id.nearyou.app.screens.profile.ProfilePlaceholderScreen
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.ic_nav_home
import id.nearyou.resources.generated.resources.ic_nav_home_filled
import id.nearyou.resources.generated.resources.ic_nav_notifications
import id.nearyou.resources.generated.resources.ic_nav_notifications_filled
import id.nearyou.resources.generated.resources.ic_nav_profile
import id.nearyou.resources.generated.resources.ic_nav_profile_filled
import id.nearyou.resources.generated.resources.notifications_badge
import id.nearyou.resources.generated.resources.section_home
import id.nearyou.resources.generated.resources.section_home_icon_description
import id.nearyou.resources.generated.resources.section_notifications
import id.nearyou.resources.generated.resources.section_notifications_icon_description
import id.nearyou.resources.generated.resources.section_profile
import id.nearyou.resources.generated.resources.section_profile_icon_description
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/**
 * The authenticated app **section shell** (`mobile-home-tab-host` § "Bottom navigation is a top-level
 * section shell"). A Material 3 `Scaffold` whose `bottomBar` is a `NavigationBar` of three top-level
 * sections — **Home / Notifikasi / Profil** — over a body that renders the selected section's content via
 * `when(selectedSection)` (design D3): Home → [HomeScreen] (the feed tab host + composer FAB), Notifikasi
 * → [NotificationsScreen], Profil → [ProfilePlaceholderScreen].
 *
 * This shell `Scaffold` is the **single inset-owning `Scaffold`** for the authenticated surface
 * (mobile-design-system § "The app shell owns a single Scaffold and window insets" / design D1): the
 * Android entry calls `enableEdgeToEdge()`, the shell `Scaffold` resolves the system-bar insets ONCE
 * (its default `contentWindowInsets` + the bottom `NavigationBar`), and the body applies
 * `Modifier.consumeWindowInsets(innerPadding)` so the section/feed/timeline composables below — which
 * declare NO `Scaffold`/`TopAppBar` of their own — do not re-apply the inset (the fix for the
 * nested-Scaffold status-bar gap). The bottom-nav icons are real Material glyphs (bundled vector
 * drawables, outlined↔filled on selection — D4/D10), NOT brand-tinted dots, and use
 * `NavigationBarItemDefaults.colors()` so the selected label stays visible (D5).
 *
 * This is the [HomeRoute][id.nearyou.app.screens.routing.HomeRoute] entry (mapped by `appEntryProvider`):
 * its body composes **directly** under the `HomeRoute` `NavEntry` (no intermediate `NavDisplay`), so every
 * `viewModel { }` inside — the Home feeds' VMs AND the notifications VM — resolves to the `HomeRoute` store
 * and survives **section switches** without re-fetch (design D3/D7), exactly as the feed tabs survive tab
 * switches. `selectedSection` is a `@Serializable` [Section] in `rememberSaveable` (iOS-safe; default
 * [Section.Home]). There is **no** per-section `NavDisplay` and **no** section-root `NavKey`.
 *
 * The composer FAB stays inside [HomeScreen] (the Home section), so it shows on Home only — never on the
 * Notifikasi / Profil sections. [onOpenComposer] and [onOpenPost] are forwarded to [HomeScreen] (wired by
 * `appEntryProvider` to root-stack `PostCreationRoute` / `PostDetailRoute` pushes, above the shell, so they
 * overlay the bottom bar). [onOpenPost] absorbs #159's feed-card → post-detail tap through the shell
 * (design D9): the Home feeds hoist it up via [HomeScreen], which the call site turns into a
 * `PostDetailRoute` push — the Notifikasi / Profil sections wire no `onOpenPost`.
 *
 * The Notifikasi nav item carries an unread **badge** (design D6) sourced from
 * `GET /api/v1/notifications/unread-count` — fetched **once** on shell composition and refreshed **once**
 * when the user leaves the Notifikasi section (having likely read some). It is shown only when `count > 0`.
 * NO polling timer / push-driven live subscription is wired (live updates deferred — `FOLLOW_UPS.md`
 * `mobile-notifications-live-unread-badge`).
 */
@Composable
fun AppShellScreen(
    onOpenComposer: () -> Unit,
    onOpenPost: (PostDetailTarget) -> Unit = {},
) {
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
                    iconOutlined = Res.drawable.ic_nav_home,
                    iconFilled = Res.drawable.ic_nav_home_filled,
                    iconDescription = stringResource(Res.string.section_home_icon_description),
                    badgeContentDescription = null,
                )
                SectionItem(
                    selected = selectedSection == Section.Notifikasi,
                    onSelect = { selectedSection = Section.Notifikasi },
                    label = stringResource(Res.string.section_notifications),
                    iconOutlined = Res.drawable.ic_nav_notifications,
                    iconFilled = Res.drawable.ic_nav_notifications_filled,
                    iconDescription = stringResource(Res.string.section_notifications_icon_description),
                    // Badge shown only when there are unread notifications (count > 0).
                    badgeContentDescription =
                        if (unreadCount > 0) stringResource(Res.string.notifications_badge) else null,
                )
                SectionItem(
                    selected = selectedSection == Section.Profil,
                    onSelect = { selectedSection = Section.Profil },
                    label = stringResource(Res.string.section_profile),
                    iconOutlined = Res.drawable.ic_nav_profile,
                    iconFilled = Res.drawable.ic_nav_profile_filled,
                    iconDescription = stringResource(Res.string.section_profile_icon_description),
                    badgeContentDescription = null,
                )
            }
        },
    ) { padding ->
        // The shell Scaffold applied the system-bar insets once (via `padding`); consume them so the
        // section/feed/timeline content below does NOT re-apply them (design D1 — the nested-Scaffold fix).
        Box(modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            when (selectedSection) {
                // The Home section's content (HomeScreen) composes directly under the HomeRoute NavEntry
                // (this shell IS that entry), so the feed viewModel { }s stay HomeRoute-scoped (design D3).
                // onOpenPost is forwarded so a Home feed-card tap pushes PostDetailRoute (#159, design D9).
                Section.Home -> HomeScreen(onOpenComposer = onOpenComposer, onOpenPost = onOpenPost)
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
 * The bottom-nav item colors for the section shell. The bare `NavigationBarItemDefaults.colors()` is
 * NOT used because, as of Material 3 1.4.0, its default **`selectedTextColor` = `secondary`** and
 * **`indicatorColor` = `secondaryContainer`** — and this brand theme deliberately makes those two
 * tokens NEUTRAL near-white (`secondary = #EEF0F4`, `secondaryContainer = #F5F6F8`; see
 * `NearYouColorScheme`), so the bare default renders the selected label near-white-on-white (invisible)
 * and the indicator pill vanishes. Per the M3 docs ("if you want the old look, set
 * `selectedTextColor = onSurface`") we apply readable, brand-aligned tokens via the official
 * `NavigationBarItemDefaults.colors(...)` API (NOT a custom composition): a light-cobalt indicator pill
 * (`primaryContainer`) behind a dark-cobalt selected icon (`onPrimaryContainer`), a near-black selected
 * label (`onSurface`), and `onSurfaceVariant` for the unselected state. This satisfies
 * `mobile-design-system` § "Navigation and tab labels are visible" (D5) — verified by a contrast
 * assertion in `AppShellScreenTest`, not just an inequality check.
 */
@Composable
fun nearYouNavigationBarItemColors(): NavigationBarItemColors =
    NavigationBarItemDefaults.colors(
        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
        selectedTextColor = MaterialTheme.colorScheme.onSurface,
        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )

/**
 * One bottom-nav section destination. The icon is a real Material glyph (bundled vector drawable,
 * outlined when unselected, filled when selected — the M3 selected/unselected convention) carrying its
 * `contentDescription` via `stringResource`. Item colors come from [nearYouNavigationBarItemColors] so
 * the selected label stays visible (design D5 — fixes the invisible-selected-label bug). When
 * [badgeContentDescription] is non-null an unread [Badge] is overlaid (its own `contentDescription` via
 * `stringResource`). A `RowScope` extension because `NavigationBarItem` is one.
 */
@Composable
private fun RowScope.SectionItem(
    selected: Boolean,
    onSelect: () -> Unit,
    label: String,
    iconOutlined: DrawableResource,
    iconFilled: DrawableResource,
    iconDescription: String,
    badgeContentDescription: String?,
) {
    NavigationBarItem(
        selected = selected,
        onClick = onSelect,
        colors = nearYouNavigationBarItemColors(),
        icon = {
            val icon: @Composable () -> Unit = {
                Icon(
                    painter = painterResource(if (selected) iconFilled else iconOutlined),
                    contentDescription = iconDescription,
                )
            }
            if (badgeContentDescription != null) {
                BadgedBox(
                    badge = { Badge(modifier = Modifier.semantics { contentDescription = badgeContentDescription }) },
                ) {
                    icon()
                }
            } else {
                icon()
            }
        },
        label = { Text(text = label) },
    )
}
