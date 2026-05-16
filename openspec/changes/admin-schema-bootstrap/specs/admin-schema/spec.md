## ADDED Requirements

### Requirement: Migration `V16__admin_users.sql` creates the `admin_users` table

The Flyway migration `V16__admin_users.sql` SHALL create the `admin_users` table with the following column shape, matching [`docs/05-Implementation.md:639-650`](../../../../../docs/05-Implementation.md) verbatim:

```sql
CREATE TABLE admin_users (
    id                     UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    email                  TEXT         NOT NULL UNIQUE,
    display_name           VARCHAR(100) NOT NULL,
    password_hash          TEXT         NOT NULL,                  -- Argon2id
    totp_secret_encrypted  BYTEA,                                  -- AES-256, key in GCP Secret Manager
    webauthn_enrolled      BOOLEAN      NOT NULL DEFAULT FALSE,
    role                   VARCHAR(16)  NOT NULL CHECK (role IN ('owner', 'admin', 'moderator', 'read_only')),
    is_active              BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_login_at          TIMESTAMPTZ
);
```

The column comments documenting `Argon2id` (`password_hash`) and `AES-256, key in GCP Secret Manager` (`totp_secret_encrypted`) MUST be preserved in the migration file via SQL `COMMENT ON COLUMN` statements so that future schema readers see the encoding contract at the schema layer.

#### Scenario: Post-migration column shape matches canonical

- **WHEN** the integration-test Kotest spec queries `information_schema.columns WHERE table_schema = 'public' AND table_name = 'admin_users'` after the V16 migration has applied
- **THEN** all ten columns exist with the data types, nullability, and default values listed above; `id` is the primary key; `email` has a UNIQUE constraint

#### Scenario: role CHECK rejects unsupported values

- **WHEN** an INSERT is attempted with `role = 'superuser'` (a value not in the allowlist `{'owner', 'admin', 'moderator', 'read_only'}`)
- **THEN** Postgres rejects the insert with a `check_violation` (SQLState `23514`) error referencing the `admin_users_role_check` constraint name

#### Scenario: email UNIQUE rejects duplicate inserts

- **WHEN** two INSERTs into `admin_users` are attempted with identical `email` values
- **THEN** the second insert fails with a `unique_violation` (SQLState `23505`) error

#### Scenario: webauthn_enrolled defaults to FALSE

- **WHEN** an INSERT into `admin_users` omits the `webauthn_enrolled` column
- **THEN** the resulting row has `webauthn_enrolled = FALSE`

#### Scenario: is_active defaults to TRUE

- **WHEN** an INSERT into `admin_users` omits the `is_active` column
- **THEN** the resulting row has `is_active = TRUE`

### Requirement: Migration creates `admin_webauthn_credentials` with CASCADE on admin deletion

The migration SHALL create the `admin_webauthn_credentials` table with the column shape matching [`docs/05-Implementation.md:652-661`](../../../../../docs/05-Implementation.md) verbatim:

```sql
CREATE TABLE admin_webauthn_credentials (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    admin_id        UUID        NOT NULL REFERENCES admin_users(id) ON DELETE CASCADE,
    credential_id   BYTEA       NOT NULL UNIQUE,
    public_key      BYTEA       NOT NULL,
    sign_count      BIGINT      NOT NULL DEFAULT 0,
    device_label    TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_used_at    TIMESTAMPTZ
);
```

`admin_id ... ON DELETE CASCADE` is intentional: when an admin user is removed, all their WebAuthn credentials lose meaning and MUST be removed in the same transaction. This contrasts with operational tables (`reports`, `moderation_queue`, `chat_messages`) which use `ON DELETE SET NULL` to preserve audit history.

#### Scenario: Post-migration column shape matches canonical

- **WHEN** the integration-test Kotest spec queries `information_schema.columns WHERE table_name = 'admin_webauthn_credentials'`
- **THEN** all eight columns exist with the data types, nullability, and defaults listed above

#### Scenario: credential_id UNIQUE rejects duplicate WebAuthn keys

- **WHEN** two INSERTs are attempted with identical `credential_id` BYTEA values
- **THEN** the second insert fails with a `unique_violation` error

#### Scenario: Admin deletion CASCADEs to credentials

