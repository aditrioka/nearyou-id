## ADDED Requirements

### Requirement: Authenticated GET /api/v1/user/consent returns the caller's stored consent triple

The backend SHALL expose `GET /api/v1/user/consent` inside the same `consentRoutes(...)` registration and the same `authenticate` guard as the `PATCH` (file `backend/ktor/src/main/kotlin/id/nearyou/app/user/ConsentRoutes.kt`; no new `Application.kt` wiring). The user identity SHALL be the JWT `sub`; the route accepts no `user_id` parameter. On success it SHALL respond `200` with the stored triple, in exactly the `PATCH` `200` wire shape: `{"analytics": Boolean, "crash": Boolean, "ads_personalization": Boolean}` (snake_case).

The read SHALL meet these constraints:

- It SHALL select only the caller's row: `SELECT … FROM users WHERE id = :jwtSub`. This is an own-row read of raw `users`, annotated `@AllowMissingBlockJoin` per the `HideDistanceRepository.SQL_SELECT` precedent (a shadow-banned caller reads their own settings; you cannot block yourself).
- It SHALL default each key independently to the V2 column default (`analytics` → `false`, `crash` → `true`, `ads_personalization` → `false`) when that key is absent from the stored object. A missing `crash` is never reported as a decline.
- A missing caller row is unreachable post-auth (`configureUserJwt` validates `sub ∈ public.users`). If it happens anyway, it SHALL surface as `500`, never as fabricated defaults.
- A read failure SHALL respond `500` and log only the content-free event `analytics_consent_read_failed` with the exception class: no token, no `sub`, no body. The existing "never logs the token, sub, or request/response body" requirement covers this handler too.
- No new Flyway migration is introduced.

A request with no bearer token or an invalid token SHALL be rejected with `401` by the shared guard.

#### Scenario: A fresh account reads the V2 default

- **GIVEN** a seeded user `U` whose `analytics_consent` is the V2 default AND a valid access token for `U`
- **WHEN** `GET /api/v1/user/consent` is called with `Authorization: Bearer <U token>`
- **THEN** the response status is `200` AND the body parses as `{"analytics": false, "crash": true, "ads_personalization": false}`

#### Scenario: The read returns the value the PATCH stored

- **GIVEN** a user `U` who called `PATCH /api/v1/user/consent` with `{"analytics": true, "crash": false, "ads_personalization": true}` and received `200`
- **WHEN** `U` calls `GET /api/v1/user/consent`
- **THEN** the response status is `200` AND the body parses as `{"analytics": true, "crash": false, "ads_personalization": true}`

#### Scenario: The read is scoped to the caller's own row

- **GIVEN** two seeded users `A` (stored `{"analytics": true, "crash": false, "ads_personalization": true}`) and `B` (the V2 default) AND a valid access token for `B`
- **WHEN** `B` calls `GET /api/v1/user/consent`
- **THEN** the body is `B`'s V2 default `{"analytics": false, "crash": true, "ads_personalization": false}`, not `A`'s triple

#### Scenario: A key missing from the stored object reads as its V2 default

- **GIVEN** a user `U` whose stored `analytics_consent` is `{"analytics": true}` (no `crash`, no `ads_personalization`)
- **WHEN** `U` calls `GET /api/v1/user/consent`
- **THEN** the body is `{"analytics": true, "crash": true, "ads_personalization": false}`

#### Scenario: Missing bearer token is rejected

- **WHEN** `GET /api/v1/user/consent` is called with no `Authorization` header
- **THEN** the response status is `401`

#### Scenario: Invalid token is rejected

- **WHEN** `GET /api/v1/user/consent` is called with `Authorization: Bearer not-a-valid-jwt`
- **THEN** the response status is `401`
