## Why

Three mobile entry screens — `SignInScreen`, `AgeGateScreen`, `ConsentScreen` — still hold ALL screen state in plain `remember { mutableStateOf(...) }` plus composition-scope `rememberCoroutineScope().launch`, the docs/11 §2.2 legacy pattern. On an Android configuration change (rotation, dark-mode flip, font-scale, fold) the composer silently resets the user's picked date-of-birth, the three consent toggles, and the in-flight/outcome state, and an in-flight sign-in/signup/consent submit launched on the composition scope is **cancelled mid-POST** — the server may have committed, so a retry risks a duplicate signup/consent write. This is the remaining surface of holistic-audit finding **05-#5** (the `PostCreationScreen`/`PostDetailScreen` half already shipped via sibling changes; those two now own VMs). Every other route-level screen in `:mobile:app` already migrated to an entry-scoped androidx `ViewModel`; these three are the last legacy holdouts and carry real data-loss bugs today.

## What Changes

- Add `SignInViewModel`, `AgeGateViewModel`, `ConsentViewModel` (androidx `ViewModel` in commonMain), each owning the state its screen holds in `remember` today, resolved via `viewModel { … }` scoped to the Nav3 entry — so screen state **survives configuration change** and an in-flight submit runs on `viewModelScope` (not cancelled by recomposition/config change).
- Each VM follows the established canonical shape (the `UsernameCustomizationViewModel`/`AppealViewModel` precedent): a private `MutableStateFlow<VmState>` projected to ONE `uiState: StateFlow<XxxUiState>` via `.map { it.toUiState() }.stateIn(viewModelScope, WhileSubscribed(5_000), initial)` (honoring audit **05-#6**'s single-`stateIn` shape for the new code), CE-rethrow + `ensureActive()` after each async hop. This is **not** a `koinViewModel()` migration — construction stays `viewModel { … }`, the de-facto convention across the existing ~17 VMs (audit 05-#7's global `koinViewModel` conversion is explicitly out of scope; see `design.md`).
- Side effects entangled with navigation move into the VMs (which gain the needed Koin deps), preserving today's exact behavior: `SignInViewModel` sets `PendingSignupIdentity` on the no-account one-shot; `AgeGateViewModel` clears `PendingSignupIdentity` on every terminal exit (reusing the pure `handleAgeGateTerminalOutcome` seam) and raises the process-death absent-identity re-route; `ConsentViewModel` writes `ConsentSnapshotStore` on submit-`200` (reusing `handleConsentTerminalOutcome`).
- One-shot navigation becomes nullable VM state consumed via an `onNavigationHandled()`-style callback (docs/11 §2.2: "one-shot events are state, not streams"), never a `Channel`/`SharedFlow` event bus.
- The existing **pure** projections (`signInUiState` / `ageGateUiState` / `consentUiState`) and pure seams (`handleAgeGateTerminalOutcome` / `handleConsentTerminalOutcome` / `isDobSubmittable` / `dobFromUtcMillis`) stay as free functions — the VMs delegate to them — so their `commonTest` coverage stays green unchanged.
- Add three `commonTest` VM unit tests modeled on `UsernameCustomizationViewModelTest` (state retention, in-flight guard, outcome→state, one-shot nav consume, side-effect ordering). The existing Robolectric `*ScreenTest`s and iOS flow tests keep rendering the same screens (same testTags/copy/callbacks) and are adjusted only where the now-`viewModelScope`-async submit needs `waitUntil` polling.

No backend or admin layer is touched — this is a mobile-only state-holder refactor with identical observable behavior plus a new config-change-survival guarantee (a vertical slice that is mobile-only by nature; see `docs/12` cohesion note in `design.md`).

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `mobile-auth-signin`: ADD a requirement that `SignInScreen` state is owned by an entry-scoped `SignInViewModel` (survives configuration change; sign-in ceremony runs on `viewModelScope`; no-account identity hand-off and outcome-clear preserved).
- `mobile-age-gate`: ADD a requirement that `AgeGateScreen` state (picked DOB, outcome, in-flight) is owned by an entry-scoped `AgeGateViewModel` (the picked DOB survives configuration change; signup runs on `viewModelScope`; the terminal-exit identity-clear and absent-identity re-route are owned by the VM via the existing pure seam).
- `mobile-analytics-consent`: ADD a requirement that `ConsentScreen` toggle + submit state is owned by an entry-scoped `ConsentViewModel` (the three toggles survive configuration change; submit runs on `viewModelScope`; the snapshot write and single-in-flight guard are preserved).

## Impact

- **Code (commonMain)**: new `screens/auth/SignInViewModel.kt`, `screens/auth/AgeGateViewModel.kt`, `screens/consent/ConsentViewModel.kt`; edits to `SignInScreen.kt`, `AgeGateScreen.kt`, `ConsentScreen.kt` (state lifted out, `viewModel { }` resolution, `collectAsStateWithLifecycle`, nav-one-shot consumption). Pure `*UiState.kt` projections and `*OutcomeHandler` seams unchanged.
- **Tests**: +3 `commonTest` VM tests; minor adjustments to `SignInScreenTest` / `AgeGateScreenTest` / `ConsentScreenTest` for the `viewModelScope`-async submit; iOS flow tests (`SignInFlowIosTest` / `AgeGateFlowIosTest` / `ConsentFlowIosTest`) re-verified (note pre-existing iOS DI-drift reds tracked in #348 — not regressed by this change).
- **No** Flyway / backend / admin / DI-module-graph changes (VMs are `viewModel { }`-constructed, not Koin singles — `MobileModule.kt` unchanged). No new dependencies.
- **Backlog**: burns down audit-burndown item `05-#5`; re-scopes the stale 05-#5/#6/#7 menu rows to reflect what is already shipped (PostCreation/PostDetail) and what remains separately (timeline-VM `stateIn` retrofit; global `koinViewModel` conversion).
