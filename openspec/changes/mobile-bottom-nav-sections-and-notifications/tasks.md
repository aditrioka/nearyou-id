## 1. Pre-implementation grounding

- [x] 1.1 Re-confirm the SHIPPED notifications wire is unchanged at impl time: re-read `backend/ktor/src/main/kotlin/id/nearyou/app/notifications/NotificationRoutes.kt` and verify the DTO field names + status/error codes in `design.md` D2 still hold (opaque `next_cursor`, `body_data` non-null, `{count}`, `{marked_read}`, `204`/`404 not_found`, `unread=` param). If drifted, update D2 + the spec before coding. — VERIFIED: wire matches D2 exactly (DEFAULT_LIMIT 20 / MAX_LIMIT 50 clamp; 13 `NotificationType` wire values). No drift; no D2/spec update needed.
- [x] 1.2 Re-read the `GlobalTimeline*` seam (`timeline/GlobalTimeline{ApiClient,Repository,Flow}.kt`, `screens/timeline/GlobalTimeline{ViewModel,Screen}.kt`, `GlobalTimelineUiState.kt`) + tests as the copy-adapt template; confirm the `viewModel { }` NavEntry-decorator + `Auth`-plugin conventions and the shipped `HomeScreen`/`AppEntryProvider` structure. — DONE: seam + host + test templates read.
- [x] 1.3 **Re-check #159 (`mobile-post-detail-screen`) state**: if merged, base/rebase on it and PRESERVE its `mobile-home-tab-host` ADDED `onOpenPost` requirement + code; if still open, design the shell so each feed top-tab hoists `onOpenPost(...)` → root-stack `PostDetailRoute` (absorb per `design.md` D9) and flag the squash-merge ordering. Reconcile the `mobile-home-tab-host` spec delta accordingly — note that #159's `onOpenPost` requirement BODY must be EDITED, not just preserved: its `appEntryProvider`→`HomeScreen` call-site clause goes stale once `HomeScreen` is a shell body (the root push moves to the shell's Home-section wiring). — #159 is OPEN + **proposal-only** (its diff vs main is 4 spec.md files; NO HomeScreen/AppEntryProvider code yet). So: keep the feed-tab `when(tab)` dispatch absorbable (no `onOpenPost` wired now — no `PostDetailRoute` exists); flag squash-merge ordering at 14.5.
- [x] 1.4 If `FOLLOW_UPS.md` is over its 30-entry cap at apply time, run `/triage-follow-ups` before adding this change's entries (Section 13). — RESOLVED: FOLLOW_UPS.md was at 32 (over cap). User chose **add-now + triage in a dedicated `/triage-follow-ups` session** (recommended option). Section 13 entries added (→37); triage spawned as a tracked background task + logged in the 2026-06-07 cap note.

## 2. Strings & resources (`:shared:resources`)

- [x] 2.1 Add section + feed strings: `section_home`, `section_notifications`, `section_profile` (+ their nav `contentDescription`s), `profile_placeholder` ("Profil segera hadir."). Reuse existing `tab_nearby`/`tab_following`/`tab_global`, `cta_post`.
- [x] 2.2 Add notifications strings: `notifications_title` ("Notifikasi"), `notifications_loading`, `notifications_empty` ("Belum ada notifikasi"), `notifications_mark_all_read` ("Tandai semua dibaca"), `notifications_badge` (badge `contentDescription`), plus type-keyed copy `notif_post_liked` / `notif_post_replied` / `notif_followed` / `notif_post_auto_hidden` / `notif_chat_message` / `notif_generic` (generic-actor copy per `design.md` D4). Reuse `signin_error_network` + `cta_retry`.
- [x] 2.3 Bump `SharedStringsCatalogTest`'s expected count (55 → 73); verify `Res` accessors resolve on all targets (confirmed at gate, §14.1).

## 3. App shell restructure (`screens/shell/`, commonMain)

- [x] 3.1 Add `AppShellScreen` + a `@Serializable Section` enum (Home / Notifikasi / Profil) held in `rememberSaveable` (iOS-safe), default Home; a Material 3 `Scaffold` whose `bottomBar` is a `NavigationBar` of the three sections (labels + `contentDescription` via `stringResource`); body renders the selected section's content. Under `NearYouTheme`; zero hardcoded strings.
- [x] 3.2 Make the shell the authenticated root: update `screens/routing/AppEntryProvider.kt` so the authenticated entry maps to `AppShellScreen` (the shell hosts `HomeScreen` for the Home section). Preserve the composer-FAB root push + (absorbed) `onOpenPost` root push at the call site. — `HomeRoute → AppShellScreen`; FAB root push preserved; #159 `onOpenPost` slot documented at the call site (proposal-only, not wired).
- [x] 3.3 Register the section-selection saver + (if needed) the polymorphic serializers for any new shell state; confirm process-death round-trip on iOS. — `Section` is `@Serializable` + `rememberSaveable` (same iOS-safe enum path as `Tab`); NOT a `NavKey`, so NO `navSavedStateConfiguration` change. Round-trip covered by `SectionSerializationTest` (§12.7).

