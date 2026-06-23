## ADDED Requirements

### Requirement: Onboarding ConsentScreen persists the submitted consent to the durable snapshot

On a successful onboarding consent submit (`PATCH /api/v1/user/consent` → `200`), `ConsentScreen` SHALL persist the server-echoed triple to the durable `ConsentSnapshotStore` (mirroring `ConsentSettingsScreen`), so a user who consents only at onboarding has a durable snapshot for later consumers (the analytics tracker, the crash gate, and settings seeding). It SHALL persist only on `200` (never on a failed submit), keeping the snapshot a faithful mirror of the last server-acknowledged state — not a client-side guess.

#### Scenario: A successful onboarding submit persists the echoed snapshot

- **GIVEN** `ConsentScreen` with Analytics toggled ON and a MockEngine responding `200 {"analytics": true, "crash": true, "ads_personalization": false}`
- **WHEN** continue is tapped and the snapshot is read back from the store
- **THEN** the persisted snapshot is `{analytics = true, crash = true, ads_personalization = false}` (the server-echoed triple)

#### Scenario: A failed onboarding submit leaves the snapshot unwritten

- **GIVEN** `ConsentScreen` and a MockEngine responding `503`
- **WHEN** continue is tapped and the snapshot is read back
- **THEN** no snapshot was written by this submit (the store retains its prior value, or remains absent for a first-run user)

## RENAMED Requirements

- FROM: `### Requirement: Reliable consent persistence is deferred and tracked`
- TO: `### Requirement: The consent snapshot is durably persisted across sessions`

## MODIFIED Requirements

### Requirement: The consent snapshot is durably persisted across sessions

The device-local `ConsentSnapshotStore` SHALL be durably persisted across process restarts via a platform `expect/actual` binding (Android DataStore / iOS `NSUserDefaults` — the no-new-pin storage family backing `SecureTokenStore`), replacing the prior in-memory binding. The persisted triple SHALL remain the server-echoed `PATCH 200` body (`ConsentResponse`), never a client-side guess. This durable persistence resolves GitHub issue [#198](https://github.com/aditrioka/nearyou-id/issues/198) `mobile-analytics-consent-persist-hardening`: a consent choice acknowledged by the server survives process death, so a consumer reading the snapshot (the analytics tracker, the crash gate) honors the user's last server-acknowledged choice across cold starts. Background retry/queue of a *failed* PATCH remains out of scope — the existing in-screen retry + skip is unchanged; a failed submit simply leaves the last acknowledged snapshot in place (or absent, for a first-run user).

#### Scenario: A consent acknowledged before process death survives a restart

- **GIVEN** a successful submit (onboarding or settings) that persists `{analytics = true, crash = true, ads_personalization = false}` to the durable store
- **WHEN** the app is relaunched (a fresh process reads the durable store, not the same in-memory instance)
- **THEN** `ConsentSnapshotStore.read()` returns `{analytics = true, crash = true, ads_personalization = false}` (the persisted triple, not absent and not the V2 default)

#### Scenario: A first-run user with no submit reads an absent snapshot

- **GIVEN** a fresh install where no consent has been submitted
- **WHEN** `ConsentSnapshotStore.read()` is called
- **THEN** it returns `null` (consumers apply their own safe default — analytics suppressed, crash opt-out-default ON)

### Requirement: A failed persist offers a non-trapping proceed-to-Home; the happy path shows no skip

To avoid trapping a user in onboarding on a transient persist failure, AFTER a `Retryable` outcome `ConsentScreen` SHALL present a skip affordance via `stringResource(Res.string.consent_skip)` that routes to `HomeScreen` (the account retains the server's safe defaults). On the happy path — before any failed submit — the screen SHALL NOT present the skip affordance (the consent step reads as a required action, not an optional one). This best-effort posture is sound because the V2 defaults are privacy-safe and the analytics tracker gates on the durable consent snapshot: a failed (unacknowledged) submit leaves the last server-acknowledged value in place — or, for a first-run user with no snapshot, the absent snapshot suppresses tracking (see the durable-persistence requirement).

#### Scenario: Skip appears only after a failed submit and routes Home

- **GIVEN** `ConsentScreen` freshly composed (no prior submit)
- **THEN** no `consent_skip` affordance is present
- **WHEN** continue is tapped against a MockEngine responding `503` (producing a `Retryable` outcome)
- **THEN** a `consent_skip` affordance becomes present AND tapping it emits a navigation event routing to `HomeScreen`
