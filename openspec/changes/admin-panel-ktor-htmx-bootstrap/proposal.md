## Why

The admin schema shipped in PR [#107](https://github.com/aditrioka/nearyou-id/pull/107) (`admin-schema-bootstrap`), but no admin-UI code consumes it yet — the `:backend:ktor` `admin` package contains only `SuspensionUnbanWorker.kt` + `UnbanWorkerRoute.kt` serving the internal `/internal/unban-worker` tick, and there are zero `/admin/*` routes, zero template engine wiring, and zero HTMX client surface. This change scaffolds the foundation that every subsequent admin change depends on: a mounted `/admin/*` Ktor route subtree, a server-side templating engine, and HTMX as the client interaction layer. It is the Admin #2 pick in [`openspec/project.md`](../../project.md) § Mobile + Admin Scaffolding Priority — the natural next step now that Admin #1 (schema) has shipped, and a prerequisite for Admin #3 (`admin-login-argon2-totp`), Admin #4 (`admin-actions-log-viewer`), and Admin #5 (`admin-suspend-unban-user-action`).

## What Changes

- **NEW** `Application.admin()` extension function (mirrors `Application.module()` shape) that mounts the `/admin/*` route subtree under the existing in-process `:backend:ktor` deployable. Wired into the main `Application.module()` call.
- **NEW** server-side templating engine wired into Ktor (Pebble vs Freemarker decided in [`design.md`](design.md) Decision 1).
- **NEW** HTMX client interaction layer available on every admin page (CDN reference vs vendored file decided in [`design.md`](design.md) Decision 2).
- **NEW** shared base layout template (header + nav stub + footer) — no functional pages behind the nav links yet.
- **NEW** `/admin/` index route serving a "Admin Panel" page that extends the base layout.
- **NEW** static-asset serving wired under `/admin/static/*` from `src/main/resources/admin/static/` (per [`design.md`](design.md) Decision 4) — populated only if Decision 2 picks the vendored HTMX path.
- **NEW** Ktor `testApplication` test coverage (per [`design.md`](design.md) Decision 5) asserting six scenarios: `/admin/` returns 200 + body identifier, layout template renders with header/nav/footer/content-block markers, HTMX asset returns 200 + accept-list `Content-Type` (`application/javascript` OR `text/javascript`), path-traversal under `/admin/static/` returns 404, `POST /admin/` returns 405 Method Not Allowed, every `/admin/*` hit emits a WARN log line with the deferred-auth marker (per Decision 6 + spec Req 4 Sc 2 rewrite — observable scaffolding-posture signal that disappears when Admin #3 lands). Plus a smoke-grade template-not-found assertion (per Decision 5) and a Decision 6 mount-guard test (`KTOR_ENV=production` config → `/admin/` returns 404).
- **NEW** capability `admin-panel-scaffold` (5 requirements, 11 scenarios — see [`specs/admin-panel-scaffold/spec.md`](specs/admin-panel-scaffold/spec.md)). Round 1 review added Requirement 5 (`KTOR_ENV` production mount guard) + extended Requirements 1, 3, and 4 with additional scenarios (POST 405, path-traversal 404, WARN log signal replacing the original untestable source-comment scenario).
- **Explicitly OUT of scope** (deferred to later Admin changes): authentication / login (Admin #3), session management + CSRF token issuance (Admin #3), any DB queries from admin routes (Admin #4+), `admin.nearyou.id` subdomain DNS + IAP/Cloud Armor + separate Cloud Run service deployment (Phase 3.5 deployment task #2 per [`docs/08-Roadmap-Risk.md`](../../../docs/08-Roadmap-Risk.md) § Phase 3.5), WebAuthn / TOTP / Argon2id (Admin #3).
- **NO** interaction with the 16 code-level invariants from [`openspec/project.md`](../../project.md) § Coding Conventions & CI Lint Rules — no `visible_*` views, no `clientIp`, no rate-limit TTL, no Redis hash tags, no username/privacy writes, no `admin_sessions` INSERT (the `csrf_token_hash` Detekt rule is in place but no INSERT site exists in this change). Reviewers should NOT expect invariant test additions; future admin changes will exercise these surfaces.

## Capabilities

### New Capabilities

- `admin-panel-scaffold`: route subtree + templating + HTMX scaffold under `/admin/*` — the foundation that subsequent admin features (login, audit log viewer, suspend/unban action) build on. Deliberately ships an unauthenticated subtree for scaffold-validation purposes (in non-prod environments only — guarded by Requirement 5's `KTOR_ENV` check); the auth gate lands in Admin #3 (`admin-login-argon2-totp`).

### Modified Capabilities

None.

## Impact

- **New Gradle dependencies** on `:backend:ktor`:
  - Ktor templating module for the chosen engine (`ktor-server-pebble-jvm` OR `ktor-server-freemarker-jvm`) — pinned via `gradle/libs.versions.toml` per the version-pinning policy in [`docs/09-Versions.md`](../../../docs/09-Versions.md); justify the pin in the Version Decisions table.
  - HTMX is a JS library, not a JVM dep — no Gradle entry. Vendored asset (if Decision 2 picks vendored) ships under `backend/ktor/src/main/resources/admin/static/htmx.min.js`.
- **New file tree** under `backend/ktor/src/main/`:
  - `kotlin/id/nearyou/app/admin/AdminModule.kt` (or similar) — the `Application.admin()` extension function.
  - `kotlin/id/nearyou/app/admin/routes/AdminIndexRoute.kt` (or similar) — the `/admin/` index route.
  - `resources/templates/admin/layout.<ext>` — base layout template (extension depends on engine: `.peb` for Pebble, `.ftl` for Freemarker).
  - `resources/templates/admin/index.<ext>` — index page extending the layout.
  - `resources/admin/static/htmx.min.js` (conditional on Decision 2).
- **New test file** under `backend/ktor/src/test/`:
  - `kotlin/id/nearyou/app/admin/AdminScaffoldTest.kt` (or similar) — Ktor `testApplication` assertions per Decision 5.
- **Modification to existing** `backend/ktor/src/main/kotlin/id/nearyou/app/Application.kt` (or wherever `Application.module()` lives) to wire `admin()` into the module bootstrap. One-line call.
- **No DB impact** — zero new migrations, zero new queries, zero schema reads.
- **No API impact** on `/api/v1/*` — admin routes mount on a disjoint `/admin/*` namespace.
- **No CI lint impact** — no new Detekt rules needed; existing `csrf_token_hash` rule is dormant (no INSERT site yet).
- **New CI step**: a one-line `sha256sum -c backend/ktor/src/main/resources/admin/static/htmx.min.js.SHA256SUMS` in the existing `lint` job (per [`design.md`](design.md) Decision 2 Vendored file provenance + Advisory monitoring subsections) — fail-fasts on vendored-HTMX file/hash drift.
- **FOLLOW_UPS gate** (`admin-app-revoke-staging-and-prod`): N/A for this scaffold. That entry blocks Admin-#2 squash-merge until the `admin_app` DB role REVOKE has landed; this change does NOT consume `admin-app-db-connection-string` (DB wiring is deferred to Admin #4+ per Non-Goals), so the gate's "no DB access yet" precondition is trivially satisfied. The gate becomes load-bearing for Admin #4 onward.
- **`docs/07-Operations.md:5` Status callout** currently says "no `/admin/*` routes, no admin UI, no Pebble templates" — after this change squash-merges, three of those four claims become false. [`tasks.md`](tasks.md) Section 7 now includes a step to amend the callout to reflect the route + Pebble + HTMX scaffold landing (auth gate + business features remain DESIGN).
- **Deployment posture (in-process, NOT separate service):** in this scaffold the admin subtree runs inside the SAME Ktor deployable as the main API. The Phase 3.5 plan in [`docs/07-Operations.md`](../../../docs/07-Operations.md) § Stack calls for `admin.nearyou.id` on a separate Cloud Run service behind IAP/Cloud Armor — that's the eventual extraction target but is deliberately deferred so this change can ship the route-scaffold cleanly. The `Application.admin()` extension-function shape (per [`design.md`](design.md) Decision 3) makes the eventual extraction-to-separate-service clean.
- **Risk of accidental exposure post-deploy:** because the admin subtree is unauthenticated in this scaffold AND runs on the main `api.nearyou.id` service, after squash-merge the `/admin/` route IS reachable from the public internet on staging (and would be on prod if this somehow shipped to prod before Admin #3). Mitigation: (a) the `/admin/` page contains zero sensitive data — just a "Admin Panel" stub; (b) the in-route comment from spec Requirement 4 makes the temporary-open-subtree intent explicit; (c) production isn't live yet (pre-Public-Launch); (d) Admin #3 lands the auth gate before any sensitive admin surface goes behind these routes. Surfacing here so the squash-merge reviewer can sanity-check.
