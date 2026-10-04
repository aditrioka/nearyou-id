## MODIFIED Requirements

### Requirement: NavDisplay scopes per-entry saveable state and ViewModels via entry decorators

The `NavDisplay` SHALL include, in its `entryDecorators` (in this order), `rememberSaveableStateHolderNavEntryDecorator()` so each `NavEntry` receives its own `SaveableStateRegistry` (per-screen `rememberSaveable` state — e.g. the composer draft — is scoped to its entry and retained while that entry remains in the back stack) **and** `rememberViewModelStoreNavEntryDecorator()` (from the `org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-navigation3` artifact) so each `NavEntry` receives its own `ViewModelStore`. A screen MAY scope a `ViewModel` to its entry via `viewModel { … }`; that ViewModel SHALL survive the entry going off-screen (e.g. while another destination is on top) and SHALL be cleared only when the entry is popped off the back stack. The Nearby feed is the first such screen (its load state is held in a `HomeRoute`-scoped ViewModel so returning from the composer does not re-fetch — see `mobile-nearby-timeline`; a successful post re-fetches explicitly through the HomeRoute feed reload key, not through a lost ViewModel — see `mobile-post-creation`).

#### Scenario: NavDisplay wires both entry decorators

- **WHEN** inspecting the `NavDisplay` declaration in `mobile/app/src/commonMain/kotlin/id/nearyou/app/App.kt`
- **THEN** its `entryDecorators` list includes `rememberSaveableStateHolderNavEntryDecorator()` AND `rememberViewModelStoreNavEntryDecorator()`

#### Scenario: The per-entry ViewModel-store artifact is declared

- **WHEN** inspecting the version catalog (`gradle/libs.versions.toml`) and `mobile/app/build.gradle.kts`
- **THEN** the `org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-navigation3` dependency is declared (pinned to the project's `androidx-lifecycle` version) and added to the `:mobile:app` `commonMain` dependencies
