## Why

The mobile app's bottom navigation **is** the three feeds (Nearby / Following / Global) — there is no room for Notifications, Profile, or any other top-level surface, so they get crammed into top-bar icons or pushed routes. That doesn't scale to a real social app shell. At the same time, the shipped `/api/v1/notifications` read API is unused on mobile, leaving the core engagement loop (who liked / replied to / followed you) invisible. This change restructures the bottom navigation into top-level **sections** — with the three feeds becoming a top tab row inside a Home section — and delivers the **Notifications section** (the in-app notifications list) as the first new section, with its unread badge on the bottom-nav item (matching `docs/03-UX-Design.md` § In-App Notification List, "unread badge count in the tab bar").

## What Changes

**Bottom-nav restructure (MODIFIED `mobile-home-tab-host`):**
- The bottom `NavigationBar` becomes top-level **sections**: **Home / Notifikasi / Profil** (replacing the current Nearby/Following/Global bottom destinations).
- The **Nearby / Following / Global feeds move to a top tab row** (Material 3 `PrimaryTabRow`) **inside the Home section** — preserving the shipped per-feed `HomeRoute`-scoped no-re-fetch behavior.
- The default section is **Home**; within Home the default feed tab is **Nearby**. Both the selected section AND the selected feed-tab are serializable (iOS-safe `rememberSaveable`).
- The composer FAB stays on the Home section (still pushes `PostCreationRoute` onto the root back stack). The **Following** feed tab and the new **Profil** section render deferred placeholders (no fetch), mirroring the established placeholder pattern.
- The restructure is designed to **absorb** the in-flight `mobile-post-detail-screen` (#159) behavior: the feed top-tabs still hoist `onOpenPost(...)` → root-stack `PostDetailRoute` push (no per-tab `NavDisplay` back stacks introduced). Merge coordination with #159 is required (both touch `HomeScreen`/`AppEntryProvider` + this spec).

**Notifications section (NEW `mobile-notifications-list`):**
- The **Notifikasi** bottom-nav section hosts a `NotificationsScreen` consuming the shipped read API, mirroring the proven `GlobalTimeline` seam: `NotificationsApiClient` + `NotificationsRepository` (behind a `NotificationsFlow` interface) + a NavEntry-scoped `NotificationsViewModel` + a Compose-free `NotificationsUiState` projection + the screen. Koin-wired, copy via `:shared:resources`, under `NearYouTheme`.
- DTOs target the **SHIPPED** `NotificationRoutes.kt` wire (opaque base64url `next_cursor`, non-null `body_data`, `{count}`, `{marked_read}`, `204`/`404 not_found`, `unread=` param) — NOT the stale `in-app-notifications` spec — guarded by a negative-regression test.
- An **unread badge** on the Notifikasi bottom-nav item from `GET /api/v1/notifications/unread-count`, refreshed on shell composition + on leaving the section.
- Rows render type-keyed Bahasa Indonesia copy; tap → mark read (`PATCH …/:id/read`); "Tandai semua dibaca" → `…/read-all`; pull-to-refresh; states loading / content / empty / error.

**Deferred (explicit positive + negative-guard requirements + `FOLLOW_UPS.md`):**
- **Deep-link tap-through** — rows mark-read on tap but don't navigate to the target post/reply/profile. Blocked on BOTH the in-flight `mobile-post-detail` screen (#159) AND a backend `GET /api/v1/posts/{id}` by-id endpoint (which #159 explicitly assigns to "the future notifications change") — neither exists, so deep-link stays deferred.
- **Actor-username rendering** — list DTO returns only `actor_user_id` (UUID); v1 renders generic-actor copy ("Seseorang …"), never the UUID; username enrichment needs a backend `visible_users` join.
- **Infinite scroll** — `next_cursor` parsed/retained, load-more deferred.
- **Live/polling unread badge** updates beyond the one-shot fetch.
- **Profil section** is a placeholder only (the real profile/settings surface is a separate future change).

Pure mobile change — **NO backend change, NO Flyway migration, NO new library pin.**

## Capabilities

### New Capabilities
- `mobile-notifications-list`: the mobile in-app notifications surface — `NotificationsScreen` (loading / content / empty / error + mark-read + mark-all-read + pull-to-refresh), its `ApiClient`/`Repository`/`Flow`/`ViewModel`/`UiState` seam over the shipped `/api/v1/notifications` read API with shipped-wire DTOs — with deep-link tap-through, actor-username rendering, infinite scroll, and live badge updates explicitly deferred.

### Modified Capabilities
- `mobile-home-tab-host`: restructured from "the Nearby/Following/Global bottom-bar tab host" into the **app bottom-nav section shell** — bottom `NavigationBar` of top-level sections (Home / Notifikasi / Profil); the three feeds become a **top tab row inside the Home section**; section selection + feed-tab selection both serializable; default section Home (default feed Nearby); the Notifikasi section hosts the new `NotificationsScreen` with an unread badge on its nav item; the Profil section is a deferred placeholder. (Capability name retained — renaming a shipped capability spec dir is out of scope; the spec body documents the broadened "app shell" role.)

## Impact

- **`:mobile:app` (additive + restructure):** new `screens/shell/` (the section `NavigationBar` shell) + `screens/home/HomeScreen.kt` reworked to host the feed `PrimaryTabRow` (Home-section content); new `notifications/` package (`NotificationsApiClient`/`Repository`/`Flow`/DTOs) + `screens/notifications/` (`NotificationsScreen`/`ViewModel`/`UiState`); a `screens/profile/ProfilePlaceholderScreen.kt`; `screens/routing/NavKeys.kt` + `AppEntryProvider.kt` (the shell becomes the authenticated root entry); Koin wiring in `MobileModule.kt`; new Bahasa Indonesia strings in `:shared:resources` (`SharedStringsCatalogTest` count bumped).
- **Coordination with #159 (`mobile-post-detail-screen`):** both modify `HomeScreen`/`AppEntryProvider` + the `mobile-home-tab-host` spec. #159 is additive (hoists `onOpenPost`, root-stack push, no per-tab back stacks) → absorbable by this restructure; the second to squash-merge rebases + reconciles per `openspec/project.md` § "Archive commits touching shared specs". Sequencing flagged in `tasks.md` + `design.md`.
- **Backend:** none — consumes the shipped `/api/v1/notifications` read API. No migration, no new endpoint, no new library pin.
- **Tests:** Robolectric `NotificationsScreenTest` + a shell/host test (sections + feed top-tabs + Notifikasi badge) on the Release-variant `*ScreenTest` exclude; commonTest `NotificationsUiStateTest` + `NotificationsApiClient`/`Repository` MockEngine tests (shipped-wire parse incl. opaque-cursor + negative-regression); iOS flow test under `src/iosTest` (K/N-legal fn names).
- **Follow-ups created:** `mobile-notifications-deep-link-targets` (blocked on #159 + backend `GET /posts/{id}`), `mobile-notifications-actor-username-enrichment`, `in-app-notifications-spec-wire-reconciliation` (bucket b), `mobile-profile-section-screen` (real Profil surface), `mobile-notifications-live-unread-badge`; infinite-scroll folded into the existing `mobile-nearby-timeline-infinite-scroll` entry.
