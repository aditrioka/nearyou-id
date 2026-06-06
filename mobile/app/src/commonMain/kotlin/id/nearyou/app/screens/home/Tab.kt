package id.nearyou.app.screens.home

import kotlinx.serialization.Serializable

/**
 * The selected home tab (design D1 / `mobile-home-tab-host` § "Tab selection is serializable and
 * survives process death"). A `@Serializable` enum — **NOT** a `NavKey` — held by `HomeScreen` in
 * `rememberSaveable`, so the active tab survives configuration change and process death on every
 * target including Kotlin/Native (iOS), where reflection-based saving is unavailable: the
 * serializable-enum path goes through kotlinx-serialization (the same iOS-safe vehicle the
 * `NavKey` routes use), never reflection.
 *
 * Tabs are **host-internal state**, not routes: no per-tab `NavDisplay` back stack and no tab-root
 * `NavKey` is declared (per-tab back stacks are deferred to the first intra-tab destination —
 * `FOLLOW_UPS.md` `mobile-home-tab-host-per-tab-backstacks`). The authenticated default is [Nearby].
 */
@Serializable
enum class Tab {
    Nearby,
    Following,
    Global,
}
