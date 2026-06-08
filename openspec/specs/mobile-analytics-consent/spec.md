# mobile-analytics-consent Specification

## Purpose
This capability governs the mobile analytics-and-tracking consent onboarding surface — the `ConsentScreen` interjected into the new-user flow after age-gate signup and before `HomeScreen`, satisfying the UU PDP requirement to collect tracking consent at onboarding (`docs/03-UX-Design.md` § Analytics & Tracking Consent). The screen presents three Material 3 toggles (Analytics, Crash Reporting, Ads Personalization) with per-category explainers and a continue CTA, initialized to the privacy-safe V2 column defaults (analytics OFF, crash ON, ads OFF) with no GET round-trip, then submits the toggle triple via `ConsentRepository.submitConsent(...)` against `PATCH /api/v1/user/consent`. Submit results map status-by-status with no generic fallthrough (`200`→route Home; `401`→terminal token-invalid; `5xx`/IO/`400`→retryable, with a non-trapping skip-to-Home affordance shown only after a failure), and the surface upholds the project's copy-via-Compose-Resources and PII (no token/`sub`/response-body logging) disciplines. It also owns the `ConsentRoute` serializable NavKey placement that replaces the age-gate entry so back-press cannot re-enter the age gate; reliable-persist hardening and a returning-user consent re-gate are explicitly deferred and FOLLOW_UPS-tracked.
## Requirements
### Requirement: ConsentScreen renders the three consent toggles, explainers, and continue CTA

