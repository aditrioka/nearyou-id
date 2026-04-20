## ADDED Requirements

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
