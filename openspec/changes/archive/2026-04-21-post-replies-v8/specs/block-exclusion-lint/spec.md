## ADDED Requirements

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
