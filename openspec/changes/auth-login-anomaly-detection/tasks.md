## 1. Detection repository (read over login_events)

- [x] 1.1 Add a `LoginAnomalyRepository` in `:backend:ktor` with a windowed detection query over `login_events`: return `(user_id, distinct_subnet_count)` for every user whose `COUNT(DISTINCT ip_subnet_24)` (NULL excluded) is strictly `> threshold` where `occurred_at > :windowStart` (the evaluation instant minus the 1h window), passed as a parameterized timestamp. Use a `PreparedStatement` with bound params only (no string interpolation); rely on the existing `(user_id, occurred_at DESC)` index; add NO index/migration.
- [x] 1.2 Define the threshold (`> 5` distinct subnets) and window length (1 hour) as named constants/config, not inline literals.

## 2. Sweep service (idempotent moderation_queue write)

- [x] 2.1 Add a `LoginAnomalyDetectionService` that captures one evaluation instant, calls the repository for flagged users, and for each inserts a `moderation_queue` row (`target_type='user'`, `target_id=user_id`, `trigger='anomaly_detection'`, `status='pending'`, non-PII `notes` = e.g. distinct-subnet count + window) via `INSERT … ON CONFLICT (target_type, target_id, trigger) DO NOTHING`.
- [x] 2.2 Make the per-user record step fail-soft: catch + log (PII-free) a single user's insert failure and continue the sweep; return a summary (evaluated/flagged/recorded counts).
- [x] 2.3 Enforce PII discipline: `notes` and all log lines carry no IP / `ip_subnet_24` / `identifier_hash` value (only counts + `user_id`). Mark the `moderation_queue` insert site with a one-line code comment recording the non-PII `notes` contract (the guard is a test, not a lint rule — the comment prevents a future-edit regression).

## 3. Internal worker route (OIDC-gated)

- [x] 3.1 Add `loginAnomalyCheckRoutes` mounting `POST /internal/login-anomaly-check`: `route("/login-anomaly-check") { install(InternalEndpointAuth){ verifier = oidcVerifier }; post { … } }` under the parent `route("/internal")` block — gate on the worker's OWN node only (mirror `RetentionCleanupRoutes`; do NOT install on the shared `/internal` node).
- [x] 3.2 Return `200 OK` with a JSON summary on success; on a thrown error return `500` with a sanitized `{"error": "<classification>"}` via the shared `classifyHandlerError` (no PII / exception leak).
- [x] 3.3 Wire the repository, service, and route into Koin + `Application.kt` `route("/internal")` mounting, reusing the existing `oidcTokenVerifier` (the same one feeding the cleanup / privacy-flip / hard-delete workers).

## 4. Cross-layer cohesion (admin visibility — declared-deferred)

