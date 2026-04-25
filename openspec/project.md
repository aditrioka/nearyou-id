# NearYouID — Project Context

Location-based social media MVP (Indonesia, 18+ only). Text posts with location, nearby discovery, 1:1 chat, freemium + Premium. Modular monolith on Kotlin Multiplatform. Pre-launch build (~19–20 weeks) with soft launch after Pre-Launch phase.

Full design lives in [`docs/`](../docs/). This file is the minimum context an AI session needs; defer to the docs for anything load-bearing.

---

## Guiding Principles

1. **Vibe coding first** — development is AI-assisted. Optimize for clean interfaces, isolated modules, and standard frameworks over clever abstractions.
2. **Minimize runtime cost** — flat/predictable pricing > pay-per-use. Free tier maximized pre-launch.
3. **Minimize maintenance overhead** — solo operator (Oka). Pick managed services over self-hosting when it causes ops headaches.
4. **Portable by design** — every vendor integration sits behind a `:infra:*` abstraction so migrations are frictionless.

See [`docs/00-README.md`](../docs/00-README.md) for the full principle statement and cross-file reference map.

---

## Tech Stack (summary)

| Layer | Choice |
|---|---|
| Mobile | Kotlin Multiplatform + Compose Multiplatform (Android + iOS) |
| Backend | Ktor on Google Cloud Run (Jakarta) |
| Admin Panel | Ktor server-side + Pebble/Freemarker + HTMX (stateful, NOT an SPA) |
| DB | Supabase Pro (PostgreSQL + PostGIS), Flyway migrations via Cloud Run Jobs |
| Auth | Google Sign-In + Apple Sign-In → Ktor RS256 JWT (REST) + HS256 (Supabase Realtime WSS) |
| Realtime chat | Supabase Realtime Broadcast (Months 1–14) → Ktor WebSocket + Upstash Redis Streams (Month 15+) behind `ChatRealtimeClient` |
| Cache / rate limit | Upstash Redis |
| Media | Cloudflare R2 (non-image, zero egress) + Cloudflare Images (`img.nearyou.id`) + CF CSAM Scanning Tool |
| Push | FCM (Android data-only; iOS alert + NSE) |
| Attestation | Play Integrity + App Attest |
| Subscription | RevenueCat |
| Feature flags | Firebase Remote Config |
| Email | Resend (transactional only) |
| Observability | Sentry KMP (errors), OpenTelemetry → Grafana Cloud (traces), GCP Monitoring (metrics), Amplitude (consent-gated product analytics) |
| Serialization | kotlinx.serialization |
| DI | Koin |

Version pinning lives in the *Version Pinning Decisions Log* (Pre-Phase 1). Full stack table + architecture diagrams: [`docs/04-Architecture.md`](../docs/04-Architecture.md).

---

## Module Structure (dependency isolation)

```
:core:domain              (pure Kotlin, zero vendor deps)
:core:data                (interfaces + DTOs)
:shared:distance, :shared:resources
:infra:supabase, :infra:r2, :infra:cloudflare-images,
:infra:revenuecat, :infra:redis, :infra:attestation,
:infra:resend, :infra:remote-config, :infra:otel,
:infra:sentry, :infra:amplitude,
:infra:postgres-neon      (Plan B scaffold, keep compilable)
:infra:ktor-ws            (post-swap chat, Month 14+)
:backend:ktor             (routes + Koin wiring)
:mobile:app
```

Backend modules inside `:backend:ktor`: user, post, social, chat, media, moderation, admin, internal, health. Details + `ChatRealtimeClient` interface in [`docs/04-Architecture.md`](../docs/04-Architecture.md).

Rule: **no vendor SDK import outside `:infra:*`**. Domain/data code depends only on interfaces.

---

## Environments

Three tiers, identical code, different secrets/config (Pre-Phase 1 bootstraps staging; production not live until Pre-Launch):

- `dev` — local, Supabase CLI + Docker Compose (Ktor + Redis)
- `staging` — Cloud Run + Supabase Free + Upstash Free + RevenueCat sandbox. Subdomains `api-staging|admin-staging|img-staging.nearyou.id`. Synthetic data only, nuke-safe.
- `production` — full-spec. Subdomains `api|admin|img.nearyou.id`.

Secrets namespaced by env prefix in GCP Secret Manager (`staging-*` vs unprefixed prod). Ktor reads `KTOR_ENV` and resolves secrets via the `secretKey(env, name)` helper — direct secret-name reads are a lint violation. Mobile uses Android flavors / iOS xcconfig schemes (`staging` vs `production`). Deploy flow: merge → `main` auto-deploys staging; git tag `v*` → prod after manual approval. Details: [`docs/04-Architecture.md`](../docs/04-Architecture.md) § Deployment.

