## Context

`actual_location` is a `GEOGRAPHY(POINT, 4326)` column on `posts` that stores the un-fuzzed coordinate. The product contract (`coordinate-jitter` capability + `docs/06-Security-Privacy.md`) is that non-admin code MUST read `display_location` (fuzzed HMAC-offset, 50–500 m band). The existing capability documents this requirement + a Phase 2 audit item to verify no leak. Today no mechanical gate enforces it at commit time.

Two adjacent rules already exist:
- `RawFromPostsRule` (V4-era) — fences `FROM posts` reads outside the admin module / own-content repositories.
- `BlockExclusionJoinRule` (V5-era) — fences protected-table queries without bidirectional `user_blocks` exclusion.

`CoordinateJitterRule` is the obvious third. It mirrors their shape (visit Kotlin `KtStringTemplateExpression`, regex-match protected token, path-based allowlist + annotation bypass) and uses the same test harness.

## Goals / Non-Goals

**Goals:**
- Fire on any Kotlin string literal matching `\bactual_location\b` unless the file is allowlisted or annotated.
- Cover the three legitimate readers: admin module (any path), post write-path repositories (INSERT statements), and test fixtures (seed data via literal INSERT / SELECT strings).
- Provide `@AllowActualLocationRead("<reason>")` annotation bypass for future admin-tool paths that the blanket allowlist misses.
- Register via `NearYouRuleSetProvider` so `./gradlew detekt` runs it automatically.

**Non-Goals:**
- Scanning raw `.sql` migration files. Detekt is a Kotlin linter; SQL migration review happens in PRs. Migration smoke tests that embed SQL as Kotlin string literals ARE covered via the test-path allowlist.
- Distinguishing reads from writes in SQL string literals. `INSERT INTO posts (..., actual_location, ...)` is a legitimate write. The path-based allowlist for post write-path files (`JdbcPostRepository`, `CreatePostService`) covers this without a read/write regex.
- Retrofitting `@AllowActualLocationRead` on existing callers. The current allowlist covers every live call site.

## Decisions

### Decision 1: Same shape as `BlockExclusionJoinRule` / `RawFromPostsRule`

Visit `KtStringTemplateExpression`, regex-match on the expression's source text. Path-based allowlist by substring check on the normalized `virtualFilePath`. Annotation bypass via walking `KtAnnotated` ancestors. Reason: mirroring the existing rules means one mental model for maintainers; the shape is proven (8 live rule tests, zero false-positives in V4–V11 shipping history).

**Alternative considered:** Visit `KtCallExpression` and check only arguments to DB-facing calls (`prepareStatement`, `createStatement`). Rejected — the existing rules use string-literal visiting and it works. No reason to diverge.

### Decision 2: Allowlist by path + filename prefix, not by package

Path substrings `/app/admin/` and `/src/test/` + filename prefixes `JdbcPostRepository`, `CreatePostService`, `PostOwnContent`, `Report*` (V9 report-submission paths that do point-lookup existence checks against `posts` including `actual_location`-bearing columns for target resolution) cover every current legitimate caller. Package-based fallback (`id.nearyou.app.admin`, `id.nearyou.app.post`) mirrors `BlockExclusionJoinRule` so synthetic-file tests still match.

**Alternative considered:** Allowlist by package only. Rejected — `JdbcPostRepository` lives in `id.nearyou.app.infra.repo`, which also hosts repos that should NOT be allowed (e.g., `JdbcPostsGlobalRepository` should not read `actual_location`; it uses `display_location`). Filename prefix is the right granularity.

### Decision 3: The annotation is `@AllowActualLocationRead`, not `@AllowActualLocation`

Rule-specific name keeps the search surface clean — `grep -r AllowActualLocationRead` returns only the rule + bypasses. Mirrors `@AllowMissingBlockJoin` / `@AllowRawPostsRead`. Requires a reason string per established convention.

### Decision 4: Regex is case-insensitive

`\bactual_location\b` with `RegexOption.IGNORE_CASE`. SQL identifiers are case-insensitive by convention; matches `Actual_Location`, `ACTUAL_LOCATION`, etc. Mirrors existing rules.

### Decision 5: No enforcement of `display_location` USE

This rule is purely negative — it forbids `actual_location`. It does NOT require `display_location` to appear. Reason: Kotlin code can legitimately contain neither (e.g., a SELECT of just `created_at`). Positive enforcement of `display_location` presence is what the Phase 2 audit + end-to-end test in `coordinate-jitter` spec do.

## Risks / Trade-offs

- **False positive**: a Kotlin string literal that mentions `actual_location` in a comment or display string (e.g., `"actual_location has been hidden"`) would trigger the rule. → **Mitigation**: regex `\bactual_location\b` only matches the literal column-name shape; comments in Kotlin docstrings are outside `KtStringTemplateExpression`. Annotation bypass covers any legitimate edge case.
- **False negative**: dynamic SQL built via string concatenation with `actual_location` in one fragment and the rest in another. → **Mitigation**: `combinedTextAndLeftmost` approach from `BlockExclusionJoinRule` handles multi-line concatenation; left unaddressed for template literals with computed parts, which is acceptable per `docs/08-Roadmap-Risk.md` "grep-level is OK for MVP."
- **Scope creep**: future admin tools might need `actual_location`. → **Mitigation**: `@AllowActualLocationRead("<reason>")` annotation exists for per-declaration bypass with documented reason.
