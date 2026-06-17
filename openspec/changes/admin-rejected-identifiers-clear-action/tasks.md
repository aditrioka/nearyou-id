## 1. Repository layer (`AdminRejectedIdentifiersRepository`)

- [x] 1.1 Add a `clear(id, actingAdminId, reason)` method that, in ONE transaction: `SELECT ... FOR UPDATE` (or conditional `DELETE ... RETURNING`) the target row to capture `identifier_hash`/`identifier_type`/`reason`/`rejected_at` for `before_state`, hard-`DELETE` it, and INSERT one `admin_actions_log` row (`action_type = 'rejected_identifier_cleared'`, `target_type = 'rejected_identifier'`, `target_id = id`, acting admin, reason, `before_state` JSONB, `after_state = NULL`). Return a result distinguishing applied / not-found / rate-capped.
- [x] 1.2 Add a `countClearsInTrailingHour(adminId)` read sourced from `admin_actions_log` (`action_type = 'rejected_identifier_cleared'` AND `created_at > NOW() - INTERVAL '1 hour'` AND `admin_id = ?`), invoked inside the same transaction as 1.1 (soft cap, no `FOR UPDATE` on the ledger).
- [x] 1.3 Enforce the 10/hour cap inside `clear(...)`, but ONLY for a row that actually exists: resolve not-found FIRST (a stale/already-cleared id is a graceful no-op that does NOT consume quota), then if the in-transaction count is `>= 10`, abort the transaction with no delete and no audit insert and signal "quota exceeded".
- [x] 1.4 All SQL parameterized (no string interpolation); reuse the viewer repo's `DataSource`/`Connection` discipline. Keep `before_state` serialization consistent with the existing `admin_actions_log` writers.

## 2. Route handler (`AdminRejectedIdentifiersRoute`)

- [x] 2.1 Wire `POST /admin/rejected-identifiers/{id}/clear` INSIDE the existing `authenticate(ADMIN_AUTH_NAME)` block; leave the collection `GET` untouched and the bare collection `POST` unmapped (405).
- [x] 2.2 Enforce the gates in order: session (already by the auth block) → CSRF (`X-CSRF-Token` vs `admin_sessions.csrf_token_hash`; mismatch → 403 + `admin_csrf_violation` audit, no write) → role via `AdminRoleGate.requireOwnerOrAdmin` (the same `owner`/`admin`-only helper chat-redaction uses — NOT the broader `requireWriteRole`, which also admits `moderator`; `moderator`/`read_only` rejected) → parse.
- [x] 2.3 Validate `{id}` is a UUID (else 400, no write) and `reason` is non-blank + length-bounded (else inline validation rejection, no write) — server-side, before any DB write.
- [x] 2.4 Call `repository.clear(...)`; map results: applied → success (HTMX row-swap / no-JS 303 redirect preserving filters); not-found/already-cleared → graceful "already removed" inline state (not 5xx); quota-exceeded → inline "quota exceeded" state (not 5xx).
- [x] 2.5 Confirm the new `'rejected_identifier_cleared'` literal is NOT added to the `admin-destructive-action-rate-limit` destructive set (it is restorative; dedicated cap only — design.md D1).

## 3. Templates (Pebble)

- [x] 3.1 Render a per-row clear control (form with hidden CSRF field + required reason input + destructive-confirm affordance) in the rejected-identifiers table/fragment — ONLY when the session role is `owner`/`admin` (pass the role flag into the template context).
- [x] 3.2 HTMX: clear posts via `hx-post` targeting the row with `outerHTML` swap on success; plain-`POST` no-JS path 303-redirects back to the filter-preserving listing.
- [x] 3.3 Ensure all dynamic values stay HTML-escaped (default Pebble autoescape; no `raw` filter).
- [x] 3.4 If `admin.css` is edited for the control, re-pin `backend/ktor/.../admin/static/htmx.min.js.SHA256SUMS` (append/refresh the `admin.css` line) — CI lint-lane integrity check, not covered by the local gradle gate.

## 4. Backend tests (Kotest, DB-tagged where they touch Postgres)

