package id.nearyou.app.screens.shell

import kotlinx.serialization.Serializable

/**
 * The selected bottom-nav **section** (design D3 / `mobile-home-tab-host` § "Bottom navigation is a
 * top-level section shell"). A `@Serializable` enum — **NOT** a `NavKey` — held by `AppShellScreen` in
 * `rememberSaveable`, so the active section survives configuration change and process death on every
 * target including Kotlin/Native (iOS), where reflection-based saving is unavailable: the
 * serializable-enum path goes through kotlinx-serialization (the same iOS-safe vehicle the `NavKey`
 * routes + the home-feed [id.nearyou.app.screens.home.Tab] use), never reflection.
 *
 * Sections are **shell-internal state**, not routes: no per-section `NavDisplay` back stack and no
 * section-root `NavKey` is declared (sections swap the shell body via `when(selectedSection)`, exactly as
 * the Home feed tabs swap via `when(selectedTab)`). The authenticated default is [Home].
 */
@Serializable
enum class Section {
    Home,
    Notifikasi,
    Profil,
}
