## MODIFIED Requirements

### Requirement: Screen state mapping covers loading, content, empty, error, and both rate-limit states

The screen SHALL render one of six visual states, all copy via `stringResource`, following the canonical loading/refresh pattern (`mobile-design-system` § "Canonical list loading and refresh pattern" — never two simultaneous progress indicators):
- **Loading** (initial load, no content yet) → a skeleton placeholder list AND a node with `stringResource(Res.string.timeline_loading)`, with at most one in-content indicator; the pull-to-refresh spinner is NOT shown during the initial load.
- **Content** (`Loaded` with non-empty posts) → the post-card list. During a **refresh** of already-loaded content the screen SHALL continue rendering the `Content` state (the post list stays mounted) with the pull-to-refresh spinner shown over it — it MUST NOT revert to the `Loading` skeleton.
- **Empty** (`Loaded`, empty posts, no `upsell`) → the **directive** empty state: a node with `stringResource(Res.string.timeline_following_placeholder)` ("*Kamu belum mengikuti siapa pun. Lihat Nearby atau Global dulu.*") AND a control labelled `stringResource(Res.string.cta_see_global)` ("*Lihat Global*") that invokes the hoisted `onSeeGlobal` lambda (per § "The empty-state CTA switches the Home pager to the Global tab"). This is the deliberate divergence from Global-empty (which reuses the loading-skeleton copy): Following-empty is a real expected state that, per `docs/03-UX-Design.md` § Empty State, MUST direct the user to Nearby/Global. The empty state SHALL be rendered inside a scrollable so pull-to-refresh is recognized from it. NO new string key is added (both keys already exist in `:shared:resources`).
- **Error** (`NetworkError` or retryable `Error`) → a node with `stringResource(Res.string.signin_error_network)` AND a retry control labelled `stringResource(Res.string.cta_retry)`.
- **Rate-limit hard** (`Loaded`, empty posts, `upsell.hard = true`) → the shared `HardLimitState` list state: a node with `stringResource(Res.string.timeline_limit_hard)` (distinct from the empty copy) AND an "Aktifkan Premium" button (`cta_activate_premium`) that invokes the hoisted `onActivatePremium(PaywallEntry.TIMELINE_CAP)`.
- **Rate-limit soft** (`Loaded`, non-empty posts, `upsell.soft = true`) → the post list AND, as its first item, the shared non-blocking `SoftLimitBanner`: `stringResource(Res.string.timeline_limit_soft)` AND an "Aktifkan Premium" text button (`cta_activate_premium`) that invokes the hoisted `onActivatePremium(PaywallEntry.TIMELINE_CAP)`.

