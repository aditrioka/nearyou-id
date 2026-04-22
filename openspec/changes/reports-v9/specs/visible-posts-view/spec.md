## ADDED Requirements

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
