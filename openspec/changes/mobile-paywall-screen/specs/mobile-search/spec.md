## MODIFIED Requirements

### Requirement: The Premium gate renders the Free-tier upsell panel reactively on 403

While the search outcome is `PremiumGate` (the reactive `403 premium_required` gate), `SearchScreen` SHALL render a Free-tier upsell panel: an explanatory body via `stringResource` (e.g. `search_premium_gate_body`) describing that search is a Premium feature, and a primary CTA via `stringResource` (e.g. `search_premium_gate_cta`, "Aktifkan Premium"). The CTA SHALL invoke a hoisted `onActivatePremium` callback that the host (the `appEntryProvider` call site) wires to push `PaywallRoute(entry = PaywallEntry.SEARCH_GATE)` onto the root back stack (the `mobile-paywall` capability — mockup frame 17, `docs/03-UX-Design.md` § Paywall & Premium Disclosure). `SearchScreen` SHALL remain navigation-free — it holds no back-stack reference; navigation is delivered only via the hoisted callback. The gate panel SHALL NOT issue any further search request while shown. (This resolves the v1 informational-placeholder state: the CTA is no longer a no-op. GitHub issue [#254](https://github.com/aditrioka/nearyou-id/issues/254) is addressed by the change introducing this behavior. The `429` rate-limit state is unaffected — it is a Premium-tier limit, so a user who reaches it is already Premium and is shown a countdown/retry, never a paywall CTA.)

#### Scenario: 403 renders the upsell panel with the Premium CTA

- **GIVEN** a `FakeSearchFlow` returning `SearchOutcome.PremiumGate` for a valid query
- **WHEN** `SearchScreen` renders
- **THEN** the rendered tree contains the upsell body (`search_premium_gate_body`) AND a CTA labelled `stringResource(Res.string.search_premium_gate_cta)`

#### Scenario: The upsell CTA pushes PaywallRoute with the search-gate entry-context

- **GIVEN** the upsell panel composed over a test root back stack (or the `appEntryProvider` call site over a test root back stack)
- **WHEN** the "Aktifkan Premium" CTA is activated
- **THEN** a `PaywallRoute(entry = PaywallEntry.SEARCH_GATE)` is appended to the root back stack AND `SearchScreen` holds no back-stack reference (navigation is delivered via the hoisted `onActivatePremium` callback)

## ADDED Requirements

### Requirement: Proactive Free-tier upsell on search-open is deferred

The mobile Cari surface SHALL gate Free users REACTIVELY only — the upsell panel renders on the backend's `403 premium_required` (per the § "The Premium gate renders the Free-tier upsell panel reactively on 403" requirement). A PROACTIVE upsell — short-circuiting a Free viewer to the upsell panel the moment search opens, before the first query (`docs/03-UX-Design.md:240` frames the gate as "Free users see an upsell on tap") — is DEFERRED to a follow-up. The proactive behavior requires a global, app-wide client subscription/entitlement signal (a loaded RevenueCat `CustomerInfo`) plus a loading/fallback design for the pre-load window (fall back to the reactive gate while the entitlement is unknown), which is out of scope for this change. This change SHALL NOT short-circuit the search surface to the upsell proactively; the reactive `403` gate remains the only gating path. The entitlement seam the proactive behavior needs (`PurchaseController` / `CustomerInfo` entitlement) is delivered by the `mobile-paywall` capability; GitHub issue [#253](https://github.com/aditrioka/nearyou-id/issues/253) tracks the proactive behavior.

#### Scenario: Search opens to Idle for all viewers (no proactive gate)

- **GIVEN** a Free viewer opening the Cari surface with no query yet
- **WHEN** `SearchScreen` first renders
- **THEN** it shows the Idle prompt (the § "Screen state mapping" Idle state) AND does NOT short-circuit to the `PremiumGate` upsell before a query is issued — the gate appears only reactively on a `403` response

#### Scenario: The proactive-upsell deferral is tracked

- **WHEN** inspecting the project's open GitHub issues
- **THEN** GitHub issue [#253](https://github.com/aditrioka/nearyou-id/issues/253) tracks the proactive Free-tier upsell-on-open as the follow-up building on the delivered entitlement seam
