# mobile-premium-username Specification

## Purpose

The `:mobile:app` **Ganti Username** surface — the consumer for the SHIPPED, frozen `premium-username-customization` backend (`PATCH /api/v1/user/username` + the non-authoritative, 3/day-rate-limited `GET /api/v1/username/check` probe), closing the mobile half of the flagship Premium username-customization perk with zero backend work. A parameterless `UsernameCustomizationRoute` (a root-stack push from the Settings "Ganti username" row) hosts a navigation-free `UsernameCustomizationScreen` + a route-scoped `UsernameCustomizationViewModel` over a `UsernameApiClient` / `UsernameRepository` / `UsernameFlow` data seam (the `mobile-search` pattern), whose sealed `UsernameChangeOutcome` / `UsernameCheckOutcome` map every HTTP status the endpoints enforce — reading the body `error` code only to split the two same-status pairs (`429` cooldown vs rate-limit; `422` format vs moderation). The route-scoped screen OWNS the Free/Premium gate (an on-entry self-`isPremium` read renders the editor or the upsell, with the reactive `403` as the authoritative backstop), so Settings pushes the route unconditionally. Live LOCAL format validation gives instant inline feedback; the network availability probe is budget-aware; a submit-confirmation modal gates the destructive change; success pops to Settings. It is reconciled to the shipped wire where the older `docs/03` UX copy diverges (one generic `409 username_unavailable` message; reactive-only cooldown via `Retry-After`), with the fuller UX (proactive cooldown, distinct unavailable messages, downgrade banner, autocomplete) tracked as follow-ups.

## Requirements
### Requirement: UsernameCustomizationRoute is a parameterless serializable NavKey pushed onto the root back stack

The change SHALL introduce a `UsernameCustomizationRoute` `NavKey` (in `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/routing/NavKeys.kt`) that is a parameterless `@Serializable data object` — the user identity lives in the persisted token, never in the serialized back stack (the `SettingsRoute` / `SearchRoute` precedent). It SHALL be registered in the `navSavedStateConfiguration` polymorphic `SerializersModule` (so the back stack is saveable on Kotlin/Native, where Nav3 reflection-based serialization is unavailable, per `mobile-app-scaffold` "Back stack uses serializable NavKey routes"). `UsernameCustomizationRoute` SHALL be reached by appending it onto the **root** back stack (above `HomeRoute`, overlaying the section `NavigationBar`) — the `SettingsRoute` / `PostDetailRoute` root-stack-push precedent — and SHALL NOT use a per-tab `NavDisplay` back stack.

#### Scenario: UsernameCustomizationRoute is registered and survives a serialized back-stack round-trip
- **GIVEN** a `UsernameCustomizationRoute` instance encoded + decoded via the `navSavedStateConfiguration` polymorphic serializer (the iOS-safe saved-state path)
- **THEN** decoding succeeds and yields a `UsernameCustomizationRoute` AND `UsernameCustomizationRoute` appears in the polymorphic `SerializersModule` registration alongside the other `NavKey` routes

#### Scenario: UsernameCustomizationRoute carries no payload
- **WHEN** inspecting the `UsernameCustomizationRoute` declaration in `NavKeys.kt`
- **THEN** it is a parameterless `data object` (no username/identity properties) — the current handle is fetched by the screen's ViewModel, not carried on the route

### Requirement: UsernameCustomizationScreen renders the Ganti Username surface and is navigation-free

