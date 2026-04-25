# Tasks — `coordinate-jitter-lint-rule`

## 1. Rule implementation

- [x] 1.1 Create `lint/detekt-rules/src/main/kotlin/id/nearyou/lint/detekt/CoordinateJitterRule.kt`. Model shape after `BlockExclusionJoinRule` (same `visitStringTemplateExpression` + `isAllowedPath` + `isInsideAllowedAnnotation` pattern). Regex: `\bactual_location\b`, case-insensitive.
- [x] 1.2 Register `CoordinateJitterRule(config)` in `NearYouRuleSetProvider.instance(config)` alongside `RawFromPostsRule` + `BlockExclusionJoinRule`.
- [x] 1.3 KDoc header: explain the protected invariant, the three-group allowlist (admin / test / post-write-path), the annotation bypass, and an explicit "why `admin_regions` / SQL migration scanning is NOT in scope" paragraph (to guard against future contributors adding it).

## 2. Rule tests

- [x] 2.1 Create `lint/detekt-rules/src/test/kotlin/id/nearyou/lint/detekt/CoordinateJitterLintTest.kt`. Model shape after `BlockExclusionJoinLintTest` (same `writeKtFile` helper + `StringSpec` structure + `rule.lint(code)` / `rule.lint(path)` assertions).
- [x] 2.2 Cover the 10 scenarios from the spec: positive-fail SELECT, admin-pkg pass, test-path pass (via `writeKtFile` with `/src/test/` in path), `JdbcPostRepository`-prefix pass (via `writeKtFile`), annotation-on-function pass, annotation-on-class pass, multi-line concat fires once, INSERT outside allowlist fires, plus the "false positive on comment-y strings" documentation fixture (fires, annotation is escape hatch).
- [x] 2.3 `./gradlew :lint:detekt-rules:test` green.

## 3. Verify no regressions on the backend

- [x] 3.1 `./gradlew detekt` green across backend + infra + lint modules. No existing `actual_location` call site falls outside the allowlist. If any does, extend the allowlist or add `@AllowActualLocationRead("<reason>")` in the same PR with a comment explaining why.
- [x] 3.2 Spot-check the 15 files currently matching `grep -r actual_location --include='*.kt'`: all under `/src/test/` OR `JdbcPostRepository.kt` OR `CreatePostService.kt` OR admin module. None should need an annotation bypass.

## 4. OpenSpec + DEFERRED cross-reference

- [x] 4.1 `openspec validate coordinate-jitter-lint-rule --strict` green.
- [x] 4.2 Mark tasks 5.3 + 5.4 done in `openspec/changes/global-timeline-with-region-polygons/tasks.md` with a note pointing at this change.
- [x] 4.3 Mark `DEFERRED.md` Cluster C as ✅ RESOLVED in the global-timeline change, pointing at this change's merge commit.

## 5. Archive (separate PR per workflow)

- [x] 5.1 `openspec archive coordinate-jitter-lint-rule` — lands the spec delta into `openspec/specs/coordinate-jitter/spec.md` as a permanent requirement and records the archive under `openspec/changes/archive/`.
