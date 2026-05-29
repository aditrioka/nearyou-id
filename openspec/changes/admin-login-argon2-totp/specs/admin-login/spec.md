## ADDED Requirements

### Requirement: Login form GET renders the unauthenticated login page

The system SHALL serve `GET /admin/login` as an unauthenticated route returning HTTP 200 with an HTML login form. The form SHALL contain three input fields — `email`, `password`, and `totp` (the 6-digit TOTP code) — and a submit button. The form's `action` attribute SHALL be `/admin/login` with `method="POST"`. The page SHALL extend the shared admin base layout (per the `admin-panel-scaffold` capability, Requirement 2 as modified by this change). The login page MUST NOT render the CSRF meta tag or the HTMX CSRF configRequest JS hook (no session exists yet).

#### Scenario: Login page renders with all three input fields

- **WHEN** an unauthenticated client sends `GET /admin/login`
- **THEN** the response status SHALL be 200
- **AND** the response body SHALL contain `<input ... name="email" ...>`, `<input ... name="password" ...>`, AND `<input ... name="totp" ...>` (attribute order tolerant; the spec asserts presence of all three name attributes)
- **AND** the response body SHALL contain a `<form ... action="/admin/login" method="POST" ...>` tag (attribute order tolerant)

#### Scenario: Login page does not include CSRF meta tag

- **WHEN** an unauthenticated client sends `GET /admin/login`
- **THEN** the response body SHALL NOT contain a `<meta name="csrf-token" ...>` tag (no session ⇒ no CSRF token to surface)

#### Scenario: Authenticated client GETting /admin/login is redirected to /admin/

- **GIVEN** an authenticated session exists for the requesting client
- **WHEN** the client sends `GET /admin/login` carrying the valid `__Host-admin_session` cookie
- **THEN** the response status SHALL be 302
- **AND** the `Location` header SHALL be `/admin/`

### Requirement: Login POST verifies Argon2id password and TOTP and creates a session on success

The system SHALL accept `POST /admin/login` with form-encoded `email`, `password`, and `totp` fields. On successful verification (admin exists AND `is_active = TRUE` AND password matches AND TOTP code matches AND `totp_secret_encrypted IS NOT NULL`), the system SHALL:

1. INSERT a row into `admin_sessions` populating `admin_id` (the matched admin's UUID), `session_token_hash` (SHA-256 of a newly-generated 256-bit random session token), `csrf_token_hash` (SHA-256 of the per-session CSRF token — see the amendment note below), `ip` (from `call.clientIp` per the `client-ip-extraction` capability — sanitized to a valid IP literal or the `0.0.0.0` sentinel so a non-literal resolved IP cannot throw at the `INET` cast), `user_agent` (from the request's `User-Agent` header, NULL if absent), `expires_at` (= NOW() + INTERVAL '8 hours'), `last_active_at` (= NOW()). `revoked_at` SHALL be left NULL.

> **AMENDED (apply phase, 2026-05-29):** the CSRF token is NOT an independent random value but is **HMAC-SHA256-derived from the session token** with a server-side secret key (`admin-csrf-hmac-key` slot): `csrfToken = base64url(HMAC-SHA256(key, sessionToken))`. This is the canonical "Signed Double-Submit Cookie" pattern (2026 OWASP CSRF Cheat Sheet) and is required because the authenticated layout must re-render the plaintext CSRF token on every page load while the schema stores only the hash — derivation lets the server recompute it without persisting a second secret. Per-login rotation (fresh session token ⇒ fresh CSRF) + no-per-request-rotation are preserved. See `design.md` D5 for the full rationale + the dated re-check that selected HMAC over plain hashing.
2. Set the `__Host-admin_session` response cookie with the plaintext session token as its value (base64url-encoded). Cookie attributes per Requirement "Cookie format meets security invariants" below.
3. Return an HTTP 200 response with the `HX-Redirect: /admin/` header (HTMX redirect convention) so the client navigates to the authenticated index.

Password verification SHALL use Argon2id via the `com.password4j:password4j` library with the parameters tuned per `design.md` Decision 2. TOTP verification SHALL use RFC 6238 via the `dev.samstevens.totp:totp` library with SHA-1, 30-second step, 6 digits, and ±1 step skew tolerance per `design.md` Decision 3. The TOTP secret SHALL be decrypted from `admin_users.totp_secret_encrypted` via AES-256-GCM using the key sourced from GCP Secret Manager slot `admin-totp-secret-aes-key` (or `staging-admin-totp-secret-aes-key` in staging) via the `secretKey(env, name)` helper.

#### Scenario: Login succeeds with valid credentials

- **GIVEN** an `admin_users` row exists with `email = 'oka@nearyou.id'`, a known Argon2id `password_hash`, a known AES-256-GCM-encrypted `totp_secret_encrypted`, `is_active = TRUE`, `role = 'owner'`
- **WHEN** the client sends `POST /admin/login` with form fields `email=oka@nearyou.id&password=<correct>&totp=<current-code>`
- **THEN** the response status SHALL be 200
- **AND** the response SHALL include header `HX-Redirect: /admin/`
- **AND** the response SHALL include a `Set-Cookie` header for `__Host-admin_session=...`
- **AND** a new `admin_sessions` row SHALL exist with `admin_id = <oka-uuid>`, `session_token_hash` = SHA-256 of the cookie value, `csrf_token_hash` non-null, `expires_at` ≈ NOW() + 8h (±10s for clock tolerance), `last_active_at` ≈ NOW(), `revoked_at IS NULL`

#### Scenario: Login fails when password is wrong

- **GIVEN** the same admin row as the success scenario
- **WHEN** the client sends `POST /admin/login` with form fields `email=oka@nearyou.id&password=<wrong>&totp=<current-code>`
- **THEN** the response status SHALL be 200
- **AND** the response SHALL NOT include any `Set-Cookie` header for `__Host-admin_session`
- **AND** no new `admin_sessions` row SHALL be created
- **AND** the response body SHALL contain the generic error message defined in Requirement "Login POST returns no-enumeration response on every failure path"

