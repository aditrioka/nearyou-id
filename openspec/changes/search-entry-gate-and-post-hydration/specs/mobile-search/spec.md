## RENAMED Requirements

- FROM: `### Requirement: The Premium gate renders the Free-tier upsell panel reactively on 403`
- TO: `### Requirement: Pagination is a "Lihat lebih banyak" load-more that appends pages

When the current `Results` outcome carries a non-null `nextOffset`, `SearchScreen` SHALL render a "Lihat lebih banyak" control via `stringResource(Res.string.search_load_more)` below the result list. Activating it SHALL issue a fetch with `offset = nextOffset`, **append** the returned hits to the retained list (NOT replace it), and update the retained `nextOffset`. The append SHALL drop any returned hit whose `postId` the retained list already holds — `OFFSET` paging over rank ties can repeat a hit when the result set shifts between pages, and the result list keys its items on `postId` (a duplicate key would crash it). A `nextOffset == null` SHALL hide the control (terminal). A returned empty page SHALL be treated as terminal (the control is hidden) even if a non-null `nextOffset` was returned — clients treat `results = []` as terminal per `premium-search` § "Pagination via OFFSET". During a load-more fetch the existing results SHALL remain rendered (the list is never torn down) with at most one in-list progress indicator; the screen state stays `Results`, NOT `Loading`.

#### Scenario: Load-more appends the next page and keeps the list mounted

- **GIVEN** `SearchScreen` in the `Results` state with a first page of 20 hits and `nextOffset = 20`, over a `FakeSearchFlow` whose load-more returns 5 more hits with `nextOffset = null`
- **WHEN** the "Lihat lebih banyak" control is activated
- **THEN** the list renders 25 hits (the 5 appended to the original 20) AND the existing 20 stayed rendered during the fetch AND the load-more control is now hidden (`nextOffset == null`)

#### Scenario: A null nextOffset hides the load-more control

- **WHEN** the `Results` outcome carries `nextOffset = null`
- **THEN** no "Lihat lebih banyak" control is rendered

#### Scenario: A load-more page repeating a retained hit does not duplicate it

- **GIVEN** a `SearchViewModel` whose retained `Results` hold `p1`, `p2` with `nextOffset = 2`, over a flow whose load-more returns `p2`, `p3`
- **WHEN** the load-more is requested
- **THEN** the retained hits are exactly `p1`, `p2`, `p3` (in that order — `p2` is not appended twice)

### Requirement: The Premium gate renders the Free-tier upsell panel on entry for a known-Free viewer and reactively on 403`

- FROM: `### Requirement: A result tap opens PostDetailRoute with documented default fields`
- TO: `### Requirement: A result tap opens PostDetailRoute hydrated from the by-id post read`

- FROM: `### Requirement: Autocomplete and proactive upsell are explicitly deferred`
- TO: `### Requirement: Username autocomplete is explicitly deferred`

## MODIFIED Requirements

### Requirement: SearchScreen renders the Cari surface and is navigation-free

