## Context

Admin #1 ([`admin-schema-bootstrap`, PR #107](https://github.com/aditrioka/nearyou-id/pull/107)) shipped the V16 `admin_actions_log` table (audit-trail-preserving by design: `admin_id NOT NULL` FK with the `NO ACTION` default + role-level `REVOKE UPDATE, DELETE` for `admin_app`). Admin #3 ([`admin-login-argon2-totp`, PR #121](https://github.com/aditrioka/nearyou-id/pull/121)) made it runtime-written (four login/CSRF action types) and shipped the session + CSRF middleware that gates every authenticated `/admin/*` route. Admin #2 ([`admin-panel-ktor-htmx-bootstrap`, PR #115](https://github.com/aditrioka/nearyou-id/pull/115)) shipped the Pebble + HTMX route subtree (`Application.admin()`) + the shared base layout with a nav stub.

This change is the **first admin business feature** and the read counterpart to that write trail: a read-only audit-log viewer at `GET /admin/actions-log`. It ships **zero migrations** (V16 schema is fixed) and **zero new library pins** (Pebble, HTMX, JDBC, the session/CSRF gate are all already present) — so it is not a substrate-introducing change and the pre-implementation library re-check does not apply.

Stakeholder posture: solo operator (Oka), pre-launch. The audit log today holds only login/CSRF rows; as Admin #5+ land destructive write actions, the trail grows and the viewer becomes the moderation-accountability surface described in [`docs/07-Operations.md`](../../../docs/07-Operations.md) § Core Features ("immutable, retained 1 year, filter by admin/target/action").

Threat model for a *read* surface: the audit log can contain operationally-sensitive data (`before_state` / `after_state` JSONB may embed user content snapshots; `ip` / `user_agent` are PII-adjacent). The viewer is reachable only behind the Admin #3 auth gate (Argon2id + TOTP + `__Host-` session cookie) and, in production, behind network-layer IAP / Cloud Armor (Phase 3.5). Output is HTML-escaped (Pebble autoescape) to prevent stored-content XSS via a malicious `reason` / state payload rendered in the admin's browser. No new write capability is introduced, so the CSRF surface is unchanged.

Constraints:

- Schema fixed (V16). The three secondary indexes (`admin_actions_admin_idx`, `admin_actions_target_idx`, `admin_actions_type_idx`, all `… , created_at DESC` where applicable) dictate which filters can be index-served — the filter set is chosen to align (D2).
- The 16 critical invariants in [`CLAUDE.md`](../../../CLAUDE.md) § "Critical invariants" apply. Relevant here: **raw-read exemption** — `admin_actions_log` is an admin table read inside the admin module, where the `visible_*`-view rule and the block-exclusion-join rule explicitly do NOT apply (per [`openspec/project.md`](../../project.md) § Coding Conventions: "Raw reads allowed only in Repository own-content paths and the admin module"). No `clientIp` / rate-limit / username-write / privacy-write surface is touched (read-only). No secret read (no `secretKey(env, name)` call). No vendor SDK import.
- Same-PR convention per [`openspec/project.md`](../../project.md) § Change Delivery Workflow — proposal + feat + archive on one branch, one squash-merge.

Stakeholders: Oka (sole admin). Reviewers: multi-lens sub-agents (proposal phase); qodo on the implementation diff at `/opsx:apply` step 8.

## Goals / Non-Goals

**Goals:**

- Ship a read-only, paginated, filterable `admin_actions_log` viewer at `GET /admin/actions-log`, behind the existing auth gate, rendering newest-first.
- Make every audit row written by Admin #3 (and every future write action) reviewable in-app — close the "logged but unobservable" gap.
- Keep filters index-aligned so the viewer stays fast as the log grows; use keyset pagination, not offset.
- Preserve the read-only posture: no mutation routes; rely on the operational `admin_app` `REVOKE` for DB immutability.
- Author a test matrix that pins each behavior (auth gate, filter composition, keyset paging, JSONB escaping, no-mutation-routes, empty state) as a regression-resistant assertion.
- Establish the `AdminActionsLogRepository` + filter-parse + table-fragment-template pattern that later admin *read* features (report queue, user search) can inherit.

**Non-Goals:**

