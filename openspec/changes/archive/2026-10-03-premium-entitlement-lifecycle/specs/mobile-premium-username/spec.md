## MODIFIED Requirements

### Requirement: The screen resolves Premium status on entry; the Settings row pushes the route unconditionally

This requirement honours docs/03 §114 ("Free user taps: paywall opens") WITHOUT adding a self-profile read to `SettingsScreen`, which today holds no Premium signal (it injects only `SettingsViewModel(tokenStore)`). The Premium gate SHALL therefore be owned by the route-scoped screen, not the Settings row:

- The `SettingsScreen` "Ganti username" row SHALL push `UsernameCustomizationRoute` **unconditionally** (no `isPremium` branch in Settings).
- The `UsernameCustomizationViewModel` SHALL resolve the caller's Premium status on entry via a self-profile read (`ProfileFlow.loadProfile(selfUserId)`, the existing stateless seam, with `selfUserId` from the existing `SelfUserIdProvider`). It renders the `PremiumGate` as the INITIAL state when the self read reports not-Premium, and the editor otherwise.

The resolved status SHALL also honour a confirmed client purchase, the `purchaseConfirmed` signal from the `mobile-premium-entitlement` capability. The effective status is the self-profile `isPremium` OR `purchaseConfirmed`. When `purchaseConfirmed` becomes `true` while the route-scoped ViewModel is alive (the buyer returns from the paywall pushed atop the username screen), the screen SHALL leave the `PremiumGate` for the editor without re-entry. It SHALL also clear any `PremiumGate` probe/change outcome and the last-probed-candidate memo, so the current candidate is re-probed rather than stuck behind a stale gate.

Because the client `isPremium` signal is `true` only for `premium_active` (NOT `premium_billing_retry`), the reactive `403 premium_required` from a probe/submit SHALL remain the **authoritative** gate. It is the backstop for billing-retry, staleness, and the post-purchase webhook-lag window. A self-read mis-classifying a billing-retry user as Free shows the paywall, which is harmless because re-subscribing is the intended action. A self-profile read failure SHALL degrade to letting the user attempt the action (the reactive `403` then governs), never an error wall.

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

#### Scenario: A purchase confirmed while the gate is shown opens the editor
- **GIVEN** the screen in `PremiumGate` because the self read reported `isPremium = false` AND a `purchaseConfirmed` flow that is `false`
- **WHEN** `purchaseConfirmed` becomes `true`
- **THEN** the state leaves `PremiumGate` for the editor without re-entering the route

#### Scenario: A confirmed purchase clears a stale probe gate and re-probes
- **GIVEN** a probe for candidate `"newname"` returned `CheckPremiumGate` (the screen shows `PremiumGate`)
- **WHEN** `purchaseConfirmed` becomes `true` and the user re-enters `"newname"`
- **THEN** the `PremiumGate` outcome is cleared AND a fresh probe for `"newname"` is issued after the debounce
