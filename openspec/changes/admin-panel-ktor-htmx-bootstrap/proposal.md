## Why

The admin panel is the Phase 3.5 MVP-readiness gap (per [`openspec/project.md`](../../project.md) § Mobile + Admin Scaffolding Priority — Admin #2 row). Admin #1 (`admin-schema-bootstrap`, PR [#107](https://github.com/aditrioka/nearyou-id/pull/107)) shipped the V16 schema (`admin_users`, `admin_sessions` + mandatory `csrf_token_hash`, `admin_actions_log`, `admin_webauthn_*`) plus the three operational FK backfills, but **no Ktor code consumes it** — the `:backend:ktor` `admin` package today is just `SuspensionUnbanWorker.kt` + `UnbanWorkerRoute.kt` (~189 LOC, no `/admin/*` routes per [`docs/04-Architecture.md:170`](../../../docs/04-Architecture.md)). Without a Ktor route subtree, a template engine choice, and HTMX wiring, every downstream Admin #3-#5 scaffolding change ([`openspec/project.md`](../../project.md) Admin menu) is blocked. Shipping the empty route subtree + "hello admin" page + the deferred REVOKE runbook step closes the bootstrap gap as a code-light, auth-gated-off, env-gated-default-closed scaffold that downstream admin work can extend.

The admin-schema spec [`openspec/specs/admin-schema/spec.md:276`](../../specs/admin-schema/spec.md) explicitly punts the `admin_app` REVOKE/GRANT runbook step to "the Admin #2 (`admin-panel-ktor-htmx-bootstrap`) lifecycle" — this change is the one that owns codifying it.

## What Changes

- **NEW**: Ktor extension function `Application.adminPanel()` in `backend/ktor/src/main/kotlin/id/nearyou/app/admin/AdminPanelModule.kt` that mounts the `/admin/*` route subtree. Wired into [`Application.module()`](../../../backend/ktor/src/main/kotlin/id/nearyou/app/Application.kt) behind an `ADMIN_PANEL_ENABLED` env-var gate (default `false` in staging/production; `true` in dev/test). The gate exists so that an unauthenticated scaffold cannot accidentally ship to the production Cloud Run service — Admin #3 (`admin-login-argon2-totp`) flips the default after the auth gate ships.
- **NEW**: Template engine choice — **Pebble** (decided in `design.md` D1; the Ktor `io.ktor:ktor-server-pebble` artifact is officially supported, Django/Jinja-style syntax, lighter footprint than Freemarker). Templates land under `backend/ktor/src/main/resources/templates/admin/`.
- **NEW**: HTMX vendored as a static asset (`backend/ktor/src/main/resources/static/admin/htmx.min.js`, pinned version per `design.md` D2) served via Ktor's built-in static-file route. Not loaded from a CDN — keeps the admin panel self-contained and defense-in-depth-aligned (no third-party JS on the admin surface).
- **NEW**: `GET /admin` route that renders a Pebble template `index.peb` showing a "Hello admin" heading + one tiny HTMX-powered interaction (a button `<button hx-get="/admin/ping" hx-target="#ping-result">Ping</button>` that updates a `<div id="ping-result">` with the server timestamp) to verify the HTMX wiring works end-to-end.
- **NEW**: `GET /admin/ping` route returning an HTML fragment (plain `<span>` with the current UTC instant) — the HTMX target endpoint. Returns `text/html`, not JSON, matching HTMX's hypermedia model.
- **NEW**: `GET /admin/static/{file}` route serving HTMX + any future admin static assets from the classpath.
- **NEW**: Integration test `AdminPanelModuleSpec.kt` under `backend/ktor/src/test/kotlin/id/nearyou/app/admin/` exercising: (a) `ADMIN_PANEL_ENABLED=false` → `/admin` returns 404; (b) `ADMIN_PANEL_ENABLED=true` → `/admin` returns 200 + HTML containing the "Hello admin" heading; (c) `/admin/ping` returns 200 + an HTML fragment with a parseable UTC timestamp; (d) `/admin/static/htmx.min.js` returns 200 with `Content-Type: application/javascript`.
- **NEW**: Operational runbook step in [`docs/07-Operations.md`](../../../docs/07-Operations.md) § Data Access Pattern (or adjacent runbook section) codifying the Supabase Console / `gcloud` procedure for applying `REVOKE UPDATE, DELETE ON admin_actions_log FROM admin_app` and the row-level GRANTs idempotently against each environment. This closes the explicit Admin #2 deferral at [`openspec/specs/admin-schema/spec.md:276`](../../specs/admin-schema/spec.md).
- **NOT shipped here** (explicit deferrals):
  - Admin login flow, session middleware, CSRF verification — Admin #3 (`admin-login-argon2-totp`).
  - `admin_users` writes, password hashing, TOTP — Admin #3.
  - Admin business features (audit log viewer, user search, report queue) — Admin #4-#5+.
  - Subdomain split to a separate Cloud Run service at `admin.nearyou.id` — deferred to the Phase 3.5 #2 deployment work (IAP vs Cloud Armor + VPN decision per Pre-Phase 1).
  - IAP / Cloud Armor wiring — same as above.
  - The `admin_app` DB role binding in Ktor (connection-string switching) — Admin #3 (login needs the role; the scaffold "hello admin" page doesn't touch the DB).
  - Any Kotlin DTO / repository / service layer for admin tables — Admin #3+.

## Capabilities

### New Capabilities

- `admin-panel-bootstrap`: Owns the admin Ktor route subtree (`/admin/*`), Pebble template engine wiring, vendored HTMX static asset, the `ADMIN_PANEL_ENABLED` env-var gate, the "hello admin" landing page, and the `/admin/ping` HTMX target. Schema-layer concerns and auth-layer concerns are explicitly out of scope (owned by `admin-schema` and the future `admin-login` capability respectively). This capability is the first concrete Ktor consumer of the admin namespace — it establishes the file structure and conventions every later admin feature builds on.

### Modified Capabilities

None. The change adds a new capability and a new docs runbook section; no shipped spec's requirements change.

## Impact

- **Code**: Single new Kotlin module file (`AdminPanelModule.kt`, ~80-120 LOC) + Pebble template (`index.peb`, ~20 LOC) + HTMX static asset (~14KB vendored JS, pinned). One small wiring change to [`Application.module()`](../../../backend/ktor/src/main/kotlin/id/nearyou/app/Application.kt) to conditionally invoke `adminPanel()`. New Ktor dependency added via the project's version catalog: a new `ktor-serverPebble` entry in [`gradle/libs.versions.toml`](../../../gradle/libs.versions.toml) pointing at `io.ktor:ktor-server-pebble-jvm` with `version.ref = "ktor"` (inheriting the existing pinned Ktor version), referenced from [`backend/ktor/build.gradle.kts`](../../../backend/ktor/build.gradle.kts) as `implementation(libs.ktor.serverPebble)`. Matches the precedent set by every other Ktor server module declaration in those files.
- **Tests**: One new integration spec (`AdminPanelModuleSpec.kt`, ~60-90 LOC) covering enable/disable gate behavior + render + HTMX target + static asset. No DB-tagged tests (this scaffold doesn't touch the DB).
- **CI**: No CI changes. The new test runs in the standard `test` job; the new dependency adds no module imports outside `:backend:ktor`. Detekt has no admin-Ktor-specific rules to satisfy today.
- **Docs**: Three coordinated edits:
  - **NEW** runbook subsection in [`docs/07-Operations.md`](../../../docs/07-Operations.md) (REVOKE/GRANT procedure for `admin_app` role) — closes the explicit Admin #2 deferral from `admin-schema/spec.md:276`.
  - **AMEND** [`docs/04-Architecture.md:170`](../../../docs/04-Architecture.md) admin-module description — the current text says "No admin UI, no admin REST surface, no `/admin/*` routes" which becomes false the moment this change ships. Update to reflect the new scaffold (Application.adminPanel() extension + Pebble + HTMX + env-gate, default-closed in staging/prod).
  - **AMEND** [`docs/07-Operations.md:5`](../../../docs/07-Operations.md) opening status note — currently "The Admin Panel is **DESIGN** ... no `/admin/*` routes". Update to reflect that the scaffold has shipped (schema via Admin #1 + route subtree via this change) while remaining DESIGN for auth + business features.
- **Deployment / runtime**: No traffic-affecting change in staging/production because `ADMIN_PANEL_ENABLED=false` by default — the new routes are not mounted. Dev/test environments mount the routes but no admin schema/auth is exercised, so the scaffold is purely additive. The eventual subdomain split (`admin.nearyou.id` per [`docs/04-Architecture.md:306`](../../../docs/04-Architecture.md)) is unaffected by this change because the routes are mounted on the existing service; when the split happens, the routes move with no source-change required.
- **Schema/DB**: None. The scaffold does not query any admin table.
- **Downstream unblocks**:
  - **Admin #3 (`admin-login-argon2-totp`)**: login flow can now be implemented as `POST /admin/login` rendered via Pebble templates; the route subtree + template engine exist.
  - **Admin #4 (`admin-actions-log-viewer`)**: read-only audit log viewer can attach as `GET /admin/audit-log` under the same route subtree.
  - **Admin #5 (`admin-suspend-unban-user-action`)**: admin write actions land as `POST /admin/users/:id/...` under the same subtree.
- **Operator workflow**: The REVOKE runbook step gives the first admin Ktor service (Admin #3+) a documented procedure to bind the `admin_app` connection from day one of admin usage, enforcing the immutability of `admin_actions_log` at the DB role layer per [`docs/04-Architecture.md:641`](../../../docs/04-Architecture.md) and [`docs/07-Operations.md` § Data Access Pattern](../../../docs/07-Operations.md).
