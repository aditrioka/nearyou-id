# user-profile-read Specification

## Purpose

The user-profile-read capability provides `GET /api/v1/users/{user_id}` (Bearer JWT via `AUTH_PROVIDER_USER`) — the single-user profile projection that backs the mobile profile screen for both own and other-user reads. It returns identity (handle, display name, bio), the raw follower / following counts, the viewer's follow state, an actively-premium badge flag, and the self-only effective-private flag. Reads are shadow-ban-safe and bidirectional-block-aware: other-user reads go through `visible_users` while self reads use the own-content raw-`users` path (so a shadow-banned viewer still sees their own profile), and an unknown, soft-deleted, shadow-banned, or blocked-either-direction target collapses to one constant, direction-less `404 user_not_found`. Suspension state is deliberately NOT carried — suspension terminates the session (`is_banned` + `token_version` bump), so a suspended user is rejected at the auth boundary and the "suspended until" countdown belongs on the auth-rejection response, not this read.
## Requirements
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
  "isPrivate": null
}
```

Null-valued fields are OMITTED from the serialized JSON, NOT emitted as `"key": null` — the app-wide `ContentNegotiation` uses `Json { explicitNulls = false }`. Concretely: `bio` is absent when the user has no bio, and `isPrivate` is absent on every other-user read. The JSON above shows the logical shape; a consuming client MUST treat an absent `bio` / `isPrivate` key as `null` (declare the client DTO field nullable-with-default). `isPremium` MUST be `true` if and only if the target's `subscription_status` is `premium_active` (`free` and `premium_billing_retry` both read as `false`). `followedByViewer` MUST reflect whether the calling viewer has a `follows` edge to the target, and MUST be `false` whenever `isSelf` is `true`. `isPrivate` is self-only (see the self-only-state requirement below). The response MUST NOT include a suspension field — see "Suspension state is NOT carried by this endpoint" below.

#### Scenario: Viewer reads another user's public profile
- **WHEN** an authenticated viewer V calls `GET /api/v1/users/{T}` for an existing, non-blocked, non-shadow-banned user T whom V does not follow
- **THEN** the response is `200` with `userId = T`, the target's `username` / `displayName` / `bio`, `isSelf = false`, `followedByViewer = false`, and `isPrivate` absent/`null` (other-user read), and no `suspendedUntil` key

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

The read MUST be filtered against `user_blocks` in BOTH directions: if the calling viewer has blocked the target, OR the target has blocked the viewer, the endpoint MUST return `404 user_not_found` with a CONSTANT, byte-identical body and NO direction hint — indistinguishable from the unknown-target response. The block predicate MUST be expressed as a bidirectional `user_blocks` exclusion (a correctness requirement guarded by the both-direction 404 scenarios below — since the other-user read goes through `visible_users`, which does not trip `BlockExclusionJoinRule`, the linter does not enforce this and the scenarios are the guardrail). This requirement does not apply to the self read (a viewer cannot block themselves).

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

`followerCount` MUST equal the total number of `follows` edges whose followee is the target, and `followingCount` MUST equal the total number of `follows` edges whose follower is the target. These counts MUST NOT be viewer-block-filtered AND MUST NOT be visibility-filtered — they are deliberately asymmetric with the `/followers` and `/following` list endpoints (which are bidirectionally viewer-block-filtered AND, as of `social-list-profile-summaries`, visibility-filtered via `visible_users`), because a follower count is a public aggregate: per-viewer filtering would leak block state via count deltas, and visibility filtering would make every shadow-ban/unban visibly twitch public counters.

#### Scenario: Counts reflect the follows graph
- **WHEN** target T is followed by 3 users and follows 5 users, and an authenticated viewer reads T's profile
- **THEN** the response has `followerCount = 3` and `followingCount = 5`

#### Scenario: Counts are not viewer-block-filtered
- **WHEN** target T has 3 followers, one of whom (X) has blocked the viewer V, and V reads T's profile
- **THEN** `followerCount` is still `3` (the raw total; the blocked follower is not subtracted from the count even though X would be excluded from the `/followers` list)

#### Scenario: Counts are not visibility-filtered
- **WHEN** target T has 3 followers, one of whom is shadow-banned, and an authenticated viewer reads T's profile
- **THEN** `followerCount` is still `3` even though the `/followers` list returns only the 2 visible members (deliberate count/list asymmetry, design D1)

### Requirement: Suspension state is NOT carried by this endpoint

The response MUST NOT include any suspension field (`suspendedUntil` or equivalent). Suspension is a **session-terminating** state per `docs/02-Product.md` § Suspension — a 7-day suspension sets `is_banned = TRUE`, sets `suspended_until`, and increments `token_version` (kicking the session). A suspended principal is therefore rejected at the auth boundary (`403 account_suspended` / `account_banned`) on every `AUTH_PROVIDER_USER` route and can NEVER reach this read; the unban worker also nulls `suspended_until` once the window elapses, so the value could never be non-null for any user who can authenticate. Per the established industry pattern (account-suspension state is surfaced at the auth boundary with a structured error body carrying the reason + expiry, NOT on a protected resource read), the suspension countdown is the concern of the auth-rejection / token-refresh response, not of the profile read. Surfacing the countdown there is tracked as a separate `follow-up`-labelled issue.

#### Scenario: Profile response has no suspension field
- **WHEN** an authenticated viewer reads any profile (own or other)
- **THEN** the JSON response contains no `suspendedUntil` key (suspension state is never carried by this endpoint)

### Requirement: Privacy state is self-only

`isPrivate` MUST be populated only when `isSelf = true`. For any other-user read it MUST be `null`. When `isSelf = true`, `isPrivate` MUST be the canonical "effective private" value from `docs/05-Implementation.md` § Effective private:

```
isPrivate = (private_profile_opt_in = TRUE AND subscription_status IN ('premium_active','premium_billing_retry'))
            OR (privacy_flip_scheduled_at IS NOT NULL AND now() < privacy_flip_scheduled_at)
```

The premium-status conjunct is required — private profile is a Premium-only feature, so a Free user with a stale `private_profile_opt_in = TRUE` MUST read `isPrivate = false`. The second term is the 72-hour privacy-flip grace short-circuit; it is forward-looking plumbing (the `/internal/privacy-flip-worker` that sets `privacy_flip_scheduled_at` is DESIGN-status per `docs/05-Implementation.md`, so in practice the column is null today — the term is included for forward-compatibility, not a live flow). The value is `false` when neither term holds.

#### Scenario: Privacy flag requires the premium-status conjunct
- **WHEN** a self-reading viewer has `private_profile_opt_in = TRUE` AND `subscription_status = 'premium_active'` (and no grace scheduled)
- **THEN** `isPrivate = true`
- **WHEN** a self-reading viewer has `private_profile_opt_in = TRUE` but `subscription_status = 'free'` (and no grace scheduled)
- **THEN** `isPrivate = false` (the stale opt-in does not make a Free user effectively private)

#### Scenario: Privacy flag honors the grace short-circuit
- **WHEN** a self-reading viewer whose effective-private base term is false (e.g. `subscription_status = 'free'`) is within the 72h grace (`privacy_flip_scheduled_at` is in the future)
- **THEN** `isPrivate = true`
- **WHEN** the grace has elapsed (`privacy_flip_scheduled_at` is in the PAST) and the base term is false
- **THEN** `isPrivate = false`

#### Scenario: Privacy flag is self-only
- **WHEN** a viewer V reads another user T (T != V), regardless of T's `private_profile_opt_in`
- **THEN** `isPrivate` is `null`

