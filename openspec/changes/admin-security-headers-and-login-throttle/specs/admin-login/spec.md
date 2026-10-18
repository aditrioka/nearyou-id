## ADDED Requirements

### Requirement: Login POST is throttled per client IP and per submitted email

The system SHALL throttle `POST /admin/login` through the shared `RateLimiter.tryAcquireByKey` sliding-window primitive (the `rate-limit-infrastructure` capability). The throttle runs after the form is parsed and the oversized-field 400 guard has run, and BEFORE any `admin_users` lookup, Argon2id verify, TOTP verify, session insert or audit-row write. It applies three tiers, in this order:

1. **Per client IP**: at most 10 attempts per rolling 15 minutes, keyed `{scope:rate_admin_login}:{ip:<hashed-ip>}`, where `<hashed-ip>` is the `IpHasher` hash of `call.clientIp` (never the raw IP).
2. **Per submitted email (burst)**: at most 5 attempts per rolling 15 minutes, keyed `{scope:rate_admin_login}:{email:<email-hash>}`, where `<email-hash>` is the SHA-256 base64url hash of the exact submitted `email` field (the same `email_hash` the email-not-found log line carries; no case folding, since the admin lookup is an exact match).
3. **Per submitted email (sustained lockout)**: at most 20 attempts per rolling 24 hours, keyed `{scope:rate_admin_login_lockout}:{email:<email-hash>}`.

The first tier that is exhausted SHALL answer, and later tiers SHALL NOT be consulted for that attempt. So a throttled attempt has used one slot in each earlier tier and none in any later tier. Only failed attempts SHALL count: when the credential checks succeed and a session is created, the attempt SHALL hand back the slot it took in each of the three tiers. That way the operator's own logins and the staging smoke scripts never consume throttle budget, and the counters say nothing about legitimate activity on an address. None of the keys is a daily-quota key (no `_day` scope suffix): they are rolling windows, not WIB-staggered calendar-day resets.

Independently of the rate tiers, at most 2 login credential checks SHALL run at once per application instance. A credential check is everything from the throttle tiers through the Argon2id and TOTP verifies. The per-IP tier alone cannot bound concurrent Argon2id work while `call.clientIp` trusts a client-supplied `CF-Connecting-IP` (the host is not yet behind Cloudflare). So this gate is what keeps a spoofed-IP spray from exhausting instance memory (19 MiB per verify against a ~128 MiB heap) or CPU. A POST that cannot obtain a permit within 2 seconds SHALL be shed: it is answered as a throttled attempt with `axis=concurrency` and `Retry-After: 60`, before any limiter call, `admin_users` lookup or hash work. A permit SHALL be returned on every outcome (success, credential failure, throttle, error), so the gate never leaks capacity.

