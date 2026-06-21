## Context

The account erasure pipeline already ships end-to-end: `deletion_requests` (V27) records the request → 30-day grace → cancel lifecycle, and the daily `account-hard-delete-worker` scans `scheduled_hard_delete_at <= NOW() AND executed_at IS NULL AND cancelled_at IS NULL`, runs tombstone + cascade + `deletion_log` write, and stamps `executed_at`. What's missing is the operator's window into that pipeline (a documented Core Feature in [`docs/07-Operations.md`](../../../docs/07-Operations.md) § Core Features → "Hard Delete Queue"; admin mockup board **frame 15**, route `/admin/deletion-requests`).

This is the **6th read-only-admin-viewer** in the admin module. The closest shipped sibling is `admin-subscription-grace-monitor` (`GET /admin/subscriptions/grace` + a manual `expedite` write): same list/keyset/filter/count/HTMX-plus-fallback shape, same CSRF + role-gate + audit-ledger-rate-limit machinery. This design mirrors it, with the **one load-bearing difference** that hard-delete expedite *actually mutates state* (it accelerates an irreversible erasure) where grace expedite was a no-op bookkeeping row.

Constraints: admin module (Argon2id + TOTP session, `__Host-admin_session` cookie, `csrf_token_hash`), JDBC + Pebble + HTMX + vendored CSS, `admin_app` DB role (raw reads allowed; `admin_actions_log` UPDATE/DELETE revoked at role level → append-only). No new migration permitted (and none needed).

## Goals / Non-Goals

**Goals:**
- Give operators a paginated, filterable, count-summarized view of accounts pending hard-delete, soonest-deadline-first, served by the **existing** `deletion_requests_scheduled_idx` partial index (zero migration).
- Provide a safe manual **expedite** lever (honor a "delete me now" support request) that accelerates the deletion via the existing worker, fully audited and rate-limited.
- Preserve the schedule/execute separation: the admin route never performs the tombstone/cascade itself.

**Non-Goals:**
- No cancellation/restore-from-queue action (un-deleting is out of scope; the user-facing cancel path owns that during grace).
- No synchronous hard-delete in the request path (the worker remains the sole executor).
- No change to the `deletion_requests` schema, the account-deletion flow, or the worker scan.
- No CSAM / data-export / attestation queues (separate Core Features, separate changes).
- No new keyset index (the existing partial index covers the list ordering; see D4).

## Decisions

### D1 — Expedite mutates `scheduled_hard_delete_at = NOW()`; the existing worker executes
`POST …/{id}/expedite` runs `UPDATE deletion_requests SET scheduled_hard_delete_at = NOW() WHERE id = ? AND executed_at IS NULL AND cancelled_at IS NULL AND scheduled_hard_delete_at > NOW()`. The next daily worker run (≤24h) then satisfies its `scheduled_hard_delete_at <= NOW()` predicate and erases the account.
- *Why:* the worker already keys on this exact column; bringing the deadline forward is the minimal, zero-migration way to expedite, and it keeps the admin route a pure *scheduling* write — consistent with the Apple-S2S immediate path and the codebase's "admin schedules, worker executes" separation.
- *Alternatives rejected:* (a) synchronous tombstone+cascade in the route — couples the HTTP path to the heavy worker job, risks a partial-cascade on request timeout, and duplicates the worker's transaction logic; (b) a new `expedited_at` boolean/column — needs a migration and a worker-scan change, both of which this design exists to avoid.
- The guarded `UPDATE … WHERE` is the **race fix**: if the worker (or a user-cancel) touched the row between list-render and expedite, the `WHERE` matches 0 rows → the action reports "no longer pending" and writes no audit row (D7).
- **Atomicity:** the guarded UPDATE, the in-transaction rate-limit count, and the audit insert run in **one JDBC transaction** (commit-or-rollback as a unit) — a cap rejection or an audit-write failure rolls back the deadline advance, never leaving an orphaned advance-without-audit or audit-without-advance. This mirrors `admin-rejected-identifiers-clear-action`'s atomicity guarantee and is captured as an explicit spec requirement (with fault-injection scenarios).

