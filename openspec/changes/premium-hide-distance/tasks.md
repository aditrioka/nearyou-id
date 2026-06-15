## 1. Schema (V23)

- [ ] 1.1 Add `backend/ktor/src/main/resources/db/migration/V23__hide_distance_opt_in.sql`: `ALTER TABLE users ADD COLUMN hide_distance_opt_in BOOLEAN NOT NULL DEFAULT FALSE;` (mirror the `private_profile_opt_in` shape in V2). No backfill. If V23 is taken by an earlier-merged change at rebase time, renumber to the next free Vnn (one-column add — trivial).
- [ ] 1.2 Confirm the migration applies cleanly on a fresh DB (boots via `KotestProjectConfig`); no `migrate-supabase-parity` change needed (additive column, no new Supabase-provided state).

## 2. Backend — write endpoint (hide-distance capability)

- [ ] 2.1 Add `users` repository write `setHideDistance(userId, enabled)` — single-statement `UPDATE users SET hide_distance_opt_in = ? WHERE id = ?` on the bounded JDBC dispatcher (mirror `ConsentRepository`). No `@allow-*` annotation (the column is neither `username` nor `private_profile_opt_in`).
- [ ] 2.2 Add `PATCH /api/v1/user/hide-distance` route (thin: parse `{"hideDistance": Boolean}`, authenticate `AUTH_PROVIDER_USER`, call repository, respond `200` echoing the stored value) — mirror `ConsentRoutes`. 401 without a valid JWT. Write permitted for any tier (effect is read-gated).
- [ ] 2.3 Register the route + repository in Koin wiring.

## 3. Backend — Nearby read suppression (nearby-timeline)

- [ ] 3.1 Extend the Nearby canonical SQL to project the **author's** effective-hide input per row (author `hide_distance_opt_in` + `subscription_status`) as **scalar columns on the EXISTING per-arm author join** (visible arm via `visible_users`, self arm via the already-`@AllowRawPostsRead`-annotated raw `users` join) — add NO new `FROM`/`JOIN`/subquery, so the existing lint exemptions, block-exclusion, and `visible_*` discipline are unchanged and no new `@allow-*` annotation is triggered.
- [ ] 3.2 Resolve the **viewer's** effective-hide once per request (single indexed-PK read of the principal's `hide_distance_opt_in` + `subscription_status`, or fold as a scalar into the query).
- [ ] 3.3 Add a pure helper `effectiveDistanceMeters(rawMeters, authorEffectiveHide, viewerEffectiveHide): Double?` returning `null` when either side hides (effectiveness = flag TRUE AND `subscription_status IN ('premium_active','premium_billing_retry')`). Apply it in `NearbyTimelineService` mapping. (Keep `:shared:distance` rendering-only — helper lives in the `timeline` package per design D2/Open-Q.)
- [ ] 3.4 Change `NearbyPostDto.distanceM` (`TimelineRoutes.kt`) from `Double` to `Double?`; populate from the helper so `null` ⇒ omitted via the app-wide `explicitNulls = false`. Leave `latitude`/`longitude`/`city_name`/ordering/radius untouched.

## 4. Mobile — Nearby DTO nullability (mobile-nearby-timeline)

- [ ] 4.1 Change `NearbyTimelineApiClient.NearbyPostDto.distanceM` from `Double` to `Double? = null` so an omitted distance parses (no `MissingFieldException`). No `mobile-post-card` change needed (already `Double?`-tolerant; renders city-only on null).
- [ ] 4.2 Verify the `HomeScreen`/`PostCardModel`/`NavKeys` `distanceM: Double?` chain still compiles end-to-end with the now-sometimes-null Nearby value.

## 5. Mobile — Settings toggle (mobile-settings)

- [ ] 5.1 Consult mockup frame 16 ("Pengaturan") — render the board + generate the measurement annex (`dev/scripts/mockup-measure.sh nearyou-screens-mockup 16`) for the PRIVASI "Sembunyikan jarak" row spacing/typography/switch styling (docs/11 §2.8).
- [ ] 5.2 Add a `HideDistanceApiClient` + `HideDistanceRepository` seam (docs/11 §2.6) calling `PATCH /api/v1/user/hide-distance` via the shared `Auth { bearer }` `HttpClient`; expose a typed outcome (success / failure). ViewModel never calls the ApiClient directly.
- [ ] 5.3 In `SettingsViewModel`, hold the toggle state (current `hide_distance_opt_in` + isEffectivelyPremium) as a `StateFlow` field (docs/11 §2.2); source the effective-premium signal from the same place Settings already learns tier (or the profile read).
- [ ] 5.4 In `SettingsScreen`, promote PRIVASI > "Sembunyikan jarak" from the deferred "Segera hadir" affordance to a real M3 `Switch` row: interactive + PATCH-on-toggle for effectively-Premium callers (revert + non-trapping error on write failure); Premium upsell / disabled affordance for Free callers (no write). Remove "Sembunyikan jarak" from the deferred-row set.
- [ ] 5.5 Add the needed `:shared:resources` Compose Multiplatform Resources strings (row title/subtitle, any toggle-state copy) — no hardcoded UI literals.

