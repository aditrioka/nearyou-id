# Holistic Audit — 2026-06-10

**Trigger (operator-reported problems):**
1. AI-built features pass build/CI but show bugs + deviations from modern Android/KMP/Ktor best practice on manual testing.
2. Features built across multiple `/next-change` cycles come out patchwork (e.g., home screen UI) — later components don't fit the skeleton built earlier.

**Mandate:** run all 3 phases sequentially without confirmation stops; document every significant decision here; flag ambiguous/risky calls for operator review at the end.

## Phase status

| Phase | Scope | Status |
|---|---|---|
| 1 — Research | Verify project standards against 2026-current CMP/M3/Nav3/KMP-libs/Ktor state (web search, dated) | DONE — consolidated into `docs/11-Engineering-Standards.md` |
| 2 — Workflow audit | Fix lifecycle gaps causing the two problems; audit + update skills/docs (Phase-1 alignment, SE principles, concision) | IN PROGRESS |
| 3 — Codebase review | Checklist-driven review+fix: backend Ktor, mobile shared, androidMain/iosMain | PENDING |

## Decision log

- **D1 (artifacts location):** audit artifacts live in `dev/audits/2026-06-10-holistic-audit/` (PROGRESS.md + CHECKLIST.md), committed to the branch so they survive session interruption and ship with the PR. Public-repo posture respected (no secrets, no PII).
- **D2 (delivery shape):** ONE branch, ONE PR (`audit/holistic-2026-06`, pushed from this worktree via `git push origin HEAD:audit/holistic-2026-06`), structured commits per phase/area. Rationale: single narrative for operator review via this file; avoids cross-PR conflicts between docs/skills edits and code fixes; this is an infra/tooling-shaped engagement (regular PR, not OpenSpec). FLAGGED: operator may prefer splitting at review time — commits are structured to allow cherry-pick splitting if so.
- **D3 (baseline):** review targets the worktree base (origin/main @ 3179aa7). Open PRs at audit start: only #165 (docs) + #168 (skill) — no open mobile/backend code PRs, so Phase 3 fixes are conflict-safe.
- **D4 (build on existing substrate, don't duplicate):** the patchwork problem was already partially attacked by #167 (`mobile-design-system` capability + home-shell redesign) and open PR #168 (`mobile-ui-foundation` skill, visual/UX layer). The new `docs/11-Engineering-Standards.md` deliberately covers the layers those DON'T: state management, Navigation 3 contract, data layer, package layout, backend architecture/perf, version policy, DoD gates — and references them rather than restating. FLAGGED: merge #168 — the standards doc + skill wiring reference `mobile-ui-foundation`.
- **D5 (version bumps — now vs deferred):** bump NOW (patch/minor, test-gated, CVE or direct-bug drivers): pgjdbc 42.7.11 (CVE-2026-42198), Ktor 3.5.0 (3.4.3 fixed a CallLogging/HttpRequestLifecycle cascade bug in our exact plugin set), Kotlin 2.3.21, kotlinx trio (coroutines/serialization/datetime 1.11.0/1.11.0/0.8.0), material3 1.11.0-alpha07 (CMP-1.11-aligned), OTel 1.63.0 + 2.28.1-alpha (CVE-2026-33701), Lettuce 6.8.2, Hikari 6.3.3. DEFER as deliberate planned upgrades: Kotlin 2.4.0 (1 week old), AGP 8.13/9, kotest 6 (breaking, 118 test files), Flyway 12, Hikari 7, Lettuce 7, DataStore 1.2.1-KMP. Full table in docs/11 §1. FLAGGED: Ktor 3.5.0 chosen over conservative 3.4.3 — full backend test suite is the gate; fallback to 3.4.3 if red.
- **D6 (mobile package restructure):** docs/11 §2.1 defines the target feature-package layout; the flat 35-file `screens/` package is declared legacy. Phase 3 M1 will do mechanical moves only (package decl + imports, zero logic edits, full test gate) and only where coupling analysis says it's low-risk. FLAGGED for review: extent of the restructure.

## Flagged for operator review

(populated as decisions accumulate; summarized at end of engagement)

## Phase 1 — Research findings (2026-06-10, 5 parallel dated-WebSearch agents)

Full consolidated output → **`docs/11-Engineering-Standards.md`**. Headlines:

- **Already current:** CMP 1.11.1, Nav3 (JetBrains) 1.1.1, Koin 4.2.1, lifecycle 2.10.0, compileSdk 36. The project's substrate choices (Nav3, CMP Resources, Credential Manager, DataStore+Tink, Netty, kotlinx.serialization, Redis-custom rate limiting) all re-verified as 2026-canonical.
- **Behind:** pgjdbc (security CVE), Ktor (3.4.3 fixed a bug in our exact plugin combo), kotlinx trio, JetBrains material3 alignment, OTel (CVE in instrumentation line), Lettuce/Hikari patch lines. → D5.
- **Practice deltas our code may violate** (Phase 3 hunting list): v1 `runComposeUiTest` now deprecated (CMP 1.11); Nav3-on-KMP requires polymorphic `SerializersModule` for NavKey restore on iOS; decorator order saveable→viewmodel; one-shot events must be state-driven (no Channel/SharedFlow buses); JDBC must run on a pool-sized bounded dispatcher (not raw `Dispatchers.IO`); Supavisor transaction mode needs `prepareThreshold=0`; `stabilityConfigurationFile` API renamed (plural); edge-to-edge mandatory at targetSdk 36 (no status/nav bar color setters); Json server defaults (`ignoreUnknownKeys`/`explicitNulls=false`/`encodeDefaults=false`).
- **Repo-map confirmations of the patchwork complaint:** flat `screens/` package (35 mixed files: nav + effects + VMs + screens), design system = 1 theme file with components inlined per screen, mixed state-holder patterns (3 androidx ViewModels vs many custom `*Flow` holders), `InvalidCursorException` duplicated in 2+ packages.

## Phase 2 — Changes applied

(pending)

## Phase 3 — Review log

(pending — per-area findings + fixes; work-list state lives in CHECKLIST.md)
