## 1. Secrets & config

- [x] 1.1 Extend `dev/.env.example` with `JITTER_SECRET=` placeholder and a comment documenting the 32-byte base64 format (same pattern as `INVITE_CODE_SECRET`)
- [x] 1.2 Extend `dev/scripts/generate-rsa-keypair.sh` to also emit a `JITTER_SECRET=$(openssl rand -base64 32)` line
- [x] 1.3 Register `"jitter-secret"` in the `SecretResolver` chain — no code change; `EnvVarSecretResolver` at Secrets.kt:16 already normalizes hyphens → underscores
- [x] 1.4 Kotest: extend `SecretsTest` with a `jitter-secret → JITTER_SECRET` case that asserts the resolver returns a 32-byte decoded key
- [x] 1.5 Fail-fast wiring landed in 8.5 Koin wiring: `secrets.resolve("jitter-secret") ?: error("Missing required secret 'jitter-secret' (set JITTER_SECRET)")` mirrors the invite-code check; additional `require(jitterSecret.size == 32)` validates the decoded key length

## 2. :shared:distance Gradle module

- [x] 2.1 Create `shared/distance/build.gradle.kts` applying `kotlin("multiplatform")`; declare `commonMain` with stdlib only; jvm target declared (iOS/native targets deferred to mobile change, as noted in build file comments)
- [x] 2.2 Register `:shared:distance` in the root `settings.gradle.kts`
- [x] 2.3 Add `":shared:distance"` as an `implementation` dependency in `backend/ktor/build.gradle.kts`
- [x] 2.4 Create `shared/distance/src/commonMain/kotlin/id/nearyou/distance/LatLng.kt` — simple data class `LatLng(lat: Double, lng: Double)`
- [x] 2.5 Create `UuidV7.kt` in `:shared:distance` — pure-Kotlin inline implementation in commonMain (48-bit unix-ms + random + version/variant bits)
- [x] 2.6 Create `JitterEngine.kt` exposing `fun offsetByBearing(actual: LatLng, postId: Uuid, secret: ByteArray): LatLng`; HMAC-SHA256 via `expect` bridge with JVM actual backed by `javax.crypto.Mac`
- [x] 2.7 Implement WGS84 great-circle forward offset as private helper `greatCircleOffset` inside `JitterEngine`; public surface is `(offsetByBearing, render, LatLng)` only
- [x] 2.8 Create `DistanceRenderer.kt` with `fun render(distanceMeters: Double): String`; floor-5km / round-to-1km rule implemented; KDoc notes "input is fuzzed distance; function does not fuzz"

## 3. :shared:distance tests

- [x] 3.1 Kotest spec `JitterEngineTest` (jvmTest) — determinism: same inputs produce byte-identical outputs across 10 repeated calls
- [x] 3.2 `JitterEngineTest` — distance bounds: seeded `Random(0xC0FFEEL)` generates 1000 UUIDv7s against a fixed actual LatLng + fixed 32-byte secret; every result lands within [50, 500] m via pure-Kotlin haversine (SplittableRandom was not adapter-friendly; stdlib `Random(seed)` gives the same reproducibility guarantee)
- [x] 3.3 `JitterEngineTest` — secret sensitivity: `offsetByBearing(a, id, sA) != offsetByBearing(a, id, sB)` for `sA != sB`
- [x] 3.4 Kotest spec `DistanceRendererTest` — asserts 4500.0→"5km", 5000.0→"5km", 7400.0→"7km", 7600.0→"8km", 12800.0→"13km"
- [x] 3.5 `./gradlew :shared:distance:allTests` — green; 5+3 tests pass on jvm target

## 4. Flyway V4 migration