- **WHEN** a row in `admin_users` is DELETEd while it has N rows in `admin_webauthn_credentials` referencing it
- **THEN** all N credential rows are removed in the same transaction

### Requirement: Migration creates `admin_sessions` with mandatory `csrf_token_hash` and partial-active index

The migration SHALL create the `admin_sessions` table with the column shape matching [`docs/05-Implementation.md:663-677`](../../../../../docs/05-Implementation.md) verbatim:

```sql
CREATE TABLE admin_sessions (
    id                   UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    admin_id             UUID         NOT NULL REFERENCES admin_users(id) ON DELETE CASCADE,
    session_token_hash   TEXT         NOT NULL UNIQUE,
    csrf_token_hash      TEXT         NOT NULL,
    ip                   INET         NOT NULL,
    user_agent           TEXT,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_active_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at           TIMESTAMPTZ  NOT NULL,
    revoked_at           TIMESTAMPTZ
);

CREATE INDEX admin_sessions_admin_idx  ON admin_sessions(admin_id, created_at DESC);
CREATE INDEX admin_sessions_active_idx ON admin_sessions(expires_at) WHERE revoked_at IS NULL;
```

The `csrf_token_hash TEXT NOT NULL` column is the schema-layer counterpart of the `AdminSessionCsrfTokenRule` Detekt invariant declared in [`openspec/project.md`](../../../../../openspec/project.md) § Coding Conventions & CI Lint Rules ("Admin sessions: every `INSERT INTO admin_sessions` must populate `csrf_token_hash`"). Shipping `NOT NULL` at the DB level makes the invariant a defense-in-depth: even a Detekt-allowlist-violating writer is blocked at the schema layer.

The `admin_sessions_active_idx` partial index uses `WHERE revoked_at IS NULL` — an immutable predicate. It MUST NOT be rewritten with `NOW()` or any volatile function in the WHERE clause (per [`openspec/project.md`](../../../../../openspec/project.md) § Coding Conventions: "Partial indexes: no `NOW()` in `WHERE`").

#### Scenario: Post-migration column shape matches canonical

- **WHEN** the integration-test Kotest spec queries `information_schema.columns WHERE table_name = 'admin_sessions'`
- **THEN** all ten columns exist with the data types, nullability, and defaults listed above

#### Scenario: csrf_token_hash NOT NULL rejects NULL inserts

- **WHEN** an INSERT into `admin_sessions` is attempted with `csrf_token_hash` omitted (or explicitly NULL)
- **THEN** Postgres rejects the insert with a `not_null_violation` (SQLState `23502`) error

#### Scenario: session_token_hash UNIQUE prevents duplicate session tokens

- **WHEN** two INSERTs are attempted with identical `session_token_hash` values
- **THEN** the second insert fails with a `unique_violation` error

#### Scenario: Partial-active index exists with the canonical predicate

- **WHEN** the integration-test Kotest spec queries `pg_indexes WHERE indexname = 'admin_sessions_active_idx'`
- **THEN** the returned `indexdef` includes `WHERE (revoked_at IS NULL)` (the immutable partial predicate)

#### Scenario: Both required indexes exist

- **WHEN** the integration-test Kotest spec queries `pg_indexes WHERE schemaname = 'public' AND tablename = 'admin_sessions'`
- **THEN** the result includes index names `admin_sessions_admin_idx` AND `admin_sessions_active_idx` (in addition to the primary-key index)

### Requirement: Migration creates `admin_webauthn_challenges` with ceremony CHECK + consumed-guard partial index

The migration SHALL create the `admin_webauthn_challenges` table with the column shape matching [`docs/05-Implementation.md:679-690`](../../../../../docs/05-Implementation.md) verbatim:

```sql
CREATE TABLE admin_webauthn_challenges (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    admin_id     UUID         REFERENCES admin_users(id) ON DELETE CASCADE,
    challenge    BYTEA        NOT NULL,
    ceremony     VARCHAR(16)  NOT NULL CHECK (ceremony IN ('registration', 'authentication')),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at   TIMESTAMPTZ  NOT NULL,
    consumed_at  TIMESTAMPTZ
);

CREATE INDEX admin_webauthn_challenges_admin_idx   ON admin_webauthn_challenges(admin_id, created_at DESC);
CREATE INDEX admin_webauthn_challenges_cleanup_idx ON admin_webauthn_challenges(expires_at) WHERE consumed_at IS NULL;
```

