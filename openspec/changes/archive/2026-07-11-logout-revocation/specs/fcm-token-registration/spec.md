# fcm-token-registration — delta (logout-revocation)

## ADDED Requirements

### Requirement: Logout deletion path (owned by auth-session)

In addition to the on-send-prune (owned by `fcm-push-dispatch`) and stale-cleanup (owned by `scheduled-retention-cleanup`) GC paths, `user_fcm_tokens` rows SHALL be deleted on logout, owned by [`auth-session`](../../specs/auth-session/spec.md): `POST /api/v1/auth/logout` deletes the caller's row(s) for the optional `fcm_token` supplied in the request body, and `POST /api/v1/auth/logout-all` deletes ALL of the caller's rows in the same transaction as the refresh-token family deletion. The UNIQUE index on `(user_id, platform, token)` makes the single-token DELETE an index seek; no schema change is required. Deleted rows are re-created naturally on next sign-in by the stateless `FcmTokenRegistrar` re-registration (`mobile-fcm-token-registration`).

#### Scenario: Schema supports the logout delete shape
- **WHEN** `auth-session`'s logout handlers execute `DELETE FROM user_fcm_tokens WHERE user_id = ? AND token = ?` (single-device) or `DELETE FROM user_fcm_tokens WHERE user_id = ?` (logout-all)
- **THEN** the single-device DELETE resolves via the `(user_id, platform, token)` UNIQUE index prefix AND no schema migration is needed

#### Scenario: A signed-out account's device receives no further pushes
- **GIVEN** a user logs out on a device whose FCM token was registered
- **WHEN** a push-generating event for that user occurs after the logout completed
- **THEN** `fcm-push-dispatch` finds no `user_fcm_tokens` row for that device and sends nothing to it