Both read limits are Free-only: `timeline-read-rate-limit` skips both buckets for `premium_active` and `premium_billing_retry`. So the read-cap Premium path never reaches a server-Premium caller, and the hard and soft states both carry it. The host (`appEntryProvider`'s `HomeRoute` entry, through `AppShellScreen` → `HomeScreen`) already maps `onActivatePremium(entry)` to a `PaywallRoute(entry)` push, so `TIMELINE_CAP` needs no new host wiring. During the post-purchase webhook-lag window (`purchaseConfirmed = true`, `mobile-premium-entitlement` § "Upsell surfaces show the activating notice during the webhook-lag window"):
- the soft banner SHALL NOT render at all. The soft cap blocks no reading, so a buyer needs neither the pitch nor a notice;
- the hard state SHALL render `premium_activating_body` in place of `timeline_limit_hard`, and its button SHALL become "Coba lagi" (`cta_retry`), which re-fetches page 1 through the feed's existing reload path instead of opening the paywall.

The screen state SHALL be modeled as a Compose-free `FollowingTimelineUiState` data class (or sealed type) plus a pure projection (`followingTimelineUiState(outcome: FollowingTimelineOutcome?, isInitialLoad: Boolean): FollowingTimelineUiState`) — mirroring `mobile-global-timeline`'s projection — so the outcome→state mapping is deterministically unit-testable in commonTest without composing the UI. The projection SHALL map `isInitialLoad = true` to `Loading`, and otherwise map the `outcome` to its state — so that during a refresh (`isInitialLoad = false`, a previous `Loaded` retained) it returns `Content`, NOT `Loading`. The pull-to-refresh `isRefreshing` value is carried separately (passed to `PullToRefreshBox`), NOT folded into this projection. The projection MUST carry no PII (no `author_user_id`, no coordinates).

#### Scenario: Projection maps each outcome to its state with the initial-vs-refresh distinction

- **WHEN** the projection is invoked for `isInitialLoad = true` (any outcome), for `Loaded(non-empty, no upsell)` with `isInitialLoad = false`, for `Loaded(empty, no upsell)`, for `Loaded(empty, upsell.hard)`, for `Loaded(non-empty, upsell.soft)`, and for `NetworkError`
- **THEN** the `isInitialLoad = true` call returns `Loading`; each non-initial call returns the corresponding content / empty / hard-limit / soft-limit / error state respectively, deterministically (no wall-clock or platform dependency)

#### Scenario: Empty Following renders the directive copy and the Lihat-Global CTA, not the loading skeleton

- **WHEN** the outcome is `Loaded` with empty posts and no `upsell` (`isInitialLoad = false`) — the `Empty` `FollowingTimelineUiState` member
- **THEN** the rendered tree contains a node whose text matches `stringResource(Res.string.timeline_following_placeholder)` AND a control whose text matches `stringResource(Res.string.cta_see_global)` AND renders zero post cards AND does NOT render the loading-skeleton `timeline_loading` copy

#### Scenario: Refresh of loaded content keeps the list and shows only the pull-to-refresh spinner

- **GIVEN** the screen in the `Content` state with loaded posts
- **WHEN** a refresh is in flight (reload triggered while content exists)
- **THEN** the post-card list remains rendered (the state stays `Content`, the skeleton is NOT shown) AND the `PullToRefreshBox` `isRefreshing` argument is `true` AND no separate in-content `CircularProgressIndicator` is rendered

#### Scenario: Error shows network copy and a retry control

- **WHEN** the outcome is `NetworkError`
- **THEN** the rendered tree contains a node whose text matches `stringResource(Res.string.signin_error_network)` AND a clickable node whose text matches `stringResource(Res.string.cta_retry)`

#### Scenario: Hard cap shows the limit copy distinct from the empty directive

- **WHEN** the outcome is `Loaded` with empty posts and `upsell.hard = true`
- **THEN** the rendered tree contains a node whose text matches `stringResource(Res.string.timeline_limit_hard)` AND does NOT render the `timeline_following_placeholder` directive copy

#### Scenario: The hard read-cap state offers the Premium path as TIMELINE_CAP

- **GIVEN** the Following screen composed with a recording `onActivatePremium` over an outcome `Loaded` with empty posts and `upsell.hard = true`
- **WHEN** the "Aktifkan Premium" control of the hard-limit state is activated
- **THEN** the rendered tree contains `stringResource(Res.string.timeline_limit_hard)` AND `onActivatePremium` fires exactly once with `PaywallEntry.TIMELINE_CAP`

#### Scenario: The soft read-cap banner offers the Premium path as TIMELINE_CAP

- **GIVEN** the Following screen composed with a recording `onActivatePremium` over an outcome `Loaded` with posts and `upsell.soft = true`
- **WHEN** the banner's "Aktifkan Premium" control is activated
- **THEN** the post cards AND the `timeline_limit_soft` banner are rendered AND `onActivatePremium` fires exactly once with `PaywallEntry.TIMELINE_CAP`

#### Scenario: After a confirmed purchase the hard state shows the activating notice and a reload

- **GIVEN** the Following screen whose Koin graph binds a `PremiumEntitlementSession` on which `onPurchaseConfirmed()` has run, over an outcome `Loaded` with empty posts and `upsell.hard = true`
- **WHEN** the state renders and its "Coba lagi" control is activated
- **THEN** the tree shows `premium_activating_body` AND neither `timeline_limit_hard` nor an "Aktifkan Premium" control AND the first page is fetched a second time

#### Scenario: After a confirmed purchase the soft banner is not rendered

- **GIVEN** the same confirmed session over an outcome `Loaded` with posts and `upsell.soft = true`
- **WHEN** the feed renders
- **THEN** the post cards are rendered AND neither `timeline_limit_soft`, `premium_activating_body` nor an "Aktifkan Premium" control is in the tree
