## 1. Pre-flight: ground in precedents + mockup (no code)

- [ ] 1.1 Read the precedent surfaces end-to-end: `admin/routes/AdminDeletionQueueRoute.kt` + `admin/deletionqueue/DeletionQueueRepository.kt` + `admin/ratelimit/DeletionQueueExpediteRateLimiter.kt` (closest analog: read list + worker-deferred write), and `admin/routes/AdminSubscriptionGraceRoute.kt` + `admin/subscriptiongrace/SubscriptionGraceRepository.kt` + `admin/ratelimit/GraceExpediteActionRateLimiter.kt` — capture the exact keyset/filter/escape/plain-GET, CSRF-before-role, required-reason, `hx-confirm`, audit-row, and rate-limit-acquire idioms to mirror.
- [ ] 1.2 Read the producer pipeline: `account/DataExportWorker.kt` (`execute()` + private `processOne`), `account/DataExportRequestRepository.kt` (`snapshotPending`, `claimPending`, `setReady`, `setFailed`), `account/DataExportWorkerRoute.kt`, `account/AccountDataExportRoutes.kt` — confirm the single-request seam shape before promoting it.
- [ ] 1.3 Render admin mockup **frame 16** (per `dev/mockups/README.md` step 4 headless-Chrome recipe) and generate its measurement annex (`dev/scripts/mockup-measure.sh nearyou-admin-mockup 16`); translate spacing/typography/tokens to the vendored admin CSS + Pebble idioms (docs/11 §3.6). On-demand only — do not commit the annex.
- [ ] 1.4 Confirm no schema work: `admin_actions_log.action_type` is free `VARCHAR(64)` (V16) → the `data_export_triggered` value needs no migration; the `data_export_requests` table + indexes already ship at V30. (No Flyway file in this change.)

## 2. Producer seam (account-data-export, MODIFIED)

- [ ] 2.1 Promote `DataExportWorker.processOne(requestId)` to a reusable single-request seam (e.g. `suspend fun processSingle(id): <outcome>`), preserving the exact claim → gather → serialize → upload → setReady → notify → email pipeline and the fail-soft (`setFailed` + `attempt_count++`) + claim-race (`SKIPPED`) semantics; refactor `execute()` to call it (no batch behavior change).
- [ ] 2.2 Unit-test the seam: claimable `pending` → `ready` (object key + notification); non-claimable (`processing`/`ready`) → no-op; gather/upload failure → `failed` + `attempt_count++`, no ready/notification; `execute()` still drains the pending set through the seam.

## 3. Admin read surface — GET /admin/data-exports

- [ ] 3.1 Add `admin/dataexportqueue/DataExportQueueRepository.kt`: parameterized keyset read (newest-`requested_at`-first; `(requested_at, id)` keyset), JOIN `users` for username, `status` + `q` (username/UUID) filters composing with AND, count summary. Projection **excludes** `r2_object_key` / signed-URL / `download_expires_at` values (identity + status only). Served by existing indexes — no new index.
- [ ] 3.2 Add `admin/routes/AdminDataExportQueueRoute.kt` `GET /admin/data-exports`: admin-auth-gated, any role; parse `status`/`q`/cursor; map DB status → frame-16 labels (`pending→QUEUED`, `processing→RUNNING`, `ready→DELIVERED`, `expired→EXPIRED`, `failed→FAILED`); delivered-via cell only for `ready`; HTML-escape all user text; HTMX fragment + plain-`GET` full-page fallback.
- [ ] 3.3 Add the Pebble template(s) for frame 16 (table + filters + count summary + per-row action cell + the info banner; username deep-links to `/admin/users?q=`), using the vendored admin CSS — no inline secrets, no contents/URL rendered.
- [ ] 3.4 Wire the repository + route into `AdminModule.kt` and the admin route registration; make the existing "Data export queue" nav item live.

## 4. Admin trigger action — POST /admin/data-exports/{id}/trigger

- [ ] 4.1 Add `admin/ratelimit/DataExportTriggerRateLimiter.kt`: distinct **10/admin/hour** bucket via the audit-trail-as-ledger COUNT pattern (mirror `DeletionQueueExpediteRateLimiter`), keyed on this admin + `action_type='data_export_triggered'`, independent of the 20/hr destructive budget.
- [ ] 4.2 Add the trigger handler in `AdminDataExportQueueRoute.kt`: gate order CSRF-before-role (mismatch → 403 + `admin_csrf_violation`, no mutation) → `role IN ('owner','admin')` → required non-empty reason → rate-limit acquire; `hx-confirm` on the control.
- [ ] 4.3 Implement the re-enqueue + single-request drive in the repository: for `failed`/`pending` targets, conditionally re-enqueue to `pending` honoring `data_export_requests_one_active_idx` (catch unique-violation → benign "already active" no-op), then invoke the producer `processSingle` seam; `processing`/`ready`/unknown → benign no-op.
- [ ] 4.4 Write exactly one immutable `admin_actions_log` row (`action_type='data_export_triggered'`, before/after status in `before_state`/`after_state`) + the state transition in one transaction on a successful trigger; **no** audit row on a rejected/no-op trigger. Use the shared `AdminAuditLogger`.

## 5. Tests (docs/13 placement; CI-equiv tag !network)

- [ ] 5.1 Route tests — read: paginated newest-first; status-label mapping for all 5 states; username deep-link; `status`/`q`/composed filters + count summary; plain-`GET` fallback; unauthenticated rejected; **no** `r2_object_key`/signed-URL/contents in any rendered response (identity-only PII).
- [ ] 5.2 Route tests — trigger: owner/admin re-runs a `failed` row → `ready` + one `data_export_triggered` audit row (before-status `failed`); stuck `pending` → processed immediately + audit row; missing/invalid CSRF → 403 + `admin_csrf_violation`, no mutation; non-owner/admin → rejected, no mutation; missing reason → rejected, no audit row; 11th-in-hour → rate-limited, no mutation; destructive 20/hr budget untouched by triggers.
- [ ] 5.3 Route tests — benign no-op: trigger on `processing` and on `ready`(valid link) → no mutation, no audit row; re-enqueue colliding with another active request → one-active unique-violation mapped to no-op (no unhandled exception, no partial mutation); unknown id → no-op, no audit row.
- [ ] 5.4 Ensure any new DB-tagged `*RoutesTest` pool `autoClose`s its `DataSource` (docs/11 §3.2 connection-budget invariant) and uses `truncatedTo(MICROS)` for any timestamp seed assertion (CI-Linux micros truncation).

## 6. Verify, reconcile docs, finalize

- [ ] 6.1 Run the pre-push gate locally: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` (both lint frameworks; fresh DB containers for the DB-tagged tests).
- [ ] 6.2 Verify-loop bring-up: launch the admin panel (admin bootstrap + TOTP), screenshot `/admin/data-exports` (list + filters) and exercise a trigger on a seeded `failed` row → observe it reach `DELIVERED`; capture evidence for the PR body (docs/11 §5 DoD, manual-verification evidence).
- [ ] 6.3 Reconcile docs/07 § Admin Panel → Data Export Queue bullet from "DEFERRED to #361" to shipped (admin surface live); note the action_type + endpoints. (Doc edit lands on this branch.)
- [ ] 6.4 On archive: close [#361](https://github.com/aditrioka/nearyou-id/issues/361); confirm `account-data-export` + `admin-data-export-queue` specs sync; no orphaned "deferred admin surface" references remain in docs/specs.
