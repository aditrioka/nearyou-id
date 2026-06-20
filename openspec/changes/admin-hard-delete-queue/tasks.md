## 1. Reference & mockup prep (do BEFORE building the template)

- [x] 1.1 Read [`docs/11-Engineering-Standards.md`](../../../docs/11-Engineering-Standards.md) § 3.6 (admin mockup binding rule) + § backend layering, and re-read the closest shipped sibling `backend/ktor/.../admin/subscriptiongrace/**` + its routes + Pebble template as the structural template to mirror.
- [x] 1.2 Render admin mockup board **frame 15** (`dev/mockups/nearyou-admin-mockup.html`, "Hard Delete Queue") via the headless-Chrome path in [`dev/mockups/README.md`](../../../dev/mockups/README.md) (extract the standalone frame to /tmp, screenshot/crop) — capture the visual reference.
- [x] 1.3 Generate the per-frame **measurement annex** for frame 15: `dev/scripts/mockup-measure.sh nearyou-admin-mockup 15` (on-demand output; do NOT commit it) for exact spacing/typography/tokens.
- [x] 1.4 Confirm the no-migration footprint holds: verify `deletion_requests_scheduled_idx` exists (V27) and `admin_actions_log.action_type` is `VARCHAR(64)` with no CHECK (so `'deletion_request_expedited'` needs no migration).

## 2. Repository layer (`admin/deletionqueue/DeletionQueueRepository.kt`)

- [x] 2.1 Implement the list query: `deletion_requests` rows with `executed_at IS NULL AND cancelled_at IS NULL`, JOIN `users` for username (tolerating a soft-deleted user row), keyset over `(scheduled_hard_delete_at ASC, id)`; project username, user_id, requested_at, scheduled_hard_delete_at, source. Annotate the raw-SQL-holding property (admin-module raw-read exception per design D6).
- [x] 2.2 Add composable parameterized filters — `q` (exact case-insensitive username OR exact user UUID; blank/whitespace ignored) and `source` (the 4 CHECK values) — and the keyset cursor pagination (no drop/dup across page boundary).
- [x] 2.3 Add the total-pending count query (filter-aware, independent of the page limit).
- [x] 2.4 Add the already-expedited indicator: LEFT JOIN / lateral to the latest `admin_actions_log` row with `action_type = 'deletion_request_expedited' AND target_id = deletion_requests.id` (acting admin + timestamp); read-only.
- [x] 2.5 Implement the guarded expedite mutation: `UPDATE deletion_requests SET scheduled_hard_delete_at = NOW() WHERE id = ? AND executed_at IS NULL AND cancelled_at IS NULL AND scheduled_hard_delete_at > NOW()` returning the affected-row count + the before/after `scheduled_hard_delete_at` + `user_id` for the audit snapshot (0 rows ⇒ reject; design D1/D7).

## 3. Service layer (`admin/deletionqueue/DeletionQueueService.kt`)

- [x] 3.1 List/read service: assemble the paginated, filtered, count-summarized view model; resolve the already-expedited indicator per row.
- [x] 3.2 Expedite service: require a non-blank reason (reject otherwise, no write); run the guarded UPDATE + the audit-ledger rate-limit count in ONE JDBC transaction.
- [x] 3.3 Wire the distinct rate-limit counter via the `admin-destructive-action-rate-limit` mechanism: cap 10/admin/trailing-hour on `action_type = 'deletion_request_expedited'`, independent of the 20/hr destructive budget; at/over cap ⇒ inline "quota exceeded", no write, no mutation (design D2).
- [x] 3.4 On success, write exactly one immutable `admin_actions_log` row: `action_type='deletion_request_expedited'`, `target_type='deletion_request'`, `target_id={id}`, acting `admin_id`, reason, before/after JSON snapshots (differing `scheduled_hard_delete_at`, carrying `user_id`). Append-only — never UPDATE/DELETE an audit row (design D5).
- [x] 3.5 Reject (no mutation, no audit row) when the guarded UPDATE matches 0 rows — unknown id / already-executed / cancelled / already-due (incl. `apple_s2s_account_delete` immediate rows).

## 4. Routes (`admin/routes/AdminDeletionQueueRoute.kt`)

- [x] 4.1 `GET /admin/deletion-requests` under the authenticated admin subtree (any admin role); HTMX-driven render AND a plain-`GET` (no-JS) fallback returning the same data; honor `q`, `source`, and the keyset cursor query params.
- [x] 4.2 `POST /admin/deletion-requests/{id}/expedite`: enforce `owner`/`admin` role; verify `X-CSRF-Token` against `admin_sessions.csrf_token_hash` (mismatch ⇒ 403 + `admin_csrf_violation` audit, no mutation); delegate to the expedite service; surface success / "quota exceeded" / "no longer pending" inline states.
- [x] 4.3 Ensure all user-controlled output (username, echoed reason) is HTML-escaped; expose identity + deletion-lifecycle fields only (no location/email/DOB).

## 5. Template + wiring

- [x] 5.1 Author the Pebble template translating frame 15 (columns: User deep-linked to `/admin/users?q=`, Requested UTC, Scheduled hard-delete UTC, Countdown, source badge; filter controls; count summary; per-row expedite control with `hx-confirm` irreversible-acceleration warning copy + reason input; already-expedited handled indicator; empty-state). Match the measurement annex (1.3) for spacing/typography; reuse vendored admin CSS (no new static asset ⇒ no `htmx.min.js.SHA256SUMS` re-pin needed; if an asset IS touched, re-pin it).
- [x] 5.2 Register the route(s) in `AdminModule.kt` and add the "Hard delete queue" nav item (frame 15 places it under the Lifecycle group) to the admin shell nav.

