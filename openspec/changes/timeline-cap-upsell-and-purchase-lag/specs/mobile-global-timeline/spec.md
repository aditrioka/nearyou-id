## MODIFIED Requirements

### Requirement: Screen state mapping covers loading, content, empty, error, and both rate-limit states

The screen SHALL render one of six visual states, all copy via `stringResource`, following the canonical loading/refresh pattern (`mobile-design-system` § "Canonical list loading and refresh pattern" — never two simultaneous progress indicators):
- **Loading** (initial load, no content yet) → a skeleton placeholder list AND a node with `stringResource(Res.string.timeline_loading)`, with at most one in-content indicator; the pull-to-refresh spinner is NOT shown during the initial load.
- **Content** (`Loaded` with non-empty posts) → the post-card list. During a **refresh** of already-loaded content the screen SHALL continue rendering the `Content` state (the post list stays mounted) with the pull-to-refresh spinner shown over it — it MUST NOT revert to the `Loading` skeleton.
- **Empty** (`Loaded`, empty posts, no `upsell`) → the loading-skeleton presentation reusing `stringResource(Res.string.timeline_loading)` ("*Sedang memuat postingan…*"). The existing `timeline_loading` key already holds the exact copy `docs/03-UX-Design.md` § Empty State prescribes for the Global-empty edge case (which it frames as a loading skeleton because Global is effectively never empty), so NO new `timeline_empty_global` key is added. The Empty `GlobalTimelineUiState` member remains distinct from Loading at the projection level even though both render the same skeleton + copy.
- **Error** (`NetworkError` or retryable `Error`) → a node with `stringResource(Res.string.signin_error_network)` AND a retry control labelled `stringResource(Res.string.cta_retry)`.
- **Rate-limit hard** (`Loaded`, empty posts, `upsell.hard = true`) → the shared `HardLimitState` list state: a node with `stringResource(Res.string.timeline_limit_hard)` (distinct from the empty copy) AND an "Aktifkan Premium" button (`cta_activate_premium`) that invokes the hoisted `onActivatePremium(PaywallEntry.TIMELINE_CAP)`.
- **Rate-limit soft** (`Loaded`, non-empty posts, `upsell.soft = true`) → the post list AND, as its first item, the shared non-blocking `SoftLimitBanner`: `stringResource(Res.string.timeline_limit_soft)` AND an "Aktifkan Premium" text button (`cta_activate_premium`) that invokes the hoisted `onActivatePremium(PaywallEntry.TIMELINE_CAP)`.

