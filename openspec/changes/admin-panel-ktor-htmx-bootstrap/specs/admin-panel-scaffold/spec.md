## ADDED Requirements

### Requirement: Admin routes mount under `/admin/*` namespace

The system SHALL expose an admin-panel route subtree under the `/admin/*` URL namespace on the `:backend:ktor` deployable. The subtree SHALL be wired via an `Application.admin()` Kotlin extension function called from `Application.module()`, mirroring the shape of `Application.module()` so future extraction into a separate Cloud Run service for `admin.nearyou.id` (per [`docs/07-Operations.md`](../../../../../docs/07-Operations.md) § Stack) is mechanical. The subtree SHALL serve a `/admin/` index route in this change; additional admin routes land in subsequent admin changes. The mount SHALL be guarded against accidental production exposure per Requirement 5 below (the `KTOR_ENV != "production"` mount-guard requirement).

#### Scenario: Index route returns 200 with rendered template content

- **WHEN** a client sends `GET /admin/` to the `:backend:ktor` deployable
- **THEN** the response status SHALL be 200
- **AND** the response body SHALL contain text identifying the page as the admin index (e.g., a "hello admin" heading or equivalent identifier — exact content authored at implementation time)
- **AND** the response body SHALL be rendered HTML produced by the templating engine chosen in `design.md` Decision 1 (not a raw string written via `respondText`)

#### Scenario: Unmapped admin routes return 404

