## 1. Pre-implementation checks

- [x] 1.1 Re-confirm zero-migration preconditions: `reserved_usernames` table + `reserved_usernames_protect_seed` + `reserved_usernames_set_updated_at` triggers exist (V3); `admin_app` has `SELECT/INSERT/UPDATE/DELETE` on `reserved_usernames` (provision-admin-app-staging.sh); `admin_actions_log.action_type` is a free `VARCHAR(64)` (no CHECK); latest migration on the branch base is V20 (this change adds none).
- [x] 1.2 Confirm signup's reserved-username check normalization (lowercase + exact match) so D9 add-normalization matches; if signup normalizes differently, align D9 before coding (design § Open Questions).
- [ ] 1.3 Render mockup **frame 21** (headless Chrome per the render rule) + generate the measurement annex (`dev/scripts/mockup-measure.sh nearyou-admin-mockup 21`) for spacing / typography / token mapping (docs/11 §3.6).
- [x] 1.4 Confirm no new library pin / no `libs.versions.toml` change → propose-time/pre-impl library re-check is N/A (skip).

## 2. Data layer (repository + rate limiter)

- [x] 2.1 `ReservedUsernameActionRateLimiter` in `admin/ratelimit/`, mirroring `DestructiveActionRateLimiter`: `countInTrailingHour(conn, adminId)` = COUNT over `admin_actions_log` for `admin_id`, `created_at > NOW() - INTERVAL '1 hour'`, `action_type IN ('reserved_username_added','reserved_username_edited','reserved_username_removed')`; `isAtOrOverCap(conn, adminId)` ≥ `RESERVED_USERNAME_ACTION_CAP = 100`; own-connection overload for the quota chip (D1).
- [x] 2.2 `ReservedUsernamesRepository` list query: keyset `(created_at DESC, username)` + `source` filter + case-insensitive substring `username` search; runs on the shared bounded JDBC dispatcher against the admin-app dataSource (D5, docs/11 §3.2).
- [x] 2.3 `addSingle` (one transaction): rate-limit pre-check → `INSERT ... ON CONFLICT (username) DO NOTHING` with `source='admin_added'`; map to sealed outcome `Added` / `AlreadyReserved`; on `Added` write the `reserved_username_added` audit row in the SAME transaction (D7).
- [x] 2.4 `bulkAdd` (one transaction): parse rows into `added` / `skipped_duplicate` / `skipped_invalid` buckets; if `count + |added| > 100` → roll back, return `RateLimited` (whole-bulk reject, D2); else INSERT the added rows + one `reserved_username_added` audit row each + COMMIT; return the three-bucket report.
- [x] 2.5 `editReason` (one transaction): rate-limit pre-check → load row (missing → `NotFound`); `seed_system` → `SeedProtected` (no mutation/audit); else `UPDATE reason` + `reserved_username_edited` audit row with `before_state.reason`/`after_state.reason` (D4, D7).
- [x] 2.6 `remove` (one transaction): rate-limit pre-check → load row (missing → `NotFound`); `seed_system` → `SeedProtected`; else `DELETE` + `reserved_username_removed` audit row with `before_state`; defensively catch the `reserved_usernames_protect_seed` trigger exception → `SeedProtected` (never a 5xx) (D4, D7).
- [x] 2.7 Sealed outcome types per op (`Added`/`AlreadyReserved`/`NotFound`/`SeedProtected`/`RateLimited` + bulk report); audit INSERT shares the op's `Connection` (atomic).
- [x] 2.8 Koin wiring in `AdminModule` (repository + rate limiter); reuse the existing admin-app dataSource + bounded dispatcher bindings.

## 3. Routes (`admin/routes/AdminReservedUsernamesRoute.kt`)

