## 1. Schema + read path (design D3, D4)

- [ ] 1.1 Add `backend/ktor/src/main/resources/db/migration/V40__notifications_type_appeal_decided.sql` — header comment (purpose, V36 precedent, additive, why no `IF EXISTS`) + one `ALTER TABLE notifications DROP CONSTRAINT notifications_type_check, ADD CONSTRAINT notifications_type_check CHECK (type IN (…14 values…))`; re-confirm V40 is still free on `origin/main` right before pushing
- [ ] 1.2 Add `APPEAL_DECIDED("appeal_decided")` to `NotificationType` (`core/data/.../NotificationRepository.kt`) and refresh its KDoc counts (13 → 14)

## 2. Admin decision emit (design D1, D2)

- [ ] 2.1 `AppealReviewRepository`: private `insertDecisionNotification(conn, userId, appealId, decision)` — raw `INSERT INTO notifications (user_id, type, target_type, target_id, body_data) VALUES (?, 'appeal_decided', 'appeal', ?, ?::jsonb)` with `body_data = {"decision": …}`; call it inside `approve` and `reject` AFTER `auditLogger.logAppeal*` and before `commit()` (so a failed insert provably rolls back the audit row too); update the class/function KDoc
- [ ] 2.2 Confirm no `PushCopy` case, no `NotificationDispatcher` call, and no `Application.kt` / `admin()` change in the diff

## 3. Mobile — notifications list (design D5, D7)

