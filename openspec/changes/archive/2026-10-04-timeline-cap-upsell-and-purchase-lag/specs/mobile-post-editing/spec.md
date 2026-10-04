## MODIFIED Requirements

### Requirement: Premium gating is reactive on the backend 403

The edit flow SHALL NOT read a client-side premium flag before allowing an edit attempt. It SHALL attempt the `PATCH` and react to the response, mirroring the shipped `mobile-search` reactive-gate house pattern.

When the submit returns `403 premium_required`, the flow SHALL present the Premium upsell ("Aktifkan Premium" CTA) rather than a generic error, and the post SHALL remain unchanged in the UI. The upsell's CTAs:
- "Aktifkan Premium" SHALL dismiss the upsell AND invoke the edit screen's hoisted `onActivatePremium`. `appEntryProvider` wires that to push `PaywallRoute(entry = PaywallEntry.EDIT_GATE)` onto the root back stack (the `mobile-paywall` capability, frame 17).
- The dismiss action SHALL only dismiss.

During the post-purchase webhook-lag window (`mobile-premium-entitlement` § "Upsell surfaces show the activating notice during the webhook-lag window") the `403 premium_required` upsell SHALL render the shared `PremiumActivatingDialog` instead: the `premium_activating_title` / `premium_activating_body` copy and a single "Tutup" that dismisses, with no "Aktifkan Premium" CTA and no `EDIT_GATE` push. The editor text stays as typed, so the viewer can submit again. This reads the signal only to choose the upsell's rendering after the `403`. The attempt itself is still never gated on a client premium flag (§ "No premium pre-check gates the attempt").

The edit screen stays navigation-free (no back-stack reference). The CTA MUST NOT be a dismiss-only dead end: the paywall shipped, so the v1 "no paywall yet" placeholder is retired.

#### Scenario: Free-tier user is shown the Premium upsell

- **WHEN** a Free-tier viewer submits an edit and the backend returns `403 premium_required`
- **THEN** the Premium upsell is presented (the "Aktifkan Premium" CTA), not a generic error
- **AND** the displayed post content is unchanged

#### Scenario: The upsell CTA invokes the hoisted callback

- **GIVEN** `EditPostScreen` composed with a recording `onActivatePremium` over a `PostEditFlow` returning `PremiumRequired`
- **WHEN** the viewer submits an edit and taps "Aktifkan Premium"
- **THEN** `onActivatePremium` fires exactly once AND the upsell is dismissed

#### Scenario: The edit gate opens the paywall as EDIT_GATE

- **GIVEN** the edit upsell shown under the real `appEntryProvider`
- **WHEN** "Aktifkan Premium" is tapped
- **THEN** the root back stack's top entry is `PaywallRoute(entry = PaywallEntry.EDIT_GATE)`

#### Scenario: Dismissing the upsell does not open the paywall

- **GIVEN** the edit upsell shown with a recording `onActivatePremium`
- **WHEN** the dismiss action is tapped
- **THEN** the upsell is dismissed AND `onActivatePremium` is not invoked

#### Scenario: No premium pre-check gates the attempt

- **WHEN** the viewer selects Edit
- **THEN** the editor opens and the attempt is made without first reading any client-side premium/entitlement flag (the 403 path is the gate)

#### Scenario: The 403 upsell shows the activating notice after a confirmed purchase

- **GIVEN** `EditPostScreen` whose Koin graph binds a `PremiumEntitlementSession` on which `onPurchaseConfirmed()` has run, over a `PostEditFlow` returning `PremiumRequired`, with a recording `onActivatePremium`
- **WHEN** the viewer submits an edit
- **THEN** the activating notice (`premium_activating_title` + `premium_activating_body`) is shown with no "Aktifkan Premium" control AND tapping "Tutup" dismisses it AND `onActivatePremium` is never invoked AND the editor keeps the typed content
