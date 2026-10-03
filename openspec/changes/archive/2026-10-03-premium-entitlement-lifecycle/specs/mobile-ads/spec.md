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

### Requirement: Ad eligibility is re-evaluated per signed-in session

The shared `AdFeedController`'s once-per-session `prepare()` latch (the single-flight `ads-config` fetch + SDK init + UMP gate) SHALL be scoped to the signed-in session, not the process. The session is identified by `PremiumEntitlementSession.sessionKey()`: the user id plus a count of ended sessions (`mobile-premium-entitlement`).

- `prepare()` within the same session it last prepared for SHALL remain a no-op.
- `prepare()` in a new session (any sign-out → sign-in on the same process, including back into the SAME account) SHALL discard the previous session's published frequency and cached ads, then re-evaluate eligibility from a fresh `ads-config` fetch.

So a second account never inherits the first account's ad eligibility. A buyer who signs out and back in as the same account never sees the stale pre-purchase frequency, because the confirmed-purchase signal resets on sign-out.

The published frequency SHALL also be readable synchronously, so a feed re-entering composition seeds its collector with the current value instead of rendering one ad-less frame.

#### Scenario: The same session stays latched

- **GIVEN** `AdFeedController` already prepared in the session for `"u-1"`
- **WHEN** `prepare()` runs again in that session
- **THEN** `ads-config` is not fetched a second time

#### Scenario: A different account re-evaluates

- **GIVEN** `AdFeedController` prepared for account `"u-1"` with a published frequency
- **WHEN** account `"u-2"` is signed in and `prepare()` runs AND `ads-config` now returns disabled
- **THEN** `ads-config` is fetched again AND the published frequency is `null`

#### Scenario: A different account never gets the previous account's cached ad

- **GIVEN** both `"u-1"` and `"u-2"` are ad-eligible AND `"u-1"` loaded an ad for slot `"ad:6"`
- **WHEN** `"u-2"` is signed in, `prepare()` runs, and `"u-2"` loads slot `"ad:6"`
- **THEN** the ad provider is asked for a fresh ad (the `"u-1"` cached ad was dropped)

#### Scenario: A buyer signing back in as the same account re-evaluates

- **GIVEN** `"u-1"` prepared with a published frequency, then purchased (confirmed signal set), then signed out
- **WHEN** `"u-1"` signs back in and `prepare()` runs AND `ads-config` now returns disabled
- **THEN** `ads-config` is fetched again AND the published frequency is `null` (the stale pre-purchase frequency is never re-shown)
