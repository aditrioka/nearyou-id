## ADDED Requirements

### Requirement: SignInScreen state is owned by an entry-scoped SignInViewModel

`SignInScreen` SHALL hold NO screen state in composition-scoped `remember { mutableStateOf(...) }` and SHALL launch NO network work on `rememberCoroutineScope()`. Instead the mobile app SHALL ship a `SignInViewModel` (androidx `ViewModel` in commonMain, file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/auth/SignInViewModel.kt`), resolved via `viewModel { … }` scoped to the `SignInRoute` Nav3 entry (the established `:mobile:app` state-holder convention, docs/11 §2.2), that owns the sign-in outcome and the in-flight flag. The ViewModel SHALL expose exactly ONE `uiState: StateFlow<SignInUiState>` produced from a private `MutableStateFlow<VmState>` via `stateIn(viewModelScope, WhileSubscribed(5_000), initial)`, where the projection delegates to the existing pure `signInUiState(outcome, inFlight)` function (which is unchanged). Because the state holder is an entry-scoped ViewModel, an Android configuration change (rotation, theme flip, font-scale, fold) SHALL NOT reset the sign-in outcome / in-flight state, and the Google Sign-In ceremony — launched on `viewModelScope` — SHALL NOT be cancelled by recomposition or configuration change. All other observable behavior (CTA label/enabled mapping, error banners, banned-state appeal entry, the involuntary-logout session-expired notice, PII discipline) SHALL be preserved exactly.

#### Scenario: SignInViewModel exposes one uiState StateFlow via stateIn

- **WHEN** inspecting `SignInViewModel`
- **THEN** it exposes a single `uiState: StateFlow<SignInUiState>` projected from one private `MutableStateFlow<VmState>` via `stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), …)` AND the projection's value equals `signInUiState(outcome, inFlight)` for the held state (the pure function is reused, not reimplemented)

#### Scenario: Sign-in outcome survives a configuration change

- **GIVEN** a `SignInViewModel` whose Google Sign-In ceremony has resolved to `SignInOutcome.NetworkError`, so `uiState.value` shows the RETRY label + NETWORK banner
- **WHEN** the screen composition is recreated (the configuration-change case) and re-collects the **same** entry-scoped ViewModel's `uiState`
- **THEN** the re-collected `uiState.value` still shows the RETRY label + NETWORK banner (the outcome was retained by the ViewModel, not reset to the initial CTA state)

#### Scenario: A double-tap cannot launch two concurrent sign-in ceremonies

- **GIVEN** a `SignInViewModel` backed by a fake `AuthFlow` that counts `signInWithGoogle()` invocations and completes after a suspending delay
- **WHEN** the sign-in action is invoked twice in rapid succession before the first completes
- **THEN** exactly one `signInWithGoogle()` invocation is recorded (the in-flight flag guards re-entry) AND while in flight `uiState.value` shows the LOADING label with the CTA disabled

#### Scenario: No-account path sets the pending identity then raises the age-gate one-shot and clears the consumed outcome

- **GIVEN** a `SignInViewModel` with an injected `PendingSignupIdentity` holder and a fake `AuthFlow` returning `SignInOutcome.NoAccount(idToken = "g-id")`
- **WHEN** the sign-in action runs to completion and the screen consumes the navigation one-shot via `onNavigationHandled()`
- **THEN** `PendingSignupIdentity` was set to `"g-id"` BEFORE the age-gate navigation one-shot was raised AND after consumption the held outcome is cleared (a re-entry to a retained `SignInRoute` does not re-raise the age-gate navigation)
