## Context

Admin #1 (`admin-schema-bootstrap`) shipped V16 with the five admin tables + three FK backfills (PR [#107](https://github.com/aditrioka/nearyou-id/pull/107)). The admin Ktor package in `:backend:ktor` is still just `SuspensionUnbanWorker.kt` + `UnbanWorkerRoute.kt` (the `/internal/unban-worker` tick endpoint); no `/admin/*` routes, no template engine, no static-asset serving. The architecture canon at [`docs/04-Architecture.md:13`](../../../docs/04-Architecture.md) prescribes "Ktor server-side + Pebble/Freemarker + HTMX" but does not pick between the two template engines — that decision is one of the things this design owns. The eventual deployment target is a separate Cloud Run service at `admin.nearyou.id` behind IAP (per [`docs/04-Architecture.md:306`](../../../docs/04-Architecture.md) + [`docs/07-Operations.md` § Security Layer 1](../../../docs/07-Operations.md)), but the subdomain split is Phase 3.5 #2 deployment work; this scaffold lives on the existing `:backend:ktor` service for now.

The `csrf_token_hash` invariant and the `admin_app` REVOKE/GRANT runbook step are documented in [`openspec/project.md`](../../../openspec/project.md) § Coding Conventions and [`docs/07-Operations.md` § Data Access Pattern](../../../docs/07-Operations.md) respectively, but neither has a Detekt rule or a codified procedure today. Admin #1's archive spec at [`openspec/specs/admin-schema/spec.md:276`](../../specs/admin-schema/spec.md) explicitly punts the REVOKE runbook to "the Admin #2 (`admin-panel-ktor-htmx-bootstrap`) lifecycle" — this change is where it lands.

The scaffold is intentionally code-light: a route subtree, a template, a vendored HTMX file, a runbook section. The point is to establish the structural conventions (mounting pattern, template directory, static-asset path) that Admin #3-#5 build on, not to ship business behavior.

## Goals / Non-Goals

**Goals:**

- Establish the `Application.adminPanel()` extension as the canonical mount point for `/admin/*` routes; future admin features attach inside this subtree.
- Pick a template engine (Pebble) and commit to it across the admin panel lifecycle. Avoid the "decide later" pattern that creates churn when Admin #3 needs templates and the engine question is still open.
- Wire HTMX as a vendored static asset (not a CDN) so the admin panel has no third-party JS dependency at runtime — defense-in-depth against admin-surface JS supply-chain attack.
- Ship the scaffold env-gated-closed-in-production (`ADMIN_PANEL_ENABLED=false`) so the unauth'd routes cannot accidentally surface on the public Cloud Run service. Admin #3 (login) flips the default after the auth gate ships.
- Close the operational REVOKE runbook deferral from `admin-schema/spec.md:276` by adding a documented procedure to [`docs/07-Operations.md`](../../../docs/07-Operations.md).
- Provide integration-test coverage demonstrating: the env-gate works, the template renders, HTMX targets resolve, and the static asset is served correctly.

**Non-Goals:**

- Admin login flow, session middleware, CSRF verification, `__Host-admin_session` cookie issuance — Admin #3 (`admin-login-argon2-totp`).
- `admin_users` writes, password hashing, TOTP, WebAuthn — Admin #3.
- The `admin_app` DB role connection-string binding in Ktor — Admin #3 owns first DB consumer.
- Subdomain split to `admin.nearyou.id` separate Cloud Run service — Phase 3.5 #2 deployment work.
- IAP / Cloud Armor / VPN network-layer wiring — Phase 3.5 #2.
- Admin business features (audit log viewer, user search, report queue, suspension actions) — Admin #4+.
- Any Ktor plugin for admin authorization (`installAdminAuth`, role-based access) — Admin #3 + Admin #5 own these.
- A "real" admin landing page UI — the "hello admin" page exists to verify wiring, not to be the eventual home screen.

## Decisions

### D1: Template engine — Pebble over Freemarker

