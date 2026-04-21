# Users Schema

## Purpose

Defines the canonical `users` and `refresh_tokens` tables introduced by Flyway `V2__auth_foundation.sql`, the 18+ DB-level CHECK constraint, and the conditional creation of the Supabase `realtime.messages` RLS policy when run against a Supabase-shaped Postgres.

See `docs/05-Implementation.md § Users Schema (Canonical)` and `§ Session Management` for column-by-column rationale.
## Requirements
### Requirement: users table created via Flyway V2

A migration `V2__auth_foundation.sql` SHALL create the `users` table with all columns and constraints listed in `docs/05-Implementation.md § Users Schema (Canonical)`, including `id`, `username`, `display_name`, `bio`, `email`, `google_id_hash UNIQUE`, `apple_id_hash UNIQUE`, `apple_relay_email`, `date_of_birth`, `private_profile_opt_in`, `privacy_flip_scheduled_at`, `is_shadow_banned`, `is_banned`, `suspended_until`, `device_fingerprint_hash`, `token_version`, `username_last_changed_at`, `invite_code_prefix UNIQUE`, `analytics_consent` JSONB, `subscription_status`, `inviter_reward_claimed_at`, `created_at`, `deleted_at`, plus the documented indexes. As of V3, the `invite_code_prefix` column MUST be populated for every new row via `base32(HMAC-SHA256(invite-code-secret, user_id.bytes))[0..8]` at INSERT time (see auth-signup + username-generation capabilities); historical (pre-V3) seeded rows MUST be backfilled with a deterministic or random-but-unique prefix as part of the V3 migration or its accompanying dev-seed update. As of V4, `users.id` is referenced by `posts.author_id` with `ON DELETE RESTRICT`; a bare `DELETE FROM users` fails while any `posts` row references that user (see `Author RESTRICT prevents premature user delete` above).

#### Scenario: Migration runs cleanly
- **WHEN** Flyway runs `V2__auth_foundation.sql` against an empty Postgres
- **THEN** the migration succeeds and `flyway_schema_history` records V2

#### Scenario: All canonical columns present
- **WHEN** querying `information_schema.columns WHERE table_name = 'users'`
- **THEN** the column list matches the canonical set above

#### Scenario: invite_code_prefix populated after V3
- **WHEN** V3 has run AND any `users` row exists (seeded or created via signup)
- **THEN** that row's `invite_code_prefix` is non-null AND respects the UNIQUE constraint

#### Scenario: User hard-delete blocked while posts exist (post-V4)
- **WHEN** V4 has run AND a `users` row has at least one `posts` row AND a bare `DELETE FROM users WHERE id = ?` is attempted
- **THEN** the DELETE fails with a foreign-key violation (SQLSTATE `23503`)

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

### Requirement: posts table created via Flyway V4

A migration `V4__post_creation.sql` SHALL create the `posts` table verbatim from `docs/05-Implementation.md § Posts Schema` with columns:
- `id UUID PRIMARY KEY DEFAULT gen_random_uuid()`
- `author_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT` (the tombstone / hard-delete worker is responsible for deleting posts before their author; `RESTRICT` is the documented guard)
- `content VARCHAR(280) NOT NULL`
- `display_location GEOGRAPHY(POINT, 4326) NOT NULL`
- `actual_location GEOGRAPHY(POINT, 4326) NOT NULL`
- `city_name TEXT` (nullable; filled by reverse-geocoding, separate change)
- `city_match_type VARCHAR(16)` (nullable; filled by reverse-geocoding, separate change)
- `image_id TEXT` (nullable; filled by the image-upload change)
- `is_auto_hidden BOOLEAN NOT NULL DEFAULT FALSE`
- `created_at TIMESTAMPTZ DEFAULT NOW()`
- `updated_at TIMESTAMPTZ` (nullable; written by the post-edit change)
- `deleted_at TIMESTAMPTZ` (nullable; written by the soft-delete change)

FTS-specific columns (`content_tsv`) are explicitly excluded from V4 and deferred to the Search change.

#### Scenario: Migration runs cleanly from V3
- **WHEN** Flyway runs `V4__post_creation.sql` against a DB at V3
- **THEN** the migration succeeds AND `flyway_schema_history` records V4

#### Scenario: All canonical columns present
- **WHEN** querying `information_schema.columns WHERE table_name = 'posts'`
- **THEN** every column above is present with its documented type and nullability

### Requirement: Post indexes created via Flyway V4

The same migration SHALL create:
- `posts_display_location_idx` — GIST on `display_location`
- `posts_actual_location_idx` — GIST on `actual_location`
- `posts_timeline_cursor_idx` — btree composite on `(created_at DESC, id DESC)` with partial predicate `WHERE deleted_at IS NULL` (docs/05 § Posts Schema; `deleted_at` column lands in this migration)
- `posts_nearby_cursor_idx` — GIST composite on `(display_location, created_at)` with partial predicate `WHERE deleted_at IS NULL` (requires the `btree_gist` extension for composite GIST on a geography + timestamp pair)

