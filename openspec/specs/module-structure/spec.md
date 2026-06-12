# Module Structure

## Purpose

This spec defines the Gradle module layout and naming for the NearYouID project.
See `docs/04-Architecture.md § Dependency Isolation Pattern` for the broader target layout this scaffold grows into.
## Requirements
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
- **WHEN** inspecting `backend/ktor/build.gradle.kts`
- **THEN** its dependency on the shared module is expressed as `projects.shared.tmp`

#### Scenario: Mobile module has migrated off the scratch placeholder
- **WHEN** inspecting `mobile/app/build.gradle.kts`
- **THEN** the file does NOT declare any dependency on `projects.shared.tmp`; the wizard's `Greeting` boilerplate is no longer consumed by `:mobile:app`

### Requirement: Core domain module

A pure-Kotlin module SHALL exist at `:core:domain` (directory `core/domain/`) containing zero vendor dependencies. It MUST apply the `nearyou.kotlin.jvm` convention plugin (which provides Kotlin/JVM setup + ktlint) and the `nearyou.detekt` convention plugin (invariant-ruleset scanning; its `detektPlugins(:lint:detekt-rules)` entry is lint-tool classpath, not a code dependency), and depend on no other project module at compile time. `kotlinx-serialization` (plugin + `-json` artifact) is the one sanctioned library addition — required by `ChatRealtimeClient`'s broadcast payload (chat-realtime design § D13).

#### Scenario: Plugin set
- **WHEN** inspecting `core/domain/build.gradle.kts`
- **THEN** the applied plugins are exactly `id("nearyou.kotlin.jvm")`, `id("nearyou.detekt")`, and the `kotlinxSerialization` alias, and the `dependencies { }` block's compile entries are limited to the Kotlin standard library + `kotlinx-serialization-json`

#### Scenario: Build succeeds
- **WHEN** running `./gradlew :core:domain:build`
- **THEN** the task completes with exit code 0

### Requirement: Core data module

A pure-Kotlin module SHALL exist at `:core:data` (directory `core/data/`) containing only interfaces and DTOs. It MUST apply the `nearyou.kotlin.jvm` and `nearyou.detekt` convention plugins and depend on no module other than `:core:domain` at compile time.

#### Scenario: Plugin set
- **WHEN** inspecting `core/data/build.gradle.kts`
- **THEN** the applied plugins are exactly `id("nearyou.kotlin.jvm")` and `id("nearyou.detekt")`

#### Scenario: Allowed dependencies
- **WHEN** inspecting `core/data/build.gradle.kts`
- **THEN** any inter-module dependency declared is `projects.core.domain` and no other

#### Scenario: Build succeeds
- **WHEN** running `./gradlew :core:data:build`
- **THEN** the task completes with exit code 0

### Requirement: Whole-project build is green

`./gradlew build` SHALL succeed across all modules listed in `settings.gradle.kts`.

#### Scenario: Top-level build
- **WHEN** running `./gradlew build` from the repository root
- **THEN** the build completes with exit code 0 and no module-resolution errors are reported

