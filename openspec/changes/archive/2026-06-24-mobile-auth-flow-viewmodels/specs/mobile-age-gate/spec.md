## ADDED Requirements

### Requirement: AgeGateScreen state is owned by an entry-scoped AgeGateViewModel

`AgeGateScreen` SHALL hold NO screen state in composition-scoped `remember { mutableStateOf(...) }` and SHALL launch NO network work on `rememberCoroutineScope()`. Instead the mobile app SHALL ship an `AgeGateViewModel` (androidx `ViewModel` in commonMain, file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/auth/AgeGateViewModel.kt`), resolved via `viewModel { … }` scoped to the `AgeGateRoute` Nav3 entry (docs/11 §2.2), that owns the picked date-of-birth (`selectedDobMillis`), the DOB-picker visibility, the signup outcome, and the in-flight flag. The ViewModel SHALL expose exactly ONE `uiState: StateFlow<AgeGateUiState>` produced from a private `MutableStateFlow<VmState>` via `stateIn(viewModelScope, WhileSubscribed(5_000), initial)`, where the projection delegates to the existing pure `ageGateUiState(outcome, dobSubmittable, inFlight)` (DOB submittability via the unchanged `isDobSubmittable` against an injectable `today: LocalDate = systemToday()`). Because the state holder is an entry-scoped ViewModel, an Android configuration change SHALL NOT reset the user's **picked date-of-birth** (the primary data-loss bug today) or the outcome/in-flight state, and the signup call — launched on `viewModelScope` — SHALL NOT be cancelled by recomposition or configuration change. The verified Google identity hand-off and lifecycle (non-clearing `peek` for resubmit; `clear()` on every terminal exit but NOT on a retryable error; the absent-identity process-death re-route) SHALL be preserved exactly, owned by the ViewModel and reusing the existing pure `handleAgeGateTerminalOutcome` seam. The DOB picker range, the "never client-block under-18" rule, and PII discipline SHALL be unchanged.

#### Scenario: AgeGateViewModel exposes one uiState StateFlow via stateIn

- **WHEN** inspecting `AgeGateViewModel`
- **THEN** it exposes a single `uiState: StateFlow<AgeGateUiState>` projected from one private `MutableStateFlow<VmState>` via `stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), …)` AND the projection's value equals `ageGateUiState(outcome, isDobSubmittable(selectedDob, today), inFlight)` for the held state

#### Scenario: Picked date-of-birth survives a configuration change

- **GIVEN** an `AgeGateViewModel` (identity present) on which the user has picked a valid DOB via `onDobPicked(...)`, so `uiState.value` shows the create-account CTA enabled
- **WHEN** the screen composition is recreated (the configuration-change case) and re-collects the **same** entry-scoped ViewModel's `uiState`
- **THEN** the re-collected `uiState` still reflects the picked DOB (CTA still enabled; the DOB field still shows the chosen date) — the picked DOB was retained by the ViewModel, not reset to none

#### Scenario: Terminal exit clears the pending identity but a retryable error does not

- **GIVEN** an `AgeGateViewModel` with an injected `PendingSignupIdentity` holding `"g-id"` and a picked DOB
- **WHEN** the signup action resolves to a terminal outcome (`Success` → home one-shot, or `AccountExists` → sign-in one-shot)
- **THEN** the ViewModel invoked `PendingSignupIdentity.clear()` (a subsequent non-clearing read returns `null`) via the reused `handleAgeGateTerminalOutcome` seam AND raised the corresponding navigation one-shot; AND, separately, when the signup instead resolves to a retryable error (`RetryableError`) the holder is NOT cleared (a resubmit re-reads `"g-id"`)

#### Scenario: Absent identity on entry raises the sign-in re-route one-shot

- **GIVEN** an `AgeGateViewModel` constructed when `PendingSignupIdentity` is empty (the restored-back-stack / process-death case)
- **WHEN** the ViewModel initializes
- **THEN** `uiState.value` signals the absent-identity state (the screen renders no signup form) AND the ViewModel raised the sign-in re-route navigation one-shot AND `PendingSignupIdentity.clear()` was invoked (idempotent terminal-exit contract)

#### Scenario: A double-tap cannot launch two concurrent signup calls

- **GIVEN** an `AgeGateViewModel` (identity present, DOB picked) backed by a fake `AuthFlow` that counts `signUpWithGoogle(...)` invocations and completes after a suspending delay
- **WHEN** the create-account action is invoked twice in rapid succession before the first completes
- **THEN** exactly one `signUpWithGoogle(...)` invocation is recorded AND while in flight `uiState.value` shows the LOADING label with the CTA disabled
