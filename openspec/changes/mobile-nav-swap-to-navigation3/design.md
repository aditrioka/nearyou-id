## Context

`:mobile:app` navigates on **Voyager `1.1.0-beta03`** + `voyager-koin` (see [`gradle/libs.versions.toml`](../../../gradle/libs.versions.toml) lines 45-51 and [`mobile/app/build.gradle.kts`](../../../mobile/app/build.gradle.kts) lines 73-74). The current shape (mapped before this change):

- `App.kt` — `NearYouTheme { Navigator(RootRouterScreen()) { navigator -> SessionExpiryEffect(navigator); CurrentScreen() } }`.
- **6 screens**, each a Voyager `Screen` with `@Composable override fun Content()`: `RootRouterScreen` (start destination; token-presence routing via `replaceAll`), `SignInScreen` (`replaceAll(HomeScreen())` on success, `push(AgeGateScreen(idToken))` on 404), `AgeGateScreen(idToken: String)` (the only screen carrying data across a boundary; `replaceAll`/re-route on outcomes), `HomeScreen` (hosts `NearbyTimelineScreen` + a FAB that `push`es `PostCreationScreen`), `PostCreationScreen` (`pop()` on success), `NearbyTimelineScreen` (navigation-free; embedded by `HomeScreen`).
- Routing verbs in use: `navigator.replaceAll(...)` (auth boundaries), `push(...)` / `pop()` (in-auth), `LocalNavigator.currentOrThrow`. No Voyager `ScreenModel` is used — screens are stateless and resolve singletons via `koinInject<T>()`. The only screen carrying constructor data is `AgeGateScreen(idToken)`.
- **15 nav-touching tests**: 7 Robolectric `*ScreenTest` (wrap a screen in `Navigator(Screen())`), 4 iOS `*FlowIosTest`, 4 pure `*UiStateTest` (no navigator).

The Voyager pin was always provisional. The catalog comment is explicit: *"Re-evaluate when Voyager cuts a stable 1.1.0 — track at the `mobile-app-scaffold-replace-wizard` archive notes."* Voyager never cut stable 1.1.0; its most recent release of any kind was an alpha in 2024. The archived scaffold `design.md` Decision 1 pre-authorized the swap: *"If state-restoration becomes load-bearing later, a swap-from-Voyager is mechanical (one file per screen) and qualifies as its own change."* This change executes that swap toward **Navigation 3**, the Compose-first navigation library backed by both Google (`androidx.navigation3`, stable) and JetBrains (the CMP port `org.jetbrains.androidx.navigation3`, supporting Android + iOS + desktop + web since CMP 1.10).

The repo is on Kotlin `2.3.20`, Compose Multiplatform `1.11.1` (satisfies Nav3's CMP ≥ 1.10 requirement), Koin `4.1.0` (satisfies `koin-compose-navigation3`'s Koin-4.x requirement), and `kotlinx-serialization 1.9.0` (powers the iOS `NavKey` polymorphic serialization). Every prerequisite is already on the classpath.

## Goals / Non-Goals

**Goals:**
- Replace Voyager with Navigation 3 (JetBrains CMP port) as the single navigation host, with **observable behavior preserved** across all 5 affected capabilities.
- Establish the Nav3 idioms the rest of the app will inherit: typed `NavKey` routes, an `entryProvider`, a developer-owned `rememberNavBackStack`, and the iOS-safe polymorphic serialization pattern.
- Preserve the id_token-never-persisted privacy contract — and newly guard it against Nav3's saveable back stack.
- Keep both Android and iOS targets green; keep the Koin DI graph and every existing string/PII/test invariant intact.

**Non-Goals:**
- Adaptive multi-pane Scenes / list-detail layouts (no tablet/foldable target yet → follow-up `mobile-nav3-adaptive-scenes`).
- The Nearby/Following/Global **tab host** with multiple back stacks (already forward-referenced by `mobile-post-creation` → follow-up `mobile-home-tab-host`).
- Deep-link routing (Nav3 `DeepLinkRequest`/`DeepLinkMatcher`; post-MVP).
- Per-entry **ViewModel** scoping (`rememberViewModelStoreNavEntryDecorator()` + `lifecycle-viewmodel-navigation3`) — deferred; no screen uses a ViewModel today.
- Nav3 `ResultEventBus` cross-screen results (the deferred Nearby-refresh-on-return keeps its own follow-up).
- Any copy / string / backend / DB / API change.

## Decisions

### Decision 1: Navigation framework — **Navigation 3 (JetBrains CMP port)**