Both read limits are Free-only: `timeline-read-rate-limit` skips both buckets for `premium_active` and `premium_billing_retry`. So the read-cap Premium path never reaches a server-Premium caller, and the hard and soft states both carry it. The host (`appEntryProvider`'s `HomeRoute` entry, through `AppShellScreen` → `HomeScreen`) already maps `onActivatePremium(entry)` to a `PaywallRoute(entry)` push, so `TIMELINE_CAP` needs no new host wiring. During the post-purchase webhook-lag window (`purchaseConfirmed = true`, `mobile-premium-entitlement` § "Upsell surfaces show the activating notice during the webhook-lag window"), both read-cap surfaces SHALL render `premium_activating_body` in place of their limit copy and show no "Aktifkan Premium" control. The hard state stays scrollable, so pull-to-refresh still re-fetches.

The screen state SHALL be modeled as a Compose-free `GlobalTimelineUiState` data class (or sealed type) plus a pure projection (`globalTimelineUiState(outcome: GlobalTimelineOutcome?, isInitialLoad: Boolean): GlobalTimelineUiState`) — mirroring `mobile-nearby-timeline`'s `NearbyTimelineUiState` — so the outcome→state mapping is deterministically unit-testable in commonTest without composing the UI. The projection SHALL map `isInitialLoad = true` to `Loading`, and otherwise map the `outcome` to its state — so that during a refresh (`isInitialLoad = false`, a previous `Loaded` retained) it returns `Content`, NOT `Loading`. The pull-to-refresh `isRefreshing` value is carried separately (passed to `PullToRefreshBox`), NOT folded into this projection. The projection MUST carry no PII (no `author_user_id`, no coordinates).

#### Scenario: Projection maps each outcome to its state with the initial-vs-refresh distinction

- **WHEN** the projection is invoked for `isInitialLoad = true` (any outcome), for `Loaded(non-empty, no upsell)` with `isInitialLoad = false`, for `Loaded(empty, no upsell)`, for `Loaded(empty, upsell.hard)`, for `Loaded(non-empty, upsell.soft)`, and for `NetworkError`
- **THEN** the `isInitialLoad = true` call returns `Loading`; each non-initial call returns the corresponding content / empty / hard-limit / soft-limit / error state respectively, deterministically (no wall-clock or platform dependency)

#### Scenario: Refresh of loaded content keeps the list and shows only the pull-to-refresh spinner

- **GIVEN** the screen in the `Content` state with loaded posts
- **WHEN** a refresh is in flight (reload triggered while content exists)
- **THEN** the post-card list remains rendered (the state stays `Content`, the skeleton is NOT shown) AND the `PullToRefreshBox` `isRefreshing` argument is `true` AND no separate in-content `CircularProgressIndicator` is rendered

#### Scenario: Error shows network copy and a retry control

- **WHEN** the outcome is `NetworkError`
- **THEN** the rendered tree contains a node whose text matches `stringResource(Res.string.signin_error_network)` AND a clickable node whose text matches `stringResource(Res.string.cta_retry)`

#### Scenario: Empty Global renders the loading-skeleton copy

- **WHEN** the outcome is `Loaded` with empty posts and no `upsell` (`isInitialLoad = false`) — the `Empty` `GlobalTimelineUiState` member
- **THEN** the rendered tree renders the loading-skeleton presentation with a node whose text matches `stringResource(Res.string.timeline_loading)` (reusing the existing key per the Empty-state note) AND renders zero post cards AND no new `timeline_empty_global` key is referenced

#### Scenario: The hard read-cap state offers the Premium path as TIMELINE_CAP

- **GIVEN** the Global screen composed with a recording `onActivatePremium` over an outcome `Loaded` with empty posts and `upsell.hard = true`
- **WHEN** the "Aktifkan Premium" control of the hard-limit state is activated
- **THEN** the rendered tree contains `stringResource(Res.string.timeline_limit_hard)` AND `onActivatePremium` fires exactly once with `PaywallEntry.TIMELINE_CAP`

#### Scenario: The soft read-cap banner offers the Premium path as TIMELINE_CAP

- **GIVEN** the Global screen composed with a recording `onActivatePremium` over an outcome `Loaded` with posts and `upsell.soft = true`
- **WHEN** the banner's "Aktifkan Premium" control is activated
- **THEN** the post cards AND the `timeline_limit_soft` banner are rendered AND `onActivatePremium` fires exactly once with `PaywallEntry.TIMELINE_CAP`

#### Scenario: A confirmed purchase shows the activating notice on both read-cap states

- **GIVEN** the Global screen whose Koin graph binds a `PremiumEntitlementSession` on which `onPurchaseConfirmed()` has run, over a hard-limit outcome and then a soft-limit outcome
- **WHEN** each state renders
- **THEN** each shows `premium_activating_body` in place of its limit copy AND neither renders an "Aktifkan Premium" control

#### Scenario: The read-cap CTA opens the paywall as TIMELINE_CAP under the real host

- **GIVEN** the Home tab host composed under the real `appEntryProvider` over a test root back stack, with the Global feed in the hard-limit state
- **WHEN** the hard-limit state's "Aktifkan Premium" control is activated
- **THEN** the root back stack's top entry is `PaywallRoute(entry = PaywallEntry.TIMELINE_CAP)`
