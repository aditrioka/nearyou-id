## MODIFIED Requirements

### Requirement: Consent gating honors the crash consent category (opt-out default)
Crash reporting SHALL be gated on `users.analytics_consent.crash`, which defaults ON (opt-out). The app MUST consume the `crash` flag already captured by the consent flow without adding new consent plumbing. On decline the app MUST stop reporting for the session; on re-consent it MUST resume. The gate reads the **durable** `ConsentSnapshotStore` (made durable by `mobile-amplitude-analytics`, resolving issue [#198](https://github.com/aditrioka/nearyou-id/issues/198)), so a prior crash decline now survives process death.

#### Scenario: Default consent keeps reporting active
- **WHEN** the user has not declined crash consent
- **THEN** crash reporting is active (opt-out default ON)

#### Scenario: Declining crash consent stops reporting immediately
- **WHEN** the user turns the crash toggle OFF in consent settings
- **THEN** the app calls `CrashReporter.close()` for the session and no further crash events are sent

#### Scenario: Re-enabling crash consent resumes reporting
- **WHEN** the user turns the crash toggle back ON
- **THEN** the app re-initializes the `CrashReporter` and crash events are sent again

#### Scenario: A persisted decline is honored
- **WHEN** the consent gate reads a `ConsentSnapshotStore` snapshot whose `crash` value is declined
- **THEN** reporting initializes (opt-out default) and is closed on the first consent read, so no events are sent

#### Scenario: A persisted decline survives a process restart
- **GIVEN** a durable snapshot whose `crash` value is declined, written before process death
- **WHEN** the app is relaunched and the startup crash gate reads the durable `ConsentSnapshotStore`
- **THEN** the persisted decline is read back and reporting is closed (the decline is not lost across the cold start)

#### Scenario: Absent snapshot falls back to the opt-out default
- **WHEN** no consent snapshot is present (a first-run user who has not yet completed the consent screen)
- **THEN** the opt-out default (`crash` ON) applies and is corrected on the next consent interaction