- [x] 4.1 Create `backend/ktor/src/main/resources/db/migration/V4__post_creation.sql`
- [x] 4.2 In V4: `CREATE TABLE posts (...)` matching `docs/05-Implementation.md § Posts Schema` verbatim — `id UUID PK DEFAULT gen_random_uuid()`, `author_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT`, `content VARCHAR(280) NOT NULL`, `display_location GEOGRAPHY(POINT, 4326) NOT NULL`, `actual_location GEOGRAPHY(POINT, 4326) NOT NULL`, `city_name TEXT`, `city_match_type VARCHAR(16)`, `image_id TEXT`, `is_auto_hidden BOOLEAN NOT NULL DEFAULT FALSE`, `created_at TIMESTAMPTZ DEFAULT NOW()`, `updated_at TIMESTAMPTZ`, `deleted_at TIMESTAMPTZ`. FTS column `content_tsv` excluded (Search change)
- [x] 4.3 In V4: `CREATE EXTENSION IF NOT EXISTS btree_gist` — required for the composite GIST on `(geography, timestamptz)`
- [x] 4.4 In V4: `CREATE INDEX posts_display_location_idx ON posts USING GIST(display_location);`
- [x] 4.5 In V4: `CREATE INDEX posts_actual_location_idx ON posts USING GIST(actual_location);`
- [x] 4.6 In V4: `CREATE INDEX posts_timeline_cursor_idx ON posts(created_at DESC, id DESC) WHERE deleted_at IS NULL;`
- [x] 4.7 In V4: `CREATE INDEX posts_nearby_cursor_idx ON posts USING GIST(display_location, created_at) WHERE deleted_at IS NULL;`
- [x] 4.8 In V4: `CREATE VIEW visible_posts AS SELECT * FROM posts WHERE is_auto_hidden = FALSE;`
- [x] 4.9 Ran `./gradlew :backend:ktor:processResources :backend:ktor:flywayMigrate --no-configuration-cache` against dev Postgres. Verified: V4 row in `flyway_schema_history` (success=TRUE), `posts` + `visible_posts` relations exist, 4 indexes + pkey present, `posts_author_id_fkey` has `confdeltype = 'r'` (RESTRICT). Note: `processResources` must run before `flywayMigrate` so V4.sql lands in the classpath Flyway scans — documented this caveat in dev/README during section 12

## 5. Migration smoke test

- [x] 5.1 Create `MigrationV4SmokeTest` (Kotest, `@Tags("database")`) mirroring the `MigrationV3SmokeTest` pattern
- [x] 5.2 Assert `posts` exists with the canonical column set + types (also asserts `content_tsv` is absent to guard FTS deferral)
- [x] 5.3 Assert all four indexes exist on `posts` AND that `posts_display_location_idx` + `posts_actual_location_idx` + `posts_nearby_cursor_idx` use access method `gist` (via `pg_class`/`pg_am` join); `posts_timeline_cursor_idx` uses `btree`
- [x] 5.4 Assert `visible_posts` view exists AND its definition contains `from posts` and `is_auto_hidden = false` (case-insensitive)
- [x] 5.5 Assert the `author_id` FK uses `ON DELETE RESTRICT` (via `pg_constraint.confdeltype = 'r'`)
- [x] 5.6 V4 smoke test suite — 6/6 passing locally under `-Dkotest.tags=database` against the dev compose Postgres. Full-suite run (task 12.1) verifies no regressions elsewhere

## 6. Domain + infra contracts

- [x] 6.1 Defined `PostRepository` interface in `:infra:supabase` with `create(conn: Connection, row: NewPostRow): UUID`
- [x] 6.2 Defined `NewPostRow` data class with NOT NULL input fields (`id`, `authorId`, `content`, `actualLat/Lng`, `displayLat/Lng`); nullable extras (`city_name`, `image_id`, etc.) and DB-defaulted columns (`is_auto_hidden`, `created_at`) omitted
- [x] 6.3 Implemented `JdbcPostRepository` with a single prepared INSERT writing both geographies via `ST_SetSRID(ST_MakePoint(x=lng, y=lat), 4326)::geography`
- [x] 6.4 Repository lives in `:infra:supabase/infra/repo/`. The INSERT is not a `FROM posts` read so the Detekt rule allows it. Verification landed in section 11 tests

## 7. ContentLengthGuard middleware

- [x] 7.1 `ContentLengthGuard.kt` carries the `Map<String, Int>` registry + `enforce(key, raw): String` that NFKC-normalizes, trims, and throws `ContentEmptyException` / `ContentTooLongException`. Not a full Ktor plugin — instantiated as a plain service and injected via Koin (simpler than a plugin for a pure pre-handler check; startup log line confirms install per the backend-bootstrap spec scenario)
- [x] 7.2 `installContentLengthGuard()` extension function seeds `("post.content" → 280)`; called from `Application.module()` (landed with task 8.5 Koin wiring)
- [x] 7.3 StatusPages handlers added in `Application.module()` map `ContentEmptyException` → 400 `content_empty` and `ContentTooLongException` → 400 `content_too_long` (landed with task 8.5)
- [x] 7.4 `ContentLengthGuardTest` — 9/9 passing: happy 20 chars, exactly-280, 281 rejected, empty / null / whitespace-only rejected, NFKC fold of fullwidth digits, emoji code-point vs Java-char semantics, 281 emoji rejected, unknown key is programmer error

## 8. SignupService analog: CreatePostService + route

