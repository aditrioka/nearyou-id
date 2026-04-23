## ADDED Requirements

### Requirement: posts row shape gains city_name and city_admin_region_id in V11

V11 SHALL add two nullable columns to the `posts` table: `city_name TEXT NULL` and `city_admin_region_id INT NULL REFERENCES admin_regions(id) ON DELETE SET NULL`. The additions are strictly additive — no existing column is modified, no NOT NULL constraint is tightened, no existing index is altered. The `users` table itself is NOT modified by V11; this capability documents the `posts` row shape that `users-schema` tracks as part of the cascade graph it documents.

#### Scenario: posts columns present after V11
- **WHEN** querying `information_schema.columns WHERE table_name = 'posts' AND column_name IN ('city_name', 'city_admin_region_id')`
- **THEN** both rows exist AND `is_nullable = 'YES'`

#### Scenario: Existing posts columns unchanged
- **WHEN** comparing the V10-era `posts` column set against post-V11 (excluding the two new columns)
- **THEN** every existing column matches in name, type, nullability, default, and check constraints

### Requirement: admin_regions delete cascades city_admin_region_id to NULL

The FK `posts.city_admin_region_id REFERENCES admin_regions(id) ON DELETE SET NULL` SHALL behave as follows: when an `admin_regions` row is hard-deleted (which happens only via the admin-module polygon maintenance path, not via any user-facing flow), every `posts` row whose `city_admin_region_id` referenced that row MUST have `city_admin_region_id` set to NULL in the same DB transaction. The `city_name` string snapshot MUST be preserved — the display string survives the FK decoupling.

#### Scenario: admin_regions hard delete sets posts.city_admin_region_id NULL
- **WHEN** an `admin_regions` row with `id = R` is hard-deleted AND there exist `posts` rows with `city_admin_region_id = R`
- **THEN** every such `posts` row is updated: `city_admin_region_id = NULL` AND `city_name` is unchanged from its pre-delete value

#### Scenario: user hard-delete cascade unaffected by V11
- **WHEN** a `users` row is hard-deleted AND that user has `posts` rows with non-NULL `city_name`
- **THEN** the existing `posts` RESTRICT / cascade rules apply unchanged (V11 adds no new cascade path from `users`)

### Requirement: users table unchanged by V11

V11 MUST NOT touch the `users` table schema, indexes, CHECK constraints, or triggers. No column is added, removed, renamed, or retyped. No FK relationships on `users` are changed. The only V11 schema changes are additive on `posts` plus the new `admin_regions` table plus the `posts_set_city_tg` trigger.

#### Scenario: users column set unchanged
- **WHEN** diffing the `users` column set before and after V11
- **THEN** the diff is empty

#### Scenario: users index set unchanged
- **WHEN** diffing `pg_indexes WHERE tablename = 'users'` before and after V11
- **THEN** the diff is empty