`admin_id` is intentionally nullable to support the registration ceremony's pre-binding phase (a challenge is created before the registering admin's row is committed; the challenge is bound to the admin upon successful verification). `consumed_at` is the replay-guard: a challenge MUST be marked consumed in the same transaction that verifies it; subsequent verification attempts with `consumed_at IS NOT NULL` MUST be rejected at the application layer.

The `admin_webauthn_challenges_cleanup_idx` partial index uses `WHERE consumed_at IS NULL` — an immutable predicate suitable for the weekly cleanup query (`DELETE FROM admin_webauthn_challenges WHERE expires_at < NOW() - INTERVAL '1 day' AND consumed_at IS NULL`).

#### Scenario: Post-migration column shape matches canonical

- **WHEN** the integration-test Kotest spec queries `information_schema.columns WHERE table_name = 'admin_webauthn_challenges'`
- **THEN** all seven columns exist with the data types, nullability, and defaults listed above; `admin_id` is nullable

#### Scenario: ceremony CHECK rejects unsupported values

- **WHEN** an INSERT is attempted with `ceremony = 'enrollment'` (not in the allowlist)
- **THEN** Postgres rejects the insert with a `check_violation` error referencing the `admin_webauthn_challenges_ceremony_check` constraint name

#### Scenario: Cleanup partial index exists with canonical predicate

- **WHEN** the integration-test Kotest spec queries `pg_indexes WHERE indexname = 'admin_webauthn_challenges_cleanup_idx'`
- **THEN** the returned `indexdef` includes `WHERE (consumed_at IS NULL)`

#### Scenario: admin_id nullability supports pre-binding

- **WHEN** an INSERT into `admin_webauthn_challenges` is attempted with `admin_id` omitted, `challenge` set, `ceremony = 'registration'`, `expires_at = NOW() + INTERVAL '5 minutes'`
- **THEN** the insert succeeds (admin_id NULL is valid for registration pre-binding)

### Requirement: Migration creates `admin_actions_log` as audit-trail-preserving by design

The migration SHALL create the `admin_actions_log` table with the column shape matching [`docs/05-Implementation.md:1189-1205`](../../../../../docs/05-Implementation.md) verbatim:

```sql
CREATE TABLE admin_actions_log (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    admin_id      UUID         NOT NULL REFERENCES admin_users(id),
    action_type   VARCHAR(64)  NOT NULL,
    target_type   VARCHAR(32),
    target_id     TEXT,
    reason        TEXT,
    before_state  JSONB,
    after_state   JSONB,
    ip            INET,
    user_agent    TEXT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX admin_actions_admin_idx  ON admin_actions_log(admin_id, created_at DESC);
CREATE INDEX admin_actions_target_idx ON admin_actions_log(target_type, target_id);
CREATE INDEX admin_actions_type_idx   ON admin_actions_log(action_type, created_at DESC);
```

The FK `admin_id UUID NOT NULL REFERENCES admin_users(id)` ships **without** an explicit `ON DELETE` clause. PostgreSQL's default is `NO ACTION`, which rejects an `admin_users` row delete that would orphan an audit row. This matches the docs (which omit the `ON DELETE` clause for this FK by design) and preserves audit-trail history at the schema layer. Application-layer code MUST treat admin-user deletion as a flag-flip on `is_active = FALSE` rather than a row DELETE so that historical audit rows remain referentially valid; the design decision is documented in `design.md` D3.

Immutability of `admin_actions_log` is enforced **at the DB role level** (UPDATE and DELETE revoked from the `admin_app` role per [`docs/05-Implementation.md:1208`](../../../../../docs/05-Implementation.md) and [`docs/07-Operations.md:27`](../../../../../docs/07-Operations.md) § Data Access Pattern). The role-level REVOKE statements are **out of scope** for this migration — they are provisioned in Supabase Console per [`docs/08-Roadmap-Risk.md:38`](../../../../../docs/08-Roadmap-Risk.md) Pre-Phase 1 #28 and applied operationally, not through Flyway. See `design.md` D4 for the rationale.

#### Scenario: Post-migration column shape matches canonical

- **WHEN** the integration-test Kotest spec queries `information_schema.columns WHERE table_name = 'admin_actions_log'`
- **THEN** all eleven columns exist with the data types, nullability, and defaults listed above

