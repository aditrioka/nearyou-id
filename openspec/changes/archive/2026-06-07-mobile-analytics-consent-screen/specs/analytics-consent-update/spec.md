## ADDED Requirements

### Requirement: Authenticated PATCH /api/v1/user/consent persists the analytics-consent triple

The backend SHALL expose `PATCH /api/v1/user/consent` in a new `backend/ktor/src/main/kotlin/id/nearyou/app/user/ConsentRoutes.kt` (mirroring the authed-self pattern of `user/FcmTokenRoutes.kt`). The route SHALL require a valid RS256 access-token (the same `authenticate` guard the other `/api/v1/user/*` and timeline routes use); the user identity is the JWT `sub`. The request body SHALL be `{"analytics": Boolean, "crash": Boolean, "ads_personalization": Boolean}` (snake_case keys matching the `users.analytics_consent` JSONB sub-keys). On success the route SHALL **full-object-write** `users.analytics_consent` for the JWT-`sub` user (replacing the entire JSONB object with the three provided booleans — no partial merge) and respond `200` with the stored triple `{"analytics", "crash", "ads_personalization"}`. No new Flyway migration is introduced (the column shipped in `V2__auth_foundation.sql`).

#### Scenario: Authenticated request with the full triple writes the row and echoes it

- **GIVEN** a seeded user `U` whose `analytics_consent` is the V2 default `{"analytics": false, "crash": true, "ads_personalization": false}`, AND a valid access token for `U`
- **WHEN** `PATCH /api/v1/user/consent` is called with `Authorization: Bearer <U token>` and body `{"analytics": true, "crash": false, "ads_personalization": true}`
- **THEN** the response status is `200` AND the response body parses as `{"analytics": true, "crash": false, "ads_personalization": true}` AND `SELECT analytics_consent FROM users WHERE id = U` now equals `{"analytics": true, "crash": false, "ads_personalization": true}`

#### Scenario: Full-object write replaces the entire JSONB (no partial merge)

- **GIVEN** a user `U` whose `analytics_consent` is `{"analytics": true, "crash": true, "ads_personalization": true}`
- **WHEN** `PATCH /api/v1/user/consent` is called with body `{"analytics": false, "crash": false, "ads_personalization": false}`
- **THEN** `U`'s stored `analytics_consent` is exactly `{"analytics": false, "crash": false, "ads_personalization": false}` (the prior `true` values are fully replaced, not merged) AND the object contains exactly the three canonical keys and no others

### Requirement: The consent write is scoped to the caller's own row only

The `UPDATE` SHALL target only the JWT-`sub` user's row (`WHERE id = :jwtSub`). A caller's request SHALL NOT modify any other user's `analytics_consent`. This is an own-content write (allowed raw per the shadow-ban carve-out for own-content / Repository paths); it changes no RLS policy, so the "RLS policy change → JWT-`sub`-not-in-`public.users`→deny" test does not apply, but the own-row authorization SHALL be verified.

#### Scenario: A user's PATCH does not affect another user's consent

- **GIVEN** two seeded users `A` and `B`, both at the V2 default consent, AND a valid access token for `A`
- **WHEN** `A` calls `PATCH /api/v1/user/consent` with body `{"analytics": true, "crash": true, "ads_personalization": true}`
- **THEN** `A`'s `analytics_consent` becomes `{"analytics": true, "crash": true, "ads_personalization": true}` AND `B`'s `analytics_consent` remains the unchanged V2 default `{"analytics": false, "crash": true, "ads_personalization": false}`

### Requirement: Unauthenticated or invalid-token requests are rejected with 401

`PATCH /api/v1/user/consent` SHALL return `401` when the request carries no bearer token or a token that fails RS256 verification, and SHALL NOT write any row.

#### Scenario: Missing bearer token is rejected

- **WHEN** `PATCH /api/v1/user/consent` is called with a well-formed body but no `Authorization` header
- **THEN** the response status is `401` AND no `users` row is modified

#### Scenario: Invalid token is rejected

- **WHEN** `PATCH /api/v1/user/consent` is called with `Authorization: Bearer not-a-valid-jwt` and a well-formed body
- **THEN** the response status is `401` AND no `users` row is modified

### Requirement: Malformed or partial bodies are rejected with 400 and never partially apply

The route SHALL reject a body that is not the complete `{analytics, crash, ads_personalization}` triple of booleans — a **missing key** or a **non-boolean value** SHALL produce `400` with no write (achieved via non-nullable `Boolean` DTO fields: a missing key raises `MissingFieldException` and a type mismatch raises a `SerializationException`, both mapped to `400`). Rationale: the full-object-write semantics require all three keys; accepting a partial body would silently default the omitted keys. **Unknown extra keys are ignored, NOT rejected** — this is the app-wide `ContentNegotiation { Json { ignoreUnknownKeys = true } }` posture (`backend/.../Application.kt`) and matches the `FcmTokenRoutes` precedent; the endpoint does not introduce a route-local strict `Json` to reject them (an extra key cannot corrupt the write, since only the three canonical fields are read).

#### Scenario: Missing a key is rejected

- **GIVEN** a valid access token for user `U`
- **WHEN** `PATCH /api/v1/user/consent` is called with body `{"analytics": true, "crash": false}` (no `ads_personalization`)
- **THEN** the response status is `400` AND `U`'s `analytics_consent` is unchanged

#### Scenario: A non-boolean value is rejected

- **GIVEN** a valid access token for user `U`
- **WHEN** `PATCH /api/v1/user/consent` is called with body `{"analytics": "yes", "crash": false, "ads_personalization": false}`
- **THEN** the response status is `400` AND `U`'s `analytics_consent` is unchanged

### Requirement: The consent route never logs the token, sub, or request/response body

The route handler SHALL NOT log the bearer token, the JWT `sub`, or the consent body at any level. The consent values are non-sensitive but the token/`sub` are PII-adjacent (consistent with the project's PII-discipline posture); the `LogLevel` of any client/server logging in this path MUST NOT include request/response bodies.

#### Scenario: No token or sub appears in the consent route source as a log argument

- **WHEN** inspecting `backend/ktor/src/main/kotlin/id/nearyou/app/user/ConsentRoutes.kt`
- **THEN** no logging call site passes the bearer token, the raw `Authorization` header, or the JWT `sub` as a logged argument
