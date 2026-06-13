# Proposal: mobile-following-timeline-screen

## Why

The Following tab in `:mobile:app` is the last of the three home feeds still showing a deferred placeholder. `mobile-home-tab-host` shipped the Following tab as a documented empty-state placeholder (`FollowingPlaceholderScreen`) that issues **no** network fetch, with an explicit note: "the real Following feed is **deferred** … which will MODIFY this requirement to introduce the live feed." Two upstream dependencies that justified the deferral are now resolved:

- **The backend is fully shipped.** `GET /api/v1/timeline/following` (`openspec/specs/following-timeline/spec.md`) returns the chronological followed-author feed with `liked_by_viewer` / `reply_count` / `city_name` and — as of `mobile-timeline-card-redesign` — `authorUsername` / `authorDisplayName`. That redesign added the identity fields to this endpoint **specifically** "so the future `mobile-following-timeline-screen` change consumes them without a backend follow-up." There is no backend work here.
- **The follow-action UI is in flight.** Live-menu pick #1 `mobile-profile-screen` (in-flight, PR [#245](https://github.com/aditrioka/nearyou-id/pull/245)) wires the follow action, so the Following feed will no longer be perpetually empty once both land.

This is live-menu pick **#2** in `openspec/project.md` § Mobile-First to Full-Demo Priority (the explicit next dependency-ordered mobile pick after the now-claimed profile #1), advancing the authenticated core loop toward the demoable end-to-end state that flips the mobile-first priority. The feed is the visual core of the demo — having the Following tab live (even when empty it directs the user to Nearby/Global per `docs/03-UX-Design.md` § Empty State) completes the three-tab home.

## What Changes

**Mobile only — NO Flyway migration, NO backend change, NO new dependency.** The change mirrors the just-shipped Global timeline seam (`openspec/specs/mobile-global-timeline/spec.md`) almost verbatim, because Following is identical to Global **minus the spatial filter** (no `lat`/`lng`/`radius_m`, no distance) and **plus** a Following-specific directive empty state.

