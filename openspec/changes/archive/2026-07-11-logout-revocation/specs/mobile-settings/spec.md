# mobile-settings — delta (logout-revocation)

## MODIFIED Requirements

### Requirement: Logout clears the token store and routes to sign-in

The LAINNYA > "Keluar" row SHALL, after a confirmation dialog (`stringResource` copy: title + body + confirm/cancel), first best-effort revoke the session server-side and then clear the persisted session by wiping `SecureTokenStore` and emit a navigation event routing to the sign-in surface via `replaceAll` (so the authenticated back stack is cleared and a system back gesture cannot return to the authenticated surface). The confirm and cancel affordances SHALL be sourced via `:shared:resources`.

The server-side revoke SHALL be a single `POST /api/v1/auth/logout` call carrying the stored refresh token and, when `FcmTokenProvider` yields one, the device's current FCM token as `fcm_token` — issued BEFORE the token wipe (the call needs the still-stored Bearer). The call is best-effort: any transport failure, timeout, or non-2xx response SHALL be swallowed (a token-free diagnostic line MAY be logged) and SHALL NOT block, delay indefinitely, or abort the local wipe and re-route — logout MUST complete locally even when fully offline. No retry is attempted; a missed revoke degrades to client-side-only logout (the refresh token expires naturally).

#### Scenario: Confirming logout revokes server-side then wipes the token store and routes to sign-in

- **GIVEN** `SettingsScreen` with a populated `SecureTokenStore` and the logout confirmation dialog shown
- **WHEN** the confirm affordance is activated
- **THEN** `POST /api/v1/auth/logout` is issued with the stored refresh token (and the current FCM token when available) BEFORE the wipe, AND `SecureTokenStore` is cleared AND a navigation event routing to the sign-in surface via `replaceAll` is emitted

#### Scenario: Server-call failure still signs the user out locally

- **GIVEN** the logout confirmation dialog shown over a populated `SecureTokenStore` and a failing network (the logout call throws or returns non-2xx)
- **WHEN** the confirm affordance is activated
- **THEN** `SecureTokenStore` is cleared AND the `replaceAll` navigation event is emitted regardless of the failure

#### Scenario: Cancelling logout leaves the session intact

- **GIVEN** the logout confirmation dialog shown over a populated `SecureTokenStore`
- **WHEN** the cancel affordance is activated
- **THEN** `SecureTokenStore` is unchanged AND no logout request is issued AND no navigation event is emitted (the dialog dismisses)