- [x] 8.1 `CreatePostService.kt` orchestrates length guard → coord envelope → `UuidV7.next()` → `JitterEngine.offsetByBearing` → single-INSERT tx via `PostRepository.create`
- [x] 8.2 Envelope constants `LAT_MIN = -11.0, LAT_MAX = 6.5, LNG_MIN = 94.0, LNG_MAX = 142.0` live in `CreatePostService.Companion` with a docs/08 Phase 1 item 21 citation
- [x] 8.3 `PostRoutes.kt` — `POST /api/v1/posts` inside `authenticate(AUTH_PROVIDER_USER) { ... }`; receives `CreatePostRequestDto`; returns 201 built as a `JsonObject` with `distance_m: null` explicitly present (the app-wide `explicitNulls = false` would otherwise drop it)
- [x] 8.4 `PostRoutes.kt` + `CreatePostService.kt` contain zero `FROM posts` strings (only the INSERT exists, and it lives in `:infra:supabase`'s `JdbcPostRepository`). Detekt rule confirmation lands in section 11
- [x] 8.5 `Application.module()` Koin wiring: resolves `jitter-secret` via `SecretResolver` with a fail-fast `require(size == 32)`; installs the content-length guard; builds `JdbcPostRepository` + `CreatePostService`; registers all three Koin singletons; calls `postRoutes(createPostService)`. StatusPages handlers for `ContentEmptyException`, `ContentTooLongException`, `LocationOutOfBoundsException` also added (task 1.5 + 7.3 land here)
- [x] 8.6 Route is outside any rate-limit plugin (no Redis in scope); response envelope matches existing auth 4xx shape

## 9. CreatePostService integration tests

- [x] 9.1 `CreatePostServiceTest` (`@Tags("database")`) stood up with `testApplication` + `JwtIssuer` issuing real JWTs against a disposable user row — mirrors the `SignupFlowTest` harness style
- [x] 9.2 Happy path: 201 + asserts `ST_Distance(actual_location, display_location)` (geography units = meters) ∈ [50, 500] m AND `ST_AsText(actual) != ST_AsText(display)`
- [x] 9.3 Length: 280 ok, 281 → 400 `content_too_long`, empty → 400 `content_empty`, whitespace-only → 400 `content_empty`
- [x] 9.4 Coord bounds: Jakarta ok, New York → 400 `location_out_of_bounds`
- [x] 9.5 Auth: missing JWT → 401; stale `token_version` (bump DB version then reuse the old token) → 401
- [x] 9.6 FK RESTRICT: direct `DELETE FROM users` fails with SQLSTATE `23503` while a post exists; clearing posts first lets the delete succeed (returns 1 row)
- [x] 9.7 Response shape: exact six keys `{id, content, latitude, longitude, distance_m, created_at}` and `distance_m` serialized as JSON null
- [x] CreatePostServiceTest summary: 7/7 passing against the live dev Postgres under `-Dkotest.tags=database`

## 10. visible_posts view behavior test

- [x] 10.1 `VisiblePostsViewTest` (`@Tags("database")`) — INSERT with `is_auto_hidden = TRUE` → `visible_posts` count 0; UPDATE flip to FALSE → count 1
- [x] 10.2 Second scenario: direct INSERT with default `is_auto_hidden` (FALSE) → immediately visible via `visible_posts`. Both scenarios green (2/2)

## 11. Detekt RawFromPostsRule

- [x] 11.1 Created new module `:lint:detekt-rules` (top-level, not nested under `build-logic` because `build-logic` is an included build reserved for Gradle precompiled script plugins; a Detekt ruleset is a runtime artifact). Registered in `settings.gradle.kts`
- [x] 11.2 Implemented `RawFromPostsRule` extending Detekt's `Rule`. Visits `KtStringTemplateExpression` nodes (Kotlin string literals, including multi-line concatenations rendered as separate expressions) and applies the case-insensitive regex `\b(?:FROM|JOIN)\s+posts\b`. SQL file scanning was not implemented in the Detekt rule itself — Detekt's PSI visitor is Kotlin-only; per docs/08 the grep-level posture is acceptable for MVP. If an operator needs SQL enforcement, a separate Gradle task can wrap a grep (added to Deferred as 13.11)
- [x] 11.3 Allowed-path suppression: `/app/admin/` substring match, `/app/post/repository/PostOwnContent*` filename match, and `@AllowRawPostsRead` annotation on any enclosing `KtAnnotated` declaration (function / class / property / file). Rule walks `getParentOfType` chain to check enclosers
- [x] 11.4 Defined `@AllowRawPostsRead(reason: String)` at `backend/ktor/src/main/kotlin/id/nearyou/app/lint/AllowRawPostsRead.kt` with SOURCE retention; the rule matches by short name only, so any package-location is OK as long as the short identifier is `AllowRawPostsRead`
- [x] 11.5 Wired via the `nearyou.ktor` convention plugin: applies `io.gitlab.arturbosch.detekt` plugin, disables default rule sets, points `config.setFrom(files("config/detekt/detekt.yml"))` to the per-module config, restricts `source` to `src/main/kotlin` (tests read raw `posts` for DB assertions; the rule protects business code, not test scaffolding). `tasks.named("check") { dependsOn("detekt") }` wires it into the standard build
- [x] 11.6 Installed Detekt 1.23.8: version pins in `gradle/libs.versions.toml`; `io.gitlab.arturbosch.detekt:detekt-gradle-plugin` added to `build-logic` so the convention plugin can apply it; detekt task runs alongside `check`
- [x] 11.7 `RawFromPostsRuleTest` — 8/8 passing: `FROM posts`, `JOIN posts`, case-insensitive lower-case `from posts`, multi-line concatenation, annotation on function suppresses, annotation on class suppresses, harmless "posts" in prose doesn't fire, word-boundary prevents `postscategory` false positive
- [x] 11.8 Full-suite `./gradlew :backend:ktor:detekt` — green on existing main sources. Manual smoke test: adding a `FROM posts` literal to a scratch file under `src/main/kotlin/id/nearyou/app/` makes the task fail with `RawFromPostsRule` as the cited source; removing the file brings it green again

## 12. End-to-end verification

- [x] 12.1 `./gradlew clean ktlintCheck detekt build test -Dkotest.tags='!network'` — green (121 tests total across 8 suites + the lint-rules module). `-Dkotest.tags='!network'` includes database-tagged tests against the live dev compose Postgres
- [x] 12.2 CI tag-exclusion remains `-Dkotest.tags='!network,!database'` (unchanged from auth-foundation)
- [x] 12.3 Covered by `CreatePostServiceTest` happy path: live HTTP POST to `/api/v1/posts` via `testApplication`, round-trips into dev Postgres, inspects both `actual_location` and `display_location` via PostGIS `ST_Distance` / `ST_AsText`. A pure-`curl` dev-compose end-to-end requires the same OAuth prerequisites as signup (noted as a conscious deferral; parallels signup-flow task 8.2-8.3)
- [x] 12.4 Manual `psql` check: seeded 5 posts around Jakarta via direct SQL (fixed geometric offsets, NOT through `JitterEngine`) and confirmed `ST_Distance(actual_location, display_location)` works as expected. Real jitter-path bounds verification lives in `JitterEngineTest` (1000 seeds, all ∈ [50, 500] m) and `CreatePostServiceTest` happy path (end-to-end through the live endpoint)
- [x] 12.5 RESTRICT psql verification: direct `DELETE FROM users` while a post exists → `foreign_key_violation` (as expected). After clearing posts, the user delete succeeds. Logged via `RAISE NOTICE`
- [x] 12.6 Updated `dev/README.md` with: new post-creation endpoint note, `JITTER_SECRET` commentary on the keypair script output, `processResources` prerequisite for `flywayMigrate`, Detekt config location + rule authoring guide, expanded database-tagged test list
- [ ] 12.7 Stage and commit in a single commit titled `feat(posts): creation + PostGIS dual-column + visible_posts view + Detekt raw-from-posts rule`

## 13. Deferred — tracked but explicitly out of scope

- [ ] 13.1 (DEFERRED) Timeline endpoints (Nearby / Following / Global) — next change
- [ ] 13.2 (DEFERRED) Post editing + `post_edits` schema + 30-minute window — Phase 4 Premium
- [ ] 13.3 (DEFERRED) Post soft-delete + hard-delete + tombstone worker — separate change
- [ ] 13.4 (DEFERRED) `user_blocks` + block-exclusion join in `visible_posts` — Phase 1 item 16 change
- [ ] 13.5 (DEFERRED) Rate limiting on post creation — Phase 1 item 24 (Redis change)
- [ ] 13.6 (DEFERRED) Attestation on post creation — attestation-integration change
- [ ] 13.7 (DEFERRED) FTS (`content_tsv` + GIN pg_trgm) — Phase 2 Search change
- [ ] 13.8 (DEFERRED) `admin_regions` polygon coord check — Pre-Phase 1 asset + admin-regions change
- [ ] 13.9 (DEFERRED) `:shared:distance` `jvmMain` / `nativeMain` `actual` implementations for HMAC (iOS Security.framework) — mobile change
- [ ] 13.10 (DEFERRED) Content-length CI lint rule "detect endpoints without length guard" — cross-cutting middleware-enforcement change
- [ ] 13.11 (DEFERRED) SQL-file scanning for `FROM posts` outside V4 — Detekt's PSI visitor is Kotlin-only so a Gradle grep task is the cleanest addition; docs/08 says grep-level is acceptable for MVP. The spec scenario "SQL file under db/migration/ other than V4" is marked deferred until a .sql-scanning task lands
