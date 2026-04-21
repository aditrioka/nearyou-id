## ADDED Requirements

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
