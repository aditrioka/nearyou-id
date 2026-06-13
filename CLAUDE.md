# CLAUDE.md

Project context for Claude running in Claude Code or the `anthropics/claude-code-action` GitHub Action. Read this every session, then defer to the full docs for anything load-bearing.

## What this project is

**nearyou-id** — location-based social media MVP (Indonesia, 18+ only). Text posts with location, nearby discovery, 1:1 chat, freemium + Premium. Modular monolith on Kotlin Multiplatform. Solo-operator build; pre-launch.

## Mobile-first to full-demo is the current priority (as of 2026-06-07)

The backend is MVP-ready (19 Flyway migrations, ~60 OpenSpec capabilities, Detekt-enforced invariants; Phase 1 + Phase 2 shipped). The 2026-05-12 mobile + admin **scaffolding** phase is complete — all 10 scaffold changes (5 mobile, 5 admin) merged. The chosen next priority (operator decision, 2026-06-07) is **finish the mobile app to a full-demo state, ahead of admin** — the backend already backs the full social loop, so mobile is the demo bottleneck. Until the mobile core loop is demoable end-to-end, `/next-change` and new OpenSpec work SHOULD bias toward **mobile feature picks**; admin (Phase 3.5) is deferred and backend hardening stays blocker-only (security invariant gap, pre-launch test requirement, or a dependency for the mobile work).

**Current state (descriptive):**