- **Replace `FollowingPlaceholderScreen` with a live `FollowingTimelineScreen`** rendering the followed-author feed through the shared `mobile-post-card` composable in a Material 3 pull-to-refresh `LazyColumn`, inset-free under the shell's single `Scaffold` (no own `Scaffold`/`TopAppBar`), under `NearYouTheme` light/dark.
- **A status-driven data seam** mirroring the Global trio: `FollowingTimelineApiClient` (`GET /api/v1/timeline/following`, no spatial params, reuses the singleton `SessionIdProvider` `X-Session-Id` header, Bearer via the shipped `Auth` plugin), `FollowingTimelineRepository` mapping each fetch result to exactly one sealed `FollowingTimelineOutcome` member (HTTP-status-driven, terminal-401 → `SessionExpired`, no generic fallthrough copy), and a `FollowingTimelineFlow` interface for fakes.
- **Response DTOs from the SHIPPED mixed-case wire** (`TimelineRoutes.kt` Following handler): bare camelCase `id`/`authorUserId`/`authorUsername`/`authorDisplayName`/`content`/`latitude`/`longitude`/`createdAt`; `@SerialName` snake `city_name`/`liked_by_viewer`/`reply_count`; top-level `posts` + bare `nextCursor` + `upsell`. **No `distanceM`.**
- **Six visual states** via `stringResource` per the `mobile-design-system` canonical loading/refresh pattern: Loading / Content / Empty / Error / rate-limit-hard / rate-limit-soft. **The Following-specific difference from Global:** the Empty state (Loaded, empty posts, no `upsell` — caller follows nobody, or no eligible posts) renders the **directive** copy `timeline_following_placeholder` ("*Kamu belum mengikuti siapa pun. Lihat Nearby atau Global dulu.*") plus a `cta_see_global` ("*Lihat Global*") control that switches the Home pager to the Global tab — per `docs/03-UX-Design.md` § Empty State "Following empty → direct user to Nearby/Global" — NOT the loading-skeleton copy Global-empty reuses.
- **HomeRoute-scoped `FollowingTimelineViewModel`** (resolved via `viewModel { }` under the `HomeRoute` NavEntry, mirroring Nearby/Global): loads once on construction, `reload()` on pull-to-refresh / retry, distinct `isInitialLoad` + `isRefreshing` flags, survives feed swipe/tab switch + section switch + composer round-trip with **no re-fetch**.
- **Inline like + reply shortcut + whole-card tap reuse the shipped seams** — the shared `InlineLikeController` + `LikeFlow` Koin singleton (no per-feed copy of the optimistic/revert/in-flight/cap lifecycle, no second like client/repository; cap-upsell via `mobile-cap-upsell-dialog`), and hoisted `onOpenPost` / `onOpenPostReply` lambdas carrying the card's non-PII display fields with `distanceM = null` (never lat/lng, never the author UUID). The screen stays navigation-free.
- **`mobile-home-tab-host` is MODIFIED**: the Following pager page now renders `FollowingTimelineScreen` (not the placeholder), issues the fetch, and wires `onOpenPost`/`onOpenPostReply` into the Following tab exactly as Nearby/Global; the HomeRoute-scoped no-re-fetch invariant extends to Following. The `FollowingTabNoFetchScanTest` is removed (it asserts the now-obsolete no-fetch posture).
- **PII discipline unchanged**: the `author_user_id` UUID and raw `latitude`/`longitude` are never rendered or logged (the `HttpClientFactory` `LogLevel.HEADERS` + `Authorization` sanitization already exclude bodies; this change does not widen logging).
- **`next_cursor` is parsed and retained**, but cursor-based load-more (infinite scroll) is **deferred** — GitHub issue [#188](https://github.com/aditrioka/nearyou-id/issues/188) (`mobile-nearby-timeline-infinite-scroll`) is extended to cover Following.

**Deferred OUT of this change (no dead controls shipped):**

- Infinite scroll / load-more (issue [#188](https://github.com/aditrioka/nearyou-id/issues/188), extended to Following).
- Tap author/avatar → profile navigation (the profile screen is the in-flight `mobile-profile-screen`, issue [#196](https://github.com/aditrioka/nearyou-id/issues/196)); the card renders identity only, per the shared `mobile-post-card` contract.
- Per-tab `NavDisplay` back stacks (issue [#189](https://github.com/aditrioka/nearyou-id/issues/189)) — the Following page composes directly under `HomeRoute`, no new `NavKey`.

## Capabilities

### New Capabilities

- `mobile-following-timeline`: the mobile Following-feed surface — `FollowingTimelineScreen` + the `FollowingTimelineApiClient` / `FollowingTimelineRepository` / `FollowingTimelineFlow` data seam + the HomeRoute-scoped `FollowingTimelineViewModel` + the pure `FollowingTimelineUiState` projection (with the directive empty state) + the shared inline-like reuse + the hoisted `onOpenPost`/`onOpenPostReply` wiring + the terminal-401 redirect state, mirroring `mobile-global-timeline` minus the spatial filter.

### Modified Capabilities

- `mobile-home-tab-host`: the Following tab renders the live `FollowingTimelineScreen` (REMOVES the "deferred placeholder, no fetch" requirement, ADDS the live-feed requirement); the tab-host body, the `onOpenPost`/`onOpenPostReply` wiring requirement, the tab-state no-re-fetch requirement, and the test-coverage requirement are updated so the Following page is treated as a live feed (and the `FollowingTabNoFetchScanTest` is dropped).

## Impact

- **Mobile**: `screens/timeline/FollowingTimelineScreen.kt` (replaces `FollowingPlaceholderScreen.kt`, deleted), `FollowingTimelineUiState.kt`, `FollowingTimelineViewModel.kt`; `timeline/FollowingTimelineApiClient.kt`, `FollowingTimelineRepository.kt`, `FollowingTimelineFlow.kt`; `di/MobileModule.kt` (3 registrations + flow bind, reuse `SessionIdProvider`); `screens/home/HomeScreen.kt` + `screens/shell/AppShellScreen.kt` + `screens/routing/AppEntryProvider.kt` (Following page renders the live screen + `onOpenPost`/`onOpenPostReply`/`onSwitchToGlobal` wiring). Tests: commonTest projection, MockEngine client/repository, Robolectric `FollowingTimelineScreenTest` (+ Release-variant exclude), iosTest flow; home-tab-host tests updated (remove `FollowingTabNoFetchScanTest`, extend no-re-fetch coverage).
- **Backend**: none (endpoint shipped).
- **Docs**: none load-bearing — the Following feed is already specified in `docs/02-Product.md` § Following Timeline + `docs/03-UX-Design.md` § Empty State; this change consumes the existing contract.
- **Wire/compat**: read-only consumer of a shipped additive endpoint; `ignoreUnknownKeys` on the client; no breaking change.
