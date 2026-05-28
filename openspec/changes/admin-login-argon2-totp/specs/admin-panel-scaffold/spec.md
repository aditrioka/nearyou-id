## MODIFIED Requirements

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

## REMOVED Requirements

### Requirement: Admin subtree does NOT require authentication in this change

**Reason**: Superseded by the `admin-login` capability's authentication gate. The `/admin/*` subtree (except `/admin/login` and `/admin/static/*`) is now gated by the session-validation middleware that wraps every authenticated route, redirecting unauthenticated requests to `/admin/login` (302). The per-request WARN-level log line carrying the `admin-login-argon2-totp` search marker is REMOVED from `Application.admin()`; its absence is positively observable as the gate-landed signal in Sentry.

**Migration**: No external action required. The unauthenticated-scaffold posture was structurally bounded to non-production environments by the now-also-removed `KTOR_ENV` mount guard, so no production behavior change is involved. Tests that previously asserted "GET /admin/ returns 200 with no auth" or "every /admin/* request emits a WARN log line containing the change-name substring" SHALL be removed by this change in favor of the auth-gated scenarios defined by the MODIFIED Requirement "Admin routes mount under `/admin/*` namespace" above + the new `admin-login` capability's scenarios.

### Requirement: Production mount guard via `KTOR_ENV` check

**Reason**: Superseded by the `admin-login` capability's authentication gate. The mount guard was platform-level defense-in-depth needed only because the underlying subtree was unauthenticated. Now that the Argon2id password + TOTP + opaque-token session + CSRF gate makes the subtree production-safe, the structural mount guard adds no marginal security and would block legitimate production deploys.

**Migration**: The `if (ktorEnv == "production") return` short-circuit at the top of `Application.admin()` SHALL be removed by this change, along with the bootstrap WARN log line that fired on the guarded path. Tests that previously asserted "GET /admin/ returns 404 in production with KTOR_ENV mock" or "bootstrap WARN log fires with `admin module skipped` substring" SHALL be removed in favor of the auth-gated scenarios. Production exposure remains bounded operationally by the Phase 3.5 separate-Cloud-Run-service deployment task (which moves `/admin/*` to `admin.nearyou.id` with IAP / Cloud Armor at Layer 1), tracked separately from this change.