**Decision:** Use **Pebble** (`io.ktor:ktor-server-pebble-jvm`, pinned to the project's existing `ktor` version in [`gradle/libs.versions.toml`](../../../gradle/libs.versions.toml) — currently `3.4.1`, inherited via `version.ref = "ktor"`). The dependency is declared as a new `ktor-serverPebble` entry in the version catalog and referenced from [`backend/ktor/build.gradle.kts`](../../../backend/ktor/build.gradle.kts) as `implementation(libs.ktor.serverPebble)`. This matches the convention of every other Ktor server module in the file (`ktor.serverCore`, `ktor.serverNetty`, `ktor.serverStatusPages`, etc.). Templates live under `backend/ktor/src/main/resources/templates/admin/` with `.peb` extension.

**Rationale:**

- Ktor first-party support: both engines have official `io.ktor:ktor-server-*` artifacts. No compatibility risk on either side.
- Syntax: Pebble uses Django/Jinja-style `{{ var }}` + `{% block %}` + `{% extends %}` — closer to modern web template conventions familiar from Jinja/Twig/Nunjucks. Freemarker uses `${var}` + `<#assign>` + `<#include>` with a more verbose, less commonly-encountered syntax.
- Footprint: Pebble's runtime JAR is smaller (~250KB vs Freemarker's ~1.5MB). Negligible on Cloud Run but principle-aligned with the project's "minimize maintenance overhead" principle.
- HTMX hypermedia idiom alignment: HTMX returns HTML fragments. Pebble's `{% block %}` inheritance + partial-rendering pattern composes more naturally with the "small templates that return fragments" approach HTMX encourages.
- Reviewer / future-contributor recognition: Pebble's syntax is a Jinja near-clone; Jinja is more widely known than Freemarker's `<#...>` directive shape. Lower cost for any reader who's touched template engines in any language.

**Alternatives considered:**

- *Freemarker.* Rejected — heavier footprint, more idiosyncratic syntax, no offsetting benefit. Both engines support the features this scaffold needs (template inheritance, partial rendering, escape filters).
- *Plain Kotlin HTML DSL (`io.ktor:ktor-server-html-builder`).* Rejected — diverges from the documented "Pebble/Freemarker" canon at [`docs/04-Architecture.md:13`](../../../docs/04-Architecture.md) without amending the doc. HTML DSL is also harder to iterate on (every template tweak requires a Kotlin recompile) and harder for non-Kotlin reviewers to read.
- *Thymeleaf.* Rejected — not in the docs as a candidate; introducing a fourth option just to consider it is scope creep.

### D2: HTMX — vendored static asset, not CDN

**Decision:** Vendor HTMX into the repo at `backend/ktor/src/main/resources/static/admin/htmx.min.js`, pinning a known version (v2.0.x — the current major as of authoring). Serve it via Ktor's `staticResources("/admin/static", "static/admin")` route.

**Rationale:**

- Defense in depth: the admin panel is a high-value surface (destructive capability). Loading JS from a third-party CDN gives an outside party the ability to inject arbitrary code into the admin context if the CDN is compromised. Cloudflare CDN compromises have historically been short windows but they exist; the admin panel is the wrong place to accept that risk.
- Reproducibility: pinning the file in the repo means the JS shipped against staging matches the JS shipped against production exactly. CDN-loaded versions can drift if the CDN's tag-vs-hash resolution shifts.
- Air-gap friendliness: the admin panel must work in IAP-protected, egress-restricted environments. Loading from a CDN requires whitelisting that CDN in any egress proxy; vendoring removes the dependency.
- Cost: HTMX is ~14KB minified, gzipped to ~5KB. Trivial repo impact, no runtime download cost.
- SRI is not a substitute: Subresource Integrity (SRI) hash-pinning addresses the "swapped JS" attack but not the "CDN unavailable" or "egress whitelist" concerns. Vendoring addresses all three.

**Alternatives considered:**

