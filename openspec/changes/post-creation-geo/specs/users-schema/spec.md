## ADDED Requirements

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

## MODIFIED Requirements

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
