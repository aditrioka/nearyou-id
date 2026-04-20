## Context

The KMP wizard generated three modules with non-architectural names (`:composeApp`, `:server`, `:shared`). `docs/04-Architecture.md § Dependency Isolation Pattern` defines a layered layout (`:core:*`, `:shared:*`, `:infra:*`, `:backend:ktor`, `:mobile:app`) that the project will grow into. We want the names to match the architecture *now* so future changes don't have to do rename-and-feature in the same patch. This change is intentionally library-free: it only restructures.

Current state:
- `:composeApp` → KMP + Compose (Android + iOS), depends on `:shared`.
- `:server` → JVM + Ktor, depends on `:shared`.
- `:shared` → KMP (Android, iOS, JVM), wizard boilerplate (`Greeting`, `Platform`).
- `iosApp/` → Xcode project consuming the `ComposeApp` framework from `:composeApp`.

## Goals / Non-Goals

**Goals:**
- Module names in `settings.gradle.kts` match Architecture doc (`:mobile:app`, `:backend:ktor`).
- New empty `:core:domain` and `:core:data` modules exist and build.
- `./gradlew build` is green after the rename.
- Zero new library dependencies.

**Non-Goals:**
- No `:infra:*` modules — added later, one per integration.
- No `:shared:distance` or `:shared:resources` — added when the consuming feature is built.
- No version catalog refactor or convention plugins.
- No KMP for `:core:*` modules (see Decision 3).
- No iOS-side rename of the `ComposeApp` framework name (see Risk 1).

## Decisions

### 1. Move modules into nested directories matching Gradle paths

`:mobile:app` → directory `mobile/app/`. `:backend:ktor` → directory `backend/ktor/`. Use Gradle's `project(":mobile:app").projectDir = file("mobile/app")` only if needed; default convention `include(":mobile:app")` already maps to `mobile/app/`, so no override required.

**Alternative considered:** keep flat directories (`composeApp/` renamed to `mobile-app/`) with explicit `projectDir` override. Rejected — convention-based layout is the Gradle norm and reads better in IDE.

### 2. Rename `:shared` → `:shared:tmp`

Both `:composeApp` and `:server` depend on `projects.shared`, but the only code in `:shared` is wizard boilerplate (`Greeting`, `Platform`). The Architecture doc has no top-level `:shared` — only namespaced submodules (`:shared:distance`, `:shared:resources`).

**Decision:** rename to `:shared:tmp` and update both consumers. The `tmp` name signals "scratch — delete or split into real `:shared:*` modules when we have actual shared code." Keeping the wizard sample running confirms the iOS framework still builds.

**Alternative considered:** delete `:shared` entirely now and move its sample code into `:mobile:app`. Rejected — `:server` also pulls from it, and we'd lose the multiplatform smoke-test that the wizard set up. Defer the deletion to a later change once we have real shared code (or a cleanup change).

**Alternative considered:** keep top-level `:shared`. Rejected — namespace would conflict with `:shared:distance` etc.

### 3. `:core:domain` and `:core:data` are pure Kotlin/JVM, not KMP

Architecture doc says "pure Kotlin, zero vendor dependencies" — that constrains *what they depend on*, not *which targets they emit*. Current consumers will only be `:backend:ktor` (JVM); `:mobile:app` does not yet import them.

**Decision:** apply `kotlin("jvm")` only. When a mobile feature first needs to consume a domain model, promote them to KMP in a separate change (adds `androidTarget`, `iosArm64`, `iosSimulatorArm64`, `jvm`).

**Alternative considered:** make them KMP from day one. Rejected — adds wizard-style multiplatform boilerplate, source-set wiring, and an Android namespace for a module with zero code. YAGNI for the current consumer set.

### 4. Stay on the existing version catalog (`gradle/libs.versions.toml`)

Each new `build.gradle.kts` references `libs.plugins.kotlinJvm` (already present). No new entries needed in the catalog.

### 5. iOS framework name unchanged

`:mobile:app` will continue to emit the iOS framework as `ComposeApp` (matches the wizard default and what `iosApp/` imports). Renaming the framework itself is out of scope; if we rename it later, `iosApp/` Xcode references must be updated in the same change.

## Risks / Trade-offs

- **iOS build references `:composeApp`** → after rename, the Xcode project's `embedAndSignAppleFrameworkForXcode` task path changes to `:mobile:app:embedAndSignAppleFrameworkForXcode`. **Mitigation:** update the Xcode build phase script in `iosApp/iosApp.xcodeproj` as part of this change.
- **IDE run configurations break** → `.idea/runConfigurations/*.xml` (and developer-local IntelliJ configs) reference `:composeApp` and `:server`. **Mitigation:** delete tracked stale configs; developers regenerate locally.
- **`:shared:tmp` name will rot** → if no one revisits it, the placeholder lingers. **Mitigation:** track removal in `openspec` as the next change after a real `:shared:*` module lands.
- **Gradle path-vs-directory mismatch surprises** → `include(":mobile:app")` infers `mobile/app/`. If we ever want the dir name to differ from the path, we'd need explicit `project(...).projectDir`. **Mitigation:** keep them aligned.

## Migration Plan

Single commit on the change branch:

1. `git mv composeApp mobile/app`, `git mv server backend/ktor`, `git mv shared shared/tmp`.
2. Update `settings.gradle.kts` `include(...)` lines.
3. Update `mobile/app/build.gradle.kts` and `backend/ktor/build.gradle.kts` to reference `projects.shared.tmp` instead of `projects.shared`.
4. Create `core/domain/build.gradle.kts` and `core/data/build.gradle.kts`.
5. Update Xcode `Build Phases → Run Script` in `iosApp/iosApp.xcodeproj/project.pbxproj` to call `:mobile:app:embedAndSignAppleFrameworkForXcode`.
6. Run `./gradlew clean build` — must be green.
7. Smoke-test: `./gradlew :mobile:app:assembleDebug` and `./gradlew :backend:ktor:run` both succeed.

**Rollback:** revert the commit. No external state, no schema migrations.

## Open Questions

- Should `:shared:tmp` be deleted in this change instead of renamed, by inlining its boilerplate into `:mobile:app`? (Current decision: keep, defer.)
- Does iOS Xcode need any updates beyond the Run Script path? (Will verify during implementation; if more, scope creep is local to `iosApp/`.)
