## 1. Backend — discrete radius set + Premium gate (`nearby-timeline`)

- [ ] 1.1 In `NearbyTimelineService.kt`, replace the `RADIUS_MIN`/`RADIUS_MAX` continuous-range constants with `ALLOWED_RADII_M = setOf(10_000, 20_000, 50_000, 100_000)`; update the service-layer defense-in-depth check (`RadiusOutOfBoundsException`) to assert set membership instead of the range.
- [ ] 1.2 In `TimelineRoutes.kt` (nearby route), replace the `radius !in RADIUS_MIN..RADIUS_MAX` check with set-membership against `ALLOWED_RADII_M`, keeping the HTTP 400 `radius_out_of_bounds` shape; ensure both 400 sites (pre-check + service-catch) reflect the set.
- [ ] 1.3 Add the Premium radius gate AFTER set-membership validation and BEFORE the rate-limiter pre-check: a Free principal (`subscriptionStatus` NOT in `{premium_active, premium_billing_retry}`) requesting any `radius_m` ≠ `20000` → HTTP 403 with new error code `radius_premium_only`; read tier from `UserPrincipal.subscriptionStatus` (no `users` SELECT). Reuse the same premium predicate already used at `TimelineRoutes.kt:260`.
- [ ] 1.4 Register the `radius_premium_only` error code in the route's error responses consistent with the existing `respondError` shape.

## 2. Backend — tests (`nearby-timeline`)

- [ ] 2.1 Out-of-set radius (`50`, `15000`, `200000`) → 400 `radius_out_of_bounds`; each of `{10000,20000,50000,100000}` passes the bounds check (Premium caller).
- [ ] 2.2 Free principal at `20000` admitted (no 403); Free at `10000`/`50000`/`100000` → 403 `radius_premium_only`.
- [ ] 2.3 Premium principal (`premium_active` and `premium_billing_retry`) at any set member → not tier-rejected.
- [ ] 2.4 Quota-safety: assert BOTH arms — a `radius_premium_only` 403 AND a `radius_out_of_bounds` 400 each leave the Free rolling/session read counters unincremented (gate runs ahead of the limiter pre-check; use the existing `StatementCounter`/`SpyRateLimiter` harness in `TimelineReadRateLimitTest.kt`).
- [ ] 2.5 Tier gate issues zero `users`-table SELECTs (assert against the no-`users`-SELECT invariant, mirroring the existing timeline-read-rate-limit test approach).
- [ ] 2.6 **Migrate ALL non-set radii across `NearbyTimelineServiceTest.kt`, not just the bounds test.** (a) The bounds test (~L497) asserts `radius_m=100000`→400 and `radius_m=100`→200, both INVERTED by this change (100000 is now a valid member; 100 is now 400 `radius_out_of_bounds`) — update it to the discrete set. (b) **Critically**, ~46 sibling call-sites use out-of-set radii (`radius_m=5000` ×~42, `radius_m=1000` ×4) on happy-path / radius-filter / pagination / shadow-ban / block tests that expect `200` + a body — every one now returns 400 `radius_out_of_bounds`. Sweep those to a valid member (e.g. `20000`) so the suite stays green. (c) Verify the order-dependent tests still pass: `location_out_of_bounds` (NY coords) and `invalid_cursor` tests use out-of-set radii but expect their OWN error — preserve the existing validation order (cursor → location → radius set-membership, all before the limiter pre-check) so those still fire their original error, not `radius_out_of_bounds`. (This suite is `database`-tagged and runs in the CI test lane — it WILL red if missed.)
- [ ] 2.7 Assert the `radius_premium_only` 403 response body matches the existing `respondError` envelope (`error.code` / `error.message`), mirroring the `location_out_of_bounds` body assertion in the existing route test (pairs with task 1.4).

## 3. Mobile — radius slider + gate + upsell (`mobile-nearby-radius-slider`)

