# Delta: admin-login (admin-mockup-parity)

## MODIFIED Requirements

### Requirement: Login form GET renders the unauthenticated login page

The system SHALL serve `GET /admin/login` as an unauthenticated route returning HTTP 200 with an HTML login form. The form SHALL contain three input fields — `email`, `password`, and `totp` (the 6-digit TOTP code) — and a submit button. The form's `action` attribute SHALL be `/admin/login` with `method="POST"`. The page SHALL extend the shared admin base layout (per the `admin-panel-scaffold` capability). The login page MUST NOT render the CSRF meta tag or the HTMX CSRF configRequest JS hook (no session exists yet).

The page's presentation SHALL follow the admin mockup board frame 1 (`dev/mockups/nearyou-admin-mockup.html` `#f01`, per [`docs/11-Engineering-Standards.md`](../../../../../docs/11-Engineering-Standards.md) § 3.6): a centered card with the brand logo only — no "Admin login" heading text; a leading vendored inline-SVG icon on each of the three inputs (mail for email, key for password, timer for TOTP); a placeholder hint on each of the three inputs (an example address for email, a password hint for password, matching the existing `6-digit code` TOTP hint — operator request 2026-06-12); a trailing show/hide visibility toggle on the password input implemented as a `<button type="button">` flipping the input's `type` between `password` and `text` via minimal inline vanilla JS (no HTMX dependency; without JS the input simply stays masked); and, when the generic error message is rendered, a leading icon inside the error banner. The error banner SHALL remain a single generic slot whose rendered bytes are identical on every failure path — the presentation change MUST NOT introduce any per-failure-path markup variation (the no-enumeration requirement of this capability is unchanged and continues to gate this).

#### Scenario: Login page renders with all three input fields

- **WHEN** an unauthenticated client sends `GET /admin/login`
- **THEN** the response status SHALL be 200
- **AND** the response body SHALL contain `<input ... name="email" ...>`, `<input ... name="password" ...>`, AND `<input ... name="totp" ...>` (attribute order tolerant; the spec asserts presence of all three name attributes)
- **AND** the response body SHALL contain a `<form ... action="/admin/login" method="POST" ...>` tag (attribute order tolerant)

#### Scenario: Login page presentation matches mockup frame 1

- **WHEN** an unauthenticated client sends `GET /admin/login`
- **THEN** the response body SHALL NOT contain an "Admin login" heading text
- **AND** each of the three inputs SHALL be accompanied by its leading inline-SVG icon, identified by `data-icon` attribute (`mail` / `key` / `timer`)
- **AND** each of the three inputs SHALL carry a `placeholder` hint (email example address / password hint / `6-digit code`)
- **AND** the password field SHALL include a visibility-toggle `<button type="button">` with an inline-SVG icon (`data-icon="visibility"`)
- **AND** the page SHALL contain the inline script wiring the toggle (flipping `type="password"` ↔ `type="text"`)

#### Scenario: Error banner renders with icon and identical bytes on every failure path

- **WHEN** a login POST fails (any failure path: wrong password, wrong TOTP, unknown email, inactive admin)
- **THEN** the re-rendered login page SHALL contain the generic error banner with its leading inline-SVG icon (`data-icon="error"`)
- **AND** the error banner markup SHALL be byte-identical across all failure paths — this scenario complements (does not replace) the existing "All failure paths return identical body, status, and headers" scenario under the no-enumeration requirement, which remains the owning byte-equality test

#### Scenario: Login page does not include CSRF meta tag

- **WHEN** an unauthenticated client sends `GET /admin/login`
- **THEN** the response body SHALL NOT contain a `<meta name="csrf-token" ...>` tag (no session ⇒ no CSRF token to surface)

#### Scenario: Authenticated client GETting /admin/login is redirected to /admin/

- **GIVEN** an authenticated session exists for the requesting client
- **WHEN** the client sends `GET /admin/login` carrying the valid `__Host-admin_session` cookie
- **THEN** the response status SHALL be 302
- **AND** the `Location` header SHALL be `/admin/`
