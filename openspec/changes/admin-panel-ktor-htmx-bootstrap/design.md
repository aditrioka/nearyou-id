## Context

Admin schema landed in PR [#107](https://github.com/aditrioka/nearyou-id/pull/107) (`admin-schema-bootstrap`), but no admin-UI code consumes it yet. The `:backend:ktor` `admin` package contains only `SuspensionUnbanWorker.kt` + `UnbanWorkerRoute.kt` serving the internal `/internal/unban-worker` tick (~189 LOC); zero `/admin/*` routes exist, no template engine is wired, and no HTMX client surface is available. The Admin Panel design in [`docs/07-Operations.md`](../../../docs/07-Operations.md) § Stack calls for "Ktor server-side + Pebble/Freemarker + HTMX. Module `admin-panel` with routes `/admin/*`. Host: separate subdomain (`admin.nearyou.id`), NOT a path on the main service." That's the eventual Phase 3.5 deployment shape; this change ships the IN-PROCESS route scaffold on the same `:backend:ktor` deployable, deferring the subdomain + IAP/Cloud Armor + separate Cloud Run service work to the Phase 3.5 deployment task.

Constraint: the change must avoid touching the 16 code-level invariants from [`openspec/project.md`](../../project.md) § Coding Conventions & CI Lint Rules — there's no DB access, no rate-limit work, no Redis access, no `admin_sessions` INSERT in this change. Future admin changes will exercise those surfaces.

Stakeholder posture: solo-operator pre-launch build. This is Admin #2 in the [`openspec/project.md`](../../project.md) § Mobile + Admin Scaffolding Priority next-step menu — unblocks Admin #3 (`admin-login-argon2-totp`), Admin #4 (`admin-actions-log-viewer`), Admin #5 (`admin-suspend-unban-user-action`), and every subsequent admin-UI change.

## Goals / Non-Goals

**Goals:**

- Mount an `/admin/*` Ktor route subtree on the existing `:backend:ktor` deployable.
- Wire a server-side templating engine (Pebble OR Freemarker — see Decision 1) such that admin pages render from `.peb` / `.ftl` templates under `src/main/resources/templates/admin/`.
- Make HTMX available as the client interaction layer for all admin pages (CDN OR vendored — see Decision 2).
- Provide a shared base layout template (header + nav stub + footer) extensible by future admin pages.
- Serve a "hello admin" index page at `/admin/` that extends the base layout, verifying end-to-end that templating + routing work.
- Cover the scaffold with Ktor `testApplication` assertions per Decision 5 — no manual-only verification.
- Shape the route mounting (Decision 3) so the eventual extraction into a separate Cloud Run service behind IAP/Cloud Armor is clean.

**Non-Goals:**

- Authentication / login / session management — deferred to Admin #3 (`admin-login-argon2-totp`). The subtree is intentionally open in this scaffold.
- `admin_sessions` CSRF token issuance + verification — deferred to Admin #3 (the `csrf_token_hash` Detekt rule is in place but no INSERT site yet exists).
- Any DB access from admin routes — deferred to Admin #4 (audit log viewer) onward. The `admin-app-db-connection-string` GCP Secret Manager slot is NOT consumed by this change.
- `admin.nearyou.id` subdomain DNS + IAP/Cloud Armor + separate Cloud Run service deployment — Phase 3.5 deployment task #2 per [`docs/08-Roadmap-Risk.md`](../../../docs/08-Roadmap-Risk.md). This change runs the admin subtree IN-PROCESS on the main `:backend:ktor` deployable; extraction to a separate service is a future infrastructure change.
- WebAuthn / TOTP / Argon2id password hashing — deferred to Admin #3.
- Admin business features (Report Queue, User Management, Hard Delete Queue, Operational Dashboard, etc., per [`docs/07-Operations.md`](../../../docs/07-Operations.md) § Core Features) — deferred to Admin #4+.
- CSS framework / design system / branded styling — the scaffold uses minimal inline-or-vanilla CSS sufficient for the "hello admin" verification. Visual design comes alongside the first real admin feature (likely Admin #4).
- Creating a new Gradle module (`admin-panel` or similar) — the change EXTENDS the existing `:backend:ktor` `admin` package. Eventual module-isation is a future refactor when the admin surface area justifies the split.

## Decisions

### Decision 1: Pebble over Freemarker for the templating engine

**Choice: Pebble.**

**Rationale.** Both are mature, well-supported Ktor templating options with first-party Ktor modules (`io.ktor:ktor-server-pebble-jvm` and `io.ktor:ktor-server-freemarker-jvm`). Pebble's template syntax (Jinja/Twig-inspired) is more contemporary and reads more naturally next to HTMX attributes (`hx-get`, `hx-target`, `hx-swap`) than Freemarker's syntax. Pebble has a slightly leaner dep footprint (no Apache Commons transitive baggage) and the Ktor + Pebble combo is the more frequently cited admin-panel stack in the Kotlin community. Freemarker's main strength (battle-tested in legacy JVM ecosystems, deep integration with Spring) doesn't matter here — we're greenfield on Ktor. Both engines support template inheritance (`{% extends %}` in Pebble, `<#include>` / macros in Freemarker), which is what we need for the base-layout pattern.

**Alternatives considered:**
- **Freemarker.** Equally capable; rejected for the syntax/footprint reasons above. If Pebble runs into a blocker during implementation (e.g., a specific HTMX feature that doesn't compose cleanly with Pebble's expression language), this is the canonical fallback — no other downstream decision in this change is tightly coupled to the engine choice.
- **Mustache / Handlebars.** Logic-less templating; rejected as too restrictive for admin-panel use cases where conditional rendering and small loops are routine.
- **Kotlin's `kotlinx.html` DSL.** Type-safe HTML in Kotlin source; rejected because it mixes template + logic in `.kt` files, making future template-editor / hot-reload workflows harder, and because HTMX swap targets are easier to author in templates than in DSL builders.

**Version pin.** Add `ktor-server-pebble-jvm` to `gradle/libs.versions.toml` per the version-pinning policy in [`docs/09-Versions.md`](../../../docs/09-Versions.md), using `version.ref = "ktor"` to share the same `ktor = "3.4.1"` version variable that the other `ktor-server-*` modules already use (project pattern is a shared version variable, not an `io.ktor:ktor-bom` import). Add a row to [`docs/09-Versions.md`](../../../docs/09-Versions.md) § Version Decisions justifying the Pebble pin (per that file's policy: "Minor (X.**Y**) and major (**X**.Y) bumps require a row... Pinning a new library is treated as a new entry").

### Decision 2: Vendored HTMX over CDN reference

**Choice: vendored HTMX file at `backend/ktor/src/main/resources/admin/static/htmx.min.js`, served via Ktor's static-resource handler at `/admin/static/htmx.min.js`.**

**Rationale.** HTMX is ~13KB minified — small enough that vendoring has negligible repo size impact. Vendoring gives us:
1. **No external dependency at request time** — admin pages render even if the CDN is down or blocked by network policy. Important because the admin panel is the ops surface that gets used DURING incidents.
2. **No version drift** — the vendored file is git-pinned at a known SHA. A CDN reference would either pin a version (achieving the same effect but with extra latency + a CSP exception) or fetch "latest" (a supply-chain risk we explicitly avoid per the project's `:infra:*` isolation pattern).
3. **No CSP exception needed** — when CSP is added in a later change (likely alongside Admin #3 auth), we can `script-src 'self'` instead of `script-src 'self' https://unpkg.com`. One fewer entry on the trusted-origins allowlist.
4. **Simpler test assertion** — Decision 5's third assertion (`GET /admin/static/htmx.min.js` returns 200 + correct `Content-Type`) is trivially true with vendoring; with CDN, it would require asserting the `<script src="https://unpkg.com/htmx.org@<version>">` tag is present and trusting the CDN.

**Alternatives considered:**
- **CDN reference (`https://unpkg.com/htmx.org@<version>/dist/htmx.min.js`).** Rejected for the reasons above. The "smaller deploy artifact" argument doesn't matter for a ~13KB file. The "auto-update to security patches" argument is illusory because pinning a version is required for reproducible builds anyway.
- **NPM-based asset pipeline (`webpack` / `vite` / `pnpm` + `assets` plugin).** Rejected as massive overkill — adds a Node.js toolchain dep to a Kotlin backend just to copy a single JS file. The whole point of HTMX is server-rendered HTML with no build step.

**Vendored file provenance.** Download `htmx.min.js` from the official `htmx.org` release artifact for a specific version tag (pin in `tasks.md` Setup section). Include the version + SHA256 in a sibling `htmx.min.js.SHA256SUMS` file (NOT a top-of-file comment — comments can be edited without updating the recorded hash, defeating the integrity check). Add a CI step that runs `sha256sum -c backend/ktor/src/main/resources/admin/static/htmx.min.js.SHA256SUMS` on every push to fail-fast on file/hash drift (the file is small enough that a single-line CI step in the existing `lint` job is sufficient — no separate workflow needed). Re-vendor at version bumps via a one-line file replace + SHA256SUMS regeneration.

**Advisory monitoring.** HTMX is not a JVM dep, so Dependabot/Renovate doesn't see it — advisories must be tracked manually. Watch the [`bigskysoftware/htmx` GitHub releases](https://github.com/bigskysoftware/htmx/releases) page (subscribe via GitHub watch → "Releases only") and the project's security advisory channel. Re-vendor on any security advisory; routine version bumps follow normal change-cadence.

### Decision 3: `Application.admin()` extension function over inline `routing { route("/admin") }` block

**Choice: extract admin routing into an `Application.admin()` Kotlin extension function (similar shape to `Application.module()`), called from the main `Application.module()` bootstrap. The function internally calls `routing { route("/admin") { ... } }`.**

**Rationale.** The eventual deployment target (per [`docs/07-Operations.md`](../../../docs/07-Operations.md) § Stack) is a SEPARATE Cloud Run service at `admin.nearyou.id` behind IAP/Cloud Armor. The `Application.admin()` shape makes that extraction mechanical: rename `module()` to `api()`, rename `admin()` to its own `module()`, ship two Cloud Run services with different `KTOR_MODULE` env vars pointing to the right entry point. No business-logic refactor needed at extraction time. Inlining the `route("/admin")` block inside `Application.module()` would entangle the admin and API route trees, requiring a non-trivial split at extraction time.

This mirrors the pattern in `mobile-app-scaffold-replace-wizard` — that change deferred Voyager-vs-Decompose-vs-vanilla to its `design.md` Decision 1, with extraction implications considered upfront. Same playbook here.

**Alternatives considered:**
- **Inline `routing { route("/admin") }` block in `Application.module()`.** Rejected for the extraction friction above.
- **New `:backend:admin-ktor` Gradle module.** Rejected as premature module-isation. The admin surface is too thin right now to justify a separate Gradle module — let it grow inside the `:backend:ktor` `admin` package first, then split when the surface area justifies the split. This is the same posture as [`openspec/project.md`](../../project.md) § Module Structure takes for `:shared:resources` ("SCAFFOLD NEXT") vs other planned modules ("DESIGN").

**Where it lives.** `backend/ktor/src/main/kotlin/id/nearyou/app/admin/AdminModule.kt` (or `AdminApp.kt` — bikeshed at implementation). The file contains the `Application.admin()` function plus any helper extension functions (e.g., `Route.adminRoutes()` if helpful internally).

### Decision 4: Static asset serving via Ktor `staticResources("/admin/static", "admin/static")`

**Choice: serve admin static assets via Ktor's built-in `staticResources` route, mounted at `/admin/static/*`, pulling from `src/main/resources/admin/static/` on the classpath.**

**Rationale.** Ktor's `staticResources` handler is the standard pattern for serving classpath-bundled static files — handles `Content-Type` inference (`.js` → `application/javascript`, `.css` → `text/css`), efficient `InputStream`-based serving, and 404-on-missing without custom code. The `/admin/static` prefix mirrors the route prefix used everywhere else in this change, making URL handling consistent. Resources under `src/main/resources/admin/static/` are bundled into the JAR at build time — no separate static-asset deployment step needed.

**Path-traversal threat model.** Ktor's `staticResources` uses classpath `getResource` lookups under the hood, which DO NOT resolve `..` path segments — a request for `/admin/static/../config/local.yaml` falls back to a 404, not a successful classpath traversal. This is asserted by the test added per Decision 5's path-traversal scenario (a `client.get("/admin/static/../../some-other-resource").status shouldBe HttpStatusCode.NotFound` assertion). The directory-mount form (vs an exact-route-only `get("/admin/static/htmx.min.js") { ... }`) is the canonical pattern in Ktor and accommodates additional admin assets (CSS, future JS files) landing in subsequent changes without route-table edits. The scaffold currently ships a single file; the directory mount is forward-compatible.

**Alternatives considered:**
- **`staticFiles("/admin/static", File("admin/static"))`** — serves from the filesystem rather than the classpath. Rejected because it requires the filesystem layout to match between dev (`./admin/static/`) and prod (where the working directory is the container's `/app`), introducing a deployment hazard.
- **Mount static assets at the root (`staticResources("/static", "static")` with a `<script src="/static/htmx.min.js">` tag).** Rejected because it pollutes the global route namespace; admin assets should be contained under `/admin/*` so a future `:api` extraction doesn't have to coordinate with admin asset paths.
- **Exact-route-only mount (`get("/admin/static/htmx.min.js") { call.respondBytes(...) }`).** Rejected as premature minimization. The directory mount + path-traversal sanitization (above) is no less safe than an exact-route mount, and the directory form scales to multiple static assets without per-asset route definitions. If the admin static surface stays at exactly one file forever (unlikely as admin features grow), this can be revisited.

**Path scheme.** `backend/ktor/src/main/resources/admin/static/htmx.min.js` (and any future CSS / JS files). Served at `https://<host>/admin/static/htmx.min.js`.

### Decision 5: Test approach via Ktor `testApplication`

**Choice: write Ktor `testApplication` assertions covering six scenarios — (a) `/admin/` returns 200 + the "Admin Panel" body content, (b) the base layout template renders without errors (asserted by `shouldContain` on stable structural markers `<header>`, `<nav>`, `<footer>` AND a child-block-rendered marker), (c) `/admin/static/htmx.min.js` returns 200 + `Content-Type` matches either `application/javascript` OR `text/javascript` (per spec's accept-list), (d) `GET /admin/static/../../some-other-resource` returns 404 (path-traversal sanitization), (e) `POST /admin/` returns 405 Method Not Allowed (only GET is wired), (f) every `/admin/*` hit emits a WARN-level log line containing both the strings `admin-login-argon2-totp` and `unauthenticated scaffold` (runtime-asserts spec Req 4 Sc 2 — the in-source-comment posture is observable via the log signal it emits).**

**Rationale.** `testApplication` is the standard Ktor test harness — fast, in-process, no external dependencies, no Docker required. The six assertions cover the things this change ships: route mounting, template rendering, static asset serving, path-traversal safety, HTTP-method discipline, and the time-bound-unauthenticated-posture observability signal. A failed render would throw an exception that propagates back as a 500, so the 200-assertion implicitly verifies the render succeeded.

**Round 1 sub-agent review folded scenarios in.** The original Decision 5 (proposal v1) covered only three happy-path scenarios. Round 1 reviewers flagged: (i) spec Req 4 Sc 2 ("source comment documents posture") was not runtime-testable — fixed by rewriting the scenario to assert the WARN log signal that the route emits at request time (also adds defense-in-depth observability for accidental-prod-deploy); (ii) Content-Type assertion accepted only one MIME flavor when the spec lists two — fixed by OR-assertion; (iii) POST /admin/ → 405 vs 404 was uncovered — fixed by adding scenario (e); (iv) path-traversal threat model was undocumented — fixed by adding scenario (d). Decision 6 (KTOR_ENV mount guard) introduces a seventh test: in a `KTOR_ENV=production` `testApplication` config, `/admin/` returns 404 (route never mounts).

**Why no auth-related assertion (beyond the unauthenticated-posture log).** Auth is a Admin #3 concern. The "no auth required" property is asserted positively by the 200 on `/admin/` — no `Authorization` header is sent in the test, and the request succeeds. Future admin changes (Admin #3 onward) will add auth-related assertions; this change MUST NOT pre-empt them. The WARN log is the bridge — it marks the unauthenticated state as observable so Admin #3's deploy will surface a "log line disappeared" diff (positive signal that the gate landed).

**Template-not-found smoke test.** Add a minimal additional assertion: a one-off `testApplication` block that registers a route returning `call.respond(PebbleContent("nonexistent.peb", emptyMap()))` and asserts a non-200 response (Pebble's `LoaderException` → 500 via Ktor's default error handler). This is a smoke-grade defense against template-loader prefix drift — if `index.peb` gets renamed or the `loader.prefix` configuration changes, this test catches it. Lightweight; not duplicating the production index-page test.

**Test file location.** `backend/ktor/src/test/kotlin/id/nearyou/app/admin/AdminScaffoldTest.kt` — co-located with other admin-package tests (per existing structure with `SuspensionUnbanWorker` tests if present, or as a fresh file otherwise).

**Alternatives considered:**
- **End-to-end browser test (Playwright / Selenium).** Rejected as massive overkill for a "hello admin" page. Adds a browser-driver toolchain dep + CI runtime cost. Defer to a real admin feature later if browser testing is needed.
- **Manual-only verification (`./gradlew run` + `curl http://localhost:8080/admin/`).** Rejected — the project's posture (per [`CLAUDE.md`](../../../CLAUDE.md) § Engineering judgment over context budget) is "documented debt is still debt; the default action is to ship the work." A 30-line `testApplication` block isn't debt worth deferring. Manual `curl` verification IS done additionally per the pre-archive checklist, but it complements automated coverage rather than replacing it.

### Decision 6: Production mount guard via `KTOR_ENV` check

**Choice: `Application.admin()` SHALL no-op when `KTOR_ENV == "production"` until the `admin-login-argon2-totp` change lands the auth gate. The guard is a single `if (ktorEnv == "production") return` at the top of the extension function, with a corresponding WARN log line emitted on the no-op path so an accidental production-deploy surfaces in observability.**

**Rationale.** The unauthenticated-subtree risk (catalogued in Risks/Trade-offs below) is bounded to pre-prod staging because production isn't live yet. But the project's posture (per [`CLAUDE.md`](../../../CLAUDE.md) § Executing actions with care) is to defend at the platform level, not just at the policy level. A one-line `KTOR_ENV` check makes "accidentally enabled in prod" impossible at the code level — no amount of policy drift can re-enable the route while the guard is in place. Admin #3 (`admin-login-argon2-totp`) removes the guard as part of its work (auth-gate-replaces-mount-guard is the natural transition). The WARN log on the no-op path means the absence of `/admin/*` routes in prod is positively observable, not silently invisible.

**Implementation shape.**

```kotlin
fun Application.admin() {
    val ktorEnv = environment.config.propertyOrNull("ktor.deployment.environment")?.getString() ?: "dev"
    if (ktorEnv == "production") {
        log.warn("admin module skipped: KTOR_ENV=production + unauthenticated scaffold. Mount guard lifts in admin-login-argon2-totp.")
        return
    }
    log.warn("admin module mounted: unauthenticated scaffold. To be closed by admin-login-argon2-totp.")
    // route + Pebble + static-resource setup here
}
```

**Alternatives considered:**
- **No guard; rely on policy + reviewer sanity-check.** Rejected. Production isn't live so the risk is bounded today, but "bounded today" isn't a property — it's a snapshot. A guard makes the property structural.
- **Firebase Remote Config feature flag (`admin_panel_unauthenticated_scaffold_enabled`, default false in prod).** Rejected as over-engineered for a one-time scaffold-then-supersede transition. Remote Config adds latency to mount-decision AND requires a runtime fetch — neither is appropriate for an application-bootstrap-time check. `KTOR_ENV` is the right grain.
- **Compile-time guard via build variant.** Rejected because `KTOR_ENV` is the canonical runtime switch in this project (per [`openspec/project.md`](../../project.md) § Environments — "Ktor reads `KTOR_ENV` and resolves secrets via the `secretKey(env, name)` helper"). Using a different mechanism for this one route would be a divergence with no benefit.

**Test coverage.** Decision 5 lists the test that verifies the guard: in a `KTOR_ENV=production` `testApplication` config, `/admin/` returns 404 (route never mounts). The `KTOR_ENV != "production"` happy path is already covered by the existing tests (default env is dev/staging-like).

## Risks / Trade-offs

- **Risk: Pebble dependency adds a new transitive surface area.** → Mitigation: pin by sharing the existing `ktor` version variable in `gradle/libs.versions.toml` (per Decision 1 — the project pattern is a shared version variable, NOT an `io.ktor:ktor-bom` import); add a one-line entry to [`docs/09-Versions.md`](../../../docs/09-Versions.md) § Version Decisions justifying the pin; future security advisories on `pebble-templates/pebble` flow through normal Dependabot review.
- **Risk: Vendored HTMX file goes stale or its hash drifts silently.** → Mitigation per Decision 2: SHA256SUMS sibling file (NOT a top-of-file comment); CI `sha256sum -c` check fail-fasts on hash drift; manual `bigskysoftware/htmx` releases watch covers Dependabot blindness. Re-vendor at version bumps via one-line file replace + SHA256SUMS regen.
- **Risk: Unauthenticated `/admin/*` subtree gets accidentally deployed to production.** → Mitigation: (a) Decision 6's `KTOR_ENV == "production"` mount guard makes "enabled in prod" structurally impossible at the code level until Admin #3 lifts it; (b) production isn't live yet (pre-Public-Launch per project state); (c) the `/admin/` page content is a "hello admin" stub with zero sensitive data; (d) Admin #3 lands the auth gate before any sensitive admin surface mounts behind these routes; (e) the in-route Kotlin source carries a comment per spec Requirement 4 explicitly stating "auth lands in Admin #3" so a future reader can't miss the intentional-open-subtree posture; (f) every `/admin/*` hit emits a WARN log line (per Decision 5 + spec Req 4 Sc 2 rewrite) so a route surface accidentally going live is observable in Sentry, not just visible to whoever notices the URL; (g) surfaced explicitly in `proposal.md` § Impact so the squash-merge reviewer sanity-checks this.
- **Risk: Eventual extraction to a separate Cloud Run service exposes coupling we missed.** → Mitigation: Decision 3 explicitly shapes routing around the extraction target. If extraction reveals coupling, that's a Phase 3.5 deployment-task discovery and gets handled there — not a blocker for this scaffold.
- **Trade-off: choosing Pebble locks the admin templating story for the entire admin surface.** → Accepted. Switching engines later means rewriting every template file — non-trivial but bounded by the admin surface size. The decision is reversible at moderate cost (rewrite all `.peb` files as `.ftl` or vice versa) and irreversible at zero cost during this change (one file).
- **Trade-off: scaffold ships no styling.** → Accepted. Visual design comes alongside the first real admin feature (likely Admin #4 `admin-actions-log-viewer`). The "hello admin" page is verification-grade, not user-facing.

## Migration Plan

No migration — greenfield scaffold. Deploy posture under [`openspec/project.md`](../../project.md) § Change Delivery Workflow's "pre-archive smoke" convention:

1. Feat commits land + CI green (lint + test).
2. **Pre-archive staging smoke: REQUIRED** (round 1 review flipped this from SKIPPED — see `tasks.md` Section 6). The change adds a new runtime dependency (Pebble) — version-resolution failures, `ClasspathLoader` prefix mismatches, and dispatcher/timeout interactions only surface against the actual staging classpath (different JVM, different `application.conf` overlay, different Cloud Run image-layer caching) than the local `./gradlew run` JVM exercises. Manual `curl` against `localhost:8080` is necessary-but-insufficient. Trigger sequence: (a) `gh workflow run deploy-staging.yml --ref admin-panel-ktor-htmx-bootstrap`; (b) poll the deploy run via `gh run watch`; (c) `curl -i https://api-staging.nearyou.id/admin/` → expect 200 + rendered HTML containing the "Admin Panel" identifier; (d) `curl -i https://api-staging.nearyou.id/admin/static/htmx.min.js` → expect 200 + `Content-Type` matching either `application/javascript` OR `text/javascript`; (e) check Cloud Run logs for the expected WARN log line emitted on each admin route hit (per Decision 5 scenario f); (f) tick Section 6 tasks in `tasks.md` with the actual log/curl evidence captured in the commit body.
3. Archive commit + `openspec validate --specs admin-panel-scaffold --strict` green.
4. Squash-merge → auto-deploys to `main`-staging. `https://api-staging.nearyou.id/admin/` becomes reachable; the "hello admin" stub IS publicly accessible (intentional per the unauthenticated-subtree design AND bounded to non-prod env per Decision 6's `KTOR_ENV` mount guard); Admin #3 closes this before any sensitive surface lands.

**Rollback strategy.** If anything in this change breaks the main API deploy (extremely unlikely since the change is additive — new dependency, new routes, new files; no edits to existing handlers), revert the squashed commit on `main` via `git revert` + new PR. The admin subtree being unauthenticated is NOT a rollback trigger — it's an intentional scaffold posture, and Admin #3 closes it.

## Open Questions

None. All decisions resolved above.
