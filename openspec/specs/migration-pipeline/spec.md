# Migration Pipeline

## Purpose

Defines the Flyway migration scaffold on `:backend:ktor`, the file-naming convention for migrations, the env-namespaced secret-resolution helper used to derive DB connection secret keys, and the smoke-test harness pattern for migrations.

See `docs/04-Architecture.md § Flyway Migration Deployment` for the production deployment design (Cloud Run Jobs `nearyou-migrate-*`, deferred to a future change).
## Requirements
### Requirement: Flyway plugin wired to backend module

The Flyway Gradle plugin SHALL be applied to `:backend:ktor` so that `./gradlew :backend:ktor:flywayMigrate` is a valid task. The plugin's `url`/`user`/`password` config MUST be sourced from environment variables (`DB_URL`, `DB_USER`, `DB_PASSWORD`) — no hardcoded credentials.

#### Scenario: Task discoverable
- **WHEN** running `./gradlew :backend:ktor:tasks --group=flyway`
- **THEN** the output lists at least `flywayMigrate`, `flywayInfo`, `flywayValidate`

#### Scenario: No hardcoded URL
- **WHEN** searching `backend/ktor/build.gradle.kts` for `jdbc:postgresql://`
- **THEN** zero matches are found

### Requirement: Migration directory and placeholder

The migration directory SHALL be `backend/ktor/src/main/resources/db/migration/` (Flyway default). It MUST contain a `V1__init.sql` placeholder whose body is a no-op SQL statement (e.g. `SELECT 1;`) and whose header comment explicitly states it is intentional and that real schema starts at `V2`.

#### Scenario: Directory exists
- **WHEN** listing `backend/ktor/src/main/resources/db/migration/`
- **THEN** the directory exists and contains `V1__init.sql`

#### Scenario: Placeholder content
- **WHEN** reading `V1__init.sql`
- **THEN** the file contains a comment marking it a placeholder and contains no `CREATE`, `ALTER`, or `DROP` statements

### Requirement: secretKey helper for env-namespaced secrets

A function `secretKey(env: String, name: String): String` SHALL exist in `:backend:ktor` returning the namespaced secret name. For `env == "staging"` it MUST return `"staging-$name"`; otherwise it MUST return `name` unchanged.

#### Scenario: Staging prefix applied
- **WHEN** calling `secretKey("staging", "admin-app-db-connection-string")`
- **THEN** the result is `"staging-admin-app-db-connection-string"`

#### Scenario: Production unchanged
- **WHEN** calling `secretKey("production", "admin-app-db-connection-string")`
- **THEN** the result is `"admin-app-db-connection-string"`

#### Scenario: Unit test exists
- **WHEN** running `./gradlew :backend:ktor:test`
- **THEN** at least one test exercises both branches of `secretKey`

### Requirement: KTOR_ENV drives environment selection

Ktor application config SHALL read the current environment from the `KTOR_ENV` environment variable via HOCON substitution (`${?KTOR_ENV}`) in `application.conf`. The application MUST default to `"production"` when `KTOR_ENV` is unset (fail-safe), per the Architecture doc's Config Separation Pattern.

#### Scenario: HOCON references KTOR_ENV
- **WHEN** reading `backend/ktor/src/main/resources/application.conf`
- **THEN** the `ktor.environment` key uses `${?KTOR_ENV}` substitution

### Requirement: First real migration V2 lands

The Flyway migration pipeline SHALL exercise an actual schema migration named `V2__auth_foundation.sql`. After auth-foundation, `flywayMigrate` against a fresh DB MUST result in `flyway_schema_history` containing both V1 (placeholder) and V2 records.

#### Scenario: V2 records on fresh migrate
- **WHEN** running `flywayMigrate` against an empty Postgres
- **THEN** `flyway_schema_history` contains a row with `version = '2'` and `success = TRUE`

#### Scenario: V2 idempotent on second run
- **WHEN** `flywayMigrate` is run a second time against the same database
- **THEN** the run is a no-op (no new history rows; no errors)

### Requirement: V4 migration lands

The Flyway migration pipeline SHALL now carry an actual migration `V4__post_creation.sql`. Running `flywayMigrate` against a database at V3 MUST advance it to V4 and record a successful row in `flyway_schema_history`.

#### Scenario: V4 records on migrate
- **WHEN** running `flywayMigrate` against a database at V3
- **THEN** `flyway_schema_history` contains a row with `version = '4'` and `success = TRUE`

