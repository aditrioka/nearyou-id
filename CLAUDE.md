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

## Delivery workflow

- **Branch naming**: OpenSpec features use the change name itself (kebab-case, no `-v<N>` suffix). Infra / tooling / CI / docs-only use `<area>/<slug>` (e.g., `ci/postgres-service`).
- **Sequence per OpenSpec change**: feat PR (squash-merge after CI green + staging deploy green) → separate archive PR.
- **Never skip hooks** (`--no-verify`, `--no-gpg-sign`, etc.).
- **No force-push to `main`**. `--force-with-lease` on topic branches is fine.
- **Direct push to `main` is hook-blocked** — every change ships via feature branch + PR + squash-merge.

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

7. **Reconcile proposals against canonical docs.** For any `docs(openspec): propose` PR: for every claim the proposal makes about schema (new columns, new tables, new CHECK constraints), algorithms (fallback ladders, rate-limit formulas, trigger bodies), or domain rules — find the canonical source (the specific `docs/<file>` or `openspec/specs/<capability>` section the proposal cites or should cite) and verify exact alignment. If the proposal diverges from canonical docs without an explicit "amend docs" statement in `proposal.md`, flag it. Divergence found post-merge is a failure mode the project has hit before (see `FOLLOW_UPS.md`); reviewers are the last line of defense. Bias toward flagging "does proposal match docs §X?" as a `question` when unsure — docs are canonical until proven otherwise.

## When NOT to use OpenSpec

Infra / tooling / CI / docs-only changes go through regular PRs. OpenSpec is for spec-driven product changes — capability + behavior + WHEN/THEN scenarios. Detekt rules, CI config, `build-logic/`, ops docs, READMEs: regular PR, regular commit prefix.

## Environments (summary)

- `dev` — local, Supabase CLI + Docker Compose (Ktor + Redis).
- `staging` — Cloud Run + Supabase Free + Upstash Free + RevenueCat sandbox. `*-staging.nearyou.id` subdomains. Synthetic data only.
- `production` — full-spec. `api|admin|img.nearyou.id`. Not live until Pre-Launch.

Secrets are env-namespaced in GCP Secret Manager (`staging-*` vs unprefixed prod). Mobile uses Android flavors / iOS xcconfig schemes.
