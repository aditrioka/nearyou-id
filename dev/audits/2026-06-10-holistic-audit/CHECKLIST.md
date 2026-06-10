# Phase 3 review work-list — holistic audit 2026-06-10

Status legend: `[ ]` pending · `[~]` review done, fixes in progress · `[x]` reviewed + fixed + tests green · `[-]` reviewed, no action needed

> Skeleton — areas will be finalized from the repo-map pass before Phase 3 starts.

## Backend (`:backend:ktor` + `:infra:*`) — performance focus

- [ ] B1. Auth (signin/signup/refresh, JWT issue/verify, sessions)
- [ ] B2. Post (create, detail, delete) + engagement (likes, replies)
- [ ] B3. Timelines (nearby / following / global) — query shapes, pagination, N+1
- [ ] B4. Social graph (follow, block) + user/profile
- [ ] B5. Chat (REST write path + broadcast publish)
- [ ] B6. Search + notifications
- [ ] B7. Moderation, reports, guard (rate limits), health, internal
- [ ] B8. Cross-cutting: DB access layer (Hikari config, dispatchers, transaction discipline), Redis usage, serialization config, StatusPages/error envelope, Koin wiring, Application bootstrap
- [ ] B9. Admin package (light pass — deferred surface, invariants only)

## Mobile shared (`:mobile:app` commonMain + `:shared:resources`) — coherence focus

- [ ] M1. App shell: navigation graph (Nav3), DI modules, theme, scaffolding/insets
- [ ] M2. Design system / shared components (cross-change coherence: post card, feeds, loading/error/empty states)
- [ ] M3. Auth + session feature (sign-in, age gate, token storage, refresh flow)
- [ ] M4. Timeline features (nearby/global/following+placeholder, home tab host, pager)
- [ ] M5. Post creation + post detail
- [ ] M6. Data layer (Ktor client setup, repos, DTOs, error mapping) + location services
- [ ] M7. State management patterns (ViewModels, UiState shapes, flows) — consistency sweep
- [ ] M8. Analytics consent + notifications + bottom-nav sections (recently merged changes)

## Native specifics

- [ ] N1. androidMain: MainActivity, lifecycle, permissions, location actuals, token storage (DataStore+Tink), credential manager
- [ ] N2. iosMain: app entry, lifecycle, permissions, CLLocationManager actuals, Keychain, expect/actual completeness
- [ ] N3. iosApp host project + build config sanity

## Final gates

- [ ] G1. `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test`
- [ ] G2. `:mobile:app:testDevDebugUnitTest` + `:mobile:app:testDevReleaseUnitTest`
- [ ] G3. iOS: `linkDebugFrameworkIosSimulatorArm64` (if iosMain touched)
- [ ] G4. PROGRESS.md final summary + flagged items
