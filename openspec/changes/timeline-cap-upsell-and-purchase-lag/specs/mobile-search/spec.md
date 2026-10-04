## MODIFIED Requirements

### Requirement: The Premium gate renders the Free-tier upsell panel reactively on 403

While the search outcome is `PremiumGate` (the reactive `403 premium_required` gate), `SearchScreen` SHALL render a Free-tier upsell panel:

- an explanatory body via `stringResource` (e.g. `search_premium_gate_body`) describing that search is a Premium feature
- a primary CTA via `stringResource` (e.g. `search_premium_gate_cta`, "Aktifkan Premium")

The CTA SHALL invoke a hoisted `onActivatePremium` callback that the host (the `appEntryProvider` call site) wires to push `PaywallRoute(entry = PaywallEntry.SEARCH_GATE)` onto the root back stack (the `mobile-paywall` capability — mockup frame 17, `docs/03-UX-Design.md` § Paywall & Premium Disclosure). `SearchScreen` SHALL remain navigation-free: it holds no back-stack reference, and navigation is delivered only via the hoisted callback.

During the post-purchase webhook-lag window (`mobile-premium-entitlement` § "Upsell surfaces show the activating notice during the webhook-lag window") the gate panel SHALL render `premium_activating_body` in place of the upsell body and SHALL show no "Aktifkan Premium" CTA. That is the state the once-only re-run below lands in when it returns `PremiumGate` again. The viewer retries by submitting the query again. `SearchScreen` reads the signal through the same fail-safe resolver it already hands to `SearchViewModel`.

The gate panel SHALL NOT issue any further search request while shown, with one exception: when the `purchaseConfirmed` signal (the `mobile-premium-entitlement` capability) becomes `true` while the outcome is `PremiumGate`, the route-scoped `SearchViewModel` SHALL re-run the current query exactly once, as if retried. The server `403` remains authoritative; during the post-purchase webhook-lag window the re-run MAY return `PremiumGate` again.

This resolves the v1 informational-placeholder state: the CTA is no longer a no-op. GitHub issue [#254](https://github.com/aditrioka/nearyou-id/issues/254) is addressed by the change introducing this behavior. The `429` rate-limit state is unaffected: it is a Premium-tier limit, so a user who reaches it is already Premium and is shown a countdown/retry, never a paywall CTA.

#### Scenario: 403 renders the upsell panel with the Premium CTA

- **GIVEN** a `FakeSearchFlow` returning `SearchOutcome.PremiumGate` for a valid query
- **WHEN** `SearchScreen` renders
- **THEN** the rendered tree contains the upsell body (`search_premium_gate_body`) AND a CTA labelled `stringResource(Res.string.search_premium_gate_cta)`

#### Scenario: The upsell CTA pushes PaywallRoute with the search-gate entry-context

- **GIVEN** the upsell panel composed over a test root back stack (or the `appEntryProvider` call site over a test root back stack)
- **WHEN** the "Aktifkan Premium" CTA is activated
- **THEN** a `PaywallRoute(entry = PaywallEntry.SEARCH_GATE)` is appended to the root back stack AND `SearchScreen` holds no back-stack reference (navigation is delivered via the hoisted `onActivatePremium` callback)

#### Scenario: A confirmed purchase re-runs the gated query once

- **GIVEN** `SearchViewModel` whose outcome for query `"kopi"` is `PremiumGate` AND a `purchaseConfirmed` flow that is `false`
- **WHEN** `purchaseConfirmed` becomes `true` AND the flow now returns `Results` for `"kopi"`
- **THEN** exactly one additional search for `"kopi"` is issued AND the outcome becomes `Results`

#### Scenario: A confirmed purchase does not re-query outside the gate

- **GIVEN** `SearchViewModel` whose outcome is `Results` (not `PremiumGate`)
- **WHEN** `purchaseConfirmed` becomes `true`
- **THEN** no additional search request is issued

#### Scenario: The gate shows the activating notice after a confirmed purchase

- **GIVEN** `SearchScreen` whose Koin graph binds a `PremiumEntitlementSession` on which `onPurchaseConfirmed()` has run AND a `FakeSearchFlow` returning `SearchOutcome.PremiumGate`
- **WHEN** the screen renders the gate for a valid query
- **THEN** the rendered tree contains `premium_activating_body` AND does NOT contain `search_premium_gate_body` AND contains no "Aktifkan Premium" control
