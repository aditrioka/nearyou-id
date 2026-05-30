## ADDED Requirements

### Requirement: A deterministic `system` sentinel admin user is seeded

The system SHALL seed exactly one `admin_users` row — the `system` sentinel — so that machine-initiated (worker / scheduled-job) `admin_actions_log` rows have a stable, non-human actor to attribute to (`admin_actions_log.admin_id` is `NOT NULL REFERENCES admin_users(id)`, so a real row is required).

The seeded row SHALL have:
- `id` = `54b53072-540e-3eb8-b8e9-343e71f28176`, the deterministic value of `UUID.nameUUIDFromBytes("system".toByteArray())`
- `email` = `system@system.nearyou.invalid` (a non-routable reserved-TLD address)
- `display_name` = `System Actor`
- `role` = `read_only` (least privilege; `role` is `NOT NULL CHECK (role IN ('owner','admin','moderator','read_only'))`)
- `is_active` = `FALSE`
- `password_hash` = a fixed non-PHC literal that is not a valid Argon2 hash string (so no password can ever verify against it)
- `totp_secret_encrypted` = NULL, `webauthn_enrolled` = FALSE

The seed SHALL be idempotent: re-applying it MUST NOT create a duplicate row or raise an error.

#### Scenario: Sentinel row exists with the deterministic UUID after migration
- **WHEN** the V18 migration has been applied to a database that already has the V16 `admin_users` table
- **THEN** exactly one `admin_users` row exists with `id = '54b53072-540e-3eb8-b8e9-343e71f28176'`, `is_active = FALSE`, `role = 'read_only'`, AND `email = 'system@system.nearyou.invalid'`

#### Scenario: Migration UUID literal equals the application constant
- **WHEN** the application constant `SYSTEM_ACTOR_ID` is compared against `UUID.nameUUIDFromBytes("system".toByteArray())`
- **THEN** both equal `54b53072-540e-3eb8-b8e9-343e71f28176`, matching the V18 migration literal (so the migration and the worker code provably reference the same row)

#### Scenario: Seed is idempotent
- **WHEN** the sentinel seed statement is executed a second time against a database that already contains the sentinel row
- **THEN** no duplicate row is created AND no error is raised (exactly one sentinel row remains)

### Requirement: The `system` sentinel cannot authenticate

The `system` sentinel SHALL NOT be able to authenticate through any admin login path. Because it is seeded `is_active = FALSE`, the admin-login active-user lookup (`WHERE email = ? AND is_active = TRUE`) MUST NOT return it, so its `password_hash` is never loaded for verification. A login attempt using the sentinel's email MUST resolve to the standard no-enumeration failure response — indistinguishable from an unknown email — and MUST NOT create a session.

#### Scenario: Login with the sentinel email fails with the no-enumeration response
- **WHEN** `POST /admin/login` is invoked with `email = 'system@system.nearyou.invalid'` and any password
- **THEN** the response is the standard no-enumeration login failure AND no `admin_sessions` row is created for the sentinel

#### Scenario: The sentinel password hash can never verify
- **WHEN** `PasswordHasher.verify(plaintext, <the sentinel password_hash literal>)` is called with any `plaintext`
- **THEN** the result is `false` (the stored value is not a valid Argon2 PHC string, so no candidate password matches) — a defense-in-depth guarantee independent of the `is_active` gate

### Requirement: The `system` sentinel is the attribution actor for machine-initiated audit rows

Machine-initiated (worker / scheduled-job) `admin_actions_log` rows SHALL set `admin_id` to the `system` sentinel's UUID. Human-initiated admin actions continue to attribute to the acting admin's own `admin_users.id`. Because `admin_actions_log.admin_id` is `NOT NULL REFERENCES admin_users(id)` with no `ON DELETE` action, the sentinel row MUST NOT be hard-deletable while it owns any `admin_actions_log` rows.

#### Scenario: Worker-written audit row attributes to the sentinel
- **WHEN** a scheduled worker writes an `admin_actions_log` row for a machine action
- **THEN** that row's `admin_id` equals the `system` sentinel UUID AND the foreign key resolves to the seeded sentinel row

#### Scenario: Sentinel cannot be hard-deleted while it owns audit rows
- **WHEN** the sentinel owns at least one `admin_actions_log` row AND a `DELETE FROM admin_users WHERE id = '54b53072-540e-3eb8-b8e9-343e71f28176'` is attempted
- **THEN** the delete is rejected by the `admin_actions_log.admin_id` foreign key (no `ON DELETE` action), preserving the audit trail
