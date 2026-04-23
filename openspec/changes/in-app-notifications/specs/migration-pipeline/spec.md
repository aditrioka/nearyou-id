## ADDED Requirements

### Requirement: V10 migration records and dependency shape

The migration pipeline SHALL record a new version `10` whose file `V10__notifications.sql` creates the `notifications` table, its 2 indexes, and its CHECK enum. The V10 header comment block SHALL document:
- Schema-level FK dependencies on V3 `users` (two edges: `user_id` CASCADE and `actor_user_id` SET NULL).
- No runtime coupling to other tables (notifications is read-only to its own surface; the `user_blocks` join happens at emit time in application code, not in a schema-level constraint or trigger).
- V10-era writers: `LikeService` (type `post_liked`), `ReplyService` (type `post_replied`), `FollowService` (type `followed`), `ReportService` auto-hide path (type `post_auto_hidden`). The other 9 values of the `type` CHECK enum are reserved for future writers and have no V10 callers.
- Reference to `docs/05-Implementation.md` §820–844 as the canonical DDL source.

#### Scenario: Fresh cold migrate V1 → V10 succeeds
- **WHEN** Flyway runs V1..V10 in order against an empty Postgres
- **THEN** all ten versions are recorded in `flyway_schema_history` in order with `success = TRUE`

#### Scenario: Incremental migrate V9 → V10 succeeds
- **WHEN** Flyway runs against a DB at V9 (post-V9 state)
- **THEN** V10 runs AND `flyway_schema_history` gains exactly one row with version `'10'` AND V9 is unchanged

#### Scenario: V10 re-run is a no-op
- **WHEN** Flyway runs a second time against a DB already at V10
- **THEN** no new `flyway_schema_history` rows are added AND no errors are raised

#### Scenario: V10 header documents dependency shape
- **WHEN** reading the top-of-file comment in `V10__notifications.sql`
- **THEN** the comment names V3 `users` as the FK target AND lists the four V10-era writers AND references `docs/05-Implementation.md` as canonical

### Requirement: V10 establishes the second valid partial-index pattern in the pipeline

The V10 migration creates `notifications_user_unread_idx` with a `WHERE read_at IS NULL` predicate. This is a VALID partial index because the predicate is immutable (unlike `WHERE released_at > NOW()` or `WHERE expires_at > NOW()` which PostgreSQL rejects at `CREATE INDEX` time per `docs/08-Roadmap-Risk.md:486` and the partial-index CI lint rule). V10 MUST document this as the second established valid partial-index pattern in the pipeline (alongside any precedent in V1 `posts` partial indexes on `deleted_at` / `is_auto_hidden` immutable predicates).

The migration-pipeline spec SHALL treat `IS NULL` and `IS NOT NULL` predicates on nullable timestamp columns as the canonical valid-partial-index pattern going forward. Non-immutable predicates (`NOW()`, `CURRENT_TIMESTAMP`, volatile functions) MUST continue to be rejected by the CI lint rule and by PostgreSQL itself.

#### Scenario: V10 partial index is valid and applies cleanly
- **WHEN** Flyway runs V10
- **THEN** `CREATE INDEX notifications_user_unread_idx ... WHERE read_at IS NULL` succeeds without error

#### Scenario: Partial-index CI lint rule does NOT flag `IS NULL` predicates
- **WHEN** Detekt runs the partial-index rule across `src/main/resources/db/migration/V10__notifications.sql`
- **THEN** no violation is raised (the rule targets `NOW()` / `CURRENT_TIMESTAMP` / volatile function predicates only, per `docs/08-Roadmap-Risk.md` CI lint rules)

### Requirement: V10 does not modify any prior migration

V1 through V9 are immutable after merge. V10 MUST NOT edit, drop, or re-create any object introduced by V1-V9. The only way V10 touches prior objects is via the two new FK edges on `users(id)` from the new `notifications` table, which are column-level constraints on the NEW table (not alterations of `users`).

#### Scenario: V1-V9 migration files untouched by V10 PR
- **WHEN** inspecting the V10 feat PR diff
- **THEN** files `V1__*.sql` through `V9__*.sql` are NOT in the changed-files list

#### Scenario: users table DDL unchanged
- **WHEN** comparing `information_schema.columns WHERE table_name = 'users'` before and after V10
- **THEN** the column sets, types, defaults, and nullabilities are identical
