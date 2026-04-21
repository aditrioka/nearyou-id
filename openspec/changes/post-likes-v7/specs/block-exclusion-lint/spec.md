## ADDED Requirements

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