#### Scenario: All three secondary indexes exist

- **WHEN** the integration-test Kotest spec queries `pg_indexes WHERE schemaname = 'public' AND tablename = 'admin_actions_log'`
- **THEN** the result includes index names `admin_actions_admin_idx`, `admin_actions_target_idx`, AND `admin_actions_type_idx` (in addition to the primary-key index)

#### Scenario: admin_id NOT NULL rejects unowned audit rows

- **WHEN** an INSERT into `admin_actions_log` is attempted with `admin_id` omitted (or explicitly NULL)
- **THEN** Postgres rejects the insert with a `not_null_violation` error

#### Scenario: NO ACTION default rejects orphaning admin deletion

- **GIVEN** an `admin_users` row exists AND an `admin_actions_log` row exists referencing that admin
- **WHEN** an attempt is made to DELETE the `admin_users` row
- **THEN** the DELETE fails with a `foreign_key_violation` (SQLState `23503`) error, preserving the audit row

### Requirement: Migration backfills three deferred operational-table FKs to `admin_users(id) ON DELETE SET NULL`

The migration SHALL convert the three nullable UUID columns currently annotated "deferred to the Phase 3.5 admin-users migration" into validated foreign keys via `ADD CONSTRAINT … NOT VALID + VALIDATE CONSTRAINT`:

1. `reports.reviewed_by` ([V9:71-73](../../../../../backend/ktor/src/main/resources/db/migration/V9__reports_moderation.sql))
2. `moderation_queue.resolved_by` ([V9:108-111](../../../../../backend/ktor/src/main/resources/db/migration/V9__reports_moderation.sql))
3. `chat_messages.redacted_by` ([V15:96-99](../../../../../backend/ktor/src/main/resources/db/migration/V15__chat_foundation.sql))

Each FK MUST use `ON DELETE SET NULL` (per the [`openspec/project.md`](../../../../../openspec/project.md) § Coding Conventions invariant: "Admin-user FKs on operational tables must use `ON DELETE SET NULL`"). The `NOT VALID + VALIDATE CONSTRAINT` pattern is chosen over plain `ADD CONSTRAINT` per the V9 deferral comment's prescription — even though all rows are NULL today and validation is constant-time, the pattern matters for future operational consistency when these columns carry data.

For each constraint, the migration SHALL also delete the obsolete SQL `COMMENT` text marking the column as "deferred to the Phase 3.5 admin-users migration" by issuing a fresh `COMMENT ON COLUMN` statement that describes the now-shipped FK relationship (e.g., `'FK to admin_users(id) ON DELETE SET NULL — set NULL when the reviewing admin's row is deleted.'`).

#### Scenario: reports.reviewed_by FK exists post-migration

- **WHEN** the integration-test Kotest spec queries `information_schema.table_constraints` for `reports` with `constraint_type = 'FOREIGN KEY'`
- **THEN** a constraint exists referencing `admin_users(id)` on column `reviewed_by` with `ON DELETE SET NULL` AND its `is_validated` (or equivalent) status is true

#### Scenario: moderation_queue.resolved_by FK exists post-migration

- **WHEN** the integration-test Kotest spec queries `information_schema.table_constraints` for `moderation_queue`
- **THEN** a FK constraint exists on `resolved_by` referencing `admin_users(id)` with `ON DELETE SET NULL`, fully validated

#### Scenario: chat_messages.redacted_by FK exists post-migration

- **WHEN** the integration-test Kotest spec queries `information_schema.table_constraints` for `chat_messages`
- **THEN** a FK constraint exists on `redacted_by` referencing `admin_users(id)` with `ON DELETE SET NULL`, fully validated

#### Scenario: Admin deletion sets reports.reviewed_by to NULL

- **GIVEN** an `admin_users` row exists AND a `reports` row exists with `reviewed_by` referencing that admin
- **WHEN** the `admin_users` row is removed via the application-layer soft-delete path (i.e., `is_active = FALSE` flag; the schema-level FK rejection demonstrated above means actual DELETE is blocked by the `admin_actions_log` FK)
- **THEN** the `reports.reviewed_by` is NOT affected (the `is_active = FALSE` is an application-layer flag and does not trigger SET NULL); this scenario documents the behavioral contract — the SET NULL clause activates only on a true row DELETE (e.g., a separate test path that temporarily removes the `admin_actions_log` constraint, or a future hard-delete scenario)

