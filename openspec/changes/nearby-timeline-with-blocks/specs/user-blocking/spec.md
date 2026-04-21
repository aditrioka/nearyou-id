## ADDED Requirements

### Requirement: user_blocks table created via Flyway V5

A migration `V5__user_blocks.sql` SHALL create the `user_blocks` table with columns:
- `blocker_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE`
- `blocked_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE`
- `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`
- `CHECK (blocker_id <> blocked_id)`
- `UNIQUE (blocker_id, blocked_id)` (composite primary key OR unique constraint — implementation may pick; both must be enforced)

The migration MUST also create both directional indexes per `docs/05-Implementation.md` §1282–1283:
- `user_blocks_blocker_idx ON user_blocks (blocker_id, blocked_id)` (covered by the UNIQUE constraint if it doubles as PK; explicit if not)
- `user_blocks_blocked_idx ON user_blocks (blocked_id, blocker_id)`

#### Scenario: Migration runs cleanly from V4
- **WHEN** Flyway runs `V5__user_blocks.sql` against a database at V4
- **THEN** the migration succeeds AND `flyway_schema_history` records V5

#### Scenario: All canonical columns present
- **WHEN** querying `information_schema.columns WHERE table_name = 'user_blocks'`
- **THEN** the columns `blocker_id`, `blocked_id`, `created_at` are present with the documented types and nullability

#### Scenario: CHECK constraint rejects self-block
- **WHEN** a direct DB INSERT is attempted with `blocker_id = blocked_id`
- **THEN** the INSERT fails with a check-constraint violation (SQLSTATE `23514`)

#### Scenario: UNIQUE constraint rejects duplicate
- **WHEN** two INSERTs share the same `(blocker_id, blocked_id)`
- **THEN** the second INSERT fails with a unique-constraint violation (SQLSTATE `23505`)

#### Scenario: Both directional indexes exist
- **WHEN** querying `pg_indexes WHERE tablename = 'user_blocks'`
- **THEN** the result contains an index on `(blocker_id, blocked_id)` AND an index on `(blocked_id, blocker_id)`

#### Scenario: Cascade on user hard-delete (blocker side)
- **WHEN** a `users` row referenced as `blocker_id` is hard-deleted
- **THEN** all `user_blocks` rows where `blocker_id = <that user>` are cascade-deleted

#### Scenario: Cascade on user hard-delete (blocked side)
- **WHEN** a `users` row referenced as `blocked_id` is hard-deleted
- **THEN** all `user_blocks` rows where `blocked_id = <that user>` are cascade-deleted

### Requirement: POST /api/v1/blocks/{user_id} creates a block (idempotent)

A Ktor route SHALL be registered at `POST /api/v1/blocks/{user_id}` requiring Bearer JWT auth. On success, the endpoint MUST insert `(blocker_id = caller, blocked_id = path-user_id)` via `INSERT ... ON CONFLICT (blocker_id, blocked_id) DO NOTHING` and return HTTP 204 with no body. Re-blocking an already-blocked user MUST also return 204 (no error).

#### Scenario: First block returns 204
- **WHEN** caller A blocks user B for the first time
- **THEN** the response is HTTP 204 AND a `user_blocks` row `(A, B, ...)` exists

#### Scenario: Re-block idempotent
- **WHEN** caller A blocks user B AND then immediately blocks user B again
- **THEN** both responses are HTTP 204 AND there is exactly one `user_blocks` row `(A, B, ...)`

#### Scenario: Unauthenticated rejected
- **WHEN** the request lacks a valid JWT
- **THEN** the response is HTTP 401 with `error.code = "unauthenticated"`

### Requirement: Self-block rejected at app layer

The route handler SHALL reject requests where the path `user_id` equals the caller's UUID with HTTP 400 and error code `cannot_block_self`. This check MUST run before the DB insert. The DB CHECK constraint serves as defense-in-depth only.

#### Scenario: Self-block returns 400
- **WHEN** caller A calls `POST /api/v1/blocks/A`
- **THEN** the response is HTTP 400 with `error.code = "cannot_block_self"` AND no `user_blocks` row is inserted

#### Scenario: Self-block does not reach DB
- **WHEN** the self-block request is made
- **THEN** no DB INSERT statement is executed for that request (verifiable via test-side spy or query log)