#### Scenario: Login fails when TOTP code is wrong

- **GIVEN** the same admin row as the success scenario
- **WHEN** the client sends `POST /admin/login` with form fields `email=oka@nearyou.id&password=<correct>&totp=<wrong>`
- **THEN** the response status SHALL be 200
- **AND** the response SHALL NOT include any `Set-Cookie` header for `__Host-admin_session`
- **AND** no new `admin_sessions` row SHALL be created
- **AND** the response body SHALL contain the generic error message

#### Scenario: Login fails when admin is_active is FALSE

- **GIVEN** an `admin_users` row exists with `is_active = FALSE`
- **WHEN** the client sends `POST /admin/login` with that admin's correct email + password + TOTP
- **THEN** the response status SHALL be 200
- **AND** the response SHALL NOT include any `Set-Cookie` header for `__Host-admin_session`
- **AND** no new `admin_sessions` row SHALL be created
- **AND** the response body SHALL contain the generic error message (same as wrong-password / wrong-TOTP scenarios above)

#### Scenario: Login fails when totp_secret_encrypted is NULL

- **GIVEN** an `admin_users` row exists with `is_active = TRUE` and `totp_secret_encrypted IS NULL` (incomplete admin row — should not happen in steady state per the bootstrap procedure, but defensively guarded)
- **WHEN** the client sends `POST /admin/login` with that admin's correct email + password + any TOTP code
- **THEN** the response status SHALL be 200
- **AND** the response SHALL NOT include any `Set-Cookie` header for `__Host-admin_session`
- **AND** no new `admin_sessions` row SHALL be created
- **AND** the response body SHALL contain the generic error message

#### Scenario: Login uses parameterized query for admin lookup

- **WHEN** the login handler queries `admin_users` by email
- **THEN** the query SHALL be a JDBC `PreparedStatement` with `email` bound as a parameter (NOT a string-interpolated query)
- **AND** the query SHALL include `is_active = TRUE` in the WHERE clause (so deactivated admins are not selected at all)

### Requirement: Login POST returns no-enumeration response on every failure path

The system SHALL return the SAME response shape for every login failure mode — wrong email (admin not found), wrong password, wrong TOTP code, `is_active = FALSE`, or `totp_secret_encrypted IS NULL`. The response SHALL be HTTP 200 with the login form re-rendered, displaying the generic error message: `"Email, password, or code is incorrect."` (the exact wording is normative; implementation MAY translate to Bahasa Indonesia in a future change, but the no-distinguishing-marker contract is the spec invariant). The response SHALL NOT include any header, body marker, or status difference that would let the requester distinguish which failure mode occurred.

To prevent timing-side-channel enumeration, the login handler SHALL ALWAYS run Argon2id verify regardless of whether the email exists. When the email is not found in `admin_users`, the handler SHALL verify the submitted password against a fixed sentinel Argon2id hash (a hardcoded valid hash with the same tuned parameters as production hashes, generated once at proposal time). When the password verification step fails (wrong password OR sentinel match against a non-existent admin), the handler SHALL ALSO run a TOTP verify against a fixed sentinel TOTP secret so the wall-time profile of `password_mismatch` matches `totp_mismatch`.

#### Scenario: All failure paths return identical body, status, and headers

- **GIVEN** a population of failure cases — (a) email not in `admin_users`, (b) email present + wrong password, (c) email present + correct password + wrong TOTP, (d) email present + `is_active = FALSE`, (e) email present + `totp_secret_encrypted IS NULL`
- **WHEN** each case POSTs to `/admin/login`
- **THEN** every response SHALL have status 200
- **AND** every response body SHALL contain the substring `Email, password, or code is incorrect.`
- **AND** no response SHALL include a header or body marker that distinguishes which failure occurred (no `X-Failure-Reason` header, no failure-specific HTML class, no debug message)

#### Scenario: Timing equalization runs Argon2id verify on email-not-found

- **GIVEN** no `admin_users` row exists with `email = 'phantom@nearyou.id'`
- **WHEN** the client sends `POST /admin/login` with `email=phantom@nearyou.id&password=anything&totp=000000`
- **THEN** the handler SHALL run an Argon2id verify against the sentinel hash before responding
- **AND** the wall-time of the response SHALL be approximately equal to the wall-time of a wrong-password response for an existing admin (the spec asserts behavioral indistinguishability; the concrete statistical-bound mechanics live at `tasks.md` task 11.5 which currently asserts MEDIAN wall-time difference ≤ 200ms across 30 samples per arm, `@Tag("benchmark")` to mitigate CI flakiness)

#### Scenario: Sentinel hash is a valid Argon2id hash with current parameters

- **WHEN** the test suite inspects the source-declared sentinel hash constant
- **THEN** the constant SHALL parse as a valid Argon2id hash via `Password.check(<any-string>, <sentinel>).withArgon2()` (returns a boolean without throwing)
- **AND** the parsed hash SHALL declare the same memory, iterations, and parallelism parameters as `PasswordHasher`'s production configuration

### Requirement: Login POST writes audit row when an admin actor is identified

The system SHALL insert a row into `admin_actions_log` for every login attempt WHERE an admin row was matched (success OR matched-but-failed paths). Successful logins SHALL be logged with `action_type = 'admin_login_success'`, `admin_id` = the matched admin's UUID, `ip` from `call.clientIp`, `user_agent` from request header (NULL-tolerant), and `after_state` JSON containing the session metadata (`session_id`, `expires_at`, `last_active_at`) but NEVER the plaintext token, the CSRF token, or any hash thereof.

Failed logins matched to an admin row SHALL be logged with `action_type = 'admin_login_failure'`, `admin_id` = the matched admin's UUID, `ip`, `user_agent`, and `reason` ∈ `{'password_mismatch', 'totp_mismatch', 'inactive_admin', 'totp_secret_missing'}`. The audit row SHALL NOT contain the submitted password, the submitted TOTP code, or any value derived from them (no hash of submitted password, no truncation).

