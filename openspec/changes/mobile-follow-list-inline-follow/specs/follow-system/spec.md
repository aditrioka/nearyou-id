## MODIFIED Requirements

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
      "followedByViewer": <boolean>,
      "isSelf": <boolean>,
      "createdAt": "<ISO-8601 UTC>"
    }
  ],
  "nextCursor": "<string or null>"
}
```

(The example reflects the SHIPPED bare-camelCase wire of `FollowRoutes.kt` — `userId`/`createdAt`/`nextCursor`; the pre-change spec text showed a snake_case example that never matched the shipped DTOs, corrected here following the `mobile-timeline-card-redesign` precedent.) The new fields are declared EXPLICITLY as bare camelCase `username` / `displayName` / `isPremium` / `followedByViewer` / `isSelf` (no `@SerialName`), matching the `UserProfileResponse` vocabulary. `username` and `displayName` MUST equal the row user's `visible_users.username` / `visible_users.display_name` (never null — NOT NULL since V2). `isPremium` MUST be computed as `subscription_status = 'premium_active'` only (the `user-profile-read` `isPremium` formula; `chat-conversations` was aligned to the same formula by the 2026-06-10 audit). `createdAt` is the follow-edge timestamp.

**Viewer-relative row state (`mobile-follow-list-inline-follow`, issue #307).** Each row MUST carry:
- `followedByViewer` — `true` iff a `follows` row `(follower_id = calling viewer, followee_id = row user)` exists at read time, computed as a correlated `EXISTS` probe on the `follows` primary key in the select list. The probe MUST NOT alter row sourcing, the block-exclusion subqueries, the keyset predicate, `ORDER BY`, or `LIMIT`.
- `isSelf` — `true` iff the row user is the calling viewer (computed where the route maps the page to the response; no SQL). A self row always has `followedByViewer = false` (the V6 CHECK forbids a self-edge).

Both are relative to the CALLING VIEWER, never to the profile owner `{user_id}`.

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
- **THEN** that row contains the keys `userId`, `username`, `displayName`, `isPremium`, `followedByViewer`, `isSelf`, `createdAt` with `username = "raka.jkt"` AND `displayName = "Raka Pratama"` AND contains NO snake_case variants (`user_id` / `display_name` / `is_premium` / `followed_by_viewer` / `is_self` / `created_at`)

#### Scenario: followedByViewer reflects the calling viewer's follow edge, not the profile owner's
- **WHEN** profile P has followers `[X, Y]` AND the caller is V (V != P) AND V follows X but not Y AND P follows Y
- **THEN** X's row has `followedByViewer = true` AND Y's row has `followedByViewer = false` AND both rows have `isSelf = false`

#### Scenario: The viewer's own row is flagged isSelf
- **WHEN** profile P has followers `[V, X]` AND the caller is V
- **THEN** V's row has `isSelf = true` AND `followedByViewer = false` AND X's row has `isSelf = false`

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

The profile-target resolution (constant-404 contract), row sourcing (INNER `JOIN visible_users` on the row user — here `followee_id`), bidirectional viewer-block filtering, profile-summary response shape, camelCase wire declaration, profile-read `isPremium` formula, viewer-relative row state (`followedByViewer` + `isSelf`), and cursor format are identical to `/followers`; `i` encodes the `followee_id` UUID of the row at the page boundary.

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
- **THEN** that row carries `username = "sari.bdg"` AND `displayName = "Sari Lestari"` AND an `isPremium` boolean AND `followedByViewer` + `isSelf` booleans AND NO snake_case key variants

#### Scenario: Own following list rows are all followedByViewer
- **WHEN** caller V calls `GET /api/v1/users/V/following` AND V follows `[X, Y]`
- **THEN** every row has `followedByViewer = true` AND `isSelf = false`

#### Scenario: Third-party following list carries the viewer's follow state
- **WHEN** profile P follows `[X, Y, V]` AND the caller is V AND V follows Y only
- **THEN** Y's row has `followedByViewer = true` AND X's row has `followedByViewer = false` AND V's row has `isSelf = true` with `followedByViewer = false`

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
24. `/followers` rows carry `followedByViewer` relative to the CALLING viewer (true for a row the viewer follows, false otherwise — independent of the profile owner's own edges) AND `isSelf = true` + `followedByViewer = false` on the viewer's own row.
25. `/following` rows carry `followedByViewer` relative to the calling viewer (all `true` on the viewer's own following list; per-row on a third-party list) AND `isSelf` on the viewer's own row.
26. Every row's key set includes `followedByViewer` + `isSelf` and no snake_case variant of either.

`MigrationV6SmokeTest` (tagged `database`) SHALL cover: migration runs cleanly from V5, both indexes exist with documented column orders, PK enforces uniqueness, CHECK enforces self-follow rejection, both FK cascades behave as specified.

#### Scenario: Both test classes discoverable
- **WHEN** running `./gradlew :backend:ktor:test --tests '*FollowEndpointsTest*' --tests '*MigrationV6SmokeTest*'`
- **THEN** both classes are discovered AND every numbered scenario above corresponds to at least one `@Test` method

