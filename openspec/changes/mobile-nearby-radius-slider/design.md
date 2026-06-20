## Context

The Nearby filter range is a Month-1 Premium differentiator (`docs/01-Business.md`:22, `docs/02-Product.md`:151) that is currently unbuilt at every layer:

- **Mobile** hardcodes `radius_m = 20000` in `NearbyTimelineRepository.kt`:10 (`NEARBY_RADIUS_M`), explicitly flagged as "the one site to generalize" for the `mobile-nearby-radius-slider` follow-up.
- **Backend** (`GET /api/v1/timeline/nearby`) validates `radius_m` as a **continuous** `[100, 50000]` range (`NearbyTimelineService.RADIUS_MIN`/`RADIUS_MAX`), applied uniformly to all callers. There is **no premium gate on radius** today — the `subscription_status` check at `TimelineRoutes.kt`:260 governs *hide-distance*, not radius. So a Free caller can already request 50 km, and the spec'd 100 km position does not exist.

The product contract is a **discrete 4-position** control {10, 20, 50, 100} km: Free locked to 20 km (slider bounces back + upsell), Premium picks any. Making that real requires both a backend boundary (otherwise the gate is a bypassable client cosmetic) and the mobile slider UI. No schema change is needed — radius is a request parameter and tier already rides on `UserPrincipal.subscriptionStatus`.

## Goals / Non-Goals

**Goals:**
- Server-enforce the freemium radius boundary: Free → 20 km only (`403 radius_premium_only` otherwise), Premium → any of {10, 20, 50, 100} km.
- Replace the continuous radius range with the discrete 4-value set and raise the ceiling to 100 km.
- Ship the mobile 4-position slider with the Free bounce-back + Premium upsell, reusing the established premium-gate + upsell idioms.
- Preserve the timeline-read invariants: zero `users` SELECTs in the handler; a rejected radius never burns a Free user's read quota.

**Non-Goals:**
- **Cross-launch persistence of the chosen radius** (see Decision 2 — in-session only; a follow-up adds a local-preference seam).
- Any DB migration, new external dependency, or new `libs.versions.toml` substrate (Material 3 `Slider` is already on the classpath).
- Changing the Nearby query, cursor, distance rendering, hide-distance, or rate-limit behavior — only the radius parameter's allowed set + tier gate.
- A continuous/free-form radius. The product is explicitly a 4-position control.

## Decisions

### Decision 1 — One combined change (backend MODIFY + mobile ADD), not a split

The premium-search and premium-username features ship as split backend/mobile changes (`premium-search`+`mobile-search`, `premium-username-customization`+`mobile-premium-username`). This change is **combined** instead because the backend delta is a single validation block plus a one-line tier check with **no migration**, and the feature is one indivisible user-facing thing (a client slider is meaningless without its server backstop, and vice versa). One-PR-per-change squash-merges them together. *Alternative considered:* split into `premium-nearby-radius` (backend) + `mobile-nearby-radius-slider` (mobile) — rejected as ceremony out of proportion to a ~30-line backend change; would also strand a half-feature (backend gate with no UI) on `main` between merges.

### Decision 2 — In-session radius selection; cross-launch persistence deferred

The mobile app has **no general-purpose local key-value preferences seam** (`SecureTokenStore` is auth-only; analytics consent is *server*-persisted via `ConsentRepository`). Persisting the chosen radius across cold starts would mean introducing a new DataStore/preferences seam — disproportionate to this change and a cross-cutting concern of its own. **Decision:** the selected radius lives in `NearbyTimelineViewModel` state, defaulting to 20 km on each cold start; a Premium user re-selects in one tap. The deferral is captured as an explicit negative-guard scenario in the mobile spec so a future `mobile-client-preferences` follow-up has a requirement to MODIFY. *Alternative considered:* add a DataStore-backed preference now — rejected (new substrate + new seam balloons an atomic Nearby change; YAGNI until a second client preference needs persistence).

