## ADDED Requirements

### Requirement: Four-position Nearby radius slider

The Nearby surface SHALL present a discrete 4-position radius control with positions **10 / 20 / 50 / 100 km**, defaulting to **20 km** on entry. The selected position SHALL be held in `NearbyTimelineViewModel` state and SHALL drive the `radius_m` query parameter of the Nearby fetch. The control SHALL be a Material 3 `Slider` (or `Slider`-family) substrate already on the classpath — no new `libs.versions.toml` entry.

#### Scenario: Default position on entry
- **WHEN** the Nearby surface is first composed
- **THEN** the radius control shows the 20 km position AND the initial Nearby fetch uses `radius_m=20000`

#### Scenario: Positions are exactly the four product values
- **WHEN** inspecting the radius-control position model
- **THEN** the selectable positions are exactly `{10000, 20000, 50000, 100000}` metres (10/20/50/100 km), with no intermediate/continuous values

### Requirement: Free tier is anchored to 20 km with a Premium upsell

For a Free viewer, the radius control SHALL remain anchored at 20 km: any attempt to select a non-20 km position SHALL snap the control back to 20 km AND surface the Premium upsell. The upsell SHALL reuse an established Free-upsell surface — either the inline `DailyCapUpsellDialog` (the daily/like-cap dialog idiom) or the `PaywallRoute` panel that `mobile-search` pushes for its Free gate — with the final surface resolved against the mockup at implementation (NOT, as an earlier draft mis-stated, `DailyCapUpsellDialog` "mirroring mobile-search" — those are two distinct surfaces). A Free viewer's effective `radius_m` SHALL therefore always be `20000` — a non-20 km value SHALL NOT be issued to the backend from a Free session. A viewer whose tier reads as **not Premium** (`is_premium = false`, i.e. `subscription_status != premium_active` per `user-profile-read` — this includes a grace-period `premium_billing_retry` user, by design Decision 6) SHALL be treated as Free here.

#### Scenario: Free drag snaps back and upsells
- **GIVEN** a Free viewer (`isPremiumKnown = false`) on the Nearby surface
- **WHEN** the viewer drags the radius control to 50 km
- **THEN** the control returns to the 20 km position AND the Premium upsell is shown AND no Nearby fetch with `radius_m=50000` is issued

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

The radius gate SHALL follow the on-entry self-`isPremium` read idiom — an `isPremiumKnown: Boolean?` that is `null` (Resolving) until the self-profile read resolves it (mirroring `UsernameCustomizationViewModel`, which is the on-entry `isPremiumKnown` precedent; `SearchViewModel` supplies the reactive-403 backstop half). Until tier is known, the control SHALL behave as Free (anchored at 20 km). As a server-authoritative backstop, a Nearby fetch that returns HTTP 403 `radius_premium_only` SHALL be mapped to the SAME Premium upsell surface as the client-side snap-back (never surfaced as a raw error), and the control SHALL revert to 20 km. This 403 handling SHALL be owned by the **ViewModel layer** reading the parsed `error.code` from the fetch result; it SHALL NOT introduce a new `NearbyTimelineOutcome` member — the `mobile-nearby-timeline` status→outcome mapping (401 → `SessionExpired`, other non-2xx → the retryable `Error`/`NetworkError` fallback) is unchanged, and the `radius_premium_only` upsell is a ViewModel-level interpretation layered above it.

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
