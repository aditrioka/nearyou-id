## RENAMED Requirements

- FROM: `### Requirement: Autocomplete, proactive upsell, and paywall navigation are explicitly deferred`
- TO: `### Requirement: Autocomplete and proactive upsell are explicitly deferred`

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

### Requirement: Autocomplete and proactive upsell are explicitly deferred

This change SHALL NOT implement: (a) username autocomplete / typeahead (`docs/03-UX-Design.md:241` — requires a NEW backend autocomplete endpoint that is not shipped); (b) a proactive "upsell on tap before typing" surface (`docs/03-UX-Design.md:240` — requires a global, app-wide client-held subscription/entitlement signal plus a loading/fallback design for the pre-load window; the reactive-on-`403` `PremiumGate` is the v1 surface). The entitlement seam that the proactive upsell needs (the `PurchaseController` / RevenueCat `CustomerInfo` entitlement) is delivered by the `mobile-paywall` capability in this change, but the proactive behavior itself is NOT built here. **Paywall navigation is NO LONGER deferred** — the Premium-gate CTA now routes to `PaywallRoute` per the § "The Premium gate renders the Free-tier upsell panel reactively on 403" requirement, so it is removed from this deferral list. Each remaining deferral SHALL be recorded as a `tasks.md` note AND a `follow-up` GitHub issue (NOT silently dropped); the proactive-upsell follow-up is GitHub issue [#253](https://github.com/aditrioka/nearyou-id/issues/253).

#### Scenario: The remaining deferrals are tracked, not silent

- **WHEN** inspecting `tasks.md` and the change's follow-up issues
- **THEN** the autocomplete and proactive-upsell deferrals are each recorded with a `follow-up` GitHub issue reference (proactive upsell = [#253](https://github.com/aditrioka/nearyou-id/issues/253)) AND paywall navigation is NOT listed as a deferral (it is implemented) AND the v1 search surface functions (reactive gate, submit/debounce search, an upsell CTA that now routes to the paywall)

#### Scenario: Search opens to Idle for all viewers (the proactive gate is not built)

- **GIVEN** a Free viewer opening the Cari surface with no query yet
- **WHEN** `SearchScreen` first renders
- **THEN** it shows the Idle prompt (the § "Screen state mapping" Idle state) AND does NOT short-circuit to the `PremiumGate` upsell before a query is issued — the gate appears only reactively on a `403` response
