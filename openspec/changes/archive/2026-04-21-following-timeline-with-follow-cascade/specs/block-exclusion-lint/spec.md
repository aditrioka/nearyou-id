## ADDED Requirements

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
