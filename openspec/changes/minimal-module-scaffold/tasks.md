## 1. Rename existing modules

- [x] 1.1 `git mv composeApp mobile/app` (creates `mobile/app/`)
- [x] 1.2 `git mv server backend/ktor` (creates `backend/ktor/`)
- [x] 1.3 `git mv shared shared/tmp` (creates `shared/tmp/`)
- [x] 1.4 Verify each moved directory still contains its `build.gradle.kts` and `src/` tree

## 2. Update Gradle settings

- [x] 2.1 Edit `settings.gradle.kts`: replace `include(":composeApp")`, `include(":server")`, `include(":shared")` with `include(":mobile:app")`, `include(":backend:ktor")`, `include(":shared:tmp")`
- [x] 2.2 Run `./gradlew projects` and confirm the project list shows exactly `:mobile:app`, `:backend:ktor`, `:shared:tmp` (plus the soon-to-be-added `:core:domain` and `:core:data`)

## 3. Repoint shared dependency in renamed modules

- [x] 3.1 In `mobile/app/build.gradle.kts`, change `implementation(projects.shared)` → `implementation(projects.shared.tmp)`
- [x] 3.2 In `backend/ktor/build.gradle.kts`, change `implementation(projects.shared)` → `implementation(projects.shared.tmp)`

## 4. Add `:core:domain`

- [x] 4.1 Create directory `core/domain/`
- [x] 4.2 Create `core/domain/build.gradle.kts` applying only `kotlin("jvm")`, with no `dependencies { }` entries beyond the implicit Kotlin stdlib
- [x] 4.3 Create empty source root `core/domain/src/main/kotlin/` (and `.gitkeep` if needed to keep the directory tracked)
- [x] 4.4 Add `include(":core:domain")` to `settings.gradle.kts`

## 5. Add `:core:data`

- [x] 5.1 Create directory `core/data/`
- [x] 5.2 Create `core/data/build.gradle.kts` applying only `kotlin("jvm")`, with `dependencies { implementation(projects.core.domain) }`
- [x] 5.3 Create empty source root `core/data/src/main/kotlin/` (and `.gitkeep` if needed)
- [x] 5.4 Add `include(":core:data")` to `settings.gradle.kts`

## 6. Update iOS Xcode project

- [x] 6.1 Open `iosApp/iosApp.xcodeproj/project.pbxproj` and locate the `Run Script` build phase that calls `:composeApp:embedAndSignAppleFrameworkForXcode`
- [x] 6.2 Replace `:composeApp:` with `:mobile:app:` in that script
- [x] 6.3 Verify no other references to `:composeApp` or `:server` remain in `iosApp/`

## 7. Verify build

- [x] 7.1 Run `./gradlew clean` (succeeds)
- [x] 7.2 Run `./gradlew build` from repository root — must exit 0
- [x] 7.3 Run `./gradlew :mobile:app:assembleDebug` — must exit 0
- [x] 7.4 Run `./gradlew :backend:ktor:build` — must exit 0
- [x] 7.5 Run `./gradlew :core:domain:build` and `./gradlew :core:data:build` — both must exit 0
- [x] 7.6 Confirm `gradle/libs.versions.toml` is unchanged versus `main` (`git diff main -- gradle/libs.versions.toml` is empty)

## 8. Cleanup

- [x] 8.1 Delete any stale `.idea/runConfigurations/*.xml` files that reference `:composeApp` or `:server` (none present)
- [x] 8.2 Update `README.md` if it lists module paths (skip if not present)
- [ ] 8.3 Stage and commit all changes in a single commit titled `chore: scaffold minimal module structure`
