## ADDED Requirements

### Requirement: View alone is insufficient for authenticated read paths

The `visible_posts` view filters `is_auto_hidden = FALSE` ONLY. It does NOT encode block exclusion. Any authenticated read path that surfaces post rows to a viewer MUST also bidirectionally exclude `user_blocks` per the `block-exclusion-lint` capability. Documentation in `V5__user_blocks.sql` (the migration that introduces `user_blocks`) SHALL state this requirement explicitly — V4 is not amended because it has already been applied to dev DBs and changing it would break Flyway checksums.

#### Scenario: V5 migration carries the consumer-requirement comment
- **WHEN** reading `V5__user_blocks.sql` header
- **THEN** the file contains a comment noting that the canonical Nearby query embeds bidirectional `user_blocks` NOT-IN subqueries AND that any business read against `visible_posts` MUST do the same

#### Scenario: Authenticated read without block exclusion fails lint
- **WHEN** a non-allowed Kotlin file contains `"SELECT * FROM visible_posts WHERE ..."` without bidirectional `user_blocks` exclusion
- **THEN** `./gradlew detekt` fails via `BlockExclusionJoinRule` (per `block-exclusion-lint` capability)

### Requirement: Lint allowlist semantics for visible_posts

`BlockExclusionJoinRule` (per `block-exclusion-lint` capability) SHALL treat `visible_posts` as a protected table identical to `posts`, `users`, `chat_messages`, and `post_replies`. The same allowlist mechanisms MUST apply: `.../admin/` paths, repository own-content files, `@AllowMissingBlockJoin("reason")` annotations, and the V5 migration file.

#### Scenario: Admin query on visible_posts allowed
- **WHEN** a file under `.../admin/` contains `"SELECT id, content FROM visible_posts LIMIT 100"` with no block exclusion
- **THEN** `./gradlew detekt` passes for that file
