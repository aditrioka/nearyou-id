## Why

The repo currently has the wizard's minimal Gradle setup and an empty Ktor `Application.kt` from the KMP wizard. Before any feature work begins, every future change will need (a) a single source of truth for library versions, (b) shared module conventions to avoid copy-pasted plugin blocks, (c) a working Flyway migration pipeline so DB-touching features can land safely, (d) a real Ktor application bootstrap with content negotiation + DI + health probes, and (e) a CI pipeline that gates PRs on build + lint + test. Doing this once now is cheaper than retrofitting it across N features later.

## What Changes

- Extend `gradle/libs.versions.toml` to add pinned versions for: kotlinx-coroutines, kotlinx-serialization, kotlinx-datetime, Koin, Flyway, HikariCP, Kotest. Keep existing entries unchanged.
- Add `docs/09-Versions.md` (Version Pinning Decisions Log stub) — explains the freeze policy and links each pin to its rationale slot.
- Add **convention plugins** as an included build at `build-logic/` providing:
  - `nearyou.kotlin.jvm` — Kotlin/JVM defaults + ktlint
  - `nearyou.kotlin.multiplatform` — KMP defaults + ktlint
  - `nearyou.ktor` — Ktor app conventions on top of `nearyou.kotlin.jvm`
- **BREAKING** Refactor existing module build scripts (`:core:domain`, `:core:data`, `:backend:ktor`, `:mobile:app`, `:shared:tmp`) to apply the new convention plugins instead of inlining `kotlin("jvm")` / `kotlin("multiplatform")` setup.
- Wire Flyway:
  - Apply Flyway Gradle plugin to `:backend:ktor` so `./gradlew flywayMigrate` works locally.
  - Add `backend/ktor/src/main/resources/db/migration/V1__init.sql` placeholder (no DDL yet, just a `SELECT 1` smoke statement so Flyway has a migration to track).
  - Add `secretKey(env, name)` helper stub in `:backend:ktor` returning the namespaced secret name (`staging-` prefix when `KTOR_ENV=staging`).
  - Connection config read from `KTOR_ENV` + `application.conf` (HOCON), no hardcoded creds.
- Add **Ktor `Application.kt`** skeleton in `:backend:ktor`:
  - Content negotiation with kotlinx.serialization
  - StatusPages plugin (5xx → JSON envelope, 4xx pass-through)
  - CallLogging
  - Koin DI installed (empty module, ready for feature wiring)
  - `/health/live` (always 200) and `/health/ready` (stub returning 200; real dep checks added when each dep is wired)
- Add **GitHub Actions** workflow `.github/workflows/ci.yml`:
  - Triggers on PR
  - Jobs: `lint` (ktlint), `build` (`./gradlew build`), `test` (`./gradlew test`)
  - **No** Flyway-migrate job, **no** Cloud Run deploy, **no** Sentry symbol upload — all deferred.

## Capabilities

### New Capabilities
- `build-toolchain`: Defines the version catalog, convention plugins, and ktlint enforcement that govern every module's build.
- `migration-pipeline`: Defines the Flyway migration scaffold, file convention, and secret-resolution helper for environment-namespaced DB connections.
- `backend-bootstrap`: Defines the Ktor application skeleton (plugins installed, health endpoints, DI scaffold) that all backend feature work plugs into.
- `ci-pipeline`: Defines the PR gate (lint + build + test) and what is deliberately deferred from CI.

### Modified Capabilities
- `module-structure`: Each module's build script now applies a `nearyou.*` convention plugin instead of declaring Kotlin/Android plugins directly. Requirements about plugin sets in `:core:domain` and `:core:data` need updating.

## Impact

- **Code paths**: every module's `build.gradle.kts`; new `build-logic/` included build; new `.github/workflows/ci.yml`; new `docs/09-Versions.md`; expanded `gradle/libs.versions.toml`; new `backend/ktor/src/main/kotlin/.../Application.kt` (replaces wizard stub) + `db/migration/V1__init.sql` + HOCON config.
- **External**: developers must run `./gradlew ktlintFormat` before pushing (or the CI lint job fails). Local Flyway run requires `DB_URL`/`DB_USER`/`DB_PASSWORD` env vars (or the helper's fallback to a dev default).
- **Out of scope**: Cloud Run deploy job, Sentry dSYM/ProGuard upload, business-logic CI lint rules, staging vs prod secret namespace beyond the helper stub.
