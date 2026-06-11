# follow-system Specification

## Purpose

The follow-system capability provides the social-graph primitives for the platform: the `follows` table plus `POST /api/v1/follows/{user_id}`, `DELETE /api/v1/follows/{user_id}`, and the follower / following list endpoints. Self-follow is rejected at both app-layer and via a DB-level CHECK; mutual-block prevents follows in either direction; blocking a user also deletes follows in both directions inside the same transaction as the `user_blocks` INSERT, so the follow-cascade is atomic and the social graph stays consistent with the block state.

As of `social-list-profile-summaries`, the surface follows the `user-profile-read` posture end-to-end: list rows embed the profile summary (`userId`, `username`, `displayName`, `isPremium`, plus the edge `createdAt`) sourced via `visible_users`, so shadow-banned / soft-deleted members never appear and no client N+1 against the single-profile endpoint is needed; lists remain bidirectionally block-excluded against the viewer (not against the profile owner); and both the list profile-target and the `POST /follows` target resolve through the visibility gate — every unresolvable cause (unknown, soft-deleted, shadow-banned, blocked-either-direction) answers one CONSTANT byte-identical `404 user_not_found`, replacing the V6-era 409 `follow_blocked` so block state never leaks cross-endpoint.
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

The route handler SHALL resolve the path `user_id` under the `user-profile-read` visibility contract BEFORE inserting: the target MUST exist in `visible_users` (never raw `users`) AND have no `user_blocks` row in either direction relative to the caller. If the target is unresolvable — unknown UUID, soft-deleted, shadow-banned, or blocked-in-either-direction — the response MUST be HTTP 404 with the CONSTANT byte-identical body `{"error":{"code":"user_not_found"}}` (no `message` field, no cause or direction hint), identical to the `user-profile-read` 404 body, emitted as a constant text body (not via map serialization) so the bytes cannot drift between causes.

The pre-insert in-transaction guard MUST remain: `followInTx` takes the canonical user-pair advisory lock and re-checks bidirectional `user_blocks` inside the transaction, so a block that lands between the resolution gate and the INSERT still prevents the edge; that path MUST map to the SAME constant 404 (no 409 path remains). The FK-violation backstop (SQLSTATE `23503` on INSERT) MUST also map to the same constant 404.

#### Scenario: Unknown target rejected
- **WHEN** caller A calls `POST /api/v1/follows/<uuid that does not exist>`
- **THEN** the response is HTTP 404 with body exactly `{"error":{"code":"user_not_found"}}`

#### Scenario: Shadow-banned target unresolvable
- **WHEN** caller A calls `POST /api/v1/follows/T` where T has `is_shadow_banned = TRUE`
- **THEN** the response is HTTP 404 with the constant body AND no `follows` row is inserted

#### Scenario: Soft-deleted target unresolvable
- **WHEN** caller A calls `POST /api/v1/follows/T` where T has `deleted_at IS NOT NULL`
- **THEN** the response is HTTP 404 with the constant body AND no `follows` row is inserted

#### Scenario: Caller has blocked target
- **WHEN** caller A has a `user_blocks` row `(A, B)` AND calls `POST /api/v1/follows/B`
- **THEN** the response is HTTP 404 with the constant body AND no `follows` row is inserted

#### Scenario: Target has blocked caller
- **WHEN** user B has a `user_blocks` row `(B, A)` AND caller A calls `POST /api/v1/follows/B`
- **THEN** the response is HTTP 404 with the constant body AND no `follows` row is inserted

#### Scenario: 404 is byte-identical across all causes and matches the profile read
- **WHEN** comparing the 404 responses for an unknown UUID, a shadow-banned target, a soft-deleted target, and both block directions, and the `GET /api/v1/users/{id}` 404
- **THEN** every status is 404 AND every body is byte-identical (`{"error":{"code":"user_not_found"}}`)

#### Scenario: Block landing mid-flight still prevents the edge
- **WHEN** a `user_blocks` row is committed by a concurrent request after caller A's resolution gate passed but before A's `follows` INSERT commits
- **THEN** the in-transaction guard rejects the edge AND the response is the constant 404 AND no `follows` row persists

