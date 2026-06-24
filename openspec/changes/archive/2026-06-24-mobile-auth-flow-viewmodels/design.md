## Context

Holistic-audit finding 05-#5 (2026-06-10) flagged five route-level screens that held all state in `remember` + `rememberCoroutineScope().launch`. Between then and now the high-value half — `PostCreationScreen`, `PostDetailScreen` — was migrated to entry-scoped ViewModels by sibling changes, and the mobile app's de-facto state-holder convention crystallized: **every** route screen now owns an androidx `ViewModel` in commonMain, resolved via `viewModel { … }` scoped to its Nav3 entry, exposing ONE `uiState: StateFlow<XxxUiState>` projected from a private `MutableStateFlow<VmState>` through `.map { it.toUiState() }.stateIn(viewModelScope, WhileSubscribed(5_000), initial)`. The canonical reference is `UsernameCustomizationViewModel` (a pushed-route form VM with a one-shot success event) and `AppealViewModel`.

The three remaining legacy holdouts are `SignInScreen`, `AgeGateScreen`, `ConsentScreen`. All three already factor their outcome→UI mapping into **pure, Compose-free** projections (`signInUiState` / `ageGateUiState` / `consentUiState`) and their navigation decisions into **pure seams** (`handleAgeGateTerminalOutcome` / `handleConsentTerminalOutcome`), each with existing `commonTest` coverage. What is NOT factored out is *ownership*: the mutable state (`outcome`, `inFlight`, picked DOB, consent toggles) lives in `remember`, and the network call launches on `rememberCoroutineScope()`. Both are lost on an Android configuration change.

## Goals / Non-Goals

