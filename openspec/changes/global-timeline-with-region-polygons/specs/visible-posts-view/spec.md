## ADDED Requirements

### Requirement: visible_posts view projects city_name and city_admin_region_id after V11

V11 SHALL recreate the `visible_posts` view via `CREATE OR REPLACE VIEW visible_posts AS SELECT * FROM posts WHERE is_auto_hidden = FALSE`. The view's filter (`is_auto_hidden = FALSE`) and name are unchanged; the `SELECT *` projection automatically includes the two new columns `city_name` and `city_admin_region_id` added by V11 to `posts`. The migration MUST issue the `CREATE OR REPLACE VIEW` explicitly (not rely on Postgres auto-extending `*`) so the view's definition in `pg_views` reflects the column additions on re-creation.

#### Scenario: View definition still SELECT * over is_auto_hidden filter
- **WHEN** querying `pg_views WHERE viewname = 'visible_posts'` after V11 has run
- **THEN** the view's `definition` contains `FROM posts` AND `is_auto_hidden = FALSE` (semantic equivalence; whitespace may differ)

#### Scenario: View projects city_name
- **WHEN** running `SELECT city_name FROM visible_posts LIMIT 1` after V11
- **THEN** the query succeeds (the column is projected through the view)

#### Scenario: View projects city_admin_region_id
- **WHEN** running `SELECT city_admin_region_id FROM visible_posts LIMIT 1` after V11
- **THEN** the query succeeds (the column is projected through the view)

#### Scenario: Existing view consumers unaffected by new columns
- **WHEN** the Nearby and Following timeline queries (which explicitly project named columns, not `*`) run after V11
- **THEN** both queries continue to succeed without modification (the new columns are additive)

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
- **WHEN** reading the top of `V11__admin_regions_and_post_city.sql`
- **THEN** the file contains a comment naming `nearby-timeline`, `following-timeline`, AND `global-timeline` as read-path consumers that must bidirectionally join `user_blocks` on top of `visible_posts`
