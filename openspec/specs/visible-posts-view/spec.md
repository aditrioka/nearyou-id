# visible-posts-view Specification

## Purpose
TBD - created by archiving change post-creation-geo. Update Purpose after archive.
## Requirements
### Requirement: visible_posts view definition

Migration `V4__post_creation.sql` SHALL create a SQL view `visible_posts` defined as `SELECT * FROM posts WHERE is_auto_hidden = FALSE`. The view MUST be created in the same migration as the `posts` table, not in a later migration.

#### Scenario: View exists after V4
- **WHEN** querying `pg_views WHERE viewname = 'visible_posts'`
- **THEN** one row returns AND its `definition` contains both `FROM posts` and `is_auto_hidden = FALSE`

### Requirement: View excludes auto-hidden rows

Rows where `is_auto_hidden = TRUE` MUST NOT appear in `visible_posts`. Rows where `is_auto_hidden = FALSE` MUST appear.

#### Scenario: Hidden row hidden
- **WHEN** a `posts` row has `is_auto_hidden = TRUE`
- **THEN** `SELECT 1 FROM visible_posts WHERE id = <that row>` returns zero rows

#### Scenario: Unhidden row visible
- **WHEN** a `posts` row has `is_auto_hidden = FALSE`
- **THEN** `SELECT 1 FROM visible_posts WHERE id = <that row>` returns one row

#### Scenario: Toggle updates visibility live
- **WHEN** a `posts` row is flipped from `is_auto_hidden = TRUE` to `FALSE`
- **THEN** `visible_posts` starts returning that row WITHOUT a view refresh step

### Requirement: Detekt rule RawFromPostsRule enforces view usage

A Detekt custom rule `RawFromPostsRule` SHALL live under `build-logic/detekt-rules/` (creating that module if absent) and MUST be active in the Detekt config applied to `backend/ktor` and `backend/ktor/src/main/resources/db/migration/`. The rule SHALL flag case-insensitive matches of `\bFROM\s+posts\b` and `\bJOIN\s+posts\b` in:
- Kotlin string literals (`.kt` files)
- SQL resource files (`.sql` files under `backend/ktor/src/main/resources/db/`)

#### Scenario: Rule present in Detekt config
- **WHEN** reading the project Detekt configuration
- **THEN** `RawFromPostsRule` is listed as an active rule

#### Scenario: Violating Kotlin file fails
- **WHEN** a non-allowed Kotlin file contains a string literal `"SELECT id FROM posts"`
- **THEN** `./gradlew detekt` fails AND the output identifies `RawFromPostsRule` as the source

### Requirement: Allowed paths for raw posts reads

The rule SHALL allow `FROM posts` / `JOIN posts` in:
1. Files under `backend/ktor/src/main/kotlin/id/nearyou/app/post/repository/` whose filename starts with `PostOwnContent`.
2. Any Kotlin function or class annotated with `@AllowRawPostsRead("<reason>")` — the annotation MUST be declared under `:backend:ktor` and MUST require a non-empty reason string.
3. Any file under `backend/ktor/src/main/kotlin/id/nearyou/app/admin/`.
4. The migration file `backend/ktor/src/main/resources/db/migration/V4__post_creation.sql` (where the `visible_posts` view is defined on top of `FROM posts`).

#### Scenario: Own-content file allowed
- **WHEN** `PostOwnContentRepository.kt` contains `"SELECT * FROM posts WHERE author_user_id = ?"`
- **THEN** `./gradlew detekt` passes

#### Scenario: Annotation allows exception
- **WHEN** a function annotated `@AllowRawPostsRead("legacy admin export, removing in Phase 3.5")` contains `"FROM posts"`
- **THEN** `./gradlew detekt` passes

#### Scenario: Admin module exempt
- **WHEN** `backend/ktor/src/main/kotlin/id/nearyou/app/admin/AdminPostsRepository.kt` contains `"SELECT * FROM posts"`
- **THEN** `./gradlew detekt` passes

#### Scenario: V4 migration exempt
- **WHEN** `V4__post_creation.sql` contains `CREATE VIEW visible_posts AS SELECT * FROM posts ...`
- **THEN** `./gradlew detekt` passes

### Requirement: Rule fires on representative cases

The rule test suite SHALL contain positive-fail cases exercising each of:
- Kotlin string literal in a non-allowed file
- Kotlin string literal concatenated over multiple lines (`"SELECT id\n" + "FROM posts"`)
- `.sql` file in `db/migration/` other than V4 containing `FROM posts`

Each MUST fail the Detekt run.

#### Scenario: Multi-line concatenation caught
- **WHEN** a non-allowed Kotlin file contains `"SELECT id\n" + "FROM posts WHERE x = 1"`
- **THEN** `./gradlew detekt` fails with `RawFromPostsRule`

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

### Requirement: RawFromPostsRule allowlist extended for ReportService target-existence readers

