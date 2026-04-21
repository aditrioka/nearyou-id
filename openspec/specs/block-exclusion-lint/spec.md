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

