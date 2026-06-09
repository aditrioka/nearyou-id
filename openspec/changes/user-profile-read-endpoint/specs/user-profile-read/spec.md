## ADDED Requirements

### Requirement: GET /api/v1/users/{user_id} returns a single user's profile

A Ktor route SHALL be registered at `GET /api/v1/users/{user_id}` requiring Bearer JWT auth via the `AUTH_PROVIDER_USER` provider. On success it MUST return `200 OK` with a JSON `UserProfileResponse` body projecting the target user. The response shape MUST be (camelCase keys, matching the repo's timeline-DTO wire convention):

```json
{
  "userId": "<uuid>",
  "username": "<string>",
  "displayName": "<string>",
  "bio": "<string|null>",
  "followerCount": 0,
  "followingCount": 0,
  "isSelf": false,
  "followedByViewer": false,
  "isPremium": false,
  "suspendedUntil": null,
  "isPrivate": null
}
```

`bio` MUST be `null` when the user has no bio. `isPremium` MUST be `true` if and only if the target's `subscription_status` is `premium_active` (`free` and `premium_billing_retry` both read as `false`). `followedByViewer` MUST reflect whether the calling viewer has a `follows` edge to the target, and MUST be `false` whenever `isSelf` is `true`. `suspendedUntil` and `isPrivate` are self-only (see the self-only-state requirement below).

#### Scenario: Viewer reads another user's public profile
- **WHEN** an authenticated viewer V calls `GET /api/v1/users/{T}` for an existing, non-blocked, non-shadow-banned user T whom V does not follow
- **THEN** the response is `200` with `userId = T`, the target's `username` / `displayName` / `bio`, `isSelf = false`, `followedByViewer = false`, and `suspendedUntil = null` and `isPrivate = null`

#### Scenario: followedByViewer reflects an existing follow edge
- **WHEN** an authenticated viewer V who follows T calls `GET /api/v1/users/{T}`
- **THEN** the response is `200` with `followedByViewer = true`

#### Scenario: isPremium reflects subscription status
- **WHEN** an authenticated viewer reads a target whose `subscription_status` is `premium_active`
- **THEN** the response `isPremium` is `true`
- **WHEN** the target's `subscription_status` is `free` or `premium_billing_retry`
- **THEN** the response `isPremium` is `false`

### Requirement: Profile read resolves the viewer's own profile via the own-content path

When `{user_id}` equals the calling viewer's id, the endpoint MUST set `isSelf = true` and MUST resolve the profile through the Repository own-content path (reading raw `users`, the shadow-ban invariant's documented own-content exception), so that a shadow-banned viewer can still read their own profile. `followedByViewer` MUST be `false` for the self read.

#### Scenario: Viewer reads own profile
- **WHEN** an authenticated viewer V calls `GET /api/v1/users/{V}`
- **THEN** the response is `200` with `isSelf = true` and `followedByViewer = false`

