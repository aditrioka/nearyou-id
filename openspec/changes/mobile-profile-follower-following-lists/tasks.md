## 1. Pre-flight & references

- [ ] 1.1 Render screens-mockup **frame 3** ("Profil — profil sendiri") + generate its measurement annex (`dev/scripts/mockup-measure.sh nearyou-screens-mockup.html 3`) per docs/11 §2.8, to confirm the **counts-tap affordance** treatment on `ProfileScreen`; record the board gap (no dedicated follower/following list frame) and the reuse decision (design D5) in the PR/verification notes.
- [ ] 1.2 Confirm the shipped wire by reading `follow-system` spec + `backend/ktor/.../follow/FollowRoutes.kt`: response `{ users: [{ userId, username, displayName, isPremium, createdAt }], nextCursor }` (bare camelCase), keyset cap 30, base64url cursor, constant `404 user_not_found`, `400 invalid_cursor`.
- [ ] 1.3 Confirm (grep `mobile/` + `shared/`) that `/followers` + `/following` have **no existing mobile consumer** (this change is the first), and re-read `mobile-profile` + `mobile-post-card` (identity treatment) + `mobile-design-system` (canonical list pattern) specs.

## 2. Shared strings (`:shared:resources`)

- [ ] 2.1 Add Bahasa-Indonesia strings: tab labels (Pengikut / Mengikuti), overlay back-bar title, the two distinct empty states ("Belum ada pengikut" / "Belum mengikuti siapa pun"), loading / error / retry copy, and the two count-tap content descriptions on `ProfileScreen`. No hardcoded literals.
- [ ] 2.2 Update `SharedStringsCatalogTest` to reference each new accessor and bump its declared-count assertion.

## 3. Data layer — DTOs + ApiClient

- [ ] 3.1 Add `@Serializable` page DTO (`users: List<FollowListUser>`, `nextCursor: String? = null`) and `FollowListUser` (bare camelCase: `userId`, `username`, `displayName`, `isPremium`, `createdAt`) in `mobile/app/src/commonMain/kotlin/id/nearyou/app/followlist/`.
- [ ] 3.2 Implement `FollowListApiClient` issuing `GET /api/v1/users/{id}/followers?cursor=` and `/following?cursor=` via the shared `HttpClient` (Bearer attached by the `Auth` plugin; no `X-Session-Id`); rethrow `CancellationException`.

## 4. Data layer — Repository / Flow + outcome mapping + pagination

- [ ] 4.1 Define the sealed `FollowListOutcome` (`Loaded(users, nextCursor)` / `NotFound` / `NetworkError`) and the `FollowListFlow` seam.
- [ ] 4.2 Implement `FollowListRepository` mapping: 200 → `Loaded`; constant `404 user_not_found` → single `NotFound` (no per-cause branch); 5xx / transport / parse / `400 invalid_cursor` → `NetworkError`; `401` delegated to the `Auth` plugin; `CancellationException` rethrown; **no generic `else`** branch.
- [ ] 4.3 Implement keyset pagination: first page (no cursor) on first display, append on scroll-to-end with the prior `nextCursor`, stop on null `nextCursor`, and guard against a duplicate in-flight load-more per tab.

## 5. State holder — UiState projection + ViewModel

- [ ] 5.1 Add the Compose-free `FollowListUiState` + a **pure** projection function (separate `isInitialLoad` / `isRefreshing`; loading / content+load-more-gate / empty / not-found / error), mirroring `NearbyTimelineUiState` / `ProfileUiState`; carry no PII beyond display fields.
- [ ] 5.2 Add `FollowListViewModel` (obtained via `koinViewModel()`, scoped to the Nav3 entry) talking to `FollowListFlow` (never the ApiClient), holding per-tab state.

## 6. Navigation

- [ ] 6.1 Add `FollowListRoute(userId: String, initialTab)` to `screens/routing/NavKeys.kt` — `@Serializable`, no coordinates, no token; register it in the `navSavedStateConfiguration` polymorphic `SerializersModule`.
- [ ] 6.2 Map `FollowListRoute` → `FollowListScreen` in `appEntryProvider`; wire the root-stack push from `ProfileScreen`'s count taps via the `mobile-home-tab-host` mechanism (the `ProfileRoute` path), and the row tap → `ProfileRoute(rowUserId)` push.

## 7. UI — FollowListScreen + rows + states

