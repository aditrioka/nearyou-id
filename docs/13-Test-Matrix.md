# 13 — Test Matrix (layer → command → environment → stage)

**Status:** canonical. This is the **single source of truth** for *which* test/check runs *where* and at *which* stage. It is reconciled against the actual CI definition in [`../.github/workflows/`](../.github/workflows/) — when CI changes, update this file in the same PR. Skill prose about running tests (`verify-loop` §D, `openspec-apply-change`, `mobile-ui-foundation`, `audit-burndown`) points here instead of each carrying a partial, drifting copy.

The recurring failure this file prevents: an agent runs *a* gate, it greens, and a check that only runs in CI (or only with a particular tag filter) reds after merge — or a layer (iOS) that CI never gates drifts red undetected.

---

## 1. CI ground truth (`.github/workflows/`)

| Workflow | Trigger | Path scope | Jobs |
|---|---|---|---|
| **`ci.yml`** | `pull_request` → `main` | none — every PR triggers it (so `merge-gate` always reports); a `changes` job computes `code=true/false` to skip heavy lanes on docs-only diffs | `changes`, `readme-sync` (warn-only), `lint`, `test`, `migrate-supabase-parity`, `merge-gate` |
| **`deploy-staging.yml`** | `push` → `main` + `workflow_dispatch` | all main pushes | `deploy` (Docker build/push → `gcloud run deploy`, `RUN_FLYWAY_ON_STARTUP=true`) |
| **`device-run.yml`** | `pull_request` → `main` + `workflow_dispatch` | `mobile/**`, `shared/**`, `scripts/**`, self | `device-run` (build staging-debug APK + Firebase Test Lab **Robo** crawl, posts a PR comment; opt out with the `skip-device-run` label) |
| **`claude.yml`** | `@claude` mentions on issue/PR events | none | not a test job |

**`merge-gate`** (`needs: [changes, lint, test, migrate-supabase-parity]`, `if: always()`) is the **sole** ruleset-required status check. `changes` is in its `needs` on purpose, so a path-filter bug that silently skips every heavy lane cannot pass the gate by accident.

---

## 2. The matrix

| Check / test | Layer | Command (canonical) | Stage | In local pre-push gate? | CI-only? |
|---|---|---|---|---|---|
| ktlint | lint/static | `./gradlew ktlintCheck` | pre-merge | ✅ | no |
| detekt (incl. custom rules) | lint/static | `./gradlew detekt` (root task — no `:mobile:app:detekt`) | pre-merge | ✅ | no |
| detekt-rules unit tests | lint/static (JVM) | `:lint:detekt-rules:test` | pre-merge | ✅ | no |
| backend Ktor (JVM + DB + Redis) | backend unit + backend DB | `./gradlew test -Dkotest.tags='!network'` | pre-merge | ⚠️ partial — needs local PG/Redis | no |
| **Dockerfile ↔ settings module-copy guard** | infra/build | `./dev/scripts/check-dockerfile-module-copies.sh` | pre-merge (lint lane) | ❌ | **yes** |
| **admin static-asset integrity** | admin static | `sha256sum -c htmx.min.js.SHA256SUMS` + inventory diff (in `backend/ktor/.../admin/static/`) | pre-merge (lint lane) | ❌ | **yes** |
| **supabase-parity Flyway migrate** | migration | `flyway/flyway:10 migrate` on a parity-init DB | pre-merge | ❌ (Docker) | **yes** |
| README module sync | docs/static | `dev/scripts/sync-readme.sh --check` (warn-only) | pre-merge | ➖ manual | no |
| `verifyBusinessModulesNoOtel` | infra/static | runs via `./gradlew check`/`test` | pre-merge | ✅ (via `check`) | no |
| mobile JVM/Robolectric unit | mobile unit | `:mobile:app:testDevDebugUnitTest` + `:mobile:app:testDevReleaseUnitTest` (flavor-qualified) | pre-merge (in full `./gradlew test`) | ⚠️ add for mobile diffs | no |
| device Robo crawl | mobile instrumented | `scripts/run_on_device.sh` → Firebase Test Lab Robo | pre-merge (mobile/shared paths) | ➖ via script (needs creds) | surfaced in CI; not in gate |
| **iOS sim / K-Native** | iOS | `:module:iosSimulatorArm64Test`, `:module:linkDebugFrameworkIosSimulatorArm64` | — | ➖ **local-only** | **never CI-gated** |
| staging deploy + boot Flyway | staging/deploy | Docker build + `gcloud run deploy` | **post-merge** | ❌ | yes |

Legend: ✅ in gate · ❌ not in gate · ⚠️ in gate with caveat · ➖ not gate-shaped.

---

## 3. The exact backend tag filter — read this before claiming "CI-equivalent"

CI runs the backend lane as:

```
./gradlew test -Dkotest.tags='!network'
```

