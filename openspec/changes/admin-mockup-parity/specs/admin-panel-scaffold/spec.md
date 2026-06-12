# Delta: admin-panel-scaffold (admin-mockup-parity)

## MODIFIED Requirements

### Requirement: Admin routes mount under `/admin/*` namespace

The system SHALL expose an admin-panel route subtree under the `/admin/*` URL namespace on the `:backend:ktor` deployable. The subtree SHALL be wired via an `Application.admin()` Kotlin extension function called from `Application.module()`, mirroring the shape of `Application.module()` so future extraction into a separate Cloud Run service for `admin.nearyou.id` (per [`docs/07-Operations.md`](../../../../../docs/07-Operations.md) § Stack) is mechanical. The subtree SHALL serve a `/admin/` index route (authenticated — see the `admin-login` capability) and the `/admin/login` + `/admin/logout` routes wired by the `admin-login` capability; additional admin routes land in subsequent admin changes. The bare path `/admin` (no trailing slash) SHALL redirect to `/admin/` so the panel is reachable without the operator remembering the trailing slash; the redirect handler itself is unauthenticated — the session gate is applied by the `/admin/` index route it redirects to. The mount runs in every environment including production — the production-safety posture is now provided by the auth gate from `admin-login` (Argon2id + TOTP + opaque-token session + CSRF), not by the structural mount guard that was REMOVED in the `admin-login` change.

#### Scenario: Index route returns 200 with rendered template content (authenticated)

- **GIVEN** an authenticated admin session (per the `admin-login` capability)
- **WHEN** the client sends `GET /admin/` to the `:backend:ktor` deployable carrying the valid `__Host-admin_session` cookie
- **THEN** the response status SHALL be 200
- **AND** the response body SHALL contain text identifying the page as the admin index (e.g., a "Admin Panel" heading or equivalent identifier — exact content authored at implementation time)
- **AND** the response body SHALL be rendered HTML produced by the templating engine chosen in `admin-panel-ktor-htmx-bootstrap` `design.md` Decision 1 (Pebble — not a raw string written via `respondText`)

#### Scenario: Bare /admin path redirects to /admin/

- **WHEN** a client sends `GET /admin` (no trailing slash) to the `:backend:ktor` deployable, with or without a valid session
- **THEN** the response status SHALL be 302
- **AND** the `Location` header SHALL be `/admin/`
- **AND** no index-page content SHALL be served on this response (the session gate runs on the redirect target: an unauthenticated client then receives the existing `/admin/` → `/admin/login` redirect; an authenticated client receives the index)

#### Scenario: Index route without authenticated session redirects to /admin/login

- **WHEN** a client sends `GET /admin/` with no `__Host-admin_session` cookie (or with an invalid / expired / revoked / idle session)
- **THEN** the response status SHALL be 302
- **AND** the `Location` header SHALL be `/admin/login`
- **AND** no response body content for the admin index page SHALL be served

#### Scenario: Unmapped admin routes return 404 regardless of auth state