---

## Coding Conventions & CI Lint Rules

Enforced by CI (see [`docs/08-Roadmap-Risk.md`](../docs/08-Roadmap-Risk.md) § Development Tools for the full list). Notable:

- **Shadow-ban safety**: business code must query `visible_*` views, never raw `FROM posts|users|post_replies|chat_messages`. Raw reads allowed only in Repository own-content paths and the admin module.
- **Block enforcement**: business queries touching posts/users/chat_messages/post_replies must include the block-exclusion join.
- **Spatial**: non-admin paths must use `fuzzed_location`, never `actual_location`, for `ST_DWithin`/`ST_Distance`.
- **Client IP**: use the `clientIp` request-context value (populated by Cloudflare-aware middleware reading `CF-Connecting-IP`). Direct `X-Forwarded-For` reads are forbidden.
- **Rate-limit TTL**: must call `computeTTLToNextReset(user_id)` (per-user WIB midnight stagger). No hardcoded midnight math.
- **Redis keys**: must include hash tag `{scope:<value>}` for cluster-safe multi-key ops.
- **Username writes**: `UPDATE users SET username = ...` allowed only in signup flow + the single Premium customization transaction + admin module. Legitimate writers annotate `// @allow-username-write: signup|customization`.
- **Privacy flag writes**: `UPDATE users SET private_profile_opt_in = FALSE` only in the privacy flip worker + Settings flow. Annotate `// @allow-privacy-write: worker|user_settings`.
- **Content length guards**: input endpoints must length-check (post/reply 280 chars, etc.).
- **Admin sessions**: every `INSERT INTO admin_sessions` must populate `csrf_token_hash`.
- **Admin-user FKs** on operational tables must use `ON DELETE SET NULL`.
- **Mobile strings**: no hardcoded UI strings; must go through Moko Resources.
- **Partial indexes**: no `NOW()` in `WHERE` (non-immutable; PG rejects).
- **RLS changes**: mandatory test case "JWT `sub` not in `public.users` → deny" on every policy change.

Other conventions:
- API versioning: `/api/v1/...` from day one.
- Code style: ktlint.
- Tests: Kotest/JUnit5, Ktor test framework, Docker-based integration tests.
- DB role separation: `main_app`, `admin_app`, `flyway_migrator` — each its own Secret Manager slot. DDL only via `flyway_migrator`.

---

## Change Delivery Workflow

Direct push to `main` is hook-blocked — every change ships via feature branch + PR + squash-merge.

**Change naming.** Kebab-case, descriptive, no `-v<N>` suffix. A change name describes what it adds, not which Flyway version it happens to bump — a change can ship zero or multiple migrations, and not every product change touches the schema. Pre-V7 archives follow this (`signup-flow`, `post-creation-geo`, `nearby-timeline-with-blocks`, `following-timeline-with-follow-cascade`); the V7–V9 trio (`post-likes-v7`, `post-replies-v8`, `reports-v9`) used an interim suffix that we're standardizing away from.

**Branch naming.**
- OpenSpec features: the change name itself (e.g., `post-reposts`, `notifications-api`).
- Archive: `openspec/archive-<change-name>`.
- Infra / tooling / CI / docs-only: `<area>/<slug>` (e.g., `ci/postgres-service`, `docs/workflow-conventions`).

**Sequence per OpenSpec change.**
1. **Feature PR** — feat commit(s) on the change branch. Squash-merge after CI green.
2. **Archive PR** — separate branch, run `openspec archive <change>` locally, docs-only PR, squash-merge. Produces the "one feat commit + one archive commit" pair on `main` visible in the V5–V8 git log precedent.

**Stacked PRs — avoid by default.** GitHub does NOT auto-retarget a child PR when its parent (base branch) is squash-merged. The squash creates a new commit on `main` whose hash differs from the child's parent ref, so the child stays pointed at the now-orphaned parent branch. Symptoms: child PR's "merge" button merges it into the parent branch instead of `main`, and the child's code never lands on `main` even though both PRs show "Merged" green in the UI.

**Two safe patterns:**
1. **Sequence, don't stack** (preferred): merge the parent PR to `main` first, then open the child PR with `base: main`. One PR open at a time per logical chain.
2. **If you must stack** (e.g., one is a low-priority docs PR you want pre-reviewed in parallel): the moment the parent PR merges, manually retarget the child via `gh pr edit <child> --base main` AND rebase the child branch onto `main` to drop the now-redundant parent commit (`git rebase main <child-branch>` — Git skips commits whose tree matches the squash-merged equivalent on `main`). Force-push with `--force-with-lease`.

