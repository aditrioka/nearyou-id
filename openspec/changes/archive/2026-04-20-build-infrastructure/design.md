## Context

The repo is at the "scaffold complete, no features yet" stage. `:mobile:app`, `:backend:ktor`, `:core:domain`, `:core:data`, and `:shared:tmp` exist with wizard-default build scripts. `gradle/libs.versions.toml` covers Kotlin/Compose/Ktor/AGP basics from the wizard; nothing else is pinned. There is no Flyway, no Koin, no health endpoint, no CI.

The Architecture doc commits us to: Ktor + Koin + Flyway + HikariCP + kotlinx-serialization + kotlinx-coroutines + kotlinx-datetime + Kotest, with ktlint as the code-quality gate and GitHub Actions as the CI host. Feature changes (auth, posts, chat) all assume this baseline. Building it once cleanly is the goal.

## Goals / Non-Goals

**Goals:**
- One canonical place for every library version (`libs.versions.toml`).
- Each module's `build.gradle.kts` is short and consists primarily of an `id("nearyou.*")` plugin application + dependency list.
- `./gradlew flywayMigrate` works against a local Postgres (URL from env vars).
- `./gradlew :backend:ktor:run` starts a Ktor server that responds 200 on `/health/live`.
- `./gradlew build`, `./gradlew test`, `./gradlew ktlintCheck` all green.
- A PR triggers a GitHub Actions run that fails on lint/build/test failure.

**Non-Goals:**
- Cloud Run deploy pipeline.
- Sentry dSYM/ProGuard upload steps.
- Real `/health/ready` dependency probes (Postgres/Redis/Realtime) — added when those deps are wired.
- Business-logic CI lint rules (e.g. detect raw `FROM posts`) — added with the code they guard.
- Staging vs production secret split beyond the `secretKey(env, name)` helper signature.
- Konsist/detekt — ktlint only for now; richer static analysis is a separate change.
- Dependency updates automation (Dependabot/Renovate) — separate change.

## Decisions

### 1. Convention plugins via included build (`build-logic/`), not `buildSrc`

`buildSrc` invalidates the entire build's configuration cache when it changes. An **included build** at `build-logic/` is incrementally compiled, doesn't poison the cache for unrelated work, and is the modern Gradle recommendation.

**Layout:**
```
build-logic/
├── settings.gradle.kts          (rootProject.name = "build-logic")
├── build.gradle.kts             (kotlin-dsl plugin, depends on Kotlin/AGP/Ktor plugins)
└── src/main/kotlin/
    ├── nearyou.kotlin.jvm.gradle.kts
    ├── nearyou.kotlin.multiplatform.gradle.kts
    └── nearyou.ktor.gradle.kts
```

Root `settings.gradle.kts` adds `includeBuild("build-logic")` and pluginManagement allows the `nearyou.*` plugin IDs.

**Alternative considered:** `buildSrc/`. Rejected — global cache invalidation hurts iteration speed once the project grows.

**Alternative considered:** plain Kotlin extension functions in `buildSrc` instead of plugins. Rejected — plugins are idiomatic, support the `plugins { id("nearyou.kotlin.jvm") }` syntax, and integrate with IDE tooling.

### 2. Convention plugins read versions via the typesafe catalog accessor

In an included build, accessing `libs.versions.kotlin.get()` requires explicit wiring: `build-logic/settings.gradle.kts` declares `dependencyResolutionManagement.versionCatalogs.create("libs") { from(files("../gradle/libs.versions.toml")) }`. Plugins then access through the `VersionCatalogsExtension` lookup since the typesafe accessors aren't generated for precompiled script plugins. This is the standard workaround documented in Gradle issues.

### 3. ktlint via `org.jlleitschuh.gradle.ktlint`

Standard, mature, easily wired into convention plugins. Configured to run on `check` (so `./gradlew check` and `./gradlew build` both fail on style violations). No detekt for now (separate scope). ktlint version pinned in `libs.versions.toml`.

### 4. Flyway via the `org.flywaydb.flyway` Gradle plugin (`:backend:ktor` only)

Flyway plugin reads `flyway { url = …; user = …; password = … }`; we feed those from environment variables in the `nearyou.ktor` convention plugin so `./gradlew flywayMigrate` works locally with `DB_URL`/`DB_USER`/`DB_PASSWORD` set, and CI can do the same later. Defaults left empty: a developer who runs `flywayMigrate` without env vars sees a clear "URL is required" error rather than a silent connect to localhost.

`backend/ktor/src/main/resources/db/migration/V1__init.sql` is just `SELECT 1;` so the migration table (`flyway_schema_history`) gets created and the pipeline is provably wired. Real DDL lands in feature changes.

**Cloud Run Jobs `nearyou-migrate-staging` / `nearyou-migrate-prod`** (per Architecture doc) are NOT created here. Only the local invocation works. Wiring CI → Cloud Run Jobs is deferred to a "deploy-pipeline" change.

### 5. `secretKey(env, name)` is a stub helper, not a Secret Manager client

