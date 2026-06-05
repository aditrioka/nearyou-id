## Context

The `reports` + `moderation_queue` tables shipped at V9 (`reports` capability), and their `reviewed_by` / `resolved_by` FKs to `admin_users(id) ON DELETE SET NULL` shipped at V16 (`admin-schema-bootstrap`). The admin panel already serves authenticated, role-gated pages inside `authenticate(ADMIN_AUTH_NAME)` (`admin-login`), extends a shared Pebble base layout (`admin-panel-scaffold`), and has a proven read-only viewer pattern in `admin-actions-log-viewer` (#123): keyset pagination over `(created_at, id) DESC`, composable parameterized filters, `admin_users` join for display, full HTML-escaping, HTMX partial-swap with a plain-`GET` fallback, strictly read-only. The `admin-user-moderation` (#134) capability serves `GET /admin/users?q=<uuid|username>` with the suspend/unban controls.

This change adds the **Report Queue** read surface (`docs/07-Operations.md` §Core Features "Report Queue", currently DESIGN) that connects them: it surfaces the report backlog and deep-links the offending user to the existing action surface. It is deliberately the read-only half — resolution write-back ships as a fast-follow, exactly as `admin-actions-log-viewer` (read) preceded `admin-user-moderation` (write).

The intent is to **reuse the `admin-actions-log-viewer` infrastructure verbatim where possible** (cursor codec, HTMX fragment detection, escaping, filter-composition helper) rather than invent parallel mechanisms.

## Goals / Non-Goals

**Goals:**
- A read-only `GET /admin/reports` moderator triage table over `reports`, newest-first, keyset-paginated, with composable parameterized filters and optional `moderation_queue` context.
- Deep-link the offending user (the reported target's author, or the target itself for user reports) to `/admin/users?q=<author>` so the moderator can act using the already-shipped suspend/unban controls.
- Match the `admin-actions-log-viewer` UX + security contract (escaping, HTMX, read-only) so the admin panel stays internally consistent.

**Non-Goals:**
- **No write surface.** Marking a report actioned/dismissed, setting `moderation_queue.resolution`/`resolved_by`/`resolved_at`, and writing `admin_actions_log` are explicitly deferred to `admin-report-queue-resolution-actions`.
- **No in-row moderation actions** (Hide/Dismiss/Shadow-ban). Suspend/ban are reachable via the deep-link to `/admin/users`.
- **No "post has edit history" prioritization filter** (needs a `post_edits` existence join) — deferred.
- **No queue-centric rollup view** (one row per `moderation_queue` entry aggregating its reports) — see Decision 1.
- **No new rate-limit surface, no new secret read, no new `libs.versions.toml` pin.**

## Decisions

### Decision 1 — Reports-centric list with an optional `moderation_queue` LEFT JOIN (not a queue-centric rollup)
`docs/07-Operations.md` states the queue "reads from `reports` + joins `moderation_queue`" → the atomic display row is a **report**, with queue context attached when present. Rationale: (a) doc-literal; (b) a report below the 3-reporter auto-hide threshold has **no** `moderation_queue` row yet — a reports-centric list still surfaces it (a queue-centric view would hide single-report items entirely); (c) the future resolution write-back operates on the `moderation_queue` row, so a queue-centric rollup is the natural home for *that* follow-up, keeping this read-only change scoped.
**Alternative considered:** queue-centric rollup (one row per `moderation_queue` row, with a reporter count). Rejected for v1 — hides sub-threshold reports and couples the read view to the deferred resolution semantics.

### Decision 2 — Keyset pagination over `(created_at, id) DESC`, reusing the `admin-actions-log-viewer` cursor contract
Mirror the shipped viewer exactly: opaque cursor encoding the last row's `(created_at, id)`; `WHERE (created_at, id) < (cursor)` for the "older" page; fixed page size; **no SQL `OFFSET`**; a malformed/absent cursor falls back to the first (newest) page rather than erroring. Reuse the existing cursor codec + helper rather than writing a second one.
**Alternative considered:** `OFFSET` pagination — rejected (project invariant; degrades on deep pages; `admin-actions-log-viewer` already forbids it).

### Decision 3 — Add one index-only Flyway migration `V19__reports_created_idx.sql` for the keyset backbone
The shipped `reports_status_idx ON (status, created_at DESC)` is **status-prefixed** and omits `id`, so it cannot back an unfiltered newest-first keyset scan over `(created_at, id)`. This is the exact situation `admin-actions-log-viewer` solved with `V17__admin_actions_log_created_idx.sql`. Add the parallel `CREATE INDEX reports_created_idx ON reports(created_at DESC, id DESC)` (next free version is **V19**; max existing is `V18__seed_system_actor.sql`). Plain `CREATE INDEX` (mirroring V17 — no `CONCURRENTLY`, which Flyway's transactional migration cannot run); `reports` is small pre-launch so a brief lock is acceptable. The existing `reports_target_idx` / `reports_status_idx` remain available for filtered views (planner's choice).
**Alternative considered:** rely on `reports_status_idx` — rejected (status-prefixed, no `id` tiebreaker → sort on the default page). **Alternative considered:** no index, accept the sort — rejected for parity with V17 and to keep the default page index-only.

### Decision 4 — `moderation_queue` attached via `LEFT JOIN LATERAL … LIMIT 1` (representative row), not a plain join
`moderation_queue` is `UNIQUE (target_type, target_id, trigger)` — a single `(target_type, target_id)` can carry **multiple** rows (one per trigger). A plain `LEFT JOIN ON (target_type, target_id)` would **fan out** one report into N display rows. Use `LEFT JOIN LATERAL (SELECT trigger, priority, status FROM moderation_queue mq WHERE mq.target_type = r.target_type AND mq.target_id = r.target_id ORDER BY priority ASC, created_at DESC LIMIT 1) mq ON TRUE` to attach a single representative queue row (most-urgent-then-newest). Today only the `auto_hide_3_reports` trigger writes (per `moderation-queue` spec), so at most one row exists in practice — but `LATERAL … LIMIT 1` keeps the join correct and fan-out-free as the other six reserved triggers come online.
**Alternative considered:** plain `LEFT JOIN` — rejected (fan-out once multiple triggers ship). **Alternative considered:** `GROUP BY` + aggregate triggers into an array — rejected (more complex than the single representative row a triage table needs).

### Decision 5 — Resolve the offending **user** per `target_type` for the deep-link; graceful no-link on a hard-deleted target
The action the moderator takes is against the offending *user*. Resolve it by target type and link to `/admin/users?q=<user>`:
- `target_type = 'user'` → link `q = target_id` directly.
- `target_type = 'post'` → `LEFT JOIN posts` → link the post's author.
- `target_type = 'reply'` → `LEFT JOIN post_replies` → link the reply's author.
- `target_type = 'chat_message'` → `LEFT JOIN chat_messages` → link the sender.
All joins are `LEFT` on indexed PKs; if the target row was hard-deleted (no match), render the `target_id` text **without** a link rather than crashing. Admin-module raw reads of `posts`/`post_replies`/`chat_messages`/`users` are permitted (the admin exemption from the `visible_*`-view + block-exclusion lint, per `admin-user-moderation`).
**Alternative considered:** v1 deep-links only `target_type='user'` rows, others show bare `target_id` — rejected; resolving all four is little extra code and the click-through-to-suspend value is the whole point of the queue. (Exact author/sender column names are verified at implementation against the shipped schema.)

### Decision 6 — Read access is session-gated (any valid admin session), not role-restricted — matching `admin-actions-log-viewer`
The sibling read page (`admin-actions-log-viewer`) is gated only by the `admin-login` session middleware ("the session middleware gates it"), with no role-tier restriction; `AdminPrincipal(admin_id, role)` is populated for downstream handlers but the *read* doesn't branch on `role`. This change mirrors that: any valid admin session may view the queue. Role enforcement lives where the destructive actions are — at `/admin/users` (suspend = owner/admin/moderator; permanent-ban unban = owner/admin) and in the deferred resolution write-back — not on this read surface. No new role tier is introduced, and no role-denial path is added here.

### Decision 7 — HTMX partial-swap + plain-`GET` fallback + full HTML-escaping, reusing scaffold conventions
`HX-Request: true` → return only the result-fragment element (no `<html>` wrapper); a plain `GET` returns the full page extending the base layout. Every rendered value is HTML-escaped; the user-controlled `reason_note` (and any joined display string) is the primary XSS surface. Pebble autoescaping stays on; no `| raw` on dynamic values.

## Risks / Trade-offs

- **`moderation_queue` fan-out as more triggers ship** → Decision 4 (`LATERAL … LIMIT 1`) makes the join single-row regardless of trigger count.
- **`priority` sort direction assumption** (ASC = more urgent) → aligns with the shipped `moderation_queue_status_idx (status, priority, created_at)` triage ordering; confirm against any existing convention at implementation (Open Question 1). Only affects *which* queue row is representative when several exist, not correctness.
- **Author-resolution joins add per-row cost** → bounded by the fixed page size and all on indexed PKs; negligible. Hard-deleted targets resolve to NULL → unlinked, no error.
- **Reporter / target identities are admin-visible** → intended; the admin module is the sanctioned reader for moderation triage (no spatial fuzzing / shadow-ban filtering applies to admin reads). Not a leak.
- **`reports` has no archival worker yet** → out of scope (separate Phase 3.5 "reports archival worker" item); keyset pagination scales fine in the interim.
- **Index lock on `CREATE INDEX`** → `reports` is tiny pre-launch; brief lock acceptable, mirroring V17. Rollback is `DROP INDEX` (no data impact).

## Migration Plan

1. `V19__reports_created_idx.sql` — `CREATE INDEX reports_created_idx ON reports(created_at DESC, id DESC);` (index-only; mirrors `V17__admin_actions_log_created_idx.sql`). No schema/data change. The migration set auto-boots in the test JVM (`KotestProjectConfig.beforeProject()`); no per-spec migrate call.
2. Mount `GET /admin/reports` inside the existing `authenticate(ADMIN_AUTH_NAME)` block; add Pebble template(s) extending the base layout; add a nav entry.
3. Rollback: revert the route mount (additive, no state) and `DROP INDEX reports_created_idx` if needed. No deploy-config / secret changes.
4. Docs at archive: flip `docs/07-Operations.md` §Core Features "Report Queue" from DESIGN to partially-shipped (read-only); note the resolution write-back follow-up.

## Open Questions

1. **`priority` sort direction** for the representative-queue-row selection (Decision 4) — ASC (lower number = more urgent, matching the `(status, priority, created_at)` index) vs DESC. Leaning ASC; confirm at implementation. Non-blocking (affects representative-row choice only when multiple triggers exist, which is not the case today).
2. **Fixed page size constant** — reuse `admin-actions-log-viewer`'s value for consistency; confirm the exact number at implementation.
3. **Trigger filter source** — the `trigger` filter reads from the joined `moderation_queue`; a report with no queue row is excluded when `trigger` is filtered (expected). Confirm this is the desired semantic (leaning yes — filtering by trigger inherently means "has a queue row of that trigger").
