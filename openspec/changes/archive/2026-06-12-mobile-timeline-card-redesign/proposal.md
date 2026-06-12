# Proposal: mobile-timeline-card-redesign

## Why

The shipped timeline cards (Nearby + Global) render content/city/distance/counts but **no author identity**, while the product spec has always specified it (`docs/02-Product.md` § Global Timeline: "*Each post shows the city name under the username*", docs/02:176) and the canonical UI mockup (frames 1 + 19 of `dev/mockups/nearyou-screens-mockup.html`, binding per `docs/11-Engineering-Standards.md` § 2.8) defines the card around it: letter avatar, display name, @username, time, coral pin + city + distance, content. The card is the visual core of the demo feeds, and the same redesign is the natural moment to stop the card-duplication drift flagged by the 2026-06-10 audit (item 05-#11: `NearbyPostCard` / `GlobalPostCard` / `PostHeader` near-verbatim copies) by birthing the card as ONE shared component. Backend work is the mobile dependency: the timeline responses don't carry author identity yet, so the three timeline endpoints gain it now (allowed under the mobile-first priority — "dependency for the mobile work", `openspec/project.md` § Mobile-First to Full-Demo Priority).

## What Changes

**Backend (dependency for the mobile card):**

- `GET /api/v1/timeline/nearby|global|following` each gain two per-post author-identity fields — wire names declared EXPLICITLY as bare camelCase **`authorUsername`** and **`authorDisplayName`** (following the shipped mixed-case wire precedent: `authorUserId` camelCase in `TimelineRoutes.kt`, `username`/`displayName` bare camelCase in `UserProfileRoutes.kt`; NOT the stale snake_case JSON examples in older spec prose). All three ship in one PR — one DTO shape; the mobile Following feed is still a placeholder but its response is prepared now.
- Identity is sourced via **`JOIN visible_users`** on `p.author_id` (shadow-ban invariant — never raw `users`; same join pattern the reply-count LATERAL already uses in all three canonical queries).
- **Zero schema migration**: `users.username` (VARCHAR(60) NOT NULL) + `users.display_name` (VARCHAR(50) NOT NULL) exist since V2. Additive + backward-compatible response change (old clients ignore unknown keys).
- `docs/05-Implementation.md` § Timeline Implementation canonical SQL (all three query blocks) amended in the same PR (docs-reconciliation rule).

**Mobile:**

- A **shared post-card composable is born in `ui/components/`** (docs/11 § 2.1 reuse-first target shape — the package's first occupant) and consumed by Nearby + Global now; Following/profile/search inherit it later. This absorbs the **post-card half of audit item 05-#11**; the list-state-kit half is explicitly NOT included (separate audit item).
- Card layout per mockup frames 1 (light) + 19 (dark): **letter avatar** (initial(s) of `authorDisplayName` on a deterministic tonal-container color — no profile photos/media exist yet), **display name + @username + time** header row, **content**, **coral pin + city + distance** meta row (distance floor ≥5 km stays via the shared `DistanceRenderer`; Global renders no distance). Read-only like/reply counts stay rendered (existing spec requirement), restyled.
- Author display identity **flows through the hoisted `onOpenPost` payload + `PostDetailRoute`** so the post-detail header renders the same identity (header renders from nav args only — no re-fetch, per shipped `mobile-post-detail`).
- **App bar with the brand logo centered** (`CenterAlignedTopAppBar`; `logo_brand_light.xml` / `logo_brand_dark.xml` from `:shared:resources` per theme) owned by the app shell's single Scaffold — placement is a conscious MODIFY of `mobile-home-tab-host` (+ the `mobile-design-system` "tab row flush against the status bar" scenario, which now reads "flush under the shell app bar").
- **PII discipline unchanged**: the `author_user_id` UUID and raw `latitude`/`longitude` are still never rendered or logged; what changes is that the *display* identity (username/display name) is now rendered, as the product spec always intended.
- **Card time label becomes text in the identity header** (after the @-handle, per mockup frames 1/19) — the clock glyph is dropped from the card affordance set; `docs/03-UX-Design.md` § canonical glyph list (and its inset paragraph, for the shell app bar) is **amended in this same PR** (canonical-docs reconciliation; the time *value* keeps the existing date-label treatment — relative formatting stays deferred).

**Deferred OUT of this change (no dead controls are shipped):**

- Inline like/reply/send action row → `mobile-inline-post-actions` (issue [#201](https://github.com/aditrioka/nearyou-id/issues/201)); counts remain read-only here.
- Radius slider → `mobile-nearby-radius-slider`.
- Tap author/avatar → profile screen: NOT wired (profile screen doesn't exist yet — issue [#196](https://github.com/aditrioka/nearyou-id/issues/196)); the card renders identity only.
- Post media/images (Month 6), relative "5 mnt" timestamps (`mobile-timeline-relative-timestamp`), Premium name shimmer (tenure-badge board), card kebab/block/report (issue [#200](https://github.com/aditrioka/nearyou-id/issues/200)), 4th "Pesan" nav section (mockup end-state "Usulan").

## Capabilities

### New Capabilities

- `mobile-post-card`: the shared timeline post-card component contract in `ui/components/` — slots/fields, letter-avatar derivation, identity row, meta row, read-only counts, no-PII rule, no-dead-controls rule, and the reuse-first consumption rule for future feed/profile/search surfaces.

### Modified Capabilities

- `nearby-timeline`: response shape + canonical query — add `authorUsername`/`authorDisplayName` via `JOIN visible_users`.
- `global-timeline`: same response-shape + canonical-query delta.
- `following-timeline`: same response-shape + canonical-query delta.
- `mobile-nearby-timeline`: DTO requirement gains the two fields; card requirements delegate to `mobile-post-card`; "No author identifier" requirement reworded (UUID/coords still banned; display identity now rendered); `onOpenPost` payload gains the two fields.
- `mobile-global-timeline`: same deltas as mobile-nearby-timeline (minus distance).
- `mobile-post-detail`: `PostDetailRoute` payload + header render author display identity; "No author identifier" requirement reworded the same way.
- `mobile-home-tab-host`: shell gains the centered brand-logo app bar; `onOpenPost`/`PostDetailRoute` field list updated.
- `mobile-design-system`: single-Scaffold requirement scenario updated — the tab row sits flush under the shell app bar (insets still applied exactly once); the Material-icons requirement drops the card **time** clock glyph (time renders as text in the identity header).

## Impact

- **Backend**: `JdbcPostsTimelineRepository` / `JdbcPostsFollowingRepository` / `JdbcPostsGlobalRepository` (SQL + row types), the three timeline services' row models, `TimelineRoutes.kt` DTOs + mapping; integration tests (`*TimelineServiceTest`, route tests). No migration, no new dependencies, no rate-limit/cursor changes.
- **Docs**: `docs/05-Implementation.md` § Timeline Implementation (3 SQL blocks).
- **Mobile**: new `ui/components/` package (shared card), `NearbyTimelineScreen` / `GlobalTimelineScreen` (consume shared card, delete local copies), timeline DTOs + domain models, `PostDetailRoute` + `PostDetailScreen` header, `AppShellScreen` (app bar), `AppEntryProvider` (payload wiring), `:shared:resources` strings (handle format, contentDescriptions). Tests: commonTest DTO/projection, Robolectric screen tests (+ Release-variant exclude), existing iOS suites kept green.
- **Wire/compat**: additive fields only; `ignoreUnknownKeys` on clients; no breaking change.
