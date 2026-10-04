## Context

`mobile-search` shipped the Cari surface with two deliberate v1 limitations (`openspec/specs/mobile-search/spec.md`):

- **Reactive-only gate.** A Free viewer sees the upsell only after a `403 premium_required`. `docs/03-UX-Design.md` § Search UX wants "Free users see an upsell on tap". The deferral said the proactive gate needs a client-held tier signal. That signal now exists twice over:
  - the on-entry **self-profile read** (`ProfileFlow.loadProfile(selfId).isPremium`), already the tier source for `UsernameCustomizationViewModel`, `PostCreationViewModel` and `NearbyTimelineViewModel`;
  - `PremiumEntitlementSession.purchaseConfirmed` (`mobile-premium-entitlement`).

  The post-purchase re-run of a `403`-gated query already ships in `SearchViewModel.init`.
- **Placeholder detail fields.** A result tap pushes `PostDetailRoute` with `cityName = ""`, `distanceM = null`, `likedByViewer = false`, `replyCount = 0`, and drops `imageUrl` too. `SinglePostApiClient.fetchFullPost` (the full `single-post-read` projection) has since shipped. The notification deep-link already resolves a post by id through it: `NotificationsRepository.resolvePostTarget` → `PostTargetResolution` → `NotificationNavTargetResolver` → `PostDetailTarget`.

Constraints:

- **Tier semantics differ.** The profile wire's `isPremium` is `subscription_status = 'premium_active'` only (`JdbcUserProfileReader`). The search gate admits `PREMIUM_STATES = {premium_active, premium_billing_retry}` (`SearchService`). A billing-retry viewer therefore reads as Free on entry but is Premium for search.
- **Gate ordering.** The search gate runs **before** the rate limiter. A `403` consumes no hourly quota.
- **Concurrent ownership.** `PostDetailScreen.kt` is being reworked by a concurrent change and MUST NOT be touched. This change is the wave's owner of `screens/routing/AppEntryProvider.kt`.

## Goals / Non-Goals

**Goals:**
- A known-Free viewer sees the Premium upsell as soon as Cari opens, before typing. The reactive `403` stays the authoritative backstop.
- A search-origin `PostDetailRoute` carries the real `cityName` / `likedByViewer` / `replyCount` / `imageUrl`.
- No second post-by-id resolution path, and no new parallel pattern for any docs/11 concern.

**Non-Goals:**
- Username autocomplete. It needs a new backend endpoint and stays deferred to #252.
- A server-side change of any kind: wire, endpoints, DB, the `isPremium` semantics.
- Any `PostDetailScreen` change, such as re-fetching the header in place.
- A per-viewer like-status endpoint. The by-id read already carries `liked_by_viewer`.

## Decisions

### D1 — Tier source: the established self-profile read, ORed with `purchaseConfirmed`
`SearchViewModel` gains `ProfileFlow` + `SelfUserIdProvider` and resolves `isPremium` on entry, exactly as Nearby and username do. The result is ORed with `purchaseConfirmed.value`, so a buyer whose read lags the webhook is never re-gated.
**Alternatives considered:**
- RevenueCat `CustomerInfo` (`PurchaseController.isPremiumEntitlementActive`): a client-only entitlement, absent for viewers without a RevenueCat customer, and a second tier-source pattern next to three existing self-read gates.
- A `/me` endpoint: does not exist. The self profile read is the de-facto `/me`.

### D2 — The on-entry gate replaces only the Idle surface; the field stays usable
When the tier is known Free, the pure projection maps the **Idle** state (query below the 2-code-point guard) to `PremiumGate`, so the existing upsell panel renders before typing. The search field stays enabled. An eligible query is still sent, and the server's answer governs, so a Free viewer who types sees Loading and then the same `403` gate. That costs no quota (D-context: the gate precedes the limiter).
**Why not disable the field / block the request?** The billing-retry divergence above. A hard client gate would lock out viewers the server admits, which violates "server authoritative". Gating only the empty surface gives docs/03's "upsell on tap" with zero false lock-out.