A throttled (or shed) attempt SHALL:
- respond `429 Too Many Requests` with a `Retry-After` header carrying the exhausted tier's retry-after in whole seconds;
- re-render the login page with the throttle message `Too many login attempts. Try again in N min.` (N = Retry-After seconds divided by 60, rounded up) in the login page's single error-banner slot;
- emit one structured WARN line at the application logger carrying `event=admin_login_throttled`, `axis` ∈ {`ip`, `email`, `email_lockout`, `concurrency`}, `email_hash`, `ip`, `user_agent` and `retry_after_s`. The client-controlled `ip` and `user_agent` values SHALL be emitted quoted, with `"`, `\`, CR and LF escaped, so a crafted header cannot forge additional fields. The line SHALL NOT carry the plaintext email, the submitted password or the submitted TOTP code;
- write NO `admin_actions_log` row: no admin actor has been identified, and V16's `admin_id NOT NULL` forbids an unowned row, consistent with design D14's email-not-found path;
- create no session and set no cookie, even when the submitted credentials are correct.

Because every tier is evaluated before the account lookup, the throttled response (status, headers and body) SHALL be identical whether or not the submitted email belongs to an admin. Throttling is not a credential failure mode, and the 200 no-enumeration contract for credential failures is unchanged.

The throttle SHALL fail open, consistent with `rate-limit-infrastructure`. When the limiter admits because Redis is unreachable (the `RedisRateLimiter` fail-soft path) or because the environment binds `NoOpRateLimiter`, the login proceeds through the normal credential checks. The admin module SHALL receive its limiter as a required wiring parameter (no default), and production wiring SHALL pass the shared env-aware rate limiter.

#### Scenario: Eleventh attempt from one IP within 15 minutes is throttled

- **GIVEN** 10 login attempts from client IP `203.0.113.7` within the last 15 minutes, each with a different email
- **WHEN** an 11th attempt arrives from `203.0.113.7` with a fresh email
- **THEN** the response status SHALL be 429
- **AND** the response SHALL carry a `Retry-After` header whose value is a positive integer number of seconds no greater than 900
- **AND** the response body SHALL contain the throttle message and the login form

#### Scenario: Sixth attempt for one email within 15 minutes is throttled across IPs

- **GIVEN** 5 login attempts for `email=target@nearyou.id` within the last 15 minutes, each from a different client IP
- **WHEN** a 6th attempt for `target@nearyou.id` arrives from yet another client IP
- **THEN** the response status SHALL be 429 with a `Retry-After` header no greater than 900 seconds

#### Scenario: Sustained attempts on one email trip the 24-hour lockout tier

- **GIVEN** 20 failed attempts for one email over the last 24 hours, spaced so that no 15-minute window holds more than 5
- **WHEN** a 21st attempt for that email arrives in a fresh 15-minute window
- **THEN** the response status SHALL be 429
- **AND** the `Retry-After` value SHALL exceed 900 seconds (the lockout tier answered, not the burst tier)
- **AND** the emitted log line SHALL carry `axis=email_lockout`

#### Scenario: A throttled attempt with correct credentials does not log in

- **GIVEN** an active admin whose email has exhausted its burst tier
- **WHEN** the client POSTs that admin's correct email, password and current TOTP code
- **THEN** the response status SHALL be 429
- **AND** no `Set-Cookie: __Host-admin_session` header SHALL be present
- **AND** no `admin_sessions` row and no `admin_actions_log` row SHALL be inserted for the attempt

#### Scenario: Throttled response is identical for an existing and a phantom email

- **GIVEN** an existing admin email and a phantom email (no `admin_users` row), each with an exhausted burst tier
- **WHEN** one more attempt is POSTed for each
- **THEN** both responses SHALL have status 429
- **AND** both bodies SHALL be byte-identical when the `Retry-After`-derived minute count is equal
- **AND** neither response SHALL carry a header or body marker that distinguishes the two

#### Scenario: Throttled attempt emits the structured WARN line and no audit row

- **GIVEN** the application's log capture (Logback `ListAppender` or equivalent)
- **WHEN** an attempt is throttled on the email burst tier with password `P@ssw0rd!Secret` and TOTP `123456`
- **THEN** a WARN-level line SHALL be captured containing `event=admin_login_throttled`, `axis=email`, `email_hash=`, `ip=`, `user_agent=` and `retry_after_s=`
- **AND** the line SHALL NOT contain the plaintext submitted email, `P@ssw0rd!Secret` or `123456`
- **AND** no `admin_actions_log` row SHALL be inserted for the attempt

#### Scenario: Client-controlled log fields cannot forge extra fields

- **GIVEN** a throttled attempt whose `CF-Connecting-IP` header carries `203.0.113.7 axis=email "x`
- **WHEN** the throttle WARN line is emitted
- **THEN** the line SHALL carry `ip="203.0.113.7 axis=email \"x"` (quoted, inner quote escaped)
- **AND** the line SHALL contain exactly one unquoted `axis=` field: the tier that actually answered

#### Scenario: A successful login hands its slots back

- **GIVEN** an active admin
- **WHEN** the admin logs in successfully 6 times within 15 minutes from one client IP
- **THEN** none of the 6 attempts SHALL be throttled
- **AND** afterwards the IP, email-burst and email-lockout buckets for that IP and email SHALL each be empty

#### Scenario: A credential check beyond the concurrency gate is shed before any other work

- **GIVEN** every credential-check permit is held by in-flight checks for longer than the 2-second wait
- **WHEN** another login POST arrives
- **THEN** the response SHALL be 429 with `Retry-After: 60` and the throttle message
- **AND** the limiter SHALL NOT have been called for this attempt, and the emitted WARN line SHALL carry `axis=concurrency`

#### Scenario: The concurrency gate never leaks a permit

- **WHEN** a sequence of login POSTs ends in a success, a wrong password, an unknown email, an oversized field (400) and a throttle (429)
- **THEN** afterwards every credential-check permit SHALL be available again

#### Scenario: Throttle keys use the hash-tag shape and never the raw IP

- **WHEN** an attempt from client IP `203.0.113.7` with email `target@nearyou.id` passes through all three tiers
- **THEN** the limiter SHALL be called with exactly the keys `{scope:rate_admin_login}:{ip:<IpHasher.hash("203.0.113.7")>}`, `{scope:rate_admin_login}:{email:<sha256-base64url("target@nearyou.id")>}` and `{scope:rate_admin_login_lockout}:{email:<same hash>}`, in that order
- **AND** no key SHALL contain the substring `203.0.113.7` or `target@nearyou.id`

#### Scenario: An IP-throttled attempt consumes no email slot

- **GIVEN** client IP `203.0.113.7` has exhausted its IP tier
- **WHEN** it POSTs an attempt for `target@nearyou.id`
- **THEN** the response status SHALL be 429 and the email tiers SHALL NOT be consulted
- **AND** a subsequent attempt for `target@nearyou.id` from a different, unthrottled IP SHALL still be admitted to the credential checks (its burst tier is untouched)

#### Scenario: Throttle fails open when the limiter admits

- **GIVEN** the admin module is wired with a limiter that always admits (the `NoOpRateLimiter` binding, the same outcome `RedisRateLimiter` returns when Redis is unreachable)
- **WHEN** 12 attempts arrive from one client IP for one email within a minute
- **THEN** no response SHALL have status 429
- **AND** every attempt SHALL receive the normal credential-check outcome (the generic 200 error page for wrong credentials)