**Recovery if a child PR was already merged into the orphaned parent branch:** rebase the child branch onto current `origin/main` (drops the redundant parent commit), force-push with `--force-with-lease`, open a new PR with the rebased branch as head and `main` as base. The merged-but-orphaned PR can be left as-is (it's noise but not destructive). Precedent: `feat/global-timeline-session-1` recovered via PR #29 after #27 merged into the docs branch instead of `main`.

**When NOT to use OpenSpec.** Infra / tooling / CI / docs-only changes go through regular PRs. OpenSpec is for spec-driven product changes — capability + behavior + WHEN/THEN scenarios. Detekt rules, CI config, `build-logic/`, ops docs, READMEs: regular PR.

**Archive timing.** Right after the feat PR merges + CI green + the staging deploy triggered by that merge reports green (see `.github/workflows/deploy-staging.yml`). Not after prod ship. If staging fails post-merge, ship the hotfix first and archive only once staging is healthy — archiving against a broken staging locks a not-actually-working spec into `openspec/specs/` as the baseline the next proposal builds on. Deploy tasks (typically 8.x in the task list) stay unchecked until *prod* infra is provisioned — don't block archive on those.

**Force-push.** `--force-with-lease` on topic branches is fine (rewrite your own history freely). `main` requires explicit per-push user authorization — the hook enforces this.

**CI expectations.** See [`.github/workflows/ci.yml`](../.github/workflows/ci.yml). Four jobs — `lint` (ktlint), `build` (assemble), `test` (full suite including `@Tags("database")`), and `migrate-supabase-parity`. Test job uses a `postgis/postgis:16-3.4` service container mirroring `dev/docker-compose.yml`; DB tests auto-boot Flyway V1..V9 once per test JVM via `KotestProjectConfig.beforeProject()` — don't add per-spec Flyway migrate calls. The `migrate-supabase-parity` job drops auto-enabled extensions and pre-seeds Supabase's `realtime` / `auth` schema surface (see `dev/supabase-parity-init.sql`) before running Flyway, catching migrations that depend on environment state they don't establish themselves — extend the parity init SQL alongside any new migration that assumes new Supabase-provided state. `concurrency: cancel-in-progress` aborts stale runs on new pushes.

---

## Key Architectural Decisions

- **Modular monolith**, not microservices. One Ktor deployable.
- **Dual JWT**: RS256 for REST (with JWKS + kid rotation) + HS256 for Supabase Realtime WSS. Third-Party Auth migration is trivial later. See [`docs/05-Implementation.md`](../docs/05-Implementation.md) § Authentication.
- **Coordinate fuzzing**: posts store both `actual_location` and `fuzzed_location` (HMAC-SHA256 jitter with `JITTER_SECRET`). Non-admin reads use fuzzed only.
- **Chat write path is Ktor-authoritative**: client writes REST → Ktor persists → Ktor broadcasts. No direct Postgres Changes subscription from clients.
- **CSAM trigger path**: Cloudflare CSAM Tool does NOT emit webhooks. MVP path is admin-triggered from the Admin Panel after CF's daily email; Phase 2 adds an optional CF Worker forwarder.
- **18+ only + under-18 blocklist** (`rejected_identifiers`); no account recovery by design.
- **Backups**: Supabase PITR 7-day + weekly `pg_dump` encrypted via `age` to R2 + append-only deletion log (7-year retention).

---

## Doc Map

| File | Scope |
|---|---|
| [`docs/00-README.md`](../docs/00-README.md) | Overview, principles, cross-references |
| [`docs/01-Business.md`](../docs/01-Business.md) | Freemium tiers, pricing, payments, ads, GTM, cost forecast |
| [`docs/02-Product.md`](../docs/02-Product.md) | Feature specs (posts, timeline, social, chat, media, search, notifications) |
| [`docs/03-UX-Design.md`](../docs/03-UX-Design.md) | Copy, onboarding, empty/permission/consent flows |
| [`docs/04-Architecture.md`](../docs/04-Architecture.md) | Tech stack, diagrams, modules, deployment, observability, backup, push |
| [`docs/05-Implementation.md`](../docs/05-Implementation.md) | DB schemas, algorithms, auth/session, rate limits, cache keys, feature flags |
| [`docs/06-Security-Privacy.md`](../docs/06-Security-Privacy.md) | Attestation, anti-spam, moderation, CSAM, UU PDP, age gate |
| [`docs/07-Operations.md`](../docs/07-Operations.md) | Admin panel stack, admin schema, IAP/Cloud Armor/WebAuthn |
| [`docs/08-Roadmap-Risk.md`](../docs/08-Roadmap-Risk.md) | Phases, CI lint rules, risk register, open decisions |

Before making a non-trivial change, skim the relevant doc — many details (jitter, rotation, race-safe patterns, rate-limit layers) are load-bearing and not duplicated here.