- **WHEN** a client sends `GET /admin/nonexistent-page` to the `:backend:ktor` deployable
- **THEN** the response status SHALL be 404
- **AND** the route subtree SHALL be present (this 404 is a path-not-found result, not a "namespace doesn't exist" result that would surface as the API's 404 handler)

#### Scenario: Non-GET methods on the admin index return 405

- **WHEN** a client sends `POST /admin/` (or any non-GET method) to the `:backend:ktor` deployable
- **THEN** the response status SHALL be 405 Method Not Allowed
- **AND** the route exists but only GET is wired in this change (subsequent admin changes will wire POST/PUT/DELETE handlers as auth-gated business actions land)

### Requirement: Shared base layout template exists and is extended by admin pages

The system SHALL provide a base layout template under `src/main/resources/templates/admin/` in the chosen templating engine's format (per `design.md` Decision 1) that admin pages extend. The layout SHALL include three structural sections: a header, a navigation stub (placeholder links — no functional pages behind them in this change), and a footer. The `/admin/` index page SHALL extend this layout rather than inlining the layout markup.

#### Scenario: Index page extends the base layout

- **WHEN** `GET /admin/` is served
- **THEN** the rendered HTML SHALL contain the header, navigation stub, and footer markup defined by the base layout template
- **AND** the rendered HTML SHALL contain the index-page-specific content block (the "hello admin" body)
- **AND** template rendering SHALL complete without throwing a template-engine exception (asserted indirectly by the 200 status on the request)

### Requirement: HTMX is available as the client interaction layer

The system SHALL load the HTMX JavaScript library on every admin page. The delivery mechanism SHALL be the one chosen in `design.md` Decision 2 (vendored static asset OR CDN reference); the chosen mechanism SHALL be consistent across all admin pages.

#### Scenario: HTMX is loaded on the admin index page

- **WHEN** `GET /admin/` is served
- **THEN** the rendered HTML SHALL include a `<script>` tag whose `src` attribute references the HTMX library at the path chosen in `design.md` Decision 2 (either `/admin/static/htmx.min.js` for the vendored option or the official `htmx.org` CDN URL for the CDN option)

#### Scenario: Vendored HTMX asset is served via the static-resource handler

- **WHEN** a client sends `GET /admin/static/htmx.min.js` to the `:backend:ktor` deployable
- **THEN** the response status SHALL be 200
- **AND** the response `Content-Type` SHALL be either `application/javascript` OR `text/javascript` (per Ktor's `staticResources` default for `.js` extensions — the test SHALL accept either value as success)
- **AND** the response body SHALL be the vendored HTMX library contents

#### Scenario: Path-traversal attempts under the static prefix return 404

- **WHEN** a client sends `GET /admin/static/../../some-other-resource` (or any request whose path includes `..` segments that would escape the configured `admin/static` classpath prefix)
- **THEN** the response status SHALL be 404
- **AND** no classpath resource outside the `admin/static` prefix SHALL be served (Ktor's `staticResources` handler sanitizes `..` segments via `getResource` lookups that do not resolve relative path segments)

### Requirement: Admin subtree does NOT require authentication in this change

The system SHALL serve `/admin/*` routes without an authentication gate in this change. The admin module SHALL emit a WARN-level log line on every request handled by an `/admin/*` route, containing both the marker string `admin-login-argon2-totp` (so the future-gate reference is searchable in observability) AND a phrase identifying the scaffold as `unauthenticated scaffold`. The admin module's route definition SHALL additionally carry an in-source code comment referencing the `admin-login-argon2-totp` change (Admin #3 in the [`openspec/project.md`](../../../../../openspec/project.md) § Mobile + Admin Scaffolding Priority menu) as the source of the future authentication gate; this comment is documentation, not the spec's normative requirement — the normative observable behavior is the WARN log. This requirement SHALL be superseded by the authentication-gating requirement that the `admin-login-argon2-totp` change introduces (which will also remove the WARN log line, since the gate-landed state is positively observable by the log's disappearance).

#### Scenario: Unauthenticated request to the admin index succeeds

- **WHEN** a client sends `GET /admin/` with no `Authorization` header, no session cookie, and no `X-CSRF-Token` header
- **THEN** the response status SHALL be 200
- **AND** the response body SHALL be the rendered admin index page (per the first requirement)

#### Scenario: Unauthenticated-scaffold WARN log emitted on every admin route hit

- **WHEN** any request hits an `/admin/*` route (including the index page and the static-asset routes)
- **THEN** the application SHALL emit at least one WARN-level log line for that request
- **AND** the log line SHALL contain the substring `admin-login-argon2-totp`
- **AND** the log line SHALL contain the phrase `unauthenticated scaffold` (or grammatically-equivalent phrasing — implementation MAY choose the exact wording as long as both required substrings are present)
- **AND** the log line's emission point SHALL be one of: (i) a route-level interceptor in `Application.admin()`, or (ii) a once-per-process module-mount log emitted at application bootstrap (in which case the per-request mode is NOT required, and the test asserts the bootstrap log instead)

### Requirement: Production mount guard via `KTOR_ENV` check

The system SHALL guard the `Application.admin()` extension function so it no-ops when `KTOR_ENV == "production"`, ensuring the unauthenticated scaffold is structurally unreachable in the production environment until the `admin-login-argon2-totp` change (Admin #3) lifts the guard alongside landing the authentication gate. The guard SHALL emit a WARN log line on the no-op path so the deliberately-absent admin routes in production are positively observable, not silently invisible. This requirement complements the unauthenticated-subtree requirement above — defense-in-depth at the platform level (not just at the policy level).

#### Scenario: Mount guard prevents admin route registration in production

- **GIVEN** the application starts with `KTOR_ENV=production`
- **WHEN** the application bootstrap completes and `Application.module()` has called `admin()`
- **THEN** no `/admin/*` route SHALL be registered in the routing table
- **AND** a WARN-level log line SHALL be emitted at bootstrap containing `admin module skipped` and a reference to `admin-login-argon2-totp`

#### Scenario: Mount succeeds in non-production environments

- **GIVEN** the application starts with `KTOR_ENV=staging` (or `dev`, or any value other than `production`)
- **WHEN** the application bootstrap completes and `Application.module()` has called `admin()`
- **THEN** the `/admin/*` routes SHALL be registered (per the first requirement above)
- **AND** subsequent `GET /admin/` requests SHALL return 200 (per the first requirement's scenarios)
