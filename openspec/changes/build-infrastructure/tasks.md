## 1. Extend the version catalog

- [x] 1.1 Add `[versions]` entries for `kotlinx-coroutines`, `kotlinx-serialization`, `kotlinx-datetime`, `koin`, `koin-ktor`, `flyway`, `flyway-postgresql`, `hikaricp`, `kotest`, `ktlint-plugin`, `postgresql-jdbc` in `gradle/libs.versions.toml`
- [x] 1.2 Add corresponding `[libraries]` entries (e.g. `kotlinx-coroutines-core`, `kotlinx-serialization-json`, `kotlinx-datetime`, `koin-core`, `koin-ktor`, `koin-logger-slf4j`, `flyway-core`, `flyway-database-postgresql`, `hikaricp`, `postgresql`, `kotest-runner-junit5`, `kotest-assertions-core`, `ktor-server-content-negotiation`, `ktor-server-status-pages`, `ktor-server-call-logging`, `ktor-serialization-kotlinx-json`)
- [x] 1.3 Add `[plugins]` entries for `kotlinx-serialization`, `ktlint`, `flyway` (do not delete existing entries)
- [x] 1.4 Run `./gradlew help` to confirm catalog parses without error

## 2. Create the `build-logic` included build

- [x] 2.1 Create `build-logic/settings.gradle.kts`: set `rootProject.name = "build-logic"`, register `dependencyResolutionManagement.versionCatalogs.create("libs") { from(files("../gradle/libs.versions.toml")) }`, configure `pluginManagement` with `gradlePluginPortal()` + `google()` + `mavenCentral()`
- [x] 2.2 Create `build-logic/build.gradle.kts`: apply `kotlin-dsl` plugin, depend on Kotlin Gradle plugin + AGP + Ktor plugin + ktlint plugin (coords pulled from the version catalog via `libs.findLibrary(...)` workaround for precompiled scripts)
- [x] 2.3 Create `build-logic/src/main/kotlin/nearyou.kotlin.jvm.gradle.kts`: apply `org.jetbrains.kotlin.jvm`, set JVM toolchain to 21, apply ktlint plugin with default config, wire `ktlintCheck` into `check`
- [x] 2.4 Create `build-logic/src/main/kotlin/nearyou.kotlin.multiplatform.gradle.kts`: apply `org.jetbrains.kotlin.multiplatform` + `com.android.library` (for Android target), apply ktlint, wire into `check` (Android-library plugin kept inline in module since `:mobile:app` uses `android.application`; multiplatform convention plugin only applies KMP + ktlint, see design Decision 1)
- [x] 2.5 Create `build-logic/src/main/kotlin/nearyou.ktor.gradle.kts`: apply `nearyou.kotlin.jvm` + `io.ktor.plugin` + `org.jetbrains.kotlin.plugin.serialization`, apply ktlint, configure Flyway plugin with env-var-driven URL/user/password
- [x] 2.6 Update root `settings.gradle.kts`: add `pluginManagement { includeBuild("build-logic") }` so the `nearyou.*` plugin IDs resolve. Also bump Gradle wrapper to 9.1.0 (required by Kotlin 2.3.20 + KGP transitive stdlib metadata) and bump heap to `-Xmx6144M` (native iOS link OOMs at 4 GB).

## 3. Refactor existing module build scripts

