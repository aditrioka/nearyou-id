# 05 — Mobile Shell & State Management (Nav3, DI, theme, auth/session, state patterns)

Audited 2026-06-10 against `docs/11-Engineering-Standards.md` §2 + `openspec/specs/mobile-design-system/spec.md`.
Scope: `mobile/app/src/commonMain/kotlin/id/nearyou/app` (routing, shell, screens, auth, di, theme, network, state holders).
All paths below are relative to that commonMain root unless prefixed.

**Nav3 KMP-mandatory checks: PASS.** `rememberNavBackStack(navSavedStateConfiguration, RootRoute)` carries the polymorphic `SerializersModule`; all 7 NavKeys (`RootRoute`, `SignInRoute`, `HomeRoute`, `AgeGateRoute`, `PostCreationRoute`, `ConsentRoute`, `PostDetailRoute`) are registered in `AppNavSerialization.kt` with a load-bearing round-trip test (`NavKeySerializationTest`); decorator order in `App.kt:46-50` is saveable-FIRST, viewModelStore-SECOND (correct); exactly one `NavDisplay`/back stack; no deprecated `PredictiveBackHandler` anywhere. Tabs/sections as pager/`rememberSaveable` enums (not NavKeys) is the deliberate, spec'd design — not flagged.

## CRITICAL

(none — the NavKey-registration drift hunt came back clean)

## HIGH

