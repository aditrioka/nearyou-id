## MODIFIED Requirements

### Requirement: ProfileApiClient parses the SHIPPED camelCase profile wire with null tolerance

`ProfileApiClient` (in `mobile/app/src/commonMain/kotlin/id/nearyou/app/profile/`) SHALL issue `GET /api/v1/users/{user_id}` and parse a `@Serializable UserProfileResponse` whose field names match the SHIPPED backend serialization in `backend/ktor/.../user/` (`user-profile-read`), NOT a stale spec JSON example. The DTO SHALL be **camelCase**: `userId: String`, `username: String`, `displayName: String`, `bio: String? = null`, `followerCount: Int`, `followingCount: Int`, `isSelf: Boolean`, `followedByViewer: Boolean`, `isPremium: Boolean`, `isPrivate: Boolean? = null`, `usernameLastChangedAt: String? = null`. `bio`, `isPrivate` and `usernameLastChangedAt` MUST be nullable-with-default because the app-wide `Json { explicitNulls = false }` OMITS them from the JSON when null (an absent key MUST decode to `null`, not throw). The repository maps `usernameLastChangedAt` to the domain `UserProfile.usernameLastChangedAt: Instant?` fail-soft: an unparseable value maps to `null` (never a failed read), since the field only drives advisory UI and the server stays authoritative. The Bearer `Authorization` header is attached by the shipped `HttpClient` `Auth` plugin (this capability MUST NOT reimplement token attachment). No `X-Session-Id` header (the profile read is not per-session soft-capped). `CancellationException` MUST be rethrown, never mapped to a failure.

#### Scenario: Parses the shipped camelCase wire with bio present and isPrivate omitted

- **GIVEN** a MockEngine returning `200` with `{ "userId": "...", "username": "raka.jkt", "displayName": "Raka Pratama", "bio": "halo", "followerCount": 3, "followingCount": 5, "isSelf": false, "followedByViewer": true, "isPremium": false }` (no `isPrivate` key)
- **WHEN** the body is parsed
- **THEN** parsing succeeds AND `bio = "halo"`, `followedByViewer = true`, AND `isPrivate` decodes to `null` (absent tolerated)

#### Scenario: Omitted bio decodes to null

- **GIVEN** a `200` body with no `bio` key and all required fields present
- **WHEN** the body is parsed
- **THEN** parsing succeeds with `bio = null` (no `SerializationException`)

#### Scenario: The self-only last-changed time maps to an Instant, absent or malformed to null

- **GIVEN** a self-read `200` body carrying `"usernameLastChangedAt": "2026-10-01T08:00:00Z"`, and separately one without the key, and separately one carrying `"usernameLastChangedAt": "not-a-date"`
- **WHEN** the repository loads each profile
- **THEN** each is `ProfileOutcome.Loaded` AND `usernameLastChangedAt` is `Instant.parse("2026-10-01T08:00:00Z")`, `null`, and `null` respectively

#### Scenario: snake_case body does not bind (negative guard)

- **GIVEN** a `200` body using snake_case `user_id` / `display_name` / `follower_count` (a stale-spec JSON shape, NOT the shipped wire)
- **WHEN** the body is parsed into `UserProfileResponse`
- **THEN** the camelCase fields are NOT populated from the snake_case keys (the casing-drift trap is guarded, per the PR #128 precedent)
