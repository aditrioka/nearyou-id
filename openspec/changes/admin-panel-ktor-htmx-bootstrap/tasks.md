## 1. Setup — dependencies + version pinning

- [ ] 1.1 Verify the Pebble Ktor module artifact coordinate (`io.ktor:ktor-server-pebble-jvm`) is published at the project's current Ktor version (`ktor = "3.4.1"` in `gradle/libs.versions.toml`); if not, surface the actual published coordinate + version to use.
- [ ] 1.2 Add the Pebble library reference to `gradle/libs.versions.toml` under `[libraries]` (e.g., `ktor-serverPebble = { module = "io.ktor:ktor-server-pebble-jvm", version.ref = "ktor" }`) — share the existing `ktor` version variable, NOT a separate coordinate.
- [ ] 1.3 Wire the new library into the `:backend:ktor` module's `build.gradle.kts` dependencies block.
- [ ] 1.4 Add a row to [`docs/09-Versions.md`](../../../docs/09-Versions.md) § Version Decisions justifying the Pebble pin (per `design.md` Decision 1 rationale).
- [ ] 1.5 Download the official HTMX `htmx.min.js` from `htmx.org` (pin a specific version — e.g., the current stable release at implementation time). Record the version + SHA256 in `tasks.md` here at task completion for archive-time traceability.
- [ ] 1.6 Run `./gradlew :backend:ktor:compileKotlin` to verify the new Pebble dependency resolves; no other code changes yet.

## 2. Routes — `Application.admin()` extension function

- [ ] 2.1 Create `backend/ktor/src/main/kotlin/id/nearyou/app/admin/AdminModule.kt` (or `AdminApp.kt` — bikeshed at file creation) containing the `Application.admin()` extension function per `design.md` Decision 3.
- [ ] 2.2 Inside `Application.admin()`, install the Pebble plugin (`install(Pebble) { loader(ClasspathLoader().apply { prefix = "templates/admin" }) }` or equivalent).
- [ ] 2.3 Inside `Application.admin()`, declare `routing { route("/admin") { … } }` — wire the index route + the `staticResources("/static", "admin/static")` call (per `design.md` Decision 4; note the inner path is `/static` because the outer `route("/admin")` already prefixes it).
- [ ] 2.4 Add the in-source comment required by spec Requirement 4: a `//` or `/* */` block referencing `admin-login-argon2-totp` as the source of the future auth gate, and stating the unauthenticated posture is intentional and time-bound.
- [ ] 2.5 Wire `admin()` into the main `Application.module()` bootstrap (find the existing module function and add a one-line `admin()` call).
- [ ] 2.6 Create `backend/ktor/src/main/kotlin/id/nearyou/app/admin/routes/AdminIndexRoute.kt` containing the `Route.adminIndex()` extension function (or whatever shape fits Decision 3) — the `/admin/` GET handler that calls `call.respond(PebbleContent("index.peb", emptyMap()))`.

## 3. Templates — base layout + index page

- [ ] 3.1 Create `backend/ktor/src/main/resources/templates/admin/layout.peb` containing the base layout per spec Requirement 2: `<header>`, `<nav>` stub with placeholder links (e.g., 1-2 `<a href="#">` items labeled as future-feature stubs), `<footer>`, and a Pebble `{% block content %}{% endblock %}` placeholder for child templates to fill.
- [ ] 3.2 In the layout's `<head>`, add the `<script src="/admin/static/htmx.min.js">` tag per spec Requirement 3 + `design.md` Decision 2 (vendored path).
- [ ] 3.3 Create `backend/ktor/src/main/resources/templates/admin/index.peb` extending the layout via Pebble's `{% extends "layout.peb" %}` syntax, with a `{% block content %}` overriding the content block — body is a simple "hello admin" heading + a sentence noting this is the scaffold landing page.
- [ ] 3.4 Inline minimal CSS (or omit entirely) — the scaffold uses vanilla browser styling per the `design.md` "scaffold ships no styling" trade-off.

## 4. Static assets — vendored HTMX

- [ ] 4.1 Place the downloaded `htmx.min.js` (from task 1.5) at `backend/ktor/src/main/resources/admin/static/htmx.min.js`.
- [ ] 4.2 Add a top-of-file comment (or a sibling `htmx.min.js.SHA256SUMS` file) recording the version + SHA256 per `design.md` Decision 2 rationale.
- [ ] 4.3 Verify the `staticResources` route from task 2.3 resolves the file at `/admin/static/htmx.min.js`.

