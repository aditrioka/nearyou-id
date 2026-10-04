## MODIFIED Requirements

### Requirement: Screen state mapping covers loading, content, empty, error, and both rate-limit states

The screen SHALL render one of six visual states, all copy via `stringResource`, following the canonical loading/refresh pattern (`mobile-design-system` § "Canonical list loading and refresh pattern" — never two simultaneous progress indicators):
- **Loading** (initial load, no content yet) → a skeleton placeholder list AND a node with `stringResource(Res.string.timeline_loading)`, with at most one in-content indicator; the pull-to-refresh spinner is NOT shown during the initial load.
- **Content** (`Loaded` with non-empty posts) → the post-card list. During a **refresh** of already-loaded content the screen SHALL continue rendering the `Content` state (the post list stays mounted) with the pull-to-refresh spinner shown over it — it MUST NOT revert to the `Loading` skeleton.
- **Empty** (`Loaded`, empty posts, no `upsell`) → a node with `stringResource(Res.string.timeline_empty_nearby)` AND a "lihat Global" CTA labelled `stringResource(Res.string.cta_see_global)` that invokes a hoisted `onSeeGlobal` callback (wired by the tab host to select the Global tab — `NearbyTimelineScreen` remains navigation-free).
- **Error** (`NetworkError` or retryable `Error`) → a node with `stringResource(Res.string.signin_error_network)` AND a retry control labelled `stringResource(Res.string.cta_retry)`.
- **Rate-limit hard** (`Loaded`, empty posts, `upsell.hard = true`) → the shared `HardLimitState` list state: a node with `stringResource(Res.string.timeline_limit_hard)` (distinct from the empty-area copy) AND an "Aktifkan Premium" button (`cta_activate_premium`) that invokes the hoisted `onActivatePremium(PaywallEntry.TIMELINE_CAP)`.
- **Rate-limit soft** (`Loaded`, non-empty posts, `upsell.soft = true`) → the post list AND, as its first item, the shared non-blocking `SoftLimitBanner`: `stringResource(Res.string.timeline_limit_soft)` AND an "Aktifkan Premium" text button (`cta_activate_premium`) that invokes the hoisted `onActivatePremium(PaywallEntry.TIMELINE_CAP)`.

Both read limits are Free-only: `timeline-read-rate-limit` skips both buckets for `premium_active` and `premium_billing_retry`. So the read-cap Premium path never reaches a server-Premium caller, and the hard and soft states both carry it. The host (`appEntryProvider`'s `HomeRoute` entry, through `AppShellScreen` → `HomeScreen`) already maps `onActivatePremium(entry)` to a `PaywallRoute(entry)` push, so `TIMELINE_CAP` needs no new host wiring. During the post-purchase webhook-lag window (`purchaseConfirmed = true`, `mobile-premium-entitlement` § "Upsell surfaces show the activating notice during the webhook-lag window"), both read-cap surfaces SHALL render `premium_activating_body` in place of their limit copy and show no "Aktifkan Premium" control. The hard state stays scrollable, so pull-to-refresh still re-fetches.

#### Scenario: Initial loading shows the skeleton and the loading copy, no pull-to-refresh spinner

- **WHEN** the screen is in the initial-load state (no content yet)
- **THEN** the rendered tree contains a node whose text matches `stringResource(Res.string.timeline_loading)` AND a single in-content indicator AND the `PullToRefreshBox` `isRefreshing` argument is `false`

#### Scenario: Refresh of loaded content keeps the list and shows only the pull-to-refresh spinner

- **GIVEN** the screen in the `Content` state with loaded posts
- **WHEN** a refresh is in flight (reload triggered while content exists)
- **THEN** the post-card list remains rendered (the state stays `Content`, the skeleton is NOT shown) AND the `PullToRefreshBox` `isRefreshing` argument is `true` AND no separate in-content `CircularProgressIndicator` is rendered

#### Scenario: Empty area shows the sparse-area copy plus a lihat-Global CTA

- **WHEN** the outcome is `Loaded` with empty posts and no `upsell`
- **THEN** the rendered tree contains a node whose text matches `stringResource(Res.string.timeline_empty_nearby)` AND a clickable node whose text matches `stringResource(Res.string.cta_see_global)` AND does NOT contain `stringResource(Res.string.timeline_limit_hard)`

#### Scenario: Error shows network copy and a retry control

- **WHEN** the outcome is `NetworkError`
- **THEN** the rendered tree contains a node whose text matches `stringResource(Res.string.signin_error_network)` AND a clickable node whose text matches `stringResource(Res.string.cta_retry)`

#### Scenario: Rate-limit hard shows the limit copy with no posts

- **WHEN** the outcome is `Loaded` with empty posts and `upsell.hard = true`
- **THEN** the rendered tree contains a node whose text matches `stringResource(Res.string.timeline_limit_hard)` AND renders zero post cards AND does NOT contain `stringResource(Res.string.timeline_empty_nearby)`

#### Scenario: Rate-limit soft shows posts plus a non-blocking banner

- **WHEN** the outcome is `Loaded` with 5 posts and `upsell.soft = true`
- **THEN** the rendered tree renders the 5 post cards AND contains a banner node whose text matches `stringResource(Res.string.timeline_limit_soft)`

#### Scenario: Empty-state CTA switches to the Global tab

- **GIVEN** the tab host is composed with the Nearby tab selected and the Nearby feed in the empty state
- **WHEN** the `cta_see_global` control is activated
- **THEN** the hoisted `onSeeGlobal` callback fires AND the tab host selects the Global tab (the body renders the Global feed surface — asserted via the Global feed list test tag / Global-only content, NOT the removed `timeline_global_title` header)

#### Scenario: The hard read-cap state offers the Premium path as TIMELINE_CAP

- **GIVEN** the Nearby screen composed with a recording `onActivatePremium` over an outcome `Loaded` with empty posts and `upsell.hard = true`
- **WHEN** the "Aktifkan Premium" control of the hard-limit state is activated
- **THEN** the rendered tree contains `stringResource(Res.string.timeline_limit_hard)` AND `onActivatePremium` fires exactly once with `PaywallEntry.TIMELINE_CAP`

#### Scenario: The soft read-cap banner offers the Premium path as TIMELINE_CAP

- **GIVEN** the Nearby screen composed with a recording `onActivatePremium` over an outcome `Loaded` with posts and `upsell.soft = true`
- **WHEN** the banner's "Aktifkan Premium" control is activated
- **THEN** the post cards AND the `timeline_limit_soft` banner are rendered AND `onActivatePremium` fires exactly once with `PaywallEntry.TIMELINE_CAP`

#### Scenario: A confirmed purchase shows the activating notice on both read-cap states

- **GIVEN** the Nearby screen whose Koin graph binds a `PremiumEntitlementSession` on which `onPurchaseConfirmed()` has run, over a hard-limit outcome and then a soft-limit outcome
- **WHEN** each state renders
- **THEN** each shows `premium_activating_body` in place of its limit copy AND neither renders an "Aktifkan Premium" control