- [ ] 3.1 `:shared:resources` strings: `notif_appeal_approved`, `notif_appeal_rejected`, `notif_appeal_decided`, `appeal_approved_body_active`; reword `appeal_rejected_body` to be accurate whether or not the action is still in effect
- [ ] 3.2 `NotificationsUiState.kt`: `NotificationRow.appealDecision` projected from `body_data.decision` for `appeal_decided` rows only (string values only)
- [ ] 3.3 `NotificationsScreen.notificationCopy`: `appeal_decided` → decision-keyed copy (approved / rejected / neutral fallback); update KDoc
- [ ] 3.4 `NotificationNavigation.kt`: `"appeal"` target → `NotificationNavIntent.OpenAppeal` → `NotificationNavTarget.Appeal` (no fetch); `NotificationsViewModel.resolveNavTarget`'s `when (intent)` gains the `OpenAppeal` branch
- [ ] 3.5 `NotificationsScreen` hoisted `onOpenAppeal: () -> Unit = {}` consumed from the VM nav signal (the screen's `when` over `NotificationNavTarget`); `AppShellScreen` forwards it; `appEntryProvider` wires it to `backStack.add(AppealRoute)`
- [ ] 3.6 `PushTapNavigationEffect.push`: `NotificationNavTarget.Appeal -> add(AppealRoute)`
- [ ] 3.7 UI DoD pass: consult the screens-board Notifikasi frame (4) for the row copy register; run the `mobile-ui-foundation` checklist over the changed row copy + the approved-via-session appeal surface (both reuse shipped composables — no layout/spacing change, so the measurement annex is N/A; record that in the PR body)

## 4. Mobile — appeal screen signed-in read (design D6)

- [ ] 4.1 `AppealApiClient(client, sessionClient)`: `status(appealToken: String?)` — null token → `sessionClient` GET with no explicit `Authorization` header; `MobileModule` passes the shared `HttpClient` as `sessionClient`
- [ ] 4.2 `AppealFlow.status(appealToken: String?)` + `AppealRepository`: null token AND `TokenStore.read() == null` → `SessionExpired` with no request; null token with a session → the session client (KDoc: null = signed-in session)
- [ ] 4.3 `AuthRepository`: `SignInApiResult.Success` branch calls `appealSession.clear()`
- [ ] 4.4 `AppealViewModel.loadOnEntry`: read with `session.peek()` (nullable) instead of short-circuiting; `AppealStatus.Decided.viaSession`
- [ ] 4.5 `AppealScreen.DecidedSurface`: approved + `viaSession` → approved title + `appeal_approved_body_active`, no re-sign-in action; KDoc updates (screen + `entry<AppealRoute>` comment)

## 5. Tests (one per spec'd scenario)

- [ ] 5.1 `MigrationV40SmokeTest` (DB): V40 history row success; `appeal_decided` accepted; all 14 accepted; `post_shared` rejected 23514; pre-V40 rows survive re-applying the V40 DDL; exactly ONE CHECK constraint on `notifications` references `type`; SQL is additive (no DELETE/UPDATE/DROP TABLE)
- [ ] 5.2 `AppealReviewTest` (DB, pool `autoClose`): approve → one approved row with exact shape (`actor NULL`, `target_type='appeal'`, `target_id=P`, body `{"decision":"approved"}`); reject with reason → body exactly `{"decision":"rejected"}`; re-approve/re-reject → no extra row; forced notification-insert failure (the shipped `withFailingConstraint` NOT VALID CHECK fault injection — promoted from its 3 private copies to one shared admin test-support helper, since this is the 4th use) → appeal still pending, user still banned, no audit row; replace the "inserts no notifications row" negative guard and fix the KDoc/comment that claims FCM fires off rows
- [ ] 5.3 Read path: `appeal_decided` row returned by `GET /api/v1/notifications` + counted by unread-count (extend the existing notifications read-path DB test)
- [ ] 5.4 No-FCM guard: extend `FcmDispatchStructuralTest` — `admin/appealreview/**` and `admin/routes/AdminAppealReviewRoute.kt` reference no `NotificationDispatcher` / `FcmDispatcher`, and `PushCopy.kt` has no `appeal_decided` case
- [ ] 5.5 Mobile commonTest: `NotificationsUiStateTest` (`appealDecision` projection incl. `{"decision":1}`, `"foo"`, absent); `resolveNotificationNavIntent` appeal → `OpenAppeal`; resolver → `Appeal` with no fetch (`NotificationsViewModelNavTest`); `AppealRepositoryTest` (null token + session → session client, no explicit header; null token + no stored session → `SessionExpired`, zero requests; session 401 → `SessionExpired`; token path unchanged); `AppealViewModelTest` (flip the "no appeal token re-routes without any status read" test; no token → Decided(viaSession=true) for approved + rejected-with-reason; `Decided` equality updated for `viaSession`); `FakeAppealFlow` accepts a null token and records status calls; `AuthRepositoryTest` (sign-in success clears a held appeal token)
- [ ] 5.6 Robolectric: `NotificationsScreenTest` (approved / rejected / neutral copy, no UUID rendered), `NotificationsScreenNavTest` (tap → `onOpenAppeal` once, marks read), `AppealScreenTest` (approved via session → no re-sign-in action; approved via token → action present; rejected via session → title + body + reason, no action), `NotificationsScreenNavFreeScanTest` (the screen holds no `AppealRoute` reference; `AppShellScreen` passes `onOpenAppeal`)
- [ ] 5.7 iOS: `NotificationsFlowIosTest` stays green + one `appeal_decided` tap → `onOpenAppeal` case (K/N-legal camelCase name); run `:mobile:app:iosSimulatorArm64Test`

## 6. Docs

- [ ] 6.1 `docs/05-Implementation.md` § Notifications Schema: CHECK DDL (+ V40 note next to "V10 is the canonical authority"), catalog row `appeal_decided | Admin decided the user's appeal | NULL | appeal | {decision}` marked in-app only (no FCM)
- [ ] 6.2 `docs/02-Product.md` event-type list (14 values), `docs/03-UX-Design.md` notification copy for `appeal_decided`, `docs/08-Roadmap-Risk.md` risk-register row ("Enum expanded to 13 types" → 14 via V40)

## 7. Verification & lifecycle

- [ ] 7.1 Gate: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test :mobile:app:ktlintCheck :mobile:app:testDevDebugUnitTest :mobile:app:testDevReleaseUnitTest --no-daemon` against a fresh throwaway Postgres on :5434 (AxonFlow override per operator approval 2026-10-04)
- [ ] 7.2 verify-loop (local): seed an appellant + pending appeal, approve and reject via the local admin panel, confirm the `notifications` rows; Android emulator + iOS simulator screenshots of the notifications row copy and the tap → appeal screen (approved-via-session surface); evidence in the PR body
- [ ] 7.3 Pre-archive staging branch deploy + smoke: V40 history row `success` + exactly one `notifications` CHECK referencing `type`, containing `appeal_decided` (read-only Supabase MCP) + `/health/ready` 200
- [ ] 7.4 PR title/body current at each phase boundary; body carries `Closes #390`