### D3 — Resolving window and read failure → the Idle prompt (optimistic)
While the read is in flight (`null`), the projection receives `viewerKnownFree = false` and renders the Idle prompt. A missing self id or any non-`Loaded` outcome degrades to Premium-known, the `NearbyTimelineViewModel` precedent: never an error wall, with the reactive `403` backstopping.
**Alternative:** a spinner during resolution. It would flash on every Premium entry, and the Idle prompt is already actionable.

### D4 — Server evidence clears the gate; a `403` does not set it; Premium is sticky
A first-page `Results` or `RateLimited` (`429` is a Premium-tier limit) sets the tier known-Premium for the rest of the visit. The tier is a tri-state (`null` unresolved / `false` / `true`), and every write moves only toward Premium. The read lands as `tier.takeIf { it == true } ?: (isPremium || confirmed)`. A read that lands after a Premium-proving answer or a confirmed purchase therefore cannot downgrade it. (`NearbyTimelineViewModel` assigns its read unconditionally; copying that would re-gate a billing-retry viewer whose `Results` beat the read.) A billing-retry viewer who searched and then clears the field returns to the Idle prompt, not the upsell. A `403` does NOT flip the tier to Free. The reactive gate already renders for the gated query. Flipping would also fight `purchaseConfirmed` during the webhook-lag window, where a `403` is expected and the activating notice owns the panel.

### D5 — Purchase confirmation extends the existing collector (not rewritten)
The shipped `init` collector (`premiumConfirmed.first { it }` → re-run a `PremiumGate` query once) gains one step: mark the tier known-Premium. The once-only re-run semantics are unchanged. An empty-query Free viewer who buys therefore lands on the Idle prompt with no request issued.

### D6 — Tap hydration lives in the ViewModel as a consumed-once target (the notification pattern)
`SearchScreen` hands a tap to `SearchViewModel.onResultTap(hit)`. The names follow the `NotificationsViewModel` precedent (docs/11 §4: one concept, one name).
1. The VM ignores the tap while a resolved target is still pending.
2. Otherwise it cancels any prior resolution (latest tap wins).
3. It marks the card `resolvingPostId`, which drives a small per-card spinner, the `NotificationsScreen` row idiom.
4. It calls `SearchFlow.resolvePostTarget(postId)`. A non-cancellation throw is treated like `Unavailable`.
5. It stores the `PostDetailTarget` as a nullable **consumed-once** `pendingNavTarget` in `uiState`.
6. The screen's `LaunchedEffect` invokes the hoisted `onOpenPost(target)` and then `onNavConsumed()`.

Per docs/11 §2.2 this models one-shot events as state; there is no `Channel`/`SharedFlow`. The screen stays navigation-free.

An in-flight resolution is cancelled, and its spinner cleared, in two cases:
- **A new search starts** (edit / submit / clear / retry), so it never opens a post that is no longer on screen.
- **`SearchScreen` leaves composition** (a `DisposableEffect` calling `onNavConsumed()`). Nav3 composes only the top entry, so without this a tap made during the push transition could resolve while Search sits under the detail, and then push a second detail when the viewer comes back.

A repeated tap on the card already resolving is ignored, so a double-tap does not restart the read. Accepted edge: an Android configuration change (rotation) also leaves composition, so a tap whose read is still in flight at that moment is dropped. The spinner clears and the viewer taps again, and nothing navigates wrongly. iOS keeps the composition across rotation.

The per-card spinner is an action affordance on the tapped card, not a list loading indicator, so it sits outside `mobile-design-system`'s "never two progress indicators" list-loading rule. The notification-row spinner already coexists with that list's load-more footer the same way.

**Alternatives considered:**
- Hydrate inside `PostDetailScreen`. Forbidden: concurrent ownership. It would also break `mobile-post-detail` § "The post header renders from nav args".
- Fetch in `appEntryProvider`. That would be I/O in composition, with no VM lifecycle.
- Push first and refresh later. That is the existing cosmetic-staleness behavior this change removes.

