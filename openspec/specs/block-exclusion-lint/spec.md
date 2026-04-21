# block-exclusion-lint Specification

## Purpose

Defines the Detekt custom rule `BlockExclusionJoinRule` that enforces bidirectional `user_blocks` exclusion on every authenticated business query touching the protected tables (`posts`, `visible_posts`, `users`, `chat_messages`, `post_replies`). Documents the trigger condition, the allowlist mechanisms (admin path, own-content repository file-name pattern, `@AllowMissingBlockJoin` annotation), composition with `RawFromPostsRule`, and lint test coverage. SQL file scanning is explicitly deferred — Detekt's PSI walker only inspects Kotlin sources.
## Requirements
### Requirement: BlockExclusionJoinRule lives under lint/detekt-rules

A Detekt custom rule `BlockExclusionJoinRule` SHALL be added to the `:lint:detekt-rules` module (the same module that hosts `RawFromPostsRule` from post-creation-geo). The rule MUST be registered in `NearYouRuleSetProvider` and activated in the project Detekt configuration applied to `:backend:ktor`.

#### Scenario: Rule registered
- **WHEN** reading the project Detekt configuration
- **THEN** `BlockExclusionJoinRule` is listed as an active rule

#### Scenario: Rule file exists
- **WHEN** listing `lint/detekt-rules/src/main/kotlin/id/nearyou/lint/detekt/`
- **THEN** a file named `BlockExclusionJoinRule.kt` exists

### Requirement: Rule trigger condition

The rule SHALL fire on Kotlin string literals (`.kt` files) that contain a case-insensitive `\bFROM\s+(posts|visible_posts|users|chat_messages|post_replies)\b` OR `\bJOIN\s+(posts|visible_posts|users|chat_messages|post_replies)\b` token, UNLESS the same literal ALSO contains all of:
1. The token `user_blocks` (case-insensitive)
2. A fragment matching `\bblocker_id\s*=` (case-insensitive)
3. A fragment matching `\bblocked_id\s*=` (case-insensitive)

The rule MUST handle multi-line Kotlin string concatenation in the same way as `RawFromPostsRule` (treat all string literal pieces of a single concatenation as one logical literal).

#### Scenario: Query missing both block-exclusion subqueries fails
- **WHEN** a non-allowed Kotlin file contains the literal `"SELECT * FROM visible_posts WHERE author_id = ?"` (no `user_blocks` join)
- **THEN** `./gradlew detekt` fails AND the output identifies `BlockExclusionJoinRule` as the source

#### Scenario: Query with both block-exclusion subqueries passes
- **WHEN** a Kotlin file contains the literal `"SELECT * FROM visible_posts WHERE author_id NOT IN (SELECT blocked_id FROM user_blocks WHERE blocker_id = ?) AND author_id NOT IN (SELECT blocker_id FROM user_blocks WHERE blocked_id = ?)"`
- **THEN** `./gradlew detekt` passes for that file

#### Scenario: Query missing only blocked_id direction fails
- **WHEN** a Kotlin file contains a query that joins `user_blocks` with `blocker_id =` but NO `blocked_id =` fragment
- **THEN** `./gradlew detekt` fails AND identifies `BlockExclusionJoinRule`

#### Scenario: Multi-line concatenation handled
- **WHEN** a non-allowed Kotlin file contains `"SELECT id\n" + "FROM posts WHERE x = 1"` (no block exclusion)
- **THEN** `./gradlew detekt` fails with `BlockExclusionJoinRule`

### Requirement: Allowlists for block-exclusion lint

The rule SHALL allow queries that touch the protected tables WITHOUT a bidirectional `user_blocks` join in any of:
1. Files under `backend/ktor/src/main/kotlin/id/nearyou/app/admin/` (admin code is exempt — admins need full visibility).
2. Repository own-content files: filename starts with one of `PostOwnContent`, `UserOwn`, `ChatOwn`, or `ReplyOwn` AND lives under `backend/ktor/src/main/kotlin/id/nearyou/app/.../repository/`.
3. Any function or class annotated `@AllowMissingBlockJoin("<reason>")`. The annotation MUST be declared under `:backend:ktor` and MUST require a non-empty reason string.

#### Scenario: Admin module exempt
- **WHEN** `backend/ktor/src/main/kotlin/id/nearyou/app/admin/AdminBlockReportRepository.kt` contains `"SELECT * FROM visible_posts"` with no block exclusion
- **THEN** `./gradlew detekt` passes for that file