## 4. Home section rework (feeds → top tab row)

- [x] 4.1 Rework `screens/home/HomeScreen.kt`: render a Material 3 `PrimaryTabRow` of the three feed tabs (`@Serializable Tab` enum in `rememberSaveable`, default Nearby) over the selected feed's body (Nearby→`NearbyTimelineScreen`, Following→placeholder, Global→`GlobalTimelineScreen`). Remove the bottom `NavigationBar` from `HomeScreen` (it belongs to the shell now).
- [x] 4.2 Keep the feed load-state ViewModels `HomeRoute`-scoped (no re-fetch on feed-tab switch AND no re-fetch on bottom-nav section switch — Home content not torn down on section change). Verify against `FakeNearby/GlobalTimelineFlow` fetch counters. — feeds compose directly under the shell's `HomeRoute` `NavEntry` (no intermediate `NavDisplay`); fetch-counter assertions in §12.6/12.7.
- [x] 4.3 Keep the composer FAB on the Home section (pushes `PostCreationRoute` to the root stack); ensure it does NOT render on Notifikasi/Profil sections. Absorb #159's hoisted `onOpenPost(...)` per task 1.3. — FAB lives in `HomeScreen` (renders on Home section only); `when(selectedTab)` dispatch keeps #159's `onOpenPost` mechanically absorbable.

## 5. Profil placeholder section

- [x] 5.1 Add `screens/profile/ProfilePlaceholderScreen.kt` rendering `stringResource(Res.string.profile_placeholder)`, issuing NO network fetch (mirror `FollowingPlaceholderScreen`). Wire it as the Profil section body.

## 6. Notifications networking layer (`notifications/` package, commonMain)

- [x] 6.1 Add `NotificationsApiClient` issuing `GET /api/v1/notifications` (first page omits `cursor`; subsequent pages pass the opaque `next_cursor` verbatim as `cursor=`; Bearer via the shipped `Auth` plugin — not reimplemented).
- [x] 6.2 Define `@Serializable` DTOs from the SHIPPED wire (`design.md` D2): `NotificationDto` (bare `id`/`type`; `@SerialName` snake `actor_user_id`/`target_type`/`target_id`/`body_data`(non-null `JsonElement`)/`created_at`/`read_at`), `NotificationListResponse` (`items`, `@SerialName("next_cursor") nextCursor: String? = null`). Reuse the shared `Json`.
- [x] 6.3 Add the unread-count client call + DTO `{ count: Long }`, the read-all client call + DTO `{ marked_read: Int }`, and the mark-read call (`PATCH /{id}/read` → 204 success / 404 no-op / other → caller reverts).

## 7. Notifications repository, outcome mapping, flow seam

- [x] 7.1 Add sealed `NotificationsOutcome` (`Loaded(items, nextCursor)` / `Error` / `NetworkError`) and `NotificationsRepository` mapping HTTP status → outcome with NO generic fallthrough (200→Loaded; 400→Error; 5xx/IO→NetworkError; 401 delegated to `Auth`). The 400 diagnostic logs status/type ONLY — never `actor_user_id`/`target_id`/`body_data`/body/token (match `GlobalTimelineRepository`).
- [x] 7.2 Add the `NotificationsFlow` interface + bind `single<NotificationsFlow> { get<NotificationsRepository>() }`; expose mark-read / mark-all-read / unread-count pass-throughs.

## 8. Notifications state projection + ViewModel

- [x] 8.1 Add Compose-free `NotificationsUiState` (Loading / Content(rows) / Empty / Error) + pure `notificationsUiState(outcome, inFlight)` projection (no PII; deterministically unit-testable).
- [x] 8.2 Add `NotificationsViewModel` resolved via `viewModel { }` scoped to the shell NavEntry (survives section switches; mirrors the `HomeRoute`-scoped feed VMs): `loadFirstPage()` once on first Notifikasi composition; `reload()` for pull-to-refresh + retry; optimistic mark-read / mark-all-read mutating the local list, with revert on non-204/404 transport failure.

## 9. NotificationsScreen (`screens/notifications/`, commonMain)

