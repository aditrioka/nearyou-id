## ADDED Requirements

### Requirement: Admin routes mount under `/admin/*` namespace

The system SHALL expose an admin-panel route subtree under the `/admin/*` URL namespace on the `:backend:ktor` deployable. The subtree SHALL be wired via an `Application.admin()` Kotlin extension function called from `Application.module()`, mirroring the shape of `Application.module()` so future extraction into a separate Cloud Run service for `admin.nearyou.id` (per [`docs/07-Operations.md`](../../../../docs/07-Operations.md) § Stack) is mechanical. The subtree SHALL serve a `/admin/` index route in this change; additional admin routes land in subsequent admin changes.

#### Scenario: Index route returns 200 with rendered template content

- **WHEN** a client sends `GET /admin/` to the `:backend:ktor` deployable
- **THEN** the response status SHALL be 200
- **AND** the response body SHALL contain text identifying the page as the admin index (e.g., a "hello admin" heading or equivalent identifier — exact content authored at implementation time)
- **AND** the response body SHALL be rendered HTML produced by the templating engine chosen in `design.md` Decision 1 (not a raw string written via `respondText`)

#### Scenario: Unmapped admin routes return 404

- **WHEN** a client sends `GET /admin/nonexistent-page` to the `:backend:ktor` deployable
- **THEN** the response status SHALL be 404
- **AND** the route subtree SHALL be present (this 404 is a path-not-found result, not a "namespace doesn't exist" result that would surface as the API's 404 handler)

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

#### Scenario: Vendored HTMX asset is served via the static-resource handler (if Decision 2 picks vendored)

- **GIVEN** `design.md` Decision 2 selected the vendored delivery mechanism
- **WHEN** a client sends `GET /admin/static/htmx.min.js` to the `:backend:ktor` deployable
- **THEN** the response status SHALL be 200
- **AND** the response `Content-Type` SHALL be `application/javascript` (or `text/javascript`, per Ktor's `staticResources` default for `.js` extensions)
- **AND** the response body SHALL be the vendored HTMX library contents

### Requirement: Admin subtree does NOT require authentication in this change

The system SHALL serve `/admin/*` routes without an authentication gate in this change. The admin module's route definition SHALL carry an in-source code comment explicitly noting that the authentication gate lands in the `admin-login-argon2-totp` change (Admin #3 in the [`openspec/project.md`](../../../../openspec/project.md) § Mobile + Admin Scaffolding Priority menu), and that this scaffold deliberately ships an unauthenticated subtree to enable end-to-end scaffold verification. This requirement SHALL be superseded by the authentication-gating requirement that the `admin-login-argon2-totp` change introduces.

#### Scenario: Unauthenticated request to the admin index succeeds

- **WHEN** a client sends `GET /admin/` with no `Authorization` header, no session cookie, and no `X-CSRF-Token` header
- **THEN** the response status SHALL be 200
- **AND** the response body SHALL be the rendered admin index page (per the first requirement)

#### Scenario: In-source comment documents the deferred-auth posture

- **WHEN** a code reader inspects the Kotlin source file that defines the `/admin/*` route mount (the `Application.admin()` function from `design.md` Decision 3)
- **THEN** the source SHALL include a comment block (Kotlin `//` or `/* */`) referencing the `admin-login-argon2-totp` change as the source of the future authentication gate
- **AND** the comment SHALL state that the scaffold's unauthenticated posture is intentional and time-bound