The mobile app SHALL ship a composable `UsernameCustomizationScreen` (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/username/UsernameCustomizationScreen.kt`), mapped from `UsernameCustomizationRoute` by the `appEntryProvider`. As a pushed root-stack overlay (like `SettingsScreen` / `SearchScreen`) it SHALL own its own Material 3 `Scaffold` with a top app bar titled via `stringResource(Res.string.username_title)` ("Ganti Username") and a leading `arrow_back` affordance invoking a hoisted `onBack`. The body SHALL render: the user's current handle (`@<current>`), a single-line new-username M3 text field (hint via `stringResource(Res.string.username_field_hint)`) capped at 30 code points, the inline validation/availability message slot, and a primary "Ganti" submit affordance (per the § "Submit confirmation modal" requirement). `UsernameCustomizationScreen` SHALL be navigation-free: it holds no back-stack reference; back invokes the hoisted `onBack`, a successful change invokes a hoisted `onChanged`, and the Premium-gate CTA invokes a hoisted `onActivatePremium` (per the § "Premium gate" requirement). No hardcoded UI string literals SHALL appear in the screen source (every `Text` / `contentDescription` resolves via `stringResource`). The screen SHALL render under `NearYouTheme` in both light and dark schemes, from theme tokens only.

#### Scenario: The screen renders the field and current handle and is navigation-free
- **WHEN** inspecting `UsernameCustomizationScreen.kt`
- **THEN** it renders a top app bar (title `stringResource(Res.string.username_title)`, back affordance bound to the hoisted `onBack`), the current `@handle`, and a new-username text field (hint via `stringResource(Res.string.username_field_hint)`) AND holds no back-stack reference (navigation is delivered via the hoisted `onBack` / `onChanged` / `onActivatePremium` lambdas only)

#### Scenario: No hardcoded UI strings in the screen source
- **WHEN** inspecting `UsernameCustomizationScreen.kt`
- **THEN** every `Text(...)` / `contentDescription = ...` call site sources its text via `stringResource(Res.string.<name>)`; zero literal string arguments appear in such call sites

### Requirement: A live client-side format guard mirrors the backend rules with no network call

The screen SHALL validate the candidate handle **locally and live** (debounced 500 ms after the last keystroke), mirroring the shipped backend's `premium-username-customization` § "Candidate format validation": post-trim length between 3 and 30 Unicode code points, charset matching `^[a-z0-9][a-z0-9_.]*[a-z0-9_]$` (lowercase alphanumerics; dots and underscores middle-only), and no consecutive dots (an explicit `!candidate.contains("..")` guard, since a single regex cannot cleanly forbid it). The guard SHALL be a pure commonMain helper, unit-testable without composing UI. A format-invalid candidate SHALL surface the inline format message (`stringResource(Res.string.username_error_format)`) immediately and SHALL gate BOTH the network availability probe AND submit — no network request SHALL be issued for a format-invalid candidate. The backend guard remains authoritative — a `422 invalid_username` (should the bounds ever diverge) maps to the format state, never a crash (per the § "Change-outcome mapping" requirement).

#### Scenario: Format-invalid candidates surface inline and issue no network request
- **GIVEN** `UsernameCustomizationScreen` over a counting `FakeUsernameFlow`
- **WHEN** the field holds `ab` (length 2), `Abc` (uppercase), `.abc` (leading dot), `abc.` (trailing dot), or `a..b` (consecutive dots)
- **THEN** the inline format message (`stringResource(Res.string.username_error_format)`) is shown AND no probe or change request is issued (the fake's invocation count stays 0)

#### Scenario: Well-formed candidates pass the local guard
- **WHEN** the guard helper evaluates `abc`, `a_b.c`, and `user1.test_2`
- **THEN** each passes the local format guard and becomes eligible for the availability probe and submit

#### Scenario: Length boundaries — 30 accepted, 31 rejected, field caps at 30
- **WHEN** the guard helper evaluates a 30-code-point candidate, a 31-code-point candidate, and the field receives a 31-code-point input
- **THEN** the 30-code-point candidate passes the guard AND the 31-code-point candidate is rejected with the inline format message AND the field caps the input at 30 code points

### Requirement: The change request targets PATCH /api/v1/user/username with the shipped wire

`UsernameApiClient` SHALL issue `PATCH /api/v1/user/username` (the canonical endpoint per `openspec/specs/premium-username-customization/spec.md`) with a JSON body whose field name matches the **shipped** `UserUsernameRoutes.kt` wire: `UsernameChangeRequest { @SerialName("new_username") newUsername: String }`. The success body SHALL be parsed as `UsernameChangeResponse { username: String }` (bare camelCase, the shipped wire). The Bearer `Authorization` header is attached by the shipped `HttpClient` `Auth` plugin — this capability MUST NOT reimplement token attachment.

#### Scenario: Change request shape
- **GIVEN** a Ktor MockEngine capturing outbound requests
- **WHEN** `UsernameApiClient.change(newUsername = "dewi.kuliner")` runs
- **THEN** the captured request is `PATCH` with path `/api/v1/user/username` AND its JSON body carries `new_username = "dewi.kuliner"`

#### Scenario: Success body parses against the shipped wire
- **GIVEN** a MockEngine returning `200 {"username":"dewi.kuliner"}`
- **WHEN** the response is parsed
- **THEN** parsing yields `UsernameChangeResponse(username = "dewi.kuliner")`

### Requirement: The availability probe targets GET /api/v1/username/check and is budget-aware

`UsernameApiClient` SHALL issue `GET /api/v1/username/check` with a `candidate` query parameter, parsing the `200` body as `UsernameCheckResponse { available: Boolean }` (the shipped wire). Because the shipped probe is **rate-limited to 3 per user per day**, the screen SHALL NOT probe per keystroke: a probe SHALL be issued only for a candidate that passes the local format guard, is different from the last probed candidate, and after the 500 ms debounce — and the screen SHALL degrade gracefully when the daily budget is exhausted (a `429 rate_limited` maps to a non-blocking "availability checked at save" state, NOT an error), since the authoritative availability check happens under the row lock at `PATCH` time (a `409` there is the backstop). The probe is a UX nicety; it carries no reservation. (Logging posture: the shipped `HttpClientFactory` `LogLevel.HEADERS` logs the request line, so the probe's `?candidate=<handle>` term travels in the logged URL in debug builds — the same inherited posture as `mobile-search`'s `q=`, NOT a regression; the candidate is user-typed content, not a token/UUID/coordinate, so no `candidate`-masking is added.)

#### Scenario: Probe request shape
- **GIVEN** a MockEngine capturing outbound requests
- **WHEN** `UsernameApiClient.check(candidate = "dewi.kuliner")` runs
- **THEN** the captured request is `GET` with path `/api/v1/username/check` AND carries `candidate=dewi.kuliner`

#### Scenario: Probe parses availability both ways
- **GIVEN** a MockEngine returning `200 {"available":true}` and, separately, `200 {"available":false}`
- **WHEN** each response is parsed
- **THEN** the parsed results are `UsernameCheckOutcome.Available(true)` and `UsernameCheckOutcome.Available(false)` respectively

#### Scenario: Probe is not issued per keystroke and degrades on budget exhaustion
- **GIVEN** `UsernameCustomizationScreen` over a counting `FakeUsernameFlow` whose probe returns `ProbeExhausted` after the 3rd call
- **WHEN** the user types a long format-valid candidate character-by-character and pauses once
- **THEN** at most one probe is issued for the settled candidate (NOT one per keystroke) AND once `ProbeExhausted` is returned the screen shows the non-blocking "availability checked at save" state, not an error, and still permits submit

### Requirement: Change-outcome mapping is HTTP-status-driven, reading the error code only to split same-status pairs

`UsernameRepository` SHALL map each `PATCH` result to exactly one member of a sealed `UsernameChangeOutcome`, keyed primarily on the HTTP **status code** and transport-failure type, with no generic "load failed" fallthrough. Two statuses carry two distinct UX outcomes each, distinguished ONLY by the shipped body `error` code — for these two the mapping SHALL read the `error` discriminator (the sole use of the parsed error code):

- **HTTP 200** → `Success(username)`.
- **HTTP 403** (`premium_required`) → `PremiumGate` (the Free-tier gate; the screen renders the upsell per the § "Premium gate" requirement).
- **HTTP 409** (`username_unavailable`) → `Unavailable` (reserved / on release hold / taken / lost race — the shipped envelope does NOT distinguish these; the screen shows one generic message per the § "Screen state mapping" requirement).
- **HTTP 422 with `error = "invalid_username"`** → `InvalidFormat` (defensive — the live local guard should pre-empt this).
- **HTTP 422 with `error = "username_rejected"`** → `Moderated` (the profanity / UU-ITE soft flag).
- **HTTP 429 with `error = "cooldown_active"`** → `CooldownActive(retryAfterSeconds)` (the 30-day one-change cooldown; `retryAfterSeconds` from the `Retry-After` header).
- **HTTP 429 with `error = "rate_limited"`** → `RateLimited(retryAfterSeconds)` (the failed-attempt throttle; `retryAfterSeconds` from `Retry-After`).
- **HTTP 503** (`feature_disabled`) → `Disabled` (the kill switch).
- **HTTP 400** (`invalid_request`) → a retryable `Error` (diagnostic to logs — NOT a silent no-op, NOT a crash).
- **HTTP 401** (terminal — survived the shipped `Auth` `refreshTokens` because the refresh itself failed) → `SessionExpired`. It MUST NOT map to `NetworkError` or `Error`.
- **HTTP 5xx or network/IO failure** → `NetworkError` (retryable).
- **Any other unenumerated non-2xx** → the defined `NetworkError` fallback (the "no generic fallthrough" rule bans a generic "load failed" *copy*, not a `when` `else` branch). An absent/unparseable `Retry-After` on either 429 SHALL floor to `0` seconds (the screen floors a non-positive value to one minute / one day respectively).

#### Scenario: Each status maps to exactly one outcome
- **WHEN** inspecting the repository result mapping and the `UsernameChangeOutcome` sealed type
- **THEN** each of HTTP 200, 403, 409, 422·invalid_username, 422·username_rejected, 429·cooldown_active, 429·rate_limited, 503, 400, terminal 401, 5xx, and network/IO failure maps to exactly one `UsernameChangeOutcome` member AND any other unenumerated non-2xx falls to the defined `NetworkError` fallback AND there is NO branch emitting a generic "load failed" copy

#### Scenario: 429 is split by the error discriminator into CooldownActive vs RateLimited
- **GIVEN** a MockEngine returning `429 {"error":"cooldown_active"}` with `Retry-After: 1209600` and, separately, `429 {"error":"rate_limited"}` with `Retry-After: 1800`
- **WHEN** the repository processes each response
- **THEN** the first maps to `CooldownActive(retryAfterSeconds = 1209600)` and the second to `RateLimited(retryAfterSeconds = 1800)` — the body `error` code, not the status alone, selects the outcome

#### Scenario: An absent or unparseable Retry-After floors to 0 seconds
- **GIVEN** a MockEngine returning `429 {"error":"cooldown_active"}` with NO `Retry-After` header, and separately `429 {"error":"rate_limited"}` with an unparseable (HTTP-date) `Retry-After`
- **WHEN** the repository processes each response
- **THEN** the outcomes are `CooldownActive(retryAfterSeconds = 0)` and `RateLimited(retryAfterSeconds = 0)` respectively AND no crash occurs (the screen later floors a non-positive value to one day / one minute)

#### Scenario: 422 is split by the error discriminator into InvalidFormat vs Moderated
- **GIVEN** a MockEngine returning `422 {"error":"invalid_username"}` and, separately, `422 {"error":"username_rejected"}`
- **WHEN** the repository processes each response
- **THEN** the first maps to `InvalidFormat` and the second to `Moderated`

#### Scenario: Terminal 401 maps to SessionExpired, never NetworkError
- **GIVEN** a MockEngine that responds 401 to the `PATCH` AND 401 to the subsequent `POST /api/v1/auth/refresh` (a terminal 401 surfaced by the `Auth` plugin)
- **WHEN** the repository processes the result
- **THEN** the outcome is `SessionExpired` AND it is NOT `NetworkError` AND NOT `Error`

### Requirement: The probe-outcome mapping is status-driven over a sealed UsernameCheckOutcome

`UsernameRepository` SHALL map each `GET /api/v1/username/check` result to exactly one member of a sealed `UsernameCheckOutcome`: **200** → `Available(isAvailable)`; **403** → `CheckPremiumGate`; **422** (`invalid_username`) → `CheckInvalidFormat` (defensive); **429** (`rate_limited`) → `ProbeExhausted`; **503** → `CheckDisabled`; **terminal 401** → `CheckSessionExpired`; **5xx / network-IO / any other** → `CheckNetworkError`. The probe mapping MUST NOT crash on any status.

#### Scenario: Probe statuses each map to one outcome
- **WHEN** inspecting the probe result mapping and `UsernameCheckOutcome`
- **THEN** HTTP 200, 403, 422, 429, 503, terminal 401, and 5xx/network each map to exactly one `UsernameCheckOutcome` member AND no status path crashes

### Requirement: Screen state mapping covers editing, availability, error, cooldown, gate, disabled, session, submitting, and success states

`UsernameCustomizationScreen` state SHALL be modeled as a Compose-free `UsernameUiState` (sealed type or data class) plus a pure projection `usernameUiState(...)` (over the current input, the local format-validity, the latest `UsernameCheckOutcome`, the latest `UsernameChangeOutcome`, and the in-flight flags) — mirroring `mobile-search`'s `searchUiState(...)` — so the mapping is deterministically unit-testable in commonTest without composing UI. The screen SHALL render exactly one state, all copy via `stringResource` following the `mobile-design-system` loading-state contract (never two simultaneous progress indicators):

- **Editing** → the field with, beneath it, the live status: the inline format message (`username_error_format`) for a format-invalid candidate, OR an availability hint for a format-valid candidate (`username_available` when the probe says available; `username_unavailable_generic` when the probe says unavailable; `username_probe_deferred` "akan dicek saat kamu simpan" when the probe is exhausted/not-yet-run). Submit is enabled only for a format-valid candidate that differs from the current handle.
- **Submitting** → a single progress indicator; the field and submit are disabled.
- **Success** → drives the success path (per the § "Successful change" requirement); the transient success copy is `stringResource(Res.string.username_success_toast)` ("Username berhasil diganti").
- **Unavailable** (`UnavailableState`) → the generic `stringResource(Res.string.username_unavailable_generic)` ("Username ini tidak tersedia. Coba username lain.") — the single message the `409` envelope permits (the three distinct docs/03 messages are deferred per the § "Deferred scope" requirement).
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

### Requirement: A submit-confirmation modal precedes the destructive change

On activating "Ganti" for a format-valid candidate that differs from the current handle, `UsernameCustomizationScreen` SHALL FIRST present a confirmation modal (docs/03 §133) with body `stringResource(Res.string.username_confirm_body)` formatted with the old and new handles ("Ganti username dari @{old} menjadi @{new}? Username lama akan dilepas ke publik 30 hari setelah perubahan."), a primary `stringResource(Res.string.username_confirm_primary)` ("Ganti"), and a secondary `stringResource(Res.string.username_confirm_dismiss)` ("Batal"). The `PATCH` SHALL be issued ONLY when the primary action is confirmed; "Batal" SHALL dismiss the modal and issue no request.

#### Scenario: Confirm fires the PATCH; dismiss issues nothing
- **GIVEN** `UsernameCustomizationScreen` over a counting `FakeUsernameFlow` with a format-valid candidate entered
- **WHEN** "Ganti" is activated, the modal appears, and the primary action is confirmed
- **THEN** exactly one change request is issued for the candidate
- **WHEN** instead the modal's "Batal" is activated
- **THEN** no change request is issued (the fake's change-invocation count stays 0)

### Requirement: A successful change shows the success toast and returns to Settings

On a `Success(username)` outcome, `UsernameCustomizationScreen` SHALL surface the transient success toast (`stringResource(Res.string.username_success_toast)`, "Username berhasil diganti") and invoke the hoisted `onChanged` to pop `UsernameCustomizationRoute` back to Settings. The new handle propagation (docs/03 §134 "the profile reloads") relies on the existing **stateless** `ProfileFlow` (`loadProfile(userId)`) re-fetching on the next read of the self-profile surface — there is NO observable cross-screen self-profile cache to invalidate (verified: `ProfileFlow` exposes no cached `StateFlow`), so this change SHALL NOT assert or introduce a cache-invalidation seam; the profile and settings surfaces reload their own state on next composition/resume. The success one-shot SHALL be modeled as a nullable `UsernameUiState` field consumed via an `onSuccessShown()` callback (the docs/11 §2.2 one-shot-events-are-state rule), not a `Channel`/`SharedFlow` event bus.

#### Scenario: Success shows the toast and pops the route without a cache-invalidation seam
- **GIVEN** `UsernameCustomizationScreen` over a `FakeUsernameFlow` returning `Success("newhandle")` and a recording `onChanged`
- **WHEN** the change completes
- **THEN** the success toast copy is shown AND `onChanged` fires (popping the route) AND no cross-screen profile-cache-invalidation call is made (none exists) — and the success one-shot is cleared via `onSuccessShown()` so it does not re-fire on recomposition

### Requirement: The Premium gate renders the upsell and routes to the paywall via a hoisted callback

While the change (or probe) outcome is `PremiumGate` (the authoritative `403 premium_required` gate — the backstop that also covers the `premium_billing_retry` edge and any stale client `isPremium` hint), `UsernameCustomizationScreen` SHALL render a Free-tier upsell panel: an explanatory body via `stringResource(Res.string.username_premium_gate_body)` (docs/03 §114: "Ganti username adalah fitur Premium") and a primary CTA via `stringResource(Res.string.username_premium_gate_cta)` ("Aktifkan Premium") that invokes the hoisted `onActivatePremium` lambda. The screen SHALL hold no back-stack reference; the `appEntryProvider` call site SHALL wire `onActivatePremium` to push `PaywallRoute` (introduced by `mobile-paywall-screen` #309). The gate panel SHALL issue no further change/probe request while shown.

#### Scenario: 403 renders the upsell and the CTA invokes the hoisted callback
- **GIVEN** `UsernameCustomizationScreen` over a `FakeUsernameFlow` returning `PremiumGate` and a recording `onActivatePremium`
- **WHEN** the screen renders and the "Aktifkan Premium" CTA is activated
- **THEN** the tree contains the upsell body (`username_premium_gate_body`) AND the CTA (`username_premium_gate_cta`) AND activating it fires the hoisted `onActivatePremium` (the screen appends no route itself — navigation is owned by the call site)

#### Scenario: The gate reached via the probe path also renders the upsell
- **GIVEN** `UsernameCustomizationScreen` over a `FakeUsernameFlow` whose probe returns `CheckPremiumGate`
- **WHEN** the screen renders
- **THEN** the same upsell panel (`username_premium_gate_body` + `username_premium_gate_cta`) is shown (the gate state is reached identically from the change `PremiumGate` and the probe `CheckPremiumGate`)

### Requirement: The screen resolves Premium status on entry; the Settings row pushes the route unconditionally

To honour docs/03 §114 ("Free user taps: paywall opens") WITHOUT adding a self-profile read to `SettingsScreen` (which today holds no Premium signal — it injects only `SettingsViewModel(tokenStore)`), the Premium gate SHALL be owned by the route-scoped screen, not the Settings row: the `SettingsScreen` "Ganti username" row SHALL push `UsernameCustomizationRoute` **unconditionally** (no `isPremium` branch in Settings), and the `UsernameCustomizationViewModel` SHALL resolve the caller's Premium status on entry via a self-profile read (`ProfileFlow.loadProfile(selfUserId)`, the existing stateless seam — `selfUserId` from the existing `SelfUserIdProvider`), rendering the `PremiumGate` as the INITIAL state when the self read reports not-Premium and the editor otherwise. Because the client `isPremium` signal is `true` only for `premium_active` (NOT `premium_billing_retry`), the reactive `403 premium_required` from a probe/submit SHALL remain the **authoritative** gate (the billing-retry/staleness backstop); a self-read mis-classifying a billing-retry user as Free shows the paywall (harmless — re-subscribe is the intended action). A self-profile read failure SHALL degrade to letting the user attempt the action (the reactive `403` then governs), never an error wall.

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

### Requirement: UsernameApiClient, UsernameRepository, and the ViewModel are Koin singletons behind a testable seam

`UsernameApiClient` and `UsernameRepository` SHALL be registered in the commonMain Koin `mobileModule`. `UsernameRepository` SHALL be bound behind a `UsernameFlow` interface (`single<UsernameFlow> { get<UsernameRepository>() }`) so a `FakeUsernameFlow` can drive the screen + ViewModel tests, mirroring the timeline / search seams. The `UsernameCustomizationViewModel` SHALL be a commonMain androidx `ViewModel` scoped to the `UsernameCustomizationRoute` NavEntry (resolved via `koinViewModel()` under the root `NavDisplay`'s `rememberViewModelStoreNavEntryDecorator()` — the pushed-route precedent), exposing ONE `StateFlow<UsernameUiState>` via `stateIn(...WhileSubscribed(5_000)...)`; it SHALL own the input, the debounced probe (a 500 ms debounce that coalesces rapid keystrokes into a single probe — protecting the 3/day budget), the submit, the success one-shot, AND the on-entry self-Premium resolution (per the § "resolves Premium status on entry" requirement), talking to the `UsernameFlow` seam (and the existing self-profile read) — never to an ApiClient directly. The query/probe/submit state is owned by the ViewModel, NOT composition-scoped `remember`.

#### Scenario: Koin registers the username graph behind the flow interface
- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/di/MobileModule.kt`
- **THEN** `mobileModule` declares singletons for `UsernameApiClient` and `UsernameRepository` AND binds `single<UsernameFlow> { get<UsernameRepository>() }`

#### Scenario: The ViewModel drives probe + submit through the UsernameFlow seam
- **GIVEN** a commonTest `UsernameCustomizationViewModel` over a `FakeUsernameFlow`
- **WHEN** a format-valid candidate is typed (then settles) and, separately, submitted-and-confirmed
- **THEN** the ViewModel invokes `UsernameFlow.check(candidate)` for the settled candidate and `UsernameFlow.change(candidate)` for the confirmed submit, exposing the resulting outcomes through the `usernameUiState(...)` projection

#### Scenario: The debounce coalesces rapid keystrokes into a single probe
- **GIVEN** a commonTest `UsernameCustomizationViewModel` over a counting `FakeUsernameFlow` and a test-advanceable dispatcher
- **WHEN** a format-valid candidate is typed character-by-character within the 500 ms window and the dispatcher is then advanced past it
- **THEN** exactly one `UsernameFlow.check(...)` is invoked (for the settled candidate), NOT one per keystroke — the debounce protects the 3/day probe budget

### Requirement: Test coverage for the screen, projection, format guard, networking, and route

The change SHALL ship: (1) a Robolectric `UsernameCustomizationScreenTest` (`mobile/app/src/androidUnitTest/...`) covering the field + live format feedback, each visual state (Editing·available / Editing·unavailable / Editing·probe-deferred / Submitting / Unavailable / Moderated / CooldownActive / RateLimited / PremiumGate / Disabled / SessionExpired / Error) via a `FakeUsernameFlow`, the on-entry gate resolution (not-Premium self-read → initial `PremiumGate`; Premium → editor) and the probe-path gate, the submit-confirmation modal (confirm fires / dismiss does not), and the success toast + `onChanged` pop — added to the `mobile/app/build.gradle.kts` Release-variant test-exclude list (per the `*ScreenTest` convention); (1b) an update to the existing (already Release-excluded) `SettingsScreenTest` asserting the "Ganti username" row pushes `UsernameCustomizationRoute` unconditionally (the MODIFIED `mobile-settings` row routing); (2) a commonTest `UsernameUiStateTest` for the pure projection (including the non-positive countdown floor); (3) commonTest for the format guard (length/charset/consecutive-dots matrix mirroring the backend's accept/reject examples, including the 30-accept / 31-reject boundary) and the `UsernameCustomizationRoute` serialized round-trip; (4) MockEngine-backed `UsernameApiClient` / `UsernameRepository` tests verifying the `PATCH` body + the `GET` `candidate` param, the shipped success/available wire parse, each change-status → `UsernameChangeOutcome` mapping (including the 429 `cooldown_active`/`rate_limited` and 422 `invalid_username`/`username_rejected` error-code splits and the `Retry-After` parse + non-positive floor), and each probe-status → `UsernameCheckOutcome` mapping; (5) an `iosTest` flow test (`UsernameFlowIosTest`) mirroring `SearchFlowIosTest` — exercising the username flow over a `FakeUsernameFlow` on the iOS/Native target so the new route + data seam compile and run on Kotlin/Native.

#### Scenario: Test classes exist and are discoverable
- **WHEN** running `./gradlew :mobile:app:testDevDebugUnitTest`
- **THEN** `UsernameCustomizationScreenTest`, `UsernameUiStateTest`, the format-guard + route round-trip tests, the `UsernameApiClient`/`UsernameRepository` MockEngine tests, and the `SettingsScreenTest` "Ganti username" row-routing assertion are discovered AND each documented state / mapping corresponds to at least one `@Test`

#### Scenario: The iOS flow test exists for the Native target
- **WHEN** inspecting `mobile/app/src/iosTest/...`
- **THEN** a `UsernameFlowIosTest` exists (mirroring `SearchFlowIosTest`) exercising the username flow over a `FakeUsernameFlow` on the iOS/Native target

#### Scenario: Screen test is excluded from the Release variant
- **WHEN** inspecting `mobile/app/build.gradle.kts`
- **THEN** the Release-variant exclude block lists `**/UsernameCustomizationScreenTest*` alongside the existing `*ScreenTest` exclusions AND `:mobile:app:testDevReleaseUnitTest` passes

### Requirement: Proactive cooldown state, distinct unavailable messages, downgrade banner, and autocomplete are explicitly deferred

This change SHALL NOT implement, and SHALL record each as a `tasks.md` note AND a `follow-up` GitHub issue (NOT a silent drop): (a) the **proactive** cooldown disabled-entry state at the Settings row (docs/03 §129 "Ganti username berikutnya tersedia dalam {countdown} hari." computed *before* tapping) — it requires a `username_last_changed_at` field on the self-profile read, which the shipped `UserProfileResponse` does not expose; the v1 surfaces the cooldown **reactively** via the `429 cooldown_active` `Retry-After`; (b) the three **distinct** unavailable messages (docs/03 §121–123 Reserved / Collision / On-release-hold) — the shipped `409 username_unavailable` envelope carries no reason discriminator; the v1 shows one generic message, and the follow-up tracks enriching the `409`/probe envelope with a reason; (c) the distinct **downgrade banner** (docs/03 §140) — it needs a "previously customized" signal the client lacks; a downgraded Free user functionally falls into the Free → paywall path; (d) username **autocomplete / typeahead** — no backend endpoint exists.

#### Scenario: The deferrals are tracked, not silent
- **WHEN** inspecting `tasks.md` and the change's follow-up issues
- **THEN** the proactive-cooldown, distinct-unavailable-messages, downgrade-banner, and autocomplete deferrals are each recorded with a `follow-up` GitHub issue reference AND the v1 surface functions without them (reactive cooldown, one generic unavailable message, Free → paywall, no autocomplete)

