## ADDED Requirements

### Requirement: reserved_usernames table created via Flyway V3

A migration `V3__signup_flow.sql` SHALL create the `reserved_usernames` table per `docs/05-Implementation.md § Reserved Usernames Schema`: columns `username TEXT PRIMARY KEY`, `reason VARCHAR(64) NOT NULL`, `source VARCHAR(16) NOT NULL DEFAULT 'admin_added' CHECK (source IN ('seed_system','admin_added'))`, `created_at TIMESTAMPTZ DEFAULT NOW()`, `updated_at TIMESTAMPTZ DEFAULT NOW()`; plus the index `reserved_usernames_source_idx ON reserved_usernames(source)`; plus the trigger functions `reserved_usernames_set_updated_at` (BEFORE UPDATE) and `reserved_usernames_protect_seed` (BEFORE UPDATE OR DELETE).

#### Scenario: Migration creates table and triggers
- **WHEN** Flyway runs `V3__signup_flow.sql` against a DB at V2
- **THEN** `\dt public.reserved_usernames` exists AND the two trigger functions are listed in `pg_proc`

#### Scenario: Seed row deletion blocked
- **WHEN** an admin attempts `DELETE FROM reserved_usernames WHERE source = 'seed_system'`
- **THEN** the DELETE fails with the protect-seed trigger exception

### Requirement: reserved_usernames seed insert

The migration SHALL insert, with `source = 'seed_system'` and `ON CONFLICT (username) DO NOTHING`, at minimum these rows: `admin`, `support`, `moderator`, `system`, `nearyou`, `staff`, `official`, `akun_dihapus`, `deleted_user`, plus every 1- and 2-character string drawn from the alphabet `[a-z0-9]`. Each seed row MUST have a non-empty `reason` string.

#### Scenario: Seed list present
- **WHEN** the migration completes
- **THEN** `SELECT COUNT(*) FROM reserved_usernames WHERE source = 'seed_system'` ≥ (9 documented + 36 single-char + 36×36 two-char) = 1341

#### Scenario: Two-char combinations exhaustive
- **WHEN** the migration completes
- **THEN** for any `a,b ∈ [a-z0-9]`, `SELECT 1 FROM reserved_usernames WHERE username = a||b` returns a row

### Requirement: rejected_identifiers table created via Flyway V3

The same migration SHALL create `rejected_identifiers` per `docs/05-Implementation.md § Rejected Identifiers Schema`: columns `id UUID PK DEFAULT gen_random_uuid()`, `identifier_hash TEXT NOT NULL`, `identifier_type VARCHAR(8) NOT NULL CHECK (identifier_type IN ('google','apple'))`, `reason VARCHAR(32) NOT NULL CHECK (reason IN ('age_under_18','attestation_persistent_fail'))`, `rejected_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`, `UNIQUE (identifier_hash, identifier_type)`; plus the index `rejected_identifiers_hash_idx ON rejected_identifiers(identifier_hash)`.

#### Scenario: Schema present
- **WHEN** querying `information_schema.columns WHERE table_name = 'rejected_identifiers'`
- **THEN** each column above is present with the correct type and nullability

#### Scenario: Reason CHECK rejects unknown value
- **WHEN** an INSERT supplies `reason = 'other'`
- **THEN** the INSERT fails with a check-constraint violation

#### Scenario: Unique pair prevents duplicate
- **WHEN** two INSERTs share the same `(identifier_hash, identifier_type)`
- **THEN** the second INSERT fails with a unique-constraint violation

### Requirement: username_history table created via Flyway V3

The migration SHALL create `username_history` per `docs/05-Implementation.md § Username History Schema`: columns `id UUID PK DEFAULT gen_random_uuid()`, `user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE`, `old_username VARCHAR(60) NOT NULL`, `new_username VARCHAR(60) NOT NULL`, `changed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`, `released_at TIMESTAMPTZ NOT NULL`; plus three indexes: `username_history_old_lower_idx ON username_history(LOWER(old_username))`, `username_history_released_idx ON username_history(released_at)` (plain B-tree, NOT partial), and `username_history_user_idx ON username_history(user_id, changed_at DESC)`.

#### Scenario: Plain B-tree on released_at
- **WHEN** querying `pg_indexes` for `username_history_released_idx`
- **THEN** the returned definition contains `btree (released_at)` AND does NOT contain `WHERE`

#### Scenario: FK cascade on user deletion
- **WHEN** a row in `users` is hard-deleted
- **THEN** all `username_history` rows referencing that user are cascade-deleted

### Requirement: V3 schema-only for username_history

This change MUST NOT introduce any INSERT into `username_history`. The table is schema-only until the Premium-username-customization change lands. Signup MUST NOT write to it.

#### Scenario: No writes during signup
- **WHEN** a signup completes successfully
- **THEN** `SELECT COUNT(*) FROM username_history` remains 0

## MODIFIED Requirements

### Requirement: users table created via Flyway V2

A migration `V2__auth_foundation.sql` SHALL create the `users` table with all columns and constraints listed in `docs/05-Implementation.md § Users Schema (Canonical)`, including `id`, `username`, `display_name`, `bio`, `email`, `google_id_hash UNIQUE`, `apple_id_hash UNIQUE`, `apple_relay_email`, `date_of_birth`, `private_profile_opt_in`, `privacy_flip_scheduled_at`, `is_shadow_banned`, `is_banned`, `suspended_until`, `device_fingerprint_hash`, `token_version`, `username_last_changed_at`, `invite_code_prefix UNIQUE`, `analytics_consent` JSONB, `subscription_status`, `inviter_reward_claimed_at`, `created_at`, `deleted_at`, plus the documented indexes. As of V3, the `invite_code_prefix` column MUST be populated for every new row via `base32(HMAC-SHA256(invite-code-secret, user_id.bytes))[0..8]` at INSERT time (see auth-signup + username-generation capabilities); historical (pre-V3) seeded rows MUST be backfilled with a deterministic or random-but-unique prefix as part of the V3 migration or its accompanying dev-seed update.

#### Scenario: Migration runs cleanly
- **WHEN** Flyway runs `V2__auth_foundation.sql` against an empty Postgres
- **THEN** the migration succeeds and `flyway_schema_history` records V2

#### Scenario: All canonical columns present
- **WHEN** querying `information_schema.columns WHERE table_name = 'users'`
- **THEN** the column list matches the canonical set above

#### Scenario: invite_code_prefix populated after V3
- **WHEN** V3 has run AND any `users` row exists (seeded or created via signup)
- **THEN** that row's `invite_code_prefix` is non-null AND respects the UNIQUE constraint
