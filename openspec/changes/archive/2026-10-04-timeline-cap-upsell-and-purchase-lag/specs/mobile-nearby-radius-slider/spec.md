## MODIFIED Requirements

### Requirement: Free tier is anchored to 20 km with a Premium upsell

For a Free viewer, the radius control SHALL remain anchored at 20 km: any attempt to select a non-20 km position SHALL snap the control back to 20 km AND surface the Premium upsell. The upsell is the shipped `RadiusPremiumUpsellDialog`, a countdown-less Free-upsell dialog: the radius gate is not time-bounded, so it is deliberately distinct from the daily-cap `DailyCapUpsellDialog`.

Its CTAs:
- "Aktifkan Premium" SHALL dismiss it AND invoke the Nearby surface's hoisted `onActivatePremium(PaywallEntry.RADIUS_GATE)`. `appEntryProvider` wires that to push `PaywallRoute(RADIUS_GATE)` onto the root back stack, so the paywall headline leads with the radius benefit.
- "Tutup" SHALL only dismiss.

The radius upsell MUST NOT open the paywall as `LIKE_CAP`. That entry belongs to the like-cap dialog hosted on the same Nearby surface, which keeps pushing `LIKE_CAP`.

During the post-purchase webhook-lag window (`mobile-premium-entitlement` § "Upsell surfaces show the activating notice during the webhook-lag window") the `RadiusPremiumUpsellDialog` SHALL render the shared `PremiumActivatingDialog` instead (the `premium_activating_title` / `premium_activating_body` copy and a single "Tutup" wired to the dismiss path), with no "Aktifkan Premium" CTA and no `RADIUS_GATE` push. A confirmed purchase already unlocks the client-side gate (§ "On-entry tier resolution and reactive 403 backstop"), so in that window the dialog is raised only by the `radius_premium_only` 403 backstop while the server tier lags. The dialog reads the signal itself, so the Nearby host and `NearbyTimelineViewModel` are unchanged.

A Free viewer's effective `radius_m` SHALL therefore always be `20000`: a non-20 km value SHALL NOT be issued to the backend from a Free session. A viewer whose tier reads as **not Premium** (`is_premium = false`, i.e. `subscription_status != premium_active` per `user-profile-read`) SHALL be treated as Free here. This includes a grace-period `premium_billing_retry` user, by design Decision 6.

#### Scenario: Free drag snaps back and upsells
- **GIVEN** a Free viewer (`isPremiumKnown = false`) on the Nearby surface
- **WHEN** the viewer drags the radius control to 50 km
- **THEN** the control returns to the 20 km position AND the Premium upsell is shown AND no Nearby fetch with `radius_m=50000` is issued

#### Scenario: The radius upsell CTA opens the paywall as RADIUS_GATE
- **GIVEN** the radius upsell shown on the Nearby surface under the real `appEntryProvider`
- **WHEN** "Aktifkan Premium" is tapped
- **THEN** the upsell is dismissed AND the root back stack's top entry is `PaywallRoute(entry = PaywallEntry.RADIUS_GATE)` (NOT `LIKE_CAP`)

#### Scenario: Free effective radius stays 20 km
- **GIVEN** a Free viewer
- **WHEN** any radius interaction occurs
- **THEN** every Nearby fetch issued in that session carries `radius_m=20000`

#### Scenario: Grace-period user is client-anchored to 20 km (Decision 6)
- **GIVEN** a viewer whose `subscription_status = premium_billing_retry` (so the wire `is_premium = false` and `isPremiumKnown = false`), even though the backend would admit a wider radius for that tier
- **WHEN** the viewer drags the radius control to 50 km
- **THEN** the control returns to 20 km AND the Premium upsell is shown AND no `radius_m=50000` fetch is issued (the client is conservative; the server stays authoritative — the wider radius is simply never requested)

#### Scenario: The radius upsell shows the activating notice after a confirmed purchase
- **GIVEN** `RadiusPremiumUpsellDialog` composed with `premiumActivating = true` and recording `onDismiss` / `onActivatePremium` callbacks
- **WHEN** the dialog renders and "Tutup" is tapped
- **THEN** it shows `premium_activating_title` and `premium_activating_body` AND no "Aktifkan Premium" control AND `onDismiss` fires once AND `onActivatePremium` is never invoked
