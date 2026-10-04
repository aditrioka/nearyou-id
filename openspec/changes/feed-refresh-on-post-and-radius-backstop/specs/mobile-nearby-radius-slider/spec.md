## MODIFIED Requirements

### Requirement: On-entry tier resolution and reactive 403 backstop

The radius gate SHALL follow the on-entry self-`isPremium` read idiom: an `isPremiumKnown: Boolean?` that is `null` (Resolving) until the self-profile read resolves it. `UsernameCustomizationViewModel` is the on-entry `isPremiumKnown` precedent; `SearchViewModel` supplies the reactive-403 backstop half. Until tier is known, the control SHALL behave as Free (anchored at 20 km).

The resolved tier SHALL also honour a confirmed client purchase, the `purchaseConfirmed` signal from the `mobile-premium-entitlement` capability. The effective tier is the self-profile `isPremium` OR `purchaseConfirmed`, so a self-profile read that lags the webhook cannot overwrite a confirmed purchase. When `purchaseConfirmed` becomes `true` while the HomeRoute-scoped `NearbyTimelineViewModel` is alive (the buyer returns from the paywall pushed atop Home), `isPremiumKnown` SHALL become `true` without a cold start or re-entry, so the Premium radii are selectable immediately.

As a server-authoritative backstop, a Nearby fetch that returns HTTP 403 `radius_premium_only` SHALL be mapped to the SAME Premium upsell surface as the client-side snap-back (never surfaced as a raw error), and the control SHALL revert to 20 km. This includes the webhook-lag window after a confirmed purchase. This 403 handling SHALL be owned by the **ViewModel layer**. The repository surfaces the parsed `error.code` as `NearbyFetchResult.PremiumGated`, outside `NearbyTimelineOutcome`, and the ViewModel owns the interpretation (revert to 20 km + upsell). It SHALL NOT introduce a new `NearbyTimelineOutcome` member: the `mobile-nearby-timeline` status→outcome mapping (401 → `SessionExpired`, other non-2xx → the retryable `Error`/`NetworkError` fallback) is unchanged, and the `radius_premium_only` upsell is a ViewModel-level interpretation layered above it.

The backstop SHALL cover **every** Nearby fetch path through one gate mapping: the initial load, pull-to-refresh, the error-state retry, a radius selection, and load-more.

- **Repository.** The repository's shared status mapping SHALL return `NearbyFetchResult.PremiumGated` for this 403 from both `loadFirstPage` and `loadMore`. Every other result SHALL be `NearbyFetchResult.Loaded(<the unchanged outcome>)`. There SHALL be no separate radius-change fetch method: a radius selection is a page-1 load at the newly selected radius.
- **ViewModel.** Every page-1 fetch SHALL go through one fetch function. Every `PremiumGated` result at a non-20 km radius SHALL go through one handler that reverts the control to 20 km and raises the upsell.
- **Gated page-1 fetch.** After the handler runs, page 1 SHALL be re-fetched at 20 km once.
- **Gated fetch at 20 km.** Whether it is the original fetch or the re-fetch, the Free anchor is never gated, so this is a server fault. It SHALL map to the retryable error, with no upsell and no further fetch.
- **Gated load-more.** It SHALL drop the stale page, so no load-more retry footer is shown, and SHALL reload page 1 at 20 km.

#### Scenario: Pre-resolution behaves as Free
- **GIVEN** `isPremiumKnown = null` (tier not yet resolved)
- **WHEN** the viewer interacts with the radius control
- **THEN** the control behaves as the Free anchor (stays at 20 km, no Premium radius issued)

#### Scenario: A radius_premium_only 403 maps to the upsell
- **GIVEN** a Nearby fetch issued at a non-20 km radius
- **WHEN** the backend responds HTTP 403 with `error.code = "radius_premium_only"`
- **THEN** the Premium upsell is shown (the same surface as the client snap-back) AND the control reverts to 20 km AND no raw error state is rendered

#### Scenario: The 403 backstop adds no new NearbyTimelineOutcome member
- **WHEN** inspecting the `radius_premium_only` handling and the `NearbyTimelineOutcome` type
- **THEN** the repository surfaces the parsed `error.code` as `NearbyFetchResult.PremiumGated` and the ViewModel interprets it AND `NearbyTimelineOutcome` gains no new member for it (the `mobile-nearby-timeline` status→outcome mapping is untouched)

#### Scenario: A purchase confirmed while the VM is alive unlocks the Premium radii
- **GIVEN** `NearbyTimelineViewModel` whose on-entry self read resolved `isPremiumKnown = false` AND a `purchaseConfirmed` flow that is `false`
- **WHEN** `purchaseConfirmed` becomes `true` and the viewer then selects the 50 km radius
- **THEN** `isPremiumKnown` is `true` AND the selection is applied (a page-1 fetch at 50 km is issued) AND no upsell is raised

#### Scenario: A lagging Free self read does not override a confirmed purchase
- **GIVEN** `purchaseConfirmed` is already `true` AND the on-entry self-profile read returns `isPremium = false` (the webhook has not landed)
- **WHEN** the ViewModel finishes resolving tier
- **THEN** `isPremiumKnown` is `true`

#### Scenario: The error-state retry maps a radius_premium_only 403 to the upsell

- **GIVEN** the Nearby feed showing the retryable error state at a 50 km selection (the selection's fetch failed before any HTTP call)
- **WHEN** the viewer taps retry and the retried fetch at 50 km returns HTTP 403 `radius_premium_only`
- **THEN** the radius upsell is raised AND the control reverts to 20 km AND page 1 is re-fetched at 20 km AND the 403 is NOT rendered as the connectivity error

#### Scenario: Pull-to-refresh maps a radius_premium_only 403 to the upsell

- **GIVEN** the Nearby feed loaded at a 50 km selection
- **WHEN** a pull-to-refresh at 50 km returns HTTP 403 `radius_premium_only`
- **THEN** the radius upsell is raised AND the control reverts to 20 km AND page 1 is re-fetched at 20 km

#### Scenario: A gated load-more drops the stale page and reloads at 20 km

- **GIVEN** the Nearby feed loaded at a 50 km selection with a non-null `nextCursor`
- **WHEN** the load-more fetch at 50 km returns HTTP 403 `radius_premium_only`
- **THEN** the radius upsell is raised AND the control reverts to 20 km AND no load-more error footer is shown AND page 1 is re-fetched at 20 km

#### Scenario: The repository maps the 403 once for both fetch methods

- **GIVEN** a `NearbyTimelineRepository` over a MockEngine
- **WHEN** `loadFirstPage(50000)` and `loadMore(cursor, anchor, 50000)` each receive HTTP 403 with `error.code = "radius_premium_only"`, and separately a 403 with any other code
- **THEN** both `radius_premium_only` calls return `NearbyFetchResult.PremiumGated` AND the other 403 returns `NearbyFetchResult.Loaded` wrapping the unchanged retryable `NearbyTimelineOutcome.NetworkError`

#### Scenario: A gated fetch at 20 km is the retryable error, without a loop or an extra upsell

- **GIVEN** a server that answers `403 radius_premium_only` even at 20 km
- **WHEN** a 50 km selection is gated, and separately when a 20 km pull-to-refresh is gated
- **THEN** the 50 km case issues exactly one 20 km re-fetch and ends in the retryable `NetworkError` AND the 20 km refresh issues no re-fetch, raises no upsell, and ends in `NetworkError`
