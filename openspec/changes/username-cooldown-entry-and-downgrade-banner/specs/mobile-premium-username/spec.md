## RENAMED Requirements

- FROM: `### Requirement: The screen resolves Premium status on entry; the Settings row pushes the route unconditionally`
- TO: `### Requirement: The screen resolves Premium status on entry; the Settings row pushes the route unless the proactive cooldown disables it`

- FROM: `### Requirement: Proactive cooldown state and the downgrade banner are deferred; distinct unavailable messages and autocomplete are not planned`
- TO: `### Requirement: Distinct unavailable messages and autocomplete are not planned`

## MODIFIED Requirements

### Requirement: Screen state mapping covers editing, availability, error, cooldown, gate, disabled, session, submitting, and success states

`UsernameCustomizationScreen` state SHALL be modeled as a Compose-free `UsernameUiState` (sealed type or data class) plus a pure projection `usernameUiState(...)` (over the current input, the local format-validity, the latest `UsernameCheckOutcome`, the latest `UsernameChangeOutcome`, and the in-flight flags) — mirroring `mobile-search`'s `searchUiState(...)` — so the mapping is deterministically unit-testable in commonTest without composing UI. The screen SHALL render exactly one state, all copy via `stringResource` following the `mobile-design-system` loading-state contract (never two simultaneous progress indicators):

- **Editing** → the field with, beneath it, the live status: the inline format message (`username_error_format`) for a format-invalid candidate, OR an availability hint for a format-valid candidate (`username_available` when the probe says available; `username_unavailable_generic` when the probe says unavailable; `username_probe_deferred` "akan dicek saat kamu simpan" when the probe is exhausted/not-yet-run). Submit is enabled only for a format-valid candidate that differs from the current handle.
- **Submitting** → a single progress indicator; the field and submit are disabled.
- **Success** → drives the success path (per the § "Successful change" requirement); the transient success copy is `stringResource(Res.string.username_success_toast)` ("Username berhasil diganti").
- **Unavailable** (`UnavailableState`) → the generic `stringResource(Res.string.username_unavailable_generic)` ("Username ini tidak tersedia. Coba username lain.") — the single message the `409` envelope permits (this single message is final — distinct reserved / collision / release-hold messages are not planned, per the § "Distinct unavailable messages and autocomplete are not planned" requirement).
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

### Requirement: The Premium gate renders the upsell and routes to the paywall via a hoisted callback

