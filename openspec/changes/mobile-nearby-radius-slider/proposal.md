## Why

The Nearby filter range is a **Month-1 Premium feature** in the freemium table (`docs/01-Business.md`:22 — "Free 20km fixed | Premium 10/20/50/100 km") and the Nearby spec (`docs/02-Product.md`:151 — "4-position slider 10/20/50/100 km — Free stuck at 20km (sliding bounces back + upsell); Premium picks any of the 4"). Neither tier is built: the mobile app hardcodes `radius_m = 20000` (`NearbyTimelineRepository.kt`:10, flagged "the one site to generalize" for this exact follow-up), and the backend `GET /api/v1/timeline/nearby` validates `radius_m` as a **continuous** `[100, 50000]` range for **every** caller — so a Free user can already request 50 km (the freemium boundary is unenforced) and the spec'd 100 km position does not exist (`RADIUS_MAX = 50000`). This change ships the discrete 4-position slider and makes the Free/Premium split a real, server-enforced boundary.

## What Changes

- **Backend — discrete radius set + 100 km ceiling.** Replace the continuous `[100, 50000]` `radius_m` validation with the discrete allowed set **{10000, 20000, 50000, 100000}** m; raise the ceiling to 100 km. A value outside the set still yields HTTP 400 `radius_out_of_bounds` (existing shape preserved). **BREAKING** (request-contract narrowing): radii that are in-range-but-not-in-set (e.g. `15000`, `30000`) that the endpoint previously accepted now return 400 — acceptable because no shipped client sends a non-20 km radius (the only caller is the mobile app's fixed `20000`).
- **Backend — server-enforced Premium gate.** A Free principal (`subscription_status` ∉ {`premium_active`, `premium_billing_retry`}) may use **only** `20000`; any other allowed-set value from a Free caller returns **HTTP 403 `radius_premium_only`**. Premium principals may use any of the 4. Tier is read from `UserPrincipal.subscriptionStatus` (no per-request `users` SELECT — preserves the timeline-read-rate-limit "zero users SELECTs in the handler" invariant), mirroring the existing hide-distance check at `TimelineRoutes.kt`:260. The gate is ordered ahead of the rate-limit pre-check so a rejected request never burns a Free user's read quota (same ordering as the existing `radius_out_of_bounds` 400).
- **Mobile — 4-position radius slider.** Add a 10/20/50/100 km slider to the Nearby surface (default 20 km). The selected radius threads through `NearbyTimelineRepository` / `NearbyTimelineFlow` / `NearbyTimelineApiClient.fetchNearby` and the load-more anchor; selecting a new radius triggers a fresh first-page load (radius stays stable across pages, per the `mobile-nearby-timeline` anchor-reuse rule).
- **Mobile — Premium gate + upsell.** Gate via the on-entry self-`isPremium` read + reactive-403 backstop idiom (the `SearchViewModel` / `UsernameCustomizationViewModel` pattern). Free user: any non-20 km drag bounces the slider back to 20 km and surfaces the Premium upsell (reuse `DailyCapUpsellDialog` / the `paywall` route, matching how `mobile-search` upsells Free). Premium user: free selection drives the fetch. All new strings via `Res.string.*` (no hardcoded UI strings).
- **No database migration** — radius is request-param-driven and the tier already lives on the auth principal.

## Capabilities

### New Capabilities
- `mobile-nearby-radius-slider`: the mobile Nearby 4-position radius selector — slider positions, default 20 km, Free bounce-back-to-20 km + Premium upsell, Premium free selection, on-entry `isPremium` gate + reactive-403 backstop, the selected radius threading through the fetch + load-more anchor, resource-backed strings, and a pure Compose-free projection for the gate/selection decision logic.

### Modified Capabilities
- `nearby-timeline`: the `radius_m` validation requirement changes from a continuous `[100, 50000]` range to the discrete set {10, 20, 50, 100} km (100 km ceiling); a new Premium radius-gating requirement adds Free→`20000`-only with a `radius_premium_only` 403 for other values, Premium→any-of-4, the no-`users`-SELECT tier read, and the gate-before-quota ordering.
- `mobile-nearby-timeline`: the "fixed `radius_m = 20000`" fetch requirement changes to "`radius_m` from the selected slider position (default 20 km)"; load-more anchor reuse is unchanged but now carries the selected radius.

## Impact

- **Backend** (`:backend:ktor`, no migration): `timeline/TimelineRoutes.kt` (nearby route ~L182–215 validation + L260 tier read), `timeline/NearbyTimelineService.kt` (`RADIUS_MIN`/`RADIUS_MAX` → discrete-set constant), a new `radius_premium_only` 403 error code, plus backend integration tests (Free-403, Premium-any, out-of-set 400, quota-not-burned ordering).
- **Mobile** (`:mobile:app`): `screens/timeline/NearbyTimelineScreen.kt` + `NearbyTimelineViewModel.kt` + `NearbyTimelineUiState.kt` (slider + gate state), `timeline/NearbyTimelineRepository.kt` (generalize `NEARBY_RADIUS_M`) / `NearbyTimelineFlow.kt` / `NearbyTimelineApiClient.kt` (thread radius), reuse of `ui/components/DailyCapUpsellDialog.kt` + `screens/paywall/`, new `:shared:resources` strings, a `commonTest` projection test + a Robolectric `*ScreenTest` (added to the Release-variant test-exclude list).
- **No new `libs.versions.toml` substrate** (Material 3 `Slider` is already on the classpath).
- **Disjoint** from all in-flight changes — nothing else touches the nearby-radius logic or the Nearby screen; no shared migration.