#### Scenario: V4 idempotent on second run
- **WHEN** `flywayMigrate` is run a second time against a database already at V4
- **THEN** the run is a no-op (no new history rows; no errors)

### Requirement: Migration smoke test asserts GIST indexes

The migration smoke-test harness SHALL include an assertion pattern for GIST indexes that future PostGIS-dependent migrations can reuse. Specifically `MigrationV4SmokeTest` MUST verify that `posts_display_location_idx` and `posts_actual_location_idx` use the `gist` access method (via `pg_indexes` or `pg_index` join on `pg_am`).

#### Scenario: Index access method is gist
- **WHEN** `MigrationV4SmokeTest` asserts on `posts_display_location_idx`
- **THEN** the assertion reads the access method for that index and verifies it equals `gist`

### Requirement: V5 migration lands

The Flyway migration pipeline SHALL now carry an actual migration `V5__user_blocks.sql`. Running `flywayMigrate` against a database at V4 MUST advance it to V5 and record a successful row in `flyway_schema_history`.

#### Scenario: V5 records on migrate
- **WHEN** running `flywayMigrate` against a database at V4
- **THEN** `flyway_schema_history` contains a row with `version = '5'` and `success = TRUE`

#### Scenario: V5 idempotent on second run
- **WHEN** `flywayMigrate` is run a second time against a database already at V5
- **THEN** the run is a no-op (no new history rows; no errors)

### Requirement: V5 establishes joint V3+V4 dependency precedent

V5 is the first migration that depends jointly on tables from two prior migrations: `users` (V3) for the FK targets and `posts`/`visible_posts` (V4) for the read-path consumers it enables. The `MigrationV5SmokeTest` SHALL verify that the migration runs cleanly only against a database that has both V3 and V4 applied. The migration file's header comment MUST document this joint dependency.

#### Scenario: V5 fails against pre-V3 baseline
- **WHEN** an attempt is made to apply V5 against a database that has only V1+V2 (no V3 users, no V4 posts)
- **THEN** Flyway either refuses (out-of-order) or the SQL fails on the missing `users` table reference

#### Scenario: V5 header comments the joint dependency
- **WHEN** reading the top of `V5__user_blocks.sql`
- **THEN** the file contains a comment referencing both V3 (`users`) and V4 (`posts`) as required preconditions

### Requirement: MigrationV5SmokeTest covers indexes, UNIQUE, CHECK, cascades

A test class `MigrationV5SmokeTest` (tagged `database`) SHALL verify, against a fresh Postgres+PostGIS test database with V1–V5 applied:
1. Both directional indexes exist with the documented column order.
2. The UNIQUE `(blocker_id, blocked_id)` constraint is enforced (duplicate INSERT fails).
3. The `CHECK (blocker_id <> blocked_id)` constraint is enforced (self-block INSERT fails).
4. The FK cascade behavior on user hard-delete works in BOTH directions.

#### Scenario: All four invariants asserted
- **WHEN** running `./gradlew :backend:ktor:test --tests '*MigrationV5SmokeTest*'`
- **THEN** the class is discovered AND assertions for each numbered invariant are present and pass

### Requirement: V6 migration lands

The Flyway migration pipeline SHALL now carry an actual migration `V6__follows.sql`. Running `flywayMigrate` against a database at V5 MUST advance it to V6 and record a successful row in `flyway_schema_history`.

#### Scenario: V6 records on migrate
- **WHEN** running `flywayMigrate` against a database at V5
- **THEN** `flyway_schema_history` contains a row with `version = '6'` and `success = TRUE`

#### Scenario: V6 idempotent on second run
- **WHEN** `flywayMigrate` is run a second time against a database already at V6
- **THEN** the run is a no-op (no new history rows; no errors)

### Requirement: V6 establishes schema-vs-runtime dependency precedent

V6 depends ON V3 (`users`) at the schema level — `follows` FKs reference `users(id)`. V6 ALSO couples to V5 (`user_blocks`) at the RUNTIME level, not the schema level: `BlockService.block()` executes a transactional DELETE against `follows` on every block, and `POST /api/v1/follows/{user_id}` reads `user_blocks` before inserting into `follows`. The V6 migration file MUST document BOTH dependency shapes in its header comment: (a) schema-level FK dependency on V3, (b) runtime-level behavior coupling to V5. This makes the V6 precedent distinct from V5 (which was the first joint schema-level dependency on V3+V4).