### D7 — `Unavailable` falls back to the v1 payload, not a "tidak tersedia" notice
A notification has nothing renderable without the read, so it shows a transient "unavailable" notice. A search hit already carries a renderable payload. `SinglePostFullResult.Unavailable` also does not distinguish `404` from a transport failure, and a flaky network must not strand a tap. On `Unavailable` the VM therefore emits the pre-change payload: the hit fields plus `cityName = ""`, `likedByViewer = false`, `replyCount = 0`, `imageUrl = null`. `distanceM` is always `null`, because search has no spatial origin and the by-id projection has no coordinates.
**Trade-off:** a post deleted after the search still opens exactly as it did before this change. The detail's own sub-fetches surface their states.

### D8 — One shared by-id post resolution (move, don't copy)
`PostTargetResolution` is the neutral "a post resolved by id" type. It moves from `notifications/NotificationsFlow.kt` to `post/PostTargetResolution.kt`, together with one `SinglePostFullResult.toPostTargetResolution()` mapping. `NotificationsRepository.resolvePostTarget` and `SearchRepository.resolvePostTarget` both call that mapping. The `Resolved → PostDetailTarget` mapping moves from `NotificationNavTargetResolver` (private) to a public `toPostDetailTarget()` next to `PostDetailTarget` in `screens/home/HomeScreen.kt`. The notification resolver and `SearchViewModel` both call it.

This is mechanical: no notification behavior changes, and their tests only update imports. The alternative was a second copy of both 9-field mappers in search, which is exactly the "second resolver" `NotificationNavigation.kt` documents against (docs/11 Pattern Registry, rule of three).

### D9 — `SearchViewModel` folds into ONE `uiState` (docs/11 §2.2 known debt)
docs/11 lists `SearchViewModel` (4 public flows) as debt to "consolidate when next touched", and this change touches it. It now exposes `uiState: StateFlow<SearchScreenUiState>` via `stateIn(WhileSubscribed(5_000))`. `SearchScreenUiState` holds the query (the text field value), the surface, the resolving card id (`resolvingPostId`) and the pending nav target (`pendingNavTarget`). The wrapper follows the `SignInScreenUiState` precedent. The surface is still produced by the pure `searchUiState(...)` projection, now with the explicit `viewerKnownFree` input. The raw `outcome` stays public as the white-box seam (the `GlobalTimelineViewModel` precedent). `isLoading` / `isLoadingMore` / the tier become private. The text field binds `uiState.query`, the `UsernameCustomizationScreen` precedent: `Dispatchers.Main.immediate` delivers the update before the next frame. docs/11 §2.2's debt count drops by one.

### D10 — One target→route helper in `screens/routing/`
`PostDetailRoute` is already built from a `PostDetailTarget` three times: the Home entry's open, its reply shortcut, and `PushTapNavigationEffect`'s notification push. Search becomes the fourth consumer. An `internal fun PostDetailTarget.toRoute(focusReplyComposer: Boolean = false)` in `screens/routing/` replaces the repeated field lists, so every call site carries the same fields, `imageUrl` included (docs/11 §4, rule of three).

### D11 — `SearchScreen.kt` sheds its state composables
`SearchScreen.kt` is 411 lines, past docs/11's ~400-line soft cap for a UI file, and this change grows it. The non-result state composables (`LoadingState`, `CenteredMessage`, `ErrorState`, `PremiumGateState`, `RateLimitedState`) move unchanged into `SearchStates.kt` in the same package, as `internal`. This is a pure move with no visual or behavioral change.

### D12 — `SearchViewModel` state shape
The VM follows the `UsernameCustomizationViewModel` shape: one private `MutableStateFlow<VmState>`, updated with `update { }`, then `.map { it.toUiState() }.stateIn(viewModelScope, WhileSubscribed(5_000), state.value.toUiState())`. The raw `outcome` stays exposed as a plain read-only getter, the white-box test seam (the role `GlobalTimelineViewModel.outcome` plays). One state object makes the tier, outcome and resolution writes atomic, and avoids a seven-flow `combine` feeding the text field.

