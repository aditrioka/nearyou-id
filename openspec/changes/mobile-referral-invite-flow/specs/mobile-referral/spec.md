## ADDED Requirements

### Requirement: ReferralRoute is a parameterless NavKey on the serializable back stack

The mobile app SHALL define a `ReferralRoute` `NavKey` as a parameterless `@Serializable data object` (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/routing/NavKeys.kt`), mapped to `ReferralScreen` by the `entryProvider`, and registered in the `navSavedStateConfiguration` polymorphic `SerializersModule` so the back stack is saveable on non-JVM targets (iOS). The route SHALL carry NO payload — the invite code and progress are fetched by the screen's ViewModel from the read endpoint, never carried on the route (the `UsernameCustomizationRoute` self-read precedent + the back-stack PII discipline, since the back stack persists to disk on iOS). The route SHALL be pushed onto the ROOT back stack above `SettingsRoute`, overlaying the section bar (the `BlockedUsersRoute` / `ConsentSettingsRoute` mechanism).

#### Scenario: ReferralRoute carries no payload and is registered for serialization
- **WHEN** inspecting `NavKeys.kt` and the `navSavedStateConfiguration` `SerializersModule`
- **THEN** `ReferralRoute` is a parameterless `@Serializable data object` declaring no properties AND it is registered in the polymorphic `SerializersModule` (so a serialized back stack containing it decodes on iOS restore)

### Requirement: ReferralScreen displays the invite code with a copy action

`ReferralScreen` (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/referral/ReferralScreen.kt`) SHALL display the user's shareable invite code (the `inviteCode` from the referral read) prominently AND SHALL provide a copy-to-clipboard action (Compose `LocalClipboardManager`, commonMain) labelled via a `:shared:resources` string. Tapping the copy action SHALL place the invite code on the clipboard. All UI copy SHALL come from `:shared:resources` CMP Resources (no hardcoded UI string literals). The screen SHALL render under `NearYouTheme` and reuse the Settings sub-surface visual idiom (the `ConsentSettingsScreen` / `BlockedUsersScreen` precedent), per the `mobile-design-system` substrate (no dedicated mockup frame exists for this surface).

#### Scenario: Loaded screen renders the invite code and a copy action
- **GIVEN** the referral state has loaded with `inviteCode = "a3f7k2mq"`
- **WHEN** `ReferralScreen` is composed
- **THEN** the rendered tree contains a node displaying `"a3f7k2mq"` AND a clickable copy action whose label sources from `Res.string.<referral copy action key>`

#### Scenario: Tapping copy places the code on the clipboard
- **GIVEN** a loaded `ReferralScreen` with `inviteCode = "a3f7k2mq"`
- **WHEN** the copy action is tapped
- **THEN** the clipboard content is `"a3f7k2mq"`

#### Scenario: No hardcoded UI strings in the referral surface source
- **WHEN** inspecting `ReferralScreen.kt`
- **THEN** every `Text(...)` / `contentDescription = ...` / similar UI-string-bearing call site sources its text via `stringResource(Res.string.<name>)`; zero literal string arguments appear in such call sites

### Requirement: ReferralScreen shows referral progress and reward state

`ReferralScreen` SHALL display the inviter-reward progress as a count toward the milestone — "<grantedReferrals> dari <milestone>" via a `:shared:resources` formatted string — and SHALL show a distinct reward-unlocked state when `inviterRewardClaimed` is `true` (the inviter lifetime reward has fired). The screen SHALL include explanatory copy describing the referral rewards (invitee earns Premium on activated registration; inviter earns Premium once at the 5th successful referral) sourced from `:shared:resources`. The displayed values are the read endpoint's reported server state — the screen computes no reward and grants nothing.

#### Scenario: Progress is rendered as count toward the milestone
- **GIVEN** the referral state has loaded with `grantedReferrals = 3`, `milestone = 5`, `inviterRewardClaimed = false`
- **WHEN** `ReferralScreen` is composed
- **THEN** the rendered tree shows the "3 dari 5" progress (via the formatted `:shared:resources` string) AND does not show the reward-unlocked state

#### Scenario: Claimed inviter reward shows the unlocked state
- **GIVEN** the referral state has loaded with `inviterRewardClaimed = true`
- **WHEN** `ReferralScreen` is composed
- **THEN** the rendered tree shows the reward-unlocked state (distinct from the in-progress state)

### Requirement: ReferralViewModel exposes one StateFlow with loading, loaded, and error states