#### Scenario: Own-content repository exempt
- **WHEN** `PostOwnContentRepository.kt` (under `.../post/repository/`) contains `"SELECT * FROM posts WHERE author_user_id = ?"` (own content, no block exclusion needed)
- **THEN** `./gradlew detekt` passes for `BlockExclusionJoinRule`

#### Scenario: Annotation allows exception
- **WHEN** a function annotated `@AllowMissingBlockJoin("aggregating count for analytics, no per-user surfaces affected")` contains `"SELECT COUNT(*) FROM posts"`
- **THEN** `./gradlew detekt` passes for that function

#### Scenario: Empty annotation reason discouraged by code review
- **WHEN** a developer attempts to add `@AllowMissingBlockJoin("")` on a function
- **THEN** the annotation accepts the empty string at compile time (Kotlin annotations cannot constrain string contents) BUT code review SHALL reject empty-string reasons; the annotation's KDoc MUST state that the reason is mandatory and non-empty

### Requirement: SQL file scanning deferred

This change SHALL scope the rule to Kotlin string literals only — matching the implementation scope of the existing `RawFromPostsRule` from post-creation-geo, which Detekt's PSI walker natively supports. Scanning of `.sql` migration files is deferred to a follow-up change that wires a SQL-aware scanner. The V5 migration file therefore does NOT need an explicit allowlist entry, but the migration's CREATE TABLE statement (which references `users(id)` for FKs) MUST remain naturally exempt because Detekt does not parse `.sql` files.

#### Scenario: SQL files not scanned by Detekt in this change
- **WHEN** running `./gradlew detekt`
- **THEN** the run does NOT error on any `.sql` file content (Detekt only inspects Kotlin sources)

### Requirement: Composes with RawFromPostsRule

`BlockExclusionJoinRule` MUST coexist with `RawFromPostsRule` from post-creation-geo without overlap or interference. A query that touches `posts` directly (not `visible_posts`) MAY trip both rules; this is intentional and documented.

#### Scenario: Both rules independently active
- **WHEN** running `./gradlew detekt` against a fixture that contains a non-allowed `"FROM posts"` literal
- **THEN** the failure output includes both `RawFromPostsRule` and `BlockExclusionJoinRule` (or the rule outputs are at minimum non-conflicting and both rules are invoked)

### Requirement: Lint test coverage

A test class `BlockExclusionJoinLintTest` SHALL exist with positive-fail cases for each of:
- Kotlin string literal in a non-allowed file with a `FROM` against each of the five protected tables (`posts`, `visible_posts`, `users`, `chat_messages`, `post_replies`)
- Kotlin string literal with `JOIN` against a protected table
- Kotlin multi-line string concatenation (`"FROM posts"` split across lines)
- Query that joins `user_blocks` but only in one direction (`blocker_id =` without `blocked_id =`)

AND positive-pass cases for each of:
- Query with both block-exclusion fragments
- File under `.../admin/`
- File matching `PostOwnContent*` pattern
- Function annotated `@AllowMissingBlockJoin("non-empty reason")`

Each fail case MUST cause `./gradlew detekt` to exit non-zero; each pass case MUST exit zero (for the rule under test, holding other rules constant).

#### Scenario: Test class exists and is wired
- **WHEN** running `./gradlew :lint:detekt-rules:test`
- **THEN** the class `BlockExclusionJoinLintTest` is discovered AND every fail/pass case above corresponds to at least one `@Test` method

### Requirement: follows is deliberately NOT a protected table

`BlockExclusionJoinRule`'s protected-tables set (from the V5-era spec: `posts`, `visible_posts`, `users`, `chat_messages`, `post_replies`) SHALL NOT be extended to include `follows`. The `BlockExclusionJoinRule.kt` source file MUST carry a KDoc paragraph documenting this deliberate exclusion with the following reasoning:

