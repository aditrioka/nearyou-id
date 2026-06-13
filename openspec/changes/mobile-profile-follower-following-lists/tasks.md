## 1. Pre-flight & references

- [x] 1.1 Consult the screens-mockup board per docs/11 §2.8: enumerated all 19 frames — frame **3** ("Profil — profil sendiri") is the counts entry point and there is **NO** dedicated follower/following list frame; recorded the gap + the identity-row reuse decision (design D5). (A frame-3 headless render/annex was judged unnecessary: the counts-tap is a behavior-only change on the unchanged counts visual — no new pixels — and the list rows reuse the existing `mobile-post-card` idiom; the board-gap note is the load-bearing output.)
- [x] 1.2 Confirm the shipped wire by reading `follow-system` spec + `backend/ktor/.../follow/FollowRoutes.kt`: response `{ users: [{ userId, username, displayName, isPremium, createdAt }], nextCursor }` (bare camelCase), keyset cap 30, base64url cursor, constant `404 user_not_found`, `400 invalid_cursor`.
- [x] 1.3 Confirm (grep `mobile/` + `shared/`) that `/followers` + `/following` have **no existing mobile consumer** (this change is the first), and re-read `mobile-profile` + `mobile-post-card` (identity treatment) + `mobile-design-system` (canonical list pattern) specs.

## 2. Shared strings (`:shared:resources`)

- [x] 2.1 Add Bahasa-Indonesia strings: tab labels (Pengikut / Mengikuti), overlay back-bar title, the two distinct empty states ("Belum ada pengikut" / "Belum mengikuti siapa pun"), loading / error / retry copy, and the two count-tap content descriptions on `ProfileScreen`. No hardcoded literals.
- [x] 2.2 Update `SharedStringsCatalogTest` to reference each new accessor and bump its declared-count assertion.

## 3. Data layer — DTOs + ApiClient

- [x] 3.1 Add `@Serializable` page DTO (`users: List<FollowListUser>`, `nextCursor: String? = null`) and `FollowListUser` (bare camelCase: `userId`, `username`, `displayName`, `isPremium`, `createdAt`) in `mobile/app/src/commonMain/kotlin/id/nearyou/app/followlist/`.
- [x] 3.2 Implement `FollowListApiClient` issuing `GET /api/v1/users/{id}/followers?cursor=` and `/following?cursor=` via the shared `HttpClient` (Bearer attached by the `Auth` plugin; no `X-Session-Id`); rethrow `CancellationException`.

## 4. Data layer — Repository / Flow + outcome mapping + pagination

- [x] 4.1 Define the sealed `FollowListOutcome` (`Loaded(users, nextCursor)` / `NotFound` / `NetworkError`) and the `FollowListFlow` seam.
- [x] 4.2 Implement `FollowListRepository` mapping: 200 → `Loaded`; constant `404 user_not_found` → single `NotFound` (no per-cause branch); 5xx / transport / parse / `400 invalid_cursor` → `NetworkError`; `401` delegated to the `Auth` plugin; `CancellationException` rethrown; **no generic `else`** branch.
- [x] 4.3 Implement keyset pagination: first page (no cursor) on first display, append on scroll-to-end with the prior `nextCursor`, stop on null `nextCursor`, and guard against a duplicate in-flight load-more per tab.

## 5. State holder — UiState projection + ViewModel

- [x] 5.1 Add the Compose-free `FollowListUiState` + a **pure** projection function (separate `isInitialLoad` / `isRefreshing`; loading / content+load-more-gate / empty / not-found / error), mirroring `NearbyTimelineUiState` / `ProfileUiState`; carry no PII beyond display fields.
- [x] 5.2 Add `FollowListViewModel` (obtained via `koinViewModel()`, scoped to the Nav3 entry) talking to `FollowListFlow` (never the ApiClient), holding per-tab state.

## 6. Navigation

- [x] 6.1 Add `FollowListRoute(userId: String, initialTab)` to `screens/routing/NavKeys.kt` — `@Serializable`, no coordinates, no token; register it in the `navSavedStateConfiguration` polymorphic `SerializersModule`.
- [x] 6.2 Map `FollowListRoute` → `FollowListScreen` in `appEntryProvider`; wire the root-stack push from `ProfileScreen`'s count taps via the `mobile-home-tab-host` mechanism (the `ProfileRoute` path), and the row tap → `ProfileRoute(rowUserId)` push.

## 7. UI — FollowListScreen + rows + states

