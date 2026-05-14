## MODIFIED Requirements

### Requirement: Shared scratch module

A placeholder shared module SHALL exist at `:shared:tmp` (directory `shared/tmp/`) holding the wizard's KMP boilerplate. It MUST NOT be referenced by any new feature code; consumers SHALL migrate to a real `:shared:<name>` module before adding logic.

#### Scenario: Settings registration
- **WHEN** Gradle parses `settings.gradle.kts`
- **THEN** `:shared:tmp` is included and resolves to directory `shared/tmp/`

#### Scenario: Existing consumers updated
- **WHEN** inspecting `backend/ktor/build.gradle.kts`
- **THEN** its dependency on the shared module is expressed as `projects.shared.tmp`

#### Scenario: Mobile module has migrated off the scratch placeholder
- **WHEN** inspecting `mobile/app/build.gradle.kts`
- **THEN** the file does NOT declare any dependency on `projects.shared.tmp`; the wizard's `Greeting` boilerplate is no longer consumed by `:mobile:app`

### Requirement: Whole-project build is green

`./gradlew build` SHALL succeed across all modules listed in `settings.gradle.kts`.

#### Scenario: Top-level build
- **WHEN** running `./gradlew build` from the repository root
- **THEN** the build completes with exit code 0 and no module-resolution errors are reported