### Decision 3 — Free tier's only permitted radius is 20 km

Per `docs/02-Product.md`:151 ("Free stuck at 20km") the Free tier is locked to exactly 20 km — **10 km is also Premium-only** (there is no "smaller is fine for Free" exception). The server gate therefore admits a Free caller only at `radius_m = 20000`; every other allowed-set value (10 km, 50 km, 100 km) → `403 radius_premium_only`. This keeps the gate a single equality check and matches the slider's single Free-anchored position.

### Decision 4 — New `radius_premium_only` 403; mobile maps it to the same upsell as the client bounce-back

A Free caller requesting a Premium radius is a *premium-gated* rejection (403), distinct from an *invalid* radius (400 `radius_out_of_bounds`, reserved for values outside {10,20,50,100} km). The new error code is **`radius_premium_only`**. The mobile reactive-403 backstop maps `radius_premium_only` to the **same** Premium upsell surface the client-side bounce-back shows (mirroring how `mobile-search` maps its Free-tier 403 to the upsell) — so a Free user who somehow reaches the server with a Premium radius (e.g. a stale client) gets a consistent upsell, never a raw error. *Alternative considered:* reuse the generic search-style 403 with no distinct code — rejected; a named code keeps the admin/observability surface and the client mapping unambiguous.

### Decision 5 — Discrete-set validation replaces the range check; quota-safe ordering preserved

`NearbyTimelineService` exposes the allowed set as a constant (e.g. `ALLOWED_RADII_M = setOf(10_000, 20_000, 50_000, 100_000)`), replacing `RADIUS_MIN`/`RADIUS_MAX`. The route validates set membership (→ 400 `radius_out_of_bounds` on miss) **then** the tier gate (→ 403 `radius_premium_only`), **both before** the rate-limiter pre-check — identical ordering to today's `radius_out_of_bounds` 400, so neither a 400 nor a 403 burns a Free user's rolling/session read quota. The service re-validates set membership as defense-in-depth (preserving the existing `RadiusOutOfBoundsException` belt-and-suspenders).

### Decision 6 — Grace-period (`premium_billing_retry`) users: server-permissive, client-conservative

The backend radius gate admits `premium_active` **OR** `premium_billing_retry` (the hide-distance predicate), but the mobile `is_premium` boolean the client reads (per `user-profile-read` / `ProfileFlow`) is `premium_active`-only — the wire exposes only the computed boolean, not the raw `subscription_status`, so the client cannot distinguish a grace-period user. **Decision:** keep the client gate keyed on `is_premium` (so a grace-period user is conservatively client-anchored to 20 km) and let the backend stay authoritative (it would *allow* that user a wider radius, but the conservative client simply never issues the request). This is a self-healing UX cut: the grace user regains the wider radius the moment they renew (→ `premium_active` → `is_premium = true`) or loses Premium entirely on full billing failure (→ `free`). *Alternative considered:* widen the profile wire to expose `subscription_status` so the client can match the 2-state predicate — rejected as a contract widening for a marginal grace-window radius benefit; the reactive-403 backstop already covers the opposite (client-thinks-Premium, server-says-no) direction. Captured here so the asymmetry is intentional, not a latent bug.

### Standards conformance (`docs/11` Pattern Registry — no deviations)

This change consumes the canonical patterns; it introduces **no** new pattern, so no `docs/11` § Pattern Registry amendment is required.