- [x] 3.1 `GET /admin/reserved-usernames` — any authenticated admin role; parse `source` / `q` / cursor; render `reserved-usernames.peb` (page) or `reserved-usernames-table.peb` (HX fragment).
- [x] 3.2 `POST /admin/reserved-usernames` (add single) — gate order: `AdminCsrfGate.validateCsrf` → `AdminRoleGate.requireWriteRole` → read body once via `formParametersAfterValidation` → validate/normalize `username` (lowercase + charset, 1..30) + `reason` (non-blank, **≤64 — matches the `reserved_usernames.reason VARCHAR(64)` column, so over-length is a 400 not a 22001 overflow 5xx**) → `addSingle`; map outcome to dual-mode response (D6, D9, D10).
- [x] 3.3 `POST /admin/reserved-usernames/bulk` — the CSV arrives as a **text/textarea form field** (read via the CSRF-gated `formParametersAfterValidation` path; **not** a `multipart/form-data` file upload — D8); gate order; enforce ≤1000 rows / ≤256 KB → 400 before parse; empty/header-only → empty report; `bulkAdd`; render the three-bucket report.
- [x] 3.4 `POST /admin/reserved-usernames/{username}/edit-reason` — gate order; parse `{username}` + `reason`; `editReason`; map outcome.
- [x] 3.5 `POST /admin/reserved-usernames/{username}/remove` — gate order; parse `{username}`; `remove`; map outcome.
- [x] 3.6 Mount the routes inside `authenticate(ADMIN_AUTH_NAME)` in `AdminModule`; English-only in-band message constants ("already reserved", "seed entry cannot be edited/removed", "not found", "quota exceeded (100/hour)", "would exceed your 100/hour quota").

## 4. UI (Pebble templates + nav, frame 21)

- [x] 4.1 `reserved-usernames.peb` (filter bar: `source` select + search; add-single form; CSV bulk-add **textarea** — paste `username,reason` lines, a text field not a file picker, D8; table) + `reserved-usernames-table.peb` (fragment) — vendored vanilla CSS tokens copied from the frame-21 `.frame` block; **HTML-escape** every `username`/`reason`; no-JS fallback forms carry the `_csrf` hidden field; responsive contract (frame 4b).
- [x] 4.2 Sidebar nav entry "Reserved usernames" in `AdminLayout` (frame 21 grouping) + `activePath = "/admin/reserved-usernames"`.
- [x] 4.3 Read-only "N/100 this hour" quota chip on the page (parity with the user-management destructive chip; drop if it complicates the fragment — design § Open Questions).
- [ ] 4.4 Apply the frame-21 measurement annex (spacing/type/token mapping) to the templates.

## 5. Tests (kotest; `@Tags("database")` for route/repository integration; new `*RoutesTest` `autoClose(hikari())` + size 2 per the CI connection budget)