## 6. Tests

- [ ] 6.1 Backend: `UserHideDistanceRoutesTest` — enable persists; disable persists; 401 unauthenticated; Free user write stored (200) but no read effect.
- [ ] 6.2 Backend: extend `NearbyTimelineServiceTest` (`database`) — author-on hides for all viewers; viewer-on hides every post; both-off shows raw-meters distance; `premium_billing_retry` author/viewer is effective; `free` + stale TRUE is NOT effective (distance shown); suppression omits `distanceM` (key absent) while `city_name`/`liked_by_viewer`/`reply_count` stay; ordering + radius set identical with flag on vs off; **self-arm — a shadow-banned author with an effective hide-distance preference sees their OWN Nearby post with `distanceM` omitted (author==viewer; reuse the existing self-visibility `setShadowBanned` block)**; **both-off with a fuzzed distance < 5km → `distanceM` present and raw (the 5km floor is render-side, unchanged by suppression)**.
- [ ] 6.3 Backend: assert no-regression on Following/Global (no `distanceM` regardless of flag) and that the existing 18+city_name Nearby scenarios still pass. Explicitly name the docs/01 6-surface no-op set: post-detail/single-post stays distance-free (existing `SinglePostRoutes` coverage); the **push notification body carries no distance** (cite/extend the existing notification-body test); Profile / Search / Chat-context-card carry no viewer-relative distance (never had it — assert distance-free, no new field). (The `DistanceRenderer.render` signature-unchanged check is compile-enforced — `:shared:distance` is not modified — so it needs no runtime test.)
- [ ] 6.4 Mobile: `NearbyTimelineApiClient` parse test — a body OMITTING `distanceM` parses with `distanceM = null` (and the existing full-shape + snake-case-fail + empty-city tests still pass).
- [ ] 6.5 Mobile: extend `SettingsScreenTest` — Premium toggle issues exactly one `PATCH /api/v1/user/hide-distance {"hideDistance":true}`; Free row shows upsell + issues no write; failed PATCH reverts the switch + surfaces error; the deferred-row "writes nothing" scenario still holds for the remaining deferred rows.
- [ ] 6.6 Mobile: a `PostCard` render test confirming a `null` `distanceM` renders city-only (reuse/extend the existing card test; confirms the omitted-distance UX).

## 7. Verification & Definition of Done (docs/11 §5)

- [ ] 7.1 Local mobile unit gate: `:mobile:app:testDevDebugUnitTest` + `:mobile:app:testDevReleaseUnitTest` green (ensure new `*ScreenTest` is in the Release-variant exclude if it uses Robolectric).
- [ ] 7.2 Pre-push gate: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` green locally (CI runs both lint frameworks).
- [ ] 7.3 UI-affecting bring-up (REQUIRED before archive): run the mobile app via `scripts/run_on_device.sh` (cloud) / emulator (local) per `verify-loop`; capture screenshots of the Settings "Sembunyikan jarak" toggle (Premium on/off + Free upsell) and a Nearby card with distance hidden (city-only) vs shown; paste evidence into the PR body.
- [ ] 7.4 Pre-archive staging smoke: `gh workflow run deploy-staging.yml --ref premium-hide-distance` → poll deploy → smoke `PATCH /api/v1/user/hide-distance` + a Nearby read showing omitted `distanceM` for a premium hider (script under `dev/scripts/smoke-premium-hide-distance.sh`). Tick this once green.

## 8. Docs & follow-up

- [ ] 8.1 **Amend canonical docs in this PR** (reviewer item 8 — this change supersedes their stale implementation model): (a) `docs/05` § "Distance Floor + Rounding + Fuzz Order (`renderDistance`)" — replace the `renderDistance(viewer, post, hideDistance: Boolean)` snippet with the shipped pure `DistanceRenderer.render(distanceMeters: Double)` and state hide-distance is **server-side field omission UPSTREAM of the pure renderer** (NOT a renderer parameter); (b) `docs/01` § Hide Distance Mechanics — fix `renderDistance(post, viewer)` → `DistanceRenderer.render(distanceMeters)` and describe the upstream-omission model. Edit prose/code ONLY — do NOT renumber any docs/05 §-coordinate citation (frozen historical IDs). If apply surfaces further doc drift, file a `follow-up` issue.
- [ ] 8.2 On merge, comment on [#267](https://github.com/aditrioka/nearyou-id/issues/267) that the "Sembunyikan jarak" deferred row is now wired (partial resolution; the other deferred Premium rows remain).
- [ ] 8.3 `openspec validate premium-hide-distance --strict` green before each push; `openspec validate --specs hide-distance --type spec --strict` (and the 3 modified capabilities) green at archive.
