## 1. Gradle wiring — Pebble dependency (via version catalog, not raw artifact strings)

- [ ] 1.1 Add a new entry to `gradle/libs.versions.toml` under the existing Ktor server module entries: `ktor-serverPebble = { module = "io.ktor:ktor-server-pebble-jvm", version.ref = "ktor" }`. The entry MUST use `version.ref = "ktor"` (inheriting the project's existing `ktor = "3.4.1"` pin); do NOT introduce a separate explicit version. Match the formatting of adjacent entries (`ktor-serverStatusPages`, `ktor-serverAuth`, etc.).
- [ ] 1.2 Add `implementation(libs.ktor.serverPebble)` to `backend/ktor/build.gradle.kts` under the existing Ktor server dependency references (alongside `implementation(libs.ktor.serverStatusPages)` etc.). Do NOT use a raw `implementation("io.ktor:...")` string — the project convention is version-catalog references.
- [ ] 1.3 Verify the dependency resolves: `./gradlew :backend:ktor:dependencies --configuration runtimeClasspath | grep pebble` should return at least one line.
- [ ] 1.4 Run `./gradlew :backend:ktor:compileKotlin` to confirm the new dependency does not introduce any classpath conflicts with existing Ktor modules (kotlinx.serialization, ContentNegotiation, etc.).
- [ ] 1.5 Verify no `:infra:*` module pulls Pebble transitively — admin templates live in `:backend:ktor` only.

## 2. Vendor HTMX

- [ ] 2.1 Download the pinned HTMX release (v2.0.x — pick the latest 2.0.z at the time of implementation, e.g., v2.0.4) from `https://unpkg.com/htmx.org@2.0.x/dist/htmx.min.js`. Save as `backend/ktor/src/main/resources/static/admin/htmx.min.js`.
- [ ] 2.2 Confirm the downloaded file's SHA-256 matches the published checksum from the HTMX release (sanity-check against a corrupted download). Document the SHA-256 in `AdminPanelModule.kt` KDoc alongside the version pin.
- [ ] 2.3 Commit the file to the repo (`backend/ktor/src/main/resources/static/admin/htmx.min.js`) — it ships as a classpath resource bundled into the production JAR.

## 3. Author `AdminPanelModule.kt`

- [ ] 3.1 Create `backend/ktor/src/main/kotlin/id/nearyou/app/admin/AdminPanelModule.kt`. Define the top-level extension `fun Application.adminPanel()`.
- [ ] 3.2 Inside `adminPanel()`, install the Pebble plugin: `install(Pebble) { loader(ClasspathLoader().apply { prefix = "templates/admin/" }) }`. The prefix lets `call.respond(PebbleContent("index.peb", ...))` resolve to `templates/admin/index.peb` without the longer path.
- [ ] 3.3 Mount the routing block: `routing { route("/admin") { ... } }` containing the GET / + GET /ping + staticResources subroute handlers.
- [ ] 3.4 Implement `GET /` (i.e., `GET /admin`): respond with `PebbleContent("index.peb", emptyMap())` (no model needed for the static "Hello admin" page).
- [ ] 3.5 Implement `GET /ping` (i.e., `GET /admin/ping`): generate the current UTC `Instant`, format as ISO-8601, render an inline HTML fragment via `call.respondText` with `ContentType.Text.Html`: `<span class="admin-ping-result" data-utc="$instant">Pong ($instant)</span>`.
- [ ] 3.6 Mount the static-asset subroute: `staticResources("/static", "static/admin")` inside the `/admin` route block (so the effective path is `/admin/static/htmx.min.js`).
- [ ] 3.7 Add a KDoc on `Application.adminPanel()` documenting: (a) the vendored HTMX version pin + SHA-256 from task 2.2, (b) the intentional absence of authentication (point to `design.md` D8 + Admin #3 as the change adding it), (c) the `ADMIN_PANEL_ENABLED` gate enforcement at the call site.
- [ ] 3.8 Verify ktlint passes on the new file (`./gradlew ktlintCheck`).

## 4. Author Pebble templates

- [ ] 4.1 Create `backend/ktor/src/main/resources/templates/admin/layout.peb` — a minimal layout stub with `<!DOCTYPE html>`, `<html>`, `<head>` (charset + title + the HTMX `<script>` include), `<body>` containing `{% block content %}{% endblock %}`. This is the future shared chrome; Admin #3 can flesh it out with header + nav.
- [ ] 4.2 Create `backend/ktor/src/main/resources/templates/admin/index.peb` — `{% extends "layout.peb" %}` + a `{% block content %}` containing the `<h1>Hello admin</h1>` heading, the `<button hx-get="/admin/ping" hx-target="#ping-result" hx-swap="innerHTML">Ping</button>`, and the `<div id="ping-result"></div>` swap target.
- [ ] 4.3 Verify the template file paths align with the Pebble loader prefix from task 3.2 (`templates/admin/` is the resource-loader root; templates reference each other as `layout.peb` etc.).
- [ ] 4.4 No inline JavaScript besides the HTMX `<script>` include — verify by `grep -c '<script' backend/ktor/src/main/resources/templates/admin/*.peb` returning exactly 1 (the HTMX include in `layout.peb`).

## 5. Wire `adminPanel()` into `Application.module()`

- [ ] 5.1 In `backend/ktor/src/main/kotlin/id/nearyou/app/Application.kt`, add a top-level function `private fun adminPanelEnabled(env: String): Boolean` that implements the gate logic per spec § ADMIN_PANEL_ENABLED env-var gate (true when env var is literal `"true"` OR unset-in-dev-or-test; false otherwise including unrecognized values).
- [ ] 5.2 Inside `Application.module()`, after the existing route wiring block (after `fcmTokenRoutes(...)` and before the `routing { route("/internal") ... }` block), insert: `if (adminPanelEnabled(ktorEnv)) { adminPanel() }`.
- [ ] 5.3 Add an import for `id.nearyou.app.admin.adminPanel` at the top of `Application.kt`.
- [ ] 5.4 Verify the wiring against the spec requirement "Default-closed in production" by smoke-testing the env-gate function in isolation via task 6.5.

## 6. Integration test `AdminPanelModuleSpec`

- [ ] 6.1 Create `backend/ktor/src/test/kotlin/id/nearyou/app/admin/AdminPanelModuleSpec.kt` as a Kotest `StringSpec` (or `FunSpec` — match the precedent in the package).
- [ ] 6.2 Test "gate=false, staging → /admin returns 404": set `ADMIN_PANEL_ENABLED=` (unset) + `ktor.environment=staging` in test config, boot a Ktor test application via `Application.module()`, assert `GET /admin` returns 404.
- [ ] 6.3 Test "gate=true, dev → /admin returns 200 + Hello admin": set `ktor.environment=dev` (env-var unset, so dev-default-true applies), assert response status 200, `Content-Type` starts with `text/html`, body contains `<h1>Hello admin</h1>`, contains a `<button>` with `hx-get="/admin/ping"`, contains `<div id="ping-result">`.
- [ ] 6.4 Test "gate=true, /admin/ping → HTML fragment with parseable data-utc": call `GET /admin/ping`, assert response status 200, `Content-Type` starts with `text/html`, body does NOT contain `<!DOCTYPE>` or `<html>`, body contains `<span class="admin-ping-result" data-utc="...">`, parse the `data-utc` value via `Instant.parse(...)` and assert it's within ±5 seconds of `Instant.now()` at test-call time.
- [ ] 6.5 Test "gate=true, /admin/static/htmx.min.js → vendored asset served": call `GET /admin/static/htmx.min.js`, assert response status 200, `Content-Type` is `application/javascript` OR `text/javascript`, body length > 1024 bytes (sanity-check against an accidentally-empty file).
- [ ] 6.6 Test "explicit ADMIN_PANEL_ENABLED=true in staging → /admin returns 200": override env var, assert mounting works for the smoke override path.
- [ ] 6.7 Test "explicit ADMIN_PANEL_ENABLED=false in dev → /admin returns 404": override env var, assert dev-default-true is overridable.
- [ ] 6.8 Test "unrecognized env-var value (e.g., 'yes') → treated as false → /admin returns 404 in dev": cover the defensive-parsing scenario.
- [ ] 6.9 Test "rendered /admin contains only one `<script>` tag, the HTMX include": parse the response body and assert exactly one `<script>` element exists with `src="/admin/static/htmx.min.js"` and no other `<script>` elements (defends against accidental inline JS leakage from future template edits).
- [ ] 6.10 Verify all tests pass via `./gradlew :backend:ktor:test --tests "*AdminPanelModuleSpec*"`.

## 6b. Coordinated doc amendments (per `design.md` D9)

- [ ] 6b.1 Amend `docs/04-Architecture.md:170` — the admin module bullet currently reads "`SuspensionUnbanWorker` + `UnbanWorkerRoute` ONLY. **No admin UI, no admin REST surface, no `/admin/*` routes.**" Update to reflect the new scaffold: e.g., "`SuspensionUnbanWorker` + `UnbanWorkerRoute` + admin panel scaffold (`Application.adminPanel()`, Pebble + HTMX, gated behind `ADMIN_PANEL_ENABLED` env var — default-closed in staging/prod, default-open in dev/test). No auth yet (Admin #3); no business UI features (Admin #4+)." Preserve any surrounding context (the line about "Per `docs/07-Operations.md` § Admin Panel ..." can stay but its tail framing may need a small adjustment so it doesn't contradict the new opening.)
- [ ] 6b.2 Amend `docs/07-Operations.md:5` opening status note — the current "The Admin Panel is **DESIGN** — Phase 3.5 deferred-to-post-MVP per `08-Roadmap-Risk.md`" becomes inaccurate post-change. Update to reflect schema (V16 via Admin #1) and scaffold (this change) have shipped, while auth + business features remain DESIGN per Phase 3.5. Suggested rewrite: "**Status (2026-05-18).** The Admin Panel is **PARTIAL** — Phase 3.5 schema + scaffold have shipped (`admin-schema-bootstrap` Admin #1 + `admin-panel-ktor-htmx-bootstrap` Admin #2). The `:backend:ktor` `admin` package now contains `SuspensionUnbanWorker.kt` + `UnbanWorkerRoute.kt` + `AdminPanelModule.kt` (route subtree, Pebble templates, vendored HTMX, env-gated default-closed in staging/prod). **No auth, no business UI — Admin #3 (login) and Admin #4+ (audit log viewer, etc.) remain DESIGN per Phase 3.5.**"
- [ ] 6b.3 Verify the amendments preserve markdown rendering (no broken links, no malformed list bullets) by running `grep -n "/admin/\\*" docs/04-Architecture.md docs/07-Operations.md` and confirming the only matches reflect the new scaffold reality.
- [ ] 6b.4 Confirm the test scenarios from spec § "docs/04-Architecture.md admin module description is amended" and § "docs/07-Operations.md opening status note is amended" all pass against the amended files (this is a self-check — the test code in section 6 above does NOT exercise doc content; these checks are manual `grep` / re-read by the change author at task-tick time).

## 7. Operational runbook section in `docs/07-Operations.md`

- [ ] 7.1 Locate the existing `### Data Access Pattern` heading within `## Admin Panel — DESIGN` in `docs/07-Operations.md`.
- [ ] 7.2 Add a new subsection immediately after § Data Access Pattern titled `### Applying admin_app REVOKE / GRANT statements per environment`.
- [ ] 7.3 Document the exact `REVOKE` statement: `REVOKE UPDATE, DELETE ON admin_actions_log FROM admin_app;`
- [ ] 7.4 Document the complete `GRANT` set: row-level access to `users`, `posts`, `post_replies`, `reports`, `moderation_queue`, `admin_actions_log` (SELECT + INSERT only — UPDATE + DELETE are REVOKE'd above), `csam_detection_archive`, etc., matching the per-table description already in § Data Access Pattern prose.
- [ ] 7.5 Document the Supabase Console procedure: Dashboard → Database → Roles → `admin_app` → Edit grants (with the equivalent UI-click sequence and a note that the `admin_app` role must be pre-provisioned per `docs/08-Roadmap-Risk.md` Pre-Phase 1 #28).
- [ ] 7.6 Document the `gcloud sql connect` + `psql` shell alternative for restricted-Console environments (the literal command + the `\du admin_app` verification step).
- [ ] 7.7 Document the idempotency note: REVOKE is idempotent in Postgres (no-ops if permission not held); GRANT may need a pre-REVOKE if narrowing.
- [ ] 7.8 Document the verification query: `SELECT grantee, privilege_type FROM information_schema.table_privileges WHERE table_name = 'admin_actions_log' AND grantee = 'admin_app';` with the expected post-REVOKE result (only SELECT and INSERT rows).
- [ ] 7.9 Cross-link from the new subsection back to `openspec/specs/admin-schema/spec.md` (the requirement that pointed at this Admin #2 lifecycle deferral).
- [ ] 7.10 Verify the new subsection appears within 200 lines of the § Data Access Pattern heading (per spec scenario "Runbook section is discoverable from § Data Access Pattern").

## 8. Validate locally + spec validate

- [ ] 8.1 Run `openspec validate admin-panel-ktor-htmx-bootstrap --strict` and confirm no errors.
- [ ] 8.2 Run `./gradlew ktlintCheck` and fix any style violations.
- [ ] 8.3 Run `./gradlew detekt` and fix any detekt violations.
- [ ] 8.4 Run `./gradlew :backend:ktor:test` to confirm the new spec passes + no existing test regressions.
- [ ] 8.5 Run `./gradlew :lint:detekt-rules:test` to confirm no detekt-rule regression.
- [ ] 8.6 Boot the dev server locally via `./gradlew :backend:ktor:run` (or the existing dev compose entrypoint) and `curl http://localhost:8080/admin` returns 200 + the rendered page; `curl http://localhost:8080/admin/ping` returns the fragment; `curl http://localhost:8080/admin/static/htmx.min.js` returns the JS.

## 9. Pre-archive staging smoke

- [ ] 9.1 Confirm task 8.1 through 8.6 passed.
- [ ] 9.2 Manually trigger staging deploy on the branch: `gh workflow run deploy-staging.yml --ref admin-panel-ktor-htmx-bootstrap`.
- [ ] 9.3 Poll the deploy run until success or 5 min timeout: `gh run list --workflow=deploy-staging.yml --branch admin-panel-ktor-htmx-bootstrap --limit 1` + `gh run view <id>`.
- [ ] 9.4 Smoke: `curl https://api-staging.nearyou.id/admin` returns 404 (default-closed in staging — matches spec scenario "Default-closed in staging").
- [ ] 9.5 Smoke (override path): `gcloud run services update <staging-service> --update-env-vars ADMIN_PANEL_ENABLED=true --region <region>`, wait 30s, re-curl `/admin` → expect 200 + Hello admin in body. Then revert: `gcloud run services update <staging-service> --remove-env-vars ADMIN_PANEL_ENABLED`. Final curl → 404 again.
- [ ] 9.6 Verify `/admin/ping` returns a fragment with a parseable `data-utc` within ±5s of wall-clock during the smoke window.
- [ ] 9.7 Verify `/admin/static/htmx.min.js` returns the vendored file from the deployed JAR.

## 10. Archive

- [ ] 10.1 Run `openspec archive admin-panel-ktor-htmx-bootstrap` locally.
- [ ] 10.2 Confirm the archive command (a) moves `openspec/changes/admin-panel-ktor-htmx-bootstrap/` to `openspec/changes/archive/<date>-admin-panel-ktor-htmx-bootstrap/` AND (b) creates / updates `openspec/specs/admin-panel-bootstrap/spec.md` synced from the delta.
- [ ] 10.3 Run `openspec validate --specs admin-panel-bootstrap --strict` and confirm no errors on the synced spec.
- [ ] 10.4 Stage + commit + push the archive commit on the same change branch.
- [ ] 10.5 Update the PR title + body per the one-PR-per-change convention's archive-phase refresh (drop the in-progress framing; list final scenarios + capability deltas + post-merge tasks if any).
- [ ] 10.6 Final squash-merge to `main`.
