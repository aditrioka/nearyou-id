## 1. Webhook scheduling + clearing (`subscription-billing-webhook` MODIFY)

- [ ] 1.1 Extend `SubscriptionEventRepository.applyStatusReturningExists` (or add a sibling) so the EXPIRATION/PURCHASE/RENEWAL status `UPDATE … RETURNING` also returns `private_profile_opt_in` and the resulting `privacy_flip_scheduled_at`; on `EXPIRATION` set `privacy_flip_scheduled_at = CASE WHEN private_profile_opt_in THEN COALESCE(privacy_flip_scheduled_at, NOW() + INTERVAL '72 hours') ELSE privacy_flip_scheduled_at END`, and on `INITIAL_PURCHASE`/`RENEWAL` set `privacy_flip_scheduled_at = NULL`, all in the existing single transaction (no new statement / no read-before-write).
- [ ] 1.2 In `SubscriptionService.process`, when the EXPIRATION transition applied to a user with `private_profile_opt_in = TRUE`, emit a `privacy_flip_warning` notification in-tx via `NotificationEmitter.emit(actorUserId = null, targetType = null, targetId = null, type = PRIVACY_FLIP_WARNING, bodyData = {"privacy_flip_scheduled_at": <iso8601 deadline>})` — in addition to the existing `subscription_expired` emit — and dispatch its FCM push post-commit via `NotificationDispatcher.dispatch` (mirror the existing emitted-id path; a single EXPIRATION may now produce two post-commit dispatches).
- [ ] 1.3 Add `NotificationType.PRIVACY_FLIP_WARNING` (if absent) mapped to the catalog `privacy_flip_warning`; confirm the V10 CHECK already permits it (no migration).
- [ ] 1.4 Keep non-private EXPIRATION and all other event types behaviorally unchanged (status + `subscription_expired` only); `CANCELLATION`/`BILLING_ISSUE`/`GRANT` untouched.

## 2. Privacy-flip worker (`privacy-flip-worker` ADD)

- [ ] 2.1 Add `PrivacyFlipWorker` (mirror `SuspensionUnbanWorker`): single data-modifying CTE — `eligible AS (SELECT id, privacy_flip_scheduled_at, private_profile_opt_in FROM users WHERE privacy_flip_scheduled_at IS NOT NULL AND privacy_flip_scheduled_at <= NOW() AND deleted_at IS NULL FOR UPDATE)` → `flipped AS (UPDATE users SET private_profile_opt_in = FALSE, privacy_flip_scheduled_at = NULL FROM eligible WHERE … RETURNING id, eligible.privacy_flip_scheduled_at AS prev_deadline)` → `INSERT INTO admin_actions_log (admin_id, action_type, target_type, target_id, reason, before_state, after_state) SELECT '54b53072-540e-3eb8-b8e9-343e71f28176'::uuid, 'system_privacy_flip_applied', 'user', flipped.id::text, 'premium_lapsed_grace_elapsed', jsonb_build_object('private_profile_opt_in', true, 'privacy_flip_scheduled_at', flipped.prev_deadline), jsonb_build_object('private_profile_opt_in', false, 'privacy_flip_scheduled_at', null) FROM flipped RETURNING target_id`. Return `PrivacyFlipResult(flippedCount, flippedUserIds, durationMs)`; reuse the `SYSTEM_ACTOR_ID` constant.
- [ ] 2.2 Add `privacyFlipWorkerRoute` (mirror `UnbanWorkerRoute`): `route("/privacy-flip-worker") { install(InternalEndpointAuth) { verifier = oidcVerifier }; post { … } }` mounted under the existing `route("/internal")` block in `Application.kt`. Install the gate on the `/privacy-flip-worker` subtree ONLY (never the shared `/internal` node) so it does not capture `/internal/revenuecat-webhook`.
- [ ] 2.3 Handler: `200 {"flipped_count": N}` on success; reuse/extend `classifyHandlerError` for `500 {"error": "<timeout|connection_refused|unknown>"}`; structured INFO log `event=privacy_flip_applied flipped_count=… flipped_user_ids=[capped @ MAX_LOGGED_USER_IDS] duration_ms=…`; rethrow `CancellationException`.
- [ ] 2.4 Wire DI/construction in `Application.kt` (worker built with the app `DataSource`; route given the worker + `OidcTokenVerifier`).