The mobile app SHALL ship a screen `ConsentScreen` (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/consent/ConsentScreen.kt`) reached after age-gate signup success. The screen SHALL display: (a) a title via `stringResource(Res.string.consent_title)`; (b) an explainer via `stringResource(Res.string.consent_explainer)`; (c) three Material 3 toggle rows — Analytics (`consent_analytics_label` + `consent_analytics_desc`), Crash Reporting (`consent_crash_label` + `consent_crash_desc`), Ads Personalization (`consent_ads_label` + `consent_ads_desc`) — each a `Switch` with its label + description text; (d) a primary continue CTA via `stringResource(Res.string.consent_cta_continue)`. No hardcoded UI string literals SHALL appear in the screen source. The screen SHALL render under `NearYouTheme`, consistent with `AgeGateScreen`/`HomeScreen`.

#### Scenario: Initial render shows title, three toggles, and the continue CTA

- **WHEN** a Compose UI test (Robolectric `runComposeUiTest`, in `androidUnitTest` per the repo's established screen-test sourceset) renders `NearYouTheme { ConsentScreen(...) }` against a fresh composition
- **THEN** the rendered tree contains a node whose text matches the runtime value of `stringResource(Res.string.consent_title)` AND nodes whose text matches each of `consent_analytics_label`, `consent_crash_label`, `consent_ads_label` AND a clickable node whose text matches `stringResource(Res.string.consent_cta_continue)`

#### Scenario: No hardcoded UI strings in ConsentScreen source

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/consent/ConsentScreen.kt`
- **THEN** every `Text(...)` / `contentDescription = ...` / similar UI-string-bearing call site sources its text via `stringResource(Res.string.<name>)`; zero literal string arguments appear in such call sites

### Requirement: Toggles default to analytics OFF, crash ON, ads OFF (the V2 column default), with no GET round-trip

`ConsentScreen`'s initial toggle state SHALL be `analytics = false`, `crash = true`, `ads_personalization = false` — hardcoded to match the `users.analytics_consent` V2 default the just-created account already holds. The screen SHALL NOT issue a GET to read current consent before rendering (the account was created seconds earlier with exactly these defaults). The default state SHALL be injectable for testability (a default-values parameter / `ConsentUiState` initial value), not read from wall-clock or platform state.

#### Scenario: Default toggle states match the V2 column default

- **WHEN** `ConsentScreen` is composed with no prior interaction
- **THEN** the Analytics switch is OFF AND the Crash Reporting switch is ON AND the Ads Personalization switch is OFF

#### Scenario: No consent-read request is issued on entry

- **GIVEN** a Ktor MockEngine that records every outbound request
- **WHEN** `ConsentScreen` is composed and reaches idle
- **THEN** no `GET /api/v1/user/consent` (nor any consent read) request was recorded (the initial state comes from the hardcoded defaults, not a server read)

### Requirement: Continue submits the current toggle triple via PATCH and routes Home on 200

On continue-CTA tap, `ConsentRepository.submitConsent(analytics, crash, adsPersonalization)` SHALL issue `PATCH /api/v1/user/consent` (via the existing `Auth { bearer }`-interceptor `HttpClient`) with body `{"analytics": <toggle>, "crash": <toggle>, "ads_personalization": <toggle>}` (snake_case `@SerialName`). On HTTP `200`, a navigation event routing to `HomeScreen` (via the `ConsentScreen` `onDone` callback wired in `AppEntryProvider` — `replaceAll(HomeRoute)`; NOT `RootRouterScreen`) SHALL be emitted; no token write occurs (the screen does not touch `SecureTokenStore`).

#### Scenario: Submit issues the canonical PATCH with the toggle triple and routes Home on 200

- **GIVEN** a Ktor MockEngine capturing outbound requests that responds `200 {"analytics": true, "crash": false, "ads_personalization": true}`, AND `ConsentScreen` with Analytics toggled ON, Crash toggled OFF, Ads toggled ON
- **WHEN** the continue CTA is tapped
- **THEN** the captured outbound request is `PATCH /api/v1/user/consent` whose JSON body parses as `{"analytics": true, "crash": false, "ads_personalization": true}` AND a navigation event routing to `HomeScreen` is emitted

#### Scenario: Toggling a switch changes the submitted value

- **GIVEN** `ConsentScreen` at default state (analytics OFF) with a capturing MockEngine responding `200`
- **WHEN** the Analytics switch is toggled ON and continue is tapped
- **THEN** the captured PATCH body has `"analytics": true` (the submitted value reflects the toggle, not the default)

### Requirement: Submit outcome mapping is status-driven with no generic fallthrough

`ConsentRepository` SHALL map every submit result to exactly one `ConsentOutcome` keyed on the HTTP **status** and transport-failure type, NOT on a parsed `error.code`: `200`→`Success` (route Home); `401`→`TokenInvalid` terminal (render `stringResource(Res.string.signin_error_token_invalid)`, no route); `5xx`/`503`/IO failure→`Retryable` (render `consent_error_retryable` + `cta_retry`, stay on screen, no route); `400`→`Retryable` with a logged diagnostic (a `400` is a client bug here, never expected, but is surfaced as retryable rather than crashing). There SHALL be no generic else-branch that silently routes Home.

#### Scenario: 5xx maps to a retryable in-screen error, no navigation

- **GIVEN** a MockEngine responding `503`
- **WHEN** continue is tapped
- **THEN** the screen shows the `consent_error_retryable` copy with a `cta_retry` affordance AND NO navigation to `HomeScreen` is emitted

#### Scenario: 401 maps to a terminal token-invalid state, no navigation

- **GIVEN** a MockEngine responding `401`
- **WHEN** continue is tapped
- **THEN** the screen shows the `signin_error_token_invalid` copy AND NO navigation to `HomeScreen` is emitted

#### Scenario: IO transport failure maps to retryable

- **GIVEN** a MockEngine that throws an IO exception
- **WHEN** continue is tapped
- **THEN** the outcome is `Retryable` (the `consent_error_retryable` copy is shown) AND no unhandled exception escapes

### Requirement: A double-tap cannot fire two concurrent PATCH calls

`submitConsent` SHALL guard against concurrent invocation (an `isInFlight` flag / `Mutex.tryLock`, or a CTA disabled-while-loading state) so a rapid double-tap on continue issues exactly one `PATCH /api/v1/user/consent`.

#### Scenario: Double-tap continue issues exactly one PATCH

- **GIVEN** a MockEngine that counts `PATCH /api/v1/user/consent` requests and responds `200` after a brief delay
- **WHEN** the continue CTA is tapped twice in rapid succession before the first call completes
- **THEN** exactly one `PATCH /api/v1/user/consent` request was recorded

### Requirement: ConsentRoute is a serializable parameterless NavKey registered for iOS back-stack persistence

A `ConsentRoute` `@Serializable data object` SHALL be added to `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/routing/NavKeys.kt` and registered in the `AppNavSerialization` polymorphic `SerializersModule` (required for back-stack saveability on iOS, where Nav3 reflection-based serialization is unavailable, per `mobile-app-scaffold` "Back stack uses serializable NavKey routes"). It SHALL carry NO identity payload (the user identity lives in the persisted token, never in the serialized back stack).

#### Scenario: ConsentRoute round-trips through the nav serializer

- **WHEN** `ConsentRoute` is serialized and deserialized via the `AppNavSerialization` polymorphic configuration
- **THEN** the round-trip succeeds (the route is registered in the polymorphic module) AND `ConsentRoute` declares no identity property

### Requirement: ConsentRoute REPLACES the age-gate entry; back-press cannot re-enter the age gate

The signup-`201` transition SHALL navigate to `ConsentRoute` via `replaceAll` (NOT a `push`/append onto the existing back stack), so the `AgeGateRoute` entry is cleared and a system back gesture on `ConsentScreen` cannot return the user to the age gate (the account is already created and tokens persisted; re-entering the age gate would be incorrect). After this transition the back stack SHALL contain `ConsentRoute` as its only/top entry with no `AgeGateRoute` beneath it.

#### Scenario: After signup-201, the back stack holds ConsentRoute with no AgeGateRoute beneath

- **GIVEN** a back stack on `AgeGateRoute` and a signup that returns `201`
- **WHEN** the signup-success transition runs
- **THEN** the resulting back stack contains `ConsentRoute` (top) AND does NOT contain `AgeGateRoute` (it was replaced, not pushed) — so a back gesture cannot navigate to the age gate

### Requirement: ConsentScreen and its repository never log the token, sub, or response body

No source file under `screens/consent/**` or `consent/**` (the entire consent package surface — `ConsentScreen`, `ConsentViewModel`, `ConsentApiClient`, `ConsentRepository`, `ConsentFlow`, and any sibling) SHALL log the bearer token, the JWT `sub`, or the PATCH response body. The Ktor client `LogLevel` used by this path MUST NOT include bodies (consistent with Mobile #3's `LogLevel.HEADERS` posture).

#### Scenario: Consent sources contain no token/sub/body log argument

- **WHEN** inspecting every source file under `screens/consent/**` and `consent/**` (the scan globs the package, not an enumerated file list, so a later-added file like `ConsentFlow` is covered)
- **THEN** no logging call site passes the bearer token, the `Authorization` header, the JWT `sub`, or the PATCH response body as a logged argument

### Requirement: A failed persist offers a non-trapping proceed-to-Home; the happy path shows no skip

To avoid trapping a user in onboarding on a transient persist failure, AFTER a `Retryable` outcome `ConsentScreen` SHALL present a skip affordance via `stringResource(Res.string.consent_skip)` that routes to `HomeScreen` (the account retains the server's safe defaults). On the happy path — before any failed submit — the screen SHALL NOT present the skip affordance (the consent step reads as a required action, not an optional one). This best-effort posture is sound because the V2 defaults are privacy-safe and no tracking SDK reads `analytics_consent` in this change; reliable persistence is deferred (see the persist-hardening requirement).

#### Scenario: Skip appears only after a failed submit and routes Home

- **GIVEN** `ConsentScreen` freshly composed (no prior submit)
- **THEN** no `consent_skip` affordance is present
- **WHEN** continue is tapped against a MockEngine responding `503` (producing a `Retryable` outcome)
- **THEN** a `consent_skip` affordance becomes present AND tapping it emits a navigation event routing to `HomeScreen`

### Requirement: Reliable consent persistence is deferred and tracked

This change SHALL NOT implement background retry/queueing of a failed consent PATCH beyond the in-screen retry + skip. A failed-then-skipped submit leaves the server at its prior (default) value without enqueuing a later sync. The reliable-persist hardening (so a failed PATCH cannot leave a future tracking SDK mismatched against the user's choice) is deferred and SHALL be recorded as `FOLLOW_UPS.md` entry `mobile-analytics-consent-persist-hardening`.

#### Scenario: A failed-then-skipped submit enqueues no background retry, and the deferral is tracked

- **GIVEN** `ConsentScreen`, a MockEngine responding `503`, and the skip affordance taken after the failure
- **WHEN** the user reaches `HomeScreen` via skip
- **THEN** no background/queued consent retry was scheduled (the skip is terminal for this onboarding session) AND `FOLLOW_UPS.md` contains an entry `mobile-analytics-consent-persist-hardening`

### Requirement: RootRouter does not re-gate returning token-bearing users on consent completion (deferred)

Because consent lives only in the signup→Home transition, a user who force-quits at `ConsentScreen` holds a valid token and is routed straight to `HomeScreen` on the next launch (consent bypassed). This change SHALL NOT add a `consent_completed_at` flag or a `RootRouterScreen` consent re-gate to prevent that — the V2 safe defaults make the bypass benign for MVP (a bypassing user is at analytics=false/ads=false, and crash=true is the documented opt-out-able default). The re-gate is deferred and SHALL be recorded as `FOLLOW_UPS.md` entry `mobile-analytics-consent-rootrouter-regate`.

#### Scenario: A returning token-bearing user reaches Home without a consent re-prompt, and the deferral is tracked

- **GIVEN** a `SecureTokenStore` holding a valid token (a previously-created account)
- **WHEN** the app launches and `RootRouterScreen` resolves the start destination
- **THEN** the user is routed to `HomeRoute` directly (no `ConsentRoute` re-gate is interposed for an already-token-bearing user) AND `FOLLOW_UPS.md` contains an entry `mobile-analytics-consent-rootrouter-regate`

