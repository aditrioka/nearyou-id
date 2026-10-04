## RENAMED Requirements

- FROM: `### Requirement: Proactive cooldown state, distinct unavailable messages, downgrade banner, and autocomplete are explicitly deferred`
- TO: `### Requirement: Proactive cooldown state and the downgrade banner are deferred; distinct unavailable messages and autocomplete are not planned`

## MODIFIED Requirements

### Requirement: Screen state mapping covers editing, availability, error, cooldown, gate, disabled, session, submitting, and success states

`UsernameCustomizationScreen` state SHALL be modeled as a Compose-free `UsernameUiState` (sealed type or data class) plus a pure projection `usernameUiState(...)` (over the current input, the local format-validity, the latest `UsernameCheckOutcome`, the latest `UsernameChangeOutcome`, and the in-flight flags) — mirroring `mobile-search`'s `searchUiState(...)` — so the mapping is deterministically unit-testable in commonTest without composing UI. The screen SHALL render exactly one state, all copy via `stringResource` following the `mobile-design-system` loading-state contract (never two simultaneous progress indicators):

- **Editing** → the field with, beneath it, the live status: the inline format message (`username_error_format`) for a format-invalid candidate, OR an availability hint for a format-valid candidate (`username_available` when the probe says available; `username_unavailable_generic` when the probe says unavailable; `username_probe_deferred` "akan dicek saat kamu simpan" when the probe is exhausted/not-yet-run). Submit is enabled only for a format-valid candidate that differs from the current handle.
- **Submitting** → a single progress indicator; the field and submit are disabled.
- **Success** → drives the success path (per the § "Successful change" requirement); the transient success copy is `stringResource(Res.string.username_success_toast)` ("Username berhasil diganti").
- **Unavailable** (`UnavailableState`) → the generic `stringResource(Res.string.username_unavailable_generic)` ("Username ini tidak tersedia. Coba username lain.") — the single message the `409` envelope permits (this single message is final — distinct reserved / collision / release-hold messages are not planned, per the § "Proactive cooldown state and the downgrade banner are deferred; distinct unavailable messages and autocomplete are not planned" requirement).
- **Moderated** → `stringResource(Res.string.username_error_moderated)` (docs/03 §124: "Username ini akan ditinjau tim moderasi. Silakan pilih username lain atau tunggu hasil review.").
- **CooldownActive** → `stringResource(Res.string.username_cooldown_countdown)` ("Ganti username berikutnya tersedia dalam %1$d hari.") formatted with whole days computed (rounded up) from the `Retry-After` seconds; a non-positive value floors to one day; no auto-retry.
- **RateLimited** → `stringResource(Res.string.username_rate_limited)` formatted with a minute countdown from `Retry-After` (the `mobile-cap-upsell-dialog` `capCountdownMinutes` formatter), floored to one minute; no auto-retry.
- **PremiumGate** → the Free-tier upsell panel (per the § "Premium gate" requirement).
- **Disabled** → `stringResource(Res.string.username_disabled)` (the kill-switch state; no retry control).
- **SessionExpired** → a neutral redirect placeholder via `stringResource(Res.string.timeline_session_redirect)`, no retry, not the connectivity copy.
- **Error** / **NetworkError** → `stringResource(Res.string.signin_error_network)` + a `stringResource(Res.string.cta_retry)` control re-issuing the last action.

The projection MUST carry no PII and MUST NOT depend on wall-clock or platform state (countdowns derive from the `Retry-After` integer + a monotonic test-advanceable clock).

#### Scenario: Projection maps each outcome to its state deterministically
- **WHEN** the projection is invoked for: a format-invalid input; a format-valid input with probe `Available(true)`, `Available(false)`, and `ProbeExhausted`; an in-flight submit; `Success`; `Unavailable`; `Moderated`; `CooldownActive(1209600)`; `RateLimited(1800)`; `PremiumGate`; `Disabled`; `SessionExpired`; and `NetworkError`
- **THEN** it returns Editing(format-error) / Editing(available) / Editing(unavailable) / Editing(probe-deferred) / Submitting / Success / Unavailable / Moderated / CooldownActive("14 hari") / RateLimited("30 menit") / PremiumGate / Disabled / SessionExpired / Error respectively, deterministically

#### Scenario: CooldownActive renders the day-countdown and does not auto-retry
- **GIVEN** the change outcome `CooldownActive(retryAfterSeconds = 1209600)` (14 days)
- **WHEN** the screen renders
- **THEN** it contains a node whose text matches `stringResource(Res.string.username_cooldown_countdown)` formatted with `14` AND no automatic re-submit is issued