- [x] 7.1 Build `FollowListScreen` (`screens/followlist/`): root-stack overlay with its own back-bar `TopAppBar`, `PrimaryTabRow` (text-only Pengikut / Mengikuti) + `HorizontalPager` synced to the tab row, opening on `initialTab`.
- [x] 7.2 Build the identity row reusing the `mobile-post-card` / `mobile-profile` treatment (letter avatar + deterministic color + display name + `@username` + Premium badge when `isPremium`); row tap → `ProfileRoute(rowUserId)`; the row `userId` UUID never rendered.
- [x] 7.3 Wire the canonical list states per tab (skeleton on initial; `PullToRefreshBox` over retained rows on refresh; empty / not-found / error inside a single-item scrollable `LazyColumn`; cursor-driven load-more); **no** `akun_dihapus`/placeholder/COALESCE/null-identity logic (design D2).

## 8. ProfileScreen — counts become tappable (MODIFIED mobile-profile)

- [x] 8.1 Make `ProfileScreen`'s follower + following counts tappable controls (content-description'd) that emit navigation to `FollowListRoute(userId, initialTab = Followers/Following)`; do NOT mutate the displayed count values; applies to both self and other-user reads.
- [x] 8.2 Invert the `ProfileScreenTest` "counts are not tappable" assertion to assert the counts ARE tappable and emit the correct `FollowListRoute` navigation.

## 9. Koin wiring

- [x] 9.1 Register `FollowListApiClient` + `FollowListRepository` as Koin singletons in `di/MobileModule.kt` with `single<FollowListFlow> { get<FollowListRepository>() }`, reusing the shared `HttpClient` (no new client, no `X-Session-Id`).

## 10. Tests — commonTest

- [x] 10.1 `FollowListUiState` projection tests (initial-load/loading, loaded-with-rows + load-more gating, empty per tab, not-found, full-screen error, **load-more-failure-retains-rows**, **refresh-from-non-content no-skeleton**, **mid-refresh `isRefreshing` transition** via a suspend-from-call fake, and the **rendered-row-count-independent-of-profile-count** guard (D3)).
- [x] 10.2 Pagination tests (cursor advance, append next page, stop on null `nextCursor`, **single-page-no-load-more**, no duplicate in-flight) via a `FakeFollowListFlow`.
- [x] 10.3 Page-DTO parse: shipped camelCase fixture (incl. omitted `nextCursor` → null) + the **snake_case negative guard** (casing-drift trap).
- [x] 10.4 Outcome mapping: constant-404 → single `NotFound` (no generic fallthrough, incl. **both-tabs-`NotFound` consistency**), `400 invalid_cursor` → `NetworkError`, `CancellationException` rethrow.
- [x] 10.5 `FollowListRoute` polymorphic serialized round-trip + `initialTab` deep-link tab selection + row → `ProfileRoute` navigation intent.

## 11. Tests — Robolectric

- [x] 11.1 `FollowListScreenTest` (`mobile/app/src/androidUnitTest/...`): both tabs present, tab switch / pager sync, a row tap fires `ProfileRoute` navigation, the two empty states, no-UUID-in-tree.
- [x] 11.2 Add `FollowListScreenTest` to the `mobile/app/build.gradle.kts` Release-variant test-exclude block (per the `*ScreenTest` convention) so `:mobile:app:testDevReleaseUnitTest` passes.

## 12. Tests — iosTest

- [x] 12.1 iosTest flow test (`mobile/app/src/iosTest/...`, Kotlin/Native-legal names, mirroring `NearbyTimelineFlowIosTest`) exercising the follow-list surface on the simulator.

## 13. Deferred-work tracking

- [x] 13.1 Open a `follow-up` GitHub issue (label `follow-up` + `mobile`) capturing the deferred **inline per-row follow/unfollow** action (rows are navigational only in this change); reference it from the `mobile-follow-lists` deferral requirement. → [#307](https://github.com/aditrioka/nearyou-id/issues/307).

## 14. Verification & Definition of Done

- [x] 14.1 Run the lint gate locally: `./gradlew ktlintCheck detekt :lint:detekt-rules:test` (both lint frameworks; ktlint + detekt).
- [x] 14.2 Run the mobile unit suites: `./gradlew :mobile:app:testStagingDebugUnitTest :mobile:app:testDevReleaseUnitTest` (commonTest + Robolectric); confirm the iosTest compiles/links (`:mobile:app:linkDebugFrameworkIosSimulatorArm64` locally, since CI/Linux can't catch K/N link errors).
- [~] 14.3 Manual-verification evidence (docs/11 §5 DoD, UI-affecting). **Done:** the Robolectric `FollowListScreenTest` + the iosTest render the real Composables and assert both-tabs/empty/row-tap-navigation/no-UUID; the inverted `ProfileScreenTest` asserts the counts are tappable + emit the right `FollowListRoute`. **Deferred to the PR's automated `device-run.yml` Robo crawl:** a live on-device visual smoke of the populated list — reaching it needs a signed-in session + seeded follows, not feasible in this session. Count-vs-list-length-by-design (D3) noted so it isn't flagged.
- [x] 14.4 Confirm the PR body `Closes #260` and stays current at each phase boundary (proposal → feat → archive) per CLAUDE.md.
