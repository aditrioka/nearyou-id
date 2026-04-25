# coordinate-jitter Specification

## Purpose
TBD - created by archiving change post-creation-geo. Update Purpose after archive.
## Requirements
### Requirement: JitterEngine lives in :shared:distance

A Kotlin Multiplatform Gradle module `:shared:distance` SHALL exist with a `commonMain` source set containing `JitterEngine.offsetByBearing(actualLatLng: LatLng, postId: Uuid, secret: ByteArray): LatLng`. The module MUST NOT depend on vendor SDKs (Ktor, Ktor client, Koin, Supabase). Backend code importing `JitterEngine` MUST depend on `:shared:distance` via Gradle rather than reimplementing the math.

#### Scenario: Module is KMP
- **WHEN** reading `shared/distance/build.gradle.kts`
- **THEN** the file applies the `kotlin("multiplatform")` plugin AND declares a `commonMain` source set

#### Scenario: Backend depends on :shared:distance
- **WHEN** reading `backend/ktor/build.gradle.kts`
- **THEN** `dependencies { ... }` contains an entry referencing `:shared:distance`

### Requirement: HMAC derivation per docs/05

`JitterEngine.offsetByBearing` SHALL derive `bearing_radians` and `distance_meters` from `hmac = HMAC-SHA256(secret, postId.toBytes())` exactly as:
- `bearing_radians = bigEndianUint32(hmac, 0) / 2^32 * 2 * PI`
- `distance_meters = 50.0 + bigEndianUint32(hmac, 4) / 2^32 * 450.0`

The output LatLng MUST be the forward-geodesic offset of the input LatLng by `(bearing_radians, distance_meters)` on the WGS84 spheroid.

#### Scenario: Distance within 50-500m band
- **WHEN** `JitterEngine.offsetByBearing(actual, postId, secret)` is called for any 1000 random `postId` values with a fixed `actual` and `secret`
- **THEN** the great-circle distance between the result and `actual` is in `[50.0, 500.0]` meters for every call

### Requirement: Determinism

The function SHALL be a pure deterministic computation. Repeated calls with the same `(actual, postId, secret)` MUST return byte-identical `LatLng` results.

#### Scenario: Same inputs same output
- **WHEN** `offsetByBearing` is called twice with the same `(actual, postId, secret)`
- **THEN** both calls return `LatLng` values whose latitude and longitude components are bit-for-bit identical

### Requirement: Secret sensitivity