The mobile app SHALL ship a composable `SearchScreen` (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/search/SearchScreen.kt`), mapped from the `SearchRoute` `NavKey` by the `appEntryProvider`, that renders the search surface. As a pushed full-screen route (overlaying the section `NavigationBar`, like `PostDetailScreen`), it SHALL own a minimal top bar carrying: (a) a back affordance invoking a hoisted `onBack` lambda; (b) an M3 single-line search text field with a hint via `stringResource(Res.string.search_hint)` ("Cari postingan"); (c) a clear affordance (visible while the field is non-empty) that empties the field and returns the screen to the Idle state — or, for a viewer known Free on entry, to the on-entry Premium gate (per the § "The Premium gate renders the Free-tier upsell panel on entry for a known-Free viewer and reactively on 403" requirement). Below the bar, the screen SHALL render the result list / state surface filling the remaining space, mapping to exactly one `SearchUiState` per the § "Screen state mapping" requirement. `SearchScreen` SHALL be navigation-free: it holds no back-stack reference; its back affordance invokes the hoisted `onBack`, and a result tap is resolved by the ViewModel and delivered to the hoisted `onOpenPost(...)` (per the § "A result tap opens PostDetailRoute hydrated from the by-id post read" requirement). No hardcoded UI string literals SHALL appear in the screen source (every `Text` / `contentDescription` resolves via `stringResource`). The screen SHALL render under `NearYouTheme` (light/dark).

#### Scenario: SearchScreen renders the search input and is navigation-free

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/search/SearchScreen.kt`
- **THEN** the screen renders a search text field (hint via `stringResource(Res.string.search_hint)`), a back affordance bound to the hoisted `onBack`, and a clear affordance AND holds no back-stack reference (navigation is delivered via the hoisted `onBack` / `onOpenPost` lambdas only)

#### Scenario: No hardcoded UI strings in SearchScreen source

- **WHEN** inspecting `SearchScreen.kt`
- **THEN** every `Text(...)` / `contentDescription = ...` call site sources its text via `stringResource(Res.string.<name>)`; zero literal string arguments appear in such call sites

#### Scenario: Back affordance invokes onBack and returns to the prior surface

- **GIVEN** `SearchScreen` composed over a test root back stack (or with a recording `onBack` callback) with `SearchRoute` as the current entry
- **WHEN** the back affordance is activated
- **THEN** the `SearchRoute` entry is removed from the root back stack (`removeLastOrNull`) / the recording `onBack` fires, and the prior surface becomes current again

### Requirement: A client-side query guard mirrors the backend 2..100 bound and debounces requests

`SearchScreen` SHALL NOT issue a request until the query, after trimming leading/trailing Unicode whitespace, is between `2` and `100` Unicode code points (mirroring the backend `premium-search` § "Query length guard 2..100"). A below-2 query (including empty) keeps the screen in the Idle state — the on-entry Premium gate for a viewer known Free on entry — and issues NO request. The text field SHALL cap input at `100` code points. A valid query SHALL be issued on a **500 ms** debounce after the last keystroke AND immediately on the keyboard submit action (`docs/03-UX-Design.md:242`). The trim + code-point counting SHALL be a pure commonMain helper, unit-testable without composing UI. This is a UX optimization; the backend guard remains authoritative — a `400 invalid_query_length` (should the bounds ever diverge) maps to `Error`, never a crash (per the § "Fetch outcome mapping" requirement).

#### Scenario: Below-2 query issues no request and stays Idle

- **GIVEN** `SearchScreen` over a counting `FakeSearchFlow` for a viewer not known Free
- **WHEN** the query field holds `a` (post-trim length 1) or `   ` (whitespace, post-trim length 0)
- **THEN** no fetch is issued (the fake's invocation count stays 0) AND the screen renders the Idle prompt

#### Scenario: 2-char and 100-char boundaries are accepted; 101 is capped

- **WHEN** the query guard helper evaluates a 2-code-point query, a 100-code-point query, and a 101-code-point input
- **THEN** the 2- and 100-code-point queries are eligible to fetch AND the field caps the 101-code-point input at 100 code points

#### Scenario: A valid query fires on debounce and on submit

- **GIVEN** `SearchScreen` over a counting `FakeSearchFlow`
- **WHEN** the user types a valid query and pauses (500 ms) — and separately, types and presses the keyboard submit action
- **THEN** a fetch is issued in each case for the current query (the fake's invocation count increases)

### Requirement: Screen state mapping covers idle, loading, results, empty, error, gate, rate-limit, and disabled states

`SearchScreen` state SHALL be modeled as a Compose-free `SearchUiState` (data class or sealed type) plus a pure projection `searchUiState(query: String, outcome: SearchOutcome?, isLoading: Boolean, isLoadingMore: Boolean, viewerKnownFree: Boolean): SearchUiState` — mirroring `mobile-global-timeline`'s `globalTimelineUiState(...)` — so the mapping is deterministically unit-testable in commonTest without composing the UI. `viewerKnownFree` is `true` only while the on-entry tier read has resolved the viewer as Free (per the § "The Premium gate renders the Free-tier upsell panel on entry for a known-Free viewer and reactively on 403" requirement); it is `false` while the read is in flight, after a failed read, and for a Premium viewer. The projection MUST carry no PII (no `author_id`, no `rank`). The screen SHALL render exactly one of these states, all copy via `stringResource`, following the `mobile-design-system` loading-state contract (never two simultaneous progress indicators):

- **Idle** (the post-trim query length is `< 2`, including the empty initial state, AND `viewerKnownFree = false`) → a directive prompt via `stringResource(Res.string.search_idle_prompt)`; no request is issued and no result/error surface is shown. With `viewerKnownFree = true` the same below-2 query projects to **PremiumGate** instead (the on-entry upsell); still no request is issued.
- **Loading** (a first-page query in flight, no prior results) → a single loading indicator via `stringResource(Res.string.timeline_loading)`; the load-more affordance is NOT shown.
- **Results** (`Results` with non-empty `hits`) → the search-result-card list; a "Lihat lebih banyak" load-more control is shown when `nextOffset != null` (per the § "Pagination" requirement).
- **EmptyResults** (`Results` with empty `hits`) → a node with `stringResource(Res.string.search_empty_results)` formatted with the current query (the `docs/03-UX-Design.md:244` copy "Tidak ada hasil untuk '{query}'. Coba kata kunci lain.").
- **Error** (`NetworkError` or retryable `Error`) → a node with `stringResource(Res.string.signin_error_network)` AND a retry control labelled `stringResource(Res.string.cta_retry)` that re-issues the current query.
- **PremiumGate** (`PremiumGate`, or a below-2 query with `viewerKnownFree = true`) → the Free-tier upsell panel (per the § "The Premium gate renders the Free-tier upsell panel on entry for a known-Free viewer and reactively on 403" requirement).
- **RateLimited** (`RateLimited`) → the rate-limit modal (per the § "Rate-limit modal" requirement).
- **Disabled** (`Disabled`) → a node with `stringResource(Res.string.search_disabled)` (the kill-switch state; no retry control, since retrying cannot help while the flag is off).
- **SessionExpired** (`SessionExpired`) → a neutral redirect placeholder via `stringResource` (e.g. `timeline_session_redirect`) with NO retry control and NOT the connectivity copy (the in-screen complement to the reliable `SignInScreen` re-route).

An eligible query (post-trim length `2..100`) projects from its outcome exactly as above regardless of `viewerKnownFree` — the server's answer governs once a query is issued.

#### Scenario: Projection maps each outcome to its state deterministically

- **WHEN** the projection is invoked (with `viewerKnownFree = false`) for a `< 2`-char query (Idle), an in-flight first-page query (Loading), `Results(non-empty, nextOffset != null)`, `Results(non-empty, nextOffset = null)`, `Results(empty)`, `PremiumGate`, `RateLimited(...)`, `Disabled`, `NetworkError`, and `SessionExpired`
- **THEN** it returns Idle / Loading / Results(with load-more) / Results(without load-more) / EmptyResults / PremiumGate / RateLimited / Disabled / Error / SessionExpired respectively, deterministically (no wall-clock or platform dependency)

#### Scenario: A known-Free viewer's below-2 query projects to the gate, an eligible one to its outcome

- **WHEN** the projection is invoked with `viewerKnownFree = true` for the empty query, for `"a"`, and for `"jakarta"` with a `Results(non-empty, nextOffset = null)` outcome
- **THEN** the empty and `"a"` queries return PremiumGate AND `"jakarta"` returns Results (the server answer governs an issued query)

#### Scenario: EmptyResults renders the query-formatted copy

- **WHEN** the outcome is `Results` with empty `hits` and the query is `qwerty`
- **THEN** the rendered tree contains a node whose text matches `stringResource(Res.string.search_empty_results)` formatted with `qwerty` AND renders zero result cards

#### Scenario: Error shows network copy and a retry control

- **WHEN** the outcome is `NetworkError`
- **THEN** the rendered tree contains a node whose text matches `stringResource(Res.string.signin_error_network)` AND a clickable node whose text matches `stringResource(Res.string.cta_retry)`

#### Scenario: Disabled renders the kill-switch copy with no retry and not the connectivity error

- **WHEN** the outcome is `Disabled`
- **THEN** the rendered tree contains a node whose text matches `stringResource(Res.string.search_disabled)` AND renders no retry control AND does NOT contain `stringResource(Res.string.signin_error_network)` (the kill-switch state is distinct from the connectivity error)

#### Scenario: SessionExpired renders the neutral redirect, not the connectivity error

- **WHEN** the outcome is `SessionExpired`
- **THEN** the rendered tree contains the neutral redirect notice (`stringResource(Res.string.timeline_session_redirect)`) AND does NOT contain `stringResource(Res.string.signin_error_network)` AND does NOT contain a `stringResource(Res.string.cta_retry)` control (the connectivity-error state is reserved for `NetworkError` / `Error`)

### Requirement: The Premium gate renders the Free-tier upsell panel on entry for a known-Free viewer and reactively on 403

While the search surface state is `PremiumGate` — either the reactive `403 premium_required` outcome, or the on-entry gate for a known-Free viewer described below — `SearchScreen` SHALL render a Free-tier upsell panel:

- an explanatory body via `stringResource` (e.g. `search_premium_gate_body`) describing that search is a Premium feature
- a primary CTA via `stringResource(Res.string.cta_activate_premium)` ("Aktifkan Premium", the shared key; there is no search-specific CTA key)

The CTA SHALL invoke a hoisted `onActivatePremium` callback that the host (the `appEntryProvider` call site) wires to push `PaywallRoute(entry = PaywallEntry.SEARCH_GATE)` onto the root back stack (the `mobile-paywall` capability — mockup frame 17, `docs/03-UX-Design.md` § Paywall & Premium Disclosure). `SearchScreen` SHALL remain navigation-free: it holds no back-stack reference, and navigation is delivered only via the hoisted callback.

**On-entry gate (`docs/03-UX-Design.md` § Search UX — "Free users see an upsell on tap").** On creation, the route-scoped `SearchViewModel` SHALL resolve the viewer's tier with the established self-profile read — `SelfUserIdProvider.selfUserId()` then `ProfileFlow.loadProfile(selfId)` — the same seam `UsernameCustomizationViewModel` / `NearbyTimelineViewModel` use. The effective tier is the read's `isPremium` OR `purchaseConfirmed.value` (the `mobile-premium-entitlement` signal), so a buyer whose read lags the webhook is never gated. The resolution SHALL drive the projection's `viewerKnownFree` input:

- read `Loaded` with `isPremium = false` and no confirmed purchase → known Free: the below-2-query surface (the would-be Idle prompt) renders the upsell panel before anything is typed, and no search request is issued for it;
- read `Loaded` with `isPremium = true`, OR a confirmed purchase → known Premium: the Idle prompt;
- the read in flight, a missing self id, or any non-`Loaded` read outcome → NOT known Free: the Idle prompt (optimistic degradation — never an error wall; the reactive `403` backstops correctness).

The on-entry gate is a UX pre-check, NOT an enforcement point; the server's `403` stays authoritative. The search field SHALL remain usable while the on-entry gate shows, and an eligible query SHALL still be issued through the normal debounce/submit path, so the server decides (the profile `isPremium` is `premium_active` only, while the search gate also admits `premium_billing_retry`; a `403` consumes no search quota because the gate precedes the rate limiter). A first-page answer that proves Premium-tier access — `Results` or `RateLimited` — SHALL mark the viewer known Premium for the rest of the route's lifetime (clearing the field afterwards returns to the Idle prompt, not the upsell). Known Premium is **sticky**: whichever of the self read, a Premium-proving answer, or a confirmed purchase lands first, a later self read SHALL NOT downgrade it back to Free (tier writes move only toward Premium). A `403` SHALL NOT mark the viewer known Free (the reactive gate already renders for that query, and during the webhook-lag window the activating notice below owns the panel).

During the post-purchase webhook-lag window (`mobile-premium-entitlement` § "Upsell surfaces show the activating notice during the webhook-lag window") the gate panel SHALL render `premium_activating_body` in place of the upsell body, and its button SHALL become "Coba lagi" (`cta_retry`). The button re-runs the current query through the screen's existing retry path instead of opening the paywall. That is the state the once-only re-run below lands in when it returns `PremiumGate` again. `SearchScreen` reads the signal through the shared fail-safe resolver. The on-entry gate is never shown while `purchaseConfirmed` is `true` (the effective tier is known Premium), so only the reactive gate can carry the activating notice.

The gate panel SHALL NOT issue a search request on its own while shown (requests come only from the viewer editing or submitting the query), with one exception: when the `purchaseConfirmed` signal becomes `true` while the outcome is `PremiumGate`, the route-scoped `SearchViewModel` SHALL re-run the current query exactly once, as if retried. The same `purchaseConfirmed` transition SHALL also mark the viewer known Premium, lifting an on-entry gate to the Idle prompt with no request issued. The server `403` remains authoritative; during the post-purchase webhook-lag window the re-run MAY return `PremiumGate` again.

This resolves the v1 informational-placeholder state: the CTA is no longer a no-op. GitHub issue [#254](https://github.com/aditrioka/nearyou-id/issues/254) is addressed by the change introducing this behavior, and the on-entry gate resolves GitHub issue [#253](https://github.com/aditrioka/nearyou-id/issues/253). The `429` rate-limit state is unaffected: it is a Premium-tier limit, so a user who reaches it is already Premium and is shown a countdown/retry, never a paywall CTA.

#### Scenario: 403 renders the upsell panel with the Premium CTA

- **GIVEN** a `FakeSearchFlow` returning `SearchOutcome.PremiumGate` for a valid query
- **WHEN** `SearchScreen` renders
- **THEN** the rendered tree contains the upsell body (`search_premium_gate_body`) AND a CTA labelled `stringResource(Res.string.cta_activate_premium)`

#### Scenario: The upsell CTA pushes PaywallRoute with the search-gate entry-context

- **GIVEN** the upsell panel composed over a test root back stack (or the `appEntryProvider` call site over a test root back stack)
- **WHEN** the "Aktifkan Premium" CTA is activated
- **THEN** a `PaywallRoute(entry = PaywallEntry.SEARCH_GATE)` is appended to the root back stack AND `SearchScreen` holds no back-stack reference (navigation is delivered via the hoisted `onActivatePremium` callback)

#### Scenario: A known-Free viewer sees the upsell before typing

- **GIVEN** `SearchScreen` whose self-profile read returns `Loaded(isPremium = false)` AND no confirmed purchase
- **WHEN** the screen first renders with an empty query
- **THEN** the rendered tree contains `search_premium_gate_body` AND the "Aktifkan Premium" CTA AND does NOT contain the Idle prompt (`search_idle_prompt`) AND no search request has been issued

#### Scenario: A Premium viewer opens to the Idle prompt

- **GIVEN** `SearchScreen` whose self-profile read returns `Loaded(isPremium = true)`
- **WHEN** the screen first renders with an empty query
- **THEN** the rendered tree contains the Idle prompt AND does NOT contain `search_premium_gate_body`

#### Scenario: A pending, failed, or missing self read degrades to the Idle prompt

- **GIVEN** a `SearchViewModel` whose self read is still in flight, OR returns a non-`Loaded` outcome (e.g. `NetworkError`), OR whose `SelfUserIdProvider` returns null
- **WHEN** its `uiState` is observed with an empty query
- **THEN** the surface is `Idle` (never `PremiumGate`, never an error state)

#### Scenario: A known-Free viewer's query is still decided by the server

- **GIVEN** a `SearchViewModel` resolved known Free on entry
- **WHEN** the viewer submits `"kopi"` and the flow returns `Results`, and the viewer then clears the field
- **THEN** a search for `"kopi"` was issued AND the surface showed `Results` AND after clearing the surface is `Idle` (the server answer marked the viewer known Premium), not `PremiumGate`

#### Scenario: A known-Free viewer's rate-limited answer also proves Premium

- **GIVEN** a `SearchViewModel` resolved known Free on entry
- **WHEN** the viewer submits `"kopi"` and the flow returns `RateLimited(60)`, and the viewer then clears the field
- **THEN** after clearing the surface is `Idle`, not `PremiumGate` (a `429` is a Premium-tier limit)

#### Scenario: A late Free read never downgrades a Premium-proving answer

- **GIVEN** a `SearchViewModel` whose self read is still in flight
- **WHEN** the viewer submits `"kopi"` and the flow returns `Results`, then the self read lands `Loaded(isPremium = false)`, then the viewer clears the field
- **THEN** the surface is `Idle` after clearing, never `PremiumGate`

#### Scenario: A purchase confirmed while the read is in flight is not overwritten

- **GIVEN** a `SearchViewModel` whose self read is still in flight
- **WHEN** `purchaseConfirmed` becomes `true` and the read then lands `Loaded(isPremium = false)`
- **THEN** the surface stays `Idle`, never `PremiumGate`

#### Scenario: A known-Free viewer's 403 keeps the gate

- **GIVEN** a `SearchViewModel` resolved known Free on entry
- **WHEN** the viewer submits `"kopi"` and the flow returns `PremiumGate`, and the viewer then clears the field
- **THEN** the surface is `PremiumGate` after the query AND still `PremiumGate` after clearing (the on-entry gate stands)

#### Scenario: A confirmed purchase lifts the on-entry gate without a request

- **GIVEN** a `SearchViewModel` resolved known Free on entry with an empty query
- **WHEN** `purchaseConfirmed` becomes `true`
- **THEN** the surface becomes `Idle` AND no search request is issued

#### Scenario: A purchase confirmed before entry is never gated on entry

- **GIVEN** `purchaseConfirmed` is already `true` AND the self read returns `Loaded(isPremium = false)` (the webhook has not landed)
- **WHEN** a `SearchViewModel` is created and its read resolves
- **THEN** the surface is `Idle`, never `PremiumGate`

#### Scenario: The on-entry gate renders on Kotlin/Native

- **GIVEN** the iOS flow test (`SearchFlowIosTest`) binding a self-profile read with `isPremium = false`
- **WHEN** `SearchScreen` first renders on the iOS simulator target
- **THEN** the upsell body renders AND the Idle prompt does not

#### Scenario: A confirmed purchase re-runs the gated query once

- **GIVEN** `SearchViewModel` whose outcome for query `"kopi"` is `PremiumGate` AND a `purchaseConfirmed` flow that is `false`
- **WHEN** `purchaseConfirmed` becomes `true` AND the flow now returns `Results` for `"kopi"`
- **THEN** exactly one additional search for `"kopi"` is issued AND the outcome becomes `Results`

#### Scenario: A confirmed purchase does not re-query outside the gate

- **GIVEN** `SearchViewModel` whose outcome is `Results` (not `PremiumGate`)
- **WHEN** `purchaseConfirmed` becomes `true`
- **THEN** no additional search request is issued

#### Scenario: The gate shows the activating notice and a retry after a confirmed purchase

- **GIVEN** `SearchScreen` whose Koin graph binds a `PremiumEntitlementSession` on which `onPurchaseConfirmed()` has run AND a `FakeSearchFlow` returning `SearchOutcome.PremiumGate`
- **WHEN** the screen renders the gate for a valid query and its "Coba lagi" control is activated
- **THEN** the rendered tree contains `premium_activating_body` AND does NOT contain `search_premium_gate_body` AND contains no "Aktifkan Premium" control AND the same query is searched once more AND the hoisted `onActivatePremium` is never invoked

### Requirement: A result tap opens PostDetailRoute hydrated from the by-id post read

A search result card SHALL be tappable. The tap SHALL go to the route-scoped `SearchViewModel` (`onResultTap(hit)`), which resolves the post through the full `single-post-read` projection (`GET /api/v1/posts/{post_id}`) via `SearchFlow.resolvePostTarget(postId)` — the SAME by-id resolution the notification deep-link uses (the shared `PostTargetResolution` + `toPostDetailTarget()` mapping; no second resolver). While the read is in flight the tapped card SHALL show a small progress indicator (test tag `searchResultResolving`) — an action affordance on the tapped card (the `NotificationsScreen` row-resolving precedent), NOT a list load/refresh indicator, so it is outside the `mobile-design-system` "never two progress indicators" list-loading rule. A newer tap on a different card SHALL cancel and supersede an in-flight one (latest tap wins); a repeated tap on the card already resolving SHALL be ignored (the in-flight read is kept, not restarted). An in-flight resolution SHALL also be cancelled — and its card indicator cleared — when the query changes (an edit, a submit, a clear, or a retry starts a new search) and when the `SearchScreen` leaves composition (e.g. covered by the pushed detail), so a resolution never navigates to a post that is no longer on screen or opens a second detail after the viewer returns. While a resolved target is pending, further taps SHALL be ignored. A non-cancellation exception from the read SHALL be treated like `Unavailable` (the fallback below), never escape the ViewModel scope.

The resolved destination SHALL be exposed as a nullable, consumed-once `pendingNavTarget: PostDetailTarget?` on the ViewModel's `uiState` (docs/11 §2.2 one-shot-as-state; NO `Channel`/`SharedFlow`; the `NotificationsViewModel` naming — `pendingNavTarget` / `onNavConsumed()` / a resolving id). `SearchScreen` SHALL invoke the hoisted `onOpenPost(target)` with it and then clear it via `onNavConsumed()`, so recomposition or a configuration change never navigates twice. `SearchScreen` SHALL remain navigation-free; the host (the `appEntryProvider` call site) pushes `PostDetailRoute` onto the root back stack through the SAME `PostDetailTarget` → `PostDetailRoute` mapping the Home feed card tap uses.

- **Resolved** → the target carries the read's `postId`, `content`, `createdAtIso`, `authorUsername`, `authorDisplayName`, `cityName`, `likedByViewer`, `replyCount`, and `imageUrl`, with `distanceM = null` (search has no spatial origin and the by-id projection carries no coordinates).
- **Unavailable** (any non-`200` incl. `404 post_not_found`, a transport failure, or a thrown read) → the target falls back to the hit's own fields (`postId`, `content`, `createdAtIso` = the hit's `createdAt`, `authorUsername`, `authorDisplayName`) with the documented defaults `cityName = ""`, `distanceM = null`, `likedByViewer = false`, `replyCount = 0`, `imageUrl = null`, so a tap is never stranded by a transient failure. In this fallback the like toggle's initial state and the header reply count MAY be cosmetically stale until the detail screen's authoritative `/likes/count` + `/replies` fetches resolve; the like endpoints are idempotent, so a stale-`false` initial state cannot corrupt server state.

The payload SHALL never carry `latitude`/`longitude` or the `author_id` UUID. This resolves GitHub issue [#255](https://github.com/aditrioka/nearyou-id/issues/255).

#### Scenario: Tapping a result pushes PostDetailRoute hydrated from the by-id read

- **GIVEN** the search surface with a loaded hit `p1` AND a `SearchFlow` whose `resolvePostTarget("p1")` returns `Resolved` with `cityName = "Jakarta Selatan"`, `likedByViewer = true`, `replyCount = 4`, `imageUrl = "https://img.example/p1.jpg"`
- **WHEN** the result card is tapped
- **THEN** exactly one `PostDetailRoute` is pushed (or one target delivered to the recording `onOpenPost`) carrying `postId = "p1"` AND `cityName = "Jakarta Selatan"`, `likedByViewer = true`, `replyCount = 4`, `imageUrl = "https://img.example/p1.jpg"`, `distanceM = null` AND no `latitude`/`longitude` and no author UUID

#### Scenario: An unavailable by-id read falls back to the hit payload with documented defaults

- **GIVEN** a loaded hit (`postId`, `content`, `createdAt`, `authorUsername`, `authorDisplayName`) AND `resolvePostTarget` returning `Unavailable`
- **WHEN** the result card is tapped
- **THEN** the delivered target carries the hit's `postId`/`content`/`createdAtIso`/`authorUsername`/`authorDisplayName` AND `cityName = ""`, `distanceM = null`, `likedByViewer = false`, `replyCount = 0`, `imageUrl = null`

#### Scenario: The tapped card shows a spinner while its read is in flight

- **GIVEN** a `resolvePostTarget` that has not returned yet
- **WHEN** a result card is tapped
- **THEN** that card renders the `searchResultResolving` progress indicator AND no navigation has happened yet

#### Scenario: A newer tap supersedes an in-flight resolution

- **GIVEN** a `resolvePostTarget` for `p1` still in flight
- **WHEN** the viewer taps `p2`, then `p1`'s read is released before `p2`'s completes
- **THEN** `p1`'s result is discarded AND exactly one target is ever delivered, and it is `p2`

#### Scenario: A double-tap on the resolving card keeps the in-flight read

- **GIVEN** a `resolvePostTarget` for `p1` still in flight after a tap
- **WHEN** the same `p1` card is tapped again
- **THEN** no second read is issued for `p1` AND the `p1` card still shows the resolving indicator

#### Scenario: A query change cancels an in-flight resolution

- **GIVEN** a `resolvePostTarget` for `p1` still in flight after a tap
- **WHEN** the viewer edits or clears the query, then the read completes
- **THEN** no target is delivered AND the resolving indicator is cleared

#### Scenario: A thrown by-id read falls back instead of crashing

- **GIVEN** a `resolvePostTarget` that throws a non-cancellation exception
- **WHEN** a result card is tapped
- **THEN** the fallback target (hit fields + documented defaults) is delivered AND no exception escapes the ViewModel

#### Scenario: The resolved target is consumed once

- **GIVEN** a resolved target delivered to `onOpenPost`
- **WHEN** the screen recomposes (or the ViewModel's `uiState` is re-collected)
- **THEN** `onOpenPost` was invoked exactly once (the pending target was cleared via `onNavConsumed()`)

### Requirement: SearchApiClient and SearchRepository are Koin singletons behind a testable seam

`SearchApiClient` and `SearchRepository` SHALL be registered in the commonMain Koin `mobileModule`. `SearchRepository` SHALL be bound behind a `SearchFlow` interface (`single<SearchFlow> { get<SearchRepository>() }`) so a `FakeSearchFlow` can drive the screen + ViewModel tests, mirroring the timeline seams. `SearchFlow` SHALL declare both `search(query, offset)` and `resolvePostTarget(postId): PostTargetResolution`; `SearchRepository` SHALL implement `resolvePostTarget` over the shared `SinglePostApiClient` Koin singleton's `fetchFullPost` through the shared `SinglePostFullResult` → `PostTargetResolution` mapping (the one `NotificationsRepository` also uses), logging only a type tag on `Unavailable` (never the post id, body, or any PII).

The `SearchViewModel` SHALL be scoped to the `SearchRoute` NavEntry (resolved via `viewModel { … }` under the root `NavDisplay`'s `rememberViewModelStoreNavEntryDecorator()` for `SearchRoute`, the pushed-route precedent). It takes the `SearchFlow`, the `ProfileFlow` + `SelfUserIdProvider` self-read seam, and the fail-safe `purchaseConfirmed` signal. It holds the query, the in-flight flags, the retained outcome, the retained `nextOffset`, the resolved tier, the resolving card id, and the consumed-once pending detail target, and it issues the search via the `SearchFlow` seam (debounced + on submit). It SHALL expose ONE `uiState: StateFlow<SearchScreenUiState>` via `stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), …)` (docs/11 §2.2) whose surface is the pure `searchUiState(...)` projection; the raw `outcome` MAY stay exposed as the white-box test seam (the `GlobalTimelineViewModel` precedent). The query/results state is owned by the ViewModel, NOT composition-scoped `remember`.

#### Scenario: Koin registers the search graph behind the flow interface

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/di/MobileModule.kt`
- **THEN** `mobileModule` declares singletons for `SearchApiClient` and `SearchRepository` (the latter over `SearchApiClient` + the shared `SinglePostApiClient`) AND binds `single<SearchFlow> { get<SearchRepository>() }`

#### Scenario: SearchViewModel issues the query through the SearchFlow seam

- **GIVEN** a commonTest `SearchViewModel` over a `FakeSearchFlow`
- **WHEN** a valid query is submitted and, separately, a load-more is requested
- **THEN** the ViewModel invokes `SearchFlow.search(query, offset = 0)` for the query and `SearchFlow.search(query, offset = <nextOffset>)` for the load-more, exposing the resulting outcome + retained `nextOffset`

#### Scenario: resolvePostTarget maps the full by-id read

- **GIVEN** a MockEngine-backed `SearchRepository` whose `GET /api/v1/posts/p1` returns `200` with the shipped mixed-case full projection (bare `id`/`authorUsername`/`authorDisplayName`/`content`/`createdAt`/`imageUrl`, snake `city_name`/`liked_by_viewer`/`reply_count`), and separately returns `404 post_not_found`
- **WHEN** `resolvePostTarget("p1")` runs for each
- **THEN** the `200` yields `PostTargetResolution.Resolved` carrying those values AND the `404` yields `PostTargetResolution.Unavailable`

#### Scenario: The ViewModel exposes one screen state

- **WHEN** inspecting `SearchViewModel`
- **THEN** the screen collects a single `uiState: StateFlow<SearchScreenUiState>` (query + surface + resolving card id + pending nav target) AND `isLoading` / `isLoadingMore` / the resolved tier are not public flows

### Requirement: Username autocomplete is explicitly deferred

The Cari surface SHALL NOT implement username autocomplete / typeahead (`docs/03-UX-Design.md` § Search UX "Autocomplete: username from the top 5 results (pg_trgm)"): it requires a NEW backend autocomplete endpoint that is not shipped. Until that endpoint lands, the search field SHALL issue only the `premium-search` request (`GET /api/v1/search`) and render no suggestion list. The deferral is tracked by GitHub issue [#252](https://github.com/aditrioka/nearyou-id/issues/252) (`follow-up`), so the follow-up change can MODIFY this requirement. The proactive "upsell on tap before typing" is NO LONGER deferred — it is implemented per the § "The Premium gate renders the Free-tier upsell panel on entry for a known-Free viewer and reactively on 403" requirement (GitHub issue #253) — and paywall navigation is NO LONGER deferred either (the gate CTA routes to `PaywallRoute`).

#### Scenario: The remaining deferral is tracked, not silent

- **WHEN** inspecting this requirement and the `follow-up` issues
- **THEN** username autocomplete is recorded with GitHub issue [#252](https://github.com/aditrioka/nearyou-id/issues/252) AND neither the proactive upsell nor paywall navigation is listed as a deferral

#### Scenario: No typeahead surface renders and no autocomplete request is issued

- **GIVEN** `SearchScreen` over a counting `FakeSearchFlow` (a Premium viewer)
- **WHEN** the viewer types a 2+-character query
- **THEN** the only fetch issued is `SearchFlow.search(...)` AND no suggestion / typeahead list is rendered apart from the result cards