## 5. Tests — Ktor testApplication

- [ ] 5.1 Create `backend/ktor/src/test/kotlin/id/nearyou/app/admin/AdminScaffoldTest.kt` with a Kotest spec class (mirror the existing test-class style in the `admin` package).
- [ ] 5.2 Write test for spec Req 1 Scenario 1: `testApplication { application { admin() }; client.get("/admin/").status shouldBe HttpStatusCode.OK; client.get("/admin/").bodyAsText() shouldContain "<identifier>"` — where `<identifier>` matches the index page's body text (e.g., "Admin Panel" or "hello admin").
- [ ] 5.3 Write test for spec Req 1 Scenario 2: `client.get("/admin/nonexistent-page").status shouldBe HttpStatusCode.NotFound`.
- [ ] 5.4 Write test for spec Req 2 Scenario 1: assert the rendered HTML body of `/admin/` contains the header/nav/footer markers + the index page's content block (use simple `shouldContain` assertions on stable strings from the layout — e.g., a footer text identifier).
- [ ] 5.5 Write test for spec Req 3 Scenario 1: assert the rendered HTML body of `/admin/` contains `<script src="/admin/static/htmx.min.js"` (substring match on the script tag).
- [ ] 5.6 Write test for spec Req 3 Scenario 2: `client.get("/admin/static/htmx.min.js").status shouldBe HttpStatusCode.OK` AND `client.get("/admin/static/htmx.min.js").contentType()?.match(ContentType.Application.JavaScript) shouldBe true` (or `ContentType.Text.JavaScript` — accept either per Decision 5 rationale).
- [ ] 5.7 Write test for spec Req 4 Scenario 1: assert `client.get("/admin/")` (with NO `Authorization` header, NO session cookie, NO `X-CSRF-Token`) returns 200 — already covered by 5.2, just add an inline comment explicitly noting "no auth headers sent — verifies spec Req 4 unauthenticated-subtree posture".
- [ ] 5.8 Run `./gradlew :backend:ktor:test --tests "*AdminScaffoldTest"` to verify all assertions pass.

## 6. Pre-archive smoke — manual local verification

- [ ] 6.1 Run `./gradlew :backend:ktor:run` locally.
- [ ] 6.2 Run `curl -i http://localhost:8080/admin/` and verify a 200 + HTML body containing the "hello admin" content.
- [ ] 6.3 Run `curl -i http://localhost:8080/admin/static/htmx.min.js` and verify a 200 + `Content-Type: application/javascript` (or `text/javascript`) + the HTMX library contents.
- [ ] 6.4 Open `http://localhost:8080/admin/` in a browser; verify the page renders with layout structure (header, nav, footer) and that the browser DevTools Network tab shows the HTMX script load succeeding (200 response).
- [ ] 6.5 NOTE: pre-archive STAGING smoke is N/A for this change — admin UI is unauthenticated + has no DB / runtime side effects; manual local verification is sufficient. Per [`openspec/project.md`](../../project.md) § Change Delivery Workflow's pre-archive smoke convention, mark this section as "manual-verified locally" in the archive commit body.

## 7. Documentation + validation

- [ ] 7.1 Run `openspec validate admin-panel-ktor-htmx-bootstrap --strict` and resolve any flagged issues.
- [ ] 7.2 Verify [`openspec/project.md`](../../project.md) § Module Structure does NOT need updating (this change does NOT scaffold a new Gradle module per `design.md` Decision 3 + Non-Goals). If something unexpected has changed during implementation, update the section.
- [ ] 7.3 Update the `:mobile:app` line in [`openspec/project.md`](../../project.md) § Module Structure to NOT change (sanity check — this is a `:backend:ktor` change).
- [ ] 7.4 If the change introduces any new file path that the README's auto-generated module section references, run `dev/scripts/sync-readme.sh --check` per [`CLAUDE.md`](../../../CLAUDE.md) § "Root README module list is auto-generated" — this change SHOULD NOT trigger the check because no new module landed, but verify.
- [ ] 7.5 Confirm `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` all green locally before pushing the final feat commit (per [`CLAUDE.md`](../../../CLAUDE.md) § Pre-push verification).
- [ ] 7.6 After archive: run `openspec validate --specs admin-panel-scaffold --strict` to verify the canonical `openspec/specs/admin-panel-scaffold/spec.md` validates after the archive's spec sync.