### D13 — `loadMore` de-duplicates by post id (review-round finding, pre-existing)
`OFFSET` paging over rank ties (`ORDER BY rank DESC, created_at DESC`, no unique tie-breaker) can repeat a hit when a matching post lands between two page fetches. The result list keys on `postId`, so a repeated id would crash the `LazyColumn` ("Key was already used"). This predates the change, but sits in the `loadMore` this change refactors. The append becomes `(current.hits + next.hits).distinctBy { it.postId }`, a one-line fix with a test, recorded as a MODIFIED Pagination requirement.

### Mockup reference (docs/11 §2.8)
The mockup board has **no Cari frame** (frames 1–19 contain no search screen). This change adds no new visual element:
- the on-entry gate reuses the shipped gate panel (`search_premium_gate_body` + `PremiumGateAction`);
- the per-card spinner reuses the notification-row idiom (16 dp, 2 dp stroke).
Visual verification is the manual run (task 7.2).

### Standards conformance (docs/11)
- **§2.2 state:** an entry-scoped androidx `ViewModel` exposing ONE `stateIn` `uiState` (D9), with a one-shot nav target as nullable state consumed via a callback (D6). The app-wide fact `purchaseConfirmed` comes through the fail-safe `rememberPremiumConfirmed()` (§2.3 "Refreshing a screen after an overlay returns" → app-wide fact).
- **§2.3 navigation:** the host (`appEntryProvider`) pushes, and `SearchScreen` holds no back-stack reference.
- **§2.6 data:** VM → `SearchFlow` (`SearchRepository`) → `SinglePostApiClient`. The VM never calls an ApiClient. One shared `HttpClient`.
- **§2.1 reuse-first:** the shared by-id resolution (D8), the existing gate panel, and the `PremiumGateAction` component.
- **No deviation** from a registered pattern, so no Pattern Registry amendment. docs/11 §2.2's known-debt sentence is updated, since `SearchViewModel` is consolidated.

### Cross-layer scope (docs/12)
- **Backend: no change.** It consumes shipped `premium-search` (`403`), `single-post-read` (`GET /api/v1/posts/{id}`, full projection) and `user-profile-read` (self `isPremium`).
- **Admin: none.** A client-side gate and a nav payload have no operator surface.
- **Mobile: this change, complete.** No layer is deferred. Autocomplete (#252) is a separate capability already tracked as a deferred requirement.

## Risks / Trade-offs

- **[A billing-retry viewer sees the upsell on entry]** → they can still type and search (D2), and a successful answer clears the gate (D4). The profile `isPremium` semantics are out of scope.
- **[One extra `GET /users/{self}` per Cari entry]** → the same cost the username, composer and Nearby gates already pay. The read is not on the typing path.
- **[A tap now waits on a by-id GET before navigating]** → a per-card spinner gives feedback. A failure or timeout still navigates with the v1 payload (D7). The latest tap wins, so a double-tap cannot push twice.
- **[A terminal `401` during the by-id read]** → `fetchFullPost` maps it to `Unavailable`, so the fallback push races the session-expiry re-route (`replaceAll(SignInRoute)`). Both orderings are benign. If the push lands first, `replaceAll` supersedes it. If the re-route lands first, the `SearchRoute` entry is gone: its VM is cleared and the pending target is never consumed.
- **[Moving `PostTargetResolution` touches notifications files]** → only imports plus one call to the shared mapper. Behavior is pinned by the existing notification tests, which stay green unchanged except for imports.
- **[Text field bound to a `stateIn` flow]** → precedent `UsernameCustomizationScreen`. It is covered by the existing type/clear/submit screen tests.

## Migration Plan

Client-only. It ships with the next app build, and there is no data migration. Rollback is a revert: the server already enforces the gate, and the v1 tap payload is the fallback path.

## Open Questions

None.
