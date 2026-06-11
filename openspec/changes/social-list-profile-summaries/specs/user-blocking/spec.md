# user-blocking — Delta Specification

## MODIFIED Requirements

### Requirement: GET /api/v1/blocks lists outbound blocks (paginated)

A Ktor route SHALL be registered at `GET /api/v1/blocks?cursor=` requiring Bearer JWT auth. The endpoint MUST return outbound blocks (where `blocker_id = caller`) paginated by keyset on `(created_at DESC, blocked_id DESC)` with a per-page cap of 30.

**Row enrichment (chat-partner masking pattern).** Each row MUST embed the blocked user's profile summary sourced via **`LEFT JOIN visible_users`** with `COALESCE` to placeholder values — `username = 'akun_dihapus'`, `displayName = 'Akun Dihapus'`, `isPremium = false` — for blocked users that are shadow-banned or otherwise filtered out by `visible_users` (the `chat-conversations` list-conversations partner pattern). A block row MUST surface even when the blocked user is hidden: the list is the owner's only handle to find and unblock a target whose profile is no longer reachable (bidirectional exclusion 404s the blocked user's profile for the blocker). Identity masking MUST be driven ONLY by `visible_users` membership — there is NO block-direction masking (rows are the caller's own outbound blocks; masking on a counter-block would itself leak that the blocked user blocked back). `isPremium` for visible blocked users MUST be computed as `subscription_status = 'premium_active'` only (the `user-profile-read` `isPremium` formula).

**Response shape.** The response shape MUST be:

