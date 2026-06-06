## Context

The read-only Report Queue (`admin-report-queue`, [PR #154](https://github.com/aditrioka/nearyou-id/pull/154)) renders `reports` ⟕ `moderation_queue` newest-first with filters + a per-row deep-link to `/admin/users`, but writes nothing. Its spec positively defers the write-back: *"Report resolution write-back and the edit-history filter are explicitly deferred ... SHALL ship as the separate change `admin-report-queue-resolution-actions`."* The `FOLLOW_UPS.md` charter for this change scopes it as the bookkeeping write-back: set `reports.status` / `reviewed_by` / `reviewed_at`, set `moderation_queue.resolution` / `status` / `resolved_by` / `resolved_at`, write one immutable `admin_actions_log` row, atomically, CSRF + role-gated like `admin-user-moderation`.

The schema is fully shipped — **no Flyway migration**:
- `reports.status` `VARCHAR(16)` CHECK ∈ {`pending`,`actioned`,`dismissed`}; `reviewed_at`, `reviewed_by` (FK→`admin_users` ON DELETE SET NULL, V16). [`V9__reports_moderation.sql:45-73`]
- `moderation_queue.status` CHECK ∈ {`pending`,`resolved`}; `resolution` CHECK ∈ {`keep`,`hide`,`delete`,`shadow_ban_author`,`suspend_author_7d`,`ban_author`,`accept_flagged_username`,`reject_flagged_username`} (or NULL); `resolved_at`, `resolved_by` (FK→`admin_users` ON DELETE SET NULL, V16). [`V9:75-111`]
- `admin_actions_log.action_type` `VARCHAR(64)`, **no CHECK** → new free-form action types need no migration. [`V16:102-114`]

The precedent to mirror exactly is `AdminUserModerationRoute.kt` (`admin-user-moderation`, Admin #5): each POST validates CSRF first, then the write-role gate, parses the path UUID (malformed → 400, no writes), reads the form field via `AdminCsrfGate.formParametersAfterValidation`, then the repository performs the mutation + writes the audit row inside one transaction and returns a sealed outcome.

## Goals / Non-Goals

**Goals:**
- Let an authenticated, write-role admin resolve a report (`pending → actioned | dismissed`) and resolve a `moderation_queue` item (`pending → resolved` + a `resolution` enum value), each atomically with exactly one `admin_actions_log` row, CSRF + role-gated.
- Apply the one content-visibility effect intrinsic to the auto-hide signal: `resolution = keep` on an `auto_hide_3_reports` item un-hides the target (`is_auto_hidden = FALSE`); `resolution = hide` leaves it hidden.
- Render in-row resolution controls in the existing report-queue table, HTMX-partial with a no-JS `POST`-form fallback, all values HTML-escaped.
- Be idempotent: re-resolving an already-resolved/already-reviewed row is a safe no-op (no second audit row, no error).

**Non-Goals:**
- **Performing author enforcement.** Recording `resolution = suspend_author_7d` / `ban_author` / `shadow_ban_author` does NOT mutate `users`. Suspend/ban enforcement stays on the existing `/admin/users` deep-link (suspend/unban shipped); shadow-ban + soft-`delete` content have no enforcement surface yet and are recorded-only here.
- The "post has edit history" prioritization filter (separate follow-up `admin-report-queue-has-edit-history-filter`).
- **Username-moderation resolutions.** `accept_flagged_username` / `reject_flagged_username` (and the `username_flagged` trigger) are owned by the future Premium Username Change Oversight feature (`docs/07-Operations.md` § Core Features), which carries a 10/hour rate limit + override-on-resubmit + manual-handle-release semantics a generic resolve can't express. This endpoint accepts only the 6 content/author resolutions and rejects the username ones — so it does not ship a misleading half-action ahead of that feature.
- The per-admin destructive-action rate limit (separate follow-up `admin-destructive-action-rate-limit`; these new POSTs will fall under it when it lands).
- Any new Flyway migration, new capability spec, or new library.

## Decisions

### D1 — Extend the `admin-report-queue` capability (no new capability)
The write-back ships under the existing `admin-report-queue` capability rather than a new `admin-report-queue-resolution`. Rationale: same route subtree (`/admin/reports*` + the sibling `/admin/moderation-queue/{id}/resolve`), same templates (`reports*.peb`), same session/CSRF/role machinery, and the read-viewer's own deferred requirement names this change as the one that lands the write-back *in that capability*. Keeping one capability avoids a spec split across two files for one surface. The delta MODIFIES the existing deferred requirement (resolution no longer deferred; edit-history filter still deferred) and ADDs the resolution requirements.

### D2 — Record-the-decision + intrinsic auto-hide toggle; author enforcement stays on the deep-link (THE central scope decision)
The `moderation_queue.resolution` enum spans content effects (`keep`/`hide`/`delete`), author effects (`shadow_ban_author`/`suspend_author_7d`/`ban_author`), and username effects (`accept`/`reject_flagged_username`). Scope of *this* change:
- **(a) Bookkeeping — IN.** Status transitions + `reviewed_by`/`resolved_by` + timestamps + one audit row, atomically.
- **(b) Intrinsic content-visibility toggle — IN.** `keep` on an `auto_hide_3_reports` item sets `is_auto_hidden = FALSE` (the symmetric inverse of the V9 BEFORE-INSERT auto-hide trigger that set it TRUE); `hide` leaves it hidden. `posts.is_auto_hidden` (V4) + `post_replies.is_auto_hidden` (V8); `users`/`chat_messages` have no such column, so the toggle is a no-op there. This is the *only* content mutation in scope because it is the direct inverse of what the queue itself created.
- **(c) Author/delete/shadow-ban enforcement — OUT (recorded-only).** The resolution value is recorded, but `users` is not mutated and no content is soft-deleted. A negative-guard scenario asserts `resolution = ban_author` leaves `users` untouched.

**Alternatives considered.** *Full in-row enforcement* (in-row Suspend/Ban/Shadow-ban that actually mutate `users`, matching the literal `docs/07-Operations.md:36` "Hide, Dismiss, Suspend, Ban, Shadow ban" list) — rejected for v1: it pulls in permanent-ban + shadow-ban user-mutation surfaces that don't exist yet (only 7-day suspend/unban shipped), each with its own invariants (`token_version` bump, shadow-ban view semantics, `account_action_applied` notifications), ballooning a focused write-back into a user-moderation change and risking spec drift. The `FOLLOW_UPS.md` charter scopes this change to bookkeeping write-back; the ops-doc list describes the *resolution decision vocabulary* + the end-state vision, with author enforcement "today reachable only via the deep-link" — and it stays there. *Pure bookkeeping with no visibility toggle* — rejected: `keep` would be meaningless (content stays auto-hidden), so the symmetric un-hide is the minimum that makes the decision real. **This scope is surfaced as Open Question OQ1 for user confirmation at review.**

### D3 — Two endpoints, matching the two-table / two-cardinality reality
- `POST /admin/reports/{reportId}/resolve` with `decision ∈ {actioned, dismissed}` → sets `reports.status` + `reviewed_by` + `reviewed_at` for that one report.
- `POST /admin/moderation-queue/{queueId}/resolve` with `resolution ∈ {keep, hide, delete, shadow_ban_author, suspend_author_7d, ban_author}` (the 6 content/author values; the 2 username values are out of scope per Non-Goals) → sets `moderation_queue.status = resolved` + `resolution` + `resolved_by` + `resolved_at`, and applies the D2(b) visibility toggle.

Both are wired inside `authenticate(ADMIN_AUTH_NAME)` alongside `adminReportQueue`. **Alternative considered:** a single report-centric `POST /admin/reports/{id}/resolve` carrying an optional `resolution` — rejected because `moderation_queue` rows are keyed `(target_type, target_id, trigger)` and *many reports map to one queue row* (3 reporters → 1 `auto_hide_3_reports` row); resolving the shared queue item *through* an arbitrary report id conflates the cardinalities and obscures the relationship. Two endpoints keep each action's unit unambiguous. **OQ2 (cascade):** whether resolving a queue item should auto-transition its same-target `pending` reports — recommended **no** for v1 (explicit, independent actions; no surprising bulk writes).

### D4 — Atomicity: mutation + audit row in one transaction
Each handler delegates to a repository method that opens one transaction, performs the conditional `UPDATE`, and writes the `admin_actions_log` row before commit — mirroring `UserModerationRepository.suspend/unban`. No partial state where the row transitions but the audit row is missing (or vice-versa).

### D5 — Idempotency via conditional UPDATE on the pending precondition
The `UPDATE` carries the precondition in its `WHERE` (`reports ... WHERE id = ? AND status = 'pending'`; `moderation_queue ... WHERE id = ? AND status = 'pending'`). Zero rows affected → the row was already resolved (or gone): return a benign "already resolved / no change" outcome, write **no** audit row, re-render with a message — never a 5xx, never a duplicate audit row. This also resolves the two-admins-resolve-the-same-row race (second is a no-op) and the many-reports-to-one-queue re-resolve (D3) cleanly.

### D6 — Gate order mirrors `admin-user-moderation`: CSRF first, then write-role
`if (!AdminCsrfGate.validateCsrf(call, auditLogger)) return@post` then `if (!AdminRoleGate.requireWriteRole(call)) return@post`, then parse the path UUID, then read the decision/resolution form field via `AdminCsrfGate.formParametersAfterValidation`, then `principal<AdminPrincipal>()` for `adminId`/`role`. Identical ordering + helpers as the shipped suspend/unban handlers so the CSRF-violation audit (`admin_csrf_violation`) and role-gate behavior are consistent.

### D7 — Dual-mode render + in-row controls, fully escaped
In-row controls live in `reports-table.peb` (a small `POST` form per row: `_csrf` hidden field + a `decision`/`resolution` `<select>` + submit), so they work HTMX-enhanced (partial swap of the table fragment) and via plain `POST` with no JS — mirroring the read viewer's `reports-table.peb` / `reports.peb` split. A successful action re-renders the (re-queried) table fragment for HTMX or 303-redirects back to the filtered queue for no-JS, matching `respondActionRedirect`. Pebble autoescape covers every value (incl. `reason_note`); the read-viewer scenario "no resolution control is rendered" is inverted in the MODIFIED spec.

### D8 — New free-form audit `action_type` values, no migration
`report_resolved` and `moderation_queue_resolved` are written via new `AdminAuditLogger` methods (siblings of `user_suspended`/`user_unbanned`). `action_type` is `VARCHAR(64)` with no CHECK, so no schema change. `before_state`/`after_state` JSONB capture the status/resolution transition for the audit trail.

## Risks / Trade-offs

- **Recorded-only author resolutions could mislead a moderator into thinking recording = enforcing.** → In-row labels distinguish "record decision" from the deep-link enforcement; design + spec state it explicitly; a negative-guard scenario asserts `resolution = ban_author` does not touch `users`. Enforcement remains the `/admin/users` deep-link the read viewer already renders.
- **Many reports → one queue row: resolving the queue item leaves sibling reports `pending`.** → D5 idempotency makes re-resolution safe; v1 requires explicit per-report dismissal (OQ2 cascade deferred). Acceptable: the queue item (the moderation decision) is resolved once; report bookkeeping is per-report by design.
- **No per-admin rate limit on these new destructive POSTs.** → Deferred to `admin-destructive-action-rate-limit`; interim mitigations per that charter (CSRF, 30-min idle + absolute session caps, IAP network gate, immutable audit trail, reversibility — every resolution is re-openable by an inverse action).
- **Concurrent resolution race.** → D5 conditional `UPDATE ... WHERE status='pending'` serializes correctly; the loser is a no-op, not a double-write.

## Migration Plan

No Flyway migration (all columns + enums + FKs shipped at V9/V16; `action_type` is free-form). Deploy is code-only (new route file + repository methods + `AdminAuditLogger` methods + template edits + `AdminModule` wiring). Rollback = revert the change; no schema to undo. Pre-archive staging smoke: log in, open `/admin/reports`, resolve a seeded report + an `auto_hide_3_reports` queue item (verify `keep` un-hides), confirm one `admin_actions_log` row per action and idempotent re-resolution.

## Open Questions

- **OQ1 (scope — central):** Confirm the D2 scope — bookkeeping write-back + intrinsic `keep`/`hide` visibility toggle, author/delete/shadow-ban **recorded-only** via the existing deep-link (recommended, matches the `FOLLOW_UPS.md` charter) — vs the fuller `docs/07-Operations.md:36` framing of in-row Suspend/Ban/Shadow-ban that *perform* enforcement (larger; pulls in user-mutation + shadow-ban surfaces). To be surfaced to the user at proposal review.
- **OQ2 (cascade):** When a `moderation_queue` item is resolved, should its same-target `pending` reports auto-transition to `actioned`/`dismissed`? Recommended **no** for v1 (explicit independent actions); revisit if the moderator UX is clunky.
