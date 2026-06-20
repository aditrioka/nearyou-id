## 1. Pattern study & reconciliation

- [x] 1.1 Re-read the shipped internal-worker pattern before writing code: `PrivacyFlipWorkerRoute.kt` + `PrivacyFlipWorker.kt` (own-subtree OIDC gate, classified-500, single structured run log) and `SuspensionUnbanWorker.kt`; confirm the `internal-endpoint-auth` install helper used to gate a single subtree.
- [x] 1.2 Confirm the three target tables + serving indexes exist and the column names match the spec: `refresh_tokens` (`expires_at`, `last_used_at`, `revoked_at`; idx `refresh_tokens_expires_idx`, V2), `notifications` (`created_at`; idx `notifications_user_all_idx`, V10), `user_fcm_tokens` (`last_seen_at`; idx `user_fcm_tokens_last_seen_idx`, V14). No Flyway migration is added by this change.
- [x] 1.3 Confirm the FCM on-send `404/410` delete is already shipped (`infra/fcm/FcmDispatcher.kt` → `UserFcmTokenReader.deleteTokenIfStale`) and is NOT modified here — this change adds only the scheduled bulk stale sweep.

## 2. Repository (JDBC retention sweeps)

- [x] 2.1 Add `RetentionCleanupRepository` (interface + JDBC impl) on the pool-bounded DB dispatcher (`docs/11` §3.2 — never raw `Dispatchers.IO`), mirroring existing repositories' construction.
- [x] 2.2 Implement `deleteExpiredAndStaleRefreshTokens(): Int` — single `DELETE FROM refresh_tokens WHERE expires_at < NOW() - INTERVAL '1 day' OR last_used_at < NOW() - INTERVAL '90 days'` (not filtered by `revoked_at`), returning `executeUpdate()`.
- [x] 2.3 Implement `purgeOldNotifications(): Int` — `DELETE FROM notifications WHERE created_at < NOW() - INTERVAL '90 days'` (type-agnostic, not filtered by `read_at`).
- [x] 2.4 Implement `deleteStaleFcmTokens(): Int` — `DELETE FROM user_fcm_tokens WHERE last_seen_at < NOW() - INTERVAL '30 days'`.
- [x] 2.5 Each sweep is an independent statement on its own connection so one sweep's failure does not roll back a sibling's reclaimed rows (design D4).

## 3. Worker orchestration + structured logging

- [x] 3.1 Add `RetentionCleanupWorker` that runs the three sweeps sequentially, aggregates the per-sweep counts into a result type (`refreshTokensDeleted`, `notificationsDeleted`, `fcmTokensDeleted`), and measures run duration.
- [x] 3.2 Emit exactly one structured INFO log line per run: `event=retention_cleanup`, the three counts, and `duration_ms` (mirror the `privacy-flip-worker` single-log discipline; no per-row logging).
- [x] 3.3 No `admin_actions_log` writes and no `system` sentinel actor (design D3 — routine hygiene, not a user-visible state change).

## 4. Route: OIDC gate, response, error classification

- [x] 4.1 Add `RetentionCleanupRoutes` mounting `POST /internal/cleanup` with the internal-endpoint OIDC verifier installed on the `/cleanup` subtree ONLY (never the shared `/internal` node) — mirror `PrivacyFlipWorkerRoute`.
- [x] 4.2 On success respond `200` with `{"refresh_tokens_deleted": <int>, "notifications_deleted": <int>, "fcm_tokens_deleted": <int>}`.
- [x] 4.3 On any handler exception respond `500` with `{"error": "<timeout|connection_refused|unknown>"}`, logging the raw exception at WARN and never placing it in the response body (reuse the existing worker error-classification helper).

## 5. Wiring & DI

- [x] 5.1 Register the repository + worker in the backend DI/module wiring (same seam as the other workers).
- [x] 5.2 Mount `RetentionCleanupRoutes` in `Application.kt`'s `/internal/*` block alongside the existing unban / privacy-flip / hard-delete worker routes; verify it does not alter the sibling `/internal/revenuecat-webhook` auth path.

## 6. Tests

