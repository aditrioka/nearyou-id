# Users Schema

Defines the canonical `users` and `refresh_tokens` tables introduced by Flyway `V2__auth_foundation.sql`, the 18+ DB-level CHECK constraint, and the conditional creation of the Supabase `realtime.messages` RLS policy when run against a Supabase-shaped Postgres.

See `docs/05-Implementation.md § Users Schema (Canonical)` and `§ Session Management` for column-by-column rationale.

## Requirements

### Requirement: users table created via Flyway V2

A migration `V2__auth_foundation.sql` SHALL create the `users` table with all columns and constraints listed in `docs/05-Implementation.md § Users Schema (Canonical)`, including `id`, `username`, `display_name`, `bio`, `email`, `google_id_hash UNIQUE`, `apple_id_hash UNIQUE`, `apple_relay_email`, `date_of_birth`, `private_profile_opt_in`, `privacy_flip_scheduled_at`, `is_shadow_banned`, `is_banned`, `suspended_until`, `device_fingerprint_hash`, `token_version`, `username_last_changed_at`, `invite_code_prefix UNIQUE`, `analytics_consent` JSONB, `subscription_status`, `inviter_reward_claimed_at`, `created_at`, `deleted_at`, plus the documented indexes.

#### Scenario: Migration runs cleanly
- **WHEN** Flyway runs `V2__auth_foundation.sql` against an empty Postgres
- **THEN** the migration succeeds and `flyway_schema_history` records V2

#### Scenario: All canonical columns present
- **WHEN** querying `information_schema.columns WHERE table_name = 'users'`
- **THEN** the column list matches the canonical set above

### Requirement: 18+ CHECK constraint enforced at DB level

The `users` table MUST include the constraint `CHECK (date_of_birth <= (CURRENT_DATE - INTERVAL '18 years'))` as a defense-in-depth backstop behind the (later) application-layer age gate. The constraint MUST use `CURRENT_DATE` (re-evaluated at INSERT/UPDATE time) — not a precomputed column or a fixed cutoff date — so it stays accurate over time.

#### Scenario: Under-18 insert rejected
- **WHEN** an INSERT supplies a `date_of_birth` less than 18 years before today
- **THEN** the INSERT fails with a check-constraint violation

#### Scenario: Constraint definition uses CURRENT_DATE
- **WHEN** querying `pg_get_constraintdef` for the `users_date_of_birth_check` constraint
- **THEN** the returned text contains `CURRENT_DATE` and `'18 years'` (no fixed date literal)

### Requirement: token_version defaults to 0 and is non-negative

`token_version` MUST default to `0` and MUST be `INT NOT NULL`. The application MAY only ever increment it (never reset).

#### Scenario: Default value
- **WHEN** an INSERT omits `token_version`
- **THEN** the inserted row has `token_version = 0`

### Requirement: refresh_tokens table

The same migration SHALL create the `refresh_tokens` table with columns `id` (UUID PK), `family_id` (UUID NOT NULL), `user_id` (UUID NOT NULL REFERENCES users(id)), `device_fingerprint_hash` (TEXT nullable), `token_hash` (TEXT NOT NULL UNIQUE), `created_at` (TIMESTAMPTZ DEFAULT NOW()), `used_at` (TIMESTAMPTZ nullable), `last_used_at` (TIMESTAMPTZ nullable), `revoked_at` (TIMESTAMPTZ nullable), `expires_at` (TIMESTAMPTZ NOT NULL); plus the documented indexes including the partial `family_active` and `expires` indexes.

#### Scenario: Schema present
- **WHEN** querying `information_schema.columns WHERE table_name = 'refresh_tokens'`
- **THEN** every column above is present with the correct type and nullability

#### Scenario: token_hash uniqueness
- **WHEN** two rows attempt to INSERT with the same `token_hash`
- **THEN** the second INSERT fails with a unique-constraint violation

### Requirement: realtime.messages RLS policy created when schema exists

If the Postgres instance has a `realtime` schema (Supabase), the migration SHALL create the `participants_can_subscribe` policy on `realtime.messages` per `docs/05-Implementation.md § RLS Policy with Regex Guard`. If the schema does not exist (plain Postgres+PostGIS dev image), the migration MUST silently skip this step (no failure). Because Postgres has no `CREATE POLICY IF NOT EXISTS`, the migration MUST use `DROP POLICY IF EXISTS … ; CREATE POLICY …` for idempotency inside the schema-existence guard.

#### Scenario: Plain Postgres
- **WHEN** the migration runs against a plain Postgres+PostGIS instance with no `realtime` schema
- **THEN** the migration succeeds and no error is raised

#### Scenario: Supabase Postgres
- **WHEN** the migration runs against a Supabase instance with the `realtime` schema present
- **THEN** the policy `participants_can_subscribe` exists on `realtime.messages` after the migration

#### Scenario: Policy SQL parses and registers in pg_policies
- **WHEN** the migration runs against a Postgres with a `realtime` schema and `realtime.messages` table present
- **THEN** the migration succeeds (no parse error from the policy DDL) AND `SELECT 1 FROM pg_policies WHERE policyname = 'participants_can_subscribe' AND tablename = 'messages' AND schemaname = 'realtime'` returns a row