- **Mobile (`:mobile:app`)** — real Compose Multiplatform app (Navigation 3 + Koin DI + Material 3 light/dark theme + `:shared:resources` CMP Resources): Google sign-in, age gate, Nearby + Global timelines, post creation, home tab host, device-location + permission flow. In flight: analytics-consent (#157), post-detail (#159), bottom-nav-sections + notifications (#162). Remaining for a full demo: profile + follow, the live Following feed, chat, search, settings (the project.md live menu).
- **Admin (`backend/ktor/.../admin`)** — well past scaffold (schema V16, Argon2id + TOTP login, audit-log viewer, suspend/unban, report-queue triage + in-row resolution actions, rejected-identifiers viewer; UI intentionally unstyled so far). **Deferred** until mobile is demo-ready. The full target surface (existing + planned) is frozen visually in the admin mockup board (`docs/11` § 3.6).

**Forward direction.** The mobile contracts in `docs/02-Product.md` / `docs/03-UX-Design.md` / `docs/04-Architecture.md` are the spec source for the remaining screens. The **canonical visual reference** is the mockup board set [`dev/mockups/`](dev/mockups/README.md) — every UI-affecting change (whatever the skill: `/next-change`, `/opsx:apply`, `mobile-ui-foundation`, …) MUST consult the matching frame before building: render the HTML (browser / preview panel / screenshot tool — agent's choice), generate the per-frame **measurement annex** for exact spacing/typography/tokens/animation (`dev/scripts/mockup-measure.sh <board> <frame-no>` — `dev/mockups/README.md` step 4; on-demand output, never committed), and translate to the surface's idioms — Compose Multiplatform per `docs/11` § 2.8 (mobile boards), **Pebble + HTMX + vendored CSS per `docs/11` § 3.6** (admin board `nearyou-admin-mockup.html`, 23 frames covering every admin feature, shipped + planned). On behavior conflicts, specs/docs win over mockups. The live **mobile critical-path menu** (profile → following feed → chat → search → settings) + the trigger to flip out of mobile-first priority live at [`openspec/project.md`](openspec/project.md) § Mobile-First to Full-Demo Priority — read that menu before defaulting to an admin or backend pick.

## Canonical references

Start here, in priority order:

- [`openspec/project.md`](openspec/project.md) — tech stack, module structure, environments, **coding conventions + CI lint rules**, change delivery workflow, key architectural decisions.
- [`docs/11-Engineering-Standards.md`](docs/11-Engineering-Standards.md) — **architectural baseline + Definition of Done; MUST-read for every product change** (proposal + apply): mobile state/nav/data contracts, backend layering + JDBC/perf contracts, Pattern Registry (anti-patchwork rule), version currency policy.
- [`docs/00-README.md`](docs/00-README.md) — principles + cross-file reference map.
- [`docs/08-Roadmap-Risk.md`](docs/08-Roadmap-Risk.md) — phase ordering, risk register, open decisions. Many "should I do X?" answers live here.
- [`docs/05-Implementation.md`](docs/05-Implementation.md) — DB schemas, canonical SQL queries (Nearby / Following / Global timelines, block-action flow, rate-limit patterns), auth/session implementation, cache keys, feature flags.
- [`docs/04-Architecture.md`](docs/04-Architecture.md) — diagrams, module boundaries, deployment, observability, push, backup.
- [`docs/02-Product.md`](docs/02-Product.md) — feature specs (posts, timelines, social, chat, media, search, notifications).
- [`docs/06-Security-Privacy.md`](docs/06-Security-Privacy.md) — attestation, anti-spam, moderation, CSAM, UU PDP, age gate.
- [`openspec/specs/`](openspec/specs/) — authoritative specs for every shipped capability.
- [`openspec/changes/`](openspec/changes/) — in-flight changes (active + archive).

Before proposing non-trivial changes, skim the relevant doc. Many details (jitter math, token rotation, race-safe patterns, rate-limit layers) are load-bearing and not duplicated in CLAUDE.md.

## Critical invariants (don't violate these)

**Canonical for the 16 code-level invariants: [`openspec/project.md`](openspec/project.md) § "Coding Conventions & CI Lint Rules"** (each tied to a Detekt rule, allowlist mechanism, or schema CHECK). Read that section before editing code that might touch any of them. A 2026-05-07 audit found the list duplicated here verbatim (drift risk); it is now a pointer + summary, with `openspec/project.md` as the single canonical source.

The 16 cover (summary; for enforcement detail — Detekt rule names, allowlist regex, annotation syntax — read project.md):

- Shadow-ban safety (`visible_*` views, never raw `posts|users|...`)
- Block enforcement (bidirectional `user_blocks` NOT-IN join via `BlockExclusionJoinRule`)
- Spatial fuzzing (`display_location` only in non-admin paths)
- Client IP (`clientIp` request-context, never raw `X-Forwarded-For`)
- Rate-limit TTL (`computeTTLToNextReset(user_id)`, no hardcoded midnight math)
- Redis hash tags (`{scope:<value>}` for cluster-safe multi-key ops)
- Username + privacy-flag write allowlists (annotated comments required)
- Content length guards (post/reply 280, chat 2000)
- Admin sessions (`csrf_token_hash` mandatory; admin-user FKs `ON DELETE SET NULL`)
- Mobile strings via Compose Multiplatform Resources only
- Partial indexes: no `NOW()` in `WHERE`
- RLS changes: mandatory "JWT `sub` not in `public.users` → deny" test
- Secrets via `secretKey(env, name)` helper only
- No vendor SDK import outside `:infra:*`

**Two CLAUDE.md-only invariants (canonical here, not in project.md)** — they govern how we author *content*, not code, so they live with the AI-session entry-point file:

- **Public repository posture**: source-available under [FSL-1.1-ALv2](LICENSE) — assume external readers in commits, comments, PR bodies, code identifiers. Slot names + GCP project IDs + service-account emails are non-sensitive (matches the existing "secrets in Secret Manager, slot names in source" pattern); never inline real secret values, customer PII, or speculative commercial strategy. When in doubt, surface to the user before committing.
- **Root README module list is auto-generated** from [`settings.gradle.kts`](settings.gradle.kts) + [`dev/module-descriptions.txt`](dev/module-descriptions.txt). When a change adds a module: (a) add a one-line description to `dev/module-descriptions.txt`, (b) run `dev/scripts/sync-readme.sh --write`. CI runs `--check` (warning-only) to surface drift. Full trigger table: `openspec/project.md` § Documentation Maintenance.

Drift-detection: a new code-level invariant goes to `openspec/project.md` first; update the 16-item summary above only if it warrants top-of-mind awareness every session. New content/posture-level invariants land in the "two CLAUDE.md-only" section here.

## Engineering judgment over context budget

**Always prioritize engineering judgment over context-budget concerns.** Between (a) doing the spec'd work fully and (b) cutting scope to save tokens, choose (a). Context pressure is a signal to surface — not a license to silently degrade quality.

Concretely:

- **Never skip spec'd test scenarios with engineering-sounding rationalizations** ("structurally enforced", "covered by smoke", "low coverage delta", "would couple to internals"). Valid observations, but NOT standalone justifications for dropping a spec'd scenario — they require explicit user buy-in first.
- **Never silently compress a deferred-work list** to fit a fading context window. If you're writing "deferred to follow-up" / "skipped" / "out of scope" for items that ARE in scope of the current change, stop.
- **If context is genuinely tight, say so directly**: state "I'm at ~N% context; doing X fully will burn the remaining budget" and ask whether to (i) split into a fresh-context follow-up session, (ii) drop X explicitly with user buy-in, or (iii) push through. Never pick (ii) silently.
- **Documented debt is still debt.** Deferred-but-real findings are tracked as **GitHub issues labeled `follow-up`** (the root `FOLLOW_UPS.md` file was retired 2026-06-09; its verified backlog migrated to issues [#173–#205](https://github.com/aditrioka/nearyou-id/issues?q=label%3Afollow-up)). Filing a `follow-up` issue does not absolve an unauthorized deferral — follow-up issues are for genuinely-out-of-scope discoveries, not cover.
- **The default action is "ship the work."** When in doubt between deferring and doing, do.

This rule supersedes any apparent token-budget incentive and applies equally to the main session and spawned sub-agents. Why it exists: in the `health-check-endpoints` ship cycle, 7 of 9 deferred test items were rationalized post-hoc as engineering judgments when the actual constraint was context budget; the user caught it on review and codified this section. Future sessions MUST honor it.

## Delivery workflow

- **Branch naming**: OpenSpec features use the change name itself (kebab-case, no `-v<N>` suffix). Infra / tooling / CI / docs-only use `<area>/<slug>` (e.g., `ci/postgres-service`).
- **One PR carries the full OpenSpec lifecycle**: `/next-change` opens it with proposal commits (`docs(openspec): propose <change-name>`); `/opsx:apply` pushes feat commits to the **same branch** and retitles via `gh pr edit` (typically `feat(<area>): <what>`); `/opsx:archive` pushes the archive commit (move under `archive/` + spec sync) to the **same branch**; one squash-merge at end-of-lifecycle = ONE commit on `main` per change. Do NOT open new PRs per phase. Full lifecycle + the precedent-transition note (V5–V11 used the deprecated 3-PR shape; one-PR from PR #37 onwards): `openspec/project.md` § Change Delivery Workflow.
- **PR title and body MUST stay current at every phase boundary** (proposal review complete → first feat commit → each section landing → archive complete) via `gh pr edit <pr> --title '...'` / `--body "$(cat <<'EOF' ... EOF)"`. The PR description is what reviewers see at squash-merge time — it must match the change NOW, not as first opened. `/opsx:apply` and `/opsx:archive` both have explicit PR-update steps; full prescription: `openspec/project.md` § "PR title and body MUST stay current at every phase boundary".
- **Never skip hooks** (`--no-verify`, `--no-gpg-sign`, etc.).
- **No force-push to `main`**. `--force-with-lease` on topic branches is fine.
- **Direct push to `main` is hook-blocked** — every change ships via feature branch + PR + squash-merge.
- **Pre-push verification**: before pushing a feat / fix / chore branch, run `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` locally. CI runs **both** lint frameworks; passing only `detekt` is insufficient (precedent: PRs #31 + #32 hit CI lint failures after skipping `ktlintCheck` locally).

## Reviewing a PR (when invoked as a reviewer)

When running as a code reviewer (via `anthropics/claude-code-action` on a pull-request event, or an `@claude review ...` comment):

1. **Start with the right lens** — identify the PR type from title/description:
   - `docs(openspec): propose <name>` → proposal review. Check `proposal.md` for a clear Why, `design.md` for trade-off rationale, `specs/**` for ADDED/MODIFIED/REMOVED headers + at least one `#### Scenario:` per requirement, `tasks.md` for `- [ ] X.Y` checkbox format. Run/recommend `openspec validate <name> --strict`. Flag scope creep, missing capability deltas, unstated assumptions.
   - `feat(<area>): <what> (V<N>)` → implementation PR. Verify the diff matches `openspec/changes/<name>/tasks.md`; all relevant tests added; Flyway SQL matches the spec verbatim (canonical query shapes matter); conformance to `docs/11-Engineering-Standards.md` (an undeclared second pattern for a Pattern-Registry concern is a **blocking** finding); for UI-affecting changes, manual-verification evidence present in the PR body (docs/11 §5 DoD).
   - `chore(openspec): archive <name>` → verify `openspec/specs/**` updated and `openspec/changes/<name>/` moved under `archive/`.
   - Anything else (`ci:`, `fix:`, non-OpenSpec `docs:`) → standard review; correctness + the critical invariants above.
2. **Check the critical invariants** on every non-trivial diff. Cite violations explicitly — name the rule, link the convention.
3. **Respect the decision layer.** Things that look like bugs are often deliberate (e.g., the reply counter not applying viewer-block exclusion — a documented privacy tradeoff in the `post-likes-v7` + `post-replies-v8` specs). Before flagging, grep `openspec/specs/` and `docs/` for the topic.
4. **Don't nitpick what lint handles** (formatting, unused imports, `RawFromPostsRule`, `BlockExclusionJoinRule`, …). Focus on what a linter can't see: spec mismatches, subtle race conditions, missed invariants, architectural drift.
5. **Severity discipline.** "bug"/"critical" = actual incorrectness only; "suggestion" = style/alternatives; "question" = uncertain intent. Don't pad reviews with optional improvements when the change is correct.
6. **Output format**: structured, scannable. Cite OpenSpec findings by `### Requirement:` header; implementation findings by file:line. Use GitHub suggestion blocks (```` ```suggestion ````) where a concrete one-line fix exists.
7. **Self-authored PR? Spawn sub-agent(s) for an independent review pass.** When this session both wrote AND reviews the PR, in-session bias is real — you skim past your own stale references, missed allowlist entries, and spec/code drift because you remember the *intent*. For non-trivial PRs (anything beyond a one-line typo fix), invoke the `general-purpose` sub-agent with explicit pointers (PR URLs, context files, this playbook) and ask for a structured report under 600 words. Sub-agent findings are input to the review, not the review itself — you own severity calls. Precedent: in the global-timeline ship cycle, sub-agent review caught 2 stale follow-up references + a spec-scenario wording bug the author missed in self-review.
   **Multi-lens dispatch (SHOULD for non-trivial proposals).** For meatier proposals (new capability + multi-table schema + algorithm changes) MAY dispatch **multiple parallel `general-purpose` sub-agents with different review lenses** — typically four: general / security-and-invariant / OpenSpec format-and-correctness / test-coverage; each lens catches findings the others miss. Optionally one round-2 regression-scan sub-agent for orphan/stale refs the round-1 sweep missed. Severity-cap stays 2 iteration rounds; trivial proposals (one-requirement tweaks) don't need 4 sub-agents. Precedent: PR [#37](https://github.com/aditrioka/nearyou-id/pull/37) (`like-rate-limit`) — round 1's security lens caught 5 hardening items the general lens didn't, test-coverage caught 3 missing-scenario bugs the security lens didn't; round 2's regression scan surfaced 6 stale references.
8. **Reconcile proposals against canonical docs.** For any `docs(openspec): propose` PR: for every claim about schema (new columns/tables/CHECKs), algorithms (fallback ladders, rate-limit formulas, trigger bodies), or domain rules — find the canonical source (the specific `docs/<file>` or `openspec/specs/<capability>` section it cites or should cite) and verify exact alignment. Divergence without an explicit "amend docs" statement in `proposal.md` gets flagged. Post-merge divergence has happened before (PRs [#18](https://github.com/aditrioka/nearyou-id/pull/18)/[#19](https://github.com/aditrioka/nearyou-id/pull/19), the `global-timeline` reconciliation incident + skill/CLAUDE.md fix; [#24](https://github.com/aditrioka/nearyou-id/pull/24), the v10 notifications `body_data` catalog amendment) — reviewers are the last line of defense. When unsure, flag "does proposal match docs §X?" as a `question` — docs are canonical until proven otherwise.

## When NOT to use OpenSpec

Infra / tooling / CI / docs-only changes go through regular PRs. OpenSpec is for spec-driven product changes — capability + behavior + WHEN/THEN scenarios. Detekt rules, CI config, `build-logic/`, ops docs, READMEs: regular PR, regular commit prefix.

## Android build & test (cloud sandbox)

The Claude Code web/cloud sandbox is a headless Linux VM (no KVM/GPU), so it builds the mobile APK and dispatches instrumented tests to a **device farm** — it never runs a local emulator. Toolchain provisioning + dispatch live in [`scripts/`](scripts/) (all idempotent; full runbook + UI-only steps in [`ENVIRONMENT_SETUP_CHECKLIST.md`](ENVIRONMENT_SETUP_CHECKLIST.md)):

- **One-time / per-cache setup**: `scripts/setup_android.sh` — installs JDK 17, Android cmdline-tools, `platform-tools`, `platforms;android-35|36`, `build-tools;35.0.0|36.0.0` (36 = the repo's `compileSdk`), accepts licenses, and persists `JAVA_HOME` / `ANDROID_HOME` / `PATH` to `$CLAUDE_ENV_FILE`. Deliberately installs **no** `emulator` / `system-images` (useless headless). Gradle's compile toolchain stays `jvmToolchain(21)`, auto-detected from the pre-installed JDK 21.
- **Health check**: `scripts/verify_env.sh` — asserts `java`, `sdkmanager`, `adb`, `gradlew` + required SDK components; non-zero on any miss. Wired as a **`SessionStart` hook** in `.claude/settings.json`, so every session reports env health up front.
- **Build an APK locally**: `./gradlew :mobile:app:assembleStagingDebug` (device-farm flavor = **`staging`**: real `api-staging.nearyou.id`, side-by-side install, real staging OAuth client). Instrumented-test APK: `…:assembleStagingDebugAndroidTest`.
- **See a change running on a REAL device** (the vibe-code-from-your-phone loop): `scripts/run_on_device.sh` — builds the APK + does a Firebase Test Lab **Robo run** (auto UI-crawl, no test code) on a real Pixel and downloads the screenshots/video to `dev/device-runs/<ts>/` so the agent can send them back. Needs the Firebase creds below.
- **Run instrumented tests on a device farm**: `scripts/test_android.sh` is the parity wrapper — adb device attached → `connectedAndroidTest`; else → farm (`FARM=local|firebase|browserstack`). Direct entry points: `scripts/test_firebase.sh` (Firebase Test Lab; needs `GCP_SA_KEY_JSON` or `GOOGLE_APPLICATION_CREDENTIALS` + `FIREBASE_PROJECT_ID`) or `scripts/test_browserstack.sh` (BrowserStack App Automate; needs `BROWSERSTACK_USERNAME` + `BROWSERSTACK_ACCESS_KEY`). Credentials are env-var-only — never hard-coded.
- **Non-UI / Robolectric unit tests** still run on the JVM with no farm: `./gradlew :mobile:app:testStagingDebugUnitTest`.
- **Firebase Test Lab is the recommended device farm** (free tier for iteration, pay-per-use, same GCP/IAM as the rest of the stack — project `nearyou-staging`); BrowserStack is the optional fallback. One-time operator setup is a single command: `dev/scripts/provision-test-lab-sa.sh`. Full setup + cost: [`dev/docs/device-farm.md`](dev/docs/device-farm.md).
- **CI device runs**: `.github/workflows/device-run.yml` auto-builds + Robo-runs every `mobile/**` / `shared/**` PR on a real device and posts the screenshots/console link as a PR comment (opt out with the `skip-device-run` label). Needs the `GCP_TESTLAB_SA_KEY` repo secret (falls back to `GCP_SA_KEY`).

## Environments (summary)

- `dev` — local, Supabase CLI + Docker Compose (Ktor + Redis).
- `staging` — Cloud Run + Supabase Free + Upstash Free + RevenueCat sandbox. `*-staging.nearyou.id` subdomains. Synthetic data only.
- `production` — full-spec. `api|admin|img.nearyou.id`. Not live until Pre-Launch.

Secrets are env-namespaced in GCP Secret Manager (`staging-*` vs unprefixed prod). Mobile uses Android flavors / iOS xcconfig schemes.