1. `follows` rows carry only `(follower_id, followee_id, created_at)` — no user-visible content (no `content`, `bio`, `display_name`, etc.).
2. Business queries against `follows` are always caller-scoped: one side of the query filter is `= :viewer` (the caller's UUID).
3. Content surfaced THROUGH `follows` (the Following timeline) lands on `visible_posts`, where this rule re-checks for bidirectional `user_blocks` exclusion.

This decision prevents future drift where a maintainer, noticing `follows` appears to be "missing" from the protected set, adds it — which would require every follow/unfollow endpoint to wrap its `INSERT INTO follows` / `DELETE FROM follows` / `SELECT ... FROM follows` in an irrelevant `user_blocks` bidirectional join, breaking the endpoints.

#### Scenario: KDoc paragraph present
- **WHEN** reading `lint/detekt-rules/src/main/kotlin/id/nearyou/lint/detekt/BlockExclusionJoinRule.kt`
- **THEN** the class-level KDoc contains a paragraph explaining why `follows` is not a protected table AND citing the three reasons above

#### Scenario: Rule does not fire on FROM follows
- **WHEN** a Kotlin file in a Detekt-scanned module contains the literal `"SELECT follower_id FROM follows WHERE followee_id = ?"` with no `user_blocks` join
- **THEN** `./gradlew detekt` passes for that file (`BlockExclusionJoinRule` does NOT flag it)

### Requirement: Lint test fixture encodes the follows-exclusion decision

`BlockExclusionJoinLintTest` SHALL include a named test fixture `follows_is_deliberately_not_protected_table` that asserts the rule does NOT fire on at least three representative `follows`-only queries:
1. `"SELECT follower_id FROM follows WHERE followee_id = ?"`
2. `"INSERT INTO follows (follower_id, followee_id) VALUES (?, ?) ON CONFLICT (follower_id, followee_id) DO NOTHING"`
3. `"DELETE FROM follows WHERE follower_id = ? AND followee_id = ?"`

This test encodes the exclusion decision in code rather than prose — a future contributor who attempts to add `follows` to the protected set will see this test fail and be forced to confront the decision.

#### Scenario: Test fixture present and green
- **WHEN** running `./gradlew :lint:detekt-rules:test --tests '*BlockExclusionJoinLintTest*'`
- **THEN** a test named `follows_is_deliberately_not_protected_table` (or a close variant) is discovered AND passes AND asserts the rule does NOT fire on all three literal patterns above

### Requirement: post_likes is deliberately NOT a protected table

`BlockExclusionJoinRule`'s protected-tables set (`posts`, `visible_posts`, `users`, `chat_messages`, `post_replies`) SHALL NOT be extended to include `post_likes`. The `BlockExclusionJoinRule.kt` source file MUST carry a KDoc paragraph documenting this deliberate exclusion with the following reasoning (parallel structure to the existing `follows` paragraph added in V6):

1. `post_likes` rows carry only `(post_id, user_id, created_at)` — no user-visible content (no body, no display name, no bio).
2. Business queries against `post_likes` are always caller-scoped: writes filter on `user_id = :caller`; the likes-count read filters on `post_id = :post_id` AND JOINs `visible_users` for shadow-ban exclusion; the timelines' `LEFT JOIN post_likes` is PK-scoped to `(p.id, :viewer)`.
3. Content reached THROUGH `post_likes` (the timelines' `liked_by_viewer` projection) lands on `visible_posts`, where this rule re-checks for bidirectional `user_blocks` exclusion on the primary `FROM visible_posts` clause.

This decision prevents future drift where a maintainer, noticing `post_likes` appears to be "missing" from the protected set, adds it — which would require the `DELETE FROM post_likes WHERE user_id = :caller` self-cleanup call to wrap itself in an irrelevant `user_blocks` bidirectional join, breaking the endpoint.

#### Scenario: KDoc paragraph present
- **WHEN** reading `lint/detekt-rules/src/main/kotlin/id/nearyou/lint/detekt/BlockExclusionJoinRule.kt`
- **THEN** the class-level KDoc contains a paragraph explaining why `post_likes` is not a protected table AND citing the three reasons above

#### Scenario: Rule does not fire on FROM post_likes
- **WHEN** a Kotlin file in a Detekt-scanned module contains the literal `"SELECT COUNT(*) FROM post_likes pl JOIN visible_users vu ON vu.id = pl.user_id WHERE pl.post_id = ?"` with no `user_blocks` join
- **THEN** `./gradlew detekt` passes for that file (`BlockExclusionJoinRule` does NOT flag it)

### Requirement: Lint test fixture encodes the post_likes-exclusion decision

`BlockExclusionJoinLintTest` SHALL include a named test fixture `post_likes_is_deliberately_not_protected_table` that asserts the rule does NOT fire on at least three representative `post_likes`-only queries:
1. `"SELECT COUNT(*) FROM post_likes pl JOIN visible_users vu ON vu.id = pl.user_id WHERE pl.post_id = ?"`
2. `"INSERT INTO post_likes (post_id, user_id) VALUES (?, ?) ON CONFLICT (post_id, user_id) DO NOTHING"`
3. `"DELETE FROM post_likes WHERE post_id = ? AND user_id = ?"`

This test encodes the exclusion decision in code rather than prose — a future contributor who attempts to add `post_likes` to the protected set will see this test fail and be forced to confront the decision (same pattern as the existing `follows_is_deliberately_not_protected_table` fixture from V6).

#### Scenario: Test fixture present and green
- **WHEN** running `./gradlew :lint:detekt-rules:test --tests '*BlockExclusionJoinLintTest*'`
- **THEN** a test named `post_likes_is_deliberately_not_protected_table` (or a close variant) is discovered AND passes AND asserts the rule does NOT fire on all three literal patterns above

### Requirement: post_replies is a protected table with first real readers in V8

`BlockExclusionJoinRule`'s protected-tables set has always listed `post_replies` (per the Phase 1 CI lint plan at `docs/05-Implementation.md:1833` and the existing "Rule trigger condition" requirement). V8 introduces the FIRST production Kotlin string literals that `FROM post_replies` — the reply-list query in `JdbcPostReplyRepository` — so this is where the rule transitions from "listed but unexercised" to "actively gating production code." The rule MUST continue to fire on any `FROM post_replies` or `JOIN post_replies` literal in a non-allowed file that does NOT contain both `blocker_id =` AND `blocked_id =` fragments. Allowlist mechanisms (admin path, own-content `ReplyOwn*` filename pattern, `@AllowMissingBlockJoin` annotation) MUST apply to `post_replies` identically to how they apply to the other four protected tables.

The V7-era rationale — "the spec's non-normative note to reflect that V7's 'not-yet-added' reason for `post_replies` no longer applies" — is retired by this requirement: `post_replies` is fully active from V8 forward.

#### Scenario: Reply-list query missing block exclusion fails
- **WHEN** a non-allowed Kotlin file contains the literal `"SELECT id, post_id, author_id, content, created_at FROM post_replies WHERE post_id = ? AND deleted_at IS NULL"` (no `user_blocks` join)
- **THEN** `./gradlew detekt` fails AND identifies `BlockExclusionJoinRule`

#### Scenario: Reply-list query with both block-exclusion subqueries passes
- **WHEN** a Kotlin file contains the canonical reply-list literal `"SELECT id, post_id, author_id, content, is_auto_hidden, created_at, updated_at FROM post_replies pr JOIN visible_users vu ON vu.id = pr.author_id WHERE pr.post_id = :post_id AND pr.deleted_at IS NULL AND (pr.is_auto_hidden = FALSE OR pr.author_id = :viewer) AND pr.author_id NOT IN (SELECT blocked_id FROM user_blocks WHERE blocker_id = :viewer) AND pr.author_id NOT IN (SELECT blocker_id FROM user_blocks WHERE blocked_id = :viewer) ORDER BY pr.created_at DESC, pr.id DESC LIMIT 31"`
- **THEN** `./gradlew detekt` passes for that file

#### Scenario: Own-reply repository exempt via ReplyOwn* filename pattern
- **WHEN** a file named `ReplyOwnContentRepository.kt` (under `.../engagement/repository/`) contains `"SELECT id FROM post_replies WHERE author_id = :caller"` (own content, no block exclusion needed)
- **THEN** `./gradlew detekt` passes for `BlockExclusionJoinRule`

### Requirement: Lint fixtures for V8 reply-list reader

`BlockExclusionJoinLintTest` SHALL include two named fixtures that codify the V8-era reply-list reader query shape — one positive-pass and one positive-fail — so that a future contributor who refactors the reply repository and accidentally drops one of the `user_blocks` NOT-IN subqueries sees the rule flag the change.

- **Positive-pass fixture** `reply_list_query_with_bidirectional_block_exclusion`: a Kotlin string literal containing `FROM post_replies`, a JOIN to `visible_users`, both `blocker_id =` and `blocked_id =` fragments tied to `user_blocks`, and the `(is_auto_hidden = FALSE OR author_id = :viewer)` filter. MUST NOT trip `BlockExclusionJoinRule`.

- **Positive-fail fixture** `reply_list_query_missing_blocked_id_fails`: a Kotlin string literal containing `FROM post_replies` and a `user_blocks` reference with `blocker_id =` BUT MISSING the `blocked_id =` fragment. MUST trip `BlockExclusionJoinRule`.

The two fixtures MUST live alongside the existing `follows_is_deliberately_not_protected_table` and `post_likes_is_deliberately_not_protected_table` fixtures in `BlockExclusionJoinLintTest`.

#### Scenario: Both V8 fixtures present and green
- **WHEN** running `./gradlew :lint:detekt-rules:test --tests '*BlockExclusionJoinLintTest*'`
- **THEN** a test named `reply_list_query_with_bidirectional_block_exclusion` (or a close variant) is discovered AND passes by asserting the rule does NOT fire on the canonical reply-list literal AND a test named `reply_list_query_missing_blocked_id_fails` (or a close variant) is discovered AND passes by asserting the rule DOES fire on the one-sided literal

