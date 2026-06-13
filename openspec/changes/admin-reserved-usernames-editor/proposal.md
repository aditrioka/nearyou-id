## Why

The `reserved_usernames` table (brand handles, ops handles, impersonation-prevention entries) ships with a seeded baseline but **no in-app management surface** — today the only way to add, re-reason, or remove an `admin_added` entry is raw SQL against staging/production. As admin Phase 3.5 resumes (the mobile-first critical-path loop is demoable end-to-end, so its flip trigger has fired), the **Reserved Usernames Editor** is the next admin Core Feature whose dependencies are already fully shipped, making it a clean, low-risk, zero-migration pick that gets operators off raw SQL for routine handle hygiene.

## What Changes

- **New admin surface `GET /admin/reserved-usernames`** — paginated list of `reserved_usernames`, filterable by `source` (`seed_system` | `admin_added`), substring-searchable on `username`; HTML-escaped HTMX render + plain-`GET` fallback; readable by any authenticated admin role. Mounted in the admin sidebar (mockup frame 21).
- **`POST /admin/reserved-usernames`** — add a single entry (`username` + `reason` both required; `source` auto-set `admin_added`). A duplicate (already-reserved) is an in-band "already reserved" message — no mutation, no audit row.
- **`POST /admin/reserved-usernames/bulk`** — CSV bulk add (`username,reason` columns); duplicates skipped with a per-row report; each newly-inserted row writes its own `reserved_username_added` audit row.
- **`POST /admin/reserved-usernames/{username}/edit-reason`** — edit `reason` only, on `admin_added` rows only (app-layer guard; the V3 DB trigger does not cover seed reason edits).
- **`POST /admin/reserved-usernames/{username}/remove`** — delete, on `admin_added` rows only at the app layer, with the existing V3 `reserved_usernames_protect_seed` trigger blocking any `seed_system` delete at the DB (belt-and-suspenders, dual-layer).
- **Per-admin write cap of 100 add/edit/remove per trailing hour**, sourced by COUNT over `admin_actions_log` (the audit trail is the ledger) — **reusing** the `admin-destructive-action-rate-limit` pattern, parameterized to the reserved-username action set + a 100/hour threshold (not the 20/hour destructive cap). Over-cap is an in-band "quota exceeded" message, never a 5xx.
- **Three new audit action types** written to the immutable `admin_actions_log`: `reserved_username_added`, `reserved_username_edited`, `reserved_username_removed` (`action_type` is a free `VARCHAR(64)` — no migration).

**Non-changes (deliberate):** no Flyway migration (table, both triggers, and the `admin_app` write grants all exist since V3 / the provision script); no new library pin; no change to signup-time reserved-username rejection behavior.

## Capabilities

### New Capabilities

- `admin-reserved-usernames-editor`: the admin panel CRUD surface over `reserved_usernames` — list/filter/search (read), add single, CSV bulk add, edit-reason, and remove (writes), all CSRF- + write-role-gated, audit-logged with three dedicated action types, per-admin rate-limited at 100/hour, with seed-row protection enforced at both the app layer and the existing DB trigger.

### Modified Capabilities

<!-- None. This change adds a new admin management surface over an existing table; it does not alter signup-flow / username-generation requirements, the reserved_usernames schema, or the destructive-action rate-limit capability's own requirements (it reuses that capability's pattern, parameterized). -->

## Impact

- **Code (`:backend:ktor`, `admin` package only):** new `routes/AdminReservedUsernamesRoute.kt` (read + write handlers) + a `ReservedUsernamesRepository` (+ admin-app JDBC), new Pebble templates (list page + table fragment) following the shipped `AdminReportResolutionRoute` / `AdminUserModerationRoute` pattern, and a sidebar nav entry in `AdminLayout`.
- **Reused infra (no new patterns):** `AdminCsrfGate`, `AdminRoleGate`, `AdminAuditLogger`, `AdminPrincipal`, `clientIp`, and the audit-log-COUNT rate-limit mechanism established by `admin-destructive-action-rate-limit`.
- **Database:** writes to `reserved_usernames` (S/I/U/D) + `admin_actions_log` (INSERT). **No DDL** — table + `reserved_usernames_protect_seed` + `reserved_usernames_set_updated_at` triggers live in `V3__signup_flow.sql`; `admin_app` already has the table grants (provision-admin-app-staging.sh). Latest migration stays V20, keeping this change rebase-disjoint from the in-flight V21 claimants (#290, #291).
- **Tests:** new DB-tagged `*RoutesTest` (autoClose its HikariPool, per the CI connection budget) covering every spec scenario, plus a DB-trigger assertion (the docs Pre-Launch "reserved_usernames trigger test").
- **Docs:** mockup frame 21 is the visual target (docs/11 §3.6); a docs/11 § Pattern Registry amendment is required **only if** the rate-limit reuse is implemented as a generalized shared component rather than a parameterized sibling (decided in design.md).
- **No external APIs, no new dependencies, no secret slots.**
