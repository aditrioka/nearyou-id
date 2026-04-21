## ADDED Requirements

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