- It **excludes only `@Tags("network")`** specs (upstream JWKS flakes). **`@Tags("database")` specs RUN in CI** against the Postgres+PostGIS (`:5433`) + Redis (`:6379`) service containers.
- `!network` ≠ `!database`. A local run with `-Dkotest.tags='!database'` **skips the DB specs CI runs** → greens locally, reds in CI (this exact trap broke an admin nav-count test, [#355](https://github.com/aditrioka/nearyou-id/pull/355); see project memory `feedback_ci_test_lane_excludes_network_not_database`).
- A bare `:backend:ktor:test` (no `-Dkotest.tags`) runs **all** tags locally, so it is CI-equivalent on the tag axis *provided a reachable Postgres+Redis* — but see §5 for the dev-DB-pollution caveat.
- Comma is **not** a valid kotest tag separator (5.x grammar is `&`/`|`/`!`).
- **Run a single spec / subset:** `./gradlew :backend:ktor:test -Dkotest.filter.specs='*.SpecName'` — the `*.` dot-prefix is required (`*SpecName` matches nothing), comma lists don't work (run one at a time), and a non-matching filter **silently reports `BUILD SUCCESSFUL` with zero tests** → confirm `build/test-results/test/TEST-<fqcn>.xml` exists (project memory `reference_kotest_gradle_single_spec`). For an area subset use `--tests "id.nearyou.app.admin.*"`.

---

## 4. The local pre-push gate, and how it differs from CI

CLAUDE.md's pre-push gate:

```bash
./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test
# mobile diffs additionally:
./gradlew :mobile:app:testDevDebugUnitTest :mobile:app:testDevReleaseUnitTest
```

CI runs **more** than this. The gate does **not** cover (each has bitten `main` before):

1. **Dockerfile ↔ `settings.gradle.kts` module-copy guard** — a new non-mobile-gated `include(":infra:X")` without a matching Dockerfile `COPY` passes the gate + test lane but **breaks every staging/prod deploy** ([#313](https://github.com/aditrioka/nearyou-id/pull/313); memory `reference_backend_docker_copy_list_vs_settings`). Run `./dev/scripts/check-dockerfile-module-copies.sh` when you touch `settings.gradle.kts` or `infra/`.
2. **admin static-asset SHA256SUMS + inventory** — editing `backend/ktor/.../admin/static/*` without re-pinning the manifest fails CI's lint lane only ([#290](https://github.com/aditrioka/nearyou-id/pull/290); memory `reference_admin_static_asset_sha256_ci_check`). `shasum -a 256 -c htmx.min.js.SHA256SUMS` + refresh `admin.css` on every CSS edit.
3. **supabase-parity Flyway migrate** — Docker-based; catches migrations relying on un-established Supabase state.
4. **full `./gradlew test`** runs every `infra:*` + mobile JVM suite; the gate names only `:backend:ktor:test` + `:lint:detekt-rules:test`.
5. **iOS** is never CI-gated (the mobile CI lane is an Android device-run). iOS specs drift red on `main` undetected ([#348](https://github.com/aditrioka/nearyou-id/issues/348)/[#318](https://github.com/aditrioka/nearyou-id/issues/318)). Run `:module:iosSimulatorArm64Test` + `linkDebugFrameworkIosSimulatorArm64` locally when touching `:shared` actuals or iOS source.

---

## 5. CI-equivalent local run (avoid dev-DB false-fails)

Running the gate against the **long-lived dev DB** false-fails ~26 isolation-dependent specs (accumulated seed posts + Redis state; Search/Nearby/Global timelines, signup-FK, rate-limit counts) — and running it while a local `:backend:ktor:run` is alive exhausts the connection budget (memory `reference_full_gate_needs_fresh_db_containers`, verify-loop §D Known blockers). Use disposable containers:

```bash
docker run -d -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=nearyou_dev \
  -p 5440:5432 postgis/postgis:16-3.4
docker run -d -p 6390:6379 redis:7-alpine
DB_URL=jdbc:postgresql://localhost:5440/nearyou_dev DB_USER=postgres DB_PASSWORD=postgres \
  REDIS_URL=redis://localhost:6390 \
  ./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test
# Flyway applies on first boot; mirrors ci.yml's service containers. rm the containers after.
```

(For local *manual* verification with a reachable dev DB rather than the full gate, `docker start nearyouid-dev-postgres nearyouid-dev-redis` is fine — memory `feedback_prefer_existing_dev_containers`. Only the multi-spec gate needs fresh containers.)

---

## 6. Stage definitions + lane-skip traps

- **pre-merge** — runs on the PR via `ci.yml`; required via `merge-gate`.
- **post-merge / staging** — `deploy-staging.yml` on push to `main`; the squash-merge is a one-way door that auto-deploys. The image builds with `-PincludeMobile=false`, so a Dockerfile/module break is invisible to the CI test lane and only fails here (trap #1 above). Pre-archive staging smoke (`/opsx:apply` step 7) is the mitigation.
- **Lane-skip traps:**
  - **docs-only diffs** (the `changes` job's `grep -vE '^(docs/|.*\.md$|\.gitignore$|LICENSE$)'`) set `code=false` → `lint`/`test`/`parity` skip while `merge-gate` still passes. A docs-tick commit mid-`/opsx:apply` therefore gets **zero test signal** — don't push a docs tick before the code commit's CI finishes (memory `feedback_ci_concurrency_cancel_in_progress`).
  - **force-push after rebase** orphans `github.event.before` → "bad object" → empty diff → `code=false` → heavy lanes skip even with real `.kt` changes. Fix with a tiny fast-forward re-poke commit (memory `feedback_ci_force_push_orphans_before`).
