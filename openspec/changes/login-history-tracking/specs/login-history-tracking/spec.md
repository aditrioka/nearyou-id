## ADDED Requirements

### Requirement: login_events schema

A Flyway migration (the next free version, **V34**) SHALL create the `login_events` table — an append-only, per-user, security-purpose record of authenticated sign-in and refresh events — with:

- `id UUID PRIMARY KEY DEFAULT gen_random_uuid()`
- `user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE`
- `occurred_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`
- `event_type VARCHAR(16) NOT NULL CHECK (event_type IN ('signin', 'refresh'))`
- `ip INET` (nullable — `call.clientIp` always resolves a value, but the column tolerates an absent/unparseable address)
- `ip_subnet_24 INET GENERATED ALWAYS AS (network(set_masklen(ip, 24))) STORED` (the /24 network derived from `ip`; NULL when `ip` is NULL)
- `device_fingerprint_hash TEXT` (the client-supplied fingerprint hash, as carried on the auth request)
- `identifier_hash TEXT` (the one-way SHA-256 of the provider subject — never the raw Google/Apple identifier)

It SHALL create a `(user_id, occurred_at DESC)` btree index serving every per-user windowed read (the engagement legs and the per-inviter anti-collision lookups). No index on this table SHALL use a partial `WHERE` predicate containing `NOW()` or any other volatile expression (the partial-index immutability invariant).

#### Scenario: Table exists with the declared columns and constraints
- **WHEN** the migration set is applied and `login_events` is inspected
- **THEN** the table exists with `id, user_id, occurred_at, event_type, ip, ip_subnet_24, device_fingerprint_hash, identifier_hash` AND `event_type` carries a CHECK limiting it to `('signin', 'refresh')` AND `user_id` has an `ON DELETE CASCADE` FK to `users(id)`

#### Scenario: The /24 subnet is generated from the IP
- **WHEN** a row is inserted with `ip = '203.0.113.45'`
- **THEN** `ip_subnet_24` is `'203.0.113.0/24'` (the generated column masks the host octet) AND a row inserted with `ip = NULL` has `ip_subnet_24 = NULL`

#### Scenario: An invalid event_type is rejected
- **WHEN** a `login_events` row is inserted with `event_type = 'logout'` (or any value outside the CHECK set)
- **THEN** the CHECK constraint rejects the insert

#### Scenario: A user hard-delete cascades the FK
- **WHEN** a `users` row referenced by `login_events` is row-deleted
- **THEN** that user's `login_events` rows are removed by the `ON DELETE CASCADE` FK (the explicit tombstone-path delete is owned by `account-hard-delete-worker`, since the worker tombstones rather than row-deletes the user)

### Requirement: Authenticated sign-in is recorded as a login event

On a successful `POST /api/v1/auth/signin` (a verified provider token resolving to a non-banned account that is issued a token pair), the system SHALL record one `login_events` row with `event_type = 'signin'`, `user_id` = the signed-in user, `ip` = `call.clientIp`, `device_fingerprint_hash` = the request's `device_fingerprint_hash` (nullable), and `identifier_hash` = the SHA-256 of the verified provider subject (the same `subHash` used for account lookup). The IP MUST be read via the `call.clientIp` request-context accessor — never a raw `X-Forwarded-For` header (the client-IP invariant). The recorder SHALL NOT persist the raw provider subject or any reversible identifier.

#### Scenario: A successful sign-in writes a signin event
- **WHEN** a user signs in successfully and is issued a token pair
- **THEN** exactly one `login_events` row exists for that user with `event_type = 'signin'`, the request's client IP, the supplied device-fingerprint hash, and the hashed provider identifier

#### Scenario: A rejected sign-in writes no event
- **WHEN** a sign-in is rejected before a token pair is issued (unknown account `404`, banned account `403`, or invalid id-token `401`)
- **THEN** no `login_events` row is written for that attempt

#### Scenario: The recorded IP comes from the client-IP accessor
- **WHEN** a sign-in is recorded
- **THEN** the stored `ip` is the value of `call.clientIp` (the sanctioned request-context accessor), not a value read directly from an `X-Forwarded-For` header

### Requirement: Token refresh is recorded as a login event

On a successful `POST /api/v1/auth/refresh` (a valid, non-reused refresh token that rotates and mints a fresh access token for a non-banned, non-deleted account), the system SHALL record one `login_events` row with `event_type = 'refresh'`, `user_id` = the rotating user, `ip` = `call.clientIp`, `device_fingerprint_hash` = the request's `device_fingerprint_hash`, and `identifier_hash` = the account's stored provider-identifier hash (`google_id_hash` or `apple_id_hash`). A refresh that is denied (token reuse, invalid/expired token, banned or soft-deleted account) SHALL write no event.

#### Scenario: A successful refresh writes a refresh event
- **WHEN** a refresh token is rotated successfully and a fresh access token is issued
- **THEN** exactly one `login_events` row exists for that user with `event_type = 'refresh'` and the request's client IP

#### Scenario: A denied refresh writes no event
- **WHEN** a refresh is rejected (token reuse, invalid/expired token, or a banned/soft-deleted account)
- **THEN** no `login_events` row is written

### Requirement: The login-event write is best-effort and never breaks authentication

Recording a login event is a non-critical side effect: if the `login_events` insert fails for any reason, the recorder SHALL swallow the error (logging it) and the sign-in / refresh response SHALL be unaffected. A login-history write failure MUST NOT prevent a token pair from being returned, mirroring the fail-soft posture of other non-critical auth-adjacent writes.

#### Scenario: A failed login-event insert does not fail sign-in
- **WHEN** the `login_events` insert throws during an otherwise-successful sign-in
- **THEN** the sign-in still responds `200` with a valid token pair AND the failure is logged (not surfaced to the client)

### Requirement: Login-history is security-purpose data exempt from analytics consent

`login_events` is collected for **all** authenticated users as essential account-security and anti-abuse data processed under a legitimate-interest basis (UU-PDP), NOT as product analytics. Its collection SHALL NOT be gated by the `users.analytics_consent.analytics` toggle (which governs Amplitude / product analytics only), and the recorder SHALL NOT read that consent flag before writing. It deliberately replaces the consent-gated client `session_start` analytics event as the source for the referral app-sessions signal. The data's processing purpose (account security + referral anti-abuse), its 90-day retention, and its presence in the data export are disclosed to the user per the privacy-policy / consent-flow obligations in `docs/06`.

#### Scenario: Login events are recorded regardless of the analytics-consent toggle
- **WHEN** a user with `analytics_consent.analytics = false` signs in or refreshes successfully
- **THEN** the `login_events` row is still written (security-purpose collection is not gated by the analytics opt-in)

### Requirement: Login-history participates in the PII lifecycle (retention, export, deletion)

Because `login_events` stores personal data (login time, IP, device-fingerprint hash, identifier hash), it SHALL be a first-class member of the UU-PDP data lifecycle: a 90-day auto-purge (the canonical "Session trail" retention window, enforced by `scheduled-retention-cleanup`), inclusion in the user's personal-data export (the "Session history" category, enforced by `account-data-export`), and removal on account hard-delete (enforced by `account-hard-delete-worker`). The detailed enforcement scenarios live in those capabilities' specs (modified by this change); this requirement establishes that `login_events` is in scope for all three and MUST NOT be omitted from any of them.

#### Scenario: login_events is in retention, export, and deletion scope
- **WHEN** the login-history PII lifecycle is audited
- **THEN** `login_events` is covered by a retention sweep (90-day), by the data-export scope (session history), and by the account hard-delete cascade — none of the three omits it
