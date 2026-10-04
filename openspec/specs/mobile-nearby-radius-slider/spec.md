# mobile-nearby-radius-slider Specification

## Purpose
The mobile Nearby radius selector — the Premium-gated 4-position filter (10 / 20 / 50 / 100 km) that replaces the previously-fixed 20 km radius on the `:mobile:app` Nearby surface (a Month-1 Premium differentiator per `docs/01-Business.md` § Freemium Tiers + `docs/02-Product.md` § Nearby Timeline). Free viewers are anchored at 20 km — any other position snaps the slider back and surfaces a Premium upsell — while Premium viewers pick any of the four, the selection threading through the Nearby fetch and load-more. The Free/Premium split is gated client-side via an on-entry self-`isPremium` read plus a reactive `radius_premium_only` 403 backstop owned by the ViewModel (no new `NearbyTimelineOutcome` member); the authoritative server enforcement lives in the `nearby-timeline` capability. Radius selection is in-session only (resets to the 20 km default on a cold start).
## Requirements
### Requirement: Four-position Nearby radius slider

The Nearby surface SHALL present a discrete 4-position radius control with positions **10 / 20 / 50 / 100 km**, defaulting to **20 km** on entry. The selected position SHALL be held in `NearbyTimelineViewModel` state and SHALL drive the `radius_m` query parameter of the Nearby fetch. The control SHALL be a Material 3 `Slider` (or `Slider`-family) substrate already on the classpath — no new `libs.versions.toml` entry.

#### Scenario: Default position on entry
- **WHEN** the Nearby surface is first composed
- **THEN** the radius control shows the 20 km position AND the initial Nearby fetch uses `radius_m=20000`

#### Scenario: Positions are exactly the four product values
- **WHEN** inspecting the radius-control position model
- **THEN** the selectable positions are exactly `{10000, 20000, 50000, 100000}` metres (10/20/50/100 km), with no intermediate/continuous values

### Requirement: Free tier is anchored to 20 km with a Premium upsell

For a Free viewer, the radius control SHALL remain anchored at 20 km: any attempt to select a non-20 km position SHALL snap the control back to 20 km AND surface the Premium upsell. The upsell is the shipped `RadiusPremiumUpsellDialog`, a countdown-less Free-upsell dialog: the radius gate is not time-bounded, so it is deliberately distinct from the daily-cap `DailyCapUpsellDialog`.

Its CTAs:
- "Aktifkan Premium" SHALL dismiss it AND invoke the Nearby surface's hoisted `onActivatePremium(PaywallEntry.RADIUS_GATE)`. `appEntryProvider` wires that to push `PaywallRoute(RADIUS_GATE)` onto the root back stack, so the paywall headline leads with the radius benefit.
- "Tutup" SHALL only dismiss.

The radius upsell MUST NOT open the paywall as `LIKE_CAP`. That entry belongs to the like-cap dialog hosted on the same Nearby surface, which keeps pushing `LIKE_CAP`.

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

### Requirement: Premium tier selects freely

For a Premium viewer (`isPremiumKnown = true` via the self-`isPremium` read; `isPremium` ⇔ `subscription_status == premium_active` per `user-profile-read`), selecting any of the four positions SHALL drive the Nearby fetch at that radius with no snap-back and no upsell.

#### Scenario: Premium selection drives the fetch
- **GIVEN** a Premium viewer on the Nearby surface
- **WHEN** the viewer selects the 100 km position
- **THEN** a fresh first-page Nearby fetch is issued with `radius_m=100000` AND no upsell is shown AND the control stays at 100 km

### Requirement: On-entry tier resolution and reactive 403 backstop

The radius gate SHALL follow the on-entry self-`isPremium` read idiom: an `isPremiumKnown: Boolean?` that is `null` (Resolving) until the self-profile read resolves it. `UsernameCustomizationViewModel` is the on-entry `isPremiumKnown` precedent; `SearchViewModel` supplies the reactive-403 backstop half. Until tier is known, the control SHALL behave as Free (anchored at 20 km).

The resolved tier SHALL also honour a confirmed client purchase, the `purchaseConfirmed` signal from the `mobile-premium-entitlement` capability. The effective tier is the self-profile `isPremium` OR `purchaseConfirmed`, so a self-profile read that lags the webhook cannot overwrite a confirmed purchase. When `purchaseConfirmed` becomes `true` while the HomeRoute-scoped `NearbyTimelineViewModel` is alive (the buyer returns from the paywall pushed atop Home), `isPremiumKnown` SHALL become `true` without a cold start or re-entry, so the Premium radii are selectable immediately.

As a server-authoritative backstop, a Nearby fetch that returns HTTP 403 `radius_premium_only` SHALL be mapped to the SAME Premium upsell surface as the client-side snap-back (never surfaced as a raw error), and the control SHALL revert to 20 km. This includes the webhook-lag window after a confirmed purchase. This 403 handling SHALL be owned by the **ViewModel layer**. The repository surfaces the parsed `error.code` as `NearbyFetchResult.PremiumGated`, outside `NearbyTimelineOutcome`, and the ViewModel owns the interpretation (revert to 20 km + upsell). It SHALL NOT introduce a new `NearbyTimelineOutcome` member: the `mobile-nearby-timeline` status→outcome mapping (401 → `SessionExpired`, other non-2xx → the retryable `Error`/`NetworkError` fallback) is unchanged, and the `radius_premium_only` upsell is a ViewModel-level interpretation layered above it.

The backstop SHALL cover **every** Nearby fetch path through one gate mapping: the initial load, pull-to-refresh, the error-state retry, a radius selection, and load-more.