The `email_not_found` failure mode (the submitted email did not resolve to any `admin_users` row, regardless of `is_active` state) is NOT written to `admin_actions_log` per `design.md` Decision 14 — the V16 schema's `admin_actions_log.admin_id NOT NULL` invariant forbids unowned audit rows, and an attacker probing for valid admin emails is not an admin actor. Instead, the system SHALL emit a structured INFO log line at the application logger (Sentry + Cloud Logging reach) carrying fields `event=admin_login_attempt_email_not_found`, `ip`, `user_agent`, `email_hash` (SHA-256 of the submitted email, base64url-encoded, so the same email is correlatable across attempts without exposing plaintext), `timestamp`.

#### Scenario: Successful login writes admin_login_success audit row

- **WHEN** a login succeeds (per the success scenario in the verification requirement)
- **THEN** an `admin_actions_log` row SHALL exist with `action_type = 'admin_login_success'`
- **AND** `admin_id` = the matched admin's UUID
- **AND** `ip` = the value from `call.clientIp`
- **AND** `user_agent` = the request's `User-Agent` header value (or NULL when absent)
- **AND** `after_state` SHALL be a JSON object containing `session_id`, `expires_at`, `last_active_at` keys (and MAY contain other non-secret session metadata)
- **AND** `after_state` SHALL NOT contain `session_token`, `csrf_token`, `password`, `totp`, or any key whose value matches the plaintext or hash of those values (verified by scanning the JSON for any substring match against the test fixtures' known values, including SHA-256 of the cookie value)

#### Scenario: Audit row records NULL user_agent when the request omitted the header

- **WHEN** a login attempt POSTs `/admin/login` with no `User-Agent` request header
- **AND** an `admin_actions_log` row is written for the attempt (success or matched-but-failed)
- **THEN** the row's `user_agent` column SHALL be NULL

#### Scenario: Audit row records the forwarded client IP (CF-Connecting-IP)

- **GIVEN** the request carries `CF-Connecting-IP: 1.2.3.4` (per the `client-ip-extraction` capability)
- **WHEN** a login attempt POSTs `/admin/login` and an audit row is written
- **THEN** the row's `ip` column SHALL be `1.2.3.4` (NOT the LB / Cloudflare edge IP that would appear in `X-Forwarded-For`'s last hop or `remoteHost`)

#### Scenario: Email-not-found does NOT write to admin_actions_log

- **GIVEN** no `admin_users` row exists with `email = 'phantom@nearyou.id'`
- **WHEN** the client sends `POST /admin/login` with `email=phantom@nearyou.id&password=anything&totp=000000`
- **THEN** no new `admin_actions_log` row SHALL be inserted (the `admin_id NOT NULL` invariant from V16 forbids it; per design.md D14, this attempt is captured at the application logger instead)
- **AND** the response body SHALL still contain the generic error message (no-enumeration contract preserved at the HTTP layer)

#### Scenario: Email-not-found writes a structured INFO log line at the application logger

- **GIVEN** no `admin_users` row exists with `email = 'phantom@nearyou.id'`
- **WHEN** the client sends `POST /admin/login` with `email=phantom@nearyou.id&password=anything&totp=000000`
- **AND** the application's log capture (via Logback `ListAppender` or equivalent) captures emitted log lines
- **THEN** an INFO-level log line SHALL be captured carrying the substring `event=admin_login_attempt_email_not_found`
- **AND** the log line SHALL include the `ip`, `user_agent`, and `email_hash` fields
- **AND** the log line SHALL NOT include the plaintext email (only its SHA-256 hash)
- **AND** the log line SHALL NOT include the submitted password or TOTP code

#### Scenario: Password mismatch failure records the matched admin's UUID

- **WHEN** a login fails because the password does not match
- **THEN** an `admin_actions_log` row SHALL exist with `action_type = 'admin_login_failure'`
- **AND** `admin_id` = the matched admin's UUID (the email did resolve to an admin row; only the password failed)
- **AND** `reason = 'password_mismatch'`

#### Scenario: TOTP mismatch failure records reason and admin

- **WHEN** a login fails because the TOTP code does not match
- **THEN** an `admin_actions_log` row SHALL exist with `action_type = 'admin_login_failure'`, `admin_id` = the matched admin's UUID, `reason = 'totp_mismatch'`

#### Scenario: Inactive admin failure records reason

- **WHEN** a login fails because the matched admin has `is_active = FALSE`
- **THEN** an `admin_actions_log` row SHALL exist with `action_type = 'admin_login_failure'`, `admin_id` = the matched admin's UUID, `reason = 'inactive_admin'`

#### Scenario: TOTP secret missing failure records reason

- **WHEN** a login fails because the matched admin's `totp_secret_encrypted` is NULL
- **THEN** an `admin_actions_log` row SHALL exist with `action_type = 'admin_login_failure'`, `admin_id` = the matched admin's UUID, `reason = 'totp_secret_missing'`

#### Scenario: Audit row never contains submitted password or TOTP