- [x] 4.1 Happy path: owner/admin + CSRF + reason → row deleted + exactly one `rejected_identifier_cleared` audit row with `before_state` captured.
- [x] 4.2 CSRF: missing/invalid token → 403 + `admin_csrf_violation`, no delete; CSRF-before-role (read_only + no CSRF → CSRF rejection).
- [x] 4.3 Role gate: `moderator` rejected (no write); `read_only` rejected (no write); `owner` and `admin` allowed.
- [x] 4.4 Reason validation: blank/whitespace rejected (no write); over-length rejected (no write).
- [x] 4.5 Idempotency/not-found: malformed `{id}` → 400 no write; nonexistent/already-cleared id → graceful no-op, no audit row.
- [x] 4.6 Rate-limit: under cap proceeds + advances; at cap (10) rejected without effect; counts only in-window `rejected_identifier_cleared` rows; per-admin isolation.
- [x] 4.7 Atomicity: fault-inject the audit insert → DELETE rolls back (row remains, no audit row).
- [x] 4.8 Template/route render: clear control present for owner/admin, absent for read_only/moderator; rendered values escaped; collection `POST` → 405; serving `GET` writes no audit row.
- [x] 4.9 Test-data hygiene: (a) seed in-window vs out-of-window `rejected_identifier_cleared` ledger rows by **relative age against DB `NOW()`** (mirror `ReservedUsernamesTestSupport.seedReservedAudit(..., ageMinutes=)`), NOT absolute client-side `Instant`s — avoids the macOS-micros / Linux-nanos truncation flake; (b) clean up the `admin_actions_log` rows this spec writes **per-test by acting-admin id** (audit rows carry `admin_id` and are the real cross-spec pollution vector — e.g. an actions-log-viewer spec counting rows); (c) do NOT `autoClose` any `DataSource`/pool that an `afterTest`/`afterSpec` cleanup uses (pool-closes-first → cleanup silently no-ops).
- [x] 4.10 Re-rejection after clear: clear a `(identifier_hash, identifier_type)` row, then re-insert the same pair via the seed helper → the insert SHALL succeed (UNIQUE no longer conflicts) and the row reappears in the viewer (the clear is not a permanent allowlist).

## 5. Manual verification (verify-loop §A — admin panel)

- [ ] 5.1 Boot `KTOR_ENV=test` backend (`RUN_FLYWAY_ON_STARTUP=true` not needed — no migration); bootstrap an `owner` admin (TOTP); seed a `rejected_identifiers` row.
- [ ] 5.2 Drive `POST /admin/rejected-identifiers/{id}/clear` via a browser MCP (login → TOTP → clear with a reason); confirm the row disappears, exactly one `admin_actions_log` row exists with `before_state`, and a second clear of the same id is a graceful no-op.
- [ ] 5.3 Confirm a `read_only`/`moderator` session sees NO clear control and is refused on a direct `POST`.
- [ ] 5.4 Capture screenshot evidence (control rendered + post-clear state) for the PR body (docs/11 §5 DoD — UI-affecting).

## 6. Staging smoke (pre-archive)

- [ ] 6.1 `gh workflow run deploy-staging.yml --ref admin-rejected-identifiers-clear-action`; poll the deploy run to green.
- [ ] 6.2 Smoke the clear against the branch deploy (seed a synthetic `rejected_identifiers` row, clear it, verify the audit row + before_state) and a role-refusal check; tick this section.

## 7. Docs + archive prep

- [x] 7.1 Update `docs/07-Operations.md` § Core Features (Rejected Identifiers Viewer): change the "manual clear path remains DESIGN — deferred to ... admin-rejected-identifiers-clear-action" line to shipped, noting owner/admin + CSRF + audit + dedicated 10/hr cap.
- [x] 7.2 Run the pre-push gate: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` (CI-equivalent against fresh DB containers if the long-lived dev DB pollutes isolation-dependent specs).
- [ ] 7.3 At archive, update the canonical `openspec/specs/admin-rejected-identifiers-viewer/spec.md` `## Purpose` paragraph (it still says the clear action "is deferred to the fast-follow `admin-rejected-identifiers-clear-action`") to describe the clear as shipped — the requirement deltas don't touch Purpose prose, so it needs a manual one-line touch-up or it will read "deferred" while the requirements implement it.
- [ ] 7.4 `/opsx:archive`: `openspec validate --specs admin-rejected-identifiers-viewer --strict`; move the change under `archive/`; resolve issue #190 (`Closes #190`).