**Choice:** Adopt `org.jetbrains.androidx.navigation3:navigation3-ui` (pulls `navigation3-runtime` + `navigation3-common` transitively) + `io.insert-koin:koin-compose-navigation3`, replacing `voyager-navigator` + `voyager-koin`. The exact version is pinned at `/opsx:apply` per the mandatory pre-implementation library re-check (see Decision 9).

**Alternatives considered:**
- **Stay on Voyager `1.1.0-beta03`.** Rejected — the pin was always "re-evaluate when stable 1.1.0 ships," and it never did; the library's release cadence has stalled (last release an alpha in 2024). Building more screens on a stalled pre-stable substrate compounds the very debt this re-evaluation exists to retire.
- **Decompose.** Rejected at scaffold time (archived Decision 1: heavier ceremony, MVI-flavored, steep curve for a solo operator) and the same reasoning holds; nothing has changed to reopen it.
- **AndroidX Navigation 2 multiplatform (`org.jetbrains.androidx.navigation:navigation-compose`).** A valid KMP nav2 option, but it is the *previous* generation; JetBrains + Google are investing forward in Nav3. Choosing nav2 now would itself be a near-term re-migration. Rejected on directional grounds.
- **Vanilla state-based nav.** Rejected at scaffold time (doesn't scale past 3-4 screens); we now have 6 and growing.

**Why Navigation 3:**
- Official Compose-first direction from both Google and JetBrains; CMP port covers Android + iOS + desktop + web.
- Developer-owned back stack (a `SnapshotStateList<NavKey>`) — routing is plain list mutation, which maps cleanly onto our existing `replaceAll`/`push`/`pop` semantics.
- First-class Koin integration (`koin-compose-navigation3`, Koin 4.x) — our `koinInject` pattern is preserved verbatim; `koinViewModel` entry-scoping is available later without rework.
- Smallest-surface moment: 6 screens, simple stack semantics, one screen carrying data — the archived scaffold decision's "mechanical, one file per screen" estimate holds.

**Trade-off accepted — the CMP port is pre-stable (alpha).** On Android, Nav3 is stable; the **multiplatform** artifact we consume is alpha. Mitigations: (a) the project has standing precedent for pre-stable pins (`material3 = 1.10.0-alpha05`, OTel alpha BOMs, and Voyager beta itself); (b) the surface is tiny, so alpha API churn (e.g. the `onBack`→`onBackCompleted` rename seen in Nav3 release notes) is cheap to absorb; (c) the exact version + stability is re-verified at `/opsx:apply` per Decision 9; (d) the runtime semantics are shared with the Android-stable core. **Substrate research date: 2026-06-05** — Nav3 confirmed the official Google + JetBrains direction; Voyager confirmed stalled (no stable 1.1.0; last release alpha 2024); CMP-port version landscape ambiguous (`1.0.0-alpha05` per the [KMP doc](https://kotlinlang.org/docs/multiplatform/compose-navigation-3.html) vs a `1.1.x` line on Maven) → resolved at apply.

### Decision 2: Route model — **typed `NavKey` entities + `entryProvider`; screens become plain composables**

**Choice:** Define one `NavKey` per destination in the existing `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/routing/` package (the home of `RootRouterScreen` + `SessionExpiryEffect` today): `RootRoute`, `SignInRoute`, `HomeRoute`, `AgeGateRoute`, `PostCreationRoute`. An `appEntryProvider(backStack, ...)` (the `entryProvider { entry<RootRoute> { ... } ... }` DSL) maps each key to its screen composable. Each screen converts from `class XScreen : Screen { @Composable override fun Content() }` to `@Composable fun XScreen(...)`, receiving the navigation affordances it needs (a re-route/`navigateTo`/`pop` lambda, or the back stack) as parameters rather than pulling `LocalNavigator.currentOrThrow`. (Placement rationale: `screens/routing/**` is already an auth-identifier-allowlisted path — see Decision 8.)

**Why:** typed routes preserve the existing "each screen is a typed entity, not a stringly path" invariant from the `mobile-app-scaffold` nav-host requirement. Hoisting navigation into lambdas (instead of a `LocalNavigator` CompositionLocal) makes the screen composables directly testable without a nav host and keeps the screens free of host-specific imports — matching the project's existing "pure, testable seam" idiom.

**Note on `RootRoute` as start + replace target:** the back stack is seeded with `RootRoute`; `RootRouterScreen`'s `LaunchedEffect` resolves token presence and calls `backStack.replaceAll(HomeRoute)` or `replaceAll(SignInRoute)` (Decision 6). This preserves the "router replaces itself at launch; splash renders while the read is in-flight" behavior exactly.

### Decision 3: iOS back-stack serialization — **polymorphic `NavKey` `SerializersModule` via `SavedStateConfiguration`**

**Choice:** All `NavKey` routes are `@Serializable` (`@Serializable data object RootRoute : NavKey`, etc.). A single `SavedStateConfiguration` carrying a `SerializersModule { polymorphic(NavKey::class) { subclass(RootRoute::class, RootRoute.serializer()); subclass(SignInRoute::class, ...); ... } }` is passed to `rememberNavBackStack(config, RootRoute)`. This lives in `screens/routing/AppNavSerialization.kt`.

**Why:** Nav3 relies on reflection-based serialization on Android (JVM), which is **unavailable on non-JVM targets** (iOS, web, desktop). Per the [JetBrains CMP-Nav3 doc](https://kotlinlang.org/docs/multiplatform/compose-navigation-3.html), the canonical pattern for non-JVM back-stack saveability is explicit polymorphic registration. Without it, the iOS back stack is not saveable and state restoration breaks. A `commonTest` serialization round-trip asserts every route key serializes + deserializes via the module.

### Decision 4: id_token holder — **in-memory `PendingSignupIdentity`, never on a `NavKey`** (security, load-bearing)

**Choice:** The verified Google `id_token` carried from the sign-in no-account path to the age-gate flow is held in an **in-memory** `PendingSignupIdentity` holder (a Koin `single` exposing `set(idToken)` / `peek(): String?` / `clear()`), **NOT** as a property on `AgeGateRoute`. `AgeGateRoute` is a parameterless marker key. The sign-in no-account path calls `pendingSignupIdentity.set(idToken)` then `backStack.add(AgeGateRoute)`; `AgeGateScreen` reads the token from the holder.

**Holder lifecycle — peek, not consume-on-read; clear on terminal exits.** The read is a **non-clearing `peek()`**, not a read-and-clear `consume()`: the `401 invalid_id_token` path fetches a *fresh* token from Google (it does not re-read the holder), but a retryable network/5xx error lets the user resubmit, and that resubmit must re-read the same identity — so consuming on first read would break in-screen retry. Instead the holder is explicitly `clear()`ed on every **terminal** transition out of the age-gate flow (`Success` → Home, `AccountExists` → SignIn, and the absent-identity re-route → SignIn). This minimizes the in-memory residency window without breaking retry (surfaced by the security-and-invariant review lens; the prior `consume()` framing was ambiguous against the one-retry requirement). The token is never persisted, logged, or rendered regardless; the `clear()`-on-terminal is defense-in-depth on top of that.

**Why (the migration's most important interaction):** the `mobile-age-gate` spec requires the id_token be *"held in in-memory navigation state only (never persisted)"* and *"MUST NOT be logged and MUST NOT be rendered."* Under Voyager, the back stack was never serialized, so `AgeGateScreen(idToken)` satisfied this trivially. Under Nav3, the back stack **is** serialized on iOS (Decision 3) — so a token on `AgeGateRoute` would be written to `SavedState`, **violating the privacy contract**. Keeping the token in an in-memory holder reproduces the Voyager privacy guarantee exactly.

**Process-death contract preserved (and strengthened):** the existing spec requires "Process death on AgeGateScreen → relaunch routes to SignInScreen." With Nav3's saveable back stack, a restored stack could land on `AgeGateRoute` after process death — but the in-memory `PendingSignupIdentity` is gone. `AgeGateScreen` therefore guards: if the holder yields `null` on entry, it emits a one-shot re-route to `SignInRoute` (equivalently: the restored stack is collapsed back to `RootRoute` on a missing pending identity). Either way the observable outcome — the user lands on sign-in, no stale-token state, no crash, no stuck splash — is identical to today. **Negative-guard test:** serialize a back stack containing `AgeGateRoute` and assert the serialized bytes contain no token substring.

**Alternatives considered:**
- **`@Serializable data class AgeGateRoute(@Transient val idToken: String = "")`.** Rejected — `@Transient` round-trips to an empty token, forcing the same missing-identity guard anyway, while inviting a future contributor to drop the `@Transient` and silently leak the token. The holder makes the never-serialized property structural, not annotation-dependent.

### Decision 5: State retention — **`rememberSaveableStateHolderNavEntryDecorator()` now; ViewModel-store decorator deferred**

**Choice:** `NavDisplay.entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator())`. Defer `rememberViewModelStoreNavEntryDecorator()` (and the `org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-navigation3` artifact) until the first ViewModel-backed screen.

**Why:** the saveable-state-holder decorator gives each `NavEntry` its own `SaveableStateRegistry`, so per-screen `rememberSaveable` state (the composer draft; the `NearbyTimelineScreen` location-gate state machine) is correctly scoped per entry and retained while the entry sits in the back stack — reproducing Voyager's per-`Screen` state retention. The ViewModel-store decorator is only meaningful when a screen scopes a `ViewModel`/`koinViewModel` to its entry; none do (all are stateless + `koinInject` singletons, which resolve from the global Koin scope regardless of nav). Adding the artifact + decorator now would be unused surface. (Known caveat tracked for the future adopter: Koin issue [#2235](https://github.com/InsertKoinIO/koin/issues/2235) — `koinViewModel` not cleared on back-stack pop with the VM-store decorator; irrelevant until we adopt entry-scoped ViewModels.)

### Decision 6: Routing helpers — **`NavBackStack.replaceAll(key)` extension + hoisted lambdas**

**Choice:** Add `fun NavBackStack.replaceAll(key: NavKey) { clear(); add(key) }` in `screens/routing/`. Auth-boundary transitions (`RootRouter` → Home/SignIn, sign-in success → Home, age-gate success → Home, account-exists → SignIn, session-expiry → SignIn) call `replaceAll`. In-auth transitions use `backStack.add(key)` (push) and `backStack.removeLastOrNull()` (pop). Screens receive narrow lambdas (`onNavigateToAgeGate: () -> Unit`, `onPostCreated: () -> Unit`, etc.) wired by the `entryProvider`, so a screen file names only the transition it triggers, not the whole back stack.

**Why:** `replaceAll` preserves the exact observable semantics of Voyager's `replaceAll` (back stack cleared to a single entry — no back-navigation across an auth boundary). Hoisted lambdas keep each screen testable in isolation and keep auth-flow identifiers (route names) localized.

### Decision 7: `SessionExpiryEffect` + `App()` host — **back-stack-driven re-route**

**Choice:** `App()` becomes `NearYouTheme { val backStack = rememberNavBackStack(navConfig, RootRoute); SessionExpiryEffect(backStack); NavDisplay(backStack = backStack, onBack = { backStack.removeLastOrNull() }, entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator()), entryProvider = appEntryProvider(backStack, ...)) }`. `SessionExpiryEffect(backStack)` collects `SessionInvalidator.sessionExpired` and calls `backStack.replaceAll(SignInRoute)` (replacing today's `navigator.replaceAll(SignInScreen())`), preserving the "terminal 401 re-routes to sign-in from any foreground screen" behavior. Both files stay under `screens/routing/**` (a carved-out auth-flow path) so `App.kt` stays free of auth-flow identifiers.

### Decision 8: Nav-model placement — **`screens/routing/**` (already allowlisted); no spec change to the carve-out**

**Choice:** Place the entire Nav3 model — route `NavKey`s (`RootRoute`, `SignInRoute`, `HomeRoute`, `AgeGateRoute`, `PostCreationRoute`), `appEntryProvider`, the polymorphic `SerializersModule` / `SavedStateConfiguration`, the `NavBackStack.replaceAll` helper, and the `PendingSignupIdentity` holder — under `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/routing/`, the path that already houses `RootRouterScreen` + `SessionExpiryEffect`. The `mobile-app-scaffold` auth-identifier allowlist is therefore **NOT modified**.

**Why:** the existing carve-out already lists `screens/routing/**`, `screens/auth/**`, `auth/**`, `network/**`, `di/**`, `config/**`. The only nav files that name auth-flow identifiers are `SignInRoute` / `AgeGateRoute` (match `SignIn`/`signin`) and `PendingSignupIdentity` (auth identity) — all of which land in the already-allowlisted `screens/routing/**`. So the scaffold's negative grep scenario ("Auth-flow identifiers are permitted only inside this change's carved-out paths") stays green with no edit. This is preferred over creating a dedicated `navigation/` package + extending the allowlist: that path would force a full MODIFIED restatement of a long, scenario-dense requirement (lines 110-159 of the scaffold spec) purely to add one directory — error-prone churn for no behavioral gain. `screens/routing/` is the semantically correct home anyway (routing is routing). The non-auth route keys (`RootRoute`/`HomeRoute`/`PostCreationRoute`) are not auth identifiers and are unconstrained, but co-locating them keeps the nav model in one discoverable place.

### Decision 9: Version pin — **deferred to `/opsx:apply` with the mandatory re-check**

**Choice:** Do NOT hardcode the Nav3 CMP version in this proposal. At `/opsx:apply`, run the pre-implementation library re-check (`WebSearch` dated to the apply date) to resolve the exact `org.jetbrains.androidx.navigation3` version + `koin-compose-navigation3` version + whether `navigation3-ui` alone (with transitive `-runtime`/`-common`) is the correct coordinate set, then pin in `gradle/libs.versions.toml` with a Nav3 rationale comment that replaces the current Voyager "re-evaluate when stable" comment.

**Why:** the proposal-phase research (2026-06-05) found version ambiguity (`1.0.0-alpha05` in the KMP doc vs a `1.1.x` line on Maven). The substrate-introducing re-check is exactly the gate for resolving this immediately before the first feat commit, so the pin reflects current-day ecosystem state rather than a proposal-day snapshot.

### Decision 10: Test-harness migration — **NavDisplay/back-stack harness; pure tests unchanged**

**Choice:** Re-wire the 7 Robolectric `*ScreenTest` from `KoinContext { NearYouTheme { Navigator(Screen()) } }` to either (a) compose the screen composable directly with a fake back stack + recording nav lambdas (preferred for screens that only trigger a transition), or (b) host the real `appEntryProvider` in a `NavDisplay` when the test asserts an actual route transition (e.g. the Home-FAB-pushes-composer case, the RootRouter routing cases). The 4 iOS `*FlowIosTest` adapt the same way. The 4 pure `*UiStateTest` are unaffected (no navigator). Add: a `NavKeySerializationTest` (round-trip every route via the polymorphic module) and a `PendingSignupIdentityNotSerializedTest` (the Decision-4 negative guard). The Release-variant `*ScreenTest` exclude in `build.gradle.kts` is preserved (and any new `*ScreenTest` added to it per the established rule).

## Risks / Trade-offs

- **Alpha CMP-Nav3 under core navigation.** → Mitigated by the pre-stable-pin precedent, the tiny surface, the apply-phase re-check (Decision 9), and shared semantics with the Android-stable core. The biggest realistic cost is an API rename on a future version bump, cheap to absorb across 6 screens.
- **iOS back-stack restoration is the behavior most changed by the swap** (Voyager didn't serialize; Nav3 does). → The id_token holder (Decision 4) + the missing-identity guard keep the observable process-death contract identical, with a negative-guard test pinning the privacy invariant. Manual iOS-sim process-death verification is in `tasks.md`.
- **`entryProvider` lambda wiring is new boilerplate.** → Contained in one `navigation/` package; each screen file shrinks (no `Screen`/`Content()` ceremony), netting roughly flat LOC.
- **Allowlist drift.** → Avoided by placing the nav model in the already-allowlisted `screens/routing/**` (Decision 8); the scaffold's auth-identifier grep scenario needs no edit and stays green. The security-and-invariant review lens double-checks that no auth-flow-named nav file escaped to a non-carved-out path.
- **Koin VM-clear-on-pop issue [#2235].** → Not triggered (VM-store decorator deferred, Decision 5); noted for the future adopter.

## Migration Plan

- **No runtime migration.** Pure mobile-side substrate swap; no DB, no API contract, no infra, no backend deploy. **Pre-archive staging smoke = N/A** (no backend/staging runtime impact); the equivalent gate is local Android `assembleDebug` + iOS framework-link + the lint/test suite.
- **Local verification gates (must pass before push):**
  - `./gradlew :mobile:app:assembleDebug` (Android).
  - `./gradlew :mobile:app:linkPodDebugFrameworkIosSimulatorArm64` (iOS framework link — Nav3-on-iOS is the load-bearing target; verify locally since CI is Linux-only).
  - `./gradlew :mobile:app:testDevDebugUnitTest :mobile:app:testDevReleaseUnitTest` (flavor-qualified; the Release variant guards the `*ScreenTest` exclude).
  - `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` (project-wide lint + tests per CLAUDE.md pre-push gate).
  - Manual: Android device + iOS sim — auth-boundary routing, FAB→composer→pop, and AgeGate process-death → SignIn.
- **Rollback:** revert the squash-merge commit on `main`. No deployed state; no data. Dependent in-flight mobile changes (if any) rebase onto the reverted state.

## Open Questions

- **Exact Nav3 CMP version + coordinate set.** Resolved at `/opsx:apply` per Decision 9 (re-check). Records: the `navigation3` version, `koin-compose-navigation3` version, and whether `navigation3-ui` alone suffices (transitive `-runtime`/`-common`) or `-runtime` must be declared explicitly.
- **Screen nav-affordance shape — lambdas vs passing the back stack.** Default to narrow per-transition lambdas (Decision 6) for testability; if a screen needs richer back-stack introspection, pass the back stack to that entry only. Resolve per-screen during `/opsx:apply`; no spec impact (the spec asserts observable routing, not the lambda signature).
