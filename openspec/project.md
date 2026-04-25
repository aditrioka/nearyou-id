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

**Test-data conventions:**
- **Inputs to integration tests against seeded reference tables** (`admin_regions`, `reserved_usernames`, etc.) MUST produce a deterministic outcome regardless of seed cardinality. A test that asserts "post at (-6.2, 106.8) → city_name NULL" is fragile because the assumption holds only while `admin_regions` is empty — once seeded, those coords become Jakarta. Either pick inputs that fall outside ALL seeded rows by construction (deep-ocean coords, unicode strings no real row uses) OR set up the test's reference data explicitly per-test rather than relying on the global seed state. Precedent: 3 timeline-test "legacy NULL row" assertions broke at V12 seed merge (PR #31) because the inputs used Jakarta-area coords; fix used `(-10.5, 105.0)` deep-Indian-Ocean coords that no kabupaten polygon covers.

---

## Change Delivery Workflow

Direct push to `main` is hook-blocked — every change ships via feature branch + PR + squash-merge.

**Change naming.** Kebab-case, descriptive, no `-v<N>` suffix. A change name describes what it adds, not which Flyway version it happens to bump — a change can ship zero or multiple migrations, and not every product change touches the schema. Pre-V7 archives follow this (`signup-flow`, `post-creation-geo`, `nearby-timeline-with-blocks`, `following-timeline-with-follow-cascade`); the V7–V9 trio (`post-likes-v7`, `post-replies-v8`, `reports-v9`) used an interim suffix that we're standardizing away from.

**Branch naming.**
- OpenSpec features: the change name itself (e.g., `post-reposts`, `notifications-api`).
- Archive: `openspec/archive-<change-name>`.
- Infra / tooling / CI / docs-only: `<area>/<slug>` (e.g., `ci/postgres-service`, `docs/workflow-conventions`).

**Sequence per OpenSpec change.** Every spec-driven product change ships as **ONE PR carrying the full lifecycle** (proposal → implementation → archive). Branch name = change name. The PR opens from `/next-change`, accumulates feat commits via `/opsx:apply`, accumulates the archive commit via `/opsx:archive`, and squash-merges ONCE at end-of-lifecycle. Result: **one commit on `main` per OpenSpec change** — not three.

1. **`/next-change` opens the PR** with the proposal commits (`docs(openspec): propose <change-name>`). Branch = change name. The skill scaffolds via `openspec-propose`, runs `openspec validate --strict`, runs the canonical-docs reconciliation pass, pushes, opens the PR, and drives the multi-lens review loop. Review-loop fixes land as new commits on the same branch.
2. **`/opsx:apply` pushes feat commits to the same branch.** When implementation begins, retitle the PR via `gh pr edit <pr> --title 'feat(<area>): <what>'` and update the body to reflect current scope. Do NOT open a new PR. CI runs on each push; the staging deploy runs only after the eventual squash-merge.
3. **`/opsx:archive` pushes the archive commit to the same branch.** `openspec archive <change>` is run locally; the resulting `openspec/specs/**` updates and the move of `openspec/changes/<change-name>/` under `archive/` are committed and pushed. Do NOT open a separate archive PR.
4. **Single squash-merge to `main` at end-of-lifecycle** — after the archive commit is pushed and CI is green. The squash produces ONE commit on `main` carrying the entire change (proposal + feat + archive in the squash body).