- [x] 5.1 List renders newest-first `(created_at DESC, username)` with the keyset cursor.
- [x] 5.2 `source=admin_added` filter excludes `seed_system` rows.
- [x] 5.3 Substring search matches `username` case-insensitively.
- [x] 5.4 Rendered `username`/`reason` are HTML-escaped (XSS payload in `reason` not executable).
- [x] 5.5 Unauthenticated `GET` → 302 `/admin/login`.
- [x] 5.6 Add valid → `admin_added` row + exactly one `reserved_username_added` audit row (`target_type='reserved_username'`, `target_id=username`).
- [x] 5.7 Add duplicate → in-band "already reserved", no mutation, no audit row.
- [x] 5.8 Add blank `reason` → 400, no write.
- [x] 5.9 Add normalizes to lowercase; out-of-charset `username` → 400, no write.
- [x] 5.10 Bulk add: new rows inserted + duplicate reported (2 added, 1 skipped-duplicate).
- [x] 5.11 Bulk add: malformed row reported as skipped-invalid (with line no.) + valid row still added; batch not aborted.
- [x] 5.12 Bulk add: 3 inserted usernames → exactly 3 `reserved_username_added` audit rows.
- [x] 5.13 Bulk add: >1000 data rows → 400, no insert.
- [x] 5.14 Bulk add: would-exceed-cap (count 98 + 5 added) → whole-bulk rejected, no rows written, no audit, count holds.
- [x] 5.15 Edit reason on `admin_added` → updated + `reserved_username_edited` audit (before/after reason).
- [x] 5.16 Edit reason on `seed_system` → app-layer blocked, no mutation, no audit row.
- [x] 5.17 Edit reason on nonexistent username → in-band "not found", no mutation.
- [x] 5.18 Edit blank `reason` → 400, no write.
- [x] 5.19 Remove `admin_added` → deleted + `reserved_username_removed` audit (before_state).
- [x] 5.20 Remove `seed_system` → app-layer blocked, no mutation, no audit row.
- [x] 5.21 DB trigger backstop: direct `DELETE` of a `seed_system` row raises (docs Pre-Launch "reserved_usernames trigger test").
- [x] 5.22 DB trigger backstop: direct `UPDATE ... SET source='admin_added'` on a `seed_system` row raises.
- [x] 5.23 Rate limit: 100th write in the trailing hour succeeds + advances the count.
- [x] 5.24 Rate limit: at-cap write → in-band "quota exceeded (100/hour)", no mutation, no audit row, count holds.
- [x] 5.25 Rate limit: count includes only the three reserved action types in-window (suspends + out-of-window rows excluded).
- [x] 5.26 Rate limit: per-admin (admin B unaffected by admin A at the cap).
- [x] 5.27 CSRF: write without a valid token → 403 + `admin_csrf_violation` audit row, no mutation.
- [x] 5.28 Role: read-only-role admin on a write route → 403, no mutation.
- [x] 5.29 Gate order: CSRF-missing + malformed target → rejected at the CSRF gate before parsing/role/mutation.
- [x] 5.30 Add: case-variant of an existing username (`Admin` when `admin` exists) → normalized → in-band "already reserved", no mutation, no audit row.
- [x] 5.31 `reason` length boundary: 64-char reason accepted; 65-char reason → 400 (single) / `skipped_invalid` (CSV), no DB-overflow 5xx.
- [x] 5.32 Bulk: a `username` repeated within one upload → inserted once + exactly one `reserved_username_added` audit row + the 2nd occurrence reported skipped-duplicate (no phantom audit row).
- [x] 5.33 Bulk: empty / header-only submission → empty (0/0/0) report, no 400/5xx, no insert.
- [x] 5.34 Edit: a successful reason edit refreshes `updated_at` (V3 `reserved_usernames_set_updated_at` trigger) — the third clause of the docs/08 Pre-Launch "reserved_usernames trigger test".
- [x] 5.35 Bulk route gating: `POST /admin/reserved-usernames/bulk` without a valid CSRF token → 403 + `admin_csrf_violation`; read-only role → 403 (the gate order is exercised on the bulk route, not only single-add).

## 6. Verification & Definition of Done

- [x] 6.1 Local gates green: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` (no `:mobile:app` touched → no mobile test lanes).
- [ ] 6.2 Admin-UI bring-up via `verify-loop` (admin surface = local Ktor boot on :8080 + admin bootstrap + TOTP): exercise add → list → edit → remove → seed-remove-blocked → over-cap; **screenshot the frame-21-conformant `/admin/reserved-usernames` page into the PR body** before archive (docs/11 §5 DoD #3 — UI-affecting change).
- [ ] 6.3 Pre-archive staging branch deploy (`gh workflow run deploy-staging.yml --ref admin-reserved-usernames-editor`) + smoke `dev/scripts/smoke-admin-reserved-usernames-editor.sh` (unauthenticated `GET /admin/reserved-usernames` → 302 `/admin/login` baseline; authenticated add/list/edit/remove/seed-block if creds available); confirm `admin_app` write grants live on the smoke target (project.md § Staging deploy timing).

## 7. PR & docs hygiene

- [x] 7.1 At the first feat commit, retitle PR #294 `feat(admin): reserved-usernames editor …` + refresh the body to the in-progress shape (project.md hard rule).
- [x] 7.2 docs/11 § Pattern Registry: NO amendment expected (the rate limiter is a second instantiation of the registered audit-log-COUNT pattern, not a fork). If apply instead generalizes `DestructiveActionRateLimiter` into a shared parameterized component, amend docs/11 § Pattern Registry in the same PR.
- [x] 7.3 No README module-list change (no new module) and no docs/09 version entry (no library pin) — confirm both are untouched.
