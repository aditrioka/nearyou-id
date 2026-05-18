## ADDED Requirements

### Requirement: `Application.adminPanel()` extension mounts `/admin/*` route subtree

A top-level Ktor extension function `fun Application.adminPanel()` SHALL be defined in `backend/ktor/src/main/kotlin/id/nearyou/app/admin/AdminPanelModule.kt`. When invoked, the function MUST install the Pebble template plugin and mount a `routing { route("/admin") { ... } }` block containing the GET `/admin`, GET `/admin/ping`, and `/admin/static/*` handlers defined by the requirements below. The function SHALL be the sole entry point for admin route registration; no other call site MAY mount `/admin/*` routes outside this function.

Authentication MUST NOT be installed inside `adminPanel()` — the scaffold is intentionally unauthenticated (per `design.md` D8). The defense against accidental production exposure is the `ADMIN_PANEL_ENABLED` gate enforced at the call site, not in-function auth.

#### Scenario: Function exists and is callable on Application

- **WHEN** `AdminPanelModule.kt` is loaded
- **THEN** the top-level symbol `Application.adminPanel` resolves to a `(Application) -> Unit` extension function callable from `Application.module()` without additional parameters

#### Scenario: Invocation mounts the `/admin/*` route subtree

- **GIVEN** an `Application` instance with no `/admin/*` routes registered
- **WHEN** `application.adminPanel()` is invoked
- **THEN** the application's routing tree contains routes for `GET /admin`, `GET /admin/ping`, and the `/admin/static/*` static-asset prefix

#### Scenario: No `Authentication` plugin installed on the admin subtree

- **WHEN** the test inspects the route attributes for `/admin`, `/admin/ping`, `/admin/static/htmx.min.js` after `adminPanel()` invocation
- **THEN** none of the routes carry a Ktor `Authentication` plugin selector — requests reach the handlers without auth verification, matching the design D8 explicit-no-auth posture

### Requirement: `ADMIN_PANEL_ENABLED` env-var gate controls mounting

The `Application.module()` call site MUST conditionally invoke `adminPanel()` based on a runtime gate. The gate reads environment variable `ADMIN_PANEL_ENABLED` (case-sensitive) and resolves to:

- `true` (mount the routes) when the env var is the literal string `"true"`, OR when the env var is unset AND `ktorEnv` is `"dev"` OR `"test"`.
- `false` (do NOT mount the routes) when the env var is the literal string `"false"`, OR when the env var is unset AND `ktorEnv` is `"staging"` OR `"production"`, OR for any unrecognized env-var value.

When the gate evaluates `false`, the `/admin/*` routes MUST NOT be present in the routing tree at all — requests SHALL receive Ktor's default unmatched-route 404 response (per design D3: 404 reveals less than 503).

#### Scenario: Default-closed in staging

- **GIVEN** `ktorEnv = "staging"` AND `ADMIN_PANEL_ENABLED` env var is unset
- **WHEN** `Application.module()` runs
- **THEN** `adminPanel()` is NOT invoked AND `GET /admin` returns `404 Not Found`

#### Scenario: Default-closed in production

- **GIVEN** `ktorEnv = "production"` AND `ADMIN_PANEL_ENABLED` env var is unset
- **WHEN** `Application.module()` runs
- **THEN** `adminPanel()` is NOT invoked AND `GET /admin` returns `404 Not Found`

#### Scenario: Default-open in dev

- **GIVEN** `ktorEnv = "dev"` AND `ADMIN_PANEL_ENABLED` env var is unset
- **WHEN** `Application.module()` runs
- **THEN** `adminPanel()` is invoked AND `GET /admin` returns `200 OK`

#### Scenario: Default-open in test

- **GIVEN** `ktorEnv = "test"` AND `ADMIN_PANEL_ENABLED` env var is unset
- **WHEN** `Application.module()` runs
- **THEN** `adminPanel()` is invoked AND `GET /admin` returns `200 OK`

#### Scenario: Explicit override to true in staging

- **GIVEN** `ktorEnv = "staging"` AND `ADMIN_PANEL_ENABLED = "true"`
- **WHEN** `Application.module()` runs
- **THEN** `adminPanel()` is invoked AND `GET /admin` returns `200 OK` (override path used for the pre-archive smoke step per `design.md` Migration Plan step 12)

#### Scenario: Explicit override to false in dev

- **GIVEN** `ktorEnv = "dev"` AND `ADMIN_PANEL_ENABLED = "false"`
- **WHEN** `Application.module()` runs
- **THEN** `adminPanel()` is NOT invoked AND `GET /admin` returns `404 Not Found`

#### Scenario: Unrecognized env-var value treated as false