**Precedent transition.** The V5–V11 git log shows the OLD 3-PR shape (e.g., `global-timeline-with-region-polygons` shipped as PR #15 propose + PR #29/#31 feat + PR #35 archive — three separate squash-merges). That convention is **deprecated**. PR #37 (`like-rate-limit`) is the FIRST change slated to ship under the one-PR convention. Future contributors reading `git log` will see two regimes pre-#37 and post-#37.

**Iteration rule applies to ALL phases.** Push new commits to the same branch through proposal-review, implementation, AND archive. Do NOT open new PRs per phase. PR number stays stable from `/next-change` opening it through final squash-merge.

**PR title and body MUST stay current at every phase boundary** (this is a hard rule, not optional). The PR is the only artifact reviewers see at squash-merge time, so it has to describe the change as it stands NOW, not as it stood when `/next-change` first opened it. Phase boundaries to refresh at:

- **At proposal review completion** (after the 2-iteration review-loop cap settles): refresh the body to summarize blocking-vs-non-blocking findings applied + a "ready to start implementation" note.
- **At first feat commit** (when `/opsx:apply` lands its first implementation commit): retitle via `gh pr edit <pr> --title 'feat(<area>): <name>'` (or whichever conventional-commit prefix matches the dominant work — `feat(rate-limit)`, `feat(engagement)`, etc.), and update the body to a "in-progress implementation" shape that lists which sections are done.
- **At every subsequent section / sub-agent dispatch** that lands a non-trivial commit: update the body's progress table — it costs 30 seconds and saves the next reviewer a `git log` archaeology session.
- **At archive completion** (`/opsx:archive` finishes): update the body to a "merge-ready" shape — drop the in-progress framing, list final test counts + capability deltas + any post-merge tasks (e.g., staging smoke), and call out the convention (`one-PR-per-change`, `single squash-merge produces one commit on main`). The title may need a final retitle if the dominant prefix changed (e.g., archive-heavy `chore(openspec):` if appropriate, but `feat(<area>):` usually still fits).

Use `gh pr edit <pr> --body "$(cat <<'EOF' ... EOF)"` to push body updates — the heredoc preserves formatting. Title updates use `gh pr edit <pr> --title '<new-title>'`. Both commands are idempotent — running them twice with the same content is a no-op on GitHub.

Precedent: PR [#37](https://github.com/aditrioka/nearyou-id/pull/37) (`like-rate-limit`) shipped under this convention with multiple title/body refreshes across the lifecycle. Skipping the archive-phase refresh once required the user to manually request the body update — codifying it here keeps that gap closed.

**Review channels for the change PR.** Two complementary channels run in parallel (per `CLAUDE.md` § Reviewing a PR §7 + the `/next-change` skill Phase D):

- **qodo GitHub App** (auto, GitHub-side). Posts on every PR push, ~<1 min wall-clock. Quota-capped on the free tier; may be silent on docs-only PRs.
- **In-session sub-agent(s)** (skill-driven). For non-trivial proposals the skill author MAY dispatch **multiple parallel `general-purpose` sub-agents with different review lenses** — typically four: general / security-and-invariant / OpenSpec format-and-correctness / test-coverage. Each lens catches findings the others miss; PR [#37](https://github.com/aditrioka/nearyou-id/pull/37) round 1 confirmed this — the security lens caught 5 hardening items the general lens didn't surface, and the test-coverage lens caught 3 missing-scenario bugs the security lens didn't. After round 1, optionally dispatch a single round-2 **regression-scan sub-agent** ("did the round-1 fixes introduce orphan refs / break previously-correct scenarios?") to catch sweep-misses; the same PR's round 2 found 6 stale references the round-1 sweep missed.
- **Triviality SHOULD, not MUST.** Multi-lens dispatch is a SHOULD for non-trivial proposals, not a MUST. Trivial proposals (e.g., one-requirement spec tweaks) don't need 4 sub-agents — one general-lens dispatch is fine.
- **qodo absent at 6 min total** → proceed with sub-agent findings alone (already encoded in the skill).
- **"Apply all findings" (blocking + non-blocking) is a valid path** — the skill's `AskUserQuestion` in step 13 already exposes this option directly.

**CI gates per phase.** CI runs on every push to the unified branch. There is NO per-phase merge gate (proposal merge → feat PR open → feat merge → archive PR open) under the new convention; the gate is at the FINAL squash-merge. Practically: the proposal phase needs `openspec validate --strict` green; the feat phase needs full `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` green; the archive phase needs `openspec validate --specs <capability> --strict` green. All three are validated by the unified CI pipeline at squash time.

**Staging deploy timing.** `.github/workflows/deploy-staging.yml` triggers on `main` push. Under the one-PR convention that means staging deploys ONCE per change, after the squash-merge — not three times. Implementation tasks that depend on a staging-deploy-green signal (e.g., end-to-end smoke tests against staging) MUST run AFTER the squash-merge, NOT before; if a smoke test must run pre-merge, it runs against an ephemeral preview environment, not staging.

**Stacked PRs — mostly moot under the one-PR convention.** Historically (under the deprecated 3-PR shape), the proposal PR and the feat PR were separate, and stacking the feat PR on the as-yet-unmerged proposal branch was a known footgun (see "Two safe patterns" below). Under the one-PR-per-change convention, the proposal/feat/archive commits all live on the same branch — there's no parent-PR/child-PR pair to stack. The caveat below STILL applies, however, when one OpenSpec change genuinely depends on another in-flight change: the dependent branch will need to base off the parent branch and rebase onto `main` once the parent squash-merges.

The pre-#37 stacking footgun:

GitHub does NOT auto-retarget a child PR when its parent (base branch) is squash-merged. The squash creates a new commit on `main` whose hash differs from the child's parent ref, so the child stays pointed at the now-orphaned parent branch. Symptoms: child PR's "merge" button merges it into the parent branch instead of `main`, and the child's code never lands on `main` even though both PRs show "Merged" green in the UI.

**Two safe patterns:**
1. **Sequence, don't stack** (preferred): merge the parent PR to `main` first, then open the child PR with `base: main`. One PR open at a time per logical chain.
2. **If you must stack** (e.g., one is a low-priority docs PR you want pre-reviewed in parallel): the moment the parent PR merges, manually retarget the child via `gh pr edit <child> --base main` AND rebase the child branch onto `main` to drop the now-redundant parent commit (`git rebase main <child-branch>` — Git skips commits whose tree matches the squash-merged equivalent on `main`). Force-push with `--force-with-lease`.

**Recovery if a child PR was already merged into the orphaned parent branch:** rebase the child branch onto current `origin/main` (drops the redundant parent commit), force-push with `--force-with-lease`, open a new PR with the rebased branch as head and `main` as base. The merged-but-orphaned PR can be left as-is (it's noise but not destructive). Precedent: `feat/global-timeline-session-1` recovered via PR #29 after #27 merged into the docs branch instead of `main`.

**Splitting on an offline-prep boundary.** When a change has a heavy offline data-prep step (polygon seeds, ML model weights, large CSV/JSON fixtures, dataset license verification, etc.) that would block the code review of the rest of the change, the schema/code half MAY ship as a separate **session of commits** on the same change branch (under the one-PR convention) or as a separate feat PR (under the deprecated 3-PR convention). Required preconditions:

1. Target columns / response DTOs / business queries MUST be NULL-tolerant or empty-state-tolerant — there must be no window where a missing seed crashes a request or surfaces a malformed response.
2. The split MUST be documented in `design.md` (amend the relevant Decision in-place; reviewers should see the split rationale at archive time).
3. Session 1 + Session 2 commits MUST land on `main` (via the same final squash-merge under the one-PR convention) within the same calendar week — no "we'll seed it later" indefinite holds. If the data half slips, revert the schema commits and ship together.

Precedent (3-PR-era shape, deprecated): `global-timeline-with-region-polygons` shipped V11 (schema + trigger + view) as Session 1 PR #29 while the OSM dataset was being prepared offline; V12 (552-row polygon seed) followed as Session 2 PR #31. Under the new convention the same split would have been Session 1 commits + Session 2 commits on a single branch, then squash-merged once. Session-scope preamble lives in `tasks.md`, design amendment in `design.md` Decision 3 ("Status: amended during Session 2"). The 4-step trigger fallback ladder + NULL-as-`""` DTO mapping made the V11-only-deploy state safe.

**Archive commits touching shared specs.** When two changes both add requirements to the same canonical capability spec (e.g., both `coordinate-jitter-lint-rule` and `global-timeline-with-region-polygons` add to `openspec/specs/coordinate-jitter/spec.md`), their archive commits WILL conflict — `openspec archive` rewrites the spec from each change's delta independently, so each change's archive commit attempts a "main + my delta" rewrite, and the second to land sees a conflict on the shared file. Resolution under the one-PR convention:

1. **Squash-merge them sequentially**, not in parallel. The first change's PR to merge writes the canonical content; the second must rebase onto fresh `main` and resolve before its squash-merge.
2. **Resolution rule**: prefer the version that matches the actually-shipped runtime behaviour. Earlier-draft requirements in the second change that have been superseded by the first change's implementation should be DROPPED (with a one-line note in the resolving commit's message), not concatenated. Concatenating produces contradictory requirements that confuse future readers.
3. Force-push the rebased branch with `--force-with-lease` after `openspec validate --specs <capability> --strict` passes.

Precedent (3-PR-era): PR #34 (`coordinate-jitter-lint-rule` archive PR) merged first; PR #35 (`global-timeline-with-region-polygons` archive PR) hit a 5-vs-3 requirement conflict on `coordinate-jitter` spec. Resolution kept #34's 5 (canonical lint rule) + 2 of #35's 3 (trigger as DB-side sanctioned reader + reverse-geocode rationale); dropped #35's "Jitter-rule allowlist extended for V11 .sql file" requirement — superseded by the Kotlin-only rule design that #34 actually shipped. Under the new convention the same conflict would surface during the second change's pre-squash rebase, with identical resolution mechanics.

**When NOT to use OpenSpec.** Infra / tooling / CI / docs-only changes go through regular PRs. OpenSpec is for spec-driven product changes — capability + behavior + WHEN/THEN scenarios. Detekt rules, CI config, `build-logic/`, ops docs, READMEs: regular PR.

**Archive timing under the one-PR convention.** The archive commit lands on the change branch BEFORE the final squash-merge. Sequencing: feat commits land + CI green → archive commit lands + `openspec validate --specs <capability> --strict` green → squash-merge → staging deploys (one push to `main`, one staging deploy). The "wait for staging green before archiving" gate from the deprecated 3-PR convention shifts to "wait for staging green before declaring the change done" — the squash-merge IS the trigger that produces the staging deploy; you can't archive-after-staging when archive is part of the squashed commit. If staging fails post-merge, the hotfix ships as a NEW change (its own PR, its own one-PR lifecycle) — do not retroactively edit the squashed commit. Deploy tasks (typically 8.x in the task list) stay unchecked until *prod* infra is provisioned — don't block the squash on those.

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