#### Scenario: Deferred-comment text is replaced

- **WHEN** the integration-test Kotest spec queries the column comment (`pg_description` joined to `pg_attribute`) on `reports.reviewed_by`
- **THEN** the comment text does NOT contain the substring `'deferred to the Phase 3.5 admin-users migration'` AND describes the now-shipped FK

### Requirement: `admin_app` role REVOKE / GRANT statements are explicitly out of scope

The migration `V16__admin_users.sql` SHALL NOT include `GRANT` or `REVOKE` statements targeting the `admin_app` Postgres role. Role-level access control for the admin tables — including the `REVOKE UPDATE, DELETE ON admin_actions_log FROM admin_app` enforcement of audit-row immutability — is provisioned operationally in Supabase Console per [`docs/08-Roadmap-Risk.md:38`](../../../../../docs/08-Roadmap-Risk.md) Pre-Phase 1 #28 ("Scoped `admin_app` DB role created in Supabase ..."). The migration is environment-portable: it MUST apply cleanly against the integration-test Postgres (where `admin_app` does NOT exist) and against staging/production Supabase Postgres (where `admin_app` is pre-provisioned).

Documentation of which `admin_app` REVOKE/GRANT statements are required lands in [`docs/07-Operations.md`](../../../../../docs/07-Operations.md) § Data Access Pattern (already present). A new operational runbook step in the Admin #2 (`admin-panel-ktor-htmx-bootstrap`) lifecycle will codify the Supabase Console / `gcloud` procedure for applying these REVOKEs idempotently against each environment.

#### Scenario: Migration applies cleanly without `admin_app` role

- **WHEN** the integration-test Postgres (which does not provision `admin_app`) executes the V16 migration via Flyway
- **THEN** the migration completes successfully without error AND no `GRANT` or `REVOKE` statement appears in the migration file (verifiable via `grep -i 'grant\|revoke' V16__admin_users.sql` returning empty)

### Requirement: Sentinel `system` admin user seed is explicitly out of scope

The migration SHALL NOT INSERT any rows into `admin_users`. The sentinel `system` admin user row required by the `suspension-unban-worker-audit-log-after-phase-3.5` follow-up — needed to satisfy the `admin_actions_log.admin_id NOT NULL` FK for worker-emitted audit rows — is deferred to the dedicated follow-up change `system-actor-and-worker-audit-rows`. That follow-up will resolve the design tension between `admin_users.password_hash NOT NULL` and the FOLLOW_UPS entry's "no `password_hash`" sentinel description, plus add the auth-bypass guard CHECK constraint or query-level safeguard.

#### Scenario: No data seeding in V16

- **WHEN** the V16 migration is applied to a fresh Postgres
- **THEN** `SELECT count(*) FROM admin_users` returns `0` AND `SELECT count(*) FROM admin_actions_log` returns `0`

### Requirement: RLS policies on admin tables are explicitly out of scope

The migration SHALL NOT `ALTER TABLE ... ENABLE ROW LEVEL SECURITY` on any admin table. Row-level security for admin tables requires the admin-session identity claim to be readable from `request.jwt.claims` (or analogous Supabase-style session context), which does NOT exist until Admin #2 (`admin-panel-ktor-htmx-bootstrap`) wires session middleware. Enabling RLS now without a configured identity source would effectively deny-all admin-table access, blocking the Admin #2 implementation and any future Flyway-managed schema reads. The canonical defenses for the pre-Admin-#2 window are (a) the dedicated `admin_app` DB role with scoped GRANTs (out of scope per the prior requirement), and (b) network-layer IAP (per [`docs/07-Operations.md`](../../../../../docs/07-Operations.md) § Security Layer 1).

A follow-up change accompanying or following Admin #2 / Admin #3 will land RLS policies on the admin tables once an identity source exists.

#### Scenario: No RLS enablement in V16

- **WHEN** the integration-test Kotest spec queries `pg_class c JOIN pg_namespace n` for the five admin tables with `relrowsecurity` flag
- **THEN** `relrowsecurity` is FALSE for `admin_users`, `admin_webauthn_credentials`, `admin_sessions`, `admin_webauthn_challenges`, AND `admin_actions_log`