- **WHEN** a login attempt fails with the submitted password `'P@ssw0rd!Secret'` and TOTP `'123456'`
- **THEN** no value in the resulting `admin_actions_log` row SHALL contain the substring `'P@ssw0rd!Secret'`
- **AND** no value SHALL contain the substring `'123456'`
- **AND** no hash of `'P@ssw0rd!Secret'` SHALL appear in any column (verified by checking `reason`, `before_state`, `after_state` JSON values against the test fixtures' known plaintexts AND their SHA-256, MD5, and Argon2id hashes)

### Requirement: __Host-admin_session cookie format meets security invariants

The system SHALL set the session cookie with name `__Host-admin_session` and the following attributes: `Secure`, `HttpOnly`, `SameSite=Strict`, `Path=/`. The cookie SHALL NOT carry a `Domain` attribute (required by the `__Host-` prefix per RFC 6265bis §4.1.3.2). The cookie value SHALL be the base64url-encoding of a 256-bit (32-byte) random token generated via `java.security.SecureRandom`. The plaintext token SHALL never be persisted; only its SHA-256 hash is stored in `admin_sessions.session_token_hash` per the canonical schema in [`docs/05-Implementation.md`](../../../../../docs/05-Implementation.md). The `Max-Age` attribute SHALL be the number of seconds until the session's `expires_at` (so the browser drops the cookie when the absolute cap is hit).

#### Scenario: Cookie sets __Host- prefix and required attributes

- **WHEN** a login succeeds
- **THEN** the `Set-Cookie` header SHALL begin with `__Host-admin_session=`
- **AND** the header SHALL contain the attributes `Secure`, `HttpOnly`, `SameSite=Strict`, `Path=/`
- **AND** the header SHALL NOT contain a `Domain=` attribute (`__Host-` prefix requirement)

#### Scenario: Cookie value is a 43-character base64url string (256 bits of entropy)

- **WHEN** a login succeeds and the `Set-Cookie` header is parsed
- **THEN** the cookie value (after the `=` and before the first `;`) SHALL match the regular expression `^[A-Za-z0-9_-]{43}$` (43 chars = 32 bytes × 8 / 6, with base64url's `-` and `_` substituting for `+` and `/`, and padding stripped)

#### Scenario: SHA-256 of cookie value matches admin_sessions.session_token_hash

- **WHEN** a login succeeds with cookie value `<plaintext-token>`
- **AND** the `admin_sessions` row inserted for that login has `session_token_hash = <stored-hash>`
- **THEN** the SHA-256 of `<plaintext-token>` SHALL equal `<stored-hash>` (constant-time equality via `MessageDigest.isEqual`)

#### Scenario: Max-Age aligns with expires_at

- **WHEN** a login succeeds with `expires_at = T + 8h`
- **THEN** the `Set-Cookie` header SHALL include `Max-Age=` with a value within ±60 seconds of 28800 (8 hours in seconds)

### Requirement: Session middleware validates the cookie on every authenticated request

The system SHALL gate every `/admin/*` route (except `GET /admin/login`, `POST /admin/login`, and `GET /admin/static/*`) behind a session validation middleware. The middleware SHALL:

1. Read the `__Host-admin_session` cookie. If absent, redirect (302) to `/admin/login`.
2. Compute SHA-256 of the cookie value.
3. SELECT the `admin_sessions` row where `session_token_hash = <computed-hash>` (parameterized query). If no row, redirect (302) to `/admin/login`.
4. Validate the row: `revoked_at IS NULL` AND `expires_at > NOW()` AND `last_active_at > NOW() - INTERVAL '30 minutes'`. If any condition fails, redirect (302) to `/admin/login` (do not extend the session).
5. UPDATE `last_active_at = NOW()` for the row.
6. Populate `call.principal` with `AdminPrincipal(admin_id, role)` for downstream route handlers.

#### Scenario: Request without session cookie redirects to /admin/login

- **WHEN** a client sends `GET /admin/` with no `Cookie` header (or with no `__Host-admin_session` cookie)
- **THEN** the response status SHALL be 302
- **AND** the `Location` header SHALL be `/admin/login`

#### Scenario: Request with valid session returns the route's content and refreshes last_active_at

- **GIVEN** an active session exists with `last_active_at = T0` where `T0 > NOW() - INTERVAL '30 minutes'`
- **WHEN** the client sends `GET /admin/` carrying the valid `__Host-admin_session` cookie
- **THEN** the response status SHALL be 200 (the route's normal response)
- **AND** after the response is sent, the `admin_sessions.last_active_at` for that session SHALL be ≥ T0 (refreshed to ≈ NOW())

#### Scenario: Request with expired session (expires_at < NOW()) redirects to login

- **GIVEN** a session row exists with `expires_at < NOW()` (absolute cap exceeded)
- **WHEN** the client sends `GET /admin/` carrying that session's cookie
- **THEN** the response status SHALL be 302
- **AND** the `Location` header SHALL be `/admin/login`
- **AND** the `admin_sessions.last_active_at` SHALL NOT be refreshed (the session is dead)

#### Scenario: Request with idle session (last_active_at > 30 minutes ago) redirects to login

- **GIVEN** a session row exists with `last_active_at < NOW() - INTERVAL '30 minutes'` AND `revoked_at IS NULL` AND `expires_at > NOW()`
- **WHEN** the client sends `GET /admin/` carrying that session's cookie
- **THEN** the response status SHALL be 302
- **AND** the `Location` header SHALL be `/admin/login`

#### Scenario: Request with revoked session redirects to login

- **GIVEN** a session row exists with `revoked_at IS NOT NULL`
- **WHEN** the client sends `GET /admin/` carrying that session's cookie
- **THEN** the response status SHALL be 302
- **AND** the `Location` header SHALL be `/admin/login`

#### Scenario: Session middleware uses parameterized query for token-hash lookup

- **WHEN** the session middleware SELECTs from `admin_sessions`
- **THEN** the query SHALL be a JDBC `PreparedStatement` with `session_token_hash` bound as a parameter (NOT string-interpolated)

#### Scenario: Session middleware fails CLOSED on DB exception

- **GIVEN** the `admin_sessions` SELECT query throws (connection pool exhausted, Postgres restart, JDBC timeout, etc.)
- **WHEN** the client sends `GET /admin/` with a session cookie
- **THEN** the response status SHALL be 302 (or 500 — both signal closed; what SHALL NOT happen is status 200)
- **AND** the `Location` header (if status 302) SHALL be `/admin/login`
- **AND** the principal SHALL NOT be populated (downstream route handlers do not receive a valid session)
- **AND** the failure SHALL be logged at WARN level at the application logger so operators see the DB outage signal (per `design.md` Decision 16)

#### Scenario: Session middleware does not extend an idle-timed-out session via last_active_at refresh

- **GIVEN** a session row with `last_active_at` AT EXACTLY `NOW() - INTERVAL '30 minutes 1 second'` (just past the sliding idle threshold)
- **WHEN** the client sends `GET /admin/` carrying that session's cookie
- **THEN** the session middleware SHALL classify the session as idle-timed-out
- **AND** the response SHALL be 302 to `/admin/login`
- **AND** the `last_active_at` column SHALL NOT be refreshed to `NOW()` (a dead session must not be revived by the same request that's being rejected)

#### Scenario: Forged cookie that hashes to no admin_sessions row redirects to login

- **GIVEN** the client sends a well-formed cookie value (43-character base64url string matching the cookie format regex) that has NEVER been issued — i.e., its SHA-256 hash does not match any row in `admin_sessions`
- **WHEN** the request reaches the session middleware
- **THEN** the response SHALL be 302 to `/admin/login`
- **AND** no `admin_sessions.last_active_at` row SHALL be updated
- **AND** the response SHALL clear the cookie (the browser MAY have cached an attacker-supplied value; clearing it terminates the loop)

### Requirement: CSRF middleware validates X-CSRF-Token on state-changing requests

The system SHALL gate every state-changing request (HTTP method ∈ `{POST, PUT, PATCH, DELETE}`) under `/admin/*` (EXCEPT `POST /admin/login`, which is pre-session and exempt) with a CSRF validation middleware. The middleware SHALL accept the CSRF token from EITHER the `X-CSRF-Token` request header OR the `_csrf` form field. The header SHALL take precedence if both are present. The middleware SHALL compute SHA-256 of the submitted token and compare (constant-time via `MessageDigest.isEqual`) against the current session's `admin_sessions.csrf_token_hash`. Mismatch SHALL return HTTP 403 and write an audit row with `action_type = 'admin_csrf_violation'` (per the audit-log requirement below).

#### Scenario: State-changing request without CSRF token returns 403

- **GIVEN** an authenticated session
- **WHEN** the client sends `POST /admin/logout` with no `X-CSRF-Token` header and no `_csrf` form field
- **THEN** the response status SHALL be 403

#### Scenario: State-changing request with wrong CSRF token returns 403

- **GIVEN** an authenticated session
- **WHEN** the client sends `POST /admin/logout` with `X-CSRF-Token: <wrong-value>` header
- **THEN** the response status SHALL be 403

#### Scenario: State-changing request with correct CSRF token succeeds

- **GIVEN** an authenticated session with stored `csrf_token_hash = SHA-256(<plaintext-csrf>)`
- **WHEN** the client sends `POST /admin/logout` with `X-CSRF-Token: <plaintext-csrf>` header
- **THEN** the CSRF middleware SHALL pass the request through to the route handler (status 200, 302, or whatever the route returns)

#### Scenario: CSRF token accepted from _csrf form field if header absent

- **GIVEN** an authenticated session with stored `csrf_token_hash = SHA-256(<plaintext-csrf>)`
- **WHEN** the client sends `POST /admin/some-state-change` with form field `_csrf=<plaintext-csrf>` and no `X-CSRF-Token` header
- **THEN** the CSRF middleware SHALL pass the request through to the route handler

#### Scenario: Header takes precedence over form field

- **GIVEN** an authenticated session with stored `csrf_token_hash = SHA-256(<plaintext-csrf>)`
- **WHEN** the client sends a state-changing request with `X-CSRF-Token: <plaintext-csrf>` header AND `_csrf=<different-value>` form field
- **THEN** the request SHALL be accepted (header `<plaintext-csrf>` matches; form field value is ignored)

#### Scenario: Login POST is exempt from CSRF middleware

- **WHEN** the client sends `POST /admin/login` with no `X-CSRF-Token` header
- **THEN** the CSRF middleware SHALL NOT reject the request
- **AND** the login route handler SHALL receive the request (and produce its normal success or failure response per the Login requirement above)

#### Scenario: GET requests are not gated by CSRF middleware

- **GIVEN** an authenticated session
- **WHEN** the client sends `GET /admin/` (idempotent method) with no `X-CSRF-Token` header
- **THEN** the request SHALL pass through to the route handler (status 200)

#### Scenario: CSRF token does NOT rotate per request — same token accepted across multiple in-flight state-changing requests

- **GIVEN** an authenticated session with stored `csrf_token_hash = SHA-256(<plaintext-csrf>)`
- **WHEN** the client sends TWO sequential state-changing requests `POST /admin/<any-test-route>` AND `POST /admin/<any-other-test-route>` with `X-CSRF-Token: <plaintext-csrf>` on BOTH
- **THEN** both requests SHALL pass the CSRF middleware (per the per-login-rotation contract in `design.md` D6 — the same token is reusable until the next successful login regenerates it)
- **AND** the `admin_sessions.csrf_token_hash` SHALL NOT have changed between the two requests (verified by SELECT after request 1 and after request 2)
- **AND** no `admin_csrf_violation` audit row SHALL be written

#### Scenario: CSRF token regenerates only on successful login (not on per-request use)

- **GIVEN** an authenticated session with `csrf_token_hash = SHA-256(<token-A>)`
- **WHEN** the client performs a successful state-changing request with `X-CSRF-Token: <token-A>` (e.g., a synthetic test route that doesn't terminate the session)
- **THEN** the `admin_sessions.csrf_token_hash` SHALL remain `SHA-256(<token-A>)` (no rotation on use)
- **AND** the next subsequent state-changing request with `X-CSRF-Token: <token-A>` SHALL also succeed

### Requirement: CSRF mismatch writes admin_csrf_violation audit row

The system SHALL insert a row into `admin_actions_log` whenever the CSRF middleware rejects a request. The row SHALL have `action_type = 'admin_csrf_violation'`, `admin_id` = the session's admin UUID (if a session was found), `target_type = 'csrf'`, `reason` describing the mismatch shape (one of `'missing_token'`, `'header_mismatch'`, `'form_field_mismatch'`), `ip`, `user_agent`. The audit row SHALL NOT contain the submitted CSRF token value (raw or hashed).

#### Scenario: Missing-token rejection writes audit row

- **GIVEN** an authenticated session
- **WHEN** the client sends a state-changing request with neither header nor form field
- **AND** the CSRF middleware returns 403
- **THEN** an `admin_actions_log` row SHALL exist with `action_type = 'admin_csrf_violation'`, `admin_id` = the session admin's UUID, `target_type = 'csrf'`, `reason = 'missing_token'`

#### Scenario: Header-mismatch rejection writes audit row

- **GIVEN** an authenticated session
- **WHEN** the client sends a state-changing request with `X-CSRF-Token: <wrong>` (and no form field)
- **AND** the CSRF middleware returns 403
- **THEN** an `admin_actions_log` row SHALL exist with `reason = 'header_mismatch'`

#### Scenario: Audit row never contains the submitted CSRF token

- **WHEN** a CSRF violation is logged with the submitted token `'wrong-csrf-token-value-here'`
- **THEN** no column in the resulting `admin_actions_log` row SHALL contain the substring `'wrong-csrf-token-value-here'`

### Requirement: Logout POST revokes session, clears cookie, and writes audit row

The system SHALL accept `POST /admin/logout` as a CSRF-required authenticated route. The handler SHALL UPDATE the current session's row to set `revoked_at = NOW()` and SHALL set a response cookie that clears `__Host-admin_session` (empty value, `Max-Age=0`, same other attributes as the original cookie so the browser drops it). The handler SHALL write an `admin_actions_log` row with `action_type = 'admin_logout'`, `admin_id` = the session admin's UUID, `ip`, `user_agent`. The handler SHALL respond with status 302 and `Location: /admin/login`.

#### Scenario: Logout sets revoked_at, clears cookie, redirects

- **GIVEN** an authenticated session with `revoked_at IS NULL`
- **WHEN** the client sends `POST /admin/logout` with the valid session cookie + valid `X-CSRF-Token`
- **THEN** the response status SHALL be 302
- **AND** the `Location` header SHALL be `/admin/login`
- **AND** the response SHALL include a `Set-Cookie` header with `__Host-admin_session=` (empty value) and `Max-Age=0`
- **AND** the `admin_sessions` row for that session SHALL have `revoked_at` ≈ NOW() (±10s tolerance)

#### Scenario: Logout writes admin_logout audit row

- **WHEN** a logout succeeds
- **THEN** an `admin_actions_log` row SHALL exist with `action_type = 'admin_logout'`, `admin_id` = the session admin's UUID

#### Scenario: Logout is idempotent against already-revoked sessions

- **GIVEN** a session that was already revoked (e.g., by a prior logout in another tab) `revoked_at IS NOT NULL`
- **WHEN** the client sends `POST /admin/logout` with the now-revoked session cookie + a previously-valid CSRF token
- **THEN** the session middleware SHALL first reject the request (302 to `/admin/login`) because `revoked_at IS NOT NULL` makes the session invalid (per the session middleware requirement)
- **AND** the logout route handler SHALL NOT be reached, so no new `admin_actions_log` row SHALL be written
- **AND** the response SHALL clear the cookie regardless (the redirect response from the session middleware does NOT need to set the clear-cookie header — the browser already considers the session invalid because the server keeps rejecting it)

### Requirement: TOTP secret decryption uses AES-256-GCM with key from GCP Secret Manager and admin-UUID AAD binding

The system SHALL decrypt `admin_users.totp_secret_encrypted` at login-verify time via `javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")`. The AES-256 key SHALL be sourced via the `secretKey(env, "admin-totp-secret-aes-key")` helper (the `KTOR_ENV`-namespaced secret slot — `staging-admin-totp-secret-aes-key` in staging, `admin-totp-secret-aes-key` in production). The ciphertext format SHALL be `BYTEA = nonce (12 bytes) || ciphertext || auth_tag (16 bytes)` per `design.md` Decision 1. The GCM authentication tag SHALL be verified — if verification fails (tampered ciphertext, wrong key, or AAD mismatch), the decryption SHALL throw and the login attempt SHALL be treated as `totp_secret_missing` (the bootstrap procedure produces valid ciphertext; a tamper signals storage corruption or row-swap, not a normal login flow).

The admin's UUID (`admin_users.id`, as 16 raw bytes from the UUID's most-significant + least-significant longs) SHALL be bound as Additional Authenticated Data (AAD) via `Cipher.updateAAD(adminUuidBytes)` before the `doFinal()` call on BOTH the encrypt and decrypt paths (per `design.md` Decision 1). This prevents a DB-write attacker from swapping admin A's encrypted TOTP secret into admin B's `totp_secret_encrypted` column — the AAD mismatch produces an `AEADBadTagException` on decrypt.

#### Scenario: AES-GCM round-trip on a known-secret fixture succeeds

- **GIVEN** a test fixture with `secret = SecureRandom.nextBytes(20)`, `key = SecureRandom.nextBytes(32)`, and ciphertext produced by the inverse encrypt helper
- **WHEN** `AesGcmCipher.decrypt(ciphertext, key)` is called
- **THEN** the result SHALL equal the original `secret`

#### Scenario: AES-GCM decryption fails on tampered ciphertext

- **GIVEN** a valid ciphertext produced by the encrypt helper
- **WHEN** any byte in the auth_tag region is flipped
- **AND** `AesGcmCipher.decrypt(tamperedCiphertext, key)` is called
- **THEN** the method SHALL throw a Java cryptography exception (`AEADBadTagException` or equivalent)

#### Scenario: Key is sourced via secretKey helper, not a hardcoded constant

- **WHEN** the source code is grep'd for the literal AES key value
- **THEN** no match SHALL exist outside test fixtures (the key value is sourced exclusively via `secretKey(env, "admin-totp-secret-aes-key")` at runtime)

#### Scenario: secret slot name matches the env-namespaced helper convention

- **WHEN** `KTOR_ENV = 'staging'`, the `secretKey(env, "admin-totp-secret-aes-key")` call SHALL resolve the GCP Secret Manager slot named `staging-admin-totp-secret-aes-key`
- **AND** when `KTOR_ENV = 'production'`, it SHALL resolve `admin-totp-secret-aes-key` (unprefixed, per the project's secret-namespacing convention)

#### Scenario: AAD-bound encrypt-decrypt round-trip succeeds when the admin UUID matches

- **GIVEN** a test fixture with `secret = SecureRandom.nextBytes(20)`, `key = SecureRandom.nextBytes(32)`, `adminUuid = UUID.randomUUID()`, and ciphertext produced by `AesGcmCipher.encrypt(secret, key, adminUuidAsBytes(adminUuid))`
- **WHEN** `AesGcmCipher.decrypt(ciphertext, key, adminUuidAsBytes(adminUuid))` is called with the SAME admin UUID
- **THEN** the result SHALL equal the original `secret`

#### Scenario: AAD-bound decrypt FAILS when the admin UUID is swapped

- **GIVEN** a ciphertext encrypted with `adminUuid = A` AAD
- **WHEN** `AesGcmCipher.decrypt(ciphertext, key, adminUuidAsBytes(B))` is called with a different admin UUID (`B != A`)
- **THEN** the method SHALL throw `AEADBadTagException` (or equivalent JCA exception)

#### Scenario: AES-GCM decryption fails on wrong-length key

- **GIVEN** a valid ciphertext encrypted with a 256-bit (32-byte) key + correct AAD
- **WHEN** `AesGcmCipher.decrypt(ciphertext, key=SecureRandom.nextBytes(16), aad=...)` is called with a 128-bit (16-byte) key
- **THEN** the method SHALL throw `InvalidKeyException` (or equivalent JCA exception indicating the key length is unsupported)
- **AND** a misconfigured GCP Secret Manager slot SHALL fail loudly at login-verify time, not silently produce a wrong-decrypt result

### Requirement: Authenticated layout includes CSRF meta tag and HTMX configRequest hook

The system SHALL render `<meta name="csrf-token" content="${csrfToken}">` in the `<head>` of every authenticated admin page where `csrfToken` is the plaintext CSRF token associated with the current session. The same authenticated layout SHALL include an inline `<script>` block that registers an `htmx:configRequest` event listener which copies the meta-tag content into the `X-CSRF-Token` request header on every HTMX request. Unauthenticated pages (including `/admin/login`) SHALL NOT render either the meta tag or the JS hook.

#### Scenario: Authenticated page renders the CSRF meta tag

- **GIVEN** an authenticated session
- **WHEN** the client sends `GET /admin/`
- **THEN** the response body SHALL contain `<meta name="csrf-token" content="...">` in the `<head>` (attribute order tolerant)
- **AND** the `content` attribute value SHALL match the plaintext CSRF token associated with the session (verifiable by SHA-256-ing the value and checking equality against the session's `csrf_token_hash`)

#### Scenario: Authenticated page includes the HTMX configRequest JS hook

- **GIVEN** an authenticated session
- **WHEN** the client sends `GET /admin/`
- **THEN** the response body SHALL contain a `<script>` block (or a referenced JS file via `<script src="...">`) that registers an `htmx:configRequest` event listener
- **AND** the listener SHALL set `evt.detail.headers['X-CSRF-Token']` from the meta tag's content (literal substring `evt.detail.headers['X-CSRF-Token']` OR `evt.detail.headers["X-CSRF-Token"]` MUST appear in the script body)

#### Scenario: Unauthenticated login page does NOT render the CSRF meta tag or JS hook

- **WHEN** an unauthenticated client sends `GET /admin/login`
- **THEN** the response body SHALL NOT contain `<meta name="csrf-token"` (substring)
- **AND** the response body SHALL NOT register an `htmx:configRequest` listener (no session ⇒ no CSRF context)

### Requirement: All admin auth comparisons are constant-time

The system SHALL use constant-time comparison primitives for every secret-derived comparison in the admin auth path. Session-token and CSRF-token hash comparisons SHALL use `java.security.MessageDigest.isEqual(byte[] a, byte[] b)`. Password verification SHALL use Password4j's `Password.check(...)` API (library-internal constant-time). TOTP verification SHALL use samstevens java-totp's `CodeVerifier.isValidCode(...)` API (library-internal constant-time). The admin auth code SHALL NOT use raw `==` / `.equals(...)` / `String.equals(...)` / `Arrays.equals(byte[] a, byte[] b)` for any comparison whose operands derive from a secret (password, TOTP code, session token, CSRF token, or any hash thereof).

#### Scenario: Source code does not use Arrays.equals on secret-derived bytes

- **WHEN** the test suite scans the admin auth source files (`backend/ktor/src/main/kotlin/id/nearyou/app/admin/auth/**/*.kt`) for the import `java.util.Arrays.equals` or unqualified `Arrays.equals(`
- **THEN** no match SHALL exist (the helper either uses `MessageDigest.isEqual` for hash compares, or library APIs)

#### Scenario: Source code does not use String.equals on token values

- **WHEN** the test suite scans the admin auth source for `String.equals(` invocations on values typed as `SessionToken`, `CsrfToken`, or `byte[]`
- **THEN** no match SHALL exist (the type system + naming convention catches this; the scan is a backstop)

#### Scenario: MessageDigest.isEqual is the canonical hash compare

- **WHEN** the test suite scans the admin auth source for byte-array equality compares
- **THEN** the canonical pattern SHALL be `MessageDigest.isEqual(a, b)` (verified by grepping for the literal `MessageDigest.isEqual` substring)

#### Scenario: Source scan catches Kotlin operator-form equality on secret-named locals

- **WHEN** the test suite scans the admin auth source for `==` operator usage on locals/parameters whose name matches `*token*` / `*hash*` / `*csrf*` (case-insensitive)
- **THEN** no match SHALL exist (Kotlin's `==` desugars to `.equals(...)` which is NOT constant-time; named-token variables SHALL only be compared via `MessageDigest.isEqual` or via library APIs)

### Requirement: Argon2id verification uses tuned parameters with documented benchmark

The system SHALL configure Password4j's `Argon2Function` with memory, iterations, and parallelism parameters tuned per `design.md` Decision 2. The parameters SHALL meet the OWASP minimums (memory ≥ 15 MiB, iterations ≥ 2, parallelism = 1) and SHALL be tuned so that a single `Password.check(...)` invocation on the local dev machine completes in approximately 400-800 milliseconds (the benchmark range; documented via a `@benchmark` comment in `PasswordHasher.kt` recording the measured mean and date).

#### Scenario: Configured parameters meet OWASP minimums

- **WHEN** the test suite inspects the `PasswordHasher` Kotlin constants
- **THEN** `memoryInKib ≥ 15 * 1024` (15 MiB)
- **AND** `iterations ≥ 2`
- **AND** `parallelism = 1`

#### Scenario: Benchmark test asserts verify time within range

- **WHEN** the benchmark Kotest spec runs `Password.check(plaintext, hash)` 10 times with the configured parameters
- **THEN** the mean wall time SHALL be ≥ 300ms (lower bound — some headroom from the 400ms target to tolerate CI variability)
- **AND** the mean wall time SHALL be ≤ 2000ms (upper bound — catches accidental over-tuning; the target is 400-800ms but CI machines vary)

### Requirement: TOTP verification uses RFC 6238 with SHA-1 / 30s / 6 digits / ±1 step skew

The system SHALL configure samstevens java-totp's `CodeVerifier` with `HashingAlgorithm.SHA1`, `timePeriod = 30` seconds, `digits = 6`, and `allowedTimePeriodDiscrepancy = 1` (so codes from the previous, current, or next step are accepted — 90-second total window per RFC 6238 §5.2 + Google Authenticator default compatibility).

#### Scenario: Configured TOTP parameters match docs

- **WHEN** the test suite inspects the `TotpVerifier` Kotlin constants
- **THEN** the algorithm SHALL be SHA-1
- **AND** the step period SHALL be 30 seconds
- **AND** the digit count SHALL be 6
- **AND** the skew tolerance SHALL be ±1 step

#### Scenario: Code generated for the current step verifies

- **GIVEN** a TOTP secret, a known timestamp `T`, and the canonical RFC 6238 generator function
- **WHEN** the verifier checks the generated code against the secret at time `T`
- **THEN** the verification SHALL return TRUE

#### Scenario: Code from one step ago verifies (skew tolerance)

- **GIVEN** the same fixture as above
- **WHEN** the verifier checks the code generated at `T - 30s` against the secret at time `T`
- **THEN** the verification SHALL return TRUE

#### Scenario: Code from two steps ago fails (skew exceeded)

- **GIVEN** the same fixture
- **WHEN** the verifier checks the code generated at `T - 60s` against the secret at time `T`
- **THEN** the verification SHALL return FALSE

#### Scenario: Code from one step ahead verifies (forward skew)

- **GIVEN** the same fixture
- **WHEN** the verifier checks the code generated at `T + 30s` against the secret at time `T`
- **THEN** the verification SHALL return TRUE

### Requirement: Login input handling tolerates Unicode and rejects malformed shapes safely

The login form fields SHALL handle Unicode email + Unicode password inputs (UTF-8 round-trip through the Argon2id verify path). The `totp` field SHALL be validated for shape — exactly 6 digits, no leading/trailing whitespace silently accepted (whitespace SHALL be rejected via the no-enumeration generic error message). Oversized inputs (>10 MiB per field) SHALL be rejected at the Ktor request-parse layer (Ktor's default `ApplicationCallPipeline.Setup` size limit) without reaching the auth handler. The `errorMessage` rendering context for `login.peb` SHALL pass through Pebble's HTML escape on render — even though the current message is a fixed string, the template SHALL escape it so a future change that interpolates user input cannot inject HTML.

#### Scenario: Unicode email + Unicode password round-trip through Argon2id verify

- **GIVEN** an `admin_users` row exists with `email = 'oka.テスト@nearyou.id'` (Unicode email) and a known Argon2id `password_hash` for the Unicode password `'p@ssword.テスト'`
- **WHEN** the client sends `POST /admin/login` with `email=oka.テスト@nearyou.id&password=p@ssword.テスト&totp=<current-code>`
- **THEN** the response SHALL succeed per the verification requirement (login succeeds)
- **AND** the audit row SHALL record the matched admin's UUID + the Unicode email is NOT recorded plaintext anywhere

#### Scenario: TOTP code with leading whitespace is rejected

- **WHEN** the client sends `POST /admin/login` with valid email + password + `totp=' 123456'` (leading space) or `'123456 '` (trailing space)
- **THEN** the response SHALL be the generic no-enumeration error (matching the wrong-TOTP shape)
- **AND** the audit row SHALL be `admin_login_failure` with `reason = 'totp_mismatch'` (NOT silently stripped to `'123456'`)

#### Scenario: Pebble template escapes the errorMessage variable

- **WHEN** the login template is rendered with `errorMessage = '<script>alert(1)</script>'` (an XSS payload — only happens if a future change interpolates user input)
- **THEN** the rendered HTML SHALL contain the entity-escaped form `&lt;script&gt;alert(1)&lt;/script&gt;`
- **AND** the rendered HTML SHALL NOT contain the raw `<script>` substring

#### Scenario: Oversized email field is rejected without reaching auth handler

- **WHEN** the client sends `POST /admin/login` with `email=<10MB string>` and otherwise-valid fields
- **THEN** the request SHALL be rejected at the request-parse layer (status 413 Payload Too Large OR 400 Bad Request — exact code depends on Ktor's parse-error handling)
- **AND** no `admin_users` SELECT SHALL be executed
- **AND** no `admin_actions_log` row SHALL be written
