# CLAUDE.md

Project context for Claude running in Claude Code or the `anthropics/claude-code-action` GitHub Action. Read this every session, then defer to the full docs for anything load-bearing.

## What this project is

**nearyou-id** — location-based social media MVP (Indonesia, 18+ only). Text posts with location, nearby discovery, 1:1 chat, freemium + Premium. Modular monolith on Kotlin Multiplatform. Solo-operator build; pre-launch.

## Canonical references

Start here, in priority order:

- [`openspec/project.md`](openspec/project.md) — tech stack, module structure, environments, **coding conventions + CI lint rules**, change delivery workflow, key architectural decisions.
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

These are enforced by CI lint rules. See `openspec/project.md` § "Coding Conventions & CI Lint Rules" for the full list + exact allowlist mechanisms.

- **Shadow-ban safety**: business queries MUST read `visible_posts` / `visible_users`, never raw `FROM posts|users|post_replies|chat_messages`. Raw reads are allowed only in Repository own-content paths, the admin module, and the `ReportService` target-existence helper.
- **Block enforcement**: authenticated queries touching `posts | visible_posts | users | chat_messages | post_replies` MUST include the bidirectional `user_blocks` NOT-IN join (`blocker_id = :viewer` AND `blocked_id = :viewer`). Enforced by the Detekt rule `BlockExclusionJoinRule`.
- **Spatial**: non-admin paths MUST use `display_location` (fuzzed), never `actual_location`, for `ST_DWithin` / `ST_Distance`. Exceptions: admin module + the one sanctioned reverse-geocode path (`posts_set_city_tg` trigger, DB-side only).
- **Client IP**: read via the `clientIp` request-context value (populated from `CF-Connecting-IP`). Direct `X-Forwarded-For` reads are forbidden.
- **Rate-limit TTL**: call `computeTTLToNextReset(user_id)` for per-user daily WIB-midnight stagger. No hardcoded midnight math.
- **Redis keys**: must include hash tag `{scope:<value>}` for cluster-safe multi-key ops.
- **Username writes**: `UPDATE users SET username = ...` is allowed only in signup flow, the single Premium customization transaction, and the admin module. Legitimate writers annotate `// @allow-username-write: signup|customization`.
- **Privacy-flag writes**: `UPDATE users SET private_profile_opt_in = FALSE` is allowed only in the privacy-flip worker and Settings. Annotate `// @allow-privacy-write: worker|user_settings`.
- **Content length guards**: input endpoints must length-check (post/reply 280 chars, chat 2000).
- **Admin sessions**: every `INSERT INTO admin_sessions` must populate `csrf_token_hash`.
- **Admin-user FKs** on operational tables must use `ON DELETE SET NULL`.
- **Mobile strings**: no hardcoded UI strings; must go through Moko Resources.
- **Partial indexes**: no `NOW()` in `WHERE` (non-immutable; PG rejects).
- **RLS changes**: mandatory test case "JWT `sub` not in `public.users` → deny" on every policy change.
- **Secrets**: Ktor MUST read via the `secretKey(env, name)` helper. Direct secret-name reads are a lint violation.
- **No vendor SDK import outside `:infra:*`** — domain/data code depends only on interfaces.

## Engineering judgment over context budget

**Always prioritize engineering judgment over context-budget concerns.** When deciding between (a) doing the spec'd work fully and (b) cutting scope to save tokens, choose (a). Context pressure is a signal to surface — not a license to silently degrade quality.

Concretely:

- **Never skip spec'd test scenarios with engineering-sounding rationalizations** like "structurally enforced", "covered by smoke", "low coverage delta", "would couple to internals". These are valid engineering observations but NOT standalone justifications for dropping a spec'd scenario. They require explicit user buy-in first.
- **Never silently compress a deferred-work list** to fit a fading context window. If you find yourself writing "deferred to follow-up" / "skipped" / "out of scope" for items that ARE in scope of the current change, stop.
- **If context is genuinely tight, say so directly.** State "I'm at ~N% context; doing X fully will burn the remaining budget" and ask whether to (i) split into a follow-up session with fresh context, (ii) drop X explicitly with user buy-in, or (iii) push through. Do NOT pick (ii) silently.
- **Documented debt is still debt.** A `FOLLOW_UPS.md` entry does not absolve a deferral that wasn't explicitly authorized by the user. The follow-up file is for genuinely-out-of-scope discoveries, not for cover.
- **The default action is "ship the work."** When in doubt between deferring and doing, do.

