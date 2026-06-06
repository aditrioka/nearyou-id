## 1. Pre-implementation grounding

- [ ] 1.1 Re-confirm the SHIPPED notifications wire is unchanged at impl time: re-read `backend/ktor/src/main/kotlin/id/nearyou/app/notifications/NotificationRoutes.kt` and verify the DTO field names + status/error codes in `design.md` D2 still hold (opaque `next_cursor`, `body_data` non-null, `{count}`, `{marked_read}`, `204`/`404 not_found`, `unread=` param). If drifted, update D2 + the spec before coding.
- [ ] 1.2 Re-read the `GlobalTimeline*` seam files (`timeline/GlobalTimelineApiClient.kt`, `GlobalTimelineRepository.kt`, `GlobalTimelineFlow.kt`, `screens/timeline/GlobalTimelineViewModel.kt`, `GlobalTimelineUiState.kt`, `GlobalTimelineScreen.kt`) + their tests as the copy-adapt template; confirm the `viewModel { }` NavEntry-decorator + `SessionIdProvider`/`Auth`-plugin conventions.

## 2. Strings & resources (`:shared:resources`)

- [ ] 2.1 Add Bahasa Indonesia strings to `:shared:resources` (`Res.string`): `notifications_title` ("Notifikasi"), `notifications_loading`, `notifications_empty` ("Belum ada notifikasi"), `notifications_open` (bell `contentDescription`), `notifications_mark_all_read` ("Tandai semua dibaca"), plus type-keyed copy `notif_post_liked` / `notif_post_replied` / `notif_followed` / `notif_post_auto_hidden` / `notif_chat_message` / `notif_generic` (generic-actor copy per `design.md` D4). Reuse existing `signin_error_network` + `cta_retry`.
- [ ] 2.2 Verify the strings are accessible via the generated `Res` accessor on all targets (no hardcoded literals will be needed in the screen).

## 3. Networking layer (`notifications/` package, commonMain)

- [ ] 3.1 Add `NotificationsApiClient` issuing `GET /api/v1/notifications` (first page omits `cursor`; subsequent pages pass the opaque `next_cursor` verbatim as `cursor=`; Bearer via the shipped `Auth` plugin — not reimplemented).
- [ ] 3.2 Define `@Serializable` DTOs from the SHIPPED wire (`design.md` D2): `NotificationDto` (bare `id`/`type`; `@SerialName` snake `actor_user_id`/`target_type`/`target_id`/`body_data`(non-null `JsonElement`)/`created_at`/`read_at`), `NotificationListResponse` (`items`, `@SerialName("next_cursor") nextCursor: String? = null`). Reuse the shared `Json` (`ignoreUnknownKeys`, `explicitNulls=false`).
- [ ] 3.3 Add the unread-count client call + DTO `{ count: Long }` and the read-all client call + DTO `{ marked_read: Int }` and the mark-read call (`PATCH /{id}/read` → 204 success / 404 no-op).

## 4. Repository, outcome mapping, flow seam (commonMain)

- [ ] 4.1 Add sealed `NotificationsOutcome` (`Loaded(items, nextCursor)` / `Error` / `NetworkError`) and `NotificationsRepository` mapping HTTP status → outcome with NO generic fallthrough (200→Loaded; 400→Error; 5xx/IO→NetworkError; 401 delegated to `Auth`).
- [ ] 4.2 Add the `NotificationsFlow` interface and bind `single<NotificationsFlow> { get<NotificationsRepository>() }`; expose mark-read / mark-all-read pass-throughs on the repository.

## 5. State projection + ViewModel (commonMain)

- [ ] 5.1 Add Compose-free `NotificationsUiState` (Loading / Content(rows) / Empty / Error) + pure `notificationsUiState(outcome, inFlight)` projection (no PII; deterministically unit-testable).
- [ ] 5.2 Add `NotificationsViewModel` (resolved via `viewModel { }` scoped to the `NotificationsRoute` NavEntry): `loadFirstPage()` once on construction; `reload()` for pull-to-refresh + retry; optimistic mark-read / mark-all-read mutating the local list.

## 6. Screen (`screens/notifications/`, commonMain)

- [ ] 6.1 Add `NotificationsScreen`: top-bar title (`notifications_title`) + back affordance; pull-to-refresh `LazyColumn`; the four states (loading / content / empty / error+retry) all via `stringResource`; under `NearYouTheme`; zero hardcoded UI strings.
- [ ] 6.2 Add the notification row composable: type-keyed generic-actor copy + `body_data` excerpts via `Res.string`; read/unread visual distinction; tolerate all 13 enum values + unknown `type` (generic fallback, no crash); NEVER render `actor_user_id`/`target_id` UUIDs.
- [ ] 6.3 Wire row tap → mark-read (optimistic; 204 success / 404 silent no-op) with NO navigation to a post/reply/profile route (deep-link deferred — negative guard); add the "Tandai semua dibaca" action → read-all.

## 7. Navigation + HomeScreen entry-point