### D2 — Expedite rate limit: distinct **10/admin/hour** counter, independent of the destructive budget
Reuse the `admin-destructive-action-rate-limit` mechanism (the immutable `admin_actions_log` IS the ledger; the count is taken inside the gated write's JDBC transaction; an at-or-over-cap attempt surfaces an inline "quota exceeded" state, not a 5xx). Count on a **distinct** key — rows with `admin_id = ?`, `action_type = 'deletion_request_expedited'`, `created_at > NOW() - INTERVAL '1 hour'` — that does **not** consume, and is not consumed by, the shared 20/hour destructive budget.
- *Why 10, not grace's 20:* expedite accelerates an **irreversible** erasure; a tighter cap bounds blast radius from a scripted-tool or compromised-session mistake. 10/hr matches the `admin-rejected-identifiers-clear-action` change (its cap is codified in the `admin-rejected-identifiers-viewer` spec) for a sensitive, low-volume support action.
- *Why independent of the destructive budget (the question the proposal flagged):* the 20/hour destructive budget governs **user-punitive** actions (suspend/ban/shadow-ban). Expedite is a **user-requested accommodation**, not a punishment — bucketing it with punitive actions would let a burst of legitimate "delete me now" tickets starve the operator's ability to ban abusers (and vice-versa). Both prior non-punitive writes (grace-expedite, rejected-identifier-clear) got their own distinct counters for exactly this reason. **Resolved** to distinct + independent; left in Open Questions only as a flagged review point.

### D3 — List population is keyed on `deletion_requests` lifecycle state, not `users.deleted_at`
The list is every `deletion_requests` row with `executed_at IS NULL AND cancelled_at IS NULL`, JOINed to `users` for the username. It deliberately does **not** filter on `users.deleted_at` (the grace-monitor *did*).
- *Why:* a pending-deletion account is typically already soft-deleted during the grace window, so a `deleted_at IS NULL` filter would hide the entire intended population. The JOIN to `users` therefore tolerates a soft-deleted (`deleted_at IS NOT NULL`) row.
- *FK guarantee (corrects an earlier over-defensive draft):* `deletion_requests.user_id` references `users(id)` `ON DELETE CASCADE`, so a non-executed request's `users` row **always physically exists** (the worker hard-deletes both rows together) — the JOIN always resolves a username and needs no absent-user fallback. The cascade makes the "absent `users` row" state unreachable, so the spec asserts FK-guaranteed presence rather than a null-username rendering path.

### D4 — Ordering: `scheduled_hard_delete_at ASC` (soonest-first), served by the existing partial index
Keyset over `(scheduled_hard_delete_at ASC, id)`. This is the operational priority — a queue is worked front-to-back by deadline; the rows about to execute matter most.
- *Why no new index:* `deletion_requests_scheduled_idx` is `ON deletion_requests(scheduled_hard_delete_at) WHERE executed_at IS NULL AND cancelled_at IS NULL` — its filter is exactly the list predicate and its sort column is the keyset leading column, so PG serves the ascending keyset scan directly. `id` is the tiebreaker for stable pagination (not in the index, but the per-deadline cardinality is tiny). This mirrors grace-monitor's "served by an existing partial index, no migration" decision.
- *Note:* this is the one viewer that orders **ascending** rather than the usual newest-first — an intentional deviation justified by the deadline-queue semantics.

### D5 — Audit row shape
A successful expedite writes one `admin_actions_log` row: `action_type = 'deletion_request_expedited'`, `target_type = 'deletion_request'`, `target_id = {deletion_requests.id}`, acting `admin_id`, the required `reason`, and `before_state`/`after_state` JSON snapshots in which `scheduled_hard_delete_at` differs (the old future deadline → `NOW()`) and which carry the `user_id` for cross-referencing. `action_type` is a new free-text `VARCHAR(64)` literal (no CHECK → no migration). Past-participle naming (`…_expedited`) follows the `rejected_identifier_cleared` / `moderation_queue_resolved` majority convention.

### D6 — Raw `deletion_requests` read under the admin-module exception
`deletion_requests` has no `visible_*` view; the admin module is the sanctioned exception to the shadow-ban-safety "query `visible_*`" invariant (per `openspec/project.md` § Coding Conventions). The SQL-holding property/companion-const is annotated accordingly. No block-exclusion join applies (this is not a posts/users/chat/replies feed query; it's an admin lifecycle table).

### Standards conformance (docs/11 — required)
Builds on existing Pattern-Registry patterns only — **backend layering** (Route → Service → Repository, parameterized JDBC, no business logic in the route), the **admin audit-ledger rate-limit** pattern (`admin-destructive-action-rate-limit`), the **admin CSRF + role-gate** pattern, and the **admin Pebble + HTMX + plain-`GET` fallback + vendored-CSS UI** pattern (docs/11 §3.6, frame 15). **No new pattern is introduced for any already-listed concern**, so no docs/11 § Pattern Registry amendment is required. The ascending-ordering choice (D4) is a parameter of the existing list pattern, not a new pattern.

## Risks / Trade-offs

- **Expedite accelerates irreversible erasure; operator error is unrecoverable.** → Defense-in-depth: owner/admin-only + CSRF + `hx-confirm` warning copy + **required reason** + the tight 10/hr cap + an immutable audit row. The action only ever moves the deadline forward for the worker; it never deletes inline, so even a mistaken expedite leaves a ≤24h window where the user-facing cancel path (if still reachable) or an operator DB intervention can intervene before the worker runs.
- **Race: the worker executes (or the user cancels) the row between render and expedite.** → The guarded `UPDATE … WHERE executed_at IS NULL AND cancelled_at IS NULL AND scheduled_hard_delete_at > NOW()` matches 0 rows → reported as "no longer pending", no audit row, no mutation (D1/D7).
- **Double-expedite noise.** → The already-expedited indicator (read-side LEFT JOIN to the latest `deletion_request_expedited` row) plus the `scheduled_hard_delete_at > NOW()` guard: a second expedite finds the deadline already at/below `NOW()` → rejected as already-due.
- **`apple_s2s_account_delete` rows are scheduled at `NOW()` (no grace).** → They appear in the list (Countdown "due now") but expedite rejects them (already-due) — there is nothing to accelerate; the worker already catches them.
- **Soft-deleted user JOIN.** → Tolerated by D3: the listed user is typically soft-deleted (`deleted_at IS NOT NULL`) but the `users` row is still present (FK `ON DELETE CASCADE` keeps it until the worker hard-deletes both rows together), so the JOIN always resolves a username; the list applies no `deleted_at` filter and never 5xx's.

## Migration Plan

- **No DB migration.** Table, partial index, and `action_type` column width all already exist.
- **Deploy:** standard merge → staging auto-deploy; the new routes mount under the existing authenticated admin subtree. If `admin/static/*` is touched (it should not need to be), re-pin `htmx.min.js.SHA256SUMS` (CI integrity check).
- **Rollback:** remove the route registration; nothing schema-level to revert. The feature is additive and read-mostly.

## Open Questions

- **Rate-limit cap — RESOLVED (confirmed by round-1 review).** D2 sets a distinct **10/admin/hour** counter independent of the 20/hour destructive budget. Round-1 sub-agent review (security + general lenses) confirmed the design's position: expedite is a user-requested accommodation, semantically disjoint from the user-punitive destructive set, so bucketing them would let legitimate "delete me now" tickets starve the ban budget (and vice-versa) — consistent with the two prior non-punitive-write precedents (grace 20/hr, rejected-identifiers-clear 10/hr). No open questions remain.
