# follow-system Specification

## Purpose
TBD - created by archiving change following-timeline-with-follow-cascade. Update Purpose after archive.
## Requirements
### Requirement: follows table created via Flyway V6

A migration `V6__follows.sql` SHALL create the `follows` table with columns:
- `follower_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE`
- `followee_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE`
- `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`
- `PRIMARY KEY (follower_id, followee_id)`
- `CHECK (follower_id <> followee_id)`

The migration MUST also create both directional indexes:
- `follows_follower_idx ON follows (follower_id, created_at DESC)` — supports fast "who does X follow (newest first)" queries.
- `follows_followee_idx ON follows (followee_id, created_at DESC)` — supports fast "who follows X (newest first)" queries.

Note: the canonical `docs/05-Implementation.md` §669–687 uses the column name `followed_id`. V6 deliberately renames to `followee_id` for grammatical symmetry with `follower_id`; the V6 header MUST document the rename and `docs/05-Implementation.md` §1295 MUST be updated in the same PR so the doc and schema agree.

#### Scenario: Migration runs cleanly from V5
- **WHEN** Flyway runs `V6__follows.sql` against a database at V5
- **THEN** the migration succeeds AND `flyway_schema_history` records V6

#### Scenario: All canonical columns present
- **WHEN** querying `information_schema.columns WHERE table_name = 'follows'`
- **THEN** the columns `follower_id`, `followee_id`, `created_at` are present with the documented types and nullability

#### Scenario: CHECK constraint rejects self-follow
- **WHEN** a direct DB INSERT is attempted with `follower_id = followee_id`
- **THEN** the INSERT fails with a check-constraint violation (SQLSTATE `23514`)

#### Scenario: Primary key rejects duplicate
- **WHEN** two INSERTs share the same `(follower_id, followee_id)`
- **THEN** the second INSERT fails with a unique-constraint violation (SQLSTATE `23505`)

#### Scenario: Both directional indexes exist
- **WHEN** querying `pg_indexes WHERE tablename = 'follows'`
- **THEN** the result contains an index whose definition orders by `follower_id` then `created_at DESC` AND an index whose definition orders by `followee_id` then `created_at DESC`

#### Scenario: Cascade on user hard-delete (follower side)
- **WHEN** a `users` row referenced as `follower_id` is hard-deleted
- **THEN** all `follows` rows where `follower_id = <that user>` are cascade-deleted

#### Scenario: Cascade on user hard-delete (followee side)
- **WHEN** a `users` row referenced as `followee_id` is hard-deleted
- **THEN** all `follows` rows where `followee_id = <that user>` are cascade-deleted

### Requirement: POST /api/v1/follows/{user_id} creates a follow (idempotent)

A Ktor route SHALL be registered at `POST /api/v1/follows/{user_id}` requiring Bearer JWT auth via the existing `auth-jwt` plugin. On success, the endpoint MUST insert `(follower_id = caller, followee_id = path-user_id)` via `INSERT ... ON CONFLICT (follower_id, followee_id) DO NOTHING` and return HTTP 204 with no body. Re-following an already-followed user MUST also return 204 (no error).

#### Scenario: First follow returns 204
- **WHEN** caller A follows user B for the first time
- **THEN** the response is HTTP 204 AND a `follows` row `(A, B, ...)` exists

#### Scenario: Re-follow idempotent
- **WHEN** caller A follows user B AND then immediately follows user B again
- **THEN** both responses are HTTP 204 AND there is exactly one `follows` row `(A, B, ...)`

#### Scenario: Unauthenticated rejected
- **WHEN** the request lacks a valid JWT
- **THEN** the response is HTTP 401 with `error.code = "unauthenticated"`

### Requirement: Self-follow rejected at app layer

The route handler SHALL reject requests where the path `user_id` equals the caller's UUID with HTTP 400 and error code `cannot_follow_self`. This check MUST run before any DB read or write. The DB CHECK constraint serves as defense-in-depth only.

#### Scenario: Self-follow returns 400
- **WHEN** caller A calls `POST /api/v1/follows/A`
- **THEN** the response is HTTP 400 with `error.code = "cannot_follow_self"` AND no `follows` row is inserted

