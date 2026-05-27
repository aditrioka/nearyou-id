## 1. Setup — dependencies + version pinning

- [ ] 1.1 Verify the Pebble Ktor module artifact coordinate (`io.ktor:ktor-server-pebble-jvm`) is published at the project's current Ktor version (`ktor = "3.4.1"` in `gradle/libs.versions.toml`); if not, surface the actual published coordinate + version to use.
- [ ] 1.2 Add the Pebble library reference to `gradle/libs.versions.toml` under `[libraries]` (e.g., `ktor-serverPebble = { module = "io.ktor:ktor-server-pebble-jvm", version.ref = "ktor" }`) — share the existing `ktor` version variable, NOT a separate coordinate.
- [ ] 1.3 Wire the new library into the `:backend:ktor` module's `build.gradle.kts` dependencies block.
- [ ] 1.4 Add a row to [`docs/09-Versions.md`](../../../docs/09-Versions.md) § Version Decisions justifying the Pebble pin (per `design.md` Decision 1 rationale).
- [ ] 1.5 Download the official HTMX `htmx.min.js` from `htmx.org` (pin a specific version — e.g., the current stable release at implementation time). Record the version in the commit body (NOT as a self-referential edit to `tasks.md`); the file's integrity hash lives in the `htmx.min.js.SHA256SUMS` sibling file added by task 4.2.
- [ ] 1.6 Run `./gradlew :backend:ktor:compileKotlin` to verify the new Pebble dependency resolves; no other code changes yet.

## 2. Routes — `Application.admin()` extension function

- [ ] 2.1 Create `backend/ktor/src/main/kotlin/id/nearyou/app/admin/AdminModule.kt` (or `AdminApp.kt` — bikeshed at file creation) containing the `Application.admin()` extension function per `design.md` Decision 3.
- [ ] 2.2 Implement the `KTOR_ENV == "production"` mount guard at the top of `Application.admin()` per `design.md` Decision 6 — read the env var via `environment.config.propertyOrNull("ktor.deployment.environment")?.getString() ?: "dev"`; if production, emit the bootstrap WARN log line (containing `admin module skipped` + `admin-login-argon2-totp`) and `return` before any plugin install or route registration.
- [ ] 2.3 In the non-production code path, emit the bootstrap WARN log line (containing `admin module mounted` + `unauthenticated scaffold` + `admin-login-argon2-totp`) per spec Req 4 Sc 2 and Req 5 Sc 1.
- [ ] 2.4 Inside `Application.admin()`, install the Pebble plugin (`install(Pebble) { loader(ClasspathLoader().apply { prefix = "templates/admin" }) }` or equivalent).
- [ ] 2.5 Inside `Application.admin()`, declare `routing { route("/admin") { … } }` — wire the index route + the `staticResources("/static", "admin/static")` call (per `design.md` Decision 4; note the inner path is `/static` because the outer `route("/admin")` already prefixes it).
- [ ] 2.6 Add the in-source comment per spec Req 4: a `//` or `/* */` block referencing `admin-login-argon2-totp` as the source of the future auth gate, and stating the unauthenticated posture is intentional and time-bound. Note: the in-source comment is documentation; the normative observable behavior is the WARN log emitted by tasks 2.2/2.3 + the per-request log added by task 2.7.
- [ ] 2.7 Wire a route-level interceptor (or equivalent per-request hook) under `route("/admin")` that emits a WARN log line per spec Req 4 Sc 2 — must contain both `admin-login-argon2-totp` and `unauthenticated scaffold` substrings. Skip emitting this on the static-asset routes if implementation deems the noise excessive; the spec scenario allows "every admin route hit OR bootstrap-only" but at minimum the request-level log MUST fire on `GET /admin/`.
- [ ] 2.8 Wire `admin()` into the main `Application.module()` bootstrap (find the existing module function and add a one-line `admin()` call).
- [ ] 2.9 Create `backend/ktor/src/main/kotlin/id/nearyou/app/admin/routes/AdminIndexRoute.kt` containing the `Route.adminIndex()` extension function (or whatever shape fits Decision 3) — the `/admin/` GET handler that calls `call.respond(PebbleContent("index.peb", emptyMap()))`.

## 3. Templates — base layout + index page

- [ ] 3.1 Create `backend/ktor/src/main/resources/templates/admin/layout.peb` containing the base layout per spec Requirement 2: `<header>`, `<nav>` stub with placeholder links (e.g., 1-2 `<a href="#">` items labeled as future-feature stubs), `<footer>`, and a Pebble `{% block content %}{% endblock %}` placeholder for child templates to fill.
- [ ] 3.2 In the layout's `<head>`, add the `<script src="/admin/static/htmx.min.js">` tag per spec Requirement 3 + `design.md` Decision 2 (vendored path).
- [ ] 3.3 Create `backend/ktor/src/main/resources/templates/admin/index.peb` extending the layout via Pebble's `{% extends "layout.peb" %}` syntax, with a `{% block content %}` overriding the content block — body is a "Admin Panel" `<h1>` heading + a sentence noting this is the scaffold landing page. Use the literal string `Admin Panel` as the page identifier (task 5.2 asserts on this substring).
- [ ] 3.4 Inline minimal CSS (or omit entirely) — the scaffold uses vanilla browser styling per the `design.md` "scaffold ships no styling" trade-off.