- [x] 9.1 Add `NotificationsScreen` (navigation-free section body, NO back affordance): top-bar title (`notifications_title`); pull-to-refresh `LazyColumn`; the four states (loading/content/empty/error+retry) via `stringResource`; under `NearYouTheme`; zero hardcoded strings.
- [x] 9.2 Add the notification row composable: type-keyed generic-actor copy + `body_data` excerpts via `Res.string`; read/unread visual distinction; tolerate all 13 enum values + unknown `type` (generic fallback) + missing excerpt key (base copy), no crash; NEVER render `actor_user_id`/`target_id` UUIDs.
- [x] 9.3 Wire row tap → mark-read (optimistic; 204 success / 404 silent no-op / other → revert) with NO navigation to a post/reply/profile route (deep-link deferred — negative guard); add the "Tandai semua dibaca" action → read-all.

## 10. Notifikasi section wiring + unread badge (in the shell)

- [x] 10.1 Render `NotificationsScreen` as the Notifikasi section body.
- [x] 10.2 Add the unread badge on the Notifikasi `NavigationBarItem` (Material 3 `Badge`) shown when `count > 0`, sourced from `GET /api/v1/notifications/unread-count`, fetched on shell (re)composition/resume + on leaving the Notifikasi section; NO polling/push live updates. Badge `contentDescription` via `stringResource`. — one-shot `LaunchedEffect(Unit)` on composition + `DisposableEffect`-onDispose on leaving Notifikasi; `BadgedBox`+`Badge` shown when `count > 0`.

## 11. Koin wiring

- [x] 11.1 Register `NotificationsApiClient`, `NotificationsRepository` (+ `NotificationsFlow` bind) in `di/MobileModule.kt`; wire `NotificationsViewModel` resolution + the shell unread-count source. Reuse existing `HttpClient` / `Auth` singletons (no new client). — VM resolved via `viewModel { NotificationsViewModel(koinInject<NotificationsFlow>()) }` in the screen; shell unread-count via the same `NotificationsFlow` single.

## 12. Tests

- [x] 12.1 commonTest `NotificationsUiStateTest`: the pure outcome→state projection (loading / content / empty / error), deterministic. (8 tests)
- [x] 12.2 commonTest `NotificationsViewModelTest`: loads-once-on-construction + reload-on-refresh/retry over `FakeNotificationsFlow`; optimistic mark-read revert-on-failure. (10 tests)
- [x] 12.3 commonTest `NotificationsApiClientTest` (MockEngine): first-page request shape (no `cursor`), opaque-cursor pass-back verbatim, shipped-wire parse (all fields), `body_data` non-null + absent-`next_cursor` tolerance, and the **negative-regression** fixture (stale `unread_count`/`marked` do NOT populate; shipped `count`/`marked_read` do). (12 tests)
- [x] 12.4 commonTest `NotificationsRepositoryTest` (MockEngine): status→outcome mapping (200/400/5xx/IO) with no fallthrough; the no-PII-in-diagnostic assertion; mark-read `204` success + `404` no-op + transport-failure revert; read-all `{marked_read}`. (9 tests)
- [x] 12.5 Robolectric `NotificationsScreenTest` (androidUnitTest): initial render + all four states via `FakeNotificationsFlow`; mark-read-on-tap (204/404/revert); mark-all-read; read/unread visual; no-UUID-in-tree; unknown-`type` fallback; missing-`body_data` render. Add `FakeNotificationsFlow` to commonTest. (13 tests + `FakeNotificationsFlow`/`fakeNotification` in commonTest)
- [x] 12.6 Robolectric shell/host test (`AppShellScreenTest` / extended `HomeScreenTest`): the three bottom-nav sections + section switching; the three Home feed top-tabs + feed-tab switching; FAB on Home section only; Following + Profil placeholders; the Notifikasi badge show-at-`count>0` / hide-at-`0`; Notifikasi section renders `NotificationsScreen`. (`AppShellScreenTest`, 10 tests incl. the Profil-no-fetch MockEngine recorder; feed-tab switching stays in `HomeTabHostScreenTest`)
- [x] 12.7 commonTest: selected-`Section` + selected-feed-`Tab` saved-state round-trips; no-re-fetch-on-feed-tab-switch AND no-re-fetch-on-section-switch invariants via fakes. — `SectionSerializationTest` (new) + `TabSerializationTest` (existing) for the round-trips; the no-re-fetch Compose invariants live in `AppShellScreenTest` (section switch) + `HomeTabHostScreenTest` (feed-tab switch) per the codebase's androidUnitTest-for-Compose convention.
- [x] 12.8 Add `**/NotificationsScreenTest*` + the shell/host `*ScreenTest` glob to the `mobile/app/build.gradle.kts` Release-variant `tasks.withType<Test>()` exclude block (ui-test-manifest host activity is debug-only); confirm `:mobile:app:testDevReleaseUnitTest` passes. — added `**/NotificationsScreenTest*` + `**/AppShellScreenTest*`; `testDevReleaseUnitTest` passes.
- [x] 12.9 iOS flow test `mobile/app/src/iosTest/.../NotificationsFlowIosTest` mirroring `NearbyTimelineFlowIosTest` (CMP `runComposeUiTest`), with Kotlin/Native-legal test fn names (no `,` `(` `)` `#`). — `NotificationsFlowIosTest` (screen) + `AppShellFlowIosTest` (shell sections + badge); both pass on `iosSimulatorArm64Test`.
- [x] 12.10 If a source-scan/no-fetch-style guard test is added (Following/Profil no-fetch, deep-link no-nav), strip comments before the forbidden-token scan (per the `FollowingTabNoFetchScanTest` convention) so KDoc text doesn't trip it. — `NotificationsDeepLinkAbsenceScanTest` (comment-stripped, assembled needles) for the deep-link no-nav guard; Profil no-fetch via the `AppShellScreenTest` MockEngine recorder.

