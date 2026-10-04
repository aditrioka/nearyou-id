## MODIFIED Requirements

### Requirement: Shared base layout template exists and is extended by admin pages

The system SHALL provide a base layout template under `src/main/resources/templates/admin/` in the Pebble templating engine's format that admin pages extend. The layout SHALL render the app shell per the admin mockup board frame 2 (`dev/mockups/nearyou-admin-mockup.html` `#f02`, per [`docs/11-Engineering-Standards.md`](../../../../../docs/11-Engineering-Standards.md) § 3.6):

- a **grouped sidebar** containing the brand logo and the shipped pages only, each nav item with a leading vendored inline-SVG icon (every icon `<svg>` carries a `data-icon="<name>"` attribute identifying the glyph, so tests can assert icon identity): the sidebar SHALL render exactly sixteen nav items under six group headings — group "Moderasi" → Dashboard (`/admin/`, icon `dashboard`), Reports (`/admin/reports`, `flag`), Appeals (`/admin/appeals`, `article`), Users (`/admin/users`, `group`); group "Anti-abuse & keamanan" → Rejected identifiers (`/admin/rejected-identifiers`, `block`), Block registry (`/admin/blocks`, `group`), CSAM detection log (`/admin/csam`, `gpp_maybe`); group "Lifecycle" → Privacy flips (`/admin/privacy-flips`, `timer`), Hard delete queue (`/admin/deletion-requests`, `auto_delete`), Data export queue (`/admin/data-exports`, `download`); group "Premium" → Subscription grace (`/admin/subscriptions/grace`, `credit_card`), Referral grants (`/admin/referral-grants`, `redeem`); group "Konfigurasi" → Feature flags (`/admin/feature-flags`, `toggle_on`), Reserved usernames (`/admin/reserved-usernames`, `badge`), Username oversight (`/admin/username-oversight`, `alternate_email`); group "Sistem" → Audit log (`/admin/actions-log`, `receipt_long`). A change that ships (or removes) an admin page's nav item SHALL carry a MODIFIED delta of this requirement updating this list. Unshipped ("Usulan") mockup menu items SHALL NOT be rendered (no dead links, no disabled placeholders), and the mockup's per-item status dots SHALL NOT be rendered (they are mockup-board annotations, not product UI). The nav item whose path matches the current page SHALL carry an active-state style.
- a **sidebar footer identity box** on authenticated pages showing the authenticated admin's role as an uppercase role chip, the admin's display name, a session line of the form `Session idle {idle-timeout} · expires {HH:mm} UTC` where the displayed expiry is the sooner of (last-activity + idle-timeout) and the session's absolute expiry, and the Logout control (existing POST + CSRF semantics unchanged).
- a **top bar** showing the current page title and an environment chip with the uppercased deployment environment name (e.g. `STAGING`), sourced from the existing deployment-environment configuration (tests assert the test-config value).

The previous layout's page footer SHALL NOT be rendered — mockup frame 2 has none (deliberate removal, recorded in the proposal).

The `/admin/` index page and the `/admin/login` page SHALL both extend this layout rather than inlining the layout markup. The layout SHALL conditionally render a `<meta name="csrf-token" content="${csrfToken}">` tag in the `<head>` plus an inline `<script>` block implementing the `htmx:configRequest` CSRF header injection (per the `admin-login` capability) — these conditional sections SHALL render ONLY when the rendering context provides a CSRF token (i.e., authenticated pages); unauthenticated pages (including `/admin/login`) SHALL NOT render either. Session-derived sections (sidebar nav, identity box) SHALL likewise render only on authenticated pages; the login page renders the shell-less centered layout per mockup frame 1.

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
