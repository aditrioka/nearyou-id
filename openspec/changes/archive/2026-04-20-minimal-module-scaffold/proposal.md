## Why

The KMP wizard scaffolded the project with default module names (`composeApp`, `server`, `shared`) that do not match the target architecture in `docs/04-Architecture.md`. Before adding any libraries or features, we need the module skeleton to reflect that architecture so subsequent changes (version catalog, dependency wiring, first features) land in the right places without renames mid-flight.

## What Changes

- **BREAKING** Rename `:composeApp` → `:mobile:app` (matches Architecture doc).
- **BREAKING** Rename `:server` → `:backend:ktor` (matches Architecture doc).
- Resolve `:shared` (decision deferred to design.md: keep as scratch under existing path or rename to `:shared:tmp`).
- Add empty `:core:domain` module (pure Kotlin JVM, zero vendor dependencies).
- Add empty `:core:data` module (pure Kotlin JVM, holds interfaces and DTOs only).
- Update `settings.gradle.kts` and each module's `build.gradle.kts` with minimal Kotlin/target setup. **No library dependencies added.**
- Verify `./gradlew build` succeeds across all modules.

## Capabilities

### New Capabilities
- `module-structure`: Defines the Gradle module layout, naming, and dependency-isolation rules for the project.

### Modified Capabilities
<!-- None — this is the first scaffold change; no existing specs. -->

## Impact

- **Code paths**: `composeApp/` → `mobile/app/`, `server/` → `backend/ktor/`, new `core/domain/` and `core/data/` directories.
- **Build files**: `settings.gradle.kts`, every module's `build.gradle.kts`.
- **IDE**: Run configurations referring to `:composeApp` or `:server` will need to be regenerated.
- **iOS**: `iosApp/` Xcode project references the shared framework; framework name and embed paths may need updating.
- **Out of scope**: version catalog, convention plugins, library deps, `:infra:*` modules, `:shared:distance`, `:shared:resources` — each will be its own change.