#### Scenario: Oversized fields are rejected before the throttle

- **WHEN** a login POST carries an `email` field longer than the oversized-input limit
- **THEN** the response SHALL be the existing 400, and no throttle slot SHALL be consumed

## MODIFIED Requirements

### Requirement: Login form GET renders the unauthenticated login page

The system SHALL serve `GET /admin/login` as an unauthenticated route returning HTTP 200 with an HTML login form. The form SHALL contain three input fields — `email`, `password`, and `totp` (the 6-digit TOTP code) — and a submit button. The form's `action` attribute SHALL be `/admin/login` with `method="POST"`. The page SHALL extend the shared admin base layout (per the `admin-panel-scaffold` capability). The login page MUST NOT render the CSRF meta tag (no session exists yet). The page loads the vendored `admin.js` like every admin page, but `admin.js` registers its HTMX CSRF configRequest listener only when that meta tag is present, so the login page registers none.

The page's presentation SHALL follow the admin mockup board frame 1 (`dev/mockups/nearyou-admin-mockup.html` `#f01`, per [`docs/11-Engineering-Standards.md`](../../../../../docs/11-Engineering-Standards.md) § 3.6):
- a centered card with the brand logo only, and no "Admin login" heading text;
- a leading vendored inline-SVG icon on each of the three inputs (mail for email, key for password, timer for TOTP);
- a placeholder hint on each of the three inputs: an example address for email, a password hint for password, and the existing `6-digit code` hint for TOTP (operator request 2026-06-12);
- a trailing show/hide visibility toggle on the password input, implemented as a `<button type="button">` that flips the input's `type` between `password` and `text`;
- when the generic error message is rendered, a leading icon inside the error banner.

The toggle is wired by the vendored panel script `/admin/static/admin.js`, which the base layout loads, and not by an inline `<script>` block (the panel runs under CSP `script-src 'self'`, per the `admin-panel-scaffold` capability). It has no HTMX dependency, and without JS the input simply stays masked.

The error banner SHALL remain a single generic slot whose rendered bytes are identical on every failure path. The presentation MUST NOT introduce any per-failure-path markup variation: the no-enumeration requirement of this capability is unchanged and continues to gate this.

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
- **AND** the page SHALL load the vendored `/admin/static/admin.js` via `<script src>` and SHALL NOT contain an inline `<script>` block
- **AND** the served `/admin/static/admin.js` SHALL contain the toggle wiring (binding `#pw-toggle` and flipping `type="password"` ↔ `type="text"`)

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

### Requirement: Authenticated layout includes CSRF meta tag and HTMX configRequest hook

The system SHALL render `<meta name="csrf-token" content="${csrfToken}">` in the `<head>` of every authenticated admin page, where `csrfToken` is the plaintext CSRF token associated with the current session.

The `htmx:configRequest` hook SHALL live in the vendored panel script `/admin/static/admin.js` (loaded by the base layout on every admin page), not in an inline `<script>` block. The hook copies the meta tag's content into the `X-CSRF-Token` request header on every HTMX request. It SHALL register its listener only when the `csrf-token` meta tag is present in the document, and SHALL read the meta tag's content at request time so a per-login rotation propagates without a page reload.

Unauthenticated pages (including `/admin/login`) SHALL NOT render the meta tag. Consequently they register no `htmx:configRequest` listener.

#### Scenario: Authenticated page renders the CSRF meta tag

- **GIVEN** an authenticated session
- **WHEN** the client sends `GET /admin/`
- **THEN** the response body SHALL contain `<meta name="csrf-token" content="...">` in the `<head>` (attribute order tolerant)
- **AND** the `content` attribute value SHALL match the plaintext CSRF token associated with the session (verifiable by SHA-256-ing the value and checking equality against the session's `csrf_token_hash`)

#### Scenario: Authenticated page loads the vendored HTMX configRequest hook

- **GIVEN** an authenticated session
- **WHEN** the client sends `GET /admin/`
- **THEN** the response body SHALL contain a `<script src="/admin/static/admin.js" ...>` tag and SHALL NOT contain an inline `<script>` block
- **AND** the served `/admin/static/admin.js` SHALL register an `htmx:configRequest` event listener that sets the header from the meta tag's content (literal substring `evt.detail.headers['X-CSRF-Token']` OR `evt.detail.headers["X-CSRF-Token"]` MUST appear in the script)
- **AND** that registration SHALL be guarded by the presence of `meta[name="csrf-token"]`

#### Scenario: Unauthenticated login page does NOT render the CSRF meta tag or JS hook

- **WHEN** an unauthenticated client sends `GET /admin/login`
- **THEN** the response body SHALL NOT contain `<meta name="csrf-token"` (substring)
- **AND** the response body SHALL NOT contain the substring `htmx:configRequest` (the hook's registration lives in `admin.js` behind the meta-tag guard, so no listener is registered on this page)
