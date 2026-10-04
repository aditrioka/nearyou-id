## MODIFIED Requirements

### Requirement: The Premium gate renders the upsell and routes to the paywall via a hoisted callback

While the change (or probe) outcome is `PremiumGate` (the authoritative `403 premium_required` gate — the backstop that also covers the `premium_billing_retry` edge and any stale client `isPremium` hint), `UsernameCustomizationScreen` SHALL render a Free-tier upsell panel: an explanatory body via `stringResource(Res.string.username_premium_gate_body)` (docs/03 §114: "Ganti username adalah fitur Premium") and a primary CTA via `stringResource(Res.string.cta_activate_premium)` ("Aktifkan Premium", the shared key; there is no username-specific CTA key) that invokes the hoisted `onActivatePremium` lambda. The screen SHALL hold no back-stack reference; the `appEntryProvider` call site SHALL wire `onActivatePremium` to push `PaywallRoute` (introduced by `mobile-paywall-screen` #309). The gate panel SHALL issue no further change/probe request while shown.

During the post-purchase webhook-lag window (`mobile-premium-entitlement` § "Upsell surfaces show the activating notice during the webhook-lag window") the gate panel SHALL render `premium_activating_body` in place of `username_premium_gate_body`, and its button SHALL become "Coba lagi" (`cta_retry`). The button clears the gate back to the editor with the candidate kept, through the ViewModel's existing candidate-change path, so the viewer can submit again. A confirmed purchase already clears a stale gate once (§ "The screen resolves Premium status on entry"). The panel shows again only when a later probe or submit still meets the lagging `403`, and without the retry the full-screen gate would leave no way back to the editor.

#### Scenario: 403 renders the upsell and the CTA invokes the hoisted callback
- **GIVEN** `UsernameCustomizationScreen` over a `FakeUsernameFlow` returning `PremiumGate` and a recording `onActivatePremium`
- **WHEN** the screen renders and the "Aktifkan Premium" CTA is activated
- **THEN** the tree contains the upsell body (`username_premium_gate_body`) AND the CTA (`cta_activate_premium`) AND activating it fires the hoisted `onActivatePremium` (the screen appends no route itself — navigation is owned by the call site)

#### Scenario: The gate reached via the probe path also renders the upsell
- **GIVEN** `UsernameCustomizationScreen` over a `FakeUsernameFlow` whose probe returns `CheckPremiumGate`
- **WHEN** the screen renders
- **THEN** the same upsell panel (`username_premium_gate_body` + `cta_activate_premium`) is shown (the gate state is reached identically from the change `PremiumGate` and the probe `CheckPremiumGate`)

#### Scenario: The gate shows the activating notice and returns to the editor after a confirmed purchase
- **GIVEN** `UsernameCustomizationScreen` whose Koin graph binds a `PremiumEntitlementSession` on which `onPurchaseConfirmed()` has run AND a `FakeUsernameFlow` returning `PremiumGate`
- **WHEN** a submitted change meets the `403` and the gate's "Coba lagi" control is activated
- **THEN** the gate showed `premium_activating_body` AND neither `username_premium_gate_body` nor an "Aktifkan Premium" control AND the retry returns to the editor holding the submitted candidate AND the hoisted `onActivatePremium` is never invoked