All four MUST be created atomically in V4 so the Timeline change that follows does not require an ALTER.

#### Scenario: All four indexes exist
- **WHEN** querying `pg_indexes WHERE tablename = 'posts'`
- **THEN** the result contains `posts_display_location_idx`, `posts_actual_location_idx`, `posts_timeline_cursor_idx`, and `posts_nearby_cursor_idx`

#### Scenario: display_location index is GIST
- **WHEN** reading the definition of `posts_display_location_idx`
- **THEN** the definition specifies `USING gist (display_location)`

### Requirement: Author RESTRICT prevents premature user delete

The `author_id` foreign key to `users(id)` MUST use `ON DELETE RESTRICT` per `docs/05-Implementation.md § Posts Schema`. The tombstone + hard-delete worker (deferred to a separate change) is responsible for deleting posts *before* the author row; `RESTRICT` is the DB-level guard against a bare `DELETE FROM users` leaving orphaned post rows or losing moderation state.

#### Scenario: Hard-delete blocked while posts exist
- **WHEN** a `users` row still has at least one `posts` row referencing it via `author_id` AND a direct `DELETE FROM users WHERE id = ?` is attempted
- **THEN** the DELETE fails with a foreign-key violation (SQLSTATE `23503`)

#### Scenario: Hard-delete succeeds after posts removed
- **WHEN** all `posts` rows for an `author_id` are deleted first AND `DELETE FROM users WHERE id = ?` is then attempted
- **THEN** the user row is deleted successfully

### Requirement: No FTS / content_tsv in V4

V4 MUST NOT introduce `content_tsv` generated column or any `GIN` full-text index on `posts`. FTS is deferred to the Search change.

#### Scenario: No content_tsv column
- **WHEN** querying `information_schema.columns WHERE table_name = 'posts' AND column_name = 'content_tsv'`
- **THEN** zero rows are returned

### Requirement: user_blocks table referenced from users via V5

As of V5, `users.id` MUST be referenced TWICE by `user_blocks` (once as `blocker_id`, once as `blocked_id`), each with `ON DELETE CASCADE`. A hard-delete of a `users` row SHALL therefore cascade to remove all outbound and inbound block rows for that user. The `posts.author_id RESTRICT` invariant from V4 still applies; the V5 cascade does not relax it (a user with posts still cannot be hard-deleted directly — posts must be removed first by the tombstone worker).

#### Scenario: V5 adds two FKs from user_blocks to users
- **WHEN** querying `information_schema.table_constraints WHERE table_name = 'user_blocks' AND constraint_type = 'FOREIGN KEY'`
- **THEN** there are exactly two FK rows referencing `users(id)` (one for `blocker_id`, one for `blocked_id`)

#### Scenario: Cascade removes block rows for hard-deleted user (both directions)
- **WHEN** a user A has `user_blocks` rows `(A, X)` and `(Y, A)` AND A has no `posts` rows AND `DELETE FROM users WHERE id = A` is executed
- **THEN** the DELETE succeeds AND `SELECT 1 FROM user_blocks WHERE blocker_id = A OR blocked_id = A` returns zero rows

#### Scenario: V4 RESTRICT still applies after V5
- **WHEN** a user has at least one `posts` row AND a direct `DELETE FROM users WHERE id = ?` is attempted
- **THEN** the DELETE fails with a foreign-key violation (V5 cascade does NOT override V4 RESTRICT)

### Requirement: follows table added via Flyway V6

V6 SHALL introduce the `follows` table referencing `users(id)` on both sides (`follower_id`, `followee_id`) with `ON DELETE CASCADE`. A `users` hard-delete MUST cascade-remove every `follows` row where the deleted user appears on either side. See the `follow-system` capability for the full table definition, indexes, and constraints.

#### Scenario: follows cascade on users delete — follower side
- **WHEN** a `users` row referenced by `follows.follower_id` is hard-deleted
- **THEN** all matching `follows` rows are cascade-deleted AND no orphan row remains

#### Scenario: follows cascade on users delete — followee side
- **WHEN** a `users` row referenced by `follows.followee_id` is hard-deleted
- **THEN** all matching `follows` rows are cascade-deleted AND no orphan row remains

#### Scenario: users hard-delete with both follows and user_blocks rows
- **WHEN** a `users` row has `follows` rows (both as follower and as followee) AND `user_blocks` rows (both as blocker and as blocked) AND the row is hard-deleted at the DB level (no `posts` rows referencing it)
- **THEN** every matching `follows` AND `user_blocks` row is cascade-deleted in the same transaction

