## ADDED Requirements

### Requirement: posts row shape unchanged by V11

V11 MUST NOT execute any `ALTER TABLE posts`. The `city_name TEXT` and `city_match_type VARCHAR(16)` columns ALREADY exist on `posts` from V4 (see `backend/ktor/src/main/resources/db/migration/V4__post_creation.sql:22–23`); V11 merely populates them via the new `posts_set_city_tg` trigger. No existing `posts` column is added, dropped, renamed, or retyped; no NOT NULL constraint is tightened; no existing index is altered. V11 also introduces NO new FK from `posts` to any other table.

The `users-schema` capability tracks the `posts` row shape as part of the cascade graph it documents; at V11 that graph is unchanged from V10.

#### Scenario: No ALTER TABLE posts in V11
- **WHEN** scanning `V11__admin_regions.sql` for `ALTER TABLE posts`
- **THEN** no match is found

#### Scenario: Pre-existing city columns remain nullable
- **WHEN** querying `information_schema.columns WHERE table_name = 'posts' AND column_name IN ('city_name', 'city_match_type')`
- **THEN** both rows exist AND `is_nullable = 'YES'` (carried forward from V4 unchanged)

#### Scenario: Existing posts columns unchanged
- **WHEN** comparing the V10-era `posts` column set against post-V11
- **THEN** every column matches in name, type, nullability, default, and check constraints (no diff)

#### Scenario: No new FK from posts introduced by V11
- **WHEN** diffing `information_schema.table_constraints WHERE table_name = 'posts' AND constraint_type = 'FOREIGN KEY'` before and after V11
- **THEN** the diff is empty

### Requirement: users table unchanged by V11

V11 MUST NOT touch the `users` table schema, indexes, CHECK constraints, or triggers. No column is added, removed, renamed, or retyped. No FK relationships on `users` are changed. The only V11 schema changes are the new `admin_regions` reference table, the `posts_set_city_tg` trigger, and an idempotent `visible_posts` view refresh.

#### Scenario: users column set unchanged
- **WHEN** diffing the `users` column set before and after V11
- **THEN** the diff is empty

#### Scenario: users index set unchanged
- **WHEN** diffing `pg_indexes WHERE tablename = 'users'` before and after V11
- **THEN** the diff is empty

#### Scenario: user hard-delete cascade unaffected by V11
- **WHEN** a `users` row is hard-deleted AND that user has `posts` rows with non-NULL `city_name`
- **THEN** the existing `posts` cascade rules apply unchanged (V11 adds no new cascade path from `users` and no new FK from `posts`)