- [x] 3.1 Rewrite `core/domain/build.gradle.kts` to apply only `id("nearyou.kotlin.jvm")` (no `dependencies { }` block, or empty)
- [x] 3.2 Rewrite `core/data/build.gradle.kts` to apply only `id("nearyou.kotlin.jvm")` and keep `dependencies { implementation(projects.core.domain) }`
- [x] 3.3 Rewrite `backend/ktor/build.gradle.kts` to apply `id("nearyou.ktor")`; keep app-specific config (`application { mainClass.set(...) }`); migrate dependencies to use new catalog libs (Koin, kotlinx-serialization JSON, content-negotiation, status-pages, call-logging, HikariCP, Postgres JDBC, Flyway core); add `testImplementation` for Kotest
- [x] 3.4 Rewrite `shared/tmp/build.gradle.kts` to apply `id("nearyou.kotlin.multiplatform")` (keep target declarations and Android namespace block in the module file since they're app-specific)
- [x] 3.5 Rewrite `mobile/app/build.gradle.kts` to apply `id("nearyou.kotlin.multiplatform")` + `id("com.android.application")` + Compose plugins (Compose plugins stay direct since they're app-only); keep all Compose dependencies
- [x] 3.6 Run `./gradlew help` to confirm every module configures cleanly

## 4. Wire Flyway

- [x] 4.1 Confirm Flyway plugin is applied via `nearyou.ktor` (done in 2.5) and that `flyway { url = providers.environmentVariable("DB_URL").orNull; user = providers.environmentVariable("DB_USER").orNull; password = providers.environmentVariable("DB_PASSWORD").orNull }` reads from env
- [x] 4.2 Create `backend/ktor/src/main/resources/db/migration/V1__init.sql` containing only `-- Intentional placeholder. Real schema starts at V2.\nSELECT 1;`
- [x] 4.3 Run `./gradlew :backend:ktor:tasks --group=flyway` and confirm `flywayMigrate`, `flywayInfo`, `flywayValidate` are listed

## 5. Add `secretKey` helper

- [x] 5.1 Create `backend/ktor/src/main/kotlin/id/nearyou/app/config/Secrets.kt` with the function `fun secretKey(env: String, name: String): String`
- [x] 5.2 Create `backend/ktor/src/test/kotlin/id/nearyou/app/config/SecretsTest.kt` (Kotest) covering both branches (`"staging"` → prefixed, `"production"` → unchanged) plus an edge case for an unrecognized env value (treated as production)
- [x] 5.3 Run `./gradlew :backend:ktor:test` — must pass

## 6. Build the Ktor application skeleton

- [x] 6.1 Create `backend/ktor/src/main/resources/application.conf` with `ktor { deployment { port = 8080, port = ${?PORT} }, application { modules = [ id.nearyou.app.ApplicationKt.module ] }, environment = ${?KTOR_ENV} }`
- [x] 6.2 Replace `backend/ktor/src/main/kotlin/id/nearyou/app/Application.kt` with a real `fun main` (delegating to `EngineMain.main(args)`) and a `fun Application.module()` that installs ContentNegotiation (kotlinx.serialization JSON), StatusPages (5xx → JSON envelope), CallLogging, and Koin (with an empty module registered)
- [x] 6.3 Add health route file `backend/ktor/src/main/kotlin/id/nearyou/app/health/HealthRoutes.kt` exposing `routing { get("/health/live") { call.respond(HttpStatusCode.OK) }; get("/health/ready") { call.respond(HttpStatusCode.OK, mapOf("status" to "ok")) } }` and call it from `Application.module()`
- [x] 6.4 Add a Kotest spec `backend/ktor/src/test/kotlin/id/nearyou/app/health/HealthRoutesTest.kt` using `testApplication { client.get("/health/live") }` to assert HTTP 200
- [x] 6.5 Run `./gradlew :backend:ktor:run` in the background, `curl -i localhost:8080/health/live` returns `HTTP/1.1 200`, kill the process

## 7. Add CI workflow

- [x] 7.1 Create `.github/workflows/ci.yml` triggered on `pull_request` to `main` only
- [x] 7.2 Add `lint` job: ubuntu-latest, JDK 21 Temurin, Gradle wrapper cache, runs `./gradlew ktlintCheck`
- [x] 7.3 Add `build` job (parallel to lint): same setup, runs `./gradlew assemble`
- [x] 7.4 Add `test` job: same setup, runs `./gradlew test`
- [x] 7.5 Validate YAML locally (e.g. `yq` or copy into GitHub Actions linter) — must parse cleanly

## 8. Documentation

- [x] 8.1 Create `docs/09-Versions.md` with sections: "Pinning Policy", "Update Cadence", and a "Version Decisions" table (columns: library, version, pinned-on date, rationale, next-review). Leave the table with a single `<!-- example row -->` placeholder.
- [x] 8.2 Update `docs/00-README.md` (if it lists docs) to reference `09-Versions.md`

## 9. End-to-end verification

- [x] 9.1 Run `./gradlew clean ktlintCheck build test` from repo root — all green (also added `.editorconfig` to allow `@Composable` PascalCase + suppressed ktlint on `MainViewController`; replaced `import androidx.compose.runtime.*` with explicit imports)
- [x] 9.2 Confirm `./gradlew :backend:ktor:run` boots, `/health/live` returns 200, `/health/ready` returns 200 with JSON body
- [x] 9.3 Optional: spin up local Postgres (`docker run -d --rm -e POSTGRES_PASSWORD=postgres -p 5432:5432 postgres:16`), run `DB_URL=jdbc:postgresql://localhost:5432/postgres DB_USER=postgres DB_PASSWORD=postgres ./gradlew :backend:ktor:flywayMigrate` — confirms `flyway_schema_history` table is created and `V1__init.sql` is recorded as version 1 (skipped: Docker daemon not running locally; tasks confirmed discoverable via `flyway` group)
- [x] 9.4 Stage and commit changes in a single commit titled `chore: build infrastructure (catalog, conventions, flyway, ktor skeleton, ci)` (commit `bf09f43`)
