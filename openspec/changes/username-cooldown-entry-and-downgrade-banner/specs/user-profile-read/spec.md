## MODIFIED Requirements

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
  "isPrivate": null,
  "usernameLastChangedAt": null
}
```

Null-valued fields are OMITTED from the serialized JSON, NOT emitted as `"key": null` — the app-wide `ContentNegotiation` uses `Json { explicitNulls = false }`. Concretely: `bio` is absent when the user has no bio, and `isPrivate` and `usernameLastChangedAt` are absent on every other-user read (and `usernameLastChangedAt` is also absent on the self read of a user who never changed their username). The JSON above shows the logical shape; a consuming client MUST treat an absent `bio` / `isPrivate` / `usernameLastChangedAt` key as `null` (declare the client DTO field nullable-with-default). `isPremium` MUST be `true` if and only if the target's `subscription_status` is `premium_active` (`free` and `premium_billing_retry` both read as `false`). `followedByViewer` MUST reflect whether the calling viewer has a `follows` edge to the target, and MUST be `false` whenever `isSelf` is `true`. `isPrivate` and `usernameLastChangedAt` are self-only (see the self-only-state requirements below). The response MUST NOT include a suspension field — see "Suspension state is NOT carried by this endpoint" below.

#### Scenario: Viewer reads another user's public profile
- **WHEN** an authenticated viewer V calls `GET /api/v1/users/{T}` for an existing, non-blocked, non-shadow-banned user T whom V does not follow
- **THEN** the response is `200` with `userId = T`, the target's `username` / `displayName` / `bio`, `isSelf = false`, `followedByViewer = false`, `isPrivate` absent/`null` and `usernameLastChangedAt` absent (other-user read), and no `suspendedUntil` key

#### Scenario: followedByViewer reflects an existing follow edge
- **WHEN** an authenticated viewer V who follows T calls `GET /api/v1/users/{T}`
- **THEN** the response is `200` with `followedByViewer = true`

#### Scenario: isPremium reflects subscription status
- **WHEN** an authenticated viewer reads a target whose `subscription_status` is `premium_active`
- **THEN** the response `isPremium` is `true`
- **WHEN** the target's `subscription_status` is `free` or `premium_billing_retry`
- **THEN** the response `isPremium` is `false`

## ADDED Requirements

### Requirement: The username last-changed time is self-only

`usernameLastChangedAt` MUST be populated only when `isSelf = true`, and MUST then equal the caller's `users.username_last_changed_at` serialized as an ISO-8601 UTC instant string (`java.time.Instant.toString()`). It MUST be absent when that column is `NULL` — a user who never changed their username (signup does not set the column; only the `premium-username-customization` `PATCH` transaction writes it). For any other-user read it MUST be absent: the other-user query MUST NOT select the column, so when another user last renamed is never disclosed. Clients use the field for the proactive 30-day cooldown and as the "previously customized" signal (`mobile-premium-username`); the server-side cooldown stays authoritative on the `PATCH`. This is a read of the column only — the username write allowlist (`openspec/project.md` § Username writes) is unaffected.

#### Scenario: A user who never changed their username has no last-changed time
- **WHEN** a self-reading viewer whose `username_last_changed_at` is `NULL` calls `GET /api/v1/users/{self}`
- **THEN** the response is `200` AND the `usernameLastChangedAt` key is absent

#### Scenario: A user who changed their username reads the time
- **WHEN** a self-reading viewer whose `username_last_changed_at` is `T` calls `GET /api/v1/users/{self}`
- **THEN** the response `usernameLastChangedAt` is the ISO-8601 string of `T`, and it parses back to the same instant

#### Scenario: Another user's last-changed time is never disclosed
- **WHEN** a viewer V reads another user T (T != V) whose `username_last_changed_at` is set
- **THEN** the `usernameLastChangedAt` key is absent from the response