#### Scenario: V6 header documents both dependency shapes
- **WHEN** reading the top of `V6__follows.sql`
- **THEN** the file contains a comment referencing V3 as the FK target AND a comment noting that V6 assumes `user_blocks` (V5) for the runtime block-cascade and mutual-block-rejection behavior — with a pointer to the `user-blocking` and `follow-system` specs

#### Scenario: V6 header documents the followed_id → followee_id rename
- **WHEN** reading the top of `V6__follows.sql`
- **THEN** the file contains a comment noting the canonical `docs/05-Implementation.md` §669–687 uses `followed_id` AND that V6 deliberately renames the column to `followee_id` AND that `docs/05-Implementation.md` §1295 must be updated in the same PR

### Requirement: MigrationV6SmokeTest covers indexes, PK, CHECK, cascades

A test class `MigrationV6SmokeTest` (tagged `database`) SHALL verify, against a fresh Postgres test database with V1–V6 applied:
1. Both directional indexes exist with the documented column orders (`follows_follower_idx`, `follows_followee_idx`).
2. The PRIMARY KEY `(follower_id, followee_id)` is enforced (duplicate INSERT fails with SQLSTATE `23505`).
3. The `CHECK (follower_id <> followee_id)` constraint is enforced (self-follow INSERT fails with SQLSTATE `23514`).
4. The FK cascade behavior on user hard-delete works in BOTH directions.
5. Applying V6 against a database at V5 advances to V6; applying V6 against V6 is a no-op.

#### Scenario: All five invariants asserted
- **WHEN** running `./gradlew :backend:ktor:test --tests '*MigrationV6SmokeTest*'`
- **THEN** the class is discovered AND assertions for each numbered invariant are present and pass

### Requirement: V7 migration lands

The Flyway migration pipeline SHALL now carry an actual migration `V7__post_likes.sql`. Running `flywayMigrate` against a database at V6 MUST advance it to V7 and record a successful row in `flyway_schema_history`.

#### Scenario: V7 records on migrate
- **WHEN** running `flywayMigrate` against a database at V6
- **THEN** `flyway_schema_history` contains a row with `version = '7'` and `success = TRUE`

#### Scenario: V7 idempotent on second run
- **WHEN** `flywayMigrate` is run a second time against a database already at V7
- **THEN** the run is a no-op (no new history rows; no errors)

### Requirement: V7 establishes schema-only joint V3+V4 dependency precedent

V7 depends on V3 (`users`) AND V4 (`posts`) at the schema level — `post_likes` FKs reference both `users(id)` and `posts(id)`. Unlike V6 (which had a runtime-level coupling to V5 via `BlockService.block()`'s transactional `follows` DELETE and the follow endpoint's mutual-block read against `user_blocks`), V7 has NO runtime coupling: no other service's behavior changes with V7's arrival; the timelines' new `LEFT JOIN post_likes` is introduced in the same deploy as V7 and depends only on V7 being present. The V7 migration file MUST document this in its header comment: (a) schema-level FK dependencies on V3 AND V4, (b) explicit "no runtime coupling" note contrasting with V6, (c) the list of consumers introduced alongside V7 (like service writes, likes-count endpoint, and `liked_by_viewer` LEFT JOIN in both timelines).

This makes V7 the third distinct migration-dependency shape in the pipeline:
- V5 was the first joint schema dependency (`user_blocks` references both V3 `users` and V4 `posts`).
- V6 was the first schema-plus-runtime dependency (schema on V3, runtime on V5 via mutual-block read + follow-cascade DELETE).
- V7 is the first schema-only joint dependency with explicit no-runtime-coupling — the schema lands before the consuming code ships in the same deploy, but no pre-existing code path changes behavior when V7 arrives.