`ReferralViewModel` (commonMain androidx `ViewModel`) SHALL expose exactly ONE `StateFlow<ReferralUiState>` via `stateIn(WhileSubscribed)` (the single-`stateIn` state-holder contract). On entry it SHALL fetch the referral state via `ReferralRepository`; the `ReferralUiState` SHALL model a loading state, a loaded state (carrying `inviteCode`, `grantedReferrals`, `milestone`, `inviterRewardClaimed`), and an error state (when the fetch fails). A retry from the error state SHALL re-fetch.

#### Scenario: Successful fetch transitions loading → loaded
- **GIVEN** a `ReferralViewModel` with a fake `ReferralRepository` returning a successful referral state
- **WHEN** the `uiState` is collected
- **THEN** it emits the loading state then the loaded state carrying the fetched `inviteCode` and progress

#### Scenario: Failed fetch transitions to the error state
- **GIVEN** a `ReferralViewModel` with a fake `ReferralRepository` returning a failure
- **WHEN** the `uiState` is collected
- **THEN** it emits the error state (no crash) AND a retry re-invokes the repository fetch

### Requirement: ReferralApiClient and ReferralRepository fetch the referral state fail-soft

`ReferralApiClient` (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/referral/ReferralApiClient.kt`) SHALL be a thin wrapper over the shared bearer-authed `HttpClient` issuing `GET /api/v1/user/referral` (mirroring `HideDistanceApiClient`); it SHALL add no auth header (the `Auth { bearer }` interceptor supplies the token) and SHALL expose a sealed result distinguishing success (carrying `inviteCode`, `grantedReferrals`, `milestone`, `inviterRewardClaimed`) from failure (any non-`200` OR a transport/parse error). A `CancellationException` SHALL be rethrown, never swallowed. `ReferralRepository` SHALL map the client result to the domain referral state (or a failure the ViewModel renders as the error state).

#### Scenario: 200 maps to a success result
- **GIVEN** a Ktor MockEngine responding `200 {"inviteCode":"a3f7k2mq","grantedReferrals":2,"milestone":5,"inviterRewardClaimed":false}`
- **WHEN** `ReferralApiClient` issues the request
- **THEN** it returns a success result carrying `inviteCode = "a3f7k2mq"`, `grantedReferrals = 2`, `milestone = 5`, `inviterRewardClaimed = false`

#### Scenario: Non-200 or transport failure maps to a failure result
- **WHEN** the request returns a non-`200` status OR the transport throws (non-cancellation)
- **THEN** `ReferralApiClient` returns the failure result (no exception propagates except `CancellationException`)

### Requirement: Settings exposes an Undang teman entry to the referral surface

The Settings screen (`mobile-settings`) SHALL present an "Undang teman" entry row (copy via `:shared:resources`) that navigates to `ReferralRoute` (per `docs/01-Business.md` § Referral System "invite code in Settings"). The entry SHALL be visible to all authenticated users regardless of subscription tier.

#### Scenario: Settings navigates to the referral surface
- **GIVEN** the Settings screen is composed for an authenticated user
- **WHEN** the "Undang teman" row is tapped
- **THEN** a navigation event pushing `ReferralRoute` is emitted

### Requirement: The referral surface is open to all tiers

The referral surface SHALL NOT be Premium-gated: `ReferralRoute` SHALL carry no `PaywallEntry`, `ReferralScreen` SHALL render no paywall/upsell, and the Settings "Undang teman" entry SHALL NOT route a Free user to the paywall. Referral participation (sharing a code, viewing progress) is universal per `docs/01-Business.md` § Referral System ("Open signup; invite codes add bonuses"); the rewards are Premium grants applied server-side, but the surface itself is ungated.

#### Scenario: A Free user reaches the referral surface without a paywall
- **GIVEN** a Free-tier authenticated user
- **WHEN** they tap "Undang teman" in Settings
- **THEN** they land on `ReferralScreen` showing their code and progress AND no paywall/upsell is shown AND no navigation to the paywall occurs

### Requirement: Native share-sheet is deferred to a follow-up

A native system share-sheet (Android `Intent.ACTION_SEND` / iOS `UIActivityViewController`, requiring an `expect/actual` platform glue) is **explicitly out of scope** for this change (a `docs/12-Integration-Contracts.md` §3 deferred layer). The v1 sharing affordance SHALL be copy-to-clipboard only. This deferral SHALL be tracked by a `follow-up` GitHub issue. A future change MAY add the share-sheet by MODIFYING this requirement.

#### Scenario: v1 ships copy-to-clipboard and no native share-sheet
- **WHEN** inspecting the referral surface in this change
- **THEN** the only sharing affordance is the copy-to-clipboard action AND no native share-sheet `expect/actual` is introduced AND a `follow-up` issue tracks the deferred share-sheet