- [ ] 7.1 Build `FollowListScreen` (`screens/followlist/`): root-stack overlay with its own back-bar `TopAppBar`, `PrimaryTabRow` (text-only Pengikut / Mengikuti) + `HorizontalPager` synced to the tab row, opening on `initialTab`.
- [ ] 7.2 Build the identity row reusing the `mobile-post-card` / `mobile-profile` treatment (letter avatar + deterministic color + display name + `@username` + Premium badge when `isPremium`); row tap → `ProfileRoute(rowUserId)`; the row `userId` UUID never rendered.
- [ ] 7.3 Wire the canonical list states per tab (skeleton on initial; `PullToRefreshBox` over retained rows on refresh; empty / not-found / error inside a single-item scrollable `LazyColumn`; cursor-driven load-more); **no** `akun_dihapus`/placeholder/COALESCE/null-identity logic (design D2).

## 8. ProfileScreen — counts become tappable (MODIFIED mobile-profile)

- [ ] 8.1 Make `ProfileScreen`'s follower + following counts tappable controls (content-description'd) that emit navigation to `FollowListRoute(userId, initialTab = Followers/Following)`; do NOT mutate the displayed count values; applies to both self and other-user reads.
- [ ] 8.2 Invert the `ProfileScreenTest` "counts are not tappable" assertion to assert the counts ARE tappable and emit the correct `FollowListRoute` navigation.

## 9. Koin wiring

- [ ] 9.1 Register `FollowListApiClient` + `FollowListRepository` as Koin singletons in `di/MobileModule.kt` with `single<FollowListFlow> { get<FollowListRepository>() }`, reusing the shared `HttpClient` (no new client, no `X-Session-Id`).

## 10. Tests — commonTest

- [ ] 10.1 `FollowListUiState` projection tests (initial-load/loading, loaded-with-rows + load-more gating, empty per tab, not-found, error).
- [ ] 10.2 Pagination tests (cursor advance, append next page, stop on null `nextCursor`, no duplicate in-flight) via a `FakeFollowListFlow`.
- [ ] 10.3 Page-DTO parse: shipped camelCase fixture (incl. omitted `nextCursor` → null) + the **snake_case negative guard** (casing-drift trap).
- [ ] 10.4 Outcome mapping: constant-404 → single `NotFound` (no generic fallthrough), `400 invalid_cursor` → `NetworkError`, `CancellationException` rethrow.
- [ ] 10.5 `FollowListRoute` polymorphic serialized round-trip + `initialTab` deep-link tab selection + row → `ProfileRoute` navigation intent.

## 11. Tests — Robolectric

- [ ] 11.1 `FollowListScreenTest` (`mobile/app/src/androidUnitTest/...`): both tabs present, tab switch / pager sync, a row tap fires `ProfileRoute` navigation, the two empty states, no-UUID-in-tree.
- [ ] 11.2 Add `FollowListScreenTest` to the `mobile/app/build.gradle.kts` Release-variant test-exclude block (per the `*ScreenTest` convention) so `:mobile:app:testDevReleaseUnitTest` passes.

## 12. Tests — iosTest

- [ ] 12.1 iosTest flow test (`mobile/app/src/iosTest/...`, Kotlin/Native-legal names, mirroring `NearbyTimelineFlowIosTest`) exercising the follow-list surface on the simulator.

## 13. Deferred-work tracking

- [ ] 13.1 Open a `follow-up` GitHub issue (label `follow-up` + `mobile`) capturing the deferred **inline per-row follow/unfollow** action (rows are navigational only in this change); reference it from the `mobile-follow-lists` deferral requirement.

## 14. Verification & Definition of Done

- [ ] 14.1 Run the lint gate locally: `./gradlew ktlintCheck detekt :lint:detekt-rules:test` (both lint frameworks; ktlint + detekt).
- [ ] 14.2 Run the mobile unit suites: `./gradlew :mobile:app:testStagingDebugUnitTest :mobile:app:testDevReleaseUnitTest` (commonTest + Robolectric); confirm the iosTest compiles/links (`:mobile:app:linkDebugFrameworkIosSimulatorArm64` locally, since CI/Linux can't catch K/N link errors).
- [ ] 14.3 Manual-verification evidence (docs/11 §5 DoD, UI-affecting): run the app (emulator/device or Robo via `scripts/run_on_device.sh`), open a profile, tap each count → land on the correct tab, swipe between tabs, tap a row → land on that profile; capture screenshots for the PR body. Note the count-vs-list-length-by-design point (design D3) so it isn't flagged.
- [ ] 14.4 Confirm the PR body `Closes #260` and stays current at each phase boundary (proposal → feat → archive) per CLAUDE.md.