While the change (or probe) outcome is `PremiumGate` (the authoritative `403 premium_required` gate — the backstop that also covers the `premium_billing_retry` edge and any stale client `isPremium` hint), `UsernameCustomizationScreen` SHALL render a Free-tier upsell panel: an explanatory body via `stringResource(Res.string.username_premium_gate_body)` (docs/03 §114: "Ganti username adalah fitur Premium") and a primary CTA via `stringResource(Res.string.cta_activate_premium)` ("Aktifkan Premium", the shared key; there is no username-specific CTA key) that invokes the hoisted `onActivatePremium` lambda. The screen SHALL hold no back-stack reference; the `appEntryProvider` call site SHALL wire `onActivatePremium` to push `PaywallRoute` (introduced by `mobile-paywall-screen` #309). The gate panel SHALL issue no further change/probe request while shown.

When the on-entry self read reports that the caller has customized their username before (`usernameLastChangedAt != null` — the downgraded-user signal, docs/03 "Username on downgrade", [#335](https://github.com/aditrioka/nearyou-id/issues/335)), the panel body SHALL instead be `stringResource(Res.string.username_retained_banner, <current handle>)` ("Username @{username} tetap milikmu. Untuk mengubahnya lagi, aktifkan Premium."), with the same "Aktifkan Premium" CTA. The test is "customized", not "not Premium", so the copy also covers the downgrade race where the self read said Premium but a probe/submit met the `403`. A failed self read (no profile) keeps the generic body.

During the post-purchase webhook-lag window (`mobile-premium-entitlement` § "Upsell surfaces show the activating notice during the webhook-lag window") the gate panel SHALL render `premium_activating_body` in place of `username_premium_gate_body` (and of `username_retained_banner`), and its button SHALL become "Coba lagi" (`cta_retry`). The button clears the gate back to the editor with the candidate kept, through the ViewModel's existing candidate-change path, so the viewer can submit again. A confirmed purchase already clears a stale gate once (§ "The screen resolves Premium status on entry"). The panel shows again only when a later probe or submit still meets the lagging `403`, and without the retry the full-screen gate would leave no way back to the editor.

#### Scenario: 403 renders the upsell and the CTA invokes the hoisted callback
- **GIVEN** `UsernameCustomizationScreen` over a `FakeUsernameFlow` returning `PremiumGate` and a recording `onActivatePremium`
- **WHEN** the screen renders and the "Aktifkan Premium" CTA is activated
- **THEN** the tree contains the upsell body (`username_premium_gate_body`) AND the CTA (`cta_activate_premium`) AND activating it fires the hoisted `onActivatePremium` (the screen appends no route itself — navigation is owned by the call site)

#### Scenario: The gate reached via the probe path also renders the upsell
- **GIVEN** `UsernameCustomizationScreen` over a `FakeUsernameFlow` whose probe returns `CheckPremiumGate`
- **WHEN** the screen renders
- **THEN** the same upsell panel (`username_premium_gate_body` + `cta_activate_premium`) is shown (the gate state is reached identically from the change `PremiumGate` and the probe `CheckPremiumGate`)

#### Scenario: A previously-customized Free user sees the retained copy in the gate
- **GIVEN** a self read reporting `isPremium = false`, `username = "dewi.kuliner"` and a non-null `usernameLastChangedAt`
- **WHEN** the screen renders the initial `PremiumGate`
- **THEN** the panel shows `username_retained_banner` formatted with `dewi.kuliner` AND NOT `username_premium_gate_body` AND the "Aktifkan Premium" CTA still fires the hoisted `onActivatePremium`

#### Scenario: The gate shows the activating notice and returns to the editor after a confirmed purchase
- **GIVEN** `UsernameCustomizationScreen` whose Koin graph binds a `PremiumEntitlementSession` on which `onPurchaseConfirmed()` has run AND a `FakeUsernameFlow` returning `PremiumGate`
- **WHEN** a submitted change meets the `403` and the gate's "Coba lagi" control is activated
- **THEN** the gate showed `premium_activating_body` AND neither `username_premium_gate_body` nor an "Aktifkan Premium" control AND the retry returns to the editor holding the submitted candidate AND the hoisted `onActivatePremium` is never invoked

### Requirement: The screen resolves Premium status on entry; the Settings row pushes the route unless the proactive cooldown disables it

This requirement honours docs/03 §114 ("Free user taps: paywall opens"). The Premium gate SHALL be owned by the route-scoped screen, not the Settings row:

- The `SettingsScreen` "Ganti username" row SHALL push `UsernameCustomizationRoute` in every entry state EXCEPT the proactive cooldown, where the row is disabled and pushes nothing (per the § "The Settings entry shows the proactive cooldown and the downgrade banner" requirement). It SHALL NOT branch on `isPremium` to divert a Free user to the paywall; the screen's gate does that.
- The `UsernameCustomizationViewModel` SHALL resolve the caller's Premium status on entry via a self-profile read (`ProfileFlow.loadProfile(selfUserId)`, the existing stateless seam, with `selfUserId` from the existing `SelfUserIdProvider`). It renders the `PremiumGate` as the INITIAL state when the self read reports not-Premium, and the editor otherwise. The same read records whether the caller has customized before (`usernameLastChangedAt != null`), which selects the gate body (§ "The Premium gate").

The resolved status SHALL also honour a confirmed client purchase, the `purchaseConfirmed` signal from the `mobile-premium-entitlement` capability. The effective status is the self-profile `isPremium` OR `purchaseConfirmed`. When `purchaseConfirmed` becomes `true` while the route-scoped ViewModel is alive (the buyer returns from the paywall pushed atop the username screen), the screen SHALL leave the `PremiumGate` for the editor without re-entry. It SHALL also clear any `PremiumGate` probe/change outcome and the last-probed-candidate memo, so the current candidate is re-probed rather than stuck behind a stale gate.

Because the client `isPremium` signal is `true` only for `premium_active` (NOT `premium_billing_retry`), the reactive `403 premium_required` from a probe/submit SHALL remain the **authoritative** gate. It is the backstop for billing-retry, staleness, and the post-purchase webhook-lag window. A self-read mis-classifying a billing-retry user as Free shows the paywall, which is harmless because re-subscribing is the intended action. A self-profile read failure SHALL degrade to letting the user attempt the action (the reactive `403` then governs), never an error wall.

#### Scenario: A not-Premium self-read renders the gate as the initial state
- **GIVEN** `UsernameCustomizationViewModel` over a `FakeUsernameFlow` and a self-profile read reporting `isPremium = false`
- **WHEN** the screen first composes
- **THEN** the initial state is `PremiumGate` (the upsell, no editor) WITHOUT having issued a change/probe request

#### Scenario: A Premium self-read renders the editor; the reactive 403 still backstops
- **GIVEN** a self-profile read reporting `isPremium = true`, then a probe/submit that returns `403 premium_required` (e.g. a downgrade race)
- **WHEN** the screen composes and the user attempts an action
- **THEN** the initial state is the editor AND the subsequent `403` transitions the screen to `PremiumGate` (the reactive backstop governs)

#### Scenario: A self-profile read failure does not wall the screen
- **GIVEN** the on-entry self-profile read fails (5xx / network)
- **THEN** the screen still renders the editor (the user may attempt the action; the reactive `403`/`200` then governs) AND no error wall is shown for the read failure alone

#### Scenario: A purchase confirmed while the gate is shown opens the editor
- **GIVEN** the screen in `PremiumGate` because the self read reported `isPremium = false` AND a `purchaseConfirmed` flow that is `false`
- **WHEN** `purchaseConfirmed` becomes `true`
- **THEN** the state leaves `PremiumGate` for the editor without re-entering the route

#### Scenario: A confirmed purchase clears a stale probe gate and re-probes
- **GIVEN** a probe for candidate `"newname"` returned `CheckPremiumGate` (the screen shows `PremiumGate`)
- **WHEN** `purchaseConfirmed` becomes `true` and the user re-enters `"newname"`
- **THEN** the `PremiumGate` outcome is cleared AND a fresh probe for `"newname"` is issued after the debounce

### Requirement: Distinct unavailable messages and autocomplete are not planned

This capability SHALL NOT implement the two items below. They are **not planned** and carry no tracking issue. (The proactive cooldown entry state, [#333](https://github.com/aditrioka/nearyou-id/issues/333), and the downgrade banner, [#335](https://github.com/aditrioka/nearyou-id/issues/335), formerly deferred here, ship per the § "The Settings entry shows the proactive cooldown and the downgrade banner" and § "The Premium gate" requirements.)

- (b) **Not planned** — the three **distinct** unavailable messages (Reserved / Collision / On-release-hold). The single generic `409 username_unavailable` message ("Username ini tidak tersedia. Coba username lain.") is FINAL: a per-reason message would tell a prober whether a handle is reserved, taken, or on its 30-day release hold, against the anti-probing posture of `premium-username-customization` (§ "Anti-probing and probe rate limits"; the shipped `409` is the constant body `{"error":"username_unavailable"}`) — operator decision 2026-09-26, [#334](https://github.com/aditrioka/nearyou-id/issues/334) closed as not planned. This screen SHALL read only the `error` code from the `409` and SHALL NOT distinguish the reasons; enriching the `409` / probe envelope with a reason is not planned.
- (d) **Not planned** — username **autocomplete / typeahead**. It is not part of the username-customization UX: docs/03 specifies none for this screen, and the only autocomplete in docs/03 is Search's (`mobile-search`, tracked by [#252](https://github.com/aditrioka/nearyou-id/issues/252)).

#### Scenario: Unavailable stays one generic message with no reason discriminator

- **GIVEN** a change attempt answered `409` with the constant body `{"error":"username_unavailable"}` (the same body for a reserved, taken, or release-hold handle)
- **WHEN** the repository maps it and the screen renders it
- **THEN** the outcome is `Unavailable` AND the tree contains `stringResource(Res.string.username_unavailable_generic)` AND the parsed error body declares only `error` (no reason field)

#### Scenario: The username field offers no autocomplete

- **WHEN** inspecting `UsernameApiClient` and `UsernameCustomizationScreen`
- **THEN** `UsernameApiClient` issues only the budget-aware `GET /api/v1/username/check` probe and the `PATCH /api/v1/user/username` submit (no suggestion endpoint) AND the screen renders no suggestion list

## ADDED Requirements

### Requirement: The Settings entry shows the proactive cooldown and the downgrade banner

`SettingsScreen` SHALL host a NavEntry-scoped `SettingsUsernameEntryViewModel` (resolved via `viewModel { … }`, alongside the screen's other per-concern ViewModels) that reads the caller's own profile through the existing `ProfileFlow.loadProfile(selfUserId)` + `SelfUserIdProvider` seam and exposes ONE `StateFlow<UsernameEntryState>`. The read SHALL run on entry and again on every `ON_RESUME` (the docs/11 § 2.3 re-read idiom), so a return from a username change or a purchase reflects fresh state; a resume while a read is in flight SHALL NOT start a second one, and a failed read SHALL keep the last state. The state SHALL be a pure function `usernameEntryState(profile, now)` over the loaded `UserProfile` and an injected clock:

- **`Cooldown(days)`** when `isPremium` AND `usernameLastChangedAt + 30 days > now` — `days` is the remaining whole days rounded up and floored to 1, through the same `cooldownDays(seconds)` rounding the reactive `429 cooldown_active` path uses. The "Ganti username" row SHALL then show `stringResource(Res.string.username_cooldown_countdown, days)` ("Ganti username berikutnya tersedia dalam %1$d hari.", docs/03 § Cooldown Messaging) as its subtitle and SHALL be disabled: not clickable, title/icon/chevron muted, and activating it pushes nothing ([#333](https://github.com/aditrioka/nearyou-id/issues/333)). The subtitle stays at full contrast.
- **`Retained(username)`** when NOT `isPremium` AND `usernameLastChangedAt != null` (a downgraded user who customized — signup never sets the column). Settings SHALL render a non-blocking banner at the top of the list with `stringResource(Res.string.username_retained_banner, username)` ("Username @{username} tetap milikmu. Untuk mengubahnya lagi, aktifkan Premium.", docs/03 "Username on downgrade", [#335](https://github.com/aditrioka/nearyou-id/issues/335)). The banner SHALL be hidden while a purchase is confirmed and the tier still lags the webhook (`rememberPremiumActivating()`). The row stays enabled and pushes the route (whose gate shows the same retained copy).
- **`Open`** otherwise — including before the first read lands, when the read fails, when the self id is missing, or when the graph binds no `ProfileFlow` / `SelfUserIdProvider` (resolved fail-safe via `getOrNull`). The row renders and pushes exactly as before.

The 30-day constant mirrors the backend `UsernameChangeService.COOLDOWN`; the server stays authoritative (a skewed device clock can only mis-count the days or leave the row enabled, and the reactive `429` then governs). The client `isPremium` is `premium_active` only, so a `premium_billing_retry` user reads as Free here — the same accepted mis-classification as the screen's gate.

#### Scenario: A Premium user inside the cooldown sees a disabled row with the countdown
- **GIVEN** a self read with `isPremium = true` and `usernameLastChangedAt` 16 days before `now`
- **WHEN** `SettingsScreen` renders
- **THEN** the "Ganti username" row shows "Ganti username berikutnya tersedia dalam 14 hari." AND is disabled AND activating it does not invoke `onOpenUsernameCustomization`

#### Scenario: The countdown rounds partial days up and ends at 30 days
- **WHEN** `usernameEntryState` is evaluated for a Premium profile whose last change was 29 days 1 hour before `now`, and separately exactly 30 days before `now`, and separately with `usernameLastChangedAt = null`
- **THEN** the results are `Cooldown(1)`, `Open`, and `Open` respectively

#### Scenario: A previously-customized Free user sees the retained banner and can still open the route
- **GIVEN** a self read with `isPremium = false`, `username = "dewi.kuliner"` and a non-null `usernameLastChangedAt`
- **WHEN** `SettingsScreen` renders and the "Ganti username" row is activated
- **THEN** the banner text "Username @dewi.kuliner tetap milikmu. Untuk mengubahnya lagi, aktifkan Premium." is shown AND `onOpenUsernameCustomization` fires once

#### Scenario: A Free user who never customized sees no banner
- **GIVEN** a self read with `isPremium = false` and `usernameLastChangedAt = null`
- **WHEN** `SettingsScreen` renders
- **THEN** no retained banner is shown AND the row shows its default subtitle and pushes the route

#### Scenario: The banner hides during the webhook-lag window
- **GIVEN** the `Retained` state AND a Koin graph binding a `PremiumEntitlementSession` on which `onPurchaseConfirmed()` has run
- **WHEN** `SettingsScreen` renders
- **THEN** no retained banner is shown

#### Scenario: A failed or unbound read leaves the row open
- **GIVEN** a self read that returns `NetworkError`, and separately a Koin graph with no `ProfileFlow` bound
- **WHEN** `SettingsScreen` renders and the row is activated
- **THEN** the row shows its default subtitle AND `onOpenUsernameCustomization` fires AND no banner is shown

#### Scenario: A resume re-reads the profile, without overlapping an in-flight read
- **GIVEN** a `SettingsUsernameEntryViewModel` whose init read is still in flight
- **WHEN** `refresh()` is called, then the init read completes, then `refresh()` is called again against a profile now inside the cooldown
- **THEN** the first `refresh()` issues no second read AND the second `refresh()` issues one read AND the state becomes `Cooldown`