## 3. Tests

- [ ] 3.1 Webhook: EXPIRATION for a private user schedules `NOW()+72h`, writes `privacy_flip_warning` (body_data carries `privacy_flip_scheduled_at`), still writes `subscription_expired`.
- [ ] 3.2 Webhook: EXPIRATION for a public user (`private_profile_opt_in = FALSE`) leaves `privacy_flip_scheduled_at` NULL, writes no `privacy_flip_warning`.
- [ ] 3.3 Webhook: a second EXPIRATION (distinct `revenuecat_event_id`) for an already-scheduled private user does NOT move the deadline (COALESCE keeps the earlier value).
- [ ] 3.4 Webhook: INITIAL_PURCHASE/RENEWAL clears a pending `privacy_flip_scheduled_at`; RENEWAL with already-NULL stays NULL (idempotent clear).
- [ ] 3.5 Worker: an elapsed-deadline private user is flipped (`private_profile_opt_in → FALSE`, `privacy_flip_scheduled_at → NULL`, counted) and writes one `system_privacy_flip_applied` audit row attributed to the system actor with correct before/after JSON.
- [ ] 3.6 Worker: an in-window (future deadline) row and a soft-deleted (`deleted_at IS NOT NULL`) elapsed row are both left untouched and uncounted.
- [ ] 3.7 Worker: a second run with no new elapsed rows returns `flipped_count = 0` and writes zero audit rows (idempotent); audit-INSERT failure rolls back the flip (atomic).
- [ ] 3.8 Routing isolation: the privacy-flip-worker OIDC gate returns `401` for an unauthenticated worker call but does NOT cause `/internal/revenuecat-webhook` to `401` (extend/mirror `InternalRoutingIsolationTest`).

## 4. Ops + verification

- [ ] 4.1 Add the hourly Cloud Scheduler job hitting `POST /internal/privacy-flip-worker` with an OIDC identity token (mirror the daily `unban-worker` job) to the deploy/ops config; note it in the PR body. Code is correct regardless of cadence.
- [ ] 4.2 Run the pre-push gate locally: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` (fresh DB containers per the full-gate recipe if DB-tagged tests are touched).
- [ ] 4.3 Manual verify (docs/11 §5 DoD): drive an EXPIRATION for a seeded private user → assert `privacy_flip_scheduled_at` set + `privacy_flip_warning` row + the admin `GET /admin/privacy-flips` monitor now shows the row IN_WINDOW; advance the deadline + invoke the worker → row flips public, audit row written, monitor empties. Capture evidence in the PR.

## 5. Docs reconciliation

- [ ] 5.1 File a `follow-up` issue for the `docs/02`:97 "busts the Redis profile cache" step (no-op today — profile reads uncached) so the cache-bust is wired if/when a profile read-cache lands.
- [ ] 5.2 At archive time, update the `subscription-billing-webhook` spec **Purpose** paragraph to drop "the 72h privacy-flip coupling" from its deferred list (this change un-defers it; the Purpose prose is not auto-synced by the requirement deltas and would otherwise read stale).
- [ ] 5.3 Confirm `docs/02` §"Privacy Downgrade Flow" + `docs/05` §"Privacy Flip Worker" status markers flip from DESIGN → shipped at archive time (the `/opsx:archive` doc-sync step), including removing the "No code today / not mounted" banners; flag the `privacy_flip_applied` (docs/08) vs `system_privacy_flip_applied` (code convention, matching `system_unban_applied`) wording as a loose-reference note, not a behavior change.
