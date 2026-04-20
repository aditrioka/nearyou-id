## MODIFIED Requirements

### Requirement: Existing-user sign-in only (signup deferred)

`POST /api/v1/auth/signin` SHALL NOT create users. If the provider-subject lookup returns no row, the endpoint MUST respond HTTP 404 with code `user_not_found`. User creation is the responsibility of the distinct `POST /api/v1/auth/signup` endpoint defined in the `auth-signup` capability. Callers that receive `user_not_found` from `/signin` SHOULD retry through `/signup` after collecting the `date_of_birth` required for account creation.

#### Scenario: Unknown provider id
- **WHEN** verification succeeds but no `users` row matches the hashed provider id
- **THEN** the response is HTTP 404 with code `user_not_found`

#### Scenario: Existing user signs in
- **WHEN** verification succeeds and a `users` row matches, with `is_banned = FALSE` and `suspended_until` null/past
- **THEN** the response is HTTP 200 with new `access_token` and `refresh_token` and `expires_in == 900`

#### Scenario: Signup path is distinct
- **WHEN** a client attempts to create a new user by calling `/signin` with an unknown provider subject
- **THEN** the response is HTTP 404 `user_not_found` (not 201, not auto-creation); the correct path is `POST /api/v1/auth/signup`
