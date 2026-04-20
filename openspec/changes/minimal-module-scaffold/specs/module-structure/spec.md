## ADDED Requirements

### Requirement: Mobile app module path

The Gradle module for the mobile (KMP + Compose) application SHALL be registered as `:mobile:app`, located at `mobile/app/`, with namespace `id.nearyou.app`.

#### Scenario: Settings registration
- **WHEN** Gradle parses `settings.gradle.kts`
- **THEN** `:mobile:app` is included and resolves to directory `mobile/app/`

#### Scenario: Build succeeds
- **WHEN** running `./gradlew :mobile:app:assembleDebug`
- **THEN** the task completes with exit code 0

### Requirement: Backend module path

The Gradle module for the Ktor backend SHALL be registered as `:backend:ktor`, located at `backend/ktor/`, with `application.mainClass` set to `id.nearyou.app.ApplicationKt`.

#### Scenario: Settings registration
- **WHEN** Gradle parses `settings.gradle.kts`
- **THEN** `:backend:ktor` is included and resolves to directory `backend/ktor/`

#### Scenario: Build succeeds
- **WHEN** running `./gradlew :backend:ktor:build`
- **THEN** the task completes with exit code 0

### Requirement: Shared scratch module

A placeholder shared module SHALL exist at `:shared:tmp` (directory `shared/tmp/`) holding the wizard's KMP boilerplate. It MUST NOT be referenced by any new feature code; consumers SHALL migrate to a real `:shared:<name>` module before adding logic.

#### Scenario: Settings registration
- **WHEN** Gradle parses `settings.gradle.kts`
- **THEN** `:shared:tmp` is included and resolves to directory `shared/tmp/`

#### Scenario: Existing consumers updated
- **WHEN** inspecting `mobile/app/build.gradle.kts` and `backend/ktor/build.gradle.kts`
- **THEN** their dependency on the shared module is expressed as `projects.shared.tmp`

### Requirement: Core domain module

A pure-Kotlin module SHALL exist at `:core:domain` (directory `core/domain/`) containing zero vendor dependencies. It MUST apply only `kotlin("jvm")` and depend on no other project module.

#### Scenario: Plugin set
- **WHEN** inspecting `core/domain/build.gradle.kts`
- **THEN** the only applied plugin is `kotlin("jvm")` and the `dependencies { }` block contains no `implementation`/`api` entries other than the Kotlin standard library

#### Scenario: Build succeeds
- **WHEN** running `./gradlew :core:domain:build`
- **THEN** the task completes with exit code 0

### Requirement: Core data module

A pure-Kotlin module SHALL exist at `:core:data` (directory `core/data/`) containing only interfaces and DTOs. It MUST apply only `kotlin("jvm")` and depend on no module other than `:core:domain`.

#### Scenario: Plugin set
- **WHEN** inspecting `core/data/build.gradle.kts`
- **THEN** the only applied plugin is `kotlin("jvm")`

#### Scenario: Allowed dependencies
- **WHEN** inspecting `core/data/build.gradle.kts`
- **THEN** any inter-module dependency declared is `projects.core.domain` and no other

#### Scenario: Build succeeds
- **WHEN** running `./gradlew :core:data:build`
- **THEN** the task completes with exit code 0

### Requirement: Whole-project build is green

`./gradlew build` SHALL succeed across all modules listed in `settings.gradle.kts` with no library dependencies added beyond what the wizard already declared.

#### Scenario: Top-level build
- **WHEN** running `./gradlew build` from the repository root
- **THEN** the build completes with exit code 0 and no module-resolution errors are reported

#### Scenario: No new library dependencies
- **WHEN** comparing `gradle/libs.versions.toml` before and after this change
- **THEN** no entries are added or removed
