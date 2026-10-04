## 1. Schema + read path (design D3, D4)

- [ ] 1.1 Add `backend/ktor/src/main/resources/db/migration/V40__notifications_type_appeal_decided.sql` — header comment (purpose, V36 precedent, additive, re-runnable) + one `ALTER TABLE notifications DROP CONSTRAINT IF EXISTS notifications_type_check, ADD CONSTRAINT notifications_type_check CHECK (type IN (…14 values…))`; re-confirm V40 is still free on `origin/main` right before pushing
- [ ] 1.2 Add `APPEAL_DECIDED("appeal_decided")` to `NotificationType` (`core/data/.../NotificationRepository.kt`) and refresh its KDoc counts (13 → 14)

## 2. Admin decision emit (design D1, D2)

- [ ] 2.1 `AppealReviewRepository`: private `insertDecisionNotification(conn, userId, appealId, decision)` — raw `INSERT INTO notifications (user_id, type, target_type, target_id, body_data) VALUES (?, 'appeal_decided', 'appeal', ?, ?::jsonb)` with `body_data = {"decision": …}`; call it inside `approve` and `reject` after the guarded `UPDATE appeals`, before `commit()`; update the class/function KDoc
- [ ] 2.2 Confirm no `PushCopy` case, no `NotificationDispatcher` call, and no `Application.kt` / `admin()` change in the diff

## 3. Mobile — notifications list (design D5, D7)

- [ ] 3.1 `:shared:resources` strings: `notif_appeal_approved`, `notif_appeal_rejected`, `notif_appeal_decided`, `appeal_approved_body_active`
- [ ] 3.2 `NotificationsUiState.kt`: `NotificationRow.appealDecision` projected from `body_data.decision` for `appeal_decided` rows only
- [ ] 3.3 `NotificationsScreen.notificationCopy`: `appeal_decided` → decision-keyed copy (approved / rejected / neutral fallback); update KDoc
- [ ] 3.4 `NotificationNavigation.kt`: `"appeal"` target → `NotificationNavIntent.OpenAppeal` → `NotificationNavTarget.Appeal` (no fetch)
- [ ] 3.5 `NotificationsScreen` hoisted `onOpenAppeal: () -> Unit = {}` consumed from the VM nav signal; `AppShellScreen` forwards it; `appEntryProvider` wires it to `backStack.add(AppealRoute)`
- [ ] 3.6 `PushTapNavigationEffect.push`: `NotificationNavTarget.Appeal -> add(AppealRoute)`

## 4. Mobile — appeal screen signed-in read (design D6)

- [ ] 4.1 `AppealApiClient(client, sessionClient)`: `status(appealToken: String?)` — null token → `sessionClient` GET with no explicit `Authorization` header; `MobileModule` passes the shared `HttpClient` as `sessionClient`
- [ ] 4.2 `AppealFlow.status(appealToken: String?)` + `AppealRepository` pass-through (KDoc: null = signed-in session)
- [ ] 4.3 `AppealViewModel.loadOnEntry`: read with `session.peek()` (nullable) instead of short-circuiting; `AppealStatus.Decided.viaSession`
- [ ] 4.4 `AppealScreen.DecidedSurface`: approved + `viaSession` → approved title + `appeal_approved_body_active`, no re-sign-in action; KDoc updates (screen + `entry<AppealRoute>` comment)

## 5. Tests (one per spec'd scenario)

- [ ] 5.1 `MigrationV40SmokeTest` (DB): V40 history row success; `appeal_decided` accepted; all 14 accepted; `post_shared` rejected 23514; pre-V40 rows survive re-applying the V40 DDL; SQL is additive (no DELETE/UPDATE/DROP TABLE)
- [ ] 5.2 `AppealReviewTest` (DB, pool `autoClose`): approve → one approved row with exact shape (`actor NULL`, `target_type='appeal'`, `target_id=P`, body `{"decision":"approved"}`); reject with reason → body exactly `{"decision":"rejected"}`; re-approve/re-reject → no extra row; forced notification-insert failure → appeal still pending, user still banned, no audit row; replace the "inserts no notifications row" negative guard
- [ ] 5.3 Read path: `appeal_decided` row returned by `GET /api/v1/notifications` + counted by unread-count (extend the existing notifications read-path DB test)
- [ ] 5.4 No-FCM guard (DB-free, in `AppealReviewTest`): reflection asserts no `AppealReviewRepository` constructor takes a `NotificationDispatcher` (FCM dispatch is call-site driven, so the decision path cannot push without one) — fails the moment a dispatcher is threaded in
- [ ] 5.5 Mobile commonTest: `NotificationsUiStateTest` (`appealDecision` projection), `resolveNotificationNavIntent` appeal → `OpenAppeal`, resolver → `Appeal` target with no fetch (`NotificationsViewModelNavTest`), `AppealRepositoryTest` (null token → session client, no explicit header; token → raw client), `AppealViewModelTest` (no token → session read → Decided(viaSession=true); no token + 401 → SessionRedirect; token path unchanged)
- [ ] 5.6 Robolectric: `NotificationsScreenTest` (approved / rejected / neutral copy, no UUID rendered), `NotificationsScreenNavTest` (tap → `onOpenAppeal` once, marks read), `AppealScreenTest` (approved via session → no re-sign-in action; approved via token → action present)
- [ ] 5.7 iOS: `NotificationsFlowIosTest` stays green + one `appeal_decided` tap → `onOpenAppeal` case; run `:mobile:app:iosSimulatorArm64Test`

## 6. Docs

- [ ] 6.1 `docs/05-Implementation.md` § Notifications Schema: CHECK DDL (+ V40 note) and catalog row `appeal_decided | Admin decided the user's appeal | NULL | appeal | {decision}`
- [ ] 6.2 `docs/02-Product.md` event-type list (14 values) + `docs/03-UX-Design.md` notification copy for `appeal_decided`

## 7. Verification & lifecycle

- [ ] 7.1 Gate: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test :mobile:app:ktlintCheck :mobile:app:testDevDebugUnitTest :mobile:app:testDevReleaseUnitTest --no-daemon` against a fresh throwaway Postgres on :5434
- [ ] 7.2 verify-loop (local): seed an appellant + pending appeal, approve and reject via the local admin panel, confirm the `notifications` rows; Android emulator + iOS simulator screenshots of the notifications row copy and the tap → appeal screen (approved-via-session surface); evidence in the PR body
- [ ] 7.3 Pre-archive staging branch deploy + smoke: V40 history row `success` + `pg_constraint` includes `appeal_decided` (read-only Supabase MCP) + `/health/ready` 200
- [ ] 7.4 PR title/body current at each phase boundary; body carries `Closes #390`