Given the same `(actual, postId)` but two different `secret` values, the function SHALL produce two different `LatLng` results (i.e., the output's bits depend on the secret).

#### Scenario: Different secret different output
- **WHEN** `offsetByBearing(actual, postId, secretA)` and `offsetByBearing(actual, postId, secretB)` are evaluated with `secretA != secretB`
- **THEN** the two returned `LatLng` values are not equal

### Requirement: Non-reversibility without the secret

Without knowledge of `secret`, there SHALL be no feasible algorithm in this codebase that recovers `actual` from `(displayLocation, postId)`. The design documents the reliance on HMAC-SHA256's preimage-resistance and keeps `secret` out of all non-backend code.

#### Scenario: Secret not in client-facing paths
- **WHEN** searching `mobile/**`, `shared/**`, and non-admin backend repositories for `JITTER_SECRET` or the secret bytes
- **THEN** no hit is found outside `backend/ktor/.../post/` (the HMAC call site), `:shared:distance` test fixtures, and secret-resolution code

### Requirement: JITTER_SECRET resolution via SecretResolver

The backend SHALL resolve the HMAC key via `secretKey(env, "jitter-secret")` through the existing `SecretResolver` chain, consistent with `invite-code-secret`. In dev, the secret comes from the `JITTER_SECRET` environment variable (hyphen-to-underscore normalized). The secret MUST be 32 bytes (base64-decoded) to match the HMAC-SHA256 key size convention.

#### Scenario: Env var resolution
- **WHEN** `SecretResolver.resolve("jitter-secret")` is invoked with `KTOR_ENV=dev` and `JITTER_SECRET=<base64 32 bytes>`
- **THEN** the returned bytes decode to exactly 32 bytes

#### Scenario: Missing secret fails fast
- **WHEN** the server starts with `JITTER_SECRET` unset
- **THEN** server startup fails with an error message identifying the missing secret key

### Requirement: Onward audit dependency from Phase 2

No behavior of `JitterEngine` or `JITTER_SECRET` resolution changes in this capability. The `nearby-timeline` endpoint introduced by this change is the first read path that surfaces `display_location` to clients, and is therefore the first end-to-end path against which the Phase 2 audit (item 15 in `docs/08-Roadmap-Risk.md` — verifying that `actual_location` never leaks via any read response) MUST be run.

#### Scenario: actual_location absent from nearby response
- **WHEN** an end-to-end test issues `GET /api/v1/timeline/nearby` for a known viewer + known posts AND inspects the JSON response
- **THEN** no field name is `actual_location` AND no value matches the post's known `actual_location` lat/lng (only `display_location`-derived `latitude`, `longitude`, and `distance_m` appear)

#### Scenario: Phase 2 audit precondition documented
- **WHEN** reading `docs/08-Roadmap-Risk.md` Phase 2 item 15 after this change archives
- **THEN** the entry references `nearby-timeline` as the read path the audit is to be run against

### Requirement: CoordinateJitterRule fences `actual_location` reads in Kotlin source

The repo SHALL ship a custom Detekt rule `CoordinateJitterRule` under `lint/detekt-rules/src/main/kotlin/id/nearyou/lint/detekt/` that fires on any Kotlin string-literal (`KtStringTemplateExpression`) whose source text contains `\bactual_location\b` (case-insensitive), unless the containing file is allowlisted or the enclosing declaration is annotated `@AllowActualLocationRead("<reason>")`.

The rule MUST be registered in `NearYouRuleSetProvider` so that `./gradlew detekt` picks it up without additional plugin configuration.

#### Scenario: Non-allowed file with `FROM posts ... actual_location` fires
- **WHEN** a Kotlin file outside the admin module / post write-path / test tree contains a string literal like `"SELECT id FROM posts WHERE actual_location IS NOT NULL"`
- **THEN** `CoordinateJitterRule` reports a code smell on that literal

#### Scenario: Rule registered via NearYouRuleSetProvider
- **WHEN** reading `NearYouRuleSetProvider.instance(config)`
- **THEN** the returned `RuleSet` includes an instance of `CoordinateJitterRule`

### Requirement: Allowlist covers the three legitimate readers

The rule SHALL NOT fire in any of these allowed contexts:

1. **Admin module**: files whose `virtualFilePath` contains `/app/admin/` OR whose package is `id.nearyou.app.admin` (or a sub-package thereof). The admin module has full coordinate access by design.
2. **Test fixtures**: files whose `virtualFilePath` contains `/src/test/`. Tests seed data via raw SQL INSERT statements that legitimately reference `actual_location` as a column name.
3. **Post write-path repositories**: files whose basename starts with any of `JdbcPostRepository`, `CreatePostService`, `PostOwnContent`. These are the sanctioned INSERT-into-posts paths. Also allows the V9 `Report*` point-lookup existence-check files per the same pattern `RawFromPostsRule` uses for the moderation module.

All three allowlist gates MUST support the detekt-test `lint(String)` synthetic-file harness via package-FQN fallback (mirror the approach in `BlockExclusionJoinRule.isAllowedPath`).

#### Scenario: Admin-module file passes
- **WHEN** a file with package `id.nearyou.app.admin.tools` contains `"SELECT actual_location FROM posts"`
- **THEN** the rule does NOT fire

#### Scenario: Test file passes
- **WHEN** a file under `.../src/test/kotlin/.../*.kt` contains `"INSERT INTO posts (..., actual_location, ...)"`
- **THEN** the rule does NOT fire

#### Scenario: JdbcPostRepository passes
- **WHEN** a file named `JdbcPostRepository.kt` (any package) contains `"INSERT INTO posts (id, author_id, content, display_location, actual_location, ...)"`
- **THEN** the rule does NOT fire

#### Scenario: Synthetic non-allowed file fires
- **WHEN** a file with package `id.nearyou.app.timeline` (not admin, not post write-path) contains `"SELECT actual_location FROM posts"`
- **THEN** the rule fires once

### Requirement: `@AllowActualLocationRead` annotation bypasses the rule

Any Kotlin declaration (class, function, property) annotated `@AllowActualLocationRead("<reason>")` — including a containing ancestor declaration — SHALL exempt string literals inside that declaration from the rule. The annotation short-name check mirrors `@AllowRawPostsRead` / `@AllowMissingBlockJoin`.

The annotation class itself need not be defined in production code for the rule to work — the rule matches by short-name. Callers define the annotation at the use site or import it from any shared location.

#### Scenario: Annotation on function suppresses
- **WHEN** a function annotated `@AllowActualLocationRead("admin debug tool")` contains `"SELECT actual_location FROM posts"`
- **THEN** the rule does NOT fire on that literal

#### Scenario: Annotation on enclosing class suppresses
- **WHEN** a class annotated `@AllowActualLocationRead("admin geo audit")` contains a method with `"WHERE actual_location IS NULL"`
- **THEN** the rule does NOT fire on that literal

### Requirement: Detekt test coverage

`lint/detekt-rules/src/test/kotlin/id/nearyou/lint/detekt/CoordinateJitterLintTest.kt` SHALL cover, at minimum:
1. Positive-fail: `SELECT actual_location FROM posts` in a non-allowed file fires.
2. Positive-pass: admin-module package exempt.
3. Positive-pass: file under `/src/test/` exempt.
4. Positive-pass: `JdbcPostRepository`-prefixed file exempt (via `writeKtFile` helper — same pattern as the `PostOwnContent` fixture in `BlockExclusionJoinLintTest`).
5. Positive-pass: `@AllowActualLocationRead("reason")` on the function suppresses.
6. Positive-pass: annotation on the enclosing class suppresses.
7. Positive-fail (false-positive-by-design): the rule is a simple regex on `\bactual_location\b`, so a user-facing Kotlin string literal that mentions the column name in prose (e.g., `"The actual_location field is hidden from non-admin users"`) DOES fire. Test documents the tolerance + points at the `@AllowActualLocationRead` annotation as the escape hatch for legitimate non-SQL uses.
8. Positive-pass: Kotlin migration smoke test (test file) that INSERTs into `posts (actual_location)` does NOT fire.
9. Positive-fail: multi-line string concatenation spanning `actual_location` across two `+`-joined literals fires exactly once (leftmost literal reports, de-duplicated).
10. Positive-fail: INSERT-only fixture outside the write-path allowlist fires (the rule doesn't distinguish read/write; path allowlist is the only sanctioned escape).

#### Scenario: Test class exists and passes
- **WHEN** running `./gradlew :lint:detekt-rules:test`
- **THEN** `CoordinateJitterLintTest` is discovered AND every scenario above corresponds to at least one test case AND all cases pass

### Requirement: Detekt run against the backend codebase remains green

As of this change, `./gradlew detekt` SHALL pass on the backend + infra + lint modules. Every existing `actual_location` usage MUST fall within the allowlist (admin / test / post-write-path / annotation) — no net-new compliance work required. If a call site is discovered that falls outside the allowlist, the change is considered BLOCKED and the rule's allowlist or an `@AllowActualLocationRead` bypass MUST be added in the same PR.

#### Scenario: Detekt green post-merge
- **WHEN** running `./gradlew detekt` after this change merges
- **THEN** the command exits 0 with no `CoordinateJitterRule` findings

### Requirement: posts_set_city_tg trigger is a sanctioned DB-side reader of actual_location

The `posts_set_city_tg` BEFORE INSERT trigger (see `region-polygons` capability) reads `NEW.actual_location` to perform a polygon-containment reverse-geocode against `admin_regions`. This SHALL be recognized as a sanctioned use of `actual_location` alongside the pre-existing admin-module path, and the sanction MUST be scoped by the following invariants:

1. **DB-side only** — the read happens in PL/pgSQL inside the trigger function. No application code gains access to `actual_location` through this path.
2. **Write-path only** — the trigger fires on INSERT, not on SELECT. Read paths still MUST NOT touch `actual_location` in any non-admin code.
3. **No client-facing projection** — the trigger writes `city_name` (a string) and `city_match_type` (a short provenance tag: `'strict'` / `'buffered_10m'` / `'fuzzy_match'` / NULL). Neither value reveals coordinates. No `actual_location`-derived latitude, longitude, or geometry representation is stored outside `posts.actual_location` itself. `city_match_type` is admin/internal only and is never projected into client-facing responses.

Read paths that surface posts to clients (Nearby, Following, Global timelines, post-detail endpoints) MUST continue to derive displayed `latitude`/`longitude` from `display_location` (the fuzzed coordinate). The existence of the trigger MUST NOT be interpreted as a broadening of the read-path rule.

#### Scenario: Trigger body uses NEW.actual_location
- **WHEN** reading the function definition of `posts_set_city_fn` via `pg_get_functiondef`
- **THEN** the body contains `NEW.actual_location` AND does NOT contain `NEW.display_location`

#### Scenario: No application-side read of actual_location on the create path
- **WHEN** grepping `backend/ktor/src/main/kotlin/id/nearyou/app/post/` for `actual_location`
- **THEN** occurrences are confined to the write path (INSERT column list, repository SQL) and do NOT include any SELECT that projects `actual_location` into application memory on the create path

### Requirement: Reverse-geocode uses actual_location per product spec

The polygon-containment subquery executed by `posts_set_city_tg` MUST use `actual_location`, not `display_location`. This matches `docs/02-Product.md` § "Polygon-Based Reverse Geocoding" which states: *"Queries use `actual_location` (not `display_location`, since accuracy matters for administrative boundaries)."* The rationale is that jitter of up to 500 m routinely crosses kotamadya-scale boundaries in Jakarta; using `display_location` would mis-label ~15–20% of Jakarta posts.

The same rule applies transitively to the optional backfill job (see `region-polygons` capability): if the backfill is implemented, it MUST read `actual_location`, not `display_location`.

#### Scenario: Trigger uses actual_location
- **WHEN** reading the function body of `posts_set_city_fn`
- **THEN** the polygon-containment call is `ST_Contains(geom::geometry, NEW.actual_location::geometry)` (NOT `display_location`)

#### Scenario: Backfill uses actual_location (if implemented)
- **WHEN** the optional `BackfillPostCityJob` SQL or any equivalent SQL migration is authored
- **THEN** it references `p.actual_location` (NOT `p.display_location`) as the point argument to `ST_Contains`

