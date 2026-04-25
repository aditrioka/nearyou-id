## ADDED Requirements

### Requirement: visible_posts view already projects city_name and city_match_type; V11 refresh is a no-op

V11 SHALL re-issue `CREATE OR REPLACE VIEW visible_posts AS SELECT * FROM posts WHERE is_auto_hidden = FALSE` verbatim as an idempotent no-op refresh. The `visible_posts` view was created in V4 with the same definition, and because `posts.city_name` + `posts.city_match_type` already exist on `posts` from V4, the view has ALWAYS projected both columns via `SELECT *`. V11 MUST NOT add or drop any projected column; V11 MUST NOT change the view's filter; V11 MUST NOT break any existing consumer. The re-issue is purely an audit gesture so `pg_views.definition` reflects V11's audit timestamp and future consumers have an explicit V11 anchor for the view definition.

#### Scenario: View definition still SELECT * over is_auto_hidden filter
- **WHEN** querying `pg_views WHERE viewname = 'visible_posts'` after V11 has run
- **THEN** the view's `definition` contains `FROM posts` AND `is_auto_hidden = FALSE` (semantic equivalence; whitespace may differ)

#### Scenario: View projects city_name (inherited from V4)
- **WHEN** running `SELECT city_name FROM visible_posts LIMIT 1` on a V4-era DB and again after V11
- **THEN** both queries succeed (the column was always projected via `SELECT *`)

#### Scenario: View projects city_match_type (inherited from V4)
- **WHEN** running `SELECT city_match_type FROM visible_posts LIMIT 1` on a V4-era DB and again after V11
- **THEN** both queries succeed

#### Scenario: Existing view consumers unaffected by V11 refresh
- **WHEN** the Nearby and Following timeline queries (which explicitly project named columns) run after V11
- **THEN** both queries continue to succeed without modification (no column was added or removed)

### Requirement: global-timeline is a new consumer requiring bidirectional block exclusion

`GET /api/v1/timeline/global` (see `global-timeline` capability) is an authenticated read path that surfaces post rows to a viewer. Like `nearby-timeline` and `following-timeline`, it MUST query `FROM visible_posts` AND MUST bidirectionally exclude `user_blocks` rows via the two NOT-IN subqueries. The `visible_posts` view's filter (`is_auto_hidden = FALSE`) remains necessary but NOT sufficient — block exclusion is still a caller-time join, not encoded in the view.

#### Scenario: Global query joins visible_posts and bidirectional user_blocks
- **WHEN** reading the Global timeline SQL in `GlobalTimelineService` (or its repository)
- **THEN** the query contains `FROM visible_posts` AND `user_blocks` with `blocker_id = :viewer` AND `user_blocks` with `blocked_id = :viewer`

#### Scenario: Global query passes BlockExclusionJoinRule
- **WHEN** `./gradlew detekt` runs against the Global timeline SQL
- **THEN** `BlockExclusionJoinRule` does NOT flag the query

### Requirement: Consumers list grows to include global-timeline

The enumerated set of authenticated business read paths that MUST perform the bidirectional `user_blocks` join on top of `visible_posts` is now at least: `nearby-timeline`, `following-timeline`, `global-timeline`. The V11 migration header SHALL reference this consumer list so future authors see the convention at point-of-use.

#### Scenario: V11 header references the consumer list
- **WHEN** reading the top of `V11__admin_regions.sql`
- **THEN** the file contains a comment naming `nearby-timeline`, `following-timeline`, AND `global-timeline` as read-path consumers that must bidirectionally join `user_blocks` on top of `visible_posts`
