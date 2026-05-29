# admin-panel-scaffold Specification

## Purpose

The `admin-panel-scaffold` capability owns the in-process Ktor route subtree (`/admin/*`) that every subsequent admin-panel feature builds on. It wires the server-side templating engine (Pebble), HTMX as the client interaction layer (vendored under `/admin/static/`), a shared base layout (header + nav stub + footer), a `/admin/` index page, and classpath-served static assets — all encapsulated behind an `Application.admin()` extension function so the eventual extraction to a separate Cloud Run service for `admin.nearyou.id` (per [`docs/07-Operations.md`](../../../docs/07-Operations.md) § Stack) is mechanical. The mount is gated by a `KTOR_ENV != "production"` check so the deliberately-unauthenticated scaffold is structurally unreachable in production until the authentication gate lands in the `admin-login-argon2-totp` change (Admin #3 in the [`openspec/project.md`](../../project.md) § Mobile + Admin Scaffolding Priority menu).

Future admin capabilities — login + session + CSRF (Admin #3), audit-log viewer (Admin #4), suspend/unban action (Admin #5) — extend this subtree rather than introducing parallel route trees. Explicitly **out of scope** for this capability: authentication / sessions / CSRF, any DB access (the `admin-app-db-connection-string` GCP slot is NOT consumed), `admin.nearyou.id` subdomain DNS + IAP/Cloud Armor + separate Cloud Run service deployment (Phase 3.5 deployment task per [`docs/08-Roadmap-Risk.md`](../../../docs/08-Roadmap-Risk.md) § Phase 3.5), WebAuthn / TOTP / Argon2id, and any admin business features (Report Queue, User Management, etc., per `docs/07-Operations.md` § Core Features). The scaffold deliberately ships an open subtree to enable end-to-end scaffold validation; the WARN log emitted by the route-level interceptor makes the unauthenticated posture observable in Sentry, and its disappearance positively signals Admin #3's auth gate landing.

See [`docs/07-Operations.md` § Admin Panel § Stack](../../../docs/07-Operations.md) for the canonical admin-panel architecture; [`docs/08-Roadmap-Risk.md` § Phase 3.5](../../../docs/08-Roadmap-Risk.md) item 1 ("Ktor + HTMX admin panel") for the build-phase context; and the in-source comment block at the top of `backend/ktor/src/main/kotlin/id/nearyou/app/admin/AdminModule.kt` for the two-step cleanup checklist Admin #3 must perform (remove the production mount guard + replace the per-request WARN interceptor with the CSRF + session check).
## Requirements
### Requirement: Admin routes mount under `/admin/*` namespace

The system SHALL expose an admin-panel route subtree under the `/admin/*` URL namespace on the `:backend:ktor` deployable. The subtree SHALL be wired via an `Application.admin()` Kotlin extension function called from `Application.module()`, mirroring the shape of `Application.module()` so future extraction into a separate Cloud Run service for `admin.nearyou.id` (per [`docs/07-Operations.md`](../../../../../docs/07-Operations.md) § Stack) is mechanical. The subtree SHALL serve a `/admin/` index route (authenticated — see the `admin-login` capability) and the `/admin/login` + `/admin/logout` routes wired by the `admin-login` capability; additional admin routes land in subsequent admin changes. The mount runs in every environment including production — the production-safety posture is now provided by the auth gate from `admin-login` (Argon2id + TOTP + opaque-token session + CSRF), not by the structural mount guard that was REMOVED below.

#### Scenario: Index route returns 200 with rendered template content (authenticated)

- **GIVEN** an authenticated admin session (per the `admin-login` capability)
- **WHEN** the client sends `GET /admin/` to the `:backend:ktor` deployable carrying the valid `__Host-admin_session` cookie
- **THEN** the response status SHALL be 200
- **AND** the response body SHALL contain text identifying the page as the admin index (e.g., a "Admin Panel" heading or equivalent identifier — exact content authored at implementation time)
- **AND** the response body SHALL be rendered HTML produced by the templating engine chosen in `admin-panel-ktor-htmx-bootstrap` `design.md` Decision 1 (Pebble — not a raw string written via `respondText`)

#### Scenario: Index route without authenticated session redirects to /admin/login

- **WHEN** a client sends `GET /admin/` with no `__Host-admin_session` cookie (or with an invalid / expired / revoked / idle session)
- **THEN** the response status SHALL be 302
- **AND** the `Location` header SHALL be `/admin/login`
- **AND** no response body content for the admin index page SHALL be served

#### Scenario: Unmapped admin routes return 404 regardless of auth state

- **WHEN** a client sends `GET /admin/nonexistent-page` to the `:backend:ktor` deployable (with or without a valid session)
- **THEN** the response status SHALL be 404
- **AND** the route subtree SHALL be present (this 404 is a path-not-found result, not a "namespace doesn't exist" result that would surface as the API's 404 handler). The 404 is returned at the routing layer regardless of authentication state; the auth middleware does not gate path-not-found results.

#### Scenario: Non-GET methods on the admin index return 405

- **WHEN** a client sends `POST /admin/` (or any non-GET method on the bare index path) to the `:backend:ktor` deployable
- **THEN** the response status SHALL be 405 Method Not Allowed
- **AND** the route exists but only GET is wired on `/admin/` itself (the `/admin/login` + `/admin/logout` paths handle POST; subsequent admin changes will wire POST/PUT/DELETE handlers on their own paths as auth-gated business actions land)

### Requirement: Shared base layout template exists and is extended by admin pages

The system SHALL provide a base layout template under `src/main/resources/templates/admin/` in the Pebble templating engine's format that admin pages extend. The layout SHALL include three structural sections: a header, a navigation stub (placeholder links — no functional pages behind them until subsequent admin changes ship), and a footer. The `/admin/` index page and the `/admin/login` page SHALL both extend this layout rather than inlining the layout markup. The layout SHALL conditionally render a `<meta name="csrf-token" content="${csrfToken}">` tag in the `<head>` plus an inline `<script>` block implementing the `htmx:configRequest` CSRF header injection (per the `admin-login` capability) — these conditional sections SHALL render ONLY when the rendering context provides a CSRF token (i.e., authenticated pages); unauthenticated pages (including `/admin/login`) SHALL NOT render either.

#### Scenario: Authenticated page extends the base layout and renders all structural sections

- **GIVEN** an authenticated session
- **WHEN** `GET /admin/` is served
- **THEN** the rendered HTML SHALL contain the header, navigation stub, and footer markup defined by the base layout template
- **AND** the rendered HTML SHALL contain the index-page-specific content block (the "Admin Panel" body)
- **AND** template rendering SHALL complete without throwing a template-engine exception (asserted indirectly by the 200 status on the request)

#### Scenario: Unauthenticated login page extends the base layout but omits the CSRF block

- **WHEN** an unauthenticated client sends `GET /admin/login`
- **THEN** the rendered HTML SHALL contain the header + navigation stub + footer structural sections from the base layout
- **AND** the rendered HTML SHALL contain the login-form-specific content block
- **AND** the rendered HTML SHALL NOT contain the `<meta name="csrf-token" ...>` tag (no session context)
- **AND** the rendered HTML SHALL NOT contain the `htmx:configRequest` JS hook (no session context)

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

#### Scenario: Path-traversal attempts under the static prefix do not serve out-of-prefix resources

- **WHEN** a client sends a request whose path includes `..` segments that would escape the configured `admin/static` classpath prefix (e.g., `GET /admin/static/../../some-other-resource` raw OR `GET /admin/static/%2E%2E/config` URL-encoded)
- **THEN** the response status SHALL NOT be 2xx (i.e., a successful resource MUST NOT be served). The exact status depends on which layer rejects the request:
  - **Ktor `testApplication` in-process** (local test harness): 400 Bad Request for the encoded form (Ktor's URL parser rejects `%2E%2E` as malformed), 404 Not Found for the raw form (HTTP client normalizes `..` segments before sending).
  - **Production stack** (Cloudflare → Google Frontend → Cloud Run → Ktor): 302 redirect for the encoded form (Google Frontend normalizes `%2E%2E` upstream of Ktor, issues a 301/302 to the normalized URL, which then 404s at the destination), 404 for the raw form. Spec amended during pre-archive staging smoke verification when the encoded request returned 302+404 on staging while the local test returned 400. Both outcomes satisfy the security property: the redirect target `/admin/config` itself returns 404 (no resource served outside the prefix), and the test SHALL assert `status.value !in 200..299` to accept any non-success outcome consistently across both environments.
- **AND** no classpath resource outside the `admin/static` prefix SHALL be served (Ktor's `staticResources` handler uses classpath `getResource` lookups that do not resolve relative path segments, AND Ktor's URL parser rejects encoded-traversal attempts upstream of the static handler when the request reaches Ktor in-process)