#### Scenario: V7 header documents schema-only dependency shape
- **WHEN** reading the top of `V7__post_likes.sql`
- **THEN** the file contains a comment referencing V3 AND V4 as FK targets AND a comment explicitly noting that V7 has no runtime coupling to any earlier migration (contrasting with V6's `BlockService.block()` cascade) AND a comment listing the V7-era consumers (like service, likes-count endpoint, `liked_by_viewer` LEFT JOIN in `NearbyTimelineService` and `FollowingTimelineService`)

#### Scenario: V7 header references docs §690–708 verbatim DDL source
- **WHEN** reading the top of `V7__post_likes.sql`
- **THEN** the file contains a comment noting that the DDL is the verbatim canonical form from `docs/05-Implementation.md` §694–702 with no column renames

### Requirement: MigrationV7SmokeTest covers indexes, PK, cascades

A test class `MigrationV7SmokeTest` (tagged `database`) SHALL verify, against a fresh Postgres test database with V1–V7 applied:
1. Both directional indexes exist with the documented column orders (`post_likes_user_idx (user_id, created_at DESC)`, `post_likes_post_idx (post_id, created_at DESC)`).
2. The PRIMARY KEY `(post_id, user_id)` is enforced (duplicate INSERT fails with SQLSTATE `23505`).
3. The FK cascade behavior on `users` hard-delete (liker side).
4. The FK cascade behavior on `posts` hard-delete (post side).
5. Applying V7 against a database at V6 advances to V7; applying V7 against V7 is a no-op.

#### Scenario: All five invariants asserted
- **WHEN** running `./gradlew :backend:ktor:test --tests '*MigrationV7SmokeTest*'`
- **THEN** the class is discovered AND assertions for each numbered invariant are present and pass

### Requirement: V8 migration lands

The Flyway migration pipeline SHALL now carry an actual migration `V8__post_replies.sql`. Running `flywayMigrate` against a database at V7 MUST advance it to V8 and record a successful row in `flyway_schema_history`.

#### Scenario: V8 records on migrate
- **WHEN** running `flywayMigrate` against a database at V7
- **THEN** `flyway_schema_history` contains a row with `version = '8'` and `success = TRUE`

#### Scenario: V8 idempotent on second run
- **WHEN** `flywayMigrate` is run a second time against a database already at V8
- **THEN** the run is a no-op (no new history rows; no errors)

### Requirement: V8 establishes schema-only joint V3+V4 dependency with first RESTRICT-side FK since V4

V8 SHALL depend on V3 (`users`) AND V4 (`posts`) at the schema level — `post_replies` FKs MUST reference both `users(id)` (as `author_id`) and `posts(id)` (as `post_id`). Like V7, V8 MUST have NO runtime coupling: no other service's behavior changes with V8's arrival; the new reply endpoints and the timelines' `LEFT JOIN LATERAL` counter are introduced in the same deploy as V8 and depend only on V8 being present.

V8's distinguishing property within the pipeline MUST be the delete-rule asymmetry: `author_id → users(id) ON DELETE RESTRICT` + `post_id → posts(id) ON DELETE CASCADE`. This SHALL be the FIRST `RESTRICT`-side FK on `users(id)` added since V4 `posts.author_id` — V5 (`user_blocks`), V6 (`follows`), and V7 (`post_likes`) all cascade on both sides. The V8 migration file MUST document this in its header comment: (a) schema-level FK dependencies on V3 AND V4, (b) explicit "no runtime coupling" note contrasting with V6 (same structure as V7's header), (c) explicit note that `author_id RESTRICT` mirrors V4 `posts.author_id` and is the fourth distinct migration-dependency shape, (d) the list of V8-era consumers (reply service POST/GET/DELETE, `LEFT JOIN LATERAL` reply counter in `NearbyTimelineService` and `FollowingTimelineService`, first lint fixtures exercising `post_replies` reader queries).

The four dependency shapes in the pipeline are:
- V5: first joint schema dependency (`user_blocks` references both V3 `users` and V4 `posts`; both-side CASCADE).
- V6: first schema-plus-runtime dependency (schema on V3, runtime on V5 via mutual-block read + follow-cascade DELETE; both-side CASCADE).
- V7: first schema-only joint dependency with explicit no-runtime-coupling (schema on V3+V4; both-side CASCADE).
- V8: schema-only joint dependency on V3+V4 with asymmetric delete rules — first `RESTRICT`-side FK on `users(id)` since V4.