- [ ] 3.1 Render the Nearby mockup frame (`dev/mockups/nearyou-screens-mockup.html`) + generate the measurement annex (`dev/scripts/mockup-measure.sh`) for the radius control + upsell; translate spacing/typography/tokens to Compose per `docs/11` §2.8.
- [ ] 3.2 Generalize `NEARBY_RADIUS_M` in `NearbyTimelineRepository.kt` into a selected-radius parameter; thread it through `NearbyTimelineFlow` + `NearbyTimelineApiClient.fetchNearby` (first page) AND the load-more anchor (radius reused across pages; a new selection starts a fresh first-page lineage).
- [ ] 3.3 Add the selected-radius + `isPremiumKnown` state to `NearbyTimelineViewModel` (on-entry self-`isPremium` read mirroring `SearchViewModel`/`UsernameCustomizationViewModel`); default position 20 km; in-session only (no persistence seam).
- [ ] 3.4 Add a pure, Compose-free projection for the gate/selection decision (Free snap-back-to-20km, Premium free-select, Resolving-as-Free, `radius_premium_only`-403 → upsell), mirroring `nearbyTimelineUiState`/`AgeGateUiState` — no Compose/platform/wall-clock dependency.
- [ ] 3.5 Add the 4-position Material 3 `Slider` control to `NearbyTimelineScreen.kt`; Free non-20km drag snaps back + shows the Premium upsell (reuse `DailyCapUpsellDialog` / `paywall`); Premium selection issues a fresh first-page fetch at the chosen radius.
- [ ] 3.6 Map a reactive HTTP 403 `radius_premium_only` from the Nearby fetch to the same upsell surface (never a raw error) and revert the control to 20 km.
- [ ] 3.7 Add all new strings (upsell rationale/CTA, any position labels) to `:shared:resources`; consume via `Res.string.*` — zero hardcoded UI strings.

## 4. Mobile — tests (`mobile-nearby-radius-slider` + `mobile-nearby-timeline`)

- [ ] 4.1 `commonTest` projection test: Free snap-back, Premium select, Resolving-as-Free, **grace-period (`premium_billing_retry` / `is_premium=false`) snapped to 20 km despite server-permit (Decision 6)**, and 403→upsell — each path ≥1 `@Test`.
- [ ] 4.2 `NearbyTimelineApiClient`/repository test: first-page fetch carries the selected `radius_m` (default `20000`; non-default `50000`); the **load-more (second) page** specifically carries the non-default selected `radius_m=50000` (not just page 1); selecting a NEW radius issues a fresh first-page with NO `cursor` (not a load-more append).
- [ ] 4.3 Robolectric `*ScreenTest`: renders the 4-position control + asserts the Free upsell surface; add the new `*ScreenTest` to the `mobile/app/build.gradle.kts` Release-variant test-exclude block.
- [ ] 4.4 Assert the `radius_premium_only` 403 backstop is owned by the ViewModel (parsed `error.code` → upsell + revert to 20 km) and adds NO new `NearbyTimelineOutcome` member (the `mobile-nearby-timeline` status→outcome mapping stays unchanged).

## 5. Verification, evidence, and PR hygiene

- [ ] 5.1 Gates green locally: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test :mobile:app:testDevDebugUnitTest :mobile:app:testDevReleaseUnitTest`.
- [ ] 5.2 UI bring-up via `verify-loop` (context-routed: local emulator/iOS sim screenshots, or cloud device-run) — Free snap-back+upsell and a Premium 50 km/100 km selection observed running; screenshot evidence in the PR body (DoD #3).
- [ ] 5.3 Runtime-impacting backend: pre-archive staging branch-deploy smoke of `GET /api/v1/timeline/nearby` at each of the 4 radii for a Free vs Premium principal (Free non-20km → 403, Premium → 200) (DoD #4).
- [ ] 5.4 Keep the PR title/body current at each phase boundary; on archive, run `openspec validate mobile-nearby-radius-slider --strict` clean and move the change under `archive/`.