- [ ] 7.1 Add a `NotificationsRoute` `NavKey` (`screens/routing/NavKeys.kt`) + map it in the root `entryProvider` to `NotificationsScreen` (root back stack, overlays the tab bar).
- [ ] 7.2 Add the bell `IconButton` (icon + `contentDescription` via `notifications_open`) at the `HomeScreen` level invoking an injected `onOpenNotifications` that pushes `NotificationsRoute` onto the root back stack (mirroring the composer FAB). Keep the `HomeScreen.kt` edit minimal/isolated (overlap with the in-flight `mobile-post-detail-screen` session — `design.md` D3).
- [ ] 7.3 Add the one-shot unread badge: fetch `GET /api/v1/notifications/unread-count` on Home (re)composition/resume + on return from `NotificationsScreen`; show the badge only when `count > 0`; NO polling/push live updates.

## 8. Koin wiring

- [ ] 8.1 Register `NotificationsApiClient`, `NotificationsRepository` (+ `NotificationsFlow` bind) in `di/MobileModule.kt`; wire `NotificationsViewModel` resolution + the Home unread-count source. Reuse existing `HttpClient` / `Auth` singletons (no new client).

## 9. Tests

- [ ] 9.1 commonTest `NotificationsUiStateTest`: the pure outcome→state projection (loading / content / empty / error), deterministic.
- [ ] 9.2 commonTest `NotificationsApiClientTest` (MockEngine): first-page request shape (no `cursor`), opaque-cursor pass-back verbatim, shipped-wire parse (all fields), `body_data` non-null + absent-`next_cursor` tolerance, and the **negative-regression** fixture (stale `unread_count`/`marked` do NOT populate; shipped `count`/`marked_read` do).
- [ ] 9.3 commonTest `NotificationsRepositoryTest` (MockEngine): status→outcome mapping (200/400/5xx/IO) with no fallthrough; mark-read `204` success + `404` no-op; read-all `{marked_read}`.
- [ ] 9.4 Robolectric `NotificationsScreenTest` (androidUnitTest): initial render + all four states via `FakeNotificationsFlow`; mark-read-on-tap; mark-all-read; read/unread visual; no-UUID-in-tree assertion. Add `FakeNotificationsFlow` to commonTest.
- [ ] 9.5 Add `**/NotificationsScreenTest*` to the `mobile/app/build.gradle.kts` Release-variant `tasks.withType<Test>()` exclude block (ui-test-manifest host activity is debug-only); confirm `:mobile:app:testDevReleaseUnitTest` passes.
- [ ] 9.6 iOS flow test `mobile/app/src/iosTest/.../NotificationsFlowIosTest` mirroring `NearbyTimelineFlowIosTest` (CMP `runComposeUiTest`), with Kotlin/Native-legal test fn names (no `,` `(` `)` `#`).
- [ ] 9.7 If a source-scan/no-fetch-style guard test is added, strip comments before the forbidden-token scan (per the `FollowingTabNoFetchScanTest` convention) so KDoc text doesn't trip it.

## 10. Follow-ups (`FOLLOW_UPS.md`)

- [ ] 10.1 Add `mobile-notifications-deep-link-targets` (MODIFY the "Tapping a row marks it read; deep-link navigation is deferred" requirement to wire navigation once post-detail/profile screens exist).
- [ ] 10.2 Add `mobile-notifications-actor-username-enrichment` (backend list-endpoint actor-username join over `visible_users` + mobile copy MODIFY to "{username} …").
- [ ] 10.3 Add `in-app-notifications-spec-wire-reconciliation` (bucket b: reconcile the `in-app-notifications` spec prose with the shipped `NotificationRoutes.kt` — `count`/`marked_read`/`unread`/`204`/`not_found`/opaque-cursor/limit-clamp — mirroring the timeline reconciliation PR #132; regular docs PR, not OpenSpec).
- [ ] 10.4 Extend the existing `mobile-nearby-timeline-infinite-scroll` follow-up to cover the notifications feed's deferred load-more (or add a sibling entry).
- [ ] 10.5 Add `mobile-notifications-live-unread-badge` (live/push-driven badge updates beyond the one-shot fetch).

## 11. Verification gate (pre-archive)

- [ ] 11.1 Run the mobile gate locally: `./gradlew :mobile:app:testDevDebugUnitTest :mobile:app:testDevReleaseUnitTest` (flavor-qualified) + root `./gradlew ktlintCheck detekt`. (Worktree needs a copied `local.properties` SDK pointer.)
- [ ] 11.2 Run `:mobile:app:iosSimulatorArm64Test` to confirm the iOS flow test passes (K/N actuals); verify via the test, not `:build`.
- [ ] 11.3 Manual smoke (optional, per `verify-loop`): harness `NotificationsScreen` / the bell into the running app (emulator or staging-flavor device) and confirm list/empty/error render + mark-read; revert any harness before commit.
- [ ] 11.4 No staging deploy / smoke step — pure mobile change, no backend/runtime impact, no Flyway migration (Section 6-style deploy tasks are N/A; note in the archive commit body).