#### Scenario: Self-follow does not reach DB
- **WHEN** the self-follow request is made
- **THEN** no DB statement is executed for that request (verifiable via test-side spy or query log)

### Requirement: Follow target user must exist

The route handler SHALL verify the path `user_id` references an existing `users.id` row before inserting. If the target user does not exist, the response MUST be HTTP 404 with error code `user_not_found`. Implementation MAY rely on the FK violation (SQLSTATE `23503`) on INSERT being mapped to 404 — the single-round-trip pattern used by `BlockService.block` — rather than a preliminary existence query.

#### Scenario: Unknown target rejected
- **WHEN** caller A calls `POST /api/v1/follows/<uuid that does not exist>`
- **THEN** the response is HTTP 404 with `error.code = "user_not_found"`

### Requirement: Mutual-block rejects follow with 409

The route handler SHALL, BEFORE inserting, execute a read against `user_blocks` of the form:

```
SELECT 1 FROM user_blocks
WHERE (blocker_id = :caller AND blocked_id = :target)
   OR (blocker_id = :target AND blocked_id = :caller)
LIMIT 1
```

If a row is returned, the endpoint MUST return HTTP 409 with error code `follow_blocked` and no `follows` row inserted. The error code MUST NOT reveal which direction of the block exists. The response body MUST NOT include any identifying information beyond the error code.

#### Scenario: Caller has blocked target
- **WHEN** caller A has a `user_blocks` row `(A, B)` AND calls `POST /api/v1/follows/B`
- **THEN** the response is HTTP 409 with `error.code = "follow_blocked"` AND no `follows` row is inserted

#### Scenario: Target has blocked caller
- **WHEN** user B has a `user_blocks` row `(B, A)` AND caller A calls `POST /api/v1/follows/B`
- **THEN** the response is HTTP 409 with `error.code = "follow_blocked"` AND no `follows` row is inserted

#### Scenario: Error body does not leak direction
- **WHEN** either block direction triggers the 409
- **THEN** the response body is identical in both cases (a constant JSON envelope `{ "error": { "code": "follow_blocked" } }` with no additional fields)

### Requirement: DELETE /api/v1/follows/{user_id} removes a follow (idempotent)

A Ktor route SHALL be registered at `DELETE /api/v1/follows/{user_id}` requiring Bearer JWT auth. The endpoint MUST execute `DELETE FROM follows WHERE follower_id = :caller AND followee_id = :path_user_id` and return HTTP 204 regardless of whether a row was deleted. Self-unfollow (path UUID equals caller) SHOULD also return 204 (no DB row will match; no special handling required).

#### Scenario: Existing follow removed
- **WHEN** caller A has previously followed B AND now calls `DELETE /api/v1/follows/B`
- **THEN** the response is HTTP 204 AND no `follows` row `(A, B, ...)` exists

#### Scenario: No-op delete still 204
- **WHEN** caller A has not followed B AND calls `DELETE /api/v1/follows/B`
- **THEN** the response is HTTP 204 (no error)

### Requirement: GET /api/v1/users/{user_id}/followers lists profile followers (paginated, viewer-block-filtered)

A Ktor route SHALL be registered at `GET /api/v1/users/{user_id}/followers?cursor=` requiring Bearer JWT auth. The endpoint MUST return the set of users who follow `{user_id}`, paginated by keyset on `(created_at DESC, follower_id DESC)` with a per-page cap of 30. The returned set MUST be filtered to exclude:
1. Users the CALLING VIEWER has blocked (`user_blocks` row `(viewer, X)`).
2. Users who have blocked the CALLING VIEWER (`user_blocks` row `(X, viewer)`).

The filter applies regardless of whether the caller is the profile owner. The response shape MUST be:

```json
{
  "users": [
    { "user_id": "<uuid>", "created_at": "<ISO-8601 UTC>" }
  ],
  "next_cursor": "<string or null>"
}
```

The cursor MUST use the same base64url-encoded JSON format as `nearby-timeline` (`{"c":"...","i":"..."}`), with `i` encoding the `follower_id` UUID of the row at the page boundary. A malformed cursor MUST yield HTTP 400 with error code `invalid_cursor`.