- [x] 6.1 Refresh-token sweep (DB-tagged route/repository test): expired-by-`expires_at` row deleted; stale-by-`last_used_at` row deleted; revoked-and-expired row deleted; still-valid recent token survives; **never-used token (`last_used_at IS NULL`) survives when `expires_at` is future, deleted when `expires_at` past** (locks the nullable-`last_used_at` NULL-comparison semantics). Use one row just inside the window and one just past; the strict `<` boundary is intentional — do NOT add an exact-`NOW()`-equality assertion (timing-flaky). New pool must `autoClose(hikari())` size 2 (CI connection budget).
- [x] 6.2 Notifications purge: 91-day-old row purged; 89-day-old survives; a `type='data_export_ready'` row >90d purged (type-agnostic). Truncate seed timestamps to micros (`truncatedTo(MICROS)`) or compare DB-read-to-DB-read to avoid the macOS-vs-Linux-CI clock false-fail.
- [x] 6.3 FCM stale sweep: 31-day-stale token deleted; 29-day token survives.
- [x] 6.4 Endpoint contract: a run with eligible rows in all three tables returns `200` with the three correct counts; a cold run against empty tables returns `200` with all counts `0`; full idempotency re-run (immediately after a run that deleted everything eligible) returns all-zero counts.
- [x] 6.5 Structured-log assertion: exactly one `event=retention_cleanup` INFO line with the three counts + duration.
- [x] 6.6 OIDC gate: unauthenticated `POST /internal/cleanup` → `401` and zero deletions; sibling-non-capture test (a `/internal/revenuecat-webhook` request is NOT `401`'d by this gate) mirroring the `privacy-flip-worker` scenario.
- [x] 6.7 Classified-500: a simulated DB timeout/connection failure yields `500` with `error ∈ {timeout, connection_refused, unknown}` and no raw exception text in the body.
- [x] 6.8 Deferred negative-guards: an expired-unconsumed `admin_webauthn_challenges` row is left untouched by the worker; a resolved `moderation_queue` row (and a resolved `reports` row) older than one year is left untouched (assert the worker deletes nothing from those tables).
- [x] 6.9 Any test that creates `notifications` / token rows cleans up after itself (per-test `afterTest` delete by a unique seed prefix) so it does not pollute sibling suites in the full multi-spec gate.

## 7. Docs & spec reconciliation (canonical alignment, same PR)

- [x] 7.1 Annotate `docs/05-Implementation.md` §112 (refresh tokens), §582 (notifications), §1120 (FCM tokens) with a `> **Status: shipped** (scheduled-retention-cleanup, 2026-06)` note pointing at `POST /internal/cleanup`, mirroring the existing privacy-flip-worker "Status: shipped" annotation at §241. Do NOT renumber any section (the §-numeric coordinates are frozen historical IDs).
- [x] 7.2 In the §112 note specifically, record the two explicit amendments (design D2): the sweep merges §112's two daily/weekly queries into one idempotent `OR` `DELETE` run daily, AND it also reaps revoked rows (`revoked_at` not in §112's wording). State the single-daily cadence in the §582/§1120 notes too so docs no longer imply a separate weekly schedule.
- [x] 7.3 **Fix the stale hard-delete references** — `docs/06-Security-Privacy.md` §386 ("Hard delete worker: Cloud Scheduler calls `/internal/cleanup` daily … reads `deletion_requests`") + §415, and `docs/08-Roadmap-Risk.md` Phase 3.5 item 6: repoint each to the **shipped** route `/internal/account-hard-delete-worker` so `/internal/cleanup` is no longer (mis)attributed to the hard-delete worker now that this change owns it for retention sweeps.
- [x] 7.4 Annotate `docs/04-Architecture.md` §463 (the "scheduled-cleanup `/internal/cleanup` job below is also DESIGN" sentence) + §482 (the "DESIGN: weekly `/internal/cleanup` job" FCM stale-token bullet) as **shipped** by this change (the FCM stale sweep is no longer DESIGN-only).
- [ ] 7.5 File a `follow-up` issue each for the two deferred sweeps (WebAuthn-challenge cleanup; moderation/reports 1-year archival), labelled `follow-up` + `backend`.
- [ ] 7.6 At `/opsx:archive`, ensure the new `openspec/specs/scheduled-retention-cleanup/spec.md` gets a real `## Purpose` (not the "TBD - created by archiving" placeholder — the known archive footgun); after archive, grep `openspec/specs` for any `TBD - created by archiving` + run `openspec validate --specs`. Confirm the two MODIFIED/RENAMED deltas (`fcm-token-registration`, `in-app-notifications`) merged cleanly into their canonical specs.

## 8. Verification & staging smoke

- [x] 8.1 Pre-push gate green locally: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test`.
- [ ] 8.2 Pre-archive staging branch deploy + smoke: unauthenticated `GET/POST /internal/cleanup` → `401`; authenticated invocation → `200` with the three counts (synthetic staging data only).
- [x] 8.3 Add the operator runbook line for provisioning the single daily Cloud Scheduler job hitting `POST /internal/cleanup` with a Google OIDC identity token (audience = internal-endpoint OIDC audience), mirroring the existing unban / privacy-flip / hard-delete schedules — no new secret slots.
