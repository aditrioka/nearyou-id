## MODIFIED Requirements

### Requirement: Consent settings initialize from the last-submitted snapshot, falling back to the V2 safe defaults

Because there is NO server consent-read endpoint (the `analytics-consent-update` capability ships `PATCH` only; the `mobile-analytics-consent` onboarding screen issues no GET), `ConsentSettingsScreen` SHALL initialize its toggles from the **last-submitted consent snapshot** persisted on the device on each successful `PATCH`, falling back to the V2 column defaults — **analytics OFF, crash ON, ads OFF** — when no snapshot exists (e.g. a returning user who consented only at onboarding, before any settings submit — noting that onboarding now also persists the snapshot, so this fallback applies mainly to a genuine first run). The persisted snapshot value SHALL be the triple the server **echoes in the `PATCH` `200` body** (`ConsentResponse` — the server's authoritative acknowledgement of the write), not a client-side guess, so the mirror cannot drift from the last server-acknowledged state on this single-device PATCH-only flow. The initial state SHALL be injectable for testability (a default-values parameter / initial `ConsentUiState`), not read from wall-clock or platform state. Durable cross-session persistence of the snapshot is now provided (a platform `expect/actual` binding landed by `mobile-amplitude-analytics`, resolving issue [#198](https://github.com/aditrioka/nearyou-id/issues/198)), so the snapshot survives process death; the write-on-`200`-echo semantics are unchanged. A true server-side consent-read endpoint (so settings could reflect a value changed on another device) remains OUT of scope and SHALL be recorded as a follow-up GitHub issue (label `follow-up`); the `PATCH` `200` already round-trips the authoritative triple, so a dedicated GET is a robustness nicety, not a correctness gap for the single-device case.

#### Scenario: No prior submit → toggles default to analytics OFF, crash ON, ads OFF

- **GIVEN** no persisted consent snapshot on the device
- **WHEN** `ConsentSettingsScreen` is rendered
- **THEN** the initial toggle state is `analytics = false`, `crash = true`, `ads_personalization = false` (the V2 default) AND no consent-read request is issued (there is no GET endpoint)

#### Scenario: A prior submit seeds the toggles from the snapshot

- **GIVEN** a persisted snapshot `{analytics = true, crash = true, ads_personalization = false}` from an earlier successful submit
- **WHEN** `ConsentSettingsScreen` is rendered
- **THEN** the toggles initialize to `analytics = true, crash = true, ads_personalization = false` (the snapshot, not the V2 default)

#### Scenario: A successful submit updates the persisted snapshot

- **GIVEN** `ConsentSettingsScreen` with the user toggling Analytics ON and submitting, the server responding `200` with the echoed triple
- **WHEN** the persisted snapshot is read back
- **THEN** the snapshot reflects the server-echoed triple from the `200` body (so a later settings re-entry initializes to it)

#### Scenario: A snapshot written by one instance seeds a freshly reconstructed instance

- **GIVEN** a shared durable on-device snapshot store and a first consent state-holder that submits `{analytics = true, crash = true, ads_personalization = false}` and receives `200`
- **WHEN** a SECOND consent state-holder is constructed from the SAME store (simulating a later app entry / process restart — NOT the same in-memory instance)
- **THEN** the second instance initializes its toggles to `{analytics = true, crash = true, ads_personalization = false}` (read back from the durable store, not the V2 default)