- [x] 4.1 Confirm (and record in the PR body) that `/admin/reports` lists `reports` (not standalone `moderation_queue` rows) and the operational-dashboard anomaly banner is deferred — so no existing admin surface renders report-less `anomaly_detection` user rows; therefore the dedicated admin review surface is deferred per docs/12 §3 (spec already declares this). (Confirmed: `admin-report-queue` LATERAL-joins `moderation_queue` per-report only; the diff adds NO admin HTML/Pebble route — recorded in the PR body.)
- [x] 4.2 File a `follow-up` GitHub issue (labels `follow-up` + `admin`) for the admin anomaly-review surface, referencing the `admin-operational-dashboard` anomaly-spike-banner deferred follow-up as the natural home + this capability as its data source. (Filed: #438.)

## 5. Tests (Kotest; DB-tagged where they touch Postgres)

- [x] 5.1 Detection threshold boundary: 5 distinct subnets in-window → user NOT flagged; 6 → flagged. Include a `COUNT(DISTINCT)`-not-`COUNT(*)` guard: many rows repeating only 3 distinct subnets → count is 3 → NOT flagged.
- [x] 5.2 NULL-subnet exclusion: 5 non-NULL distinct + N NULL-subnet rows → count is 5 → NOT flagged.
- [x] 5.3 Window correctness: distinct-subnet rows older than the trailing hour are not counted. Include the boundary-spread sub-case: 4 distinct subnets in-window + 3 expired = 4 counted → NOT flagged (the single captured eval-instant makes this deterministic).
- [x] 5.4 Idempotency: two consecutive sweeps over a still-flagged user → exactly one `anomaly_detection` `moderation_queue` row (`ON CONFLICT DO NOTHING`). Add the accepted-trade-off guard: a pre-existing `status='resolved'` `anomaly_detection` row also suppresses re-enqueue and is left `resolved` (backs the "already-resolved anomaly row also suppresses re-enqueue" scenario).
- [x] 5.5 Recorded-row shape: a flagged user yields one row with `target_type='user'`, `trigger='anomaly_detection'`, `status='pending'`.
- [x] 5.6 No-PII discipline: the recorded `notes` AND the worker/service/repository log lines (captured via a test logger on both the success and error paths) contain no IP / `ip_subnet_24` value / identifier hash (backs the "no PII in notes" + "no subnet or IP value appears in logs" scenarios).
- [x] 5.7 Fail-soft sweep: with 3 flagged users where user #2's insert throws, users #1 and #3 are still recorded and the run does not 500 on that single error.
- [x] 5.8 Route OIDC gate: `POST /internal/login-anomaly-check` without a valid OIDC bearer → `401` AND a seeded flaggable user has NO `moderation_queue` row after the call (assert the sweep did not run); with a valid bearer → `200` + JSON summary.
- [x] 5.8a Empty sweep: with no users meeting the threshold → `200` + a zero-count summary AND no `moderation_queue` row is written (guards the summary-shape + no-spurious-write contract).
- [x] 5.9 Internal-routing isolation: the login-anomaly-check OIDC gate does not 401 a sibling internal route using a different auth scheme (extend / mirror `InternalRoutingIsolationTest`).
- [x] 5.10 Use deterministic test inputs against the seeded reference tables (per project.md Test-data conventions); DB-touching specs are `database`-tagged and the pool autoCloses per docs/11 §3.2 (CI connection-budget).
- [x] 5.11 Scope + output guard (backs the "computes only the subnet-spread signal / emits only the moderation_queue row / no Sentry-Slack alert" and "durable signal produced but no admin UI" negative-guard scenarios): assert the sweep's only persisted anomaly output is the `moderation_queue` row — it writes NO `reports` row and touches no other table for the anomaly — and that no Sentry/Slack/alert-dispatch dependency is wired into the service. (The "adds no admin HTML/Pebble route" half is verified structurally by the diff in task 4.1, not a runtime test.)

## 6. Pre-archive deploy + smoke

- [x] 6.1 Local gate: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` green. (ktlint/detekt/lint-rules ✅; the 17 new specs ✅. The full `:backend:ktor:test` against the shared local dev DB shows 3 pre-existing dev-DB-drift failures — `MigrationV15/16SmokeTest` assert `chat_messages.embedded_post_edit_id` is still-deferred, but the long-lived dev DB carries a newer-main post-edit-history migration absent from this branch; this change adds zero DDL/migration. Proven green on a fresh DB migrated from this branch: all 178 migration smoke tests pass — CI-equivalent.)
- [x] 6.2 Manual staging branch deploy (`gh workflow run deploy-staging.yml --ref auth-login-anomaly-detection`); poll the deploy run. (Run 28272316945 → conclusion=success.)
- [x] 6.3 Smoke the OIDC-gated worker against the branch deploy: `401` without a valid OIDC bearer (the safe, no-secret check); confirm the route is mounted and gated. Record evidence in the PR body. (`POST /internal/login-anomaly-check` → 401 `{"error":"missing_authorization"}`; `/internal/cleanup` → 401 baseline; nonexistent `/internal/*` → 404, proving the gate is route-specific. Recorded in PR body.)
- [ ] 6.4 Operator (human-required, surfaced in Preflight): create the Cloud Scheduler job invoking `POST /internal/login-anomaly-check` with an OIDC identity for the internal audience (mirroring `/internal/cleanup`). Not a code task — tracked separately; does NOT block the squash-merge. (Operator-pending; surfaced in the PR body + preflight. Left unchecked because the Scheduler job is the operator's action, not a code change in this PR.)
