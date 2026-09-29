## MODIFIED Requirements

### Requirement: Premium viewers see zero ads

A viewer the server reports as premium SHALL see no ads anywhere: no SDK initialization for ad serving, no UMP form, and no ad slots interleaved into any feed. The server report is the `ads-config` `ads_enabled = false` for premium, which remains the single server-authoritative source that can ENABLE ads.

A confirmed client purchase SHALL also suppress ads. This is the `purchaseConfirmed` signal from the `mobile-premium-entitlement` capability. The signal can only REMOVE ads (the fail-safe direction) and never enables them, so it covers the window before the webhook flips the server tier:

- When `purchaseConfirmed` becomes `true`, the shared `AdFeedController` SHALL stop publishing an ad frequency immediately (any interleaved slots disappear without a cold start), and `loadAd` SHALL return no ad.
- A `prepare()` while `purchaseConfirmed` is `true` SHALL NOT initialize the ad SDK or request UMP consent.

#### Scenario: Premium viewer has no ad slots

- **WHEN** a premium viewer (whose `ads-config` returns `ads_enabled = false`) browses any feed
- **THEN** no native-ad slot appears and no UMP consent form is shown

#### Scenario: A confirmed purchase removes ad slots immediately

- **GIVEN** `AdFeedController` prepared with `ads-config` enabled (a non-null frequency published) AND a `purchaseConfirmed` flow that is `false`
- **WHEN** `purchaseConfirmed` becomes `true`
- **THEN** the published frequency becomes `null` AND `loadAd(...)` returns `null` without calling the ad provider

#### Scenario: A confirmed purchase skips SDK init and UMP on prepare

- **GIVEN** a `purchaseConfirmed` flow that is `true` before the first `prepare()`
- **WHEN** `prepare()` runs
- **THEN** the ad provider is not initialized AND consent is not requested AND the published frequency is `null`

## ADDED Requirements

### Requirement: Ad eligibility is re-evaluated per signed-in account

The shared `AdFeedController`'s once-per-session `prepare()` latch (the single-flight `ads-config` fetch + SDK init + UMP gate) SHALL be scoped to the signed-in account, not the process. The account is the signed-in user id, resolved through `SelfUserIdProvider`.

- `prepare()` for the same account it last prepared for SHALL remain a no-op.
- `prepare()` for a different account (after a sign-out → sign-in on the same process) SHALL discard the previous account's published frequency and cached ads, then re-evaluate eligibility from a fresh `ads-config` fetch.

A second account therefore never inherits the first account's ad eligibility, whether ads-on for a Premium second account or ads-off for a Free one.

#### Scenario: The same account stays latched

- **GIVEN** `AdFeedController` already prepared for account `"u-1"`
- **WHEN** `prepare()` runs again while `"u-1"` is signed in
- **THEN** `ads-config` is not fetched a second time

#### Scenario: A different account re-evaluates

- **GIVEN** `AdFeedController` prepared for account `"u-1"` with a published frequency and a cached ad
- **WHEN** account `"u-2"` is signed in and `prepare()` runs AND `ads-config` now returns disabled
- **THEN** `ads-config` is fetched again AND the published frequency is `null` AND the `"u-1"` cached ad is not served