#### Scenario: V8 header documents schema-only dependency shape
- **WHEN** reading the top of `V8__post_replies.sql`
- **THEN** the file contains a comment referencing V3 AND V4 as FK targets AND a comment explicitly noting that V8 has no runtime coupling to any earlier migration (contrasting with V6's `BlockService.block()` cascade) AND a comment explaining that `author_id RESTRICT` mirrors V4 `posts.author_id` and is the first new `RESTRICT`-side FK on `users(id)` since V4 AND a comment listing the V8-era consumers (reply service, timeline reply counter, lint fixtures)

#### Scenario: V8 header references docs §716–729 verbatim DDL source
- **WHEN** reading the top of `V8__post_replies.sql`
- **THEN** the file contains a comment noting that the DDL is the verbatim canonical form from `docs/05-Implementation.md` §716–729 with no column renames

### Requirement: MigrationV8SmokeTest covers indexes, partial predicates, delete rules, cascades, RESTRICT

A test class `MigrationV8SmokeTest` (tagged `database`) SHALL verify, against a fresh Postgres test database with V1–V8 applied:
1. Both partial indexes exist with the documented column orders and the `deleted_at IS NULL` predicate (`post_replies_post_idx (post_id, created_at DESC) WHERE deleted_at IS NULL`, `post_replies_author_idx (author_id, created_at DESC) WHERE deleted_at IS NULL`).
2. The `post_id → posts(id)` FK delete rule is `CASCADE` (via `information_schema.referential_constraints`).
3. The `author_id → users(id)` FK delete rule is `RESTRICT` / `NO ACTION` (per the V4 convention).
4. `post_id` CASCADE: hard-deleting a `posts` row removes all `post_replies` rows for that post.
5. `author_id` RESTRICT: hard-deleting a `users` row with ≥1 `post_replies` row (any `deleted_at` value, including non-null) fails with SQLSTATE `23503`.
6. `author_id` RESTRICT after reply hard-delete: hard-deleting all `post_replies` rows for a user (no `posts`, `user_blocks`, `follows`, `post_likes` blockers) then hard-deleting the user succeeds.
7. Applying V8 against a database at V7 advances to V8; applying V8 against V8 is a no-op.
8. Clean migrate from V6 (skip V7 is NOT valid — V7 remains a prerequisite; this bullet verifies V1–V8 cold migrate is clean, with V7 in between).

#### Scenario: All invariants asserted
- **WHEN** running `./gradlew :backend:ktor:test --tests '*MigrationV8SmokeTest*'`
- **THEN** the class is discovered AND assertions for each numbered invariant are present and pass

### Requirement: V9 migration lands

The Flyway migration pipeline SHALL now carry an actual migration `V9__reports_moderation.sql`. Running `flywayMigrate` against a database at V8 MUST advance it to V9 and record a successful row in `flyway_schema_history`.

#### Scenario: V9 records on migrate
- **WHEN** running `flywayMigrate` against a database at V8
- **THEN** `flyway_schema_history` contains a row with `version = '9'` and `success = TRUE`

#### Scenario: V9 idempotent on second run
- **WHEN** `flywayMigrate` is run a second time against a database already at V9
- **THEN** the run is a no-op (no new history rows; no errors)

#### Scenario: Cold migrate V1→V9 succeeds
- **WHEN** `flywayMigrate` runs against an empty Postgres with V1–V9 migrations all present
- **THEN** all 9 versions apply successfully in order AND `flyway_schema_history` contains rows for every version from `'1'` through `'9'`

### Requirement: V9 establishes fifth distinct dependency shape — schema-only single-side CASCADE + deferred-FK columns

V9 SHALL depend on V3 (`users`) at the schema level — `reports.reporter_id` FK references `users(id) ON DELETE CASCADE`. Unlike V5 / V6 / V7 which had joint V3+V4 dependencies, V9 references ONLY V3 (the `target_id UUID` column in `reports` and `moderation_queue` is polymorphic across `posts` / `post_replies` / `users` / `chat_messages` and MUST NOT have a foreign key constraint — the polymorphism is enforced at the application layer).

V9 additionally introduces the FIRST deferred-FK pattern in the pipeline: two columns (`reports.reviewed_by` and `moderation_queue.resolved_by`) are declared as plain `UUID` without a constraint, with `COMMENT ON COLUMN` recording the deferred FK target. This establishes the convention for any future column that must reference a downstream-introduced table: ship the column, document the deferred target in a database comment, add the constraint via `ALTER TABLE ... ADD CONSTRAINT ... NOT VALID` + `VALIDATE CONSTRAINT` in the downstream migration.

The V9 migration file MUST document in its header comment:
- (a) single-side schema dependency on V3 `users` via `reports.reporter_id` CASCADE,
- (b) polymorphic `target_id` column intentionally without FK — application-layer resolution per `target_type`,
- (c) deferred-FK pattern for `reviewed_by` and `resolved_by` (targeting the future `admin_users` table) with a reference to the Phase 3.5 admin-users migration convention,
- (d) the V9-era consumers: the `ReportService` POST endpoint, the transactional auto-hide path (UPDATE on `posts` / `post_replies` + INSERT into `moderation_queue`), and the `RawFromPostsRule` exception-list entry for `ReportService` target-existence readers.

The five dependency shapes in the pipeline after V9 are:
- V5: first joint schema dependency (`user_blocks` references both V3 `users` and V4 `posts`; both-side CASCADE).
- V6: first schema-plus-runtime dependency (schema on V3, runtime on V5 via mutual-block read + follow-cascade DELETE; both-side CASCADE).
- V7: first schema-only joint dependency with explicit no-runtime-coupling (schema on V3+V4; both-side CASCADE).
- V8: schema-only joint dependency on V3+V4 with asymmetric delete rules — first new `RESTRICT`-side FK on `users(id)` since V4.
- **V9**: schema-only single-side CASCADE dependency on V3 `users` + polymorphic `target_id` without FK + first deferred-FK columns (targeting the not-yet-shipped `admin_users` table). V9 has NO runtime coupling to any earlier migration — the `ReportService` and the transactional auto-hide + queue-insert paths are introduced in the same deploy as V9 and depend only on V9 being present.

#### Scenario: V9 header documents single-side schema dependency
- **WHEN** reading the top of `V9__reports_moderation.sql`
- **THEN** the file contains a comment identifying V3 (`users`) as the only schema dependency AND a comment noting that `target_id` is intentionally polymorphic without a FK AND a comment noting no runtime coupling to any earlier migration

#### Scenario: V9 header documents deferred-FK pattern
- **WHEN** reading the top of `V9__reports_moderation.sql`
- **THEN** the file contains a comment explaining that `reports.reviewed_by` and `moderation_queue.resolved_by` are plain UUID columns whose FK to `admin_users(id) ON DELETE SET NULL` is deferred to the Phase 3.5 admin-users migration

#### Scenario: V9 header lists V9-era consumers
- **WHEN** reading the top of `V9__reports_moderation.sql`
- **THEN** the file contains a comment listing `ReportService` POST endpoint, the transactional auto-hide path on `posts` / `post_replies`, and the `moderation_queue` `auto_hide_3_reports` writer as V9-era consumers

#### Scenario: V9 header references docs §745–816 as canonical source
- **WHEN** reading the top of `V9__reports_moderation.sql`
- **THEN** the file contains a comment noting that the DDL is aligned with the canonical form at `docs/05-Implementation.md` §745–816

### Requirement: MigrationV9SmokeTest covers schema, constraints, indexes, deferred-FK columns, and CASCADE

A test class `MigrationV9SmokeTest` (tagged `database`) SHALL verify, against a fresh Postgres test database with V1–V9 applied:
1. Both tables (`reports`, `moderation_queue`) exist with the full documented column set (name, type, nullability, default).
2. All 8 CHECK constraints exist and enforce their documented enum sets — `reports.target_type` (4 values), `reports.reason_category` (8 values), `reports.status` (3 values), `moderation_queue.target_type` (4 values), `moderation_queue.trigger` (7 values), `moderation_queue.status` (2 values), `moderation_queue.resolution` (8 values + NULL allowed), `reports` / `moderation_queue` `priority` default.
3. `reports` has the UNIQUE `(reporter_id, target_type, target_id)` constraint enforced (second INSERT with same tuple fails SQLSTATE `23505`).
4. `moderation_queue` has the UNIQUE `(target_type, target_id, trigger)` constraint enforced.
5. All 5 indexes exist with their documented column orders (`reports_status_idx`, `reports_target_idx`, `reports_reporter_idx`, `moderation_queue_status_idx`, `moderation_queue_target_idx`).
6. `reports.reporter_id → users(id)` FK has `delete_rule = 'CASCADE'` (via `information_schema.referential_constraints`).
7. `reports.reviewed_by` and `moderation_queue.resolved_by` have NO foreign-key constraint (both zero rows in `information_schema.referential_constraints`).
8. `reports.reviewed_by` and `moderation_queue.resolved_by` carry a `pg_description` comment mentioning `admin_users` and the phrase `deferred`.
9. `reports.target_id` and `moderation_queue.target_id` have NO foreign-key constraint (polymorphic columns).
10. `reports.reporter_id` CASCADE: creating a user + report then hard-deleting the user (with no other RESTRICT FKs blocking) removes the associated `reports` row in the same transaction.
11. Applying V9 against a database at V8 advances to V9; applying V9 against V9 is a no-op.
12. Cold migrate V1→V9 applies cleanly on an empty Postgres.

#### Scenario: All invariants asserted
- **WHEN** running `./gradlew :backend:ktor:test --tests '*MigrationV9SmokeTest*'`
- **THEN** the class is discovered AND assertions for each numbered invariant are present and pass

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