**Goals:**
- Lift the three screens' mutable state and async work into entry-scoped ViewModels so state survives configuration change and in-flight submits run on `viewModelScope`.
- Preserve **exactly** today's observable behavior: same outcomes, banners, CTA states, navigation transitions, PII discipline, double-tap guards, identity-holder lifecycle, snapshot write.
- Build the new VMs on the established single-`stateIn` shape (audit 05-#6 for the new code).
- Keep the pure projections/seams as free functions so their tests stay green unchanged.

**Non-Goals:**
- **No `koinViewModel()` conversion.** Audit 05-#7 (declare VMs in Koin + switch call sites to `koinViewModel()`) is explicitly out of scope: the codebase has ~17 VMs all constructed via `viewModel { … }`; converting only these 3 would *fork* the pattern, and converting all 17 is a separate, larger, app-wide decision (it also surfaces a docs/11 §2.2-vs-code divergence worth resolving on its own). This change matches the established convention, not docs/11's aspirational `koinViewModel()` wording.
- **No timeline-VM retrofit.** Audit 05-#6 for the *existing* `NearbyTimelineViewModel` / `GlobalTimelineViewModel` / `NotificationsViewModel` (still multi-`StateFlow`) is a risky, heavily-tested retrofit the finding itself sized "for its own PR" — left as a separate backlog item.
- No backend, admin, DI-graph, Flyway, or dependency changes. No UI-layout/mockup changes (pixels unchanged).

## Decisions

### D1 — VM shape mirrors `UsernameCustomizationViewModel`
Each VM holds a `private data class VmState(...)` in a `private val state = MutableStateFlow(VmState())`, and exposes `val uiState: StateFlow<XxxUiState> = state.map { it.toUiState() }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), state.value.toUiState())`. `toUiState()` delegates to the **existing** pure projection. Each user action is a VM method that does `state.update { … }` and, for async work, `viewModelScope.launch { … }` with the `try { … } catch (c: CancellationException) { throw c } catch (_: Throwable) { …Transport/Network… }` + `ensureActive()` convention. _Alternative considered:_ keep three raw `MutableStateFlow`s (the older timeline-VM shape). Rejected — the audit's own 05-#6 target is the single-`stateIn` shape, and the new VMs should not seed a second pattern.

### D2 — Screens resolve VMs via `viewModel { … }`, collect with `collectAsStateWithLifecycle`
`val viewModel = viewModel { SignInViewModel(authFlow, pendingSignupIdentity, pendingReturnDestination) }` etc. — Koin deps are `koinInject()`-ed at the call site and passed in, exactly like `AppealScreen`/`PaywallScreen`/`NotificationsScreen`. The screen reads `val ui by viewModel.uiState.collectAsStateWithLifecycle()`. The `viewModel { }` factory resolves against the `LocalViewModelStoreOwner` the Nav3 `rememberViewModelStoreNavEntryDecorator` provides at runtime (and the default test activity provides under Robolectric — confirmed by the passing `PostCreationScreenTest`), so the VM is entry-scoped and cleared on pop.

### D3 — One-shot navigation as nullable VM state (no event streams)
Today each screen drives navigation from `LaunchedEffect(outcome)` + callbacks. The VMs instead expose a nullable one-shot nav field (e.g. `SignInUiState.navigation: SignInNavTarget?` = `Home` | `AgeGate`, `AgeGateUiState`-side `navigation: …`, `ConsentUiState.done: Boolean`/nav field), consumed by a `LaunchedEffect(nav) { if (nav != null) { invoke the screen's hoisted callback; viewModel.onNavigationHandled() } }`. This is docs/11 §2.2's "one-shot events are state, not streams." The side effects that today sit beside navigation move **into the VM**, which gains the dep:
- **SignIn:** on `NoAccount(idToken)` the VM calls `pendingSignupIdentity.set(idToken)` then raises `navigation = AgeGate`; after the screen consumes it the VM clears both the nav field and the consumed `outcome` (so system-back to a retained `SignInRoute` cannot re-fire).
- **AgeGate:** on a terminal outcome the VM delegates to the existing pure `handleAgeGateTerminalOutcome(outcome, pendingSignupIdentity, onSignedUp, onExitToSignIn)` — passing as the two navigation callbacks lambdas that do `state.update { … }` to raise the matching nav one-shot (the seam's *signature is unchanged*, so `AgeGateOutcomeHandlerTest` stays green: VM lambdas are drop-in equivalents of the test's counter lambdas). The absent-identity process-death guard moves to VM `init`, which reads the holder **synchronously** (`PendingSignupIdentity` is a Koin `single`, not an async source): if absent, `clear()` + raise the SignIn re-route one-shot + set `identityAbsent` in the **initial** `VmState`. Because `init` runs before the first composition, the screen reads `identityAbsent` from `uiState` and early-returns **before any other branch** — the signup form never renders even one frame (strictly better than today's `LaunchedEffect`-based guard). The screen MUST gate on `uiState`'s `identityAbsent`, never re-derive the guard from a `LaunchedEffect`.
- **Consent:** on submit-`200` the VM writes `snapshotStore.write(ConsentSnapshot(analytics, crash, ads))` before raising the done one-shot — same ordering as today.

_Alternative considered:_ keep navigation `LaunchedEffect(outcome)` reading a VM-exposed `outcome` and keep the side effects in the composable. Rejected — that leaves `pendingSignupIdentity.set`/`clear` and `snapshotStore.write` launching from composition (the very §2.2 violation we're removing) and re-introduces the "config change cancels the side effect" window.

### D4 — Pure projections/seams stay free functions
`signInUiState`, `ageGateUiState`, `consentUiState`, `handleAgeGateTerminalOutcome`, `handleConsentTerminalOutcome`, `isDobSubmittable`, `dobFromUtcMillis`, `systemToday` are unchanged free functions; the VMs delegate to them. Their existing `commonTest`s (`SignInUiStateTest`, `AgeGateUiStateTest`, `AgeGateOutcomeHandlerTest`, `ConsentUiStateTest`, `ConsentOutcomeHandlerTest`) need no edits. `AgeGateViewModel` takes `today: LocalDate = systemToday()` so DOB validation stays deterministically testable.

### D5 — `showPicker` and toggle defaults
`AgeGateScreen`'s `showPicker` (dialog visibility) moves into `VmState` for a single source of truth (rotation keeps the picker open). `ConsentViewModel` takes `initialAnalytics/Crash/Ads` constructor params (defaults false/true/false) seeding `VmState`, exactly as the screen does today (injectable for tests).

## Risks / Trade-offs

- **Robolectric `*ScreenTest` async timing** → the submit now runs on `viewModelScope` (a real coroutine), async w.r.t. compose. Mitigation: poll with `waitUntil` for post-submit assertions, the documented pattern already used by `PostCreationScreenTest` ("the real submit runs on viewModelScope … async w.r.t. compose").
- **VM resolution under test** → `viewModel { }` needs a `ViewModelStoreOwner`. Mitigation: confirmed present under the Robolectric runner for the already-migrated screens; no harness change needed.
- **Pre-existing iOS flow-test reds (#348, 22/778 DI drift since #234)** → the SignIn/AgeGate/Consent iOS flow tests may sit in that red set. Mitigation: re-run `:mobile:app:iosSimulatorArm64Test` and confirm this change does not *add* failures; pre-existing reds stay tracked in #348, not in scope here.
- **Behavior-drift risk during the lift** → the subtle bits (NoAccount `outcome=null` reset, terminal-exit `clear()` only on terminal not retryable, `snapshotStore.write` only on `Success`) must be preserved exactly. Mitigation: reuse the pure seams verbatim; cover each in the new VM unit tests; keep the existing ScreenTest/iOS assertions as the behavioral backstop.
- **`docs/12` cohesion** → this capability is mobile-only by nature (a client state-holder refactor with no wire/admin counterpart); no deferred-layer requirement is needed. Stated explicitly so the cohesion gate reads as satisfied, not skipped.

## Migration Plan

Pure refactor, no runtime migration. Order: Post-precedent VMs first verifies the pattern → write `SignInViewModel` + lift `SignInScreen` → `AgeGateViewModel` + `AgeGateScreen` → `ConsentViewModel` + `ConsentScreen`; add VM unit tests alongside each; run the mobile gate (`testDevDebugUnitTest` + `testDevReleaseUnitTest`) and `iosSimulatorArm64Test`; manual verify-loop §B on an Android device incl. an explicit **rotate-mid-flow** check (pick DOB → rotate → DOB retained; toggle consent → rotate → toggles retained). Rollback = revert the branch (no schema/state to unwind).

## Open Questions

_None._ The scope, pattern, and out-of-scope boundaries (no `koinViewModel`, no timeline retrofit) are settled above.