- **GIVEN** `ktorEnv = "dev"` AND `ADMIN_PANEL_ENABLED = "yes"` (not the literal `"true"` or `"false"`)
- **WHEN** `Application.module()` runs
- **THEN** `adminPanel()` is NOT invoked AND `GET /admin` returns `404 Not Found` (defensive parsing — only the exact string `"true"` opts in to mounting)

### Requirement: Pebble is the template engine

The admin panel SHALL use Pebble (`io.ktor:ktor-server-pebble`, version inherited from the project's Ktor BOM) as its template engine. Freemarker, Thymeleaf, and the Ktor HTML DSL MUST NOT be introduced for admin templates. Template files SHALL be stored at `backend/ktor/src/main/resources/templates/admin/` with the `.peb` extension.

`adminPanel()` MUST install the Pebble plugin with the resource-loader pointing at `templates/admin/` (so templates can be referenced as `index.peb` rather than the longer `templates/admin/index.peb`).

#### Scenario: Pebble plugin is installed

- **WHEN** the test inspects `application.pluginRegistry` after `adminPanel()` invocation
- **THEN** the Pebble plugin (`Pebble` plugin key from `io.ktor.server.pebble`) is present in the registry

#### Scenario: Templates directory is the admin subfolder

- **GIVEN** the Pebble engine is configured by `adminPanel()`
- **WHEN** the test renders the template literal `index.peb`
- **THEN** the engine resolves the template from `backend/ktor/src/main/resources/templates/admin/index.peb` (i.e., the configured root is `templates/admin/`, not `templates/`)

#### Scenario: Version catalog declares the Pebble artifact entry

- **WHEN** the test inspects `gradle/libs.versions.toml` for the Pebble entry
- **THEN** the file contains a line matching the regex `ktor-serverPebble\s*=\s*\{\s*module\s*=\s*"io\.ktor:ktor-server-pebble-jvm"` AND that entry uses `version.ref = "ktor"` (inheriting the project's existing Ktor version pin; no separate explicit version)

#### Scenario: `:backend:ktor` references the Pebble catalog alias

- **WHEN** the test inspects `backend/ktor/build.gradle.kts` for the Pebble dependency
- **THEN** the file contains a line matching `implementation\(libs\.ktor\.serverPebble\)` — matching the convention used by every other Ktor server module (`libs.ktor.serverCore`, `libs.ktor.serverStatusPages`, etc.) — AND does NOT contain a raw `implementation("io.ktor:ktor-server-pebble...")` string (raw strings outside the catalog convention are forbidden)

### Requirement: `GET /admin` renders the "hello admin" Pebble template

`GET /admin` SHALL render the Pebble template `index.peb` and return it as `text/html; charset=UTF-8` with HTTP status `200 OK`. The rendered HTML MUST contain:

1. An `<h1>` element with text "Hello admin" (exact match, case-sensitive).
2. A `<button>` element with attributes `hx-get="/admin/ping"`, `hx-target="#ping-result"`, and `hx-swap="innerHTML"` (the HTMX-powered interaction per `design.md` D6).
3. A `<div id="ping-result">` element (initially empty) — the HTMX swap target.
4. A `<script src="/admin/static/htmx.min.js"></script>` tag referencing the vendored HTMX file (per `design.md` D2 and D7).

The template MUST NOT contain any inline JavaScript beyond what is required by HTMX (i.e., no `<script>` blocks with embedded JS besides the HTMX library include).

#### Scenario: 200 OK with text/html

- **WHEN** an HTTP `GET /admin` request is made with `ADMIN_PANEL_ENABLED=true`
- **THEN** the response status is `200 OK` AND the `Content-Type` header starts with `text/html`

#### Scenario: Body contains the "Hello admin" heading

- **WHEN** the response body to `GET /admin` is parsed
- **THEN** it contains the literal substring `<h1>Hello admin</h1>` (or `<h1>` and `Hello admin` and `</h1>` in order, allowing for `<h1 class="..">` attribute variation)

#### Scenario: Body contains the HTMX-powered button

- **WHEN** the response body to `GET /admin` is parsed
- **THEN** it contains a `<button>` element with attribute `hx-get="/admin/ping"` AND attribute `hx-target="#ping-result"` AND attribute `hx-swap="innerHTML"`

#### Scenario: Body contains the HTMX swap target div

- **WHEN** the response body to `GET /admin` is parsed
- **THEN** it contains a `<div>` element with attribute `id="ping-result"`

#### Scenario: Body includes the vendored HTMX script tag

- **WHEN** the response body to `GET /admin` is parsed
- **THEN** it contains a `<script>` element with attribute `src="/admin/static/htmx.min.js"`

#### Scenario: No inline JavaScript beyond HTMX include

- **WHEN** the response body to `GET /admin` is parsed
- **THEN** there are no `<script>` elements with non-empty inner text (the only `<script>` tag is the empty-body HTMX library include from the previous scenario)

### Requirement: `GET /admin/ping` returns an HTML fragment with a UTC timestamp

`GET /admin/ping` SHALL return an HTML fragment (NOT JSON, per `design.md` D6) with HTTP status `200 OK` and `Content-Type: text/html; charset=UTF-8`. The fragment MUST contain:

1. A `<span>` element with class `admin-ping-result`.
2. A `data-utc` attribute on that span whose value is a valid ISO-8601 UTC instant string in the form `YYYY-MM-DDTHH:MM:SSZ` (or with a fractional-second part) representing the server's current UTC time at request handling.
3. Human-readable inner text including the timestamp.

The response body MUST NOT contain a full HTML document (`<!DOCTYPE>`, `<html>`, `<head>`, `<body>` — fragments only, suitable for HTMX `innerHTML` swap).

#### Scenario: 200 OK with text/html

- **WHEN** an HTTP `GET /admin/ping` request is made with `ADMIN_PANEL_ENABLED=true`
- **THEN** the response status is `200 OK` AND the `Content-Type` header starts with `text/html`

#### Scenario: Body is a fragment, not a document

- **WHEN** the response body to `GET /admin/ping` is parsed
- **THEN** it does NOT contain a `<!DOCTYPE>` declaration AND does NOT contain `<html>` or `<body>` tags

#### Scenario: Body contains the span with class admin-ping-result

- **WHEN** the response body to `GET /admin/ping` is parsed
- **THEN** it contains a `<span>` element with attribute `class="admin-ping-result"` (or a class list containing `admin-ping-result`)

#### Scenario: data-utc carries a parseable UTC instant

- **WHEN** the response body to `GET /admin/ping` is parsed AND the `data-utc` attribute value is extracted
- **THEN** the value parses successfully as a `java.time.Instant` (regex `^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z$`)

#### Scenario: data-utc represents a recent timestamp

- **WHEN** `GET /admin/ping` is called at wall-clock time T
- **THEN** the parsed `data-utc` instant is within ±5 seconds of T (allows for slow test environments + clock skew)

### Requirement: HTMX is vendored at `backend/ktor/src/main/resources/static/admin/htmx.min.js`

A copy of HTMX (version pinned in `AdminPanelModule.kt` KDoc, expected to be v2.0.x as of authoring) SHALL exist at `backend/ktor/src/main/resources/static/admin/htmx.min.js` as a committed file. CDN-loaded HTMX is NOT permitted (per `design.md` D2).

The file MUST be served via `GET /admin/static/htmx.min.js` returning HTTP `200 OK` with `Content-Type: application/javascript` (or `text/javascript`; either is acceptable per RFC 9239) and the raw file bytes as the body.

#### Scenario: Vendored file exists in source tree

- **WHEN** the test inspects the filesystem at `backend/ktor/src/main/resources/static/admin/htmx.min.js`
- **THEN** the file exists AND is non-empty (> 1KB; sanity-check against an accidentally-empty commit)

#### Scenario: GET /admin/static/htmx.min.js returns the file

- **WHEN** an HTTP `GET /admin/static/htmx.min.js` request is made with `ADMIN_PANEL_ENABLED=true`
- **THEN** the response status is `200 OK` AND the `Content-Type` header is `application/javascript` OR `text/javascript` AND the body length matches the file size on disk

#### Scenario: Pinned HTMX version is documented

- **WHEN** the test inspects `AdminPanelModule.kt` for the KDoc on the static-asset declaration
- **THEN** the KDoc contains the literal substring `htmx` AND a version number matching the regex `[0-9]+\.[0-9]+\.[0-9]+` (the pin)

#### Scenario: No CDN reference in the rendered admin page

- **WHEN** the response body to `GET /admin` is parsed for `<script>` element `src` attributes
- **THEN** none of the `src` values point to an external host (no `https://unpkg.com/...`, `https://cdn.jsdelivr.net/...`, etc.) — every `src` is a relative path under `/admin/static/`

### Requirement: `admin_app` REVOKE/GRANT runbook step lands in `docs/07-Operations.md`

A new operational runbook subsection SHALL be added under [`docs/07-Operations.md`](../../../../docs/07-Operations.md) § Data Access Pattern (or as a sibling section to it within the same file) codifying the procedure for applying the `admin_app` DB role permissions per environment. The subsection MUST include:

1. The exact `REVOKE UPDATE, DELETE ON admin_actions_log FROM admin_app;` statement.
2. The complete set of `GRANT` statements for the `admin_app` role's row-level access (matching the per-table description at [`docs/07-Operations.md` § Data Access Pattern](../../../../docs/07-Operations.md): `users`, `posts`, `post_replies`, `reports`, `moderation_queue`, `admin_actions_log`, `csam_detection_archive`, and other operational tables).
3. The Supabase Console procedure path (Dashboard → Database → Roles → `admin_app` → Edit grants).
4. The `gcloud sql connect` + psql shell alternative for restricted-Console environments.
5. An idempotency note: REVOKE is idempotent (no-ops if permission isn't held); GRANT may need a pre-REVOKE if narrowing.
6. A verification query: `SELECT grantee, privilege_type FROM information_schema.table_privileges WHERE table_name = 'admin_actions_log' AND grantee = 'admin_app';` with the expected post-REVOKE result (only `SELECT` and `INSERT` privileges should remain).

This runbook step closes the explicit Admin #2 deferral from [`openspec/specs/admin-schema/spec.md:276`](../../../../openspec/specs/admin-schema/spec.md).

#### Scenario: Runbook section exists with the canonical REVOKE statement

- **WHEN** the test reads `docs/07-Operations.md`
- **THEN** it contains the literal substring `REVOKE UPDATE, DELETE ON admin_actions_log FROM admin_app`

#### Scenario: Runbook section references the verification query

- **WHEN** the test reads `docs/07-Operations.md`
- **THEN** it contains the literal substring `information_schema.table_privileges` AND the substring `grantee = 'admin_app'`

#### Scenario: Runbook section is discoverable from § Data Access Pattern

- **WHEN** the test reads `docs/07-Operations.md` and locates the `## Admin Panel — DESIGN` or `### Data Access Pattern` section
- **THEN** the new runbook subsection appears either inside the same section or as the immediately-following sibling section (heading appears within 200 lines of the § Data Access Pattern heading)

#### Scenario: Runbook section documents both Console + gcloud paths

- **WHEN** the test reads the new runbook subsection
- **THEN** it contains both the literal substring `Supabase Console` (or `Supabase Dashboard`) AND the literal substring `gcloud sql connect` (or `gcloud` + `psql`)

### Requirement: `docs/04-Architecture.md` admin module description is amended in this change

[`docs/04-Architecture.md:170`](../../../../docs/04-Architecture.md) currently contains the prose "`SuspensionUnbanWorker` + `UnbanWorkerRoute` ONLY. **No admin UI, no admin REST surface, no `/admin/*` routes.**" The "no `/admin/*` routes" claim becomes false the moment this change ships. The change SHALL amend that paragraph to (a) reflect the new `Application.adminPanel()` extension + Pebble + HTMX + env-gate scaffold, and (b) preserve the "no auth, no business features yet" framing as it remains accurate post-change. The amendment MUST happen in the same PR / commit graph as the code change — not deferred to a follow-up — to avoid the inconsistency window between code merge and doc merge (per `design.md` D9).

#### Scenario: Stale "no `/admin/*` routes" prose is removed

- **WHEN** the test reads `docs/04-Architecture.md` post-change
- **THEN** the file does NOT contain the literal substring `no /admin/* routes` AND does NOT contain the substring `no admin REST surface` (those phrases become false post-change)

#### Scenario: Updated admin module description references the new scaffold

- **WHEN** the test reads `docs/04-Architecture.md` post-change and locates the admin module bullet
- **THEN** the bullet's description contains references to `Application.adminPanel` OR `admin panel scaffold` AND `ADMIN_PANEL_ENABLED` (the env-gate variable name) so that future readers can find the scaffold's source from this entry point

### Requirement: `docs/07-Operations.md` opening status note is amended in this change

[`docs/07-Operations.md:5`](../../../../docs/07-Operations.md) currently contains the opening status note "The Admin Panel is **DESIGN** — Phase 3.5 deferred-to-post-MVP per `08-Roadmap-Risk.md`." With Admin #1 (schema) already shipped and Admin #2 (this scaffold) shipping, this opening framing needs to shift. The change SHALL amend the opening status note to (a) acknowledge the schema (V16 via Admin #1) AND the scaffold (this change) have shipped, (b) make explicit that auth + business features remain DESIGN per Phase 3.5 ordering, and (c) preserve the canonical authority of the rest of the § Admin Panel section.

#### Scenario: Stale "fully DESIGN" framing is updated

- **WHEN** the test reads `docs/07-Operations.md` lines 1-15 post-change
- **THEN** the opening status note does NOT claim the Admin Panel is fully `DESIGN` (it MAY use phrases like "partial scaffold shipped" or "schema + scaffold shipped; auth + business features remain DESIGN") AND it DOES still reference Phase 3.5 ordering for the deferred work

#### Scenario: Opening note acknowledges Admin #1 + Admin #2

- **WHEN** the test reads `docs/07-Operations.md` lines 1-15 post-change
- **THEN** the opening status note contains a reference identifying that the V16 schema (Admin #1) AND the route subtree scaffold (Admin #2) have shipped (acceptable phrasings: explicit "V16" / "admin-schema" / "admin-panel" mentions; OR "scaffold has shipped" with link to the relevant archived change)
