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

**Vendored file provenance.** Download `htmx.min.js` from the official `htmx.org` release artifact for a specific version tag (pin in `tasks.md` Setup section). Include the version + SHA256 in a sibling `htmx.min.js.SHA256SUMS` file or a top-of-file comment for auditability. Re-vendor at version bumps via a one-line task — no scripting needed.

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

**Rationale.** Ktor's `staticResources` handler is the standard pattern for serving classpath-bundled static files — handles `Content-Type` inference (`.js` → `application/javascript`, `.css` → `text/css`), efficient `InputStream`-based serving, and 404-on-missing without custom code. The `/admin/static` prefix mirrors the route prefix used everywhere else in this change, making URL handling consistent. Resources under `src/main/resources/admin/static/` are bundled into the JAR at build time — no separate static-asset deployment step needed. CDN-fronted caching via Cloudflare comes "for free" when `admin.nearyou.id` lands behind Cloudflare in Phase 3.5.

**Alternatives considered:**
- **`staticFiles("/admin/static", File("admin/static"))`** — serves from the filesystem rather than the classpath. Rejected because it requires the filesystem layout to match between dev (`./admin/static/`) and prod (where the working directory is the container's `/app`), introducing a deployment hazard.
- **Mount static assets at the root (`staticResources("/static", "static")` with a `<script src="/static/htmx.min.js">` tag).** Rejected because it pollutes the global route namespace; admin assets should be contained under `/admin/*` so a future `:api` extraction doesn't have to coordinate with admin asset paths.

**Path scheme.** `backend/ktor/src/main/resources/admin/static/htmx.min.js` (and any future CSS / JS files). Served at `https://<host>/admin/static/htmx.min.js`.

### Decision 5: Test approach via Ktor `testApplication`

**Choice: write Ktor `testApplication` assertions covering three scenarios — (a) `/admin/` returns 200 + the "hello admin" body content, (b) the base layout template renders without errors (asserted indirectly by the absence of a template-engine exception on the index render), (c) `/admin/static/htmx.min.js` returns 200 + `Content-Type: application/javascript`.**

**Rationale.** `testApplication` is the standard Ktor test harness — fast, in-process, no external dependencies, no Docker required. The three assertions cover the three things this change ships: route mounting, template rendering, static asset serving. They're all happy-path; this is a scaffold change with no error-handling logic to exercise. A failed render would throw an exception that propagates back as a 500, so the 200-assertion implicitly verifies the render succeeded. The `Content-Type` assertion on the static asset verifies Decision 4's `staticResources` configuration works.

**Why no auth-related assertion.** This change deliberately ships an unauthenticated subtree (per the spec's Requirement 4). The "no auth required" property is asserted positively by the 200 on `/admin/` — no `Authorization` header is sent in the test, and the request succeeds. Future admin changes (Admin #3 onward) will add auth-related assertions; this change MUST NOT pre-empt them.

**Test file location.** `backend/ktor/src/test/kotlin/id/nearyou/app/admin/AdminScaffoldTest.kt` — co-located with other admin-package tests (per existing structure with `SuspensionUnbanWorker` tests if present, or as a fresh file otherwise).

**Alternatives considered:**
- **End-to-end browser test (Playwright / Selenium).** Rejected as massive overkill for a "hello admin" page. Adds a browser-driver toolchain dep + CI runtime cost. Defer to a real admin feature later if browser testing is needed.
- **Manual-only verification (`./gradlew run` + `curl http://localhost:8080/admin/`).** Rejected — the project's posture (per [`CLAUDE.md`](../../../CLAUDE.md) § Engineering judgment over context budget) is "documented debt is still debt; the default action is to ship the work." A 30-line `testApplication` block isn't debt worth deferring. Manual `curl` verification IS done additionally per the pre-archive checklist, but it complements automated coverage rather than replacing it.

## Risks / Trade-offs

- **Risk: Pebble dependency adds a new transitive surface area.** → Mitigation: pin via the Ktor BOM (no separate version coordinate); add a one-line entry to [`docs/09-Versions.md`](../../../docs/09-Versions.md) § Version Decisions justifying the pin; future security advisories on `pebble-templates/pebble` flow through normal Dependabot review.
- **Risk: Vendored HTMX file goes stale and misses security patches.** → Mitigation: HTMX security advisories are rare (single-file, ~13KB, well-audited surface). Add the version + SHA256 to a top-of-file comment so future maintainers can grep for "is this current?" Re-vendor at version bumps; the action is a one-line file replace.
- **Risk: Unauthenticated `/admin/*` subtree gets accidentally deployed to production.** → Mitigation: (a) production isn't live yet (pre-Public-Launch per project state); (b) the `/admin/` page content is a "hello admin" stub with zero sensitive data; (c) Admin #3 lands the auth gate before any sensitive admin surface mounts behind these routes; (d) the in-route Kotlin source carries a comment per spec Requirement 4 explicitly stating "auth lands in Admin #3" so a future reader can't miss the intentional-open-subtree posture; (e) surfaced explicitly in `proposal.md` § Impact so the squash-merge reviewer sanity-checks this.
- **Risk: Eventual extraction to a separate Cloud Run service exposes coupling we missed.** → Mitigation: Decision 3 explicitly shapes routing around the extraction target. If extraction reveals coupling, that's a Phase 3.5 deployment-task discovery and gets handled there — not a blocker for this scaffold.
- **Trade-off: choosing Pebble locks the admin templating story for the entire admin surface.** → Accepted. Switching engines later means rewriting every template file — non-trivial but bounded by the admin surface size. The decision is reversible at moderate cost (rewrite all `.peb` files as `.ftl` or vice versa) and irreversible at zero cost during this change (one file).
- **Trade-off: scaffold ships no styling.** → Accepted. Visual design comes alongside the first real admin feature (likely Admin #4 `admin-actions-log-viewer`). The "hello admin" page is verification-grade, not user-facing.

## Migration Plan

No migration — greenfield scaffold. Deploy posture under [`openspec/project.md`](../../project.md) § Change Delivery Workflow's "pre-archive smoke" convention:

1. Feat commits land + CI green (lint + test).
2. Pre-archive smoke step: SKIPPED. The admin UI is unauthenticated + has no DB / runtime side effects in this change. Manual local verification (`./gradlew run` + `curl -i http://localhost:8080/admin/` + `curl -i http://localhost:8080/admin/static/htmx.min.js`) is sufficient. Note this in the `tasks.md` Section 6 entry.
3. Archive commit + `openspec validate --specs admin-panel-scaffold --strict` green.
4. Squash-merge → auto-deploys to `main`-staging. `https://api-staging.nearyou.id/admin/` becomes reachable; the "hello admin" stub IS publicly accessible (intentional per the unauthenticated-subtree design); Admin #3 closes this before any sensitive surface lands.

**Rollback strategy.** If anything in this change breaks the main API deploy (extremely unlikely since the change is additive — new dependency, new routes, new files; no edits to existing handlers), revert the squashed commit on `main` via `git revert` + new PR. The admin subtree being unauthenticated is NOT a rollback trigger — it's an intentional scaffold posture, and Admin #3 closes it.

## Open Questions

None. All decisions resolved above.
