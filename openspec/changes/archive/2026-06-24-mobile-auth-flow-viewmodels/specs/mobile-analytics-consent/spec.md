## ADDED Requirements

### Requirement: ConsentScreen state is owned by an entry-scoped ConsentViewModel

`ConsentScreen` SHALL hold NO screen state in composition-scoped `remember { mutableStateOf(...) }` and SHALL launch NO network work on `rememberCoroutineScope()`. Instead the mobile app SHALL ship a `ConsentViewModel` (androidx `ViewModel` in commonMain, file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/consent/ConsentViewModel.kt`), resolved via `viewModel { … }` scoped to the `ConsentRoute` Nav3 entry (docs/11 §2.2), that owns the three consent toggles (analytics / crash / ads), the submit outcome, and the in-flight flag. The ViewModel SHALL expose exactly ONE `uiState: StateFlow<ConsentUiState>` produced from a private `MutableStateFlow<VmState>` via `stateIn(viewModelScope, WhileSubscribed(5_000), initial)`, where the projection delegates to the existing pure `consentUiState(outcome, inFlight)` and surfaces the three toggle values for the `Switch` rows. The toggles SHALL seed from injectable `initialAnalytics`/`initialCrash`/`initialAdsPersonalization` constructor params (defaults OFF / ON / OFF — the V2 column default; no GET round-trip). Because the state holder is an entry-scoped ViewModel, an Android configuration change SHALL NOT reset the three toggle selections or the outcome/in-flight state, and the consent PATCH — launched on `viewModelScope` — SHALL NOT be cancelled by recomposition or configuration change. The submit-success durable-snapshot write, the single-in-flight double-tap guard, the status-driven outcome mapping with no fallthrough, the post-failure non-trapping skip, and PII discipline SHALL be preserved exactly.

#### Scenario: ConsentViewModel exposes one uiState StateFlow via stateIn

- **WHEN** inspecting `ConsentViewModel`
- **THEN** it exposes a single `uiState: StateFlow<ConsentUiState>` projected from one private `MutableStateFlow<VmState>` via `stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), …)` AND the projection's value equals `consentUiState(outcome, inFlight)` for the held state, with the three toggle booleans surfaced for the switch rows

#### Scenario: Consent toggle selections survive a configuration change

- **GIVEN** a `ConsentViewModel` seeded with the defaults (analytics OFF, crash ON, ads OFF) on which the user has flipped analytics ON and crash OFF
- **WHEN** the screen composition is recreated (the configuration-change case) and re-collects the **same** entry-scoped ViewModel's `uiState`
- **THEN** the re-collected `uiState` still reflects analytics ON, crash OFF, ads OFF (the toggle selections were retained by the ViewModel, not reset to the defaults)

#### Scenario: Double-tap continue issues exactly one PATCH and the snapshot is written only on success

- **GIVEN** a `ConsentViewModel` backed by a fake `ConsentFlow` that counts `submitConsent(...)` calls (responding `Success` after a suspending delay) and a recording `ConsentSnapshotStore`
- **WHEN** the continue action is invoked twice in rapid succession before the first completes
- **THEN** exactly one `submitConsent(...)` call is recorded (the in-flight guard) AND on the `Success` the ViewModel wrote the submitted toggle triple to `ConsentSnapshotStore` exactly once BEFORE raising the done navigation one-shot; AND when the submit instead resolves to `RetryableError` no snapshot is written and `uiState.value` shows the retryable banner with the non-trapping skip available