#### Scenario: A non-positive countdown floors to one day / one minute and does not flash-clear
- **GIVEN** the change outcome `CooldownActive(retryAfterSeconds = 0)` and, separately, `RateLimited(retryAfterSeconds = 0)`
- **WHEN** each state renders
- **THEN** the cooldown shows the one-day countdown ("1 hari") and the rate-limit shows the one-minute countdown ("1 menit") AND neither flash-clears on entry

#### Scenario: Unavailable renders the single generic message
- **WHEN** the change outcome is `Unavailable`
- **THEN** the rendered tree contains `stringResource(Res.string.username_unavailable_generic)` AND does NOT attempt to distinguish reserved vs collision vs release-hold (the shipped `409` envelope carries no reason)

### Requirement: Proactive cooldown state and the downgrade banner are deferred; distinct unavailable messages and autocomplete are not planned

This capability SHALL NOT implement the four items below. The two **deferred** items, (a) and (c), SHALL each stay tracked by an open `follow-up` GitHub issue until a change MODIFIES this requirement to ship them. The two **not planned** items, (b) and (d), SHALL NOT be implemented at all and carry no tracking issue.

- (a) **Deferred ([#333](https://github.com/aditrioka/nearyou-id/issues/333))** — the **proactive** cooldown disabled-entry state at the Settings row (docs/03 § "Cooldown Messaging": "Ganti username berikutnya tersedia dalam {countdown} hari." computed *before* tapping). It requires a `username_last_changed_at` field on the self-profile read, which the shipped `UserProfileResponse` does not expose; the v1 surfaces the cooldown **reactively** via the `429 cooldown_active` `Retry-After`.
- (b) **Not planned** — the three **distinct** unavailable messages (Reserved / Collision / On-release-hold). The single generic `409 username_unavailable` message ("Username ini tidak tersedia. Coba username lain.") is FINAL: a per-reason message would tell a prober whether a handle is reserved, taken, or on its 30-day release hold, against the anti-probing posture of `premium-username-customization` (§ "Anti-probing and probe rate limits"; the shipped `409` is the constant body `{"error":"username_unavailable"}`) — operator decision 2026-09-26, [#334](https://github.com/aditrioka/nearyou-id/issues/334) closed as not planned. This screen SHALL read only the `error` code from the `409` and SHALL NOT distinguish the reasons; enriching the `409` / probe envelope with a reason is not planned.
- (c) **Deferred ([#335](https://github.com/aditrioka/nearyou-id/issues/335))** — the distinct **downgrade banner** (docs/03 § "Downgrade Copy"). It needs a "previously customized" signal the client lacks; a downgraded Free user functionally falls into the Free → paywall path.
- (d) **Not planned** — username **autocomplete / typeahead**. It is not part of the username-customization UX: docs/03 specifies none for this screen, and the only autocomplete in docs/03 is Search's (`mobile-search`, tracked by [#252](https://github.com/aditrioka/nearyou-id/issues/252)).

#### Scenario: The two deferrals stay tracked and the v1 works without them

- **WHEN** inspecting the open `follow-up` issues and the shipped screen
- **THEN** the proactive-cooldown (a) and downgrade-banner (c) deferrals are each tracked by an open `follow-up` GitHub issue ([#333](https://github.com/aditrioka/nearyou-id/issues/333), [#335](https://github.com/aditrioka/nearyou-id/issues/335)) AND the v1 surface functions without them (reactive cooldown via `429 cooldown_active`, Free → paywall)

#### Scenario: Unavailable stays one generic message with no reason discriminator

- **GIVEN** a change attempt answered `409` with the constant body `{"error":"username_unavailable"}` (the same body for a reserved, taken, or release-hold handle)
- **WHEN** the repository maps it and the screen renders it
- **THEN** the outcome is `Unavailable` AND the tree contains `stringResource(Res.string.username_unavailable_generic)` AND the parsed error body declares only `error` (no reason field)

#### Scenario: The username field offers no autocomplete

- **WHEN** inspecting `UsernameApiClient` and `UsernameCustomizationScreen`
- **THEN** `UsernameApiClient` issues only the budget-aware `GET /api/v1/username/check` probe and the `PATCH /api/v1/user/username` submit (no suggestion endpoint) AND the screen renders no suggestion list
