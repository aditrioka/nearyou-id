## Context

The `content-moderation-keyword-lists` capability owns the canonical content-write call-order contract: every handler that INSERTs into `posts.content` / `post_replies.content` / `chat_messages.content` MUST first invoke `TextModerator.moderate(content)` to produce a `Verdict` (Layer 1+2 synchronous moderation). The contract is defined at [`content-moderation-keyword-lists/spec.md:372`](../../../openspec/specs/content-moderation-keyword-lists/spec.md) ("`TextModerator` integration is invoked AFTER existing length and rate-limit gates, BEFORE INSERT") and enforced today by 3 static-source-scan integration tests (one per write-path surface).

Test-time enforcement works but has known limitations:
- The signal arrives at `:backend:ktor:test` run, not at the developer's IDE squiggle. Round-trip is slower.
- A new content-write surface (e.g., a future post-edit endpoint, an admin chat-redaction handler) added without realising the call-order requirement would NOT be caught by the existing 3 tests — they each scan a specific source path; a new path silently slips through.
- The contract is only as strong as the test discoverability; the 3 tests rely on naming/path conventions that future contributors must mirror.

A Detekt rule shifts enforcement to compile-time at every content-write call site, regardless of which path it lives in. Mirror precedent: the existing 7 lint rules in `:lint:detekt-rules` (`OtelForbiddenAttributeRule`, `RawFromPostsRule`, `BlockExclusionJoinRule`, `RedisHashTagRule`, `RawXForwardedForRule`, `RateLimitTtlRule`, `CoordinateJitterRule`) all enforce comparable invariants — wrong-method use, raw-secret leakage, rate-limit-config drift, etc. — and the just-shipped `OtelForbiddenAttributeRule` (PR [#99](https://github.com/aditrioka/nearyou-id/pull/99)) plus the in-flight `IpAxisMustUseTryAcquireByKeyRule` (PR [#100](https://github.com/aditrioka/nearyou-id/pull/100)) extend that pattern with the same pragma: trade modest implementation cost for permanent compile-time defence against silently-introduced regressions.

## Goals / Non-Goals

**Goals:**

- Mechanically prevent the regression where a new write-path handler INSERTs into content tables without first invoking `TextModerator.moderate(content)`.
- Mirror the structural shape of the 7 existing rules + the 2 in-flight (PR #99 just shipped, PR #100 in flight) — same `:lint:detekt-rules` location, same `NearYouRuleSetProvider` registration, same Kotest `*LintTest.kt` shape, same dual-allowlist pattern (path-based `**/src/test/**` + package-FQN fallback `id.nearyou.test.fixtures.*`).
- Provide an annotation-bypass mechanism (`@AllowContentWriteWithoutModeration("<reason>")`) for the legitimate carve-outs (admin tombstone / admin redaction / Flyway seed). Mirror the precedent set by `RateLimitTtlRule`'s `@AllowDailyTtlOverride` and `RedisHashTagRule`'s `@AllowRawRedisKey`.
- Coexist with the existing 3 static-source-scan integration tests — defense-in-depth, not replacement.

**Non-Goals:**

- Replacing the existing 3 integration tests. They cover end-to-end behaviour (HTTP handler → moderator → INSERT) which a Detekt rule cannot. The rule is the static-call-site complement.
- Layer 3 dispatch call-site enforcement. Layer 3 is owned by `text-moderation-perspective-api-layer` capability; a Detekt rule for that surface would be a separate change.
- Mobile-side enforcement. Mobile doesn't write content directly (calls backend API).
- Migration / `.sql` file enforcement. Detekt visits Kotlin PSI; SQL files are reviewed in PR + the `seed` annotation reason covers Flyway content writes.
- Cross-rule composition tests with `BlockExclusionJoinRule` or `RawFromPostsRule`. Those rules fire on READ sites; this rule fires on WRITE sites — orthogonal.
- Type-resolution-based enforcement. Detekt's PSI mode is sufficient for this rule's pattern (callee short-name + container function PSI-walk); type-resolution would add build-graph complexity that the project's other 7 rules deliberately avoid.

## Decisions

### Decision 1: Detection mechanism — PSI walk for `TextModerator.moderate` ancestry

**Choice:** The rule visits each candidate write-call expression (defined by Decision 2) and walks UP the PSI tree to the enclosing function (`KtNamedFunction` or `KtFunctionLiteral`). It then walks DOWN the function body, searching for any `KtCallExpression` whose callee short-name matches `moderate` AND whose containing-class context (per Decision 3 disambiguation) is `TextModerator`. If such a call is found AND its source position PSI-precedes the write call's position, the rule passes; otherwise it fires.

**Alternatives considered:**

- (a) Static call-graph analysis (Detekt type-resolution mode) — tracks `TextModerator.moderate` via type information, regardless of variable aliasing or indirection. More precise but requires the analysed module's classpath; adds build-graph complexity that the existing 7 rules deliberately avoid.
- (b) Annotation-driven — every content-write site carries `@ModeratedBy(TextModerator::class)` annotation; the rule fires on missing annotation. Simpler but requires every existing call site to be annotated up-front (~10 sites + future); inverts the default-allow stance.
- (c) Current choice — PSI walk for moderate-call ancestry within enclosing function.

**Rationale for (c):**

- Mirrors the existing PSI-walk approach in `BlockExclusionJoinRule.kt:176-186` (which checks for the block-exclusion JOIN clause within the same query-construction context) and `RateLimitTtlRule.kt:92-100` (which checks for the `computeTTLToNextReset` callsite within the same daily-cap registration context).
- Detekt's PSI mode is the project default; no build-graph extension required.
- Trade-off accepted: the PSI walk doesn't follow function calls (e.g., a write helper function that delegates to `INSERT INTO posts` would be invisible to the rule). Mitigation: the existing 3 integration tests catch the end-to-end behaviour; the PSI walk only protects against the most common shape (inlined INSERT in handler function body). A future contributor extracting a write helper would need to either (i) keep the moderate call in the calling function (rule passes) OR (ii) annotate the write helper with the carve-out (rule passes via annotation) OR (iii) move the moderate call into the helper (rule passes again because both calls are in the same function).

### Decision 2: Candidate write-call surfaces — SQL-string-literal regex + canonical service methods

**Choice:** The rule fires on `KtCallExpression`s matching either:

- **(a) SQL-string-literal pattern**: any `KtStringTemplateExpression` whose flat content matches the regex `(?i)\bINSERT\s+INTO\s+(posts|post_replies|chat_messages)\b[^)]*\bcontent\b`. The string-template parent is presumed to be a JDBC call (e.g., `connection.prepareStatement(...)` or `JdbcUtils.exec(...)`); the rule fires on the literal regardless of the immediate enclosing call shape.
- **(b) Service-method pattern**: callee short-names matching `create` (containing class `PostRepository`), `post` (containing class `ReplyService`), `sendMessage` (containing class `ChatRepository`). The class disambiguation uses the callee's PSI receiver-expression's apparent type (Decision 3).

**Rationale:**

- Both surfaces exist in the codebase. (a) covers raw JDBC call sites (the Repository layer); (b) covers service-method call sites (the route handler layer). A future content-write surface would either fit (a) or (b); the rule's two-arm design covers both shapes.
- The regex `(?i)\bINSERT\s+INTO\s+(posts|post_replies|chat_messages)\b[^)]*\bcontent\b` is intentionally narrow — matches ONLY the 3 canonical content tables AND requires the `content` column reference. Avoids false positives on INSERTs into unrelated columns (e.g., `INSERT INTO posts (..., view_count, ...)` which wouldn't pass the `\bcontent\b` requirement).
- The 3 service-method names are project-conventional; matching by short-name + class disambiguation (Decision 3) eliminates the false-positive surface for collisions (e.g., `OtherClass.post(...)` doesn't fire because the receiver type doesn't match).

### Decision 3: Class disambiguation — receiver-expression type heuristic, NOT type-resolution mode

**Choice:** For service-method matches (Decision 2 arm (b)), the rule disambiguates via PSI heuristic — the callee `KtCallExpression.parent` is a `KtDotQualifiedExpression`; the receiver expression's `text` is matched against the canonical short-name set (`PostRepository`, `ReplyService`, `ChatRepository`, plus aliasing variations like `postRepository.create`, `replyService.post`). Same approach as `BlockExclusionJoinRule.kt`'s receiver-name heuristic.

**Trade-off accepted:** if a future contributor creates a non-`PostRepository` class with a `create(content, ...)` method, the rule may produce a false positive. Mitigation: the test-source allowlist + the annotation-bypass mechanism cover legitimate exceptions; the false-positive surface is small (the project doesn't have a "rename PostRepository to OtherName" precedent).

### Decision 4: Annotation-bypass — `@AllowContentWriteWithoutModeration("<reason>")` with reason-vocabulary enforcement

**Choice:** Introduce annotation `@AllowContentWriteWithoutModeration("<reason>")` (placed alongside the existing `@AllowDailyTtlOverride` / `@AllowRawRedisKey` / `@AllowForbiddenSpanAttribute` / `@AllowUsernameWrite` annotations — verify location during apply; likely `:lint:detekt-rules`'s annotation source set or `:core:domain`'s `Annotations.kt`). The annotation accepts a single string argument enumerated as one of: `"tombstone"` (admin-driven content tombstone replacement), `"admin_redaction"` (Phase 3.5 admin chat-message redaction with system-controlled replacement string), `"seed"` (Flyway seed migrations / test fixtures populating content directly).

The rule MUST enforce `reason.isNotBlank()` (mirror precedent: `RateLimitTtlRule`'s `@AllowDailyTtlOverride` enforcement at `RateLimitTtlRule.kt:160-180`). An empty-string reason fails the rule with a message instructing the contributor to provide a documented reason.

**Rationale:**

- Mirror the established annotation-bypass pattern from 3+ existing rules. Consistency reduces cognitive load for contributors who hit the lint failure.
- The 3-value reason enumeration covers all known legitimate carve-outs identified during `content-moderation-keyword-lists` design (per the FOLLOW_UPS entry's "Annotation reasons enumeration" question). A future legitimate exception (rare) would extend the enum + amend the rule.
- The non-blank-reason enforcement prevents `@Suppress`-style bypass without justification; future readers see WHY the carve-out exists.

### Decision 5: Test-source allowlist — dual mechanism (path + package-FQN)

**Choice:** Mirror the canonical 3-rule precedent (`RedisHashTagRule.kt:115-120`, `RateLimitTtlRule.kt:92-100`, `BlockExclusionJoinRule.kt:176-186`):

- Path-based: short-circuit if `containingKtFile.virtualFilePath?.replace('\\', '/')?.contains("/src/test/") == true`.
- Package-FQN fallback: short-circuit if `containingKtFile.packageFqName.asString() == "id.nearyou.test.fixtures"` OR `startsWith("id.nearyou.test.fixtures.")`.

The package-FQN fallback is required because Detekt's test harness `lint(String)` overload synthesises files with `virtualFilePath = null`. Without the fallback every positive-case fixture in `ContentWriteRequiresModerationLintTest` would silently pass.

(NOTE: `OtelForbiddenAttributeRule` uses `id.nearyou.lint.detekt.*` as its FQN fallback because its KDoc embeds raw IP literals that would otherwise self-trigger; this rule has no analogous self-fixture concern — the KDoc references the regex literal, not Kotlin SQL syntax — so adopt the canonical 3-rule majority `id.nearyou.test.fixtures.*` pattern.)

### Decision 6: Co-located `NearYouRuleSetProvider` registration test (mirror just-shipped pattern)

**Choice:** The provider-registration assertion is a Kotest block named `"rule registered in NearYouRuleSetProvider"` co-located inside `ContentWriteRequiresModerationLintTest.kt`. Mirror the verified just-shipped pattern at [`OtelForbiddenAttributeLintTest.kt:807-812`](../../../lint/detekt-rules/src/test/kotlin/id/nearyou/lint/detekt/OtelForbiddenAttributeLintTest.kt) (and earlier `RedisHashTagRuleTest.kt:244` + `RateLimitTtlRuleTest.kt:242`). There is **no separate `NearYouRuleSetProviderTest.kt` file** in the project; each rule's test file owns its own registration assertion.

### Decision 7: Coordination with PR [#100](https://github.com/aditrioka/nearyou-id/pull/100)

**Choice:** This change is the would-be 8th rule, but PR #100 (`IpAxisMustUseTryAcquireByKeyRule`) is also queued as the 8th rule. Whichever change merges to `main` second will need to rebase its `NearYouRuleSetProvider` edit to position the new entry as the 9th. Trivial conflict resolution (1-line addition vs 1-line addition; both can coexist as 8th + 9th). No spec-level conflict (PR #100 modifies `rate-limit-infrastructure`; this change modifies `content-moderation-keyword-lists` — disjoint capabilities).

**Rationale:** Cheapest possible coordination — accept the 1-line rebase rather than coordinate ordering at proposal time. Per `openspec/project.md` § "Archive commits touching shared specs", the spec-level conflict is the expensive one; this change has none. Per the parallel-PR analysis at the start of this skill invocation, the rebase is mechanical (Git can usually auto-resolve 1-line additions to a list).

## Risks / Trade-offs

| Risk | Mitigation |
|---|---|
| False positive on a future write-helper-function pattern (Decision 1 PSI walk doesn't follow function calls) | Annotation-bypass mechanism (Decision 4) covers legitimate exceptions; the 3 existing integration tests catch end-to-end regressions; future contributors can keep the moderate call in the same function as the write |
| False positive on a non-`PostRepository` class with a `create(content, ...)` method (Decision 3 receiver-type heuristic) | Annotation-bypass; small false-positive surface (project doesn't have a precedent for renaming the canonical Repository classes) |
| Maintainer disables the rule via `@Suppress("ContentWriteRequiresModeration")` without justification | Standard Detekt escape across all rules; code-review remains the canonical defence; reason-enforcement annotation is the documented alternative path |
| The 3 enumerated annotation reasons (`tombstone` / `admin_redaction` / `seed`) miss a future legitimate carve-out | Future change extends the enum + amends the rule; explicit follow-up surface |
| Rule ordering conflict in `NearYouRuleSetProvider` rebase vs PR #100 (Decision 7) | 1-line rebase, mechanically auto-resolvable in most cases; documented in proposal Out of scope |
| Adding the 8th (or 9th) Detekt rule increases per-PR Detekt runtime | Each rule is O(1) per PSI visit; project Detekt runtime is dominated by file-walk + config parsing, not per-rule logic. Empirical: `OtelForbiddenAttributeRule` added <100ms (per PR [#99](https://github.com/aditrioka/nearyou-id/pull/99) CI logs); this rule is structurally simpler — expected addition: <30ms |
| Spec scope creep: the rule's enforcement boundary becomes a debate (e.g., "does THIS shape count as content-write?") | The 2-arm Decision 2 (SQL-literal regex + service-method names) defines the surface explicitly; future expansions go through the spec amendment process |

## Open Questions

- **Annotation location**: should the new `@AllowContentWriteWithoutModeration` live in `:core:domain`'s `Annotations.kt` (sibling to `@AllowDailyTtlOverride`) OR in `:lint:detekt-rules` (sibling to test annotations)? Verify during apply by reading the existing annotation file's package + dependency surface. If annotations are currently in `:core:domain`, follow that precedent; if `:lint:detekt-rules`, follow that. Either is structurally fine.
- **Service-method variant set**: Decision 2 arm (b) lists 3 canonical service methods (`PostRepository.create`, `ReplyService.post`, `ChatRepository.sendMessage`). Verify these are the actual canonical write-method names by reading the existing source code at apply time. If the actual names differ slightly (e.g., `PostRepository.insert` vs `PostRepository.create`), update the rule's match set accordingly.