- **Mobile state holder (§2.2):** `NearbyTimelineViewModel` `StateFlow` + a pure Compose-free projection for the gate/selection decision (mirrors `nearbyTimelineUiState` / `AgeGateUiState`; the on-entry `isPremiumKnown: Boolean?` Resolving→known idiom is `UsernameCustomizationViewModel`'s, and the reactive-403 backstop is `SearchViewModel`'s — this change uses both halves).
- **Navigation (§2.3):** Navigation 3 — the slider lives on the existing Nearby surface; the upsell reuses the existing `paywall` route. No new nav substrate.
- **Mobile data layer (§2.6):** the existing status-driven `NearbyTimelineRepository` / `NearbyTimelineFlow` / `NearbyTimelineApiClient` seam — the selected radius threads through unchanged interfaces (`NEARBY_RADIUS_M` generalized to a parameter).
- **Backend layering (§3.1):** route → service. The gate is a route-level concern (reads the principal, shapes the 400/403); set membership is the service's domain rule.
- **Reuse-first (§4):** the upsell reuses one of the two established Free-upsell surfaces — the inline `ui/components/DailyCapUpsellDialog` (the daily/like-cap dialog idiom) or the `screens/paywall/` `PaywallRoute` panel `mobile-search` pushes (these are distinct surfaces; pick at apply per the mockup — Open Question below); the premium server-gate reuses the `premium-search` Free→403 precedent; the tier read reuses the `hide-distance` `principal.subscriptionStatus` precedent (`premium_active` OR `premium_billing_retry`).
- **UI substrate:** Material 3 `Slider` (already on the classpath via `mobile-design-system`); strings via `Res.string.*`.

## Risks / Trade-offs

- **[BREAKING request-contract narrowing]** Radii previously accepted in `(100, 50000)` but not in the new set (e.g. 15000) now 400. → **Mitigation:** the only shipped caller sends the fixed `20000` (still valid); no other client exists pre-launch. Documented as BREAKING in the proposal.
- **[In-session radius resets to 20 km each launch]** A Premium user must re-pick after a cold start. → **Mitigation:** one tap; persistence follow-up is spec-anchored (Decision 2). Acceptable MVP cut.
- **[Client/server gate drift]** If the client bounce-back and the server gate disagree on the allowed set, a Premium user could see an inconsistent state. → **Mitigation:** the allowed set + Free-anchor (20 km) are asserted on both sides; the reactive-403 backstop converges any client-side miss to the upsell.
- **[Stale-principal tier]** `subscription_status` is read from the JWT/principal, which can lag a just-purchased entitlement. → **Mitigation:** identical to every other principal-gated premium feature (search, hide-distance, username) — token refresh resolves it; out of scope to change here.

## Migration Plan

- **No DB migration.** Backend is a pure code change (validation set + tier gate + new error code). Deploy is the standard staging-branch-deploy + `/health/ready` smoke; runtime-impacting (a backend route changes), so a pre-archive staging smoke of `GET /api/v1/timeline/nearby` at each of the 4 radii for a Free vs Premium principal is required (DoD #4).
- **Rollback:** revert the PR; the request contract reverts to the continuous `[100, 50000]` range with no premium gate. No data to unwind.
- **Sequencing:** backend gate and mobile slider land on the same branch (Decision 1); the mobile client tolerates the pre-deploy backend (sends only 20 km until the slider ships) and the post-deploy backend tolerates the old fixed-20 km client — so there is no ordering hazard between the two halves.

## Open Questions

- **Slider affordance for Free users:** does the Free slider render all 4 positions (and bounce back) or render visibly locked with a Premium lock icon on 10/50/100 km? Leaning: render all 4, bounce non-20 km back + upsell (matches `docs/02`:151 "sliding bounces back"). Final affordance confirmed against `dev/mockups/nearyou-screens-mockup.html` at apply (mockup-measure per `docs/11` §2.8).
- **Upsell surface choice:** the inline `DailyCapUpsellDialog` (the daily/like-cap dialog idiom) vs the full-screen `PaywallRoute` panel that `mobile-search` pushes for its Free gate (`SearchScreen` `PremiumGateState` → `PaywallRoute(SEARCH_GATE)`). These are two distinct existing surfaces — the radius upsell reuses one, not a new one. Leaning: the inline `DailyCapUpsellDialog` (lighter, keeps the user on Nearby — a radius snap-back is a momentary correction, not a hard screen-entry gate like search); confirm against `dev/mockups/nearyou-screens-mockup.html` at apply.
