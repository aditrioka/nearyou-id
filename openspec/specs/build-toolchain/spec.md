# Build Toolchain

This spec defines the version catalog, convention plugins, and ktlint enforcement that govern every module's build. See `docs/09-Versions.md` for the version-pinning policy and decision log.

## Requirements

### Requirement: Single version catalog

All third-party library and plugin versions consumed by any module SHALL be declared in `gradle/libs.versions.toml`. Modules MUST reference dependencies via the typesafe `libs.*` accessor (e.g. `libs.kotlinx.coroutines.core`); they MUST NOT inline string coordinates like `"io.ktor:ktor-server-core:3.4.1"`.

#### Scenario: No inline coordinates
- **WHEN** searching every `build.gradle.kts` (excluding `build-logic/`) for raw `group:artifact:version` strings in `dependencies { }` or `plugins { }` blocks
- **THEN** zero matches are found

#### Scenario: Required pins present
- **WHEN** parsing `gradle/libs.versions.toml`
- **THEN** the `[versions]` table contains pinned entries for `kotlin`, `ktor`, `kotlinx-coroutines`, `kotlinx-serialization`, `kotlinx-datetime`, `koin`, `flyway`, `hikaricp`, `kotest`, `ktlint-plugin`

### Requirement: Convention plugins via included build

The repository SHALL provide convention plugins through an included build at `build-logic/`. The included build MUST publish at least three precompiled script plugins: `nearyou.kotlin.jvm`, `nearyou.kotlin.multiplatform`, `nearyou.ktor`.

#### Scenario: Included build registered
- **WHEN** parsing root `settings.gradle.kts`
- **THEN** it contains `includeBuild("build-logic")`

#### Scenario: Plugins resolvable
- **WHEN** running `./gradlew :backend:ktor:dependencies` (or any module-level task)
- **THEN** Gradle resolves the `nearyou.ktor` plugin without "plugin not found" errors

### Requirement: Modules apply convention plugins

Every module under `core/`, `backend/`, and `shared/` SHALL apply exactly one `nearyou.*` convention plugin in its `plugins { }` block. Modules MUST NOT directly apply `kotlin("jvm")`, `kotlin("multiplatform")`, or `id("io.ktor.plugin")` in their own build script.

#### Scenario: Core modules use jvm plugin
- **WHEN** inspecting `core/domain/build.gradle.kts` and `core/data/build.gradle.kts`
- **THEN** each applies `id("nearyou.kotlin.jvm")` and applies no other Kotlin/Android plugin directly

#### Scenario: Backend uses ktor plugin
- **WHEN** inspecting `backend/ktor/build.gradle.kts`
- **THEN** it applies `id("nearyou.ktor")` and applies no other Kotlin/Ktor plugin directly

#### Scenario: Shared uses multiplatform plugin
- **WHEN** inspecting `shared/tmp/build.gradle.kts`
- **THEN** it applies `id("nearyou.kotlin.multiplatform")` (Android-library-specific config remains in the module's own block)

### Requirement: ktlint enforced via convention

Every convention plugin SHALL configure ktlint such that running `./gradlew ktlintCheck` (or `./gradlew check` / `./gradlew build`) fails the build on style violations.

#### Scenario: ktlintCheck task wired
- **WHEN** running `./gradlew :core:domain:tasks --group=verification`
- **THEN** the output lists `ktlintCheck`

#### Scenario: Style violation fails the build
- **WHEN** a Kotlin source file in any module contains a known ktlint violation (e.g. wrong indentation) and `./gradlew build` is run
- **THEN** the build exits non-zero with ktlint output identifying the violation

### Requirement: Version Pinning Decisions Log exists

A document SHALL exist at `docs/09-Versions.md` describing the version-pin policy and providing a table schema for recording each pin's rationale and review cadence.

#### Scenario: Doc present
- **WHEN** listing files in `docs/`
- **THEN** `09-Versions.md` is among them and is non-empty