#### Scenario: Visible unblocked target still followable
- **WHEN** caller A calls `POST /api/v1/follows/B` where B is visible (not shadow-banned, not deleted) and no block exists in either direction
- **THEN** the response is HTTP 204 AND a `follows` row `(A, B)` exists

### Requirement: DELETE /api/v1/follows/{user_id} removes a follow (idempotent)

A Ktor route SHALL be registered at `DELETE /api/v1/follows/{user_id}` requiring Bearer JWT auth. The endpoint MUST execute `DELETE FROM follows WHERE follower_id = :caller AND followee_id = :path_user_id` and return HTTP 204 regardless of whether a row was deleted. Self-unfollow (path UUID equals caller) SHOULD also return 204 (no DB row will match; no special handling required).

#### Scenario: Existing follow removed
- **WHEN** caller A has previously followed B AND now calls `DELETE /api/v1/follows/B`
- **THEN** the response is HTTP 204 AND no `follows` row `(A, B, ...)` exists

#### Scenario: No-op delete still 204
- **WHEN** caller A has not followed B AND calls `DELETE /api/v1/follows/B`
- **THEN** the response is HTTP 204 (no error)

### Requirement: GET /api/v1/users/{user_id}/followers lists profile followers (paginated, viewer-block-filtered)

A Ktor route SHALL be registered at `GET /api/v1/users/{user_id}/followers?cursor=` requiring Bearer JWT auth. The endpoint MUST return the set of users who follow `{user_id}`, paginated by keyset on `(created_at DESC, follower_id DESC)` with a per-page cap of 30.

**Profile-target resolution (constant-404 contract).** Before reading the list, the profile target MUST be resolved under the `user-profile-read` contract: when `{user_id}` equals the caller, via raw `users` existence on the own-content path (a shadow-banned caller keeps their own lists; the raw-`users` self probe is KDoc-justified at the SQL-holding declaration, mirroring the `JdbcUserProfileReader.SQL_SELF` justification — the `@AllowMissingBlockJoin` annotation is a `:backend:ktor` lint construct and `BlockExclusionJoinRule` does not scan `:infra:supabase`, where this repository lives); otherwise via `visible_users` plus a bidirectional `user_blocks` exclusion. An unresolvable target — unknown UUID, soft-deleted, shadow-banned, or blocked-in-either-direction — MUST yield HTTP 404 with the CONSTANT byte-identical body `{"error":{"code":"user_not_found"}}` (no `message` field), identical to the `user-profile-read` 404 body.

**Row sourcing and filtering.** The list query MUST source rows `FROM follows` with an INNER `JOIN visible_users` on the row user (`follower_id`) — the join `docs/05-Implementation.md` §1272 mandates — so shadow-banned and soft-deleted followers do NOT appear. The returned set MUST also exclude, via the two bidirectional `user_blocks` NOT-IN subqueries on the `follows` clause, both of which MUST remain present (NOTE: the linter does NOT enforce these subqueries — `follows` is deliberately outside `BlockExclusionJoinRule`'s protected-table pattern, `visible_users` does not trip the rule, and the rule's detekt ruleset only runs on `:backend:ktor` sources while this SQL lives in `:infra:supabase` — so the block-exclusion scenarios below are the guardrail, the same stance `user-profile-read` documents):
1. Users the CALLING VIEWER has blocked (`user_blocks` row `(viewer, X)`).
2. Users who have blocked the CALLING VIEWER (`user_blocks` row `(X, viewer)`).

The filter applies regardless of whether the caller is the profile owner. The identity join MUST NOT appear in the `ORDER BY` and MUST NOT appear in the keyset predicate; `LIMIT` applies after the join, so a page fills with visible rows.

**Response shape.** Each row MUST embed the profile summary of the row user. The response shape MUST be:

```json
{
  "users": [
    {
      "userId": "<uuid>",
      "username": "<string>",
      "displayName": "<string>",
      "isPremium": <boolean>,
      "createdAt": "<ISO-8601 UTC>"
    }
  ],
  "nextCursor": "<string or null>"
}
```