1. `screens/notifications/NotificationsScreen.kt:117-129` — Nested `Scaffold` + `TopAppBar` inside the shell body
   NotificationsScreen is section content rendered inside `AppShellScreen`'s body, yet wraps itself in its own `Scaffold` with a `TopAppBar` — `mobile-design-system` § "The app shell owns a single Scaffold" says shell-body content "MUST NOT wrap its body in its own `Scaffold` or `TopAppBar`". It renders OK today only because the shell's `consumeWindowInsets(padding)` zeroes the nested Scaffold's insets; the double-inset defect returns if that modifier is ever reordered. Root cause is sequencing: #162 (notifications) merged hours before #167 (design-system substrate) on 2026-06-08, and #167's retrofit swept only the 4 files named in its scenarios. Fix: replace Scaffold/TopAppBar with a plain title row (the spec'd `notifications_title` top bar does not require the `TopAppBar` component). Confidence: high.

2. `screens/notifications/NotificationsUiState.kt:81` + `NotificationsScreen.kt:88,131-134` — Refresh tears down content; two progress indicators; PTR spinner during initial load
   `notificationsUiState()` returns `Loading` whenever `inFlight` — so a pull-to-refresh from `Content` unmounts the list back to the skeleton, AND `isRefreshing = inFlight` is passed to `PullToRefreshBox`, so the initial load shows the in-content spinner + the PTR indicator simultaneously. Violates three `mobile-design-system` § "Canonical list loading and refresh pattern" scenarios verbatim. `NotificationsViewModel` kept the pre-split single `inFlight` while both timeline VMs migrated to `isInitialLoad`/`isRefreshing` (#167) — textbook cross-change drift. Fix: adopt the split-flag VM shape + keep the retained outcome during reload; note `openspec/specs/mobile-notifications-list/spec.md:120` itself encodes the stale pattern ("Loading (fetch in-flight)"), so the fix needs a spec MODIFY too. Confidence: high.

3. `screens/notifications/NotificationsScreen.kt:146-200` — Empty/Error/Loading states are non-scrollable → pull-to-refresh gesture dead from them
   `LoadingState`/`CenteredMessage`/`ErrorState` are plain `Column`/`Box`, not inside a scrollable; `PullToRefreshBox` requires a scrollable child to recognize the gesture, so refresh-from-empty/error doesn't work (the design-system scenario "Pull-to-refresh is available from a non-Content state" fails). Nearby/Global solved this with `*ScrollableState` single-item `LazyColumn` wrappers; notifications never got the treatment. Fix: wrap non-Content states in the same single-item LazyColumn idiom. Confidence: high.

4. `screens/routing/ProactiveTokenRefreshTrigger.kt:42-44` (with `auth/TokenRefresher.kt:99-108`, `network/HttpClientFactory.kt:110-124`) — Proactive refresh never updates the Ktor Auth plugin's cached `BearerTokens`
   The proactive path writes the rotated pair to `TokenStore` only; Ktor's `BearerAuthProvider` caches `loadTokens` until `refreshTokens` runs or `clearToken()` is called — neither happens (no `clearToken` call exists in the module). So after an on-resume proactive refresh, subsequent requests still attach the OLD access token: at expiry the user eats exactly the 401 + reactive-refresh round-trip the feature was built to avoid, plus a redundant second rotation (extra token-family churn). Self-healing (TokenRefresher re-reads the store, so no reuse-detection trip), and the overlap case coalesces correctly via single-flight — but the spec'd preemptive goal (docs/05 §Session line 38) is silently defeated whenever the proactive refresh completes before the next 401. Neither the archived design.md nor the spec discusses the cache, so this is an overlooked interaction, not a decision. Fix sketch: after a successful refresh in the proactive path, `httpClient.authProviders.filterIsInstance<BearerAuthProvider>().firstOrNull()?.clearToken()`. Confidence: medium-high (Ktor 3.x AuthTokenHolder semantics).

5. `screens/auth/SignInScreen.kt:80-81`, `screens/auth/AgeGateScreen.kt:112-115`, `screens/consent/ConsentScreen.kt:79-83`, `screens/post/PostCreationScreen.kt:75-77`, `screens/post/PostDetailScreen.kt:117-138` — Five screens hold ALL state in plain `remember` + composition-scope launches (docs/11 §2.2 legacy pattern)
   No ViewModel, no `rememberSaveable`: on Android config change the composer draft (`content`), the reply draft (`replyContent`), optimistic like state, picked DOB, and consent toggles are all silently reset, and an in-flight submit launched on `rememberCoroutineScope` is cancelled mid-POST (server may have committed → retry risks a duplicate post/reply). PostDetail is the worst case: 10 `remember` vars + 2 effects + 2 launch sites in one composable. These predate the docs/11 baseline (legacy-tolerated), but they're the largest §2.2 migration surface and carry real data-loss bugs today. Fix: migrate per-screen to entry-scoped ViewModels (PostDetail/PostCreation first — they own user-typed drafts). Confidence: high.

## MEDIUM

6. `screens/timeline/NearbyTimelineViewModel.kt:41-48` (same: GlobalTimelineViewModel, NotificationsViewModel) — VMs expose 3 raw `MutableStateFlow`s instead of ONE `StateFlow<XxxUiState>` via `stateIn(WhileSubscribed(5000))`
   docs/11 §2.2 prescribes one UiState StateFlow per screen; here the UiState projection (`nearbyTimelineUiState(outcome, isInitialLoad)`) runs in the composable on every recomposition and the "one state" contract is split across three hot flows. Internally consistent across all 3 VMs (no fork), but a baseline deviation that any new screen will copy. Fix: fold the projection into the VM behind a single `uiState: StateFlow`. Confidence: high.

7. `di/MobileModule.kt` (whole file) + `viewModel { … }` call sites (NearbyTimelineScreen.kt:160, GlobalTimelineScreen.kt:90, NotificationsScreen.kt:82) — ViewModels are invisible to Koin
   Zero VM declarations in DI; screens hand-construct VMs via `viewModel { Xxx(koinInject'd dep) }`. Entry-scoping still works (the ViewModelStore decorator supplies the owner), but docs/11 §2.2/§2.3 prescribe `koinViewModel()` + Koin VM declarations (`navigation<Key> { }` / `koinEntryProvider` where applicable). One more place each new screen must choose a pattern. Fix: declare `viewModelOf(::NearbyTimelineViewModel)` etc. and switch call sites to `koinViewModel()` in one mechanical pass. Confidence: high.

8. `auth/AuthRepository.kt:26,79-81` — `AuthFlow.handleTerminal401()` is dead in production + stale KDoc
   No production caller (only a test fake overrides it); the real terminal-401 path is `TokenRefresher → SessionInvalidator` wired in `HttpClientFactory`. Its KDoc still claims it "signals `RootRouterScreen` to re-route" — the re-route owner has been `SessionExpiryEffect` since #172. A duplicated session-invalidation seam inviting a future second wiring. Fix: delete the interface member (or wire it as the single funnel and document). Confidence: high.

9. `screens/shell/AppShellScreen.kt:97-103,148-155` — Shell fetches the unread badge straight from a repository inside composition effects
   `koinInject<NotificationsFlow>()` + `LaunchedEffect { unreadCount = flow.unreadCount() }` + a `DisposableEffect.onDispose { scope.launch { … } }` re-fetch, held in plain `remember`. docs/11 §2.2: business/data work never launches from composables. The fetch-on-compose / refresh-on-leave TIMING is spec'd (D6), but the holder/launch mechanics are the legacy pattern (and rotation silently re-fires the fetch). Fix: shell-scoped VM owning `unreadCount` with `onSectionLeft()`. Confidence: high.

10. `screens/post/PostCreationScreen.kt:84-95`, `screens/post/PostDetailScreen.kt:155-215` — Double-submit window + inconsistent double-tap defense across features
    `inFlight` is set INSIDE `scope.launch`, so two same-frame taps can both pass the `if (!inFlight)` guard before the first body runs. Auth and Consent backstop this with repo-level `Mutex.tryLock` (→ `Cancelled`); CreatePost and PostDetail (reply POST — non-idempotent) have no backstop → duplicate post/reply possible. Fix: set `inFlight = true` before `launch` AND add the same `tryLock` guard to `CreatePostRepository`/`PostDetailRepository` (pattern parity). Confidence: medium (window is one frame, but the asymmetry vs Auth/Consent is real).

11. `screens/timeline/NearbyTimelineScreen.kt:256-496` vs `screens/timeline/GlobalTimelineScreen.kt:151-356` — ~150 lines of copy-pasted state composables (2nd full copy; 3rd partial in Notifications)
    `LoadingState`, `CenteredMessageState`, `ErrorState`, `SoftLimitBanner`, `PostList`, `*PostCard`, `*ScrollableState`, `postDateLabel` are duplicated near-verbatim; NotificationsScreen re-derives its own diverging (and spec-violating, see #2/#3) variants. docs/11 §4 rule-of-three: extract at the 2nd-3rd use site; no `ui/components/` package exists yet. This duplication is HOW finding #2/#3 happened — the 3rd copy drifted. Fix: extract the shared list-state kit into `ui/components/` as the first §2.1 target-shape move. Confidence: high.

12. `location/LocationGate.kt:20-24` + `screens/timeline/NearbyTimelineScreen.kt:114` — Custom StateFlow holder `remember`'d in composition
    `LocationGate` is exactly the "plain `*Flow`/state holder class" docs/11 §2.2 declares legacy, and being `remember`-scoped it is rebuilt (state → `Loading`, OS permission re-queried) every time the user swipes back to the Nearby page, while the feed VM beside it survives in the HomeRoute store. Works (ON_RESUME replay re-projects fast), but it's the clearest mixed-pattern screen: one screen, three holder kinds (VM + custom holder + remember). Fix: fold gate state into `NearbyTimelineViewModel` or hoist the gate into the HomeRoute scope. Confidence: high.

## LOW

13. All 9 collection sites use `collectAsState()` not `collectAsStateWithLifecycle()` (NearbyTimelineScreen.kt:115,161-163; GlobalTimelineScreen.kt:91-93; NotificationsScreen.kt:83-84) — docs/11 §2.2 prescribes lifecycle-aware collection (available in commonMain via JetBrains lifecycle-runtime-compose). Cost is near-zero today (hot StateFlows), but it blocks `WhileSubscribed(5000)` from ever doing its job once #6 lands. Mechanical sweep. Confidence: high.

14. `items()` calls carry `key` but no `contentType`, and list-bearing UiState fields are `List<T>` not `ImmutableList` (NearbyTimelineScreen.kt:371, GlobalTimelineScreen.kt:238, NotificationsScreen.kt:211; `*UiState.Content`) — docs/11 §2.4. Benign under strong skipping (stable instances from StateFlow), but spec'd. Confidence: medium.

15. `theme/NearYouTheme.kt:59` — `catch (_: Throwable)` around `resolver.preload()` swallows `CancellationException`; every other async catch in the module rethrows CE first ("mirrors AuthApiClient" convention — see NearbyTimelineViewModel.kt:66-68, ProactiveRefreshEffect.kt:36-38). Harmless here (effect teardown), but it's the one convention hole. Confidence: high.

16. `auth/TokenRefresher.kt:73-76` — Leader cancellation poisons followers: `completeExceptionally(CancellationException)` makes every follower's `await()` rethrow CE, silently unwinding the follower's coroutine (e.g. a reactive refresh inside the Auth plugin) even though the follower wasn't cancelled — the original request then fails without `SessionInvalidator` firing, leaving the timeline's `SessionRedirect` placeholder with no redirect. Window is tiny (leader = app-root scope teardown or a popped screen's reactive call). Fix sketch: leader runs the POST in a `NonCancellable`/owned scope, or followers translate a foreign CE into a retry. Confidence: medium.

## STATE-HOLDER INVENTORY

The `*Flow` types are NOT state holders despite the name — they are stateless repository seam interfaces (`AuthFlow`, `ConsentFlow`, `CreatePostFlow`, `NearbyTimelineFlow`, `GlobalTimelineFlow`, `NotificationsFlow`, `PostDetailFlow`; production bindings = the `*Repository` Koin singles; only Auth/Consent hold a re-entrancy `Mutex`). The naming collides with both kotlinx `Flow` and docs/11 §2.2's "legacy `*Flow` holder" vocabulary — a §4 naming-coherence drift worth resolving when the data layer moves to the §2.1 target shape (`XxxRepository` is the docs/11 §2.6 name for this role).

| Screen / surface | State holder(s) | Pattern | Conforms §2.2? |
|---|---|---|---|
| RootRouterScreen | none — `LaunchedEffect` one-shot `AuthFlow.isAuthenticated()` read | composable-launched read | No (minor; one-shot router) |
| SignInScreen | `remember` outcome/inFlight (+ `PendingReturnDestination` read) | ad-hoc remember + scope.launch | No (finding 5) |
| AgeGateScreen | `remember` dob/showPicker/outcome/inFlight | ad-hoc remember + scope.launch | No (finding 5) |
| ConsentScreen | `remember` 3 toggles + outcome/inFlight | ad-hoc remember + scope.launch | No (finding 5) |
| AppShellScreen | `rememberSaveable` Section + `remember` unreadCount + direct `NotificationsFlow` calls | UI state OK; data-from-UI not | Partial (finding 9; Section-as-enum is deliberate) |
| HomeScreen | `rememberSaveable` Tab + `rememberPagerState` | host UI state (tabs ≠ NavKeys, spec'd) | Yes (deliberate exception) |
| NearbyTimelineScreen | `NearbyTimelineViewModel` (entry-scoped) + `LocationGate` (remember'd custom holder) | mixed VM + legacy holder | Partial (findings 6, 12) |
| GlobalTimelineScreen | `GlobalTimelineViewModel` (entry-scoped) | VM, 3 raw StateFlows, no stateIn | Partial (finding 6) |
| NotificationsScreen | `NotificationsViewModel` (entry-scoped) | VM, single `inFlight` (pre-split) | Partial (findings 2, 6) |
| PostCreationScreen | `remember` content/outcome/inFlight | ad-hoc remember + scope.launch | No (findings 5, 10) |
| PostDetailScreen | 10 × `remember` vars, 2 effects, 2 launch sites | ad-hoc remember + scope.launch | No (findings 5, 10) |
| App root | `SessionInvalidator` (CONFLATED channel) + `ProactiveTokenRefreshTrigger` | cross-cutting signal, spec'd D1/D3 | Yes (documented exception; not a VM→UI bus) |
| Cross-cutting | `PendingSignupIdentity`, `PendingReturnDestination` (Koin singles, in-memory) | deliberate PII-safe holders | Yes (documented exception) |

Notes for the migration decision: (a) PostDetailScreen's own `Scaffold` is legitimate — it is a root-stack overlay, not shell-body content, and must own its insets (SignIn/AgeGate/Consent/PostCreation do the same via `safeContentPadding()`); (b) theme conforms (FontFamilyResolver.preload pattern present, all colors via scheme/`LocalNearYouColors`, dark wired); (c) DI singles are appropriately stateless; no `GlobalScope`, no ad-hoc `CoroutineScope()`, no hardcoded `Dispatchers.*` anywhere in commonMain.
