## ADDED Requirements

### Requirement: `ContentWriteRequiresModerationRule` fences content-write call sites without preceding `TextModerator.moderate`

The `:lint:detekt-rules` module SHALL ship a Detekt `Rule` named `ContentWriteRequiresModerationRule` that fires on candidate content-write `KtCallExpression`s when the enclosing function does NOT also invoke `TextModerator.moderate(...)` BEFORE the write call. This is the compile-time complement to the existing test-time enforcement at [`PostCreationModerationIntegrationTest.kt:477`](../../../../../backend/ktor/src/test/kotlin/id/nearyou/app/post/PostCreationModerationIntegrationTest.kt), [`PostRepliesModerationIntegrationTest.kt:406`](../../../../../backend/ktor/src/test/kotlin/id/nearyou/app/engagement/PostRepliesModerationIntegrationTest.kt), and [`ChatModerationIntegrationTest.kt:442`](../../../../../backend/ktor/src/test/kotlin/id/nearyou/app/chat/ChatModerationIntegrationTest.kt).

**Candidate content-write surfaces** (the rule's match set):

1. **SQL-string-literal pattern**: any `KtStringTemplateExpression` whose flat content matches the regex `(?i)\bINSERT\s+INTO\s+(posts|post_replies|chat_messages)\b[^)]*\bcontent\b`. Covers raw JDBC INSERT call sites at the Repository layer.
2. **Service-method pattern**: `KtCallExpression`s whose callee short-name is `create` (with receiver-expression text matching `PostRepository` / `postRepository`), `post` (receiver `ReplyService` / `replyService`), or `sendMessage` (receiver `ChatRepository` / `chatRepository`). Covers service-method call sites at the route handler layer. The actual canonical service-method names SHALL be verified against the source at implementation time; if a name differs (e.g., `insert` instead of `create`), the rule's match set is updated accordingly.

**Detection mechanism**: PSI walk for `TextModerator.moderate` ancestry within the enclosing function. The rule visits each candidate write-call expression, walks UP the PSI tree to the enclosing `KtNamedFunction` or `KtFunctionLiteral`, then walks DOWN the function body searching for any `KtCallExpression` whose callee short-name is `moderate` AND whose receiver context matches `TextModerator`. If such a call is found AND its source position PSI-precedes the write call, the rule passes; otherwise it fires on the write call's PSI element.

**Annotation-bypass**: a function annotated with `@AllowContentWriteWithoutModeration("<reason>")` (where `<reason>` is non-blank and one of `tombstone` / `admin_redaction` / `seed`) SHALL pass the rule. Empty-string reasons MUST fail the rule (mirror `RateLimitTtlRule`'s `@AllowDailyTtlOverride` enforcement). Reasons not in the enumerated set MUST fail the rule with an error message naming the allowed reasons. The annotation lives alongside the existing `@AllowDailyTtlOverride` / `@AllowRawRedisKey` / `@AllowForbiddenSpanAttribute` annotations (location verified at implementation time per the project annotation pattern).

**Path + package-FQN allowlist**: the rule SHALL short-circuit (return early without firing) on any `KtFile` whose:
- `virtualFilePath` contains the substring `/src/test/` (path-based test-source allowlist), OR
- `packageFqName` equals `id.nearyou.test.fixtures` OR begins with `id.nearyou.test.fixtures.` (package-FQN fallback covering Detekt-test-harness synthetic files whose `virtualFilePath` is null).

This dual-allowlist pattern mirrors the canonical 3-rule precedent: [`RedisHashTagRule.kt:115-120`](../../../../../lint/detekt-rules/src/main/kotlin/id/nearyou/lint/detekt/RedisHashTagRule.kt), [`RateLimitTtlRule.kt:92-100`](../../../../../lint/detekt-rules/src/main/kotlin/id/nearyou/lint/detekt/RateLimitTtlRule.kt), [`BlockExclusionJoinRule.kt:176-186`](../../../../../lint/detekt-rules/src/main/kotlin/id/nearyou/lint/detekt/BlockExclusionJoinRule.kt). All three use `id.nearyou.test.fixtures.*` as the FQN fallback.

The rule SHALL be registered in `lint/detekt-rules/src/main/kotlin/id/nearyou/lint/detekt/NearYouRuleSetProvider.kt`. No edit to `backend/ktor/config/detekt/detekt.yml` is required — the rule follows the canonical default-active pattern (the project Detekt config currently enumerates only 2 of the 7 existing rules; the others are active by default after `NearYouRuleSetProvider` registration).

This requirement complements — and does NOT replace — the existing test-time enforcement scenarios on the parent capability. Together the two layers form defense-in-depth on the call-order contract: compile-time call-site mechanical (this rule) + test-time end-to-end behaviour (the 3 existing integration tests).

#### Scenario: Inlined INSERT into posts.content without preceding moderate call fires
- **WHEN** a non-allowlisted Kotlin file contains a function whose body invokes `connection.prepareStatement("INSERT INTO posts (id, author_id, content, ...) VALUES (?, ?, ?, ...)")` (or any equivalent INSERT-into-posts SQL string literal containing the `content` column reference) WITHOUT a preceding `TextModerator.moderate(...)` invocation in the same function
- **THEN** the rule fires on the `KtStringTemplateExpression` containing the INSERT SQL literal AND the error message recommends invoking `TextModerator.moderate(content)` BEFORE the INSERT

#### Scenario: Inlined INSERT into post_replies.content without preceding moderate call fires
- **WHEN** a non-allowlisted Kotlin file contains a function whose body invokes an INSERT-into-`post_replies` SQL containing the `content` column reference WITHOUT a preceding `TextModerator.moderate(...)` call
- **THEN** the rule fires

#### Scenario: Inlined INSERT into chat_messages.content without preceding moderate call fires
- **WHEN** a non-allowlisted Kotlin file contains a function whose body invokes an INSERT-into-`chat_messages` SQL containing the `content` column reference WITHOUT a preceding `TextModerator.moderate(...)` call
- **THEN** the rule fires

#### Scenario: Service-method PostRepository.create without preceding moderate call fires
- **WHEN** a non-allowlisted Kotlin file contains a function whose body invokes `postRepository.create(content, ...)` (or `PostRepository(...).create(content, ...)`) WITHOUT a preceding `TextModerator.moderate(...)` call
- **THEN** the rule fires on the `KtCallExpression`

#### Scenario: Service-method ReplyService.post without preceding moderate call fires
- **WHEN** a non-allowlisted Kotlin file contains a function whose body invokes `replyService.post(content, ...)` WITHOUT a preceding `TextModerator.moderate(...)` call
- **THEN** the rule fires

#### Scenario: Service-method ChatRepository.sendMessage without preceding moderate call fires
- **WHEN** a non-allowlisted Kotlin file contains a function whose body invokes `chatRepository.sendMessage(content, ...)` WITHOUT a preceding `TextModerator.moderate(...)` call
- **THEN** the rule fires

#### Scenario: Inlined INSERT WITH preceding TextModerator.moderate call passes
- **WHEN** a non-allowlisted Kotlin file contains a function whose body invokes `val verdict = textModerator.moderate(content)` THEN proceeds to `connection.prepareStatement("INSERT INTO posts (..., content, ...) VALUES (...)")` (the canonical call-order shape)
- **THEN** the rule does NOT fire

#### Scenario: Function annotated with `@AllowContentWriteWithoutModeration("tombstone")` passes
- **WHEN** a non-allowlisted Kotlin file contains a function annotated with `@AllowContentWriteWithoutModeration("tombstone")` whose body INSERTs into a content table WITHOUT a `TextModerator.moderate(...)` call (e.g., admin tombstone replacement that overwrites user content with a fixed `[konten dihapus]` string — no need to moderate the system-controlled string)
- **THEN** the rule does NOT fire (the annotation's reason is non-blank AND in the enumerated set)

#### Scenario: Function annotated with `@AllowContentWriteWithoutModeration("admin_redaction")` passes
- **WHEN** a non-allowlisted Kotlin file (specifically the Phase 3.5 admin chat-redaction handler) contains a function annotated with `@AllowContentWriteWithoutModeration("admin_redaction")` whose body UPDATEs `chat_messages.content` to a fixed system-controlled redaction string WITHOUT a `TextModerator.moderate(...)` call
- **THEN** the rule does NOT fire

#### Scenario: Function annotated with `@AllowContentWriteWithoutModeration("seed")` passes
- **WHEN** a non-allowlisted Kotlin source file contains a function annotated with `@AllowContentWriteWithoutModeration("seed")` whose body INSERTs seed-data content rows WITHOUT a `TextModerator.moderate(...)` call (covers test-fixture seed harnesses; Flyway `.sql` migrations themselves are out of scope per `proposal.md` § Out of scope, but Kotlin-driven seed harnesses are in scope)
- **THEN** the rule does NOT fire

#### Scenario: Function annotated with `@AllowContentWriteWithoutModeration("")` (empty reason) fires
- **WHEN** a non-allowlisted Kotlin file contains a function annotated with `@AllowContentWriteWithoutModeration("")` (empty-string reason) whose body INSERTs into a content table WITHOUT a `TextModerator.moderate(...)` call
- **THEN** the rule fires (the annotation requires a non-blank reason; this is the canonical anti-`@Suppress`-style-bypass enforcement, mirror `RateLimitTtlRule`'s `@AllowDailyTtlOverride` empty-reason enforcement)

#### Scenario: Function annotated with `@AllowContentWriteWithoutModeration("custom_reason_not_in_enum")` fires
- **WHEN** a non-allowlisted Kotlin file contains a function annotated with `@AllowContentWriteWithoutModeration("custom_reason_not_in_enum")` whose body INSERTs into a content table
- **THEN** the rule fires AND the error message names the allowed reasons (`tombstone` / `admin_redaction` / `seed`)

#### Scenario: Test-source path allowlist applies
- **WHEN** a Kotlin file under any `/src/test/` path (e.g., `backend/ktor/src/test/kotlin/.../SomeFixtureTest.kt`, the new `lint/detekt-rules/src/test/kotlin/.../ContentWriteRequiresModerationLintTest.kt`) contains a function whose body INSERTs into a content table WITHOUT a `TextModerator.moderate(...)` call
- **THEN** the rule does NOT fire (the `/src/test/` path allowlist suppresses)

#### Scenario: Package-FQN fallback allowlist applies (synthetic test fixtures)
- **WHEN** a Kotlin file with `packageFqName` of `id.nearyou.test.fixtures.contentwrite` (synthetic test fixture from Detekt's `lint(String)` overload, no `virtualFilePath` populated) contains a function whose body INSERTs into a content table WITHOUT a `TextModerator.moderate(...)` call
- **THEN** the rule does NOT fire (the package-FQN fallback covers synthetic test fixtures whose path-based check fails)

#### Scenario: Non-content-table INSERT does not fire
- **WHEN** a non-allowlisted Kotlin file contains a function whose body invokes `connection.prepareStatement("INSERT INTO follows (follower_id, followee_id) VALUES (?, ?)")` (a non-content table) WITHOUT a `TextModerator.moderate(...)` call
- **THEN** the rule does NOT fire (the regex matches only `posts` / `post_replies` / `chat_messages` AND requires the `content` column reference)

#### Scenario: INSERT into a content table but NOT the content column does not fire
- **WHEN** a non-allowlisted Kotlin file contains a function whose body invokes `connection.prepareStatement("INSERT INTO posts (id, view_count) VALUES (?, ?)")` (writes to `posts` but not the `content` column — e.g., a future analytics-only INSERT path) WITHOUT a `TextModerator.moderate(...)` call
- **THEN** the rule does NOT fire (the regex requires the `\bcontent\b` column reference within the INSERT)

#### Scenario: Rule registered via NearYouRuleSetProvider
- **WHEN** the Kotest block `"rule registered in NearYouRuleSetProvider"` co-located inside `ContentWriteRequiresModerationLintTest.kt` invokes `NearYouRuleSetProvider().instance(Config.empty)` AND maps `ruleSet.rules.map { it::class.simpleName }`
- **THEN** the resulting list contains the string `"ContentWriteRequiresModerationRule"` (mirrors the precedent at [`OtelForbiddenAttributeLintTest.kt:807-812`](../../../../../lint/detekt-rules/src/test/kotlin/id/nearyou/lint/detekt/OtelForbiddenAttributeLintTest.kt) — the registration block lives inside each rule's own `*LintTest.kt`, NOT in a separate `NearYouRuleSetProviderTest.kt` file)

### Requirement: Detekt test coverage for `ContentWriteRequiresModerationRule`

The `:lint:detekt-rules` module SHALL ship a Kotest test class `ContentWriteRequiresModerationLintTest` covering the following fixture scenarios. Each scenario is a Detekt-rule unit test exercising the rule against a single inline-Kotlin fixture string via the project's existing Detekt-test harness (mirror the `OtelForbiddenAttributeLintTest` shipped under `observability-otel-foundation`).

1. **Positive — inlined posts INSERT no-moderate**: fixture function with `INSERT INTO posts (..., content, ...)` SQL literal AND no `TextModerator.moderate(...)` call → exactly one finding.
2. **Positive — inlined post_replies INSERT no-moderate**: same shape for `post_replies` → exactly one finding.
3. **Positive — inlined chat_messages INSERT no-moderate**: same shape for `chat_messages` → exactly one finding.
4. **Positive — service-method PostRepository.create no-moderate**: fixture `postRepository.create(content, ...)` no preceding moderate → exactly one finding.
5. **Positive — service-method ReplyService.post no-moderate**: fixture `replyService.post(content, ...)` no preceding moderate → exactly one finding.
6. **Positive — service-method ChatRepository.sendMessage no-moderate**: fixture `chatRepository.sendMessage(content, ...)` no preceding moderate → exactly one finding.
7. **Negative — INSERT WITH preceding moderate**: fixture function with `val verdict = textModerator.moderate(content)` THEN `INSERT INTO posts ...` → zero findings.
8. **Negative — annotation `@AllowContentWriteWithoutModeration("tombstone")`**: positive fixture (1) but with the annotation → zero findings.
9. **Negative — annotation `@AllowContentWriteWithoutModeration("admin_redaction")`**: positive fixture (3) but with the annotation → zero findings.
10. **Negative — annotation `@AllowContentWriteWithoutModeration("seed")`**: positive fixture (1) but with the annotation → zero findings.
11. **Positive — annotation `@AllowContentWriteWithoutModeration("")` (empty reason)**: positive fixture (1) but with empty-reason annotation → exactly one finding (empty reason fails the require check).
12. **Positive — annotation `@AllowContentWriteWithoutModeration("custom_reason")` (non-enumerated reason)**: positive fixture (1) but with non-enumerated-reason annotation → exactly one finding AND the error message contains the allowed-reasons enumeration.
13. **Allowlist — test-source path**: positive fixture (1) under simulated `/src/test/` virtual path → zero findings.
14. **Allowlist — package-FQN fallback**: positive fixture (1) under simulated `id.nearyou.test.fixtures.contentwrite` package (no virtualFilePath) → zero findings.
15. **Negative — non-content-table INSERT**: fixture with `INSERT INTO follows (...)` (non-content table) no preceding moderate → zero findings.
16. **Negative — content-table INSERT without content column**: fixture with `INSERT INTO posts (id, view_count) VALUES (...)` no preceding moderate → zero findings.
17. **Composition with `BlockExclusionJoinRule`**: a fixture line that exercises BOTH (a content-write missing moderate AND a query missing block-exclusion join) → produces TWO independent findings (one per rule), no cross-suppression. The composition test asserts the two finding-IDs both appear.
18. **Provider registration**: a Kotest block `"rule registered in NearYouRuleSetProvider"` co-located inside `ContentWriteRequiresModerationLintTest.kt` (NOT a separate `NearYouRuleSetProviderTest.kt` file) asserts `NearYouRuleSetProvider().instance(Config.empty).ruleSet.rules.map { it::class.simpleName }.contains("ContentWriteRequiresModerationRule")`.

#### Scenario: Test class exists and all positive cases fire
- **WHEN** running `./gradlew :lint:detekt-rules:test`
- **THEN** `ContentWriteRequiresModerationLintTest` is discovered AND every positive-case fixture (1, 2, 3, 4, 5, 6, 11, 12) produces exactly one finding under `ContentWriteRequiresModerationRule` AND each finding's reported message includes the recommended-fix text mentioning `TextModerator.moderate`

#### Scenario: All negative cases pass
- **WHEN** running `./gradlew :lint:detekt-rules:test`
- **THEN** every negative-case fixture (7, 8, 9, 10, 15, 16) produces zero findings under `ContentWriteRequiresModerationRule`

#### Scenario: Allowlist mechanisms suppress findings
- **WHEN** test-source allowlist fixture (13) AND package-FQN-fallback fixture (14) are evaluated
- **THEN** both produce zero findings under `ContentWriteRequiresModerationRule` (the allowlist short-circuits before per-call detection)

#### Scenario: Composition with `BlockExclusionJoinRule` produces independent findings
- **WHEN** fixture (17) is run against BOTH `ContentWriteRequiresModerationRule` AND `BlockExclusionJoinRule` in the same Detekt invocation
- **THEN** the captured findings list contains exactly one finding from each rule (two findings total) AND neither rule suppresses the other

#### Scenario: Project Detekt run includes the rule
- **WHEN** running `./gradlew detekt` against the full project codebase (post-implementation)
- **THEN** `ContentWriteRequiresModerationRule` is one of the executed rules AND every existing content-write call site in `:backend:ktor` either (a) invokes `TextModerator.moderate(...)` first (the canonical shape per the existing 3 integration tests) OR (b) is annotated with `@AllowContentWriteWithoutModeration("<reason>")` for legitimate carve-outs. If any existing call site fires the rule, the implementation MUST either fix the call site (preferred — add the moderate call) OR add the appropriate annotation (with documented reason) BEFORE proceeding to push.

## MODIFIED Requirements

### Requirement: `TextModerator` integration is invoked AFTER existing length and rate-limit gates, BEFORE INSERT

Every write-path handler that wires `TextModerator.moderate(...)` SHALL run the moderator AFTER existing length validation, rate-limit check, and (where applicable) block check, AND BEFORE the content INSERT. The exact order is:

1. Authentication / authorization
2. Per-endpoint rate limit (existing where applicable)
3. Block check (existing where applicable: chat send)
4. Content length guard (post 280 / reply 280 / chat 2000)
5. **`TextModerator.moderate(content)`** ← Layer 1 + Layer 2 (synchronous)
6. INSERT into the relevant table
7. **`layer3DispatcherScope.dispatch(coroutineContext) { layer3Moderator.moderate(targetType, newRowId, content) }`** ← Layer 3 (asynchronous, fire-and-forget) — applies to `posts` and `post_replies` per the [`text-moderation-perspective-api-layer`](../../../specs/text-moderation-perspective-api-layer/spec.md) capability; does NOT apply to chat as of this change (see that capability's design Open Question 1 for the chat deferral rationale)
8. (chat path only) broadcast publish via `ChatRealtimeClient`

This ordering ensures: cheap deterministic checks (length, rate limit, block) reject malformed/abusive requests before invoking the moderator (which has Redis/Remote Config network surface); the moderator runs against already-length-validated content (no content too long to fingerprint); content is moderated before becoming visible.

For `Verdict.Flag`, the `moderation_queue` row SHALL be written in the same SQL transaction as the content INSERT, with `ON CONFLICT (target_type, target_id, trigger) DO NOTHING` for idempotency (per existing [`moderation-queue/spec.md`](../../specs/moderation-queue/spec.md) UNIQUE constraint).

**Layer 3 boundary:** Layer 3 (asynchronous toxicity classifier — OpenAI Moderation today, vendor-neutral interface) runs AFTER the synchronous INSERT in a separate async dispatcher (the `Layer3DispatcherScope`). Layer 3 is NOT part of the synchronous `Verdict` produced by `TextModerator.moderate(content)`. The synchronous moderator returns `Allow` / `Reject` / `Flag`; the asynchronous Layer 3 produces a separate `Outcome` (`NoAction` / `FlagOnly` / `AutoHide`) per the [`text-moderation-perspective-api-layer`](../../../specs/text-moderation-perspective-api-layer/spec.md) capability. The two surfaces have different lifetimes (synchronous request vs fire-and-forget coroutine), different transaction boundaries (Layer 1+2 share the request transaction; Layer 3 owns its own transaction), and different failure semantics (Layer 1+2 throw or return Allow on loader failure; Layer 3 always fails open via `Outcome.NoAction`).

**Defense-in-depth enforcement.** This call-order contract is enforced by TWO complementary layers:

1. **Test-time** (existing): three static-source-scan integration tests at [`PostCreationModerationIntegrationTest.kt:477`](../../../../backend/ktor/src/test/kotlin/id/nearyou/app/post/PostCreationModerationIntegrationTest.kt) (`"POST /api/v1/posts handler source: length guard → moderator → INSERT call order"`), [`PostRepliesModerationIntegrationTest.kt:406`](../../../../backend/ktor/src/test/kotlin/id/nearyou/app/engagement/PostRepliesModerationIntegrationTest.kt) (`"ReplyService.post: visibility resolution → moderator → INSERT → notification emit call order"`), and [`ChatModerationIntegrationTest.kt:442`](../../../../backend/ktor/src/test/kotlin/id/nearyou/app/chat/ChatModerationIntegrationTest.kt) (`"ChatRepository.sendMessage: block check → preInsertHookInTx → INSERT call order"`).

2. **Compile-time** (new — added by the `ContentWriteRequiresModerationRule` requirement above): a Detekt rule that fires on candidate content-write call sites when the enclosing function does NOT also invoke `TextModerator.moderate(...)` BEFORE the write call. The rule's allowlist mechanism (`@AllowContentWriteWithoutModeration("<reason>")` annotation with reasons `tombstone` / `admin_redaction` / `seed`) provides documented bypass for legitimate carve-outs.

The two layers are independent — neither replaces the other. Compile-time enforcement provides faster developer feedback (IDE squiggles) and catches regressions in NEW write-path surfaces that the existing 3 integration tests don't scan; test-time enforcement provides end-to-end behavioural assertion (HTTP handler → moderator → INSERT) that compile-time PSI walks cannot.

#### Scenario: Moderator runs after length guard, before INSERT
- **WHEN** a write-path handler is statically analyzed for the call order of `contentLengthGuard`, `TextModerator.moderate`, and the canonical INSERT call
- **THEN** the call order is exactly: `contentLengthGuard` → `TextModerator.moderate` → `INSERT` (no INSERT before moderate; no moderate before length guard)

#### Scenario: Flag verdict writes moderation_queue row in the same transaction as INSERT
- **WHEN** `Verdict.Flag` is produced AND the handler proceeds to INSERT
- **THEN** the SQL transaction emits both the content INSERT (e.g., `INSERT INTO posts ...`) AND the `INSERT INTO moderation_queue (target_type, target_id, trigger, ...) VALUES (...) ON CONFLICT DO NOTHING` AND a single COMMIT (atomicity)

#### Scenario: Flag with idempotent retry does not double-write moderation_queue
- **GIVEN** a `Verdict.Flag` writes a `moderation_queue` row with `(target_type, target_id, trigger) = ('post', U, 'uu_ite_keyword_match')` AND a retry of the same content+target produces another `Verdict.Flag`
- **WHEN** the second handler attempts the INSERT
- **THEN** the `ON CONFLICT (target_type, target_id, trigger) DO NOTHING` clause suppresses the duplicate AND the queue contains exactly one row for that target+trigger

#### Scenario: Concurrent Flag inserts collapse to one queue row
- **GIVEN** two concurrent transactions T1 and T2 each produce a `Verdict.Flag` for the same target tuple `(target_type='post', target_id=P, trigger='uu_ite_keyword_match')` (e.g., a retry race where both clients submit the same content nearly simultaneously, both pass auth + length + moderation, both reach the INSERT step)
- **WHEN** both transactions execute `INSERT INTO moderation_queue ... ON CONFLICT (target_type, target_id, trigger) DO NOTHING` AND both COMMIT
- **THEN** exactly one `moderation_queue` row exists for the tuple AND neither transaction surfaces a unique-violation error to its caller (mirrors the existing `moderation-queue` capability's idempotency guarantee on the auto-hide-3-reports writer; the Layer 2 writer this change introduces has the same semantics)

#### Scenario: Layer 3 dispatch fires AFTER the INSERT commit (post path)
- **WHEN** the `POST /api/v1/posts` handler runs AND `TextModerator.moderate(content)` returns `Verdict.Allow` AND the INSERT into `posts` commits successfully
- **THEN** within the handler scope, `layer3DispatcherScope.dispatch(coroutineContext) { layer3Moderator.moderate(POST, <new post id>, content) }` is invoked exactly once AFTER the INSERT commit AND BEFORE the response is sent (Layer 3 runs in a fire-and-forget coroutine; the response is not blocked on the vendor)

#### Scenario: Layer 3 dispatch fires AFTER the INSERT commit (reply path)
- **WHEN** the `POST /api/v1/posts/{post_id}/replies` handler runs AND `TextModerator.moderate(content)` returns `Verdict.Allow` AND the INSERT into `post_replies` commits successfully
- **THEN** within the handler scope, `layer3DispatcherScope.dispatch(coroutineContext) { layer3Moderator.moderate(REPLY, <new reply id>, content) }` is invoked exactly once AFTER the INSERT commit AND BEFORE the response is sent

#### Scenario: Layer 1 reject prevents Layer 3 dispatch
- **WHEN** `TextModerator.moderate(content)` returns `Verdict.Reject(matchedKeywords = listOf("badword"))` AND the handler returns HTTP 400
- **THEN** `layer3DispatcherScope.dispatch(coroutineContext) { ... }` is NOT invoked (no row was INSERTed; no target exists to moderate; the handler short-circuits before reaching the dispatch call site — verifiable via mock-spy call count on the dispatcher scope)

#### Scenario: Compile-time rule fires on new content-write surface without preceding moderate
- **WHEN** a future contributor adds a new content-write function (e.g., a Phase 4 admin chat-redaction handler that updates `chat_messages.content`, or a future post-edit handler that updates `posts.content`) AND the function does NOT invoke `TextModerator.moderate(content)` BEFORE the write AND the function does NOT carry the `@AllowContentWriteWithoutModeration("<reason>")` annotation
- **THEN** the `./gradlew detekt` run fails with a `ContentWriteRequiresModerationRule` finding on the write call site (the compile-time defence catches the regression at IDE-feedback latency, before the existing 3 test-time integration tests would surface it AND before any new write path slips through their path-specific scans)