## 4. Static assets — vendored HTMX + integrity

- [ ] 4.1 Place the downloaded `htmx.min.js` (from task 1.5) at `backend/ktor/src/main/resources/admin/static/htmx.min.js`.
- [ ] 4.2 Generate the SHA256SUMS sibling file: `cd backend/ktor/src/main/resources/admin/static/ && sha256sum htmx.min.js > htmx.min.js.SHA256SUMS`. Commit BOTH files together.
- [ ] 4.3 Add a one-line CI step to the existing `lint` job in `.github/workflows/ci.yml` (or wherever the `lint` job lives): `cd backend/ktor/src/main/resources/admin/static/ && sha256sum -c htmx.min.js.SHA256SUMS`. Fail-fasts on file/hash drift per `design.md` Decision 2 Vendored file provenance subsection.
- [ ] 4.4 Verify the `staticResources` route from task 2.5 resolves the file at `/admin/static/htmx.min.js`.

## 5. Tests — Ktor testApplication

- [ ] 5.1 Create `backend/ktor/src/test/kotlin/id/nearyou/app/admin/AdminScaffoldTest.kt` with a Kotest spec class (mirror the existing `StringSpec` style used by `SuspensionUnbanWorkerTest` + `UnbanWorkerRouteTest`).
- [ ] 5.2 Test for spec Req 1 Sc 1: `testApplication { application { admin() } }` → `client.get("/admin/").status shouldBe HttpStatusCode.OK` AND `client.get("/admin/").bodyAsText() shouldContain "Admin Panel"` (the literal identifier per task 3.3).
- [ ] 5.3 Test for spec Req 1 Sc 2: `client.get("/admin/nonexistent-page").status shouldBe HttpStatusCode.NotFound`.
- [ ] 5.4 Test for spec Req 1 Sc 3 (`POST /admin/` → 405): `client.post("/admin/").status shouldBe HttpStatusCode.MethodNotAllowed`. Note: Ktor's default for an unhandled method when the path is wired returns 405; if implementation observes 404 instead, the test catches the discrepancy and forces an explicit route-level method-fence rather than relying on default behavior.
- [ ] 5.5 Test for spec Req 2 Sc 1 (layout-extension): assert the `bodyAsText()` of `client.get("/admin/")` `shouldContain "<header>"` AND `shouldContain "<nav>"` AND `shouldContain "<footer>"` AND `shouldContain "Admin Panel"` (the child-block content). These four substrings together verify structural layout-inheritance, not just any-string-from-the-rendered-page.
- [ ] 5.6 Test for spec Req 3 Sc 1: assert the `bodyAsText()` of `client.get("/admin/")` contains the substring `<script src="/admin/static/htmx.min.js"` (matches both self-closing and explicit-end-tag forms).
- [ ] 5.7 Test for spec Req 3 Sc 2 (vendored asset): `client.get("/admin/static/htmx.min.js").status shouldBe HttpStatusCode.OK`. For Content-Type, accept BOTH `application/javascript` AND `text/javascript`: extract `client.get("/admin/static/htmx.min.js").contentType()` and assert its `contentType` + `contentSubtype` matches either `application/javascript` OR `text/javascript` (e.g., a helper like `actual in setOf(ContentType.Application.JavaScript, ContentType.Text.JavaScript)`). Per spec accept-list — do NOT assert against only one MIME flavor.
- [ ] 5.8 Test for spec Req 3 Sc 3 (path-traversal): `client.get("/admin/static/../../some-other-resource").status shouldBe HttpStatusCode.NotFound`. Also assert against an explicit-encoded form: `client.get("/admin/static/%2E%2E/config").status shouldBe HttpStatusCode.NotFound` to cover URL-encoded traversal attempts.
- [ ] 5.9 Test for spec Req 4 Sc 1 (unauthenticated): `client.get("/admin/")` with NO `Authorization` header, NO session cookie, NO `X-CSRF-Token` returns 200 — already covered by 5.2; add an inline comment in the test body explicitly noting "no auth headers sent — verifies spec Req 4 Sc 1 unauthenticated-subtree posture".
- [ ] 5.10 Test for spec Req 4 Sc 2 (WARN log on admin route hit): use a Kotest log-capture utility (or Logback's `ListAppender` wired in the test bootstrap) to capture log lines emitted during `client.get("/admin/")`; assert at least one captured line is WARN-level AND contains BOTH the substring `admin-login-argon2-totp` AND the phrase `unauthenticated scaffold`. If the implementation chose the bootstrap-only emission shape (per spec Req 4 Sc 2 alternative `(ii)`), assert against the bootstrap log instead — the test should adapt to whichever path is implemented.
- [ ] 5.11 Test for spec Req 5 Sc 1 (mount guard prevents prod registration): `testApplication { environment { config = MapApplicationConfig("ktor.deployment.environment" to "production") }; application { admin() } }` → `client.get("/admin/").status shouldBe HttpStatusCode.NotFound` AND the captured logs SHALL contain a bootstrap WARN line with `admin module skipped` + `admin-login-argon2-totp`.
- [ ] 5.12 Test for spec Req 5 Sc 2 (mount succeeds in non-prod): `testApplication { environment { config = MapApplicationConfig("ktor.deployment.environment" to "staging") }; application { admin() } }` → `client.get("/admin/").status shouldBe HttpStatusCode.OK`. Cross-check with 5.2's dev/default-env path.
- [ ] 5.13 Template-not-found smoke (per `design.md` Decision 5): write a one-off `testApplication` block that registers a throwaway route returning `call.respond(PebbleContent("nonexistent.peb", emptyMap()))` and asserts a non-200 response. Defense against template-loader prefix drift.
- [ ] 5.14 Run `./gradlew :backend:ktor:test --tests "*AdminScaffoldTest"` to verify all assertions pass.

## 6. Pre-archive staging smoke — REQUIRED (round 1 review flipped this from SKIPPED)

- [ ] 6.1 Trigger staging deploy on the change branch: `gh workflow run deploy-staging.yml --ref admin-panel-ktor-htmx-bootstrap`. Note the run ID emitted.
- [ ] 6.2 Poll the deploy run: `gh run watch <run-id>` (or equivalent). Wait for green before proceeding.
- [ ] 6.3 Smoke `GET /admin/`: `curl -i https://api-staging.nearyou.id/admin/` — verify 200 + rendered HTML body containing `Admin Panel` + the `<script src="/admin/static/htmx.min.js">` tag.
- [ ] 6.4 Smoke the static asset: `curl -i https://api-staging.nearyou.id/admin/static/htmx.min.js` — verify 200 + `Content-Type` matches either `application/javascript` OR `text/javascript`.
- [ ] 6.5 Smoke path-traversal sanity: `curl -i https://api-staging.nearyou.id/admin/static/../../config` — expect 404. Defense against the unlikely scenario where staging's Cloudflare config strips `..` before the request reaches Cloud Run, masking a missing sanitizer in the deployable.
- [ ] 6.6 Inspect Cloud Run logs for the expected WARN log lines from tasks 2.3 + 2.7 (`gh run view <run-id>` if surfaced; otherwise `gcloud run services logs read` against the staging service). Capture one example log line in the archive commit body for evidence.
- [ ] 6.7 Also smoke locally for completeness: `./gradlew :backend:ktor:run` + `curl -i http://localhost:8080/admin/` + `curl -i http://localhost:8080/admin/static/htmx.min.js` + browser-load `http://localhost:8080/admin/` to verify Network tab shows HTMX 200.
- [ ] 6.8 Document the smoke results in the archive commit body — captured curl headers + the WARN log line evidence. The commit body is the audit trail at squash-merge time.

## 7. Documentation + validation

- [ ] 7.1 Run `openspec validate admin-panel-ktor-htmx-bootstrap --strict` and resolve any flagged issues.
- [ ] 7.2 Amend the [`docs/07-Operations.md`](../../../docs/07-Operations.md) Status callout (currently dated 2026-05-07; reads `"there are no /admin/* routes, no admin UI, no Pebble templates"` per round-1 review N2). New language should reflect: the route subtree + Pebble + HTMX scaffold ship in Admin #2 (`admin-panel-ktor-htmx-bootstrap`); the unauthenticated posture is intentional + time-bound per Decision 6's `KTOR_ENV` mount guard; auth gate + admin business features remain DESIGN per Admin #3-#5. Update the date stamp to the merge date.
- [ ] 7.3 If the change introduces any new file path that the README's auto-generated module section references, run `dev/scripts/sync-readme.sh --check` per [`CLAUDE.md`](../../../CLAUDE.md) § "Root README module list is auto-generated" — this change SHOULD NOT trigger the check because no new module landed (per `design.md` Decision 3 + Non-Goals), but verify.
- [ ] 7.4 Confirm `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` all green locally before pushing the final feat commit (per [`CLAUDE.md`](../../../CLAUDE.md) § Pre-push verification).
- [ ] 7.5 After archive: run `openspec validate --specs admin-panel-scaffold --strict` to verify the canonical `openspec/specs/admin-panel-scaffold/spec.md` validates after the archive's spec sync.