```json
{
  "blocks": [
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

(The example reflects the SHIPPED bare-camelCase wire of `BlockRoutes.kt` — `userId`/`createdAt`/`nextCursor`; the pre-change spec text showed a snake_case example that never matched the shipped DTOs, corrected here following the `mobile-timeline-card-redesign` precedent.) The new fields are declared EXPLICITLY as bare camelCase `username` / `displayName` / `isPremium` (no `@SerialName`), the same profile-summary vocabulary as the `follow-system` lists and `UserProfileResponse`. `createdAt` is the block-edge timestamp.

The cursor MUST use the same base64url-encoded JSON format as the `nearby-timeline` cursor (`{"c":"...","i":"..."}`), but the `i` field encodes the `blocked_id` UUID rather than a post UUID.

#### Scenario: Authenticated list returns own blocks only
- **WHEN** caller A has blocks `(A, B)` and `(A, C)` AND user X has block `(X, Y)` AND caller A calls `GET /api/v1/blocks`
- **THEN** the response contains exactly `[B, C]` (in `created_at DESC` order) AND does NOT contain Y

#### Scenario: Rows embed the blocked user's profile summary with exact camelCase keys
- **WHEN** caller A has blocked a visible user with `username = "raka.jkt"`, `display_name = "Raka Pratama"`, `subscription_status = 'premium_active'`
- **THEN** that row contains `userId`, `username = "raka.jkt"`, `displayName = "Raka Pratama"`, `isPremium = true`, `createdAt` AND contains NO snake_case key variants

#### Scenario: Hidden blocked user is masked but the row survives
- **WHEN** caller A has blocked user B AND B later becomes `is_shadow_banned = TRUE` (or `deleted_at IS NOT NULL`)
- **THEN** `GET /api/v1/blocks` still returns B's row AND its summary is `username = "akun_dihapus"`, `displayName = "Akun Dihapus"`, `isPremium = false` AND `userId` still carries B's UUID (so `DELETE /api/v1/blocks/{B}` remains possible)

#### Scenario: Counter-block does not mask identity
- **WHEN** caller A has blocked visible user B AND B has also blocked A
- **THEN** A's `GET /api/v1/blocks` row for B carries B's real `username`/`displayName` (masking is driven only by `visible_users` membership, never by block state)

#### Scenario: Page cap of 30 enforced
- **WHEN** caller A has 50 outbound blocks
- **THEN** the response `blocks` array contains exactly 30 entries AND `nextCursor` is non-null

#### Scenario: nextCursor null on last page
- **WHEN** the response contains <30 entries
- **THEN** `nextCursor` is `null`

### Requirement: Integration test coverage

`BlockEndpointsTest` (tagged `database`) SHALL cover:
1. Block creates row, returns 204.
2. Re-block idempotent (still 204, still one row).
3. Self-block rejected (400 `cannot_block_self`, no row inserted).
4. Block target not found (404 `user_not_found`).
5. Block of a shadow-banned target succeeds (204, row created).
6. Block of a soft-deleted target succeeds (204, row created).
7. Unblock removes row, returns 204.
8. Unblock no-op returns 204.
9. List returns own blocks only, ordered by `created_at DESC`.
10. List rows embed the blocked user's profile summary (exact camelCase keys asserted on the raw JSON, no snake_case variants).
11. List masks a hidden (shadow-banned or soft-deleted) blocked user via the COALESCE placeholders while the row survives with the real `userId`.
12. Counter-block does not mask identity.
13. List paginates correctly with cursor.
14. All three endpoints (`POST /blocks/{user_id}`, `DELETE /blocks/{user_id}`, `GET /blocks`) return 401 without JWT (corrects the previous enumeration's "four" — only three routes exist).

`MigrationV5SmokeTest` SHALL cover: migration runs cleanly, both indexes exist with the documented column orders, UNIQUE constraint present, CHECK constraint present, both FK cascades behave as specified.

#### Scenario: Both test classes discoverable
- **WHEN** running `./gradlew :backend:ktor:test --tests '*BlockEndpointsTest*' --tests '*MigrationV5SmokeTest*'`
- **THEN** both classes are discovered AND every numbered scenario above corresponds to at least one `@Test` method

## ADDED Requirements

### Requirement: Block actions deliberately retain existence-based semantics

`POST /api/v1/blocks/{user_id}` and `DELETE /api/v1/blocks/{user_id}` SHALL NOT adopt the constant-404 visibility contract that `user-profile-read`, the `follow-system` lists, and `POST /follows` use. Block-create MUST keep resolving the target against existence (raw `users` FK semantics): a shadow-banned or soft-deleted target MUST still be blockable (HTTP 204, `user_blocks` row created) — a user must always be able to block a harasser whose account has since been hidden, and chat or other surfaces may still expose hidden users' past activity. Only a hard-nonexistent target yields 404 `user_not_found`. `DELETE /blocks/{user_id}` remains 204 regardless of prior state.

The residual side-channel this preserves — `POST /blocks` answering 204 for a hidden-but-existing target versus 404 for a hard-nonexistent UUID — is ACCEPTED: exploiting it costs a real block mutation (which immediately hides the target from the prober bidirectionally) inside the 30/h block rate-limit bucket. This trade-off is deliberate (safety over oracle-closure) and any future change tightening block-action semantics MUST modify this requirement explicitly.

#### Scenario: Shadow-banned target still blockable
- **WHEN** caller A calls `POST /api/v1/blocks/T` where T has `is_shadow_banned = TRUE` and no prior block exists
- **THEN** the response is HTTP 204 AND a `user_blocks` row `(A, T)` exists

#### Scenario: Soft-deleted target still blockable
- **WHEN** caller A calls `POST /api/v1/blocks/T` where T has `deleted_at IS NOT NULL`
- **THEN** the response is HTTP 204 AND a `user_blocks` row `(A, T)` exists

#### Scenario: Hard-nonexistent target still 404s
- **WHEN** caller A calls `POST /api/v1/blocks/<uuid that has never existed>`
- **THEN** the response is HTTP 404 with `error.code = "user_not_found"`

#### Scenario: Negative guard — block actions are exempt from the constant-404 alignment
- **WHEN** auditing which endpoints implement the constant-404 visibility contract
- **THEN** `POST /api/v1/blocks/{user_id}` and `DELETE /api/v1/blocks/{user_id}` are documented exemptions per this requirement (the lists and follow endpoints are NOT exempt)