Signature lands now:
```kotlin
fun secretKey(env: String, name: String): String =
    if (env == "staging") "staging-$name" else name
```

It returns the *name* of the secret (the key), not the value. Resolution against GCP Secret Manager / env var fallback is added in the staging-bootstrap change. This forces every callsite to go through the namespacing rule from Day 1, even before a Secret Manager client exists.

**Why now?** Per the Roadmap risk table: "Staging secrets leak into production deployment → CI lint rule requiring `secretKey(env, name)` helper." Having the helper present means the CI lint rule (when it lands later) has something to point at.

### 6. Ktor app uses HOCON `application.conf`, not pure Kotlin config

HOCON is the Ktor default; all the docs assume it. `KTOR_ENV` is read via `${?KTOR_ENV}` substitution per the Architecture doc snippet. Switching to YAML or pure Kotlin config later is possible but offers no benefit now.

### 7. StatusPages: 5xx → JSON envelope, 4xx untouched

`{ "error": { "code": "...", "message": "..." } }` envelope on 5xx. 4xx pass-through (Ktor's status defaults are fine for now; per-feature handlers add specific 400/403/404 mappings). Concrete error codes are defined when each feature lands.

### 8. CI: GitHub Actions, single workflow, three jobs

```yaml
.github/workflows/ci.yml
├── lint   (./gradlew ktlintCheck)
├── build  (./gradlew assemble)
└── test   (./gradlew test)
```

JDK 21 (LTS), `actions/setup-java@v4` with `temurin`, Gradle wrapper, cache via `gradle/actions/setup-gradle@v4`. Triggered on `pull_request` to `main`. **No push trigger** — pushes to `main` will eventually trigger deploy jobs in a later change; keep this workflow PR-only.

### 9. `docs/09-Versions.md` is a stub, not a complete log

Just the schema + headings. Each future version bump gets a row. We don't backfill rationale for the wizard's existing pins — that would be archaeology.

## Risks / Trade-offs

- **Included-build kotlin-dsl is heavier** → adds ~5–10 s to the first configuration of a clean checkout. **Mitigation:** Gradle's configuration cache absorbs the cost on subsequent runs. Acceptable.
- **Convention plugins hide what each module is doing** → harder to read at a glance for someone unfamiliar with the project. **Mitigation:** keep convention plugins small (~30 LOC each) and well-named. Document them in `docs/04-Architecture.md` follow-up note (out of scope for this change; tracked).
- **ktlint version drift between IDE and CI** → developer sees no error locally, CI fails. **Mitigation:** pin ktlint version in `libs.versions.toml`; document IDE plugin install. Accept that some IDE/CI mismatch will surface and we'll fix it as it comes up.
- **Flyway placeholder migration `V1__init.sql` could be misinterpreted as the schema seed** → someone might add real DDL there instead of starting `V2`. **Mitigation:** comment header in the file explicitly says "intentional no-op; real schema starts at V2".
- **Health endpoints lie** → `/health/ready` returns 200 with no real probes; if Cloud Run probes it before deps are wired, traffic gets routed to a broken instance. **Mitigation:** Cloud Run isn't deployed in this change; `/health/ready` is upgraded in the same change that wires the first dependency (likely the Postgres/Supabase change).

## Migration Plan

1. Add `build-logic/` (settings, build, three precompiled script plugins).
2. Update root `settings.gradle.kts` with `includeBuild("build-logic")` and the `pluginManagement` block.
3. Extend `gradle/libs.versions.toml`.
4. Refactor each module's `build.gradle.kts` to apply the new convention plugin.
5. Add `:backend:ktor` Flyway config, `V1__init.sql`, `application.conf`, real `Application.kt`, `secretKey` helper.
6. Add `.github/workflows/ci.yml`.
7. Add `docs/09-Versions.md`.
8. Run locally:
   - `./gradlew ktlintCheck` (must pass — may need to format the wizard's code first)
   - `./gradlew build`
   - `./gradlew :backend:ktor:run` then `curl localhost:8080/health/live` returns 200
   - `DB_URL=jdbc:postgresql://localhost:5432/postgres DB_USER=postgres DB_PASSWORD=postgres ./gradlew :backend:ktor:flywayMigrate` against a local Postgres (Docker)

**Rollback:** revert the commit. No external state. Cloud Run isn't touched.

## Open Questions

- Should `:mobile:app`'s build script also apply a `nearyou.kotlin.multiplatform` convention plugin, or is the KMP+Compose+Android setup too app-specific to share with `:shared:tmp`? (Current decision: yes, share the KMP plugin; the Android-application-specific bits stay inline in `:mobile:app`'s build script.)
- ktlint config strictness: take the defaults or write a `.editorconfig` with explicit rules now? (Current decision: defaults. Tighten only if churn becomes annoying.)
- Should `application.conf` reference a `dev`-specific connection string by default so `./gradlew :backend:ktor:run` works without env vars? (Current decision: no — fail loud is better than connect to a wrong DB.)