## 13. Follow-ups (`FOLLOW_UPS.md`)

- [x] 13.1 Add `mobile-notifications-deep-link-targets` (MODIFY the deep-link-deferred requirement once the in-flight `mobile-post-detail` screen lands AND a backend `GET /api/v1/posts/{id}` by-id endpoint exists).
- [x] 13.2 Add `mobile-notifications-actor-username-enrichment` (backend list-endpoint actor-username join over `visible_users` + mobile copy MODIFY to "{username} …").
- [x] 13.3 Add `in-app-notifications-spec-wire-reconciliation` (bucket b: reconcile the `in-app-notifications` spec prose with the shipped `NotificationRoutes.kt` — `count`/`marked_read`/`unread`/`204`/`not_found`/opaque-cursor/limit-clamp — mirroring PR #132; regular docs PR).
- [x] 13.4 Add `mobile-profile-section-screen` (MODIFY the Profil-placeholder requirement to the real profile/settings surface).
- [x] 13.5 Add `mobile-notifications-live-unread-badge` (live/push-driven badge beyond the one-shot fetch).
- [x] 13.6 Extend the existing `mobile-nearby-timeline-infinite-scroll` follow-up to also cover the notifications feed's deferred load-more (or add a sibling entry). — extended the existing entry to cover all three feeds (Nearby + Global + notifications).

## 14. Verification gate (pre-archive)

- [x] 14.1 Run the mobile gate locally: `./gradlew :mobile:app:testDevDebugUnitTest :mobile:app:testDevReleaseUnitTest` (flavor-qualified) + root `./gradlew ktlintCheck detekt`. (Worktree needs a copied `local.properties` SDK pointer.) — ALL GREEN (debug + release variants + ktlint + detekt; `local.properties` copied from the main worktree).
- [x] 14.2 Run `:mobile:app:iosSimulatorArm64Test` to confirm the iOS flow test passes (K/N actuals); verify via the test, not `:build`. — GREEN: 253 iOS tests, 0 failures (incl. `NotificationsFlowIosTest` + `AppShellFlowIosTest`).
- [ ] 14.3 Manual smoke (per `verify-loop`): launch the app (emulator + iOS sim), confirm the bottom-nav sections (Home/Notifikasi/Profil), the Home feed top-tabs, the Notifikasi list + badge + mark-read; screenshot. — DEFERRED to the user's discretion: the behaviors are comprehensively covered by the green Robolectric screen+shell tests (13 + 10) + iOS flow tests (`NotificationsFlowIosTest`/`AppShellFlowIosTest`); a device-level smoke is an optional confidence add-on (run `/verify` or `verify-loop` to do it). Surfaced in the apply summary — NOT silently skipped.
- [x] 14.4 No staging deploy / smoke step — pure mobile change, no backend/runtime impact, no Flyway migration (Section-6-style deploy tasks N/A; note in the archive commit body). — CONFIRMED N/A: zero backend / migration / new-library changes; consumes the already-shipped `/api/v1/notifications` read API.
- [ ] 14.5 Confirm #159 squash-merge ordering with the user before this change's final squash-merge; reconcile the shared `mobile-home-tab-host` spec + `HomeScreen`/`AppEntryProvider` if #159 landed first (including EDITING #159's now-stale `onOpenPost` call-site clause to the shell's Home-section wiring, not just appending the requirement). — PENDING (end-of-lifecycle, at squash-merge): #159 is still proposal-only/open; flagged for the user at archive/merge time.