#### Scenario: Unauthenticated rejected
- **WHEN** the request lacks a valid JWT
- **THEN** the response is HTTP 401 with `error.code = "unauthenticated"`

#### Scenario: Profile owner sees own followers (filtered)
- **WHEN** profile P has followers `[X, Y, Z]` AND the caller is P AND P has blocked X
- **THEN** the response `users` array does NOT contain X AND contains Y and Z

#### Scenario: Third-party viewer sees filtered followers
- **WHEN** profile P has followers `[X, Y]` AND the caller is V (V != P) AND Y has blocked V
- **THEN** the response `users` array does NOT contain Y AND contains X

#### Scenario: Unknown profile target
- **WHEN** the caller calls `GET /api/v1/users/<uuid that does not exist>/followers`
- **THEN** the response is HTTP 404 with `error.code = "user_not_found"`

#### Scenario: Page cap of 30 enforced
- **WHEN** profile P has 50 visible-to-viewer followers
- **THEN** the response `users` array contains exactly 30 entries AND `next_cursor` is non-null

#### Scenario: next_cursor null on last page
- **WHEN** the response contains <30 entries
- **THEN** `next_cursor` is `null`

#### Scenario: Malformed cursor rejected
- **WHEN** `cursor=not-a-base64-json`
- **THEN** the response is HTTP 400 with `error.code = "invalid_cursor"`

### Requirement: GET /api/v1/users/{user_id}/following lists profile outbound follows (paginated, viewer-block-filtered)

A Ktor route SHALL be registered at `GET /api/v1/users/{user_id}/following?cursor=` requiring Bearer JWT auth. The endpoint MUST return the set of users whom `{user_id}` follows, paginated by keyset on `(created_at DESC, followee_id DESC)` with a per-page cap of 30. The returned set MUST be filtered to exclude users the CALLING VIEWER has blocked or been blocked by (same semantics as `/followers`).

The response shape and cursor format are identical to `/followers`; `i` encodes the `followee_id` UUID of the row at the page boundary.

#### Scenario: Unauthenticated rejected
- **WHEN** the request lacks a valid JWT
- **THEN** the response is HTTP 401 with `error.code = "unauthenticated"`

#### Scenario: Viewer-blocked followees excluded
- **WHEN** profile P follows `[X, Y]` AND the caller is V AND V has blocked X
- **THEN** the response `users` array does NOT contain X AND contains Y

#### Scenario: Unknown profile target
- **WHEN** the caller calls `GET /api/v1/users/<uuid that does not exist>/following`
- **THEN** the response is HTTP 404 with `error.code = "user_not_found"`

### Requirement: Integration test coverage

`FollowEndpointsTest` (tagged `database`) SHALL cover end-to-end against a Postgres test DB:
1. First follow returns 204 and creates a row.
2. Re-follow is idempotent (204, still one row).
3. Self-follow rejected (400 `cannot_follow_self`, no row).
4. Follow target not found (404 `user_not_found`).
5. Follow when caller has blocked target (409 `follow_blocked`, no row).
6. Follow when target has blocked caller (409 `follow_blocked`, no row).
7. 409 response body identical in both block directions.
8. Unfollow removes existing row, returns 204.
9. Unfollow no-op returns 204.
10. `/followers` returns profile followers ordered `created_at DESC`.
11. `/followers` excludes viewer-blocked users (both directions).
12. `/followers` paginates correctly with cursor.
13. `/followers` returns 404 for unknown profile UUID.
14. `/following` returns profile outbound follows ordered `created_at DESC`.
15. `/following` excludes viewer-blocked users (both directions).
16. All five follow endpoints return 401 without JWT.

`MigrationV6SmokeTest` (tagged `database`) SHALL cover: migration runs cleanly from V5, both indexes exist with documented column orders, PK enforces uniqueness, CHECK enforces self-follow rejection, both FK cascades behave as specified.

#### Scenario: Both test classes discoverable
- **WHEN** running `./gradlew :backend:ktor:test --tests '*FollowEndpointsTest*' --tests '*MigrationV6SmokeTest*'`
- **THEN** both classes are discovered AND every numbered scenario above corresponds to at least one `@Test` method