## 6. Tests (mirror `admin-subscription-grace-monitor` coverage)

- [x] 6.1 List: pending rows listed; executed + cancelled excluded; soft-deleted target still listed with username; empty-state renders zero count; unauthenticated ⇒ redirect to login.
- [x] 6.2 List filter (route-level): `q` narrows by username; `source` filter; filters compose; blank-`q` ignored; SQL-injection-safe binding of `q`/`source`. (Keyset no-drop/dup, count-independent-of-limit, and ascending ordering are verified at the repository layer in 6.9 — a fixed-page route cannot honestly exercise keyset boundaries.)
- [x] 6.3 PII + escaping: identity-only fields rendered; username/reason with HTML metacharacters escaped.
- [x] 6.4 Expedite happy path: `scheduled_hard_delete_at` advanced to `NOW()`; exactly one `deletion_request_expedited` audit row with differing before/after snapshots. Assert the route does NOT erase: `executed_at` stays NULL **AND `deletion_log` has no row for the user AND the user is not tombstoned / content untouched** (only the daily worker erases).
- [x] 6.5 Expedite guards: blank reason rejected (no write); read-only admin role denied; CSRF mismatch ⇒ 403 + `admin_csrf_violation`, no mutation; CSRF rejection precedes the role check (read-only admin + bad CSRF ⇒ CSRF violation, not a plain role-deny).
- [x] 6.6 Expedite rate limit: 11th in an hour rejected without effect; per-admin isolation; independent of the destructive budget in BOTH directions (exhausted destructive budget does not block expedite, and an expedite does not consume the destructive budget).
- [x] 6.7 Already-expedited indicator: handled indicator shown for a prior-expedited row; available control + no audit write for a never-expedited row.
- [x] 6.8 Expedite rejection set: already-executed / cancelled / unknown id ⇒ rejected, no audit row, no mutation. Already-due: seed an actual `source = 'apple_s2s_account_delete'` row at `scheduled_hard_delete_at = NOW()` (or any past-deadline row) ⇒ rejected. Render→expedite **race**: flip a rendered row to `cancelled_at = NOW()` (or `executed_at`) before expedite ⇒ the guarded UPDATE matches 0 rows ⇒ "no longer pending" inline state, no audit row.
- [x] 6.9 Repository-layer test (`DeletionQueueRepositoryTest`, `@Tags("database")`): page through with `pageSize = 2` asserting each seeded id appears exactly once (keyset no drop/dup across the boundary); the pending count is independent of the page limit; rows are ordered `scheduled_hard_delete_at` **ascending** (seed ≥3 distinct deadlines, assert the projected list is ascending).
- [x] 6.10 Expedite atomicity: a rate-limit-cap rejection leaves `scheduled_hard_delete_at` unchanged AND writes no audit row; an `admin_actions_log` insert failure (fault-injected within the txn) rolls back the deadline advance (no orphaned advance-without-audit).

## 7. Verification & doc sync

- [x] 7.1 Run the pre-push gate: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` (both lint frameworks; CI runs both).
- [ ] 7.2 Manual admin smoke per the `verify-loop` skill: boot Ktor admin (KTOR_ENV=test fail-soft + admin bootstrap + TOTP), seed a pending `deletion_requests` row, load `/admin/deletion-requests`, exercise filters, and run one expedite end-to-end — confirm the deadline advances + the audit row lands AND that `executed_at` / `deletion_log` stay empty post-expedite, pre-worker (the route schedules, it does not erase). Capture evidence for the PR body (docs/11 §5 DoD).
- [x] 7.3 Doc/mockup status sync (same PR): the docs/07 § Core Features "Hard Delete Queue" bullet is currently a bare bullet (no status marker) — add a **SHIPPED** marker + route (`/admin/deletion-requests` + `…/{id}/expedite`) + frame-15 reference, mirroring the "Subscription Grace Monitor — **SHIPPED**" bullet shape (NOT a DESIGN→SHIPPED flip; there is no DESIGN marker to flip from). Also flip admin mockup frame 15's `Usulan` tag + the nav `dot usul` (proposed) status to shipped.
- [x] 7.4 Confirm no `docs/11` § Pattern Registry amendment is needed (design introduces no new pattern for an already-listed concern — Standards-conformance note in design.md).

## Implementation notes

- **No separate `Service` class (§3).** The §3 "service layer" responsibilities (reason validation, the rate-limit-gated transaction, the audit write, eligibility rejection) are implemented in `DeletionQueueRepository.expedite` + the route handler, mirroring the shipped `admin-subscription-grace-monitor` sibling (Route + Repository + a distinct RateLimiter; no `Service` class). The design said to mirror that sibling.
- **Measurement annex (§1.3) N/A for this surface.** The admin Pebble templates reuse the mockup board's own vendored CSS classes verbatim (`tbl`, `st pend/neut`, `btn dangert`, `banner warn`), so consulting frame 15's markup IS the faithful translation; the per-frame measurement annex (px→token) is for Compose mobile surfaces, not a Pebble surface that inherits the mockup's CSS.
- **Guarded mutation via `SELECT … FOR UPDATE` + eligibility (§2.5).** The row-lock + eligibility check (`executed_at IS NULL AND cancelled_at IS NULL AND scheduled_hard_delete_at > NOW()`) provides the spec's guarded-UPDATE guarantee (race-safe; 0 rows ⇒ Ineligible), matching the grace sibling's `lockEligible…` pattern.