- **WHEN** a client sends `GET /admin/nonexistent-page` to the `:backend:ktor` deployable (with or without a valid session)
- **THEN** the response status SHALL be 404
- **AND** the route subtree SHALL be present (this 404 is a path-not-found result, not a "namespace doesn't exist" result that would surface as the API's 404 handler). The 404 is returned at the routing layer regardless of authentication state; the auth middleware does not gate path-not-found results. The bare `/admin` path is NOT an unmapped route — it is the explicit redirect wired by this requirement's "Bare /admin path redirects to /admin/" scenario.

#### Scenario: Non-GET methods on the admin index return 405

- **WHEN** a client sends `POST /admin/` (or any non-GET method on the bare index path) to the `:backend:ktor` deployable
- **THEN** the response status SHALL be 405 Method Not Allowed
- **AND** `POST /admin` (the bare path, no trailing slash) SHALL return 404 — the redirect is registered for GET only, and Ktor resolves a non-GET on that bare node as path-not-found (verified at implementation time), preserving the unmapped-route contract for every non-GET method on the bare path
- **AND** the route exists but only GET is wired on `/admin/` itself (the `/admin/login` + `/admin/logout` paths handle POST; subsequent admin changes will wire POST/PUT/DELETE handlers on their own paths as auth-gated business actions land)

### Requirement: Shared base layout template exists and is extended by admin pages

The system SHALL provide a base layout template under `src/main/resources/templates/admin/` in the Pebble templating engine's format that admin pages extend. The layout SHALL render the app shell per the admin mockup board frame 2 (`dev/mockups/nearyou-admin-mockup.html` `#f02`, per [`docs/11-Engineering-Standards.md`](../../../../../docs/11-Engineering-Standards.md) § 3.6):

- a **grouped sidebar** containing the brand logo and the shipped pages only, each nav item with a leading vendored inline-SVG icon (every icon `<svg>` carries a `data-icon="<name>"` attribute identifying the glyph, so tests can assert icon identity): group "Moderasi" → Dashboard (`/admin/`), Reports (`/admin/reports`), Users (`/admin/users`); group "Anti-abuse & keamanan" → Rejected identifiers (`/admin/rejected-identifiers`); group "Sistem" → Audit log (`/admin/actions-log`). Unshipped ("Usulan") mockup menu items SHALL NOT be rendered (no dead links, no disabled placeholders), and the mockup's per-item status dots SHALL NOT be rendered (they are mockup-board annotations, not product UI; the Dashboard item is rendered despite its hollow board dot — that annotation marks the unbuilt frame-3 Operational Dashboard content, not the shipped `/admin/` landing the item links to). The nav item whose path matches the current page SHALL carry an active-state style.
- a **sidebar footer identity box** on authenticated pages showing the authenticated admin's role as an uppercase role chip, the admin's display name, a session line of the form `Session idle {idle-timeout} · expires {HH:mm} UTC` where the displayed expiry is the sooner of (last-activity + idle-timeout) and the session's absolute expiry, and the Logout control (existing POST + CSRF semantics unchanged).
- a **top bar** showing the current page title and an environment chip with the uppercased deployment environment name (e.g. `STAGING`), sourced from the existing deployment-environment configuration (tests assert the test-config value).

The previous layout's page footer SHALL NOT be rendered — mockup frame 2 has none (deliberate removal, recorded in the proposal).

The `/admin/` index page and the `/admin/login` page SHALL both extend this layout rather than inlining the layout markup. The layout SHALL conditionally render a `<meta name="csrf-token" content="${csrfToken}">` tag in the `<head>` plus an inline `<script>` block implementing the `htmx:configRequest` CSRF header injection (per the `admin-login` capability) — these conditional sections SHALL render ONLY when the rendering context provides a CSRF token (i.e., authenticated pages); unauthenticated pages (including `/admin/login`) SHALL NOT render either. Session-derived sections (sidebar nav, identity box) SHALL likewise render only on authenticated pages; the login page renders the shell-less centered layout per mockup frame 1.

#### Scenario: Authenticated page extends the base layout and renders all structural sections

- **GIVEN** an authenticated session
- **WHEN** `GET /admin/` is served
- **THEN** the rendered HTML SHALL contain the grouped sidebar with exactly the five shipped nav items (Dashboard, Reports, Users, Rejected identifiers, Audit log) under their group headings, each with an inline-SVG icon identified by its `data-icon` attribute (`dashboard`, `flag`, `group`, `block`, `receipt_long`)
- **AND** the rendered HTML SHALL NOT contain nav items for unshipped pages (e.g. no "Post edit history", "Attestation review", "Feature flags")
- **AND** the rendered HTML SHALL contain the identity box with the admin's role chip, display name, and a `Session idle … · expires … UTC` line
- **AND** the rendered HTML SHALL contain the top bar with the page title and the environment chip
- **AND** the rendered HTML SHALL contain the index-page-specific content block
- **AND** template rendering SHALL complete without throwing a template-engine exception (asserted indirectly by the 200 status on the request)

#### Scenario: Session expiry display shows the absolute cap when it is the sooner bound

- **GIVEN** an authenticated session whose absolute expiry (`expiresAt`) is sooner than (last-activity + idle-timeout) — e.g. a session within 30 minutes of its 8 h cap
- **WHEN** `GET /admin/` is served
- **THEN** the identity box session line SHALL render the expiry as the session's absolute expiry, formatted `HH:mm` UTC
- **AND** for a fresh session (idle deadline sooner than the cap) the same line renders the idle deadline instead

#### Scenario: Active nav item reflects the current page

- **GIVEN** an authenticated session
- **WHEN** `GET /admin/reports` is served
- **THEN** the rendered HTML SHALL mark the Reports nav item with the active-state class
- **AND** the top bar page title SHALL identify the Reports page

#### Scenario: Unauthenticated login page extends the base layout but omits the CSRF block

- **WHEN** an unauthenticated client sends `GET /admin/login`
- **THEN** the rendered HTML SHALL contain the login-form-specific content block in the centered shell-less layout (no sidebar, no identity box, no top bar environment chip)
- **AND** the rendered HTML SHALL NOT contain the `<meta name="csrf-token" ...>` tag (no session context)
- **AND** the rendered HTML SHALL NOT contain the `htmx:configRequest` JS hook (no session context)

## ADDED Requirements

### Requirement: Scaffold landing renders greeting and live stat cards

The `/admin/` index page SHALL render, per the admin mockup board frame 2: a greeting heading `Welcome back, {display name}` using the authenticated admin's display name; exactly three quick-link stat cards populated with live values queried at request time — **Report queue** (count of pending reports + relative age of the oldest pending report, linking to `/admin/reports`), **Rejected identifiers** (count of rows created in the last 24 hours + the most frequent rejection reason over that window, ties broken deterministically, linking to `/admin/rejected-identifiers`), **Audit log** (count of `admin_actions_log` rows for the current UTC day + the `action_type` of the newest row, linking to `/admin/actions-log`); and an informational banner describing the shell's CSRF posture. The previous static description cards — including the "User moderation" card, which frame 2 drops from the landing — SHALL NOT be rendered. All time arithmetic SHALL use UTC. The last-action slot shows the newest row **all-time** (the operator wants to know the last thing that happened, even if it was yesterday); its placeholder renders only when the log is empty. Empty-state values (no pending reports, no rejections in 24 h, empty audit log) SHALL render as zero counts with a placeholder (e.g. `—`) for the age/reason/last-action slot, not error or omit the card.

#### Scenario: Stat cards show live values

- **GIVEN** an authenticated session
- **AND** the database contains 4 pending reports (oldest created 2 hours ago), 12 `rejected_identifiers` rows in the last 24 hours of which `age_under_18` is the most frequent reason, and 9 `admin_actions_log` rows today (UTC) with the newest having `action_type = 'user_suspended'`
- **WHEN** `GET /admin/` is served
- **THEN** the Report queue card SHALL show a pending count of 4 and an oldest-pending age derived from the 2-hour-old row
- **AND** the Rejected identifiers card SHALL show 12 and `age_under_18`
- **AND** the Audit log card SHALL show 9 and `user_suspended`

#### Scenario: Top-reason tie breaks deterministically

- **GIVEN** an authenticated session
- **AND** the last 24 hours contain an equal count of `rejected_identifiers` rows for two reasons (e.g. `age_under_18` and `duplicate_identifier`)
- **WHEN** `GET /admin/` is served
- **THEN** the Rejected identifiers card SHALL show the alphabetically-first of the tied reasons (per the deterministic `ORDER BY count DESC, reason ASC` tie-break)

#### Scenario: Empty database renders zero-state cards

- **GIVEN** an authenticated session against a database with no pending reports, no `rejected_identifiers` rows in the last 24 hours, and no `admin_actions_log` rows today
- **WHEN** `GET /admin/` is served
- **THEN** the response status SHALL be 200
- **AND** each of the three stat cards SHALL render with a zero count and a placeholder value in its secondary slot

#### Scenario: Audit card with only-yesterday rows shows zero count but the real last action

- **GIVEN** an authenticated session against a database whose `admin_actions_log` rows are all older than the current UTC day
- **WHEN** `GET /admin/` is served
- **THEN** the Audit log card SHALL show an actions-today count of 0
- **AND** the last-action slot SHALL show the newest row's `action_type` (not the placeholder)

#### Scenario: Landing drops the static User moderation card

- **GIVEN** an authenticated session
- **WHEN** `GET /admin/` is served
- **THEN** the rendered HTML SHALL NOT contain a "User moderation" quick-link card (the Users page remains reachable from the sidebar)
