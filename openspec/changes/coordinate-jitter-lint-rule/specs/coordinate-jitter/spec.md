## ADDED Requirements

### Requirement: CoordinateJitterRule fences `actual_location` reads in Kotlin source

The repo SHALL ship a custom Detekt rule `CoordinateJitterRule` under `lint/detekt-rules/src/main/kotlin/id/nearyou/lint/detekt/` that fires on any Kotlin string-literal (`KtStringTemplateExpression`) whose source text contains `\bactual_location\b` (case-insensitive), unless the containing file is allowlisted or the enclosing declaration is annotated `@AllowActualLocationRead("<reason>")`.

The rule MUST be registered in `NearYouRuleSetProvider` so that `./gradlew detekt` picks it up without additional plugin configuration.

#### Scenario: Non-allowed file with `FROM posts ... actual_location` fires
- **WHEN** a Kotlin file outside the admin module / post write-path / test tree contains a string literal like `"SELECT id FROM posts WHERE actual_location IS NOT NULL"`
- **THEN** `CoordinateJitterRule` reports a code smell on that literal

#### Scenario: Rule registered via NearYouRuleSetProvider
- **WHEN** reading `NearYouRuleSetProvider.instance(config)`
- **THEN** the returned `RuleSet` includes an instance of `CoordinateJitterRule`

### Requirement: Allowlist covers the three legitimate readers

The rule SHALL NOT fire in any of these allowed contexts:

1. **Admin module**: files whose `virtualFilePath` contains `/app/admin/` OR whose package is `id.nearyou.app.admin` (or a sub-package thereof). The admin module has full coordinate access by design.
2. **Test fixtures**: files whose `virtualFilePath` contains `/src/test/`. Tests seed data via raw SQL INSERT statements that legitimately reference `actual_location` as a column name.
3. **Post write-path repositories**: files whose basename starts with any of `JdbcPostRepository`, `CreatePostService`, `PostOwnContent`. These are the sanctioned INSERT-into-posts paths. Also allows the V9 `Report*` point-lookup existence-check files per the same pattern `RawFromPostsRule` uses for the moderation module.

All three allowlist gates MUST support the detekt-test `lint(String)` synthetic-file harness via package-FQN fallback (mirror the approach in `BlockExclusionJoinRule.isAllowedPath`).

#### Scenario: Admin-module file passes
- **WHEN** a file with package `id.nearyou.app.admin.tools` contains `"SELECT actual_location FROM posts"`
- **THEN** the rule does NOT fire

#### Scenario: Test file passes
- **WHEN** a file under `.../src/test/kotlin/.../*.kt` contains `"INSERT INTO posts (..., actual_location, ...)"`
- **THEN** the rule does NOT fire

#### Scenario: JdbcPostRepository passes
- **WHEN** a file named `JdbcPostRepository.kt` (any package) contains `"INSERT INTO posts (id, author_id, content, display_location, actual_location, ...)"`
- **THEN** the rule does NOT fire

#### Scenario: Synthetic non-allowed file fires
- **WHEN** a file with package `id.nearyou.app.timeline` (not admin, not post write-path) contains `"SELECT actual_location FROM posts"`
- **THEN** the rule fires once

### Requirement: `@AllowActualLocationRead` annotation bypasses the rule

Any Kotlin declaration (class, function, property) annotated `@AllowActualLocationRead("<reason>")` — including a containing ancestor declaration — SHALL exempt string literals inside that declaration from the rule. The annotation short-name check mirrors `@AllowRawPostsRead` / `@AllowMissingBlockJoin`.

The annotation class itself need not be defined in production code for the rule to work — the rule matches by short-name. Callers define the annotation at the use site or import it from any shared location.

#### Scenario: Annotation on function suppresses
- **WHEN** a function annotated `@AllowActualLocationRead("admin debug tool")` contains `"SELECT actual_location FROM posts"`
- **THEN** the rule does NOT fire on that literal

#### Scenario: Annotation on enclosing class suppresses
- **WHEN** a class annotated `@AllowActualLocationRead("admin geo audit")` contains a method with `"WHERE actual_location IS NULL"`
- **THEN** the rule does NOT fire on that literal

### Requirement: Detekt test coverage

`lint/detekt-rules/src/test/kotlin/id/nearyou/lint/detekt/CoordinateJitterLintTest.kt` SHALL cover, at minimum:
1. Positive-fail: `SELECT actual_location FROM posts` in a non-allowed file fires.
2. Positive-pass: admin-module package exempt.
3. Positive-pass: file under `/src/test/` exempt.
4. Positive-pass: `JdbcPostRepository`-prefixed file exempt (via `writeKtFile` helper — same pattern as the `PostOwnContent` fixture in `BlockExclusionJoinLintTest`).
5. Positive-pass: `@AllowActualLocationRead("reason")` on the function suppresses.
6. Positive-pass: annotation on the enclosing class suppresses.
7. Positive-pass: unrelated string mentioning `actual_location` in a non-SQL context does NOT fire (e.g., a user-facing message "actual_location has been hidden" should still fire since the rule is a simple regex — so this scenario is actually a **fail** case documenting the false-positive surface; the annotation is the escape hatch).
8. Positive-pass: Kotlin migration smoke test (test file) that INSERTs into `posts (actual_location)` does NOT fire.
9. Positive-fail: multi-line string concatenation spanning `actual_location` across two `+`-joined literals fires exactly once (leftmost literal reports, de-duplicated).
10. Positive-fail: INSERT-only fixture outside the write-path allowlist fires (the rule doesn't distinguish read/write; path allowlist is the only sanctioned escape).

#### Scenario: Test class exists and passes
- **WHEN** running `./gradlew :lint:detekt-rules:test`
- **THEN** `CoordinateJitterLintTest` is discovered AND every scenario above corresponds to at least one test case AND all cases pass

### Requirement: Detekt run against the backend codebase remains green

As of this change, `./gradlew detekt` SHALL pass on the backend + infra + lint modules. Every existing `actual_location` usage MUST fall within the allowlist (admin / test / post-write-path / annotation) — no net-new compliance work required. If a call site is discovered that falls outside the allowlist, the change is considered BLOCKED and the rule's allowlist or an `@AllowActualLocationRead` bypass MUST be added in the same PR.

#### Scenario: Detekt green post-merge
- **WHEN** running `./gradlew detekt` after this change merges
- **THEN** the command exits 0 with no `CoordinateJitterRule` findings
