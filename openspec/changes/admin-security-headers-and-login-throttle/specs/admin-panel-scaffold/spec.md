## ADDED Requirements

### Requirement: Every response carries the admin security response headers

The system SHALL add the following headers to every HTTP response the `:backend:ktor` deployable serves:

- `Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'`
- `X-Frame-Options: DENY`
- `X-Content-Type-Options: nosniff`
- `Referrer-Policy: same-origin`
- `Strict-Transport-Security: max-age=31536000`

The set exists for the admin panel. It is applied host-wide rather than keyed on an `/admin` path prefix, because the router decodes and collapses path segments: `//admin/users` and `/%61dmin/login` reach the admin handlers while a raw-path prefix check would miss them. The headers are inert on the JSON API, which the backend serves without any other HTML surface, and `nosniff` + HSTS also benefit it.

The headers SHALL be attached before any other plugin can reject the call, so every response carries them. That includes:
- full pages, HTMX fragments and the vendored static assets under `/admin/static/`;
- redirects, including the unauthenticated 302 to `/admin/login`;
- error, throttle and routing-layer 404/405 responses;
- early transport rejections such as the request-body-limit 413.

The panel SHALL work under this policy with no `'unsafe-inline'`, nonce, hash or `'unsafe-eval'` source:
- no admin template contains an inline `<script>` block (a `<script>` without a `src` attribute) or an inline style attribute (the existing #525/#585 guard);
- no admin template contains an inline event-handler attribute (`on<event>=`) or an eval-dependent htmx feature: `hx-on`, `hx-vars`, a `js:` / `javascript:` value, or an `hx-trigger` event filter (`[...]`);
- all panel behaviour lives in the vendored same-origin static files;
- `admin.js` is pinned in `admin/static/htmx.min.js.SHA256SUMS` beside the other vendored assets.

#### Scenario: Login page carries the security header set

- **WHEN** an unauthenticated client sends `GET /admin/login`
- **THEN** the response SHALL carry each of the five headers with exactly the values listed above

#### Scenario: Authenticated page and HTMX fragment carry the header set

- **GIVEN** an authenticated session
- **WHEN** the client sends `GET /admin/` and an HTMX fragment request (`HX-Request: true`) to an admin list route
- **THEN** both responses SHALL carry the five headers with the values listed above

#### Scenario: Redirects, static assets and unmapped admin paths carry the header set

- **WHEN** a client without a session sends `GET /admin/users` (answered `302` to `/admin/login`), `GET /admin` (answered `302` to `/admin/`), `GET /admin/static/admin.js` (answered `200`) and `GET /admin/nonexistent-page` (answered `404`)
- **THEN** each response SHALL carry the five headers with the values listed above

#### Scenario: Router-normalised admin paths carry the header set

- **WHEN** a client sends `GET //admin/users` (answered `302` to `/admin/login`) and `GET /%61dmin/login` (answered `200` with the login form)
- **THEN** both responses SHALL carry the five headers with the values listed above

#### Scenario: An early transport rejection carries the header set

- **WHEN** a client POSTs a body over the request-body limit to an admin path (answered `413` by the request-body limit before routing)
- **THEN** the `413` response SHALL carry the five headers with the values listed above

#### Scenario: Non-admin paths carry the same header set

- **WHEN** a client sends a request outside the `/admin` prefix (e.g. `GET /administrator` or an `/api/v1/*` path)
- **THEN** the response SHALL carry the same five headers (the set is host-wide)

#### Scenario: No admin template carries an inline script, inline handler or eval-dependent htmx feature

- **WHEN** the test suite scans every `templates/admin/*.peb` source file
- **THEN** no file SHALL contain a `<script` tag without a `src=` attribute
- **AND** no file SHALL contain an inline event-handler attribute (whitespace followed by `on<letters>=`), `hx-on`, `hx-vars`, a `js:` or `javascript:` value, or an `hx-trigger` value containing `[`

#### Scenario: The vendored panel script is served and pinned

- **WHEN** a client sends `GET /admin/static/admin.js`
- **THEN** the response status SHALL be 200 with `Content-Type` `application/javascript` or `text/javascript`
- **AND** `admin/static/htmx.min.js.SHA256SUMS` SHALL list `admin.js`, with the SHA-256 of the served bytes

## MODIFIED Requirements

### Requirement: Shared base layout template exists and is extended by admin pages

The system SHALL provide a base layout template under `src/main/resources/templates/admin/` in the Pebble templating engine's format that admin pages extend. The layout SHALL render the app shell per the admin mockup board frame 2 (`dev/mockups/nearyou-admin-mockup.html` `#f02`, per [`docs/11-Engineering-Standards.md`](../../../../../docs/11-Engineering-Standards.md) § 3.6):

- a **grouped sidebar** containing the brand logo and the shipped pages only, each nav item with a leading vendored inline-SVG icon (every icon `<svg>` carries a `data-icon="<name>"` attribute identifying the glyph, so tests can assert icon identity): the sidebar SHALL render exactly sixteen nav items under six group headings — group "Moderasi" → Dashboard (`/admin/`, icon `dashboard`), Reports (`/admin/reports`, `flag`), Appeals (`/admin/appeals`, `article`), Users (`/admin/users`, `group`); group "Anti-abuse & keamanan" → Rejected identifiers (`/admin/rejected-identifiers`, `block`), Block registry (`/admin/blocks`, `group`), CSAM detection log (`/admin/csam`, `gpp_maybe`); group "Lifecycle" → Privacy flips (`/admin/privacy-flips`, `timer`), Hard delete queue (`/admin/deletion-requests`, `auto_delete`), Data export queue (`/admin/data-exports`, `download`); group "Premium" → Subscription grace (`/admin/subscriptions/grace`, `credit_card`), Referral grants (`/admin/referral-grants`, `redeem`); group "Konfigurasi" → Feature flags (`/admin/feature-flags`, `toggle_on`), Reserved usernames (`/admin/reserved-usernames`, `badge`), Username oversight (`/admin/username-oversight`, `alternate_email`); group "Sistem" → Audit log (`/admin/actions-log`, `receipt_long`). A change that ships (or removes) an admin page's nav item SHALL carry a MODIFIED delta of this requirement updating this list. Unshipped ("Usulan") mockup menu items SHALL NOT be rendered (no dead links, no disabled placeholders), and the mockup's per-item status dots SHALL NOT be rendered (they are mockup-board annotations, not product UI). The nav item whose path matches the current page SHALL carry an active-state style.
- a **sidebar footer identity box** on authenticated pages showing the authenticated admin's role as an uppercase role chip, the admin's display name, a session line of the form `Session idle {idle-timeout} · expires {HH:mm} UTC` where the displayed expiry is the sooner of (last-activity + idle-timeout) and the session's absolute expiry, and the Logout control (existing POST + CSRF semantics unchanged).
- a **top bar** showing the current page title and an environment chip with the uppercased deployment environment name (e.g. `STAGING`), sourced from the existing deployment-environment configuration (tests assert the test-config value).

The previous layout's page footer SHALL NOT be rendered — mockup frame 2 has none (deliberate removal, recorded in the proposal).

The `/admin/` index page and the `/admin/login` page SHALL both extend this layout rather than inlining the layout markup.

On every page, the layout SHALL load the vendored panel script `/admin/static/admin.js` (after the vendored HTMX script) and a `<meta name="htmx-config">` tag carrying four settings:
- it turns off htmx's injected indicator `<style>` element (`includeIndicatorStyles: false`);
- it turns off htmx's eval-dependent features (`allowEval: false`);
- it stops htmx re-executing scripts found in swapped content (`allowScriptTags: false`);
- it stops htmx keeping page snapshots in `localStorage`, where admin data would outlive logout (`historyCacheSize: 0`, with `refreshOnHistoryMiss: true`, so back/forward navigation performs a full page load).

The layout SHALL contain no inline `<script>` block, since the panel runs under CSP `script-src 'self'` (see "Every response carries the admin security response headers").

The layout SHALL conditionally render a `<meta name="csrf-token" content="${csrfToken}">` tag in the `<head>`. The `htmx:configRequest` CSRF header injection that reads it lives in `admin.js`, per the `admin-login` capability. This conditional section SHALL render ONLY when the rendering context provides a CSRF token (i.e., authenticated pages); unauthenticated pages (including `/admin/login`) SHALL NOT render it.

Session-derived sections (sidebar nav, identity box) SHALL likewise render only on authenticated pages; the login page renders the shell-less centered layout per mockup frame 1.

#### Scenario: Authenticated page extends the base layout and renders all structural sections

- **GIVEN** an authenticated session
- **WHEN** `GET /admin/` is served
- **THEN** the rendered HTML SHALL contain the grouped sidebar with exactly the sixteen shipped nav items under their six group headings (Moderasi, Anti-abuse & keamanan, Lifecycle, Premium, Konfigurasi, Sistem), each with an inline-SVG icon identified by its `data-icon` attribute per the list above
- **AND** the rendered HTML SHALL NOT contain nav items for unshipped pages (e.g. no "Post edit history", "Attestation review") and no board-annotation status dots
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

#### Scenario: Every admin page loads the vendored panel script and the htmx CSP config

- **WHEN** `GET /admin/login` (unauthenticated) and `GET /admin/` (authenticated) are served
- **THEN** each rendered page SHALL contain a `<script src="/admin/static/admin.js" ...>` tag after the `/admin/static/htmx.min.js` script tag
- **AND** each SHALL contain `<meta name="htmx-config" ...>` whose JSON content sets `includeIndicatorStyles`, `allowEval` and `allowScriptTags` to `false`, `historyCacheSize` to `0` and `refreshOnHistoryMiss` to `true`
- **AND** neither SHALL contain an inline `<script>` block (a `<script` tag without `src=`)
