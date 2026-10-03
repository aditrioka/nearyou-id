## MODIFIED Requirements

### Requirement: On-entry tier resolution and reactive 403 backstop

The radius gate SHALL follow the on-entry self-`isPremium` read idiom: an `isPremiumKnown: Boolean?` that is `null` (Resolving) until the self-profile read resolves it. `UsernameCustomizationViewModel` is the on-entry `isPremiumKnown` precedent; `SearchViewModel` supplies the reactive-403 backstop half. Until tier is known, the control SHALL behave as Free (anchored at 20 km).

The resolved tier SHALL also honour a confirmed client purchase, the `purchaseConfirmed` signal from the `mobile-premium-entitlement` capability. The effective tier is the self-profile `isPremium` OR `purchaseConfirmed`, so a self-profile read that lags the webhook cannot overwrite a confirmed purchase. When `purchaseConfirmed` becomes `true` while the HomeRoute-scoped `NearbyTimelineViewModel` is alive (the buyer returns from the paywall pushed atop Home), `isPremiumKnown` SHALL become `true` without a cold start or re-entry, so the Premium radii are selectable immediately.

As a server-authoritative backstop, a Nearby fetch that returns HTTP 403 `radius_premium_only` SHALL be mapped to the SAME Premium upsell surface as the client-side snap-back (never surfaced as a raw error), and the control SHALL revert to 20 km. This includes the webhook-lag window after a confirmed purchase. This 403 handling SHALL be owned by the **ViewModel layer** reading the parsed `error.code` from the fetch result. It SHALL NOT introduce a new `NearbyTimelineOutcome` member: the `mobile-nearby-timeline` status→outcome mapping (401 → `SessionExpired`, other non-2xx → the retryable `Error`/`NetworkError` fallback) is unchanged, and the `radius_premium_only` upsell is a ViewModel-level interpretation layered above it.

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
- **THEN** the 403 is interpreted in the ViewModel from the parsed `error.code` AND `NearbyTimelineOutcome` gains no new member for it (the `mobile-nearby-timeline` status→outcome mapping is untouched)

#### Scenario: A purchase confirmed while the VM is alive unlocks the Premium radii
- **GIVEN** `NearbyTimelineViewModel` whose on-entry self read resolved `isPremiumKnown = false` AND a `purchaseConfirmed` flow that is `false`
- **WHEN** `purchaseConfirmed` becomes `true` and the viewer then selects the 50 km radius
- **THEN** `isPremiumKnown` is `true` AND the selection is applied (a page-1 fetch at 50 km is issued) AND no upsell is raised

#### Scenario: A lagging Free self read does not override a confirmed purchase
- **GIVEN** `purchaseConfirmed` is already `true` AND the on-entry self-profile read returns `isPremium = false` (the webhook has not landed)
- **WHEN** the ViewModel finishes resolving tier
- **THEN** `isPremiumKnown` is `true`
