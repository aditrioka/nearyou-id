## Why

The mobile app can sign in, pass the age gate, grant location, browse the Nearby/Global feeds, and create a post — but it has **no way to see who liked, replied to, or followed you**. The backend in-app-notifications read API (`GET /api/v1/notifications`, `…/unread-count`, `PATCH …/:id/read`, `…/read-all`) has shipped and is unused. A notifications surface is the core engagement loop and the next high-impact mobile screen now that the timeline feeds are in place.

## What Changes

- Add an **in-app notifications list screen** to `:mobile:app` consuming the shipped `/api/v1/notifications` read API, mirroring the proven `GlobalTimeline`/`NearbyTimeline` seam: `NotificationsApiClient` + `NotificationsRepository` (behind a `NotificationsFlow` interface) + a NavEntry-scoped `NotificationsViewModel` + a Compose-free `NotificationsUiState` projection + `NotificationsScreen`, Koin-wired, copy via `:shared:resources`, under `NearYouTheme`.
- Add a **notifications entry-point** (bell affordance) at the `HomeScreen` top-bar level that pushes a new `NotificationsRoute` onto the **root** back stack (mirroring the composer FAB → `PostCreationRoute`), with a **minimal unread badge** from `GET /api/v1/notifications/unread-count`.
- Render rows from the **shipped wire DTOs** (`NotificationRoutes.kt`), NOT the stale `in-app-notifications` spec JSON examples — including the **opaque base64url `next_cursor`** and the `{count}` / `{marked_read}` response shapes.
- Map the notification `type` values to localized Bahasa Indonesia copy; tap a row → mark read (`PATCH …/:id/read`); "Tandai semua dibaca" → `PATCH …/read-all`; pull-to-refresh re-fetches page 1.
- **Deferred (explicit positive + negative-guard requirements + `FOLLOW_UPS.md`):**
  - **Deep-link tap-through** — rows mark-read on tap but do NOT navigate to the target post/reply/profile (those destination screens don't exist yet; post-detail is a separate in-flight change, profile unbuilt).
  - **Actor-username rendering** — the list endpoint returns only `actor_user_id` (a UUID), so v1 renders generic-actor copy ("Seseorang …") + `body_data` excerpts and NEVER the raw UUID; username-enriched copy needs a backend list-endpoint actor-username join (mirroring FCM's `ActorUsernameLookup`).
  - **Infinite scroll** — `next_cursor` is parsed/retained but load-more is not wired (tracked alongside the existing `mobile-nearby-timeline-infinite-scroll` follow-up).
  - **Live/polling unread badge** — the badge is one-shot (fetch on Home entry + refresh on return); live updates are out of scope.
- Pure mobile change — **NO backend change, NO Flyway migration, NO new library pin.**

## Capabilities

### New Capabilities
- `mobile-notifications-list`: the mobile in-app notifications surface — the `NotificationsScreen` feed (list / loading / empty / error states + mark-read + mark-all-read + pull-to-refresh), its `ApiClient`/`Repository`/`Flow`/`ViewModel`/`UiState` seam over the shipped `/api/v1/notifications` read API with shipped-wire DTOs, the `HomeScreen` bell entry-point pushing `NotificationsRoute`, and the one-shot unread badge — with deep-link tap-through, actor-username rendering, infinite scroll, and live badge updates explicitly deferred.

### Modified Capabilities
<!-- None. The HomeScreen bell entry-point is additive and does not contradict any mobile-home-tab-host requirement (which mandates the bottom NavigationBar + FAB + tab content but not the absence of a top bar); keeping the entry-point requirement inside the new capability avoids a competing delta on mobile-home-tab-host, which the in-flight mobile-post-detail-screen session also modifies (per-tab back stacks) — minimizing archive-time spec conflict per openspec/project.md § "Archive commits touching shared specs". The in-app-notifications spec's divergence from its own shipped NotificationRoutes.kt is a pre-existing staleness logged to FOLLOW_UPS.md (bucket b), not modified by this mobile change. -->

## Impact

- **New code** (`:mobile:app`, all additive): `timeline`-sibling package `notifications/` (`NotificationsApiClient`, `NotificationsRepository`, `NotificationsFlow`, DTOs) + `screens/notifications/` (`NotificationsScreen`, `NotificationsViewModel`, `NotificationsUiState`) + a `NotificationsRoute` `NavKey` + the `HomeScreen` bell entry-point + Koin wiring in `MobileModule.kt` + Bahasa Indonesia strings in `:shared:resources`.
- **Touches** `HomeScreen.kt` (adds the bell/top-bar affordance) — small overlap with the in-flight `mobile-post-detail-screen` session (makes posts tappable); different regions, trivial rebase.
- **Consumes** the shipped `/api/v1/notifications` read API — no backend/route/schema change.
- **Tests**: Robolectric `NotificationsScreenTest` (Release-variant `*ScreenTest` exclude), commonTest `NotificationsUiStateTest` + `NotificationsApiClient`/`Repository` MockEngine tests (shipped-wire parsing incl. opaque-cursor round-trip + status→outcome mapping), iOS flow test under `src/iosTest` (K/N-legal fn names).
- **Follow-ups created**: `mobile-notifications-deep-link-targets`, `mobile-notifications-actor-username-enrichment`, `in-app-notifications-spec-wire-reconciliation` (bucket b); infinite-scroll folded into the existing `mobile-nearby-timeline-infinite-scroll` entry.
