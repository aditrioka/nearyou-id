# follow-system — Delta Specification

## MODIFIED Requirements

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

### Requirement: GET /api/v1/users/{user_id}/followers lists profile followers (paginated, viewer-block-filtered)

A Ktor route SHALL be registered at `GET /api/v1/users/{user_id}/followers?cursor=` requiring Bearer JWT auth. The endpoint MUST return the set of users who follow `{user_id}`, paginated by keyset on `(created_at DESC, follower_id DESC)` with a per-page cap of 30.

**Profile-target resolution (constant-404 contract).** Before reading the list, the profile target MUST be resolved under the `user-profile-read` contract: when `{user_id}` equals the caller, via raw `users` existence on the own-content path (a shadow-banned caller keeps their own lists; the raw-`users` SQL carries the `@AllowMissingBlockJoin` allowlist annotation on the SQL-holding declaration); otherwise via `visible_users` plus a bidirectional `user_blocks` exclusion. An unresolvable target — unknown UUID, soft-deleted, shadow-banned, or blocked-in-either-direction — MUST yield HTTP 404 with the CONSTANT byte-identical body `{"error":{"code":"user_not_found"}}` (no `message` field), identical to the `user-profile-read` 404 body.

**Row sourcing and filtering.** The list query MUST source rows `FROM follows` with an INNER `JOIN visible_users` on the row user (`follower_id`) — the join `docs/05-Implementation.md` §1272 mandates — so shadow-banned and soft-deleted followers do NOT appear. The returned set MUST also exclude, via the two bidirectional `user_blocks` NOT-IN subqueries on the `follows` clause (which MUST both remain present so `BlockExclusionJoinRule` continues to pass):
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

(The example reflects the SHIPPED bare-camelCase wire of `FollowRoutes.kt` — `userId`/`createdAt`/`nextCursor`; the pre-change spec text showed a snake_case example that never matched the shipped DTOs, corrected here following the `mobile-timeline-card-redesign` precedent.) The new fields are declared EXPLICITLY as bare camelCase `username` / `displayName` / `isPremium` (no `@SerialName`), matching the `UserProfileResponse` vocabulary. `username` and `displayName` MUST equal the row user's `visible_users.username` / `visible_users.display_name` (never null — NOT NULL since V2). `isPremium` MUST be computed as `subscription_status = 'premium_active'` (the `user-profile-read` design-D2 formula; deliberately NOT the `chat-conversations` two-status variant). `createdAt` is the follow-edge timestamp.

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

#### Scenario: isPremium uses the design-D2 formula
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

The profile-target resolution (constant-404 contract), row sourcing (INNER `JOIN visible_users` on the row user — here `followee_id`), bidirectional viewer-block filtering, profile-summary response shape, camelCase wire declaration, design-D2 `isPremium` formula, and cursor format are identical to `/followers`; `i` encodes the `followee_id` UUID of the row at the page boundary.

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
21. `isPremium` is `true` for `premium_active` and `false` for `premium_billing_retry` (design-D2 formula).
22. All five follow endpoints return 401 without JWT.

`MigrationV6SmokeTest` (tagged `database`) SHALL cover: migration runs cleanly from V5, both indexes exist with documented column orders, PK enforces uniqueness, CHECK enforces self-follow rejection, both FK cascades behave as specified.

#### Scenario: Both test classes discoverable
- **WHEN** running `./gradlew :backend:ktor:test --tests '*FollowEndpointsTest*' --tests '*MigrationV6SmokeTest*'`
- **THEN** both classes are discovered AND every numbered scenario above corresponds to at least one `@Test` method

## REMOVED Requirements

### Requirement: Mutual-block rejects follow with 409

**Reason**: The 409 `follow_blocked` status is itself a cross-user leak: its body hides the block direction, but receiving 409 instead of 404 tells a caller who has NOT blocked the target that the target blocked them, and distinguishes "blocked" from "gone" — the exact differential the `user-profile-read` constant-404 design (D4) closes. The newer profile-read posture supersedes the V6-era 409 contract before any client codes against it.

**Migration**: Blocked pairs now answer the CONSTANT 404 under the modified "Follow target user must exist" requirement, which also absorbs the pre-insert bidirectional `user_blocks` guard text (the in-transaction SELECT + user-pair advisory lock are unchanged — only the HTTP mapping changes). The `FOLLOW_BLOCKED_BODY` constant and the 409 scenarios are removed; clients distinguish nothing beyond "target not available".
