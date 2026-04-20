## MODIFIED Requirements

### Requirement: Core domain module

A pure-Kotlin module SHALL exist at `:core:domain` (directory `core/domain/`) containing zero vendor dependencies. It MUST apply the `nearyou.kotlin.jvm` convention plugin (which provides Kotlin/JVM setup + ktlint) and depend on no other project module.

#### Scenario: Plugin set
- **WHEN** inspecting `core/domain/build.gradle.kts`
- **THEN** the only applied plugin is `id("nearyou.kotlin.jvm")` and the `dependencies { }` block contains no `implementation`/`api` entries other than the Kotlin standard library

#### Scenario: Build succeeds
- **WHEN** running `./gradlew :core:domain:build`
- **THEN** the task completes with exit code 0

### Requirement: Core data module

A pure-Kotlin module SHALL exist at `:core:data` (directory `core/data/`) containing only interfaces and DTOs. It MUST apply the `nearyou.kotlin.jvm` convention plugin and depend on no module other than `:core:domain`.

#### Scenario: Plugin set
- **WHEN** inspecting `core/data/build.gradle.kts`
- **THEN** the only applied plugin is `id("nearyou.kotlin.jvm")`

#### Scenario: Allowed dependencies
- **WHEN** inspecting `core/data/build.gradle.kts`
- **THEN** any inter-module dependency declared is `projects.core.domain` and no other

#### Scenario: Build succeeds
- **WHEN** running `./gradlew :core:data:build`
- **THEN** the task completes with exit code 0