### Requirement: Block target user must exist

The route handler SHALL verify the path `user_id` references an existing `users.id` row before inserting. If the target user does not exist, the response MUST be HTTP 404 with error code `user_not_found`.

#### Scenario: Unknown target rejected
- **WHEN** caller A calls `POST /api/v1/blocks/<uuid that does not exist>`
- **THEN** the response is HTTP 404 with `error.code = "user_not_found"`

### Requirement: DELETE /api/v1/blocks/{user_id} removes a block (idempotent)

A Ktor route SHALL be registered at `DELETE /api/v1/blocks/{user_id}` requiring Bearer JWT auth. The endpoint MUST execute `DELETE FROM user_blocks WHERE blocker_id = :caller AND blocked_id = :path_user_id` and return HTTP 204 regardless of whether a row was deleted.

#### Scenario: Existing block removed
- **WHEN** caller A has previously blocked B AND now calls `DELETE /api/v1/blocks/B`
- **THEN** the response is HTTP 204 AND no `user_blocks` row `(A, B, ...)` exists

#### Scenario: No-op delete still 204
- **WHEN** caller A has not blocked B AND calls `DELETE /api/v1/blocks/B`
- **THEN** the response is HTTP 204 (no error)

### Requirement: GET /api/v1/blocks lists outbound blocks (paginated)

A Ktor route SHALL be registered at `GET /api/v1/blocks?cursor=` requiring Bearer JWT auth. The endpoint MUST return outbound blocks (where `blocker_id = caller`) paginated by keyset on `(created_at DESC, blocked_id DESC)` with a per-page cap of 30. The response shape MUST be:

```json
{
  "blocks": [
    { "user_id": "<uuid>", "created_at": "<ISO-8601 UTC>" }
  ],
  "next_cursor": "<string or null>"
}
```

The cursor MUST use the same base64url-encoded JSON format as the `nearby-timeline` cursor (`{"c":"...","i":"..."}`), but the `i` field encodes the `blocked_id` UUID rather than a post UUID.

#### Scenario: Authenticated list returns own blocks only
- **WHEN** caller A has blocks `(A, B)` and `(A, C)` AND user X has block `(X, Y)` AND caller A calls `GET /api/v1/blocks`
- **THEN** the response contains exactly `[B, C]` (in `created_at DESC` order) AND does NOT contain Y

#### Scenario: Page cap of 30 enforced
- **WHEN** caller A has 50 outbound blocks
- **THEN** the response `blocks` array contains exactly 30 entries AND `next_cursor` is non-null

#### Scenario: next_cursor null on last page
- **WHEN** the response contains <30 entries
- **THEN** `next_cursor` is `null`

### Requirement: Future follow-cascade hook documented

The V5 migration SQL file SHALL contain a top-of-file comment noting that future tables coupling block state to other relationships (follows, mutes, reports) MUST add their own block-on-delete or block-on-create cascade behavior. V5 itself does NOT add a follows cascade because the `follows` table does not yet exist.

#### Scenario: Comment present in V5
- **WHEN** reading `V5__user_blocks.sql`
- **THEN** the file contains a comment string referencing `follows` and the future-cascade convention

### Requirement: Integration test coverage

`BlockEndpointsTest` (tagged `database`) SHALL cover:
1. Block creates row, returns 204.
2. Re-block idempotent (still 204, still one row).
3. Self-block rejected (400 `cannot_block_self`, no row inserted).
4. Block target not found (404 `user_not_found`).
5. Unblock removes row, returns 204.
6. Unblock no-op returns 204.
7. List returns own blocks only, ordered by `created_at DESC`.
8. List paginates correctly with cursor.
9. All four endpoints return 401 without JWT.

`MigrationV5SmokeTest` SHALL cover: migration runs cleanly, both indexes exist with the documented column orders, UNIQUE constraint present, CHECK constraint present, both FK cascades behave as specified.

#### Scenario: Both test classes discoverable
- **WHEN** running `./gradlew :backend:ktor:test --tests '*BlockEndpointsTest*' --tests '*MigrationV5SmokeTest*'`
- **THEN** both classes are discovered AND every numbered scenario above corresponds to at least one `@Test` method