This rule supersedes any apparent token-budget incentive. It applies equally to the main session and to spawned sub-agents.

Why this exists: in the `health-check-endpoints` ship cycle, 7 of 9 deferred test items were rationalized post-hoc as engineering judgments when the actual constraint was context budget. The user caught it on review and codified the rule (this section). Future Claude sessions in this repo MUST honor it.

## Delivery workflow

- **Branch naming**: OpenSpec features use the change name itself (kebab-case, no `-v<N>` suffix). Infra / tooling / CI / docs-only use `<area>/<slug>` (e.g., `ci/postgres-service`).
- **Sequence per OpenSpec change** — **ONE PR carries the full lifecycle.** `/next-change` opens the PR with proposal commits (`docs(openspec): propose <change-name>`); `/opsx:apply` pushes feat commits to the **same branch** and retitles via `gh pr edit` (typically to `feat(<area>): <what>`); `/opsx:archive` pushes the archive commit (move under `archive/` + spec sync) to the **same branch**; final **single squash-merge** at end-of-lifecycle produces ONE commit on `main` per change. See `openspec/project.md` § Change Delivery Workflow for the full lifecycle, the precedent-transition note (V5–V11 followed the deprecated 3-PR shape; PR #37 onwards uses the one-PR convention), and the iteration rule (do NOT open new PRs per phase — push commits to the same branch through proposal-review, implementation, and archive).
- **PR title and body MUST stay current at every phase boundary** (proposal review complete → first feat commit → each section landing → archive complete). Use `gh pr edit <pr> --title '...'` and `gh pr edit <pr> --body "$(cat <<'EOF' ... EOF)"`. The PR description is what reviewers see at squash-merge time — it has to match the change as it stands NOW, not as it stood when `/next-change` first opened it. The `/opsx:apply` and `/opsx:archive` skills both have explicit PR-update steps for this; see `openspec/project.md` § "PR title and body MUST stay current at every phase boundary" for the full prescription.
- **Never skip hooks** (`--no-verify`, `--no-gpg-sign`, etc.).
- **No force-push to `main`**. `--force-with-lease` on topic branches is fine.
- **Direct push to `main` is hook-blocked** — every change ships via feature branch + PR + squash-merge.
- **Pre-push verification**: before pushing a feat / fix / chore branch, run `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` locally. CI runs **both** lint frameworks (`ktlintCheck` + `detekt`); passing only `detekt` is insufficient. Precedent: PR #31 + #32 hit CI lint failures because `ktlintCheck` was skipped locally.

## Reviewing a PR (when invoked as a reviewer)

When running as a code reviewer (via `anthropics/claude-code-action` on a pull request event, or via an `@claude review ...` comment):

1. **Start with the right lens.** Identify the PR type from the title/description:
   - `docs(openspec): propose <name>` → proposal review. Check `proposal.md` for a clear Why, `design.md` for trade-off rationale, `specs/**` for ADDED/MODIFIED/REMOVED headers + at least one `#### Scenario:` per requirement, `tasks.md` for `- [ ] X.Y` checkbox format. Run/recommend `openspec validate <name> --strict`. Flag scope creep, missing capability deltas, unstated assumptions.
   - `feat(<area>): <what> (V<N>)` → implementation PR. Verify the spec/task mapping: does the diff match `openspec/changes/<name>/tasks.md`? Are all relevant tests added? Does the SQL in the new Flyway migration match the spec requirements verbatim (canonical query shapes matter here)?
   - `chore(openspec): archive <name>` → archive PR. Verify `openspec/specs/**` is now updated and `openspec/changes/<name>/` is moved under `archive/`.
   - Anything else (`ci:`, `fix:`, `docs:` non-OpenSpec) → standard review; focus on correctness + the critical invariants above.

2. **Check the critical invariants.** For every non-trivial code diff, check whether the change respects the invariant list above. Be explicit when citing a violation — name the rule, link the convention.

3. **Respect the decision layer.** Many things that look like bugs are deliberate (e.g., "why doesn't the reply counter apply viewer-block exclusion?" — privacy tradeoff, documented in `post-likes-v7` + `post-replies-v8` specs). Before flagging, grep `openspec/specs/` and `docs/` for the topic.

4. **Don't nitpick what lint handles.** ktlint + Detekt already catch formatting, unused imports, `RawFromPostsRule`, `BlockExclusionJoinRule`, etc. Don't duplicate that surface; focus on things a linter can't see (spec mismatches, subtle race conditions, missed invariants, architectural drift).

5. **Severity discipline.** Reserve "bug" / "critical" for actual incorrectness. Use "suggestion" for style / alternatives. Use "question" when you're uncertain about intent. Avoid padding the review with optional improvements when the change is correct.

6. **Output format.** Structured, scannable. For OpenSpec changes, cite the requirement by its `### Requirement:` header when flagging. For implementation, cite the file:line. Use GitHub suggestion blocks (```` ```suggestion ````) where a concrete one-line fix exists.

7. **Self-authored PR? Spawn sub-agent(s) for an independent review pass.** When this Claude session both wrote AND is reviewing the PR, in-session bias is real — you skim past your own stale references, missed allowlist entries, and spec/code drift because you remember the *intent*. For non-trivial PRs (anything beyond a one-line typo fix), invoke the `general-purpose` sub-agent with explicit pointers (PR URLs, context files, the reviewer playbook below) and ask for a structured report under 600 words. Treat the sub-agent's findings as input to the review, not as the review itself — you still own severity calls. Precedent: in the global-timeline ship cycle, sub-agent review caught 2 stale `FOLLOW_UPS.md` refs + a spec-scenario wording bug that this author missed in self-review.

   **Multi-lens dispatch (SHOULD for non-trivial proposals).** For meatier proposals (e.g., new capability + multi-table schema + algorithm changes) you MAY dispatch **multiple parallel `general-purpose` sub-agents with different review lenses** — typically four: general / security-and-invariant / OpenSpec format-and-correctness / test-coverage. Each lens catches findings the others miss. After round 1, optionally dispatch a single **round-2 regression-scan sub-agent** to find orphan refs and stale references the round-1 sweep missed. Severity-cap remains 2 iteration rounds. This is a SHOULD, not a MUST — trivial proposals (one-requirement spec tweaks) don't need 4 sub-agents. Precedent: PR [#37](https://github.com/aditrioka/nearyou-id/pull/37) (`like-rate-limit`) — round 1 ran 4 parallel lens sub-agents (security caught 5 hardening items the general lens didn't; test-coverage caught 3 missing-scenario bugs the security lens didn't); round 2 ran 1 regression-scan agent that surfaced 6 stale references the round-1 sweep missed.

8. **Reconcile proposals against canonical docs.** For any `docs(openspec): propose` PR: for every claim the proposal makes about schema (new columns, new tables, new CHECK constraints), algorithms (fallback ladders, rate-limit formulas, trigger bodies), or domain rules — find the canonical source (the specific `docs/<file>` or `openspec/specs/<capability>` section the proposal cites or should cite) and verify exact alignment. If the proposal diverges from canonical docs without an explicit "amend docs" statement in `proposal.md`, flag it. Divergence found post-merge is a failure mode the project has hit before (see PRs [#18](https://github.com/aditrioka/nearyou-id/pull/18) / [#19](https://github.com/aditrioka/nearyou-id/pull/19) for the `global-timeline` reconciliation incident + skill/CLAUDE.md fix, and [#24](https://github.com/aditrioka/nearyou-id/pull/24) for the v10 notifications `body_data` catalog amendment); reviewers are the last line of defense. Bias toward flagging "does proposal match docs §X?" as a `question` when unsure — docs are canonical until proven otherwise.

## When NOT to use OpenSpec

Infra / tooling / CI / docs-only changes go through regular PRs. OpenSpec is for spec-driven product changes — capability + behavior + WHEN/THEN scenarios. Detekt rules, CI config, `build-logic/`, ops docs, READMEs: regular PR, regular commit prefix.

## Environments (summary)

- `dev` — local, Supabase CLI + Docker Compose (Ktor + Redis).
- `staging` — Cloud Run + Supabase Free + Upstash Free + RevenueCat sandbox. `*-staging.nearyou.id` subdomains. Synthetic data only.
- `production` — full-spec. `api|admin|img.nearyou.id`. Not live until Pre-Launch.

Secrets are env-namespaced in GCP Secret Manager (`staging-*` vs unprefixed prod). Mobile uses Android flavors / iOS xcconfig schemes.
