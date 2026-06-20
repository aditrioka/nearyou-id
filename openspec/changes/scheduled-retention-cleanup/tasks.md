## 1. Pattern study & reconciliation

- [ ] 1.1 Re-read the shipped internal-worker pattern before writing code: `PrivacyFlipWorkerRoute.kt` + `PrivacyFlipWorker.kt` (own-subtree OIDC gate, classified-500, single structured run log) and `SuspensionUnbanWorker.kt`; confirm the `internal-endpoint-auth` install helper used to gate a single subtree.
- [ ] 1.2 Confirm the three target tables + serving indexes exist and the column names match the spec: `refresh_tokens` (`expires_at`, `last_used_at`, `revoked_at`; idx `refresh_tokens_expires_idx`, V2), `notifications` (`created_at`; idx `notifications_user_all_idx`, V10), `user_fcm_tokens` (`last_seen_at`; idx `user_fcm_tokens_last_seen_idx`, V14). No Flyway migration is added by this change.
- [ ] 1.3 Confirm the FCM on-send `404/410` delete is already shipped (`infra/fcm/FcmDispatcher.kt` → `UserFcmTokenReader.deleteTokenIfStale`) and is NOT modified here — this change adds only the scheduled bulk stale sweep.

## 2. Repository (JDBC retention sweeps)

- [ ] 2.1 Add `RetentionCleanupRepository` (interface + JDBC impl) on the pool-bounded DB dispatcher (`docs/11` §3.2 — never raw `Dispatchers.IO`), mirroring existing repositories' construction.
- [ ] 2.2 Implement `deleteExpiredAndStaleRefreshTokens(): Int` — single `DELETE FROM refresh_tokens WHERE expires_at < NOW() - INTERVAL '1 day' OR last_used_at < NOW() - INTERVAL '90 days'` (not filtered by `revoked_at`), returning `executeUpdate()`.
- [ ] 2.3 Implement `purgeOldNotifications(): Int` — `DELETE FROM notifications WHERE created_at < NOW() - INTERVAL '90 days'` (type-agnostic, not filtered by `read_at`).
- [ ] 2.4 Implement `deleteStaleFcmTokens(): Int` — `DELETE FROM user_fcm_tokens WHERE last_seen_at < NOW() - INTERVAL '30 days'`.
- [ ] 2.5 Each sweep is an independent statement on its own connection so one sweep's failure does not roll back a sibling's reclaimed rows (design D4).

## 3. Worker orchestration + structured logging

- [ ] 3.1 Add `RetentionCleanupWorker` that runs the three sweeps sequentially, aggregates the per-sweep counts into a result type (`refreshTokensDeleted`, `notificationsDeleted`, `fcmTokensDeleted`), and measures run duration.
- [ ] 3.2 Emit exactly one structured INFO log line per run: `event=retention_cleanup`, the three counts, and `duration_ms` (mirror the `privacy-flip-worker` single-log discipline; no per-row logging).
- [ ] 3.3 No `admin_actions_log` writes and no `system` sentinel actor (design D3 — routine hygiene, not a user-visible state change).

## 4. Route: OIDC gate, response, error classification

- [ ] 4.1 Add `RetentionCleanupRoutes` mounting `POST /internal/cleanup` with the internal-endpoint OIDC verifier installed on the `/cleanup` subtree ONLY (never the shared `/internal` node) — mirror `PrivacyFlipWorkerRoute`.
- [ ] 4.2 On success respond `200` with `{"refresh_tokens_deleted": <int>, "notifications_deleted": <int>, "fcm_tokens_deleted": <int>}`.
- [ ] 4.3 On any handler exception respond `500` with `{"error": "<timeout|connection_refused|unknown>"}`, logging the raw exception at WARN and never placing it in the response body (reuse the existing worker error-classification helper).

## 5. Wiring & DI

- [ ] 5.1 Register the repository + worker in the backend DI/module wiring (same seam as the other workers).
- [ ] 5.2 Mount `RetentionCleanupRoutes` in `Application.kt`'s `/internal/*` block alongside the existing unban / privacy-flip / hard-delete worker routes; verify it does not alter the sibling `/internal/revenuecat-webhook` auth path.

## 6. Tests

- [ ] 6.1 Refresh-token sweep (DB-tagged route/repository test): expired-by-`expires_at` row deleted; stale-by-`last_used_at` row deleted; revoked-and-expired row deleted; still-valid recent token survives (one row just inside the window survives, one just past is deleted). New pool must `autoClose(hikari())` size 2 (CI connection budget).
- [ ] 6.2 Notifications purge: 91-day-old row purged; 89-day-old survives; a `type='data_export_ready'` row >90d purged (type-agnostic). Truncate seed timestamps to micros (`truncatedTo(MICROS)`) or compare DB-read-to-DB-read to avoid the macOS-vs-Linux-CI clock false-fail.
- [ ] 6.3 FCM stale sweep: 31-day-stale token deleted; 29-day token survives.
- [ ] 6.4 Endpoint contract: a run with eligible rows in all three tables returns `200` with the three correct counts; full idempotency re-run returns all-zero counts.
- [ ] 6.5 Structured-log assertion: exactly one `event=retention_cleanup` INFO line with the three counts + duration.
- [ ] 6.6 OIDC gate: unauthenticated `POST /internal/cleanup` → `401` and zero deletions; sibling-non-capture test (a `/internal/revenuecat-webhook` request is NOT `401`'d by this gate) mirroring the `privacy-flip-worker` scenario.
- [ ] 6.7 Classified-500: a simulated DB timeout/connection failure yields `500` with `error ∈ {timeout, connection_refused, unknown}` and no raw exception text in the body.
- [ ] 6.8 Deferred negative-guards: an expired-unconsumed `admin_webauthn_challenges` row is left untouched by the worker; a resolved `moderation_queue` row (and a resolved `reports` row) older than one year is left untouched (assert the worker deletes nothing from those tables).
- [ ] 6.9 Any test that creates `notifications` / token rows cleans up after itself (per-test `afterTest` delete by a unique seed prefix) so it does not pollute sibling suites in the full multi-spec gate.

## 7. Docs reconciliation (canonical-doc alignment, same PR)

- [ ] 7.1 Annotate `docs/05-Implementation.md` §112 (refresh tokens), §582 (notifications), §1120 (FCM tokens) with a `> **Status: shipped** (scheduled-retention-cleanup, 2026-06)` note pointing at `POST /internal/cleanup`, mirroring the existing privacy-flip-worker "Status: shipped" annotation at §241. Do NOT renumber any section (the §-numeric coordinates are frozen historical IDs).
- [ ] 7.2 In those notes, record the single-daily cadence (design D2) as the as-built behavior so docs no longer imply a separate weekly schedule. File a `follow-up` issue each for the two deferred sweeps (WebAuthn-challenge cleanup; moderation/reports 1-year archival), labelled `follow-up` + `backend`.

## 8. Verification & staging smoke

- [ ] 8.1 Pre-push gate green locally: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test`.
- [ ] 8.2 Pre-archive staging branch deploy + smoke: unauthenticated `GET/POST /internal/cleanup` → `401`; authenticated invocation → `200` with the three counts (synthetic staging data only).
- [ ] 8.3 Add the operator runbook line for provisioning the single daily Cloud Scheduler job hitting `POST /internal/cleanup` with a Google OIDC identity token (audience = internal-endpoint OIDC audience), mirroring the existing unban / privacy-flip / hard-delete schedules — no new secret slots.