(The example reflects the SHIPPED bare-camelCase wire of `FollowRoutes.kt` — `userId`/`createdAt`/`nextCursor`; the pre-change spec text showed a snake_case example that never matched the shipped DTOs, corrected here following the `mobile-timeline-card-redesign` precedent.) The new fields are declared EXPLICITLY as bare camelCase `username` / `displayName` / `isPremium` (no `@SerialName`), matching the `UserProfileResponse` vocabulary. `username` and `displayName` MUST equal the row user's `visible_users.username` / `visible_users.display_name` (never null — NOT NULL since V2). `isPremium` MUST be computed as `subscription_status = 'premium_active'` only (the `user-profile-read` `isPremium` formula; `chat-conversations` was aligned to the same formula by the 2026-06-10 audit). `createdAt` is the follow-edge timestamp.

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

#### Scenario: Rows embed the profile summary with exact camelCase keys
- **WHEN** profile P has a visible follower with `username = "raka.jkt"`, `display_name = "Raka Pratama"`
- **THEN** that row contains the keys `userId`, `username`, `displayName`, `isPremium`, `createdAt` with `username = "raka.jkt"` AND `displayName = "Raka Pratama"` AND contains NO snake_case variants (`user_id` / `display_name` / `is_premium` / `created_at`)

#### Scenario: isPremium uses the profile-read formula
- **WHEN** profile P has two visible followers, one with `subscription_status = 'premium_active'` and one with `subscription_status = 'premium_billing_retry'`
- **THEN** the first row has `isPremium = true` AND the second has `isPremium = false`

#### Scenario: Shadow-banned follower excluded from rows
- **WHEN** profile P has 3 followers, one of whom has `is_shadow_banned = TRUE`
- **THEN** the response `users` array contains exactly the 2 visible followers

#### Scenario: Soft-deleted follower excluded from rows
- **WHEN** profile P has 3 followers, one of whom has `deleted_at IS NOT NULL`
- **THEN** the response `users` array contains exactly the 2 non-deleted followers

#### Scenario: Unknown profile target
- **WHEN** the caller calls `GET /api/v1/users/<uuid that does not exist>/followers`
- **THEN** the response is HTTP 404 with body exactly `{"error":{"code":"user_not_found"}}`

#### Scenario: Hidden or blocked profile target is unresolvable, byte-identically
- **WHEN** comparing the responses for `GET /users/{T}/followers` where T is (a) an unknown UUID, (b) shadow-banned, (c) soft-deleted, (d) has blocked the caller, (e) is blocked by the caller
- **THEN** every response is HTTP 404 AND every body is byte-identical AND equals the `GET /api/v1/users/{T}` 404 body

#### Scenario: Shadow-banned caller keeps own lists
- **WHEN** caller S has `is_shadow_banned = TRUE` AND calls `GET /api/v1/users/S/followers`
- **THEN** the response is HTTP 200 with S's (visible) followers

#### Scenario: Page cap of 30 enforced
- **WHEN** profile P has 50 visible-to-viewer followers
- **THEN** the response `users` array contains exactly 30 entries AND `nextCursor` is non-null

#### Scenario: nextCursor null on last page
- **WHEN** the response contains <30 entries
- **THEN** `nextCursor` is `null`

#### Scenario: Malformed cursor rejected
- **WHEN** `cursor=not-a-base64-json`
- **THEN** the response is HTTP 400 with `error.code = "invalid_cursor"`

### Requirement: GET /api/v1/users/{user_id}/following lists profile outbound follows (paginated, viewer-block-filtered)

A Ktor route SHALL be registered at `GET /api/v1/users/{user_id}/following?cursor=` requiring Bearer JWT auth. The endpoint MUST return the set of users whom `{user_id}` follows, paginated by keyset on `(created_at DESC, followee_id DESC)` with a per-page cap of 30.

The profile-target resolution (constant-404 contract), row sourcing (INNER `JOIN visible_users` on the row user — here `followee_id`), bidirectional viewer-block filtering, profile-summary response shape, camelCase wire declaration, profile-read `isPremium` formula, and cursor format are identical to `/followers`; `i` encodes the `followee_id` UUID of the row at the page boundary.

#### Scenario: Unauthenticated rejected
- **WHEN** the request lacks a valid JWT
- **THEN** the response is HTTP 401 with `error.code = "unauthenticated"`

#### Scenario: Viewer-blocked followees excluded
- **WHEN** profile P follows `[X, Y]` AND the caller is V AND V has blocked X
- **THEN** the response `users` array does NOT contain X AND contains Y