The `RawFromPostsRule` Detekt custom rule SHALL extend its allowlist to permit raw `FROM posts` / `FROM post_replies` / `FROM users` / `FROM chat_messages` point-lookups in one additional code path: the V9 `ReportService` (and its target-existence helper) for the `POST /api/v1/reports` endpoint. Specifically:

- Files under `backend/ktor/src/main/kotlin/id/nearyou/app/moderation/` whose filename starts with `Report` (e.g., `ReportService.kt`, `ReportRoutes.kt`, `ReportController.kt`, `ReportTargetResolver.kt`).

These files MAY issue `SELECT 1 FROM posts WHERE id = ? AND deleted_at IS NULL` (and equivalent lookups on `post_replies`, `users`, `chat_messages`) without going through `visible_*` views and without bidirectional `user_blocks` exclusion. The existing `@AllowRawPostsRead("<reason>")` annotation mechanism remains available; adding the directory-based exception is the convention for a new module whose point-lookups are universally legitimate.

The rationale is spec-level: the Report submission flow must accept reports on content the reporter cannot currently see (target shadow-banned, bidirectionally blocked, or already auto-hidden) — filtering the target-existence check through `visible_*` views or block joins would suppress exactly the signal the reports table is meant to capture. See `reports` capability requirement "Target existence check WITHOUT block-exclusion filtering" for the product reasoning.

#### Scenario: ReportService.kt allowed to FROM posts without visible_posts
- **WHEN** `backend/ktor/src/main/kotlin/id/nearyou/app/moderation/ReportService.kt` contains `"SELECT 1 FROM posts WHERE id = ? AND deleted_at IS NULL"`
- **THEN** `./gradlew detekt` passes (the moderation-directory + `Report*` filename allowlist entry applies)

#### Scenario: ReportTargetResolver.kt allowed to FROM post_replies without visible_users join
- **WHEN** `ReportTargetResolver.kt` (under `.../moderation/`) contains `"SELECT 1 FROM post_replies WHERE id = ? AND deleted_at IS NULL"`
- **THEN** `./gradlew detekt` passes

#### Scenario: ReportService allowed to FROM users for self-report check
- **WHEN** `ReportService.kt` contains `"SELECT 1 FROM users WHERE id = ? AND deleted_at IS NULL"`
- **THEN** `./gradlew detekt` passes

#### Scenario: ReportService allowed to FROM chat_messages for chat-message-target existence
- **WHEN** `ReportService.kt` contains `"SELECT 1 FROM chat_messages WHERE id = ?"`
- **THEN** `./gradlew detekt` passes

#### Scenario: Non-Report file under .../moderation/ still subject to the rule
- **WHEN** a file named `ModerationDashboardReader.kt` (not starting with `Report`) under `.../moderation/` contains `"SELECT * FROM posts"`
- **THEN** `./gradlew detekt` fails via `RawFromPostsRule` (the allowlist narrows to `Report*` filenames, not the whole directory)

#### Scenario: Report* file outside .../moderation/ still subject to the rule
- **WHEN** a file `ReportLikeDashboard.kt` outside `.../moderation/` contains `"SELECT * FROM posts"`
- **THEN** `./gradlew detekt` fails (the allowlist requires both the directory AND the filename prefix)

### Requirement: visible_posts view definition unchanged by V9

V9 MUST NOT alter the `visible_posts` view definition. Its authoritative form remains `SELECT * FROM posts WHERE is_auto_hidden = FALSE`. V9 relies exactly on this predicate — flipping `posts.is_auto_hidden = TRUE` is the entire mechanism by which V9 removes a reported post from every authenticated read path. No view-level change is needed.

#### Scenario: View definition unchanged
- **WHEN** querying `pg_views WHERE viewname = 'visible_posts'` after V9 has run
- **THEN** the view's `definition` remains `SELECT * FROM posts WHERE is_auto_hidden = FALSE` (semantic equivalence; Postgres may canonicalize whitespace/case but the predicate is unchanged)

### Requirement: V9-era writer confirms the V4 contract

The V9 reports auto-hide path (see `reports` capability) SHALL be the first production code path that sets `posts.is_auto_hidden = TRUE` in a non-admin code path (admin flips are a Phase 3.5 capability). The existence of this writer confirms the V4 contract: `visible_posts` is the sole authenticated read path, and its filter is the sole enforcement mechanism for "hidden" content. No read path added in V9 reads `visible_posts` directly.

#### Scenario: V9 writer exists and targets is_auto_hidden
- **WHEN** searching `backend/ktor/src/main/kotlin/id/nearyou/app/moderation/` for `UPDATE posts SET is_auto_hidden = TRUE`
- **THEN** the match is present in the ReportService auto-hide code path (confirms the V4 contract is now actively exercised)

#### Scenario: No new reader of visible_posts in V9
- **WHEN** searching code introduced by V9 for `FROM visible_posts`
- **THEN** no matches are found (V9 is write-only against the `is_auto_hidden` column; reads are unchanged)

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