- *CDN-loaded HTMX with SRI hash pinning.* Rejected — addresses the swap-attack vector but not unavailability or egress concerns. Adds runtime dependency on an external service for a surface that should be self-contained.
- *npm/jsdelivr-managed via a build step.* Rejected — the project has no JS bundler today (it's a pure Kotlin/JVM monolith). Adding one for ~14KB of HTMX would be disproportionate.

### D3: Env-gate via `ADMIN_PANEL_ENABLED` env var (default closed in staging/prod)

**Decision:** Read `ADMIN_PANEL_ENABLED` from the environment via the standard `secretKey(env, ...)`-style helper, default `false` for staging + production, `true` for `dev` and `test`. The gate is checked in [`Application.module()`](../../../backend/ktor/src/main/kotlin/id/nearyou/app/Application.kt) before invoking `adminPanel()`. When disabled, the `/admin/*` route subtree is not mounted at all (not even with a "panel disabled" page) — requests return 404 from Ktor's default unmatched-route handler.

**Rationale:**

- Unauth'd scaffold safety: the "hello admin" page itself is harmless, but mounting an unauth'd `/admin` endpoint on the public Cloud Run service sets a bad precedent. A reader-only attacker could fingerprint the admin panel's framework choice (Pebble headers, HTMX version) without exposure to actual data — that's already more reconnaissance than the scaffold should offer. Returning 404 makes the scaffold invisible to unauthenticated probes.
- Inversion vs Detekt's "fail-fast on missing secret" idiom: Redis, Firebase, OpenAI clients all fail-fast in staging/prod on missing secrets. The admin panel takes the opposite stance — present-but-disabled is the default, opt-in via env-var is required to mount. The asymmetry is intentional: those clients are required for application function; the admin panel is an internal tool that shouldn't ship to production until Admin #3 makes it usable.
- Admin #3 transition: when login lands, Admin #3 either (a) flips the default to `true` everywhere and the scaffold becomes auth-gated by virtue of the new login flow, or (b) ships its own flag (`ADMIN_PANEL_REQUIRE_AUTH=true` in staging/prod). Either path is a small wiring change; the choice is Admin #3's to make.
- Env var, not config property: the existing pattern in [`Application.module()`](../../../backend/ktor/src/main/kotlin/id/nearyou/app/Application.kt) reads booleans from env vars (e.g., `RUN_FLYWAY_ON_STARTUP`). Following that pattern reduces surprise — operators read one config surface.

**Alternatives considered:**

- *Always mount, return 503 when disabled.* Rejected — surfaces the framework choice to unauth'd probes (HTTP server header reveals Ktor; 503 reveals the path is wired). 404 is the correct "no such surface here" signal.
- *Mount in dev only (compile-time guard).* Rejected — prevents staging smoke testing of the scaffold. The runtime flag lets staging exercise the routes for debugging without flipping a public exposure.
- *Always mount + always return 401.* Rejected — implies authentication is in play, which is misleading until Admin #3 ships actual auth.

### D4: Mounting pattern — `Application.adminPanel()` extension function

**Decision:** Add a top-level extension `fun Application.adminPanel()` in `AdminPanelModule.kt`. The function installs the Pebble plugin (scoped to the call — Ktor's `install` is idempotent per Application) and mounts a `routing { route("/admin") { ... } }` block with the GET handlers + the static-asset subroute. [`Application.module()`](../../../backend/ktor/src/main/kotlin/id/nearyou/app/Application.kt) conditionally calls `adminPanel()` based on the `ADMIN_PANEL_ENABLED` gate.

**Rationale:**

- Pattern consistency: existing modules use the `fun Route.xxxRoutes(deps)` shape (e.g., `unbanWorkerRoute(worker)`, `postRoutes(createPostService)`). The admin scaffold is one level higher: it owns the route-tree structure AND the Pebble install AND the static-asset subroute, not just a handler. Promoting it to a top-level Application extension keeps `Application.module()` readable (`if (adminPanelEnabled) adminPanel()` is one line) and gives admin a clear single-file boundary that Admin #3-#5 can extend inside the same file or split into sibling files (`AdminLoginRoutes.kt`, `AdminAuditRoutes.kt`).
- Discoverability: a single `AdminPanelModule.kt` matching the package structure ([`id.nearyou.app.admin`](../../../backend/ktor/src/main/kotlin/id/nearyou/app/admin)) makes the admin subtree obvious to future contributors grepping for "admin".
- Subdomain split future-proofing: when the subdomain split happens (Phase 3.5 #2), `Application.adminPanel()` can be lifted into its own `:backend:ktor-admin` module or its own Ktor service entry-point with minimal refactor. The function's signature is the seam.

**Alternatives considered:**

- *Nested route block inside `Application.module()`.* Rejected — bloats `Application.module()` (already ~800 lines), couples admin wiring to the main app's growth, and makes the eventual subdomain split harder.
- *Separate `AdminApplication` Ktor entry point now.* Rejected — premature. The scaffold belongs in the existing service until the subdomain split work happens; introducing a second Ktor `main()` for a "hello admin" page is over-engineering.

### D5: REVOKE runbook step location — `docs/07-Operations.md` § Data Access Pattern subsection

**Decision:** Add a new subsection `### Applying `admin_app` REVOKE / GRANT statements per environment` inside the existing § Data Access Pattern section of [`docs/07-Operations.md`](../../../docs/07-Operations.md). The procedure documents:

1. The exact `REVOKE UPDATE, DELETE ON admin_actions_log FROM admin_app;` statement.
2. The exact `GRANT SELECT, INSERT ON admin_actions_log TO admin_app;` statement (and analogous GRANTs for the other operational tables per [`docs/07-Operations.md` § Data Access Pattern](../../../docs/07-Operations.md) prose).
3. The Supabase Console path (Dashboard → Database → Roles → `admin_app` → Edit grants) AND the `gcloud sql connect` + psql shell alternative for environments where Console access is restricted.
4. An idempotency note: REVOKE is idempotent (Postgres no-ops if the permission isn't held); GRANT may need a pre-REVOKE if narrowing permissions.
5. The verification query: `SELECT grantee, privilege_type FROM information_schema.table_privileges WHERE table_name = 'admin_actions_log' AND grantee = 'admin_app';` — expected result post-REVOKE.

**Rationale:**

- Single canonical location: the doc already owns the operational role description ([`docs/07-Operations.md` § Data Access Pattern](../../../docs/07-Operations.md)) listing the permissions; the procedure for *applying* them belongs adjacent to that description.
- Discoverability: future ops onboarding will read § Data Access Pattern; the procedure lives where they're already looking.
- Closes the explicit Admin #2 deferral: [`openspec/specs/admin-schema/spec.md:276`](../../specs/admin-schema/spec.md) says "*A new operational runbook step in the Admin #2 (`admin-panel-ktor-htmx-bootstrap`) lifecycle will codify the Supabase Console / `gcloud` procedure for applying these REVOKEs idempotently against each environment.*" This change owns landing it.

**Alternatives considered:**

- *Land as a separate `docs/11-admin-runbook.md` file.* Rejected — premature file proliferation. The procedure is one section; it belongs alongside the role description that motivates it.
- *Defer to Admin #3 (which actually consumes the role).* Rejected — Admin #1's archive spec explicitly assigns this to Admin #2's lifecycle. Honoring that assignment keeps the deferral chain finite (no "we'll do it next change" indefinite slide).

### D6: HTMX target endpoint shape — HTML fragment, not JSON

**Decision:** `GET /admin/ping` returns `text/html` with body `<span class="admin-ping-result" data-utc="2026-05-18T03:14:15Z">Pong (2026-05-18T03:14:15Z)</span>` (or analogous). Not JSON.

**Rationale:**

- HTMX is hypermedia-driven: the response IS the rendered fragment. Returning JSON would require client-side rendering, defeating HTMX's purpose. The fragment can be inserted directly via `hx-target` + `hx-swap`.
- The pattern this endpoint establishes — return HTML fragments, no JSON layer between server and view — is the model every future admin HTMX endpoint will follow. Establishing it here gives Admin #3+ a clear template.
- The fragment carries a `data-utc` attribute so tests can assert on the structured value without HTML-parsing the human-readable display string.

**Alternatives considered:**

- *JSON + client-side render.* Rejected — defeats the HTMX hypermedia model. If we want JSON, we shouldn't be using HTMX.
- *Plain text with no HTML structure.* Rejected — slightly harder to insert via HTMX's default `innerHTML` swap (works, but the fragment doesn't carry the assertable `data-utc` attribute).

### D7: Static asset serving — classpath resource, not filesystem

**Decision:** Use Ktor's `staticResources("/admin/static", "static/admin")` (classpath-relative). The HTMX file lives at `backend/ktor/src/main/resources/static/admin/htmx.min.js` and is bundled into the application JAR at build time.

**Rationale:**

- Cloud Run friendliness: classpath resources are baked into the deployed JAR; no filesystem layout assumption. Filesystem-based static serving (`staticFiles("/admin/static", File("admin/static"))`) couples deploy artifact to working-directory layout, which is brittle on Cloud Run.
- Build reproducibility: classpath bundling means the test JAR and the production JAR have the same HTMX file at the same path. No "works locally, missing in container" surprise.
- Single source of truth: the file lives in `src/main/resources/`, version-controlled, diffed in PRs like any other source file.

**Alternatives considered:**

- *Filesystem-based static serving from a sibling directory.* Rejected — couples deploy to working-directory layout (see above).
- *Inline HTMX into the Pebble template via `<script>...</script>`.* Rejected — bloats the template, breaks browser caching of the JS asset across page navigations.

### D8: No auth gate, intentionally — and the gate is the env-var, not Ktor authentication

**Decision:** No `Authentication` plugin install on the `/admin/*` route subtree. The defense is exclusively the `ADMIN_PANEL_ENABLED` env-var gate (D3): when false, the routes don't exist; when true (dev/test only by default), the routes serve unauth'd content. No partial auth — no "logged-in or anonymous" branching.

**Rationale:**

- Avoids half-shipped auth shape. Authentication is Admin #3's domain; building a "no-op auth plugin" here that Admin #3 then has to rip out is anti-pattern.
- The env-var gate is a complete defense for the staging/prod posture: routes don't exist there. The dev/test posture exposes the routes locally, which is the intended behavior (the developer using the scaffold needs to interact with it).
- Explicit non-implementation: a comment in `AdminPanelModule.kt` documents that auth is intentionally absent and points to Admin #3 as the change that adds it. Code review can spot the gap and link to this design's D8.

**Alternatives considered:**

- *Install Ktor's `Authentication` plugin with a no-op verifier.* Rejected — implies auth exists, misleads readers and security review.
- *Add HTTP basic auth as a placeholder.* Rejected — encourages "scaffold creds" leakage; the env-var gate is a cleaner stand-in.

### D9: Bundle coordinated doc updates with the code change (don't defer)

**Decision:** This change amends three doc surfaces in the same PR as the code:

1. [`docs/07-Operations.md`](../../../docs/07-Operations.md) — add the new REVOKE/GRANT runbook subsection (D5 above).
2. [`docs/04-Architecture.md:170`](../../../docs/04-Architecture.md) admin module bullet — currently reads "`SuspensionUnbanWorker` + `UnbanWorkerRoute` ONLY. **No admin UI, no admin REST surface, no `/admin/*` routes.**" The "no `/admin/*` routes" prose becomes false the moment this change merges. Update to a current-and-forward-looking text reflecting the scaffold (Application.adminPanel(), Pebble, HTMX, env-gate) while preserving the "no auth, no business features" framing.
3. [`docs/07-Operations.md:5`](../../../docs/07-Operations.md) opening status note — currently reads "The Admin Panel is **DESIGN** — Phase 3.5 deferred-to-post-MVP". With Admin #1 (schema) shipped and Admin #2 (this scaffold) shipping, the framing needs to shift from "fully DESIGN" to "schema + scaffold shipped; auth and business features remain DESIGN per Phase 3.5". Preserves the canonical authority of the section but reflects current state.

**Rationale:**

- Bundling these doc updates with the code change avoids near-immediate doc drift. A merged change that ships the scaffold while leaving "no `/admin/*` routes" in canonical docs is a self-inflicted inconsistency that the next AI session (or human reader) trips on.
- The doc edits are small (one paragraph each) and tightly scoped to the surfaces that this change directly touches. They are NOT bonus refactors — they reflect what this change does.
- The change-author's reconciliation pass (per `/next-change` Phase B step 7) explicitly catches drift like this. Honoring the rule means amending the docs here.

**Alternatives considered:**

- *Land doc updates as a follow-up.* Rejected — creates a guaranteed inconsistency window between code merge and doc merge. The 4-page bundle is a one-PR change that lives entirely on this branch.
- *Update only the most directly-affected line (architecture admin bullet); skip the operations status note.* Rejected — both are equally directly affected. Half-updating still leaves a stale claim.
- *Treat as separate change to keep code PR focused.* Rejected — bundling matches the project's general convention of amending docs alongside the code that contradicts them (precedent: PR #19 amending docs alongside `global-timeline` corrections).

## Risks / Trade-offs

- **Risk:** Operator forgets to flip `ADMIN_PANEL_ENABLED=true` in dev and the scaffold appears broken ("why does /admin 404 locally?"). → **Mitigation:** the dev/test default is `true`. Developers don't need to set the env-var; staging/prod operators do (to disable, which is the default). A comment in `AdminPanelModule.kt` documents the gate behavior.

- **Risk:** Pebble artifact version drifts from the rest of the Ktor surface (`ktor = "3.4.1"` in [`gradle/libs.versions.toml`](../../../gradle/libs.versions.toml)), causing a runtime version skew. → **Mitigation:** the catalog entry uses `version.ref = "ktor"`, inheriting the existing pin. A Ktor bump updates every artifact (including Pebble) atomically; no drift possible from explicit pinning. The new Pebble entry follows the exact convention of `ktor-serverContentNegotiation` / `ktor-serverStatusPages` / etc.

- **Risk:** Vendored HTMX file becomes stale relative to upstream security fixes. → **Mitigation:** the pinned version is documented in `AdminPanelModule.kt` KDoc + a `FOLLOW_UPS.md`-style or `docs/07-Operations.md` runbook note for "check HTMX upstream for security advisories on this version annually." HTMX is a small, stable library (no known active CVE history); the annual cadence is sufficient.

- **Risk:** The "hello admin" page leaks information about the admin panel's framework choice in HTTP headers (`Server: ktor-server-core/...` + Pebble's response shape). → **Mitigation:** when `ADMIN_PANEL_ENABLED=false` (the staging/prod default), the routes don't exist — no headers to leak. When `true` (dev/test only), the attacker doesn't have network access. Admin #3 + the eventual subdomain split + IAP move this surface behind network-layer defenses where header fingerprinting is moot.

- **Risk:** The REVOKE runbook step lands in `docs/07-Operations.md` but is never executed against staging because no admin code consumes the `admin_app` role yet. → **Mitigation:** acceptable for this change. The procedure is documented; Admin #3 (which IS the first consumer of the `admin_app` role) executes it against staging as part of its pre-archive smoke. This change just establishes the canonical procedure; consumption is downstream.

- **Trade-off:** Picking Pebble locks the project into one engine for the admin panel's lifecycle (changing engines later means rewriting all templates). → **Mitigation:** the docs already mention "Pebble/Freemarker"; picking either was always going to be a one-way door. Pebble's modern Jinja-style syntax is the lower regret choice.

- **Trade-off:** Vendoring HTMX adds ~14KB to the deployed JAR + requires manual version updates. → **Mitigation:** trivial size impact; the security + reproducibility benefits justify the manual update cost (annual cadence per the staleness risk mitigation above).

## Migration Plan

1. **Land Pebble dependency** (via the project's version catalog, not raw artifact strings): add `ktor-serverPebble = { module = "io.ktor:ktor-server-pebble-jvm", version.ref = "ktor" }` to [`gradle/libs.versions.toml`](../../../gradle/libs.versions.toml) under the existing Ktor server module entries (alongside `ktor-serverStatusPages`, `ktor-serverAuth`, etc.). Then add `implementation(libs.ktor.serverPebble)` to [`backend/ktor/build.gradle.kts`](../../../backend/ktor/build.gradle.kts) alongside the existing Ktor server module references. Version inherits from `ktor = "3.4.1"`.

2. **Author `AdminPanelModule.kt`**: extension function `Application.adminPanel()` installing Pebble + mounting `/admin/*` route subtree with GET / + GET /ping + static subroute.

3. **Author Pebble template**: `backend/ktor/src/main/resources/templates/admin/index.peb` rendering the "hello admin" heading + HTMX button. Use `{% extends "admin/layout.peb" %}` for future shared chrome (header + nav placeholder) — `layout.peb` is a minimal stub for Admin #3 to flesh out.

4. **Vendor HTMX**: download pinned `htmx.min.js` (v2.0.x) to `backend/ktor/src/main/resources/static/admin/htmx.min.js`. Commit the file. Add a KDoc note in `AdminPanelModule.kt` pinning the version + linking to upstream's release notes.

5. **Wire into `Application.module()`**: add the conditional `if (adminPanelEnabled) adminPanel()` after the existing `notificationRoutes` / `fcmTokenRoutes` block. The flag reads `ADMIN_PANEL_ENABLED` env var, defaults `true` for `ktorEnv in {"dev", "test"}` and `false` otherwise.

6. **Author integration test**: `AdminPanelModuleSpec.kt` exercising the four scenarios from the proposal (gate=false → 404, gate=true → render, /ping → fragment, /static → asset).

7. **Author runbook section in `docs/07-Operations.md`**: insert the `### Applying admin_app REVOKE / GRANT statements per environment` subsection under § Data Access Pattern. Includes the SQL statements + Supabase Console path + `gcloud` alternative + verification query.

8. **Validate locally**: `./gradlew :backend:ktor:test --tests "*AdminPanelModuleSpec*"` + the full lint suite (`./gradlew ktlintCheck detekt :lint:detekt-rules:test`).

9. **Push to branch + open PR**: branch name `admin-panel-ktor-htmx-bootstrap`. PR title `docs(openspec): propose admin-panel-ktor-htmx-bootstrap` initially; retitled to `feat(admin): admin-panel-ktor-htmx-bootstrap` at first feat commit per the one-PR-per-change convention.

10. **Iterate via auto-review**: qodo + sub-agent review the proposal. Apply blocking findings on the same branch.

11. **Implement (via `/opsx:apply`)**: feat commits land on the same branch — `AdminPanelModule.kt` + Pebble templates + HTMX vendor + wiring + test + docs runbook.

12. **Pre-archive smoke step**: trigger staging deploy on the branch via `gh workflow run deploy-staging.yml --ref admin-panel-ktor-htmx-bootstrap`. Verify `/admin` returns 404 (default-closed) + manually flip the env-var via `gcloud run services update --update-env-vars ADMIN_PANEL_ENABLED=true` to verify the gate flips the route, then revert before archive. The flag-flip step is purely for smoke verification, not for staging persistence.

13. **Archive (via `/opsx:archive`)**: `openspec archive admin-panel-ktor-htmx-bootstrap` → moves the change under `openspec/changes/archive/`, syncs `openspec/specs/admin-panel-bootstrap/spec.md` from the change delta. Squash-merge to `main` → auto-deploys staging with `ADMIN_PANEL_ENABLED=false`.

14. **Rollback path** (if discovered post-merge): the scaffold ships in a default-closed state. Rollback is unmounting the route subtree, which is a one-line revert in `Application.module()`. The Pebble dependency + vendored HTMX file are no-op when not invoked.

## Open Questions

None blocking. Two soft questions surfaced during proposal authoring; both can be deferred to reviewer comments:

1. **Should the Pebble template directory use `templates/admin/` or `admin/templates/`?** The proposal picks `templates/admin/` to match Ktor's idiomatic `templates/` root and let `admin/` be a sub-namespace as more capability-specific template directories appear (e.g., `templates/auth/`, `templates/onboarding/`). Reviewer-overridable.

2. **Should the static-asset URL be `/admin/static/` or `/static/admin/`?** The proposal picks `/admin/static/` so the entire admin panel surface (HTML + static assets) lives under one prefix that the future subdomain split can lift cleanly. `/static/admin/` would scatter the surface across two prefixes. Reviewer-overridable.
