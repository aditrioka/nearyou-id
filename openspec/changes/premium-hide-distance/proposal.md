## Why

"Hide Distance" is a launch-day Premium perk (`docs/01-Business.md` freemium table — "Hide distance: Premium, distance number only, Month 1"; `docs/08-Roadmap-Risk.md` Phase 4 item #15) that is still unbuilt. The mobile Settings screen already ships a **deferred** PRIVASI > "Sembunyikan jarak" row that only shows "Segera hadir" and writes nothing (`mobile-settings` spec, tracked by follow-up issue [#267](https://github.com/aditrioka/nearyou-id/issues/267)). This change makes that toggle real, rounding out the Premium revenue loop alongside the in-flight paywall / username-customization / post-editing work — and it does so with a footprint disjoint from those concurrent changes (it is the only change touching the distance render path).

## What Changes

- Add a Premium-gated, per-user `users.hide_distance_opt_in` boolean (default `FALSE`). When **effective**, it symmetrically suppresses the **distance number** on the Nearby feed — viewers stop seeing it on the activator's posts, and the activator stops seeing it on everyone's posts.
- The flag is **effective only while the user is Premium** (`subscription_status IN ('premium_active','premium_billing_retry')`), mirroring how `private_profile_opt_in` is premium-gated in `user-profile-read`. A Free user with a stale `TRUE` flag is treated as OFF.
- **Scope is the distance number only.** City name stays visible; the global 5km distance floor is unchanged; ordering is unchanged (all timelines already sort by time, so suppressing the number leaks no ordering signal). The Nearby **radius filter still applies** — a hide-distance viewer still gets the proximity-filtered feed, just without the per-card number.
- **Reconciliation with the docs/01 6-surface mandatory checklist (Timeline card, Post detail, Profile, Chat context card, Search result, Notification list):** today only `GET /api/v1/timeline/nearby` emits a distance number. Following + Global specs already mandate **no** `distanceM`/`distance_m` field; post-detail / single-post / search / chat-embedded already leave it null ("v1 has no viewer-location context"); push bodies never carry distance. So the **active** behavioral change is on Nearby alone; every other surface is asserted distance-free as a no-regression guard (the rule is a verified no-op there).
- New write endpoint `PATCH /api/v1/user/hide-distance` (JWT-required, boolean body), mirroring the `PATCH /api/v1/user/consent` precedent. `hide_distance_opt_in` is a fresh column — **not** `username` and **not** `private_profile_opt_in` — so it is outside both the username-write and privacy-flag-write Detekt allowlists (no `@allow-*` annotation; no new lint invariant).
- Mobile: promote the deferred Settings "Sembunyikan jarak" row to a **backed Premium-gated toggle** wired to the new endpoint (Free users see the existing Premium upsell / disabled affordance); make the Nearby wire DTO's `distanceM` nullable so an omitted value parses (the shared `PostCard` already renders city-only when `distanceM` is null — no card change).
- `:shared:distance` `DistanceRenderer.render(Double): String` stays a **pure formatter** (unchanged); suppression is a visibility decision at the Nearby read path that omits the field before rendering.
- **Deferred (named so a follow-up has something to MODIFY):** the Premium Tenure Counter (separate docs/01 feature); any change to the 5km floor or jitter order; adding viewer-relative distance to post-detail / search (those staying null is intentional).

## Capabilities

### New Capabilities
- `hide-distance`: the Premium hide-distance feature — the `users.hide_distance_opt_in` column, the premium-effective + symmetric (author-OR-viewer) suppression rule, the `PATCH /api/v1/user/hide-distance` write endpoint, and the cross-surface scope statement (Nearby is the only distance-bearing surface; the rule no-ops on every already-distance-free surface).

### Modified Capabilities
- `nearby-timeline`: the response `distanceM` becomes nullable and is **omitted** when the hide-distance rule applies for the (author, viewer) pair; when present it is still the raw-meters `ST_Distance(display_location, …)` value. The query additionally reads the author's and viewer's `hide_distance_opt_in` + premium status. Radius filtering, keyset pagination, time ordering, and the `display_location`-only jitter invariant are unchanged.
- `mobile-settings`: the PRIVASI > "Sembunyikan jarak" row is promoted from a deferred "Segera hadir" no-write affordance to a **backed Premium-gated toggle** wired to `PATCH /api/v1/user/hide-distance`; Free users get the Premium upsell / disabled state; the toggle reflects current server state. (Partially resolves [#267](https://github.com/aditrioka/nearyou-id/issues/267).)
- `mobile-nearby-timeline`: the Nearby response wire DTO's `distanceM` changes from non-null `Double` to nullable-with-default so an omitted distance deserializes and renders city-only via the already-null-tolerant `mobile-post-card`.

## Impact

- **Schema:** one new column `users.hide_distance_opt_in BOOLEAN NOT NULL DEFAULT FALSE` in the next Flyway migration (latest is V22 → this is **V23**; flag as a rebase-resolvable overlap with in-flight `premium-image-upload-pipeline` #325 / `privacy-flip-worker` #321 — a one-column add renumbers trivially).
- **Backend (`:backend:ktor`):** new `user`-package endpoint + repository write; Nearby timeline service + SQL projection + DTO change (`timeline` package).
- **Mobile (`:mobile:app`):** `SettingsScreen` toggle + a write client; `NearbyPostDto` (`timeline` package) nullability; new Compose Multiplatform Resources strings.
- **Shared (`:shared:distance`):** no API change (`render()` stays pure).
- **Specs:** 1 new (`hide-distance`) + 3 modified (`nearby-timeline`, `mobile-settings`, `mobile-nearby-timeline`).
- **No new library / `libs.versions.toml` change.** Reuses existing Ktor, DataStore, Compose, kotlinx.serialization.
- **Follow-up:** partially resolves [#267](https://github.com/aditrioka/nearyou-id/issues/267) (the "Sembunyikan jarak" deferred row).
