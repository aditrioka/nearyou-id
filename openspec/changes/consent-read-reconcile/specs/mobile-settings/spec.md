## MODIFIED Requirements

### Requirement: Consent settings initialize from the last-submitted snapshot, falling back to the V2 safe defaults

`ConsentSettingsScreen` SHALL render its first frame from the durable on-device consent snapshot, falling back to the V2 column defaults (**analytics OFF, crash ON, ads OFF**) when no snapshot exists. It SHALL then refresh from the server:

- **First frame.** The snapshot holds the last server-acknowledged triple. It is written on each successful `PATCH` and by the sign-in reconcile (`mobile-analytics-consent`). This gives an instant render with no spinner.
- **Server read.** On entry, its ViewModel SHALL read `GET /api/v1/user/consent` (`analytics-consent-update`) through the reused `ConsentFlow` seam and the shared `ConsentReconciler`.
- **Applying the result.** When the read succeeds AND the user has neither changed a toggle nor started a save since entry, the toggles SHALL take the server values. The reconciler SHALL write them to the snapshot and apply the `crash` value immediately (`CrashReportingController.applyConsent`). So a value changed on another device is reflected.
- **Late result.** When the user has already acted, the read result SHALL be discarded entirely: it neither moves the switches nor overwrites the snapshot, so a newer save is never clobbered by an older read.
- **Failed read.** A failed read (non-`200`, transport error, timeout) SHALL keep the snapshot-seeded state, show no error, and change no state. A terminal `401` stays owned by the shared client's session-expiry path.

The persisted snapshot value SHALL always be a server-acknowledged triple: the `PATCH` `200` echo or the `GET` read, never a client-side guess. The initial state SHALL be injectable for testability, not read from wall-clock or platform state. The consent settings ViewModel SHALL expose ONE `StateFlow` UI state projected via `stateIn` (docs/11 §2.2).

#### Scenario: No prior submit → toggles default to analytics OFF, crash ON, ads OFF

- **GIVEN** no persisted consent snapshot on the device AND a consent read that is unavailable
- **WHEN** `ConsentSettingsScreen` is rendered
- **THEN** the toggle state is `analytics = false`, `crash = true`, `ads_personalization = false` (the V2 default) AND no snapshot is written

#### Scenario: A prior submit seeds the toggles from the snapshot

- **GIVEN** a persisted snapshot `{analytics = true, crash = true, ads_personalization = false}` from an earlier successful submit AND a consent read that is unavailable
- **WHEN** `ConsentSettingsScreen` is rendered
- **THEN** the toggles initialize to `analytics = true, crash = true, ads_personalization = false` (the snapshot, not the V2 default)

#### Scenario: The server read replaces the seed when the user has not acted

- **GIVEN** a persisted snapshot `{analytics = false, crash = true, ads_personalization = false}` AND a consent read returning `{analytics = true, crash = false, ads_personalization = true}` (changed on another device) AND a recording crash-consent callback
- **WHEN** the consent settings ViewModel is constructed and the read completes before any user interaction
- **THEN** the toggles show `analytics = true, crash = false, ads_personalization = true` AND the snapshot store now holds that triple AND the crash-consent callback received `false`

#### Scenario: A read that resolves after the user acted is discarded

- **GIVEN** a consent read that suspends AND a persisted snapshot `{analytics = false, crash = true, ads_personalization = false}`
- **WHEN** the user toggles Analytics ON (or starts a save) and only then the read resolves with `{analytics = false, crash = false, ads_personalization = true}`
- **THEN** the toggles still show the user's Analytics ON AND the snapshot store is not overwritten by the read AND no crash-consent callback fires from the read

#### Scenario: A successful submit updates the persisted snapshot

- **GIVEN** `ConsentSettingsScreen` with the user toggling Analytics ON and submitting, the server responding `200` with the echoed triple
- **WHEN** the persisted snapshot is read back
- **THEN** the snapshot reflects the server-echoed triple from the `200` body (so a later settings re-entry initializes to it)

#### Scenario: A snapshot written by one instance seeds a freshly reconstructed instance

- **GIVEN** a shared durable on-device snapshot store and a first consent state-holder that submits `{analytics = true, crash = true, ads_personalization = false}` and receives `200`
- **WHEN** a SECOND consent state-holder is constructed from the SAME store (simulating a later app entry / process restart — NOT the same in-memory instance)
- **THEN** the second instance initializes its toggles to `{analytics = true, crash = true, ads_personalization = false}` (read back from the durable store, not the V2 default)

### Requirement: Logout clears the token store and routes to sign-in

The LAINNYA > "Keluar" row SHALL, after a confirmation dialog (`stringResource` copy: title + body + confirm/cancel), first best-effort revoke the session server-side and then clear the persisted session by wiping `SecureTokenStore` and emit a navigation event routing to the sign-in surface via `replaceAll` (so the authenticated back stack is cleared and a system back gesture cannot return to the authenticated surface). The confirm and cancel affordances SHALL be sourced via `:shared:resources`.

The same local wipe SHALL also clear the device consent snapshot (`ConsentSnapshotStore.clear()`). Consent is per-account (`users.analytics_consent`), so the next account to sign in on the device SHALL NOT inherit the signed-out account's consent. The next sign-in repopulates the snapshot from the server via the sign-in reconcile (`mobile-analytics-consent`); if that reconcile fails, the next cold start re-gates as for any empty snapshot.

The server-side revoke SHALL be a single `POST /api/v1/auth/logout` call carrying the stored refresh token and, when `FcmTokenProvider` yields one, the device's current FCM token as `fcm_token` — issued BEFORE the token wipe (the call needs the still-stored Bearer). The call is best-effort: any transport failure, timeout, or non-2xx response SHALL be swallowed (a token-free diagnostic line MAY be logged) and SHALL NOT block, delay indefinitely, or abort the local wipe and re-route — logout MUST complete locally even when fully offline. No retry is attempted; a missed revoke degrades to client-side-only logout (the refresh token expires naturally).

#### Scenario: Confirming logout revokes server-side then wipes the token store and routes to sign-in

- **GIVEN** `SettingsScreen` with a populated `SecureTokenStore` and the logout confirmation dialog shown
- **WHEN** the confirm affordance is activated
- **THEN** `POST /api/v1/auth/logout` is issued with the stored refresh token (and the current FCM token when available) BEFORE the wipe, AND `SecureTokenStore` is cleared AND a navigation event routing to the sign-in surface via `replaceAll` is emitted

#### Scenario: Confirming logout clears the consent snapshot

- **GIVEN** a settings/logout state holder wired to a consent snapshot store holding `{analytics = true, crash = false, ads_personalization = true}`
- **WHEN** logout is confirmed
- **THEN** `ConsentSnapshotStore.read()` returns `null` after the wipe (whether or not the server revoke succeeded)

#### Scenario: Server-call failure still signs the user out locally

- **GIVEN** the logout confirmation dialog shown over a populated `SecureTokenStore` and a failing network (the logout call throws or returns non-2xx)
- **WHEN** the confirm affordance is activated
- **THEN** `SecureTokenStore` is cleared AND the `replaceAll` navigation event is emitted regardless of the failure

#### Scenario: Cancelling logout leaves the session intact

- **GIVEN** the logout confirmation dialog shown over a populated `SecureTokenStore`
- **WHEN** the cancel affordance is activated
- **THEN** `SecureTokenStore` is unchanged AND no logout request is issued AND no navigation event is emitted (the dialog dismisses)
