## ADDED Requirements

### Requirement: Global timeline query is a new protected literal that passes the rule

The canonical Global timeline SQL introduced by `GlobalTimelineService` (per `global-timeline` capability) contains `FROM visible_posts` — a protected-table token under the existing `BlockExclusionJoinRule`. The query SHALL include both bidirectional `user_blocks` NOT-IN subqueries such that the rule's token check passes:
- Contains `user_blocks`.
- Contains `blocker_id =`.
- Contains `blocked_id =`.

No rule-behavior change is required for this capability — the existing V5-era rule already handles the new literal correctly. This requirement exists to document the Global query as an explicit positive-pass fixture in the rule's test suite so future drift (a maintainer inadvertently removing one of the two subqueries) is caught.

#### Scenario: Global query passes BlockExclusionJoinRule
- **WHEN** `./gradlew detekt` runs against `GlobalTimelineService.kt` (or its repository)
- **THEN** `BlockExclusionJoinRule` does NOT flag the file

#### Scenario: Rule test suite contains a Global-timeline positive-pass fixture
- **WHEN** listing the Kotlin test resources under `lint/detekt-rules/src/test/`
- **THEN** a fixture exists representing the canonical Global query (FROM visible_posts + both user_blocks subqueries) AND the rule test asserts the rule does NOT fire on it

### Requirement: Rule test suite adds negative fixture for Global timeline missing a subquery

The rule's test suite SHALL contain a negative-fail fixture where a Global-shaped literal (`FROM visible_posts` + a follows/global-style filter) omits one of the two `user_blocks` subqueries. The rule MUST fire on this fixture. This defends against a drift class where a maintainer writes a Global variant that accidentally mirrors one directional check only.

#### Scenario: Global query missing blocked_id subquery fails
- **WHEN** a test fixture contains `"SELECT * FROM visible_posts WHERE author_user_id NOT IN (SELECT blocked_id FROM user_blocks WHERE blocker_id = :v)"` (missing the second, bidirectional subquery)
- **THEN** the rule test asserts `BlockExclusionJoinRule` fires on the fixture

#### Scenario: Global query missing blocker_id subquery fails
- **WHEN** a test fixture contains `"SELECT * FROM visible_posts WHERE author_user_id NOT IN (SELECT blocker_id FROM user_blocks WHERE blocked_id = :v)"` (missing the first subquery)
- **THEN** the rule test asserts `BlockExclusionJoinRule` fires on the fixture

### Requirement: admin_regions is NOT a protected table

The rule's protected-table list (`posts`, `visible_posts`, `users`, `chat_messages`, `post_replies`) SHALL NOT be extended to include `admin_regions`. Polygons carry administrative boundary data, not user-visible content; queries against `admin_regions` are reference-data lookups and do not need block-exclusion. Adding it would force the `posts_set_city_tg` trigger and any admin-module polygon query to contain meaningless `user_blocks` subqueries.

A KDoc paragraph in `BlockExclusionJoinRule.kt` SHOULD state this reasoning explicitly so future maintainers do not add `admin_regions` to the protected list.

#### Scenario: admin_regions literal does not trigger the rule
- **WHEN** a non-allowed file contains `"SELECT id FROM admin_regions WHERE level = 'kabupaten_kota'"`
- **THEN** `./gradlew detekt` passes (the rule does not fire on `admin_regions`)

#### Scenario: KDoc documents admin_regions exclusion reasoning
- **WHEN** reading the KDoc of `BlockExclusionJoinRule.kt`
- **THEN** a paragraph explains why `admin_regions` (and other reference-data tables) is deliberately outside the protected set