- Admin write actions (suspend/unban is Admin #5; redaction/CSAM/flags are later changes).
- `target_id` → target-entity deep links (target pages don't exist yet).
- CSV/JSON export; full-text JSONB search (no GIN index); retention/archival worker; RLS on `admin_actions_log`; auto-refresh/polling.

## Decisions

### D1: Keyset pagination on `(created_at, id)` DESC — not SQL `OFFSET`

**Choice:** Fixed page size of **50**. Ordering `ORDER BY created_at DESC, id DESC`. "Older" navigation passes an opaque cursor token encoding the last-seen `(created_at, id)`; the next query adds `WHERE (created_at, id) < (:cursorCreatedAt, :cursorId)` (row-value comparison). Fetch `LIMIT 51` — if 51 rows return, the 51st is dropped from display and signals "there is an older page" (its `(created_at, id)` becomes the next cursor). No "page N of M" / total-count query.

**Rationale:**

- `admin_actions_log` is append-only and unbounded (1-year+ retention). SQL `OFFSET n` scans + discards `n` rows server-side — cost grows linearly with depth — and *drifts* when new rows are inserted between page loads (a row can be skipped or shown twice). Keyset paging is O(log n) per page via the index and is stable under concurrent inserts.
- Row-value comparison `(created_at, id) < (?, ?)` is directly index-servable by the DESC indexes. `id` (a UUID) is the deterministic tiebreaker for rows sharing a `created_at` (sub-millisecond bursts during a scripted action).
- The cursor is `base64url("<epochMillisOrIso>|<uuid>")`. It is *opaque to the user* but not a security boundary — it only encodes a sort position over data the admin is already authorized to read; tampering at worst returns a different valid page. The handler validates the decoded shape and ignores a malformed cursor (treats as first page) rather than erroring.

**Trade-off:** no random-access "jump to page 7" and no total count. For an audit log reviewed newest-first with filters, that is the right ergonomic — and total-count over a growing log is itself an expensive query we avoid.

### D2: Filter set = union of the two canonical sources, all index-aligned

`docs/07-Operations.md` § Core Features specifies "filter by **admin/target/action**". The scaffolding menu ([`openspec/project.md`](../../project.md) § Mobile + Admin Scaffolding Priority, Admin #4 row) specifies "filter by **action type / admin / date range**". These diverge. **Resolution: ship the union** — `action_type`, `admin_id`, `target_type` (+ optional `target_id`), and a `from`/`to` date range — because each equality filter is served by a dedicated index and the date range is served by the `created_at DESC` component of whichever equality index the query selects; the union is strictly more useful at no added cost:

| Filter | Param(s) | Index served | Predicate |
|---|---|---|---|
| Action type | `action_type` | `admin_actions_type_idx (action_type, created_at DESC)` | `action_type = ?` |
| Admin | `admin_id` (UUID) | `admin_actions_admin_idx (admin_id, created_at DESC)` | `admin_id = ?` |
| Target | `target_type` (+ `target_id`) | `admin_actions_target_idx (target_type, target_id)` | `target_type = ?` [`AND target_id = ?`] |
| Date range | `from`, `to` (ISO-8601 date) | `created_at DESC` component of all three indexes | `created_at >= ?` / `created_at < ?` (exclusive upper, whole-day inclusive) |

This is a **bucket-(a)-adjacent reconciliation**: neither source is *wrong*, they are differently-scoped. Both are pre-launch design docs (not "cite a shipped PR as source of truth"), and the union satisfies both. No doc amendment is filed — the divergence is two overlapping subsets of the same shippable feature, recorded here so reviewers see the rationale. Filters compose with AND. When multiple filters are present, Postgres picks the most selective index; the others apply as filter conditions on the fetched rows. **Caveat on the date-only case:** a `from`/`to` filter with NO equality filter alongside it has no dedicated leading-column index — the planner will scan + sort by `created_at` rather than do a pure index range-scan. That is acceptable for a pre-launch, low-volume audit log (and the common path pairs date with an `action_type`/`admin_id`/`target` filter that does lead with an index), but the design does not claim a pure index path for the standalone date-range query. `from`/`to` parse as whole dates in the server's timezone interpretation of the column (`TIMESTAMPTZ`); `to` is treated as inclusive-of-the-whole-day via a `< to + 1 day` exclusive upper bound (documented so a reviewer doesn't read it as an off-by-one).

### D3: HTMX partial swap with plain-GET progressive enhancement

The filter `<form>` uses `hx-get="/admin/actions-log"` + `hx-target="#actions-log-table"` + `hx-swap="outerHTML"` + `hx-push-url="true"`; the "Older" control is an `hx-get` to the cursor URL targeting the same fragment. The handler branches on the `HX-Request: true` header:

- **HX-Request present** → respond with ONLY the `actions-log-table.peb` fragment (filter form stays put; table swaps in place; URL updated via `hx-push-url` so the filtered view is shareable/bookmarkable/back-button-correct). The fragment does NOT extend the base layout, so it carries no CSRF meta tag (none needed — the table swap is a GET).
- **No HX-Request** (plain navigation, no JS, or a shared link opened cold) → render the full `actions-log.peb` page, which extends the shared base layout `layout.peb` (the shipped scaffold layout — flat name, no underscore prefix; matches `index.peb` / `login.peb`) and `{% include %}`s the same `actions-log-table.peb` fragment, so the feature works identically without JavaScript.

This mirrors the dual-mode the `admin-login` redirect adopted (HX-Redirect vs 303) and the project's progressive-enhancement stance. The fragment is included by the full page, so there is one source of truth for the table markup.

**CSRF token in the full-page model (mandatory).** The shared `layout.peb` renders its `<meta name="csrf-token">` tag + the `htmx:configRequest` injection script + the hidden `_csrf` input ONLY inside `{% if csrfToken is not null %}` (so the logout POST form and any future authenticated HTMX action carries the CSRF header). The full-page render of the viewer therefore MUST populate `csrfToken` in its Pebble model exactly as `AdminIndexRoute` does — derived from the `__Host-admin_session` cookie via `HashUtil.deriveCsrfFromSessionToken(sessionToken, csrfHmacKeyProvider())`. Consequently `fun Route.adminActionsLog(...)` takes the `csrfHmacKeyProvider: () -> ByteArray` (the same provider `AdminModule.admin(...)` already threads to `adminIndex(...)`). The HTMX *fragment* response omits `csrfToken` (it renders no layout). Omitting this on the full page is the B2 bug the proposal-review caught: the authenticated page would render without CSRF wiring and silently diverge from `/admin/`.

### D4: No audit-on-read

Viewing the audit log does NOT write an `admin_actions_log` row. Reads are not auditable admin *actions* — `admin_actions_log` is the in-app surface for admin-actor *operations* (per the docs: "Every destructive / high-impact admin operation writes an `admin_actions_log` row"). This is consistent with `admin-login`'s decision (D14 of that change) to route the non-actor `email_not_found` failure to the structured INFO logger rather than the audit table. If a future compliance requirement demands read-access logging, it ships as its own change (likely a structured-log line, not an `admin_actions_log` row, to avoid the viewer surfacing "admin viewed the log" noise that would dominate the trail).

### D5: Accessible to all authenticated admin roles, including `read_only`

The viewer is gated only by "is there a valid session" — every role in the V16 `admin_users.role` allowlist (`owner`, `admin`, `moderator`, `read_only`) may read it. Rationale: the audit log is an accountability surface; transparency across roles is the point, and `read_only` exists precisely for review-without-mutation access. `AdminPrincipal.role` is available if a future change wants to redact specific `action_type`s from lower roles, but no role-based redaction ships here (it would be premature and is not in the docs). This is recorded so a security reviewer doesn't read the absence of a role check as an omission — it is a deliberate "authenticated-is-sufficient" decision for a read-only audit surface.

### D6: "Audit Log" nav link is an implementation task, not a spec modification

The `admin-panel-scaffold` base-layout requirement defines the nav as "placeholder links — no functional pages behind them **until subsequent admin changes ship**." The shipped `layout.peb` already carries an `<li><a href="#">Audit log (stub)</a></li>` placeholder entry; this change *edits that existing stub* — points its `href` at `/admin/actions-log` and drops the "(stub)" suffix — rather than adding a new link. That is exactly the "subsequent admin change" the requirement anticipates — it does not contradict or modify the requirement, so no `MODIFIED admin-panel-scaffold` delta is filed. The edit lives in `layout.peb` and is covered by a route/template test, but the capability spec stays scoped to the viewer itself. (If a later change makes the nav data-driven or role-filtered, *that* would warrant a scaffold-spec modification.)

### D7: `AdminActionsLogRepository` — parameterized, dynamic-WHERE, admin-module raw read

A new `AdminActionsLogRepository(dataSource)` mirrors the `SessionRepository` / `AdminUserRepository` JDBC pattern from Admin #3 (`PreparedStatement`, no ORM). The query is `SELECT … FROM admin_actions_log l JOIN admin_users u ON u.id = l.admin_id WHERE <dynamic> ORDER BY l.created_at DESC, l.id DESC LIMIT ?`. The dynamic WHERE is assembled from the active filters as parameterized fragments (`?` placeholders bound positionally) — **never** string-interpolated values — so the filter surface carries no SQL-injection risk. The `admin_users` join uses `JOIN` (not `LEFT JOIN`): `admin_id` is `NOT NULL` and the FK guarantees a matching row, so every audit row has an actor (the `system`-actor sentinel is a separate deferred concern and, if it ever lands, is a real `admin_users` row that the `JOIN` resolves). The admin module is exempt from the `RawFromPostsRule` / `BlockExclusionJoinRule` Detekt rules; `admin_actions_log` / `admin_users` are not user-content tables and require no `visible_*` view or block-exclusion join.

### D8: All audit-row values render escaped; JSONB on-demand detail

Every rendered audit-row value is HTML-escaped — not just the two JSONB columns. The summary table row shows the structured columns (`created_at`, actor, `action_type`, `target_type`/`target_id`, `reason`, `ip`, `user_agent`); `before_state` / `after_state` (`JSONB`, potentially large or embedding user-content snapshots) render inside a collapsible per-row detail (`<details>` element — no JS dependency) as pretty-printed text. **All** of these are escaped via Pebble's default-on autoescaping; the templates MUST NOT use the `raw` filter on ANY audit-row value. The highest-risk vectors are the free-text `reason` and especially `user_agent` — the latter is *fully client-controlled* at write time (an attacker can set any `User-Agent` string when probing the login endpoint, and that string is persisted into the audit row), so it must be escaped when rendered inline in the summary table, not only the JSONB detail. This prevents a stored-XSS where a crafted `reason` / `user_agent` / state payload executes in the reviewing admin's browser. `NULL` `before_state` / `after_state` columns render as an em-dash, not the literal string "null". (Pebble autoescaping is confirmed default-on: the shipped `install(Pebble)` in `AdminModule.kt` sets no `autoEscaping(false)` override.)

## Risks / Trade-offs

- **Keyset paging gives no total count / random access.** Accepted (D1) — correct for an append-only audit log reviewed newest-first; total-count queries over a growing table are the cost we are avoiding.
- **No role-based redaction** (D5) — every authenticated admin sees every row. For a solo operator this is correct; if multi-admin with tiered visibility is needed later, it is an additive change keyed on `AdminPrincipal.role`.
- **JSONB rendered escaped but un-searchable** — no GIN index ships (out of scope). If `reason`/state search becomes a real need, it is a separate schema change. Trade-off: keeps this change migration-free.
- **`from`/`to` timezone interpretation** — dates are interpreted against the `TIMESTAMPTZ` column; documented in D2 to avoid an off-by-one reading. Tests pin the boundary behavior.

## Migration Plan

No DB migration (V16 schema is fixed). No new dependency pins. Rollout is a code-only deploy: the new route mounts inside the existing `authenticate(ADMIN_AUTH_NAME)` block, the templates are added under `resources/templates/admin/`, and the nav link appears in the base layout. Pre-archive staging smoke (D-context) validates against the staging test admin. No rollback complexity — reverting the commit removes the route + templates with no schema or data residue.

## Open Questions

- **Page size 50** is a reasonable default; if smoke shows the table is unwieldy on the admin's screen, it can be tuned without spec change (it is an implementation constant, not a spec'd contract — the spec asserts "fixed page size + keyset paging", not the literal 50).
- **Cursor token format** (`base64url("<created_at>|<id>")`) is an internal detail; the spec asserts the pagination *behavior* (stable, newest-first, no offset drift), not the token encoding, so it can change without a spec amendment.
