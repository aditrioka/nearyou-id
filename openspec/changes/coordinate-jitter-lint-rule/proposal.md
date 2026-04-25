## Why

The `coordinate-jitter` capability specifies that non-admin read paths MUST use `display_location` (fuzzed) and NEVER `actual_location`. Today this is a convention enforced by code review + a Phase 2 audit item ("actual_location never leaks via any read response") — no mechanical gate.

Two adjacent capabilities already have Detekt rules that fence their invariants:
- `RawFromPostsRule` — forbids raw `FROM posts` in business code.
- `BlockExclusionJoinRule` — forbids queries against protected tables without bidirectional `user_blocks` exclusion.

`CoordinateJitterRule` is the obvious third rule in that triad, locking the `actual_location` invariant at commit time instead of in a future audit pass. Direct prompt: the `global-timeline-with-region-polygons` change left tasks 5.3 + 5.4 deferred ("Rule does not exist — separate change") and pointed at this one. The `posts_set_city_tg` trigger (V11) is the second sanctioned DB-side reader of `actual_location` after the admin module and the seed-time import; shipping the rule lets us formally allowlist V11 rather than carrying that allowlist as prose.

## What Changes

- Add `CoordinateJitterRule` in `lint/detekt-rules/src/main/kotlin/id/nearyou/lint/detekt/` — a Detekt Rule that fires on any Kotlin string literal containing `actual_location` unless the file is in an allowed path or annotated with `@AllowActualLocationRead("<reason>")`.
- Register in `NearYouRuleSetProvider`.
- Ship the test fixture class `CoordinateJitterLintTest` covering positive-fail, positive-pass, allowlist-path, and annotation-bypass cases — including explicit V11-shape + V12-shape fixtures documenting why migration SQL references to `actual_location` are sanctioned.
- Update the `coordinate-jitter` capability spec with an ADDED requirement: "`actual_location` reads are lint-gated by `CoordinateJitterRule`" + associated scenarios.
- Mark tasks 5.3 + 5.4 in `global-timeline-with-region-polygons` as done via the follow-up note in DEFERRED.md Cluster C.

## Capabilities

### Modified Capabilities

- `coordinate-jitter` — gains a new requirement pinning `CoordinateJitterRule`: protected pattern (`\bactual_location\b`), allowlist (admin module, post write-path files, test fixtures, sanctioned migration trigger reads), annotation bypass (`@AllowActualLocationRead("<reason>")`), Detekt test coverage.

## Impact

- **Code**: ~150 LOC for the rule + KDoc; ~300 LOC for `CoordinateJitterLintTest` covering 8–10 fixture cases; 1-line update to `NearYouRuleSetProvider` to register the rule.
- **Schema / APIs / Dependencies**: none.
- **Out of scope (explicit)**:
  - Scanning `.sql` migration files directly. Detekt visits Kotlin PSI; migration files are reviewed in PRs. The rule fires on Kotlin string-literal copies of SQL (migration smoke tests, repos) which covers the leak surface that actually matters.
  - A follow-up to extend the rule to check `admin_regions` table allowlist — that already lives in `BlockExclusionJoinRule` KDoc (shipped with `global-timeline-with-region-polygons`).
  - Retrofitting `@AllowActualLocationRead` annotations onto every existing read path in repos / services. The allowlist-by-path covers the current call sites; the annotation exists as a future escape hatch for new admin tools.