- **Repository.** The repository's shared status mapping SHALL return `NearbyFetchResult.PremiumGated` for this 403 from both `loadFirstPage` and `loadMore`. Every other result SHALL be `NearbyFetchResult.Loaded(<the unchanged outcome>)`. There SHALL be no separate radius-change fetch method: a radius selection is a page-1 load at the newly selected radius.
- **ViewModel.** Every page-1 fetch SHALL go through one fetch function. Every `PremiumGated` result at a non-20 km radius SHALL go through one handler that reverts the control to 20 km and raises the upsell.
- **Gated page-1 fetch.** After the handler runs, page 1 SHALL be re-fetched at 20 km once.
- **Gated fetch at 20 km.** Whether it is the original fetch or the re-fetch, the Free anchor is never gated, so this is a server fault. It SHALL map to the retryable error, with no upsell and no further fetch.
- **Gated load-more.** A load-more gated at a non-20 km radius SHALL drop the stale page and SHALL reload page 1 at 20 km. No load-more retry footer is shown, because the page can never load at that radius; this is the one exception to `mobile-design-system` § "Canonical list load-more (infinite-scroll) pattern", whose error footer covers retryable failures. A gated load-more at 20 km is the server fault above and SHALL be the ordinary load-more retry footer. A *stale* gated load-more SHALL apply nothing: one that lands after a refresh has already reverted the radius raises no second upsell and spends no extra read.

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

#### Scenario: A stale gated load-more applies nothing twice

- **GIVEN** a 50 km load-more in flight when a pull-to-refresh at 50 km is gated (→ 20 km + upsell, then dismissed)
- **WHEN** the stale load-more's `403 radius_premium_only` lands
- **THEN** no second upsell is raised AND no extra page-1 fetch is issued AND no load-more retry footer is shown

#### Scenario: A gated fetch at 20 km is the retryable error, without a loop or an extra upsell

- **GIVEN** a server that answers `403 radius_premium_only` even at 20 km
- **WHEN** a 50 km selection is gated, and separately when a 20 km pull-to-refresh is gated
- **THEN** the 50 km case issues exactly one 20 km re-fetch and ends in the retryable `NetworkError` AND the 20 km refresh issues no re-fetch, raises no upsell, and ends in `NetworkError` AND a gated 20 km load-more shows the ordinary retry footer with no upsell and no page-1 reload

### Requirement: Selected radius threads through fetch and load-more; a new selection reloads the first page

The selected radius SHALL thread through the existing `NearbyTimelineRepository` / `NearbyTimelineFlow` / `NearbyTimelineApiClient` seam (generalizing the former `NEARBY_RADIUS_M` constant into the selected value). Load-more pages SHALL reuse the in-flight selected radius alongside the first-page coordinate anchor (per `mobile-nearby-timeline` anchor-reuse), keeping the radius stable across pages. Selecting a NEW radius position SHALL trigger a fresh first-page load (new cursor lineage), NOT a load-more append.

#### Scenario: Load-more reuses the selected radius
- **GIVEN** a Premium viewer who loaded the Nearby first page at `radius_m=50000` with a non-null `nextCursor`
- **WHEN** a load-more page is requested
- **THEN** the follow-up fetch carries `radius_m=50000` (the selected radius reused, matching the page-1 anchor)

#### Scenario: Changing radius reloads from the first page
- **GIVEN** a Premium viewer currently viewing results at `radius_m=20000`
- **WHEN** the viewer selects 100 km
- **THEN** a fresh first-page fetch is issued at `radius_m=100000` with NO `cursor` (not a load-more append)

### Requirement: Radius selection is in-session only (cross-launch persistence deferred)

The selected radius SHALL be held in in-memory ViewModel state for the session only and SHALL reset to the 20 km default on a cold start. This change SHALL NOT introduce a local key-value preference seam; cross-launch persistence is an explicit deferral (a future `mobile-client-preferences` follow-up MODIFIES this requirement to persist the choice).

#### Scenario: Radius resets to default on cold start
- **GIVEN** a Premium viewer who selected 100 km in a prior session
- **WHEN** the app is cold-started and the Nearby surface is entered
- **THEN** the radius control shows the 20 km default (the prior selection is not restored)

### Requirement: Radius-control and upsell strings are resource-backed

All user-facing strings introduced by this change (position labels if any, the upsell rationale/CTA copy) SHALL be sourced via `Res.string.*` from `:shared:resources`, with zero hardcoded UI string literals in the mobile sources.

#### Scenario: No hardcoded UI strings
- **WHEN** inspecting the radius-control and upsell composables added by this change
- **THEN** every user-facing string is referenced via `stringResource(Res.string.<name>)` AND no literal UI string appears

### Requirement: Test coverage for the gate/selection projection and the rendered control

The change SHALL ship: (1) a `commonTest` test for the pure, Compose-free gate/selection decision logic — covering Free snap-back-to-20 km, Premium free-selection, the Resolving (tier-unknown) Free behavior, and the `radius_premium_only`-403 → upsell mapping — with no Compose/platform/wall-clock dependency; (2) a Robolectric `*ScreenTest` asserting the rendered control + the Free upsell surface, added to the `mobile/app/build.gradle.kts` Release-variant test-exclude block alongside the existing `*ScreenTest` exclusions.

#### Scenario: Projection test is discoverable and covers each path
- **WHEN** running `./gradlew :mobile:app:testDevDebugUnitTest`
- **THEN** the gate/selection projection test is discovered AND each documented path (Free snap-back, Premium select, Resolving-as-Free, 403→upsell) corresponds to at least one `@Test`

#### Scenario: New screen test excluded from the Release variant
- **WHEN** inspecting `mobile/app/build.gradle.kts`
- **THEN** any new `*ScreenTest` added by this change is listed in the `tasks.withType<Test>()` Release-variant exclude block

