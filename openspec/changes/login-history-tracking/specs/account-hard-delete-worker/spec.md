## MODIFIED Requirements

### Requirement: Hard-delete cascade-deletes ephemeral and relational data

In the same transaction the worker SHALL explicitly `DELETE` (the FK `ON DELETE CASCADE` does not fire because the `users` row is not row-deleted): the user's session and refresh tokens (all families → `refresh_tokens`), follow edges in BOTH directions (`follows`), `user_blocks` in BOTH directions, FCM tokens (`user_fcm_tokens`), notifications addressed to the user (`notifications`), and the user's login-history rows (`login_events` — the durable login/session trail). Deleting the token and login-history rows terminates any live session and erases the departing user's stored IP / device-fingerprint / provider-identifier history. Note: `docs/06` also lists "non-post location history" in the cascade set, but location-on-open is request-only / not persisted (`docs/03` § Location Permission + § Retention) — there is no such table in the current schema, so that item is a **no-op today**; if a location-history table is ever added it MUST join this cascade.

#### Scenario: Cascade tables are emptied for the deleted user
- **WHEN** the worker hard-deletes a user who had follows (both directions), blocks (both directions), FCM tokens, addressed notifications, login-history rows, and active sessions
- **THEN** afterward zero `follows`, `user_blocks`, `user_fcm_tokens`, addressed `notifications`, `login_events`, and session/refresh-token rows reference that user

#### Scenario: Blocks are deleted in both directions
- **WHEN** the deleted user had blocked user X and user Y had blocked the deleted user
- **THEN** both `user_blocks` rows are removed (the both-directions decision, design Q2 — accepted ghost-post edge case)

#### Scenario: Login-history rows are deleted on hard-delete
- **WHEN** the worker hard-deletes a user who has `login_events` rows
- **THEN** afterward zero `login_events` rows reference that user (the explicit delete fires because the FK `ON DELETE CASCADE` does not — the user row is tombstoned, not row-deleted)
