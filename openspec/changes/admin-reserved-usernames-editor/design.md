## Context

`reserved_usernames` (V3) holds the handles signup rejects: a `seed_system` baseline (brand/ops handles + short-handle combos) plus operator-curated `admin_added` rows, distinguished by the `source` CHECK column. Two DB triggers already guard it: `reserved_usernames_set_updated_at` (touch `updated_at` on UPDATE) and `reserved_usernames_protect_seed` (raise on DELETE of a `seed_system` row, and on any UPDATE that changes a `seed_system` row's `source`). The `admin_app` role already has `SELECT/INSERT/UPDATE/DELETE` on the table (provision script). Today there is no in-app surface — `admin_added` curation is raw SQL.

This change adds the admin-panel CRUD surface (mockup frame 21), reusing the shipped admin stack: `AdminCsrfGate`, `AdminRoleGate`, `AdminPrincipal`, `AdminAuditLogger`, `clientIp`, the route→repository(transaction-holding) shape from `AdminReportResolutionRoute`/`AdminUserModerationRoute`, and the audit-log-COUNT rate-limit mechanism from `DestructiveActionRateLimiter`. It is **zero-migration**: table, both triggers, the action-type column (free `VARCHAR(64)`), and the `admin_app` grants all pre-exist.

## Standards conformance (docs/11)

- **§3.1 layering** — realized in the admin package's established shape: a thin `AdminReservedUsernamesRoute` (parse / authenticate / CSRF / validate / respond) over a `ReservedUsernamesRepository` whose write methods are the transaction + business-rule boundary (the service-equivalent, exactly as `ReportResolutionRepository`/`UserModerationRepository` do). No SQL in the route; no `ApplicationCall` in the repository. **No deviation.**
- **§3.2 JDBC** — repository uses the shared bounded JDBC dispatcher + one transaction per write op (rate-limit COUNT + mutation + audit INSERT on one `Connection`). Test pool: `autoClose(hikari())` + size 2.
- **§3.5 testing** — kotest JUnit5, `@Tags("database")` for the route/repository integration specs; deterministic inputs.
- **§3.6 admin UI** — Pebble templates + HTMX fragment swap + no-JS fallback + vendored vanilla CSS tokens; **mockup frame 21 is the visual target** (render + per-frame measurement annex at implementation).
- **§4 Pattern Registry** — the per-admin rate limit reuses the audit-log-COUNT soft-cap **pattern** already registered via `admin-destructive-action-rate-limit`; this change adds a second *instantiation* (different action set + threshold), not a different pattern (Decision 1). **No docs/11 amendment required.** CSRF/role/audit reuse is straight pattern reuse. If review prefers generalizing the limiter into one shared parameterized component, that is a follow-up refactor, not a blocking deviation.

## Goals / Non-Goals

**Goals:**
- Operators add / CSV-bulk-add / re-reason / remove `admin_added` reserved usernames from the panel, off raw SQL.
- Seed rows are protected at both the app layer and the DB (for removal), per the docs Pre-Launch "reserved_usernames trigger test".
- Every mutation is CSRF- + write-role-gated, per-admin rate-limited (100/hour), and immutably audit-logged with a dedicated action type.

**Non-Goals:**
- No change to signup-time reserved-username rejection behavior (read path unchanged).
- No new migration, no new library, no new DB grant, no new index (cardinality doesn't warrant one yet).
- Not the Premium Username Change Oversight surface (frame 22, `username_history` / `username_flagged`) — that is a separate change.
- No editing of a reserved row's `username` (rename) or `source` — only `reason` is mutable; rename = remove + add.

## Decisions

### D1 — Rate limit: sibling limiter, same pattern (100/hour over the reserved action set)
Add `ReservedUsernameActionRateLimiter` in `admin/ratelimit/`, mirroring `DestructiveActionRateLimiter` exactly: `countInTrailingHour(conn, adminId)` = `COUNT(*) FROM admin_actions_log WHERE admin_id = ? AND created_at > NOW() - INTERVAL '1 hour' AND action_type IN ('reserved_username_added','reserved_username_edited','reserved_username_removed')`, plus `isAtOrOverCap(conn, adminId)` = count `>= 100`. Checked on the gated write's `Connection` (in-transaction soft cap; no `FOR UPDATE`; ±1 concurrency tolerance accepted — abuse-prevention, not an authorization boundary; the solo-admin period makes concurrency moot anyway). **Alternative considered:** generalize `DestructiveActionRateLimiter` into one parameterized `AdminActionRateLimiter(actionSet, cap)`. Rejected for this change — it refactors a shipped, spec-bound capability with two existing consumers (`admin-user-moderation`, `admin-report-queue`), widening blast radius and merge surface for no behavioral gain. Same pattern either way; the sibling keeps the change self-contained and disjoint.

### D2 — CSV bulk add: one transaction, skip duplicates, atomic quota reject
A bulk upload is one transaction: parse rows → bucket each as `added` (new + valid), `skipped_duplicate` (PK already present **or already accepted earlier in the same batch** — a username repeated within one CSV is added once and the later occurrence(s) report as `skipped_duplicate`, so no phantom audit row), or `skipped_invalid` (bad arity / blank / charset-failing / `reason` over the column width); let `N = |added|` (distinct new usernames). If `countInTrailingHour(conn) + N > 100`, **roll back and reject the whole upload** ("this upload of N would exceed your 100/hour quota; X already used"), no rows written. Otherwise INSERT the N rows + N `reserved_username_added` audit rows + COMMIT, and return the three-bucket report. **Alternative:** partial-apply-up-to-cap. Rejected — opaque (operator can't tell which rows landed); atomic-all-or-nothing is predictable and re-uploadable.

### D3 — Per-row routes identify the target via `{username}` path param
`POST /admin/reserved-usernames/{username}/edit-reason` and `.../{username}/remove` — consistent with the sibling `{id}` routes (`/admin/reports/{id}/resolve`, `/admin/users/{id}/suspend`). This is path-safe because **added usernames are normalized to lowercase and validated against the canonical username charset** (`[a-z0-9._]`) at add time (D9) — every char in that set is a safe single-path-segment char. **Alternative:** carry `username` as a CSRF-consumed form field. Rejected — diverges from the established `{id}`-path admin write routes for no safety gain once D9 constrains the charset.

### D4 — Seed `reason`-edit guard is app-layer only (no migration)
The V3 `reserved_usernames_protect_seed` trigger blocks seed *delete* and seed *source-change*, but NOT seed *reason* edits. The editor refuses edit-reason (and remove) on any `seed_system` row at the app layer **before** issuing SQL; the DB trigger is the defense-in-depth second line for *remove* only. We deliberately do **not** add a migration extending the trigger to reason edits: docs mandate only UI-level read-only for the seed reason, the destructive op (delete) is already DB-enforced, a seed-reason typo is low-severity + recoverable, and a trigger change would cost a migration plus the zero-migration parallel-merge disjointness this change is chosen for.

### D5 — Keyset pagination, newest-first `(created_at DESC, username)`
Mirrors the sibling admin viewers (block-registry / privacy-flips / rejected-identifiers); surfaces recent `admin_added` rows first; `username` (PK) is the unique keyset tiebreak (seed rows share a migration timestamp). The `source` filter (`seed_system`|`admin_added`|all) and a case-insensitive substring `username` search compose with the cursor. **No new index** — at current cardinality (hundreds to low thousands) a scan + sort is cheap; a `(created_at DESC, username)` keyset index is a deferred follow-up if growth warrants (same posture as block-registry).

### D6 — State-changing gate order (mirror AdminReportResolutionRoute D6)
Each write POST: `AdminCsrfGate.validateCsrf` FIRST (403 + `admin_csrf_violation` audit on miss) → `AdminRoleGate.requireWriteRole` (403 for read-only roles) → parse path `{username}` + read the CSRF-consumed body once via `formParametersAfterValidation` → validate (blank/charset/length failures → 400, no write) → repository transaction. `GET` needs only the authenticated-admin session (any role).

### D7 — Audit atomicity + row shape
The success audit row is INSERTed **inside the repository transaction** (one `Connection`, atomic with the mutation and the rate-limit COUNT) — not via `AdminAuditLogger` (which uses its own connection); `AdminAuditLogger` is used only for the standalone `admin_csrf_violation` rejection row, matching the report/moderation precedent. Row: `action_type` ∈ {`reserved_username_added`,`reserved_username_edited`,`reserved_username_removed`}; `target_type='reserved_username'`; `target_id` = the username (the `TEXT` column holds it directly); `reason` = the operator-supplied reason text; `before_state`/`after_state` JSONB (e.g. edit → `{"reason": "<old>"}` / `{"reason": "<new>"}`; add → after `{"username","reason","source":"admin_added"}`; remove → before `{"username","reason","source"}`); `ip` via `clientIp`; `user_agent`.

### D8 — CSV transport + guardrails
**Transport: the CSV is a `text`/textarea form field** (operator pastes `username,reason` lines), read via the existing `formParametersAfterValidation` / `receiveParameters` path — **NOT a `multipart/form-data` file upload.** This is load-bearing: the reused `AdminCsrfGate` consumes the request body via `receiveParameters()` (form fields only) and Ktor does not re-serve a consumed body without `DoubleReceive`, so a `<input type=file>` part would be unreadable after the CSRF gate. A true file upload would require a new CSRF-over-multipart body-read pattern (extend `AdminCsrfGate` to `receiveMultipart` once, validating `_csrf` + the file part from one read) — that is a *new pattern* (docs/11 §4) and is explicitly **out of scope**; the textarea keeps the change inside the existing gate with no new pattern. (The mockup's "CSV upload" wording is satisfied functionally by a paste field; a file picker is a future enhancement if operators ask.)

Parsing: accept an optional `username,reason` header (detected + skipped); parse RFC-4180-style (quoted fields may contain commas/quotes). Bound the field: ≤1000 data rows and ≤256 KB → 400 before parsing. An empty / header-only submission is **not** an error — it returns an empty three-bucket report (0/0/0). Each malformed row (wrong column count, blank username, charset-failing username, blank reason, `reason` over 64 chars) → `skipped_invalid` with its line number; malformed rows never abort the batch.

### D9 — Input validation + normalization
`username`: trimmed → lowercased → validated against the canonical username **charset** (`[a-z0-9._]`) + length 1..30. This is **charset-only and deliberately NOT the full signup username *shape* rules** (≥3 chars, no leading/trailing separator, no consecutive dots): the V3 seed data itself reserves 1–2-char short-handles (`reserved-short-handle` rows), so an admin must be able to reserve sub-regex / edge handles too — the charset bound is what guarantees D3 path-safety and keeps entries collision-relevant (real usernames only use these chars). `reason`: trimmed, non-blank, **length-capped at ≤64 chars to match the `reserved_usernames.reason VARCHAR(64)` column** (V3) — the cap MUST equal the column width so an over-length reason is a clean 400 / `skipped_invalid` at the app layer, never a Postgres `22001` overflow 5xx (which D10 forbids and which a zero-migration change cannot widen its way out of). Validation is a server-side allowlist exactly like the report-resolution enum allowlist (out-of-allowlist → 400 single / `skipped_invalid` CSV, no write, never a DB-CHECK 5xx).

### D10 — Dual-mode response
Mirror AdminReportResolutionRoute D8: a successful or no-op write re-renders the filter-preserving `reserved-usernames-table.peb` fragment for an `HX-Request`, or 303-redirects back to the filtered list (no-JS). In-band messages ("already reserved", "seed row cannot be edited/removed", "quota exceeded (100/hour)") render in the swapped fragment / re-rendered page — never a 4xx/5xx body for these expected outcomes.

## Risks / Trade-offs

- **Seed `reason` mutable at the DB layer** → only the app guard prevents a seed-reason edit (D4). Mitigation: app refuses before SQL + a test asserts the refusal; the destructive op (delete) is DB-enforced; seed-reason typos are low-severity and recoverable. Accepted to preserve zero-migration.
- **Soft rate cap (±1 under concurrency)** → identical posture to the shipped destructive limiter; abuse-prevention, not an authz boundary; solo-admin period makes it academic.
- **No keyset index (scan + sort)** → fine at current cardinality; deferred index follow-up if `admin_added` grows large.
- **CSV in-memory parse** → bounded by the row/byte caps (D8).
- **Audit stores raw username in `target_id`** → reserved usernames are blocked *handles*, not user PII; storing them plaintext in the audit trail is consistent with existing admin audit rows that reference usernames.

## Migration Plan

No DDL, no data migration, no new grant. Deploy is a normal Cloud Run revision. **Rollback** = revert the squash-merge commit; written audit rows remain (immutable, harmless) and no schema state must be undone. Pre-archive staging branch deploy + smoke (add → list → edit → remove → seed-remove-blocked → over-cap) per project.md § Staging deploy timing; confirm `admin_app` write grants are live on the smoke target.

## Open Questions

- **Signup normalization parity** — confirm at `/opsx:apply` that the signup reserved-check compares the lowercased candidate to `reserved_usernames` by exact match (so storing lowercased entries is correct). If signup normalizes differently, align D9. (Expected: lowercase-exact, matching the lowercase seeds.)
- **Quota chip** — whether to surface the acting admin's trailing-hour reserved-action count ("N/100 this hour") on the page, mirroring the user-management destructive-quota chip. Default: include a small read-only indicator for parity; drop if it complicates the fragment.
