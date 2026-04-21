## ADDED Requirements

### Requirement: Following timeline is an additional view consumer requiring bidirectional block exclusion

`GET /api/v1/timeline/following` (see `following-timeline` capability) is an authenticated read path that surfaces post rows to a viewer. Like `nearby-timeline`, it MUST query `FROM visible_posts` AND MUST bidirectionally exclude `user_blocks` rows via the two NOT-IN subqueries. The `visible_posts` view's filter (`is_auto_hidden = FALSE`) remains necessary but NOT sufficient — block exclusion is still a caller-time join, not encoded in the view.

#### Scenario: Following query joins visible_posts and bidirectional user_blocks
- **WHEN** reading the Following timeline SQL in `FollowingTimelineService` (or its repository)
- **THEN** the query contains `FROM visible_posts` AND `user_blocks` with `blocker_id = :viewer` AND `user_blocks` with `blocked_id = :viewer`

#### Scenario: Following query passes BlockExclusionJoinRule
- **WHEN** `./gradlew detekt` runs against the Following timeline SQL (if it lives in a Detekt-scanned module)
- **THEN** `BlockExclusionJoinRule` does NOT flag the query

### Requirement: Consumers list grows to include following-timeline

The enumerated set of authenticated business read paths that MUST perform the bidirectional `user_blocks` join on top of `visible_posts` is now at least: `nearby-timeline`, `following-timeline`. This set MUST grow (not shrink) as new timeline-style endpoints are added (e.g., future Global timeline). The V6 migration header SHOULD reference this growing list so future authors see the convention at point-of-use.

#### Scenario: V6 header references the consumer list
- **WHEN** reading the top of `V6__follows.sql`
- **THEN** the file contains a comment naming both `nearby-timeline` and `following-timeline` as read-path consumers that must bidirectionally join `user_blocks`
