## ADDED Requirements

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