#### Scenario: Shadow-banned viewer reads own profile
- **WHEN** an authenticated viewer V who is shadow-banned (`is_shadow_banned = TRUE`) calls `GET /api/v1/users/{V}`
- **THEN** the response is `200` with `isSelf = true` (the own-content path does not filter the viewer's own shadow-banned row)

### Requirement: Other-user reads are shadow-ban-safe via visible_users

For any read where `{user_id}` is NOT the calling viewer, the repository MUST resolve the target through the `visible_users` view (never raw `FROM users`). A target that is soft-deleted (`deleted_at IS NOT NULL`) or shadow-banned (`is_shadow_banned = TRUE`) MUST therefore be unresolvable and MUST produce `404 user_not_found`. Unknown UUIDs MUST also produce `404 user_not_found`.

#### Scenario: Unknown target
- **WHEN** an authenticated viewer calls `GET /api/v1/users/<uuid that does not exist>`
- **THEN** the response is `404` with body `{"error":{"code":"user_not_found"}}`

#### Scenario: Shadow-banned target is invisible to others
- **WHEN** an authenticated viewer V calls `GET /api/v1/users/{T}` where T (T != V) is shadow-banned
- **THEN** the response is `404 user_not_found` (identical body to the unknown-target case)

#### Scenario: Soft-deleted target is invisible
- **WHEN** an authenticated viewer V calls `GET /api/v1/users/{T}` where T (T != V) has `deleted_at` set
- **THEN** the response is `404 user_not_found`

### Requirement: Profile read is bidirectional-block-aware and leak-safe

The read MUST be filtered against `user_blocks` in BOTH directions: if the calling viewer has blocked the target, OR the target has blocked the viewer, the endpoint MUST return `404 user_not_found` with a CONSTANT, byte-identical body and NO direction hint — indistinguishable from the unknown-target response. The block predicate MUST be expressed as a bidirectional `user_blocks` exclusion satisfying `BlockExclusionJoinRule`. This requirement does not apply to the self read (a viewer cannot block themselves).

#### Scenario: Viewer has blocked the target
- **WHEN** an authenticated viewer V who has blocked T calls `GET /api/v1/users/{T}`
- **THEN** the response is `404` with body `{"error":{"code":"user_not_found"}}` (no indication that a block exists or which direction)

#### Scenario: Target has blocked the viewer
- **WHEN** an authenticated viewer V calls `GET /api/v1/users/{T}` where T has blocked V
- **THEN** the response is `404` with body `{"error":{"code":"user_not_found"}}`, byte-identical to the viewer-blocked-target and unknown-target responses

### Requirement: Profile read rejects malformed and unauthenticated requests

A request with a `{user_id}` path segment that is not a valid UUID MUST return `400 invalid_request` without performing any read. A request without a valid authenticated principal MUST return `401 Unauthorized`.

#### Scenario: Malformed user_id
- **WHEN** an authenticated viewer calls `GET /api/v1/users/not-a-uuid`
- **THEN** the response is `400` with error code `invalid_request`

#### Scenario: Unauthenticated request
- **WHEN** a caller without a valid Bearer JWT calls `GET /api/v1/users/{any-uuid}`
- **THEN** the response is `401`

### Requirement: Follower and following counts are raw totals

`followerCount` MUST equal the total number of `follows` edges whose followee is the target, and `followingCount` MUST equal the total number of `follows` edges whose follower is the target. These counts MUST NOT be viewer-block-filtered — they are deliberately asymmetric with the `/followers` and `/following` list endpoints (which ARE bidirectionally viewer-block-filtered), because a follower count is a public aggregate and per-viewer filtering would leak block state via count deltas.

#### Scenario: Counts reflect the follows graph
- **WHEN** target T is followed by 3 users and follows 5 users, and an authenticated viewer reads T's profile
- **THEN** the response has `followerCount = 3` and `followingCount = 5`

#### Scenario: Counts are not viewer-block-filtered
- **WHEN** target T has 3 followers, one of whom (X) has blocked the viewer V, and V reads T's profile
- **THEN** `followerCount` is still `3` (the raw total; the blocked follower is not subtracted from the count even though X would be excluded from the `/followers` list)

### Requirement: Suspension and privacy state are self-only

`suspendedUntil` and `isPrivate` MUST be populated only when `isSelf = true`. For any other-user read they MUST be `null`. When `isSelf = true`: `suspendedUntil` MUST be the target's `suspended_until` as an ISO-8601 string (or `null` if not suspended); `isPrivate` MUST be `true` if `private_profile_opt_in = TRUE` OR (`privacy_flip_scheduled_at IS NOT NULL` AND the current time is before `privacy_flip_scheduled_at`), honoring the 72-hour privacy-flip grace window, else `false`.

#### Scenario: Own suspension countdown is exposed to self
- **WHEN** a suspended viewer V (`suspended_until` set to a future timestamp) reads their own profile
- **THEN** `isSelf = true` and `suspendedUntil` is the ISO-8601 string of V's `suspended_until`

#### Scenario: Suspension state is not leaked for other users
- **WHEN** an authenticated viewer V reads target T (T != V) where T has a non-null `suspended_until`
- **THEN** the response `suspendedUntil` is `null` (T's moderation state is not surfaced to V)

#### Scenario: Privacy flag is self-only and honors the grace window
- **WHEN** a viewer V whose `private_profile_opt_in = FALSE` but who is within the 72h grace (`privacy_flip_scheduled_at` is in the future) reads their own profile
- **THEN** `isSelf = true` and `isPrivate = true`
- **WHEN** the same V reads another user T (T != V)
- **THEN** `isPrivate` is `null`