#### Scenario: Hidden followees excluded from rows
- **WHEN** profile P follows 3 users, one shadow-banned and one soft-deleted
- **THEN** the response `users` array contains exactly the 1 visible followee

#### Scenario: Rows embed the profile summary
- **WHEN** profile P follows a visible user with `username = "sari.bdg"`, `display_name = "Sari Lestari"`
- **THEN** that row carries `username = "sari.bdg"` AND `displayName = "Sari Lestari"` AND an `isPremium` boolean AND NO snake_case key variants

#### Scenario: Unknown or unresolvable profile target answers the constant 404
- **WHEN** the caller calls `GET /api/v1/users/{T}/following` where T is unknown, shadow-banned, soft-deleted, or blocked-in-either-direction relative to the caller
- **THEN** the response is HTTP 404 AND the body is byte-identical across all causes AND equals the `GET /api/v1/users/{T}` 404 body

#### Scenario: Shadow-banned caller keeps own lists
- **WHEN** caller S has `is_shadow_banned = TRUE` AND calls `GET /api/v1/users/S/following`
- **THEN** the response is HTTP 200 with S's (visible) followees

### Requirement: Integration test coverage

`FollowEndpointsTest` (tagged `database`) SHALL cover end-to-end against a Postgres test DB:
1. First follow returns 204 and creates a row.
2. Re-follow is idempotent (204, still one row).
3. Self-follow rejected (400 `cannot_follow_self`, no row).
4. Follow target not found (constant 404, no row).
5. Follow when caller has blocked target (constant 404, no row).
6. Follow when target has blocked caller (constant 404, no row).
7. Follow of a shadow-banned target (constant 404, no row).
8. Follow of a soft-deleted target (constant 404, no row).
9. The follow 404 body is byte-identical across causes 4–8 AND equals the profile-read 404 body.
10. Unfollow removes existing row, returns 204.
11. Unfollow no-op returns 204.
12. `/followers` returns profile followers ordered `created_at DESC` with embedded profile summaries (exact camelCase keys, values from the row user).
13. `/followers` excludes viewer-blocked users (both directions).
14. `/followers` excludes shadow-banned and soft-deleted members.
15. `/followers` paginates correctly with cursor.
16. `/followers` answers the constant 404 for unknown, shadow-banned, soft-deleted, and blocked-either-direction targets, byte-identically.
17. A shadow-banned caller still reads their own `/followers` (200).
18. `/following` returns profile outbound follows ordered `created_at DESC` with embedded profile summaries.
19. `/following` excludes viewer-blocked users (both directions) and hidden members.
20. `/following` answers the constant 404 for unresolvable targets, byte-identically.
21. `isPremium` is `true` for `premium_active` and `false` for `premium_billing_retry` (profile-read formula).
22. All four follow endpoints (`POST /follows`, `DELETE /follows`, `/followers`, `/following`) return 401 without JWT (corrects the previous enumeration's "five" — only four routes exist).
23. The in-transaction block guard is covered at repo level (seed a block, call `followInTx` directly → `FollowBlockedException`) AND a route-mapping test asserts `FollowBlockedException` → the constant 404 (the resolution gate makes the endpoint path unreachable for pre-existing blocks, so endpoint tests alone never exercise the guard).

`MigrationV6SmokeTest` (tagged `database`) SHALL cover: migration runs cleanly from V5, both indexes exist with documented column orders, PK enforces uniqueness, CHECK enforces self-follow rejection, both FK cascades behave as specified.

#### Scenario: Both test classes discoverable
- **WHEN** running `./gradlew :backend:ktor:test --tests '*FollowEndpointsTest*' --tests '*MigrationV6SmokeTest*'`
- **THEN** both classes are discovered AND every numbered scenario above corresponds to at least one `@Test` method

### Requirement: Successful follow emits a followed notification for the followee

V10 introduces the FIRST notification-write side-effect for follows. On a successful `POST /api/v1/follows/{user_id}` that inserts a new row into `follows`, the `FollowService` SHALL invoke `NotificationEmitter.emit(type = 'followed', recipient = followee.id, actor = caller, target_type = NULL, target_id = NULL, body_data = {})` in the SAME DB transaction as the `follows` INSERT.

The emit is subject to the `NotificationEmitter` suppression rules (see `in-app-notifications` capability):
- Self-action: the `follows` table CHECK constraint (`follower_id <> followee_id`) already prevents self-follow at the DB layer, so this case is defended-in-depth but unreachable via the endpoint; the emitter's self-action short-circuit is a belt-and-suspenders match to the schema invariant.
- Block: if the follower and followee have a `user_blocks` row in either direction, no notification row is written. Whether the block prevents the follow insertion itself is a V6 `follow-system` concern that V10 does NOT change — V10 only decides whether to emit a notification given that a follow row was inserted.

Unfollow (`DELETE /api/v1/follows/{user_id}`) MUST NOT emit a counter-notification (no `unfollowed` type exists in the V10 enum). The previously-written `followed` notification MAY remain in the followee's feed as a historical record.

Re-follow after an unfollow (which produces a NEW `follows` row given the cascade on unfollow) SHOULD emit a new `followed` notification (each distinct follow relationship produces one notification). If the follow row insertion is idempotent (e.g. unique-constraint-ON-CONFLICT-DO-NOTHING), only the first (actually-inserted) follow emits.

If the notification INSERT fails (e.g. followee hard-deleted between request and emit), the encompassing transaction rolls back; the `follows` INSERT does NOT persist.

Response shapes for `POST /api/v1/follows/{user_id}` and `DELETE /api/v1/follows/{user_id}` MUST NOT change as part of V10. The `is_following` / `followed_by_viewer` fields on user profiles / timelines are unchanged.

#### Scenario: Bob follows Alice produces followed notification for Alice
- **WHEN** Bob POSTs `/api/v1/follows/{aliceId}` AND no block exists between Alice and Bob
- **THEN** HTTP 2xx (per V6 contract) AND exactly one `notifications` row exists with `user_id = Alice.id, type = 'followed', actor_user_id = Bob.id, target_type = NULL, target_id = NULL, body_data = {}`

#### Scenario: Self-follow rejection still fires first
- **WHEN** Alice POSTs `/api/v1/follows/{aliceId}` on her own id
- **THEN** the V6 CHECK constraint / endpoint logic rejects the follow (per the V6 contract) AND zero `notifications` rows are inserted

#### Scenario: Follow from blocked user produces no notification (Alice blocked Bob)
- **WHEN** Alice has a `user_blocks` row `(blocker_id = Alice, blocked_id = Bob)` AND Bob successfully inserts a follow row (whatever the V6 block semantics permit)
- **THEN** zero `notifications` rows are inserted for Alice

#### Scenario: Follow from blocked user produces no notification (Bob blocked Alice)
- **WHEN** Bob has a `user_blocks` row `(blocker_id = Bob, blocked_id = Alice)` AND Bob successfully inserts a follow row
- **THEN** zero `notifications` rows are inserted for Alice (bidirectional suppression)

#### Scenario: body_data is empty object, target_type and target_id are NULL
- **WHEN** Bob follows Alice
- **THEN** the emitted notification's `target_type IS NULL` AND `target_id IS NULL` AND `body_data = {}` (matches the `docs/05-Implementation.md:857` catalog)

#### Scenario: Unfollow does NOT emit a counter-notification
- **WHEN** Bob has followed Alice (producing a notification) AND Bob DELETEs `/api/v1/follows/{aliceId}`
- **THEN** HTTP 2xx AND no new `notifications` row is inserted (the original `followed` row for Alice persists as a historical record)

#### Scenario: Re-follow after unfollow emits a new notification
- **WHEN** Bob has followed Alice AND then unfollowed AND then re-follows (producing a new `follows` row insert)
- **THEN** a second `notifications` row with `type = 'followed'` is inserted for Alice (each distinct follow relationship emits once)

#### Scenario: Notification INSERT failure rolls back the follow
- **WHEN** Bob attempts to follow Alice AND Alice is hard-deleted between the follow validation and the notification INSERT
- **THEN** the transaction rolls back AND zero `follows` rows persist

#### Scenario: Follow endpoint response shapes unchanged
- **WHEN** inspecting the response bodies of `POST /api/v1/follows/{user_id}` and `DELETE /api/v1/follows/{user_id}` pre-V10 and post-V10
- **THEN** each matches the pre-V10 contract (V10 does not alter the follow endpoint response shapes; the later `social-list-profile-summaries` 404 alignment is a separate, deliberate change)

