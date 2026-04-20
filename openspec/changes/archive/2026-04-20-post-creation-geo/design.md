## Context

Auth-foundation (V2) and signup-flow (V3) landed the ability for a real authenticated `users` row to exist. Every Phase 1 item from 13 onward assumes posts are writable, and almost every Phase 2 feature (timeline, reply, report, chat embed, moderation auto-hide) assumes two invariants that must be established together:

1. `posts` carries both `actual_location` (admin-only) and `display_location` (fuzzed, non-admin), written atomically at INSERT time via a server-held `JITTER_SECRET`.
2. All non-admin reads go through `visible_posts`, a view that filters `is_auto_hidden = FALSE`.

If either invariant slips in later, every consumer has to be re-audited. The rule `docs/08-Roadmap-Risk.md` § Development Tools ("Detect raw `FROM posts` in the business module") is load-bearing — it exists precisely to keep this gate sealed across a codebase that AI assistants will help grow quickly. The Detekt rule must land **with** the view, not after.

Constraints:
- Dev Postgres is `postgis/postgis:16-3.4` (already present from auth-foundation). Staging/prod are not in scope.
- Previous changes established `SecretResolver` + `secretKey(env, name)` helper. Hyphenated secret names normalize to `UPPER_UNDERSCORE` env vars; `InviteCodeSecretResolverTest` is the reference pattern.
- Modular monolith rule: vendor SDKs live only in `:infra:*`. Jitter math is vendor-neutral, so `:shared:distance` is the correct site.
- Kotest is the test framework; `database` tag gates integration tests against the dev container; CI currently excludes `network` and `database` tags.
- Ktor `Authentication` plugin (RS256 middleware) is already installed; `call.principal<UserPrincipal>()` works on any authenticated route.

Stakeholders: the backend vertical, the future timeline change (first consumer of `visible_posts`), the future mobile change (first real consumer of `DistanceRenderer` outside tests), and the future moderation change (first writer of `is_auto_hidden = TRUE`).

## Goals / Non-Goals

**Goals:**
- A single authenticated backend endpoint that creates a post with both geographies populated in one transaction.
- Deterministic, non-reversible coordinate jitter shared between backend and (future) KMP mobile via `:shared:distance`.
- Canonical `renderDistance` implementation with the `docs/05` test matrix passing.
- `visible_posts` view in place from V4 onward, with a Detekt rule that makes bypassing it a CI failure.
- V4 migration that carries every index future changes will need (no ALTER chains).
- A content-length middleware registry that the next endpoint (reply) can register a limit against without touching the middleware itself.

**Non-Goals:**
- Timeline reads (Nearby / Following / Global) — separate change.
- Post editing / soft-delete / hard-delete / tombstone — separate changes.
- Block feature and the block-exclusion join layered into `visible_posts` — separate change; view carries only the auto-hide filter for now.
- Rate limiting and attestation gate on the post creation route.
- FTS / `content_tsv` / `pg_trgm` — Search change.
- `admin_regions` polygon check; the envelope is coarse Indonesia-bounding-box only.
- Staging/prod secret plumbing for `JITTER_SECRET`.
- Mobile consumption of `:shared:distance`; `jvmMain`/`nativeMain` source sets are empty stubs this change leaves for the mobile cut.

## Decisions

### Decision 1 — `:shared:distance` is a KMP module, not pure-JVM

**Choice**: Create `shared/distance/` as a Kotlin Multiplatform Gradle module with a `commonMain` source set. Backend depends on it the way it would any KMP library.

**Alternatives considered**:
- Plain JVM module and duplicate the jitter math in mobile later. Rejected: `docs/08` mandates the jitter-reversibility-impossibility unit test run cross-runtime with backend, and `:shared:distance` is named in `docs/08` Phase 1 item 14 as a KMP module.
- Put the math in `:core:domain`. Rejected: `:core:domain` is "pure Kotlin, zero vendor deps" (project.md) but also broad; keeping jitter + distance in a narrow module makes dependency boundaries obvious and keeps the CI surface small.

**Trade-off**: Minor Gradle complexity now (KMP plugin, two empty source sets). Saved work when the mobile change lands.

### Decision 2 — HMAC algorithm exactly per docs/05, no clamping

**Choice**: `hmac = HMAC-SHA256(JITTER_SECRET, post_id.bytes)`; `bearing_radians = uint32be(hmac[0..4]) / 2^32 * 2π`; `distance_meters = 50.0 + uint32be(hmac[4..8]) / 2^32 * 450.0`. Offset via forward geodesic (bearing + distance over WGS84 spheroid).

**Alternatives considered**:
- Clamp `distance_meters` into a tighter band (e.g., 100–400m) to reduce precision loss. Rejected: `docs/08` risk register locks "~500m" precision loss as an acceptable trade-off. Diverging from the documented formula silently would break any later rotation/re-fuzz script that assumes the same math.
- Use the PostGIS `ST_Project` function server-side. Rejected: we want the math to be the same byte-exact operation on backend + mobile; writing it in Kotlin keeps the contract one implementation.

**Trade-off**: Pure-Kotlin geodesic math is more code than `ST_Project`. We mitigate by cross-checking against PostGIS in the integration test (run on dev Postgres) — the integration test asserts `ST_Distance(actual, display)` lands in [50, 500]m for 1000 random seeds, proving the pure-Kotlin forward geodesic agrees with PostGIS within the band.

### Decision 3 — UUIDv7 for `post_id` generated in app layer

**Choice**: App layer generates the UUIDv7 before INSERT so it can feed the HMAC and set the column directly.

**Alternatives considered**:
- Let Postgres generate `id` via `DEFAULT gen_random_uuid()` and then compute `display_location` in a trigger. Rejected: the trigger would need the JITTER_SECRET at the DB layer, violating the "secret only in app process memory" posture. Also moves business logic into PL/pgSQL, away from the CI test harness.
- Let Postgres generate `id`, then `UPDATE` the row with the computed `display_location`. Rejected: creates an intermediate state where `display_location` is NULL — the NOT NULL constraint in the canonical schema rules this out, and even as a WITH-CTE round-trip it's extra round-trips for no gain.

**Trade-off**: App layer needs a reliable UUIDv7 source. Kotlin doesn't have UUIDv7 in the stdlib; we'll add a tiny utility in `:shared:distance` (the module with the cross-runtime footprint) or use `com.github.f4b6a3:uuid-creator` if already transitively available. Will decide at implementation; both are low-risk.

### Decision 4 — `visible_posts` is a SQL view, not a Kotlin-side filter

**Choice**: `CREATE VIEW visible_posts AS SELECT * FROM posts WHERE is_auto_hidden = FALSE`.

**Alternatives considered**:
- Apply the filter in the repository layer every time. Rejected: easy to forget; the whole point of the Detekt rule is to trust the DB-level gate. Views also let future joins (block-exclusion, shadow-ban-on-author) be added to the view definition in one place.
- Use RLS on the table. Rejected: RLS belongs on `realtime.messages` and other Supabase-originated paths, not on a Ktor-owned table the backend accesses via a trusted role. Would also interact awkwardly with the admin-only `actual_location` reads.

**Trade-off**: When we add the block-exclusion join (next change), we'll `CREATE OR REPLACE VIEW` with the new join. The view definition becomes more complex over time; we accept that as "the one place that gets more complex, instead of every call site."

### Decision 5 — Detekt custom rule over grep / CI script

**Choice**: Implement `RawFromPostsRule` in a `build-logic/detekt-rules/` module. Detekt already runs (or is trivial to add) in CI; a custom rule integrates with the existing build toolchain and ide-highlights in dev.

**Alternatives considered**:
- Shell grep step in CI. Rejected: no IDE feedback; harder to annotate exceptions (`@AllowRawPostsRead`).
- Full SQL parser (e.g., JSQLParser). Rejected: overkill; our JDBC is raw strings with `FROM posts` appearing verbatim. `docs/08` says "grep-level is enough for MVP."

**Rule scope**:
- Scan: `.kt` files (JDBC strings and identifiers) and `.sql` files under `backend/ktor/src/main/resources/db/`.
- Allowed: files under `backend/ktor/src/main/kotlin/id/nearyou/app/post/repository/PostOwnContent*.kt`; files under `backend/ktor/src/main/kotlin/id/nearyou/app/admin/`; any function/class annotated `@AllowRawPostsRead("reason")`; the V4 migration file that defines the view.
- Match pattern: case-insensitive `\bFROM\s+posts\b` and `\bJOIN\s+posts\b` in string literals, and the same patterns in `.sql` files.

**Trade-off**: Custom Detekt rules have their own small learning curve. Worth it for IDE feedback + annotation-based exceptions.

### Decision 6 — Content-length middleware as a per-route limits registry

**Choice**: `ContentLengthGuard` is a Ktor plugin that reads a registered `Map<Route, Int>`. This change registers `post.content → 280`; future changes add `reply.content → 280`, `chat.content → 2000`, `bio → 160`, etc.

**Alternatives considered**:
- Put a `require(content.length in 1..280)` at the top of the handler. Rejected: `docs/08` lint rule "Detect content input endpoints without a length guard" wants a central enforcement point to lint against.
- Annotation-driven (`@MaxLength(280)` on the DTO). Rejected: Ktor/kotlinx.serialization doesn't have native support; would need a kotlinx-serialization plugin; reduces velocity for MVP.

**NFKC + trim step**: always applied before length check. Empty-after-trim → 400 `content_empty`; > 280 code points (NOT bytes) → 400 `content_too_long`. The canonical "length" unit per `docs/05` is Unicode code points.

**Trade-off**: The registry pattern has a small risk of a new route being added without a registered limit. The lint rule "Detect content input endpoints without a length guard" exists to catch that, but is not in scope for this change — note the debt.

### Decision 7 — Coord envelope is bounding box, not polygon

**Choice**: `-11.0 <= lat <= 6.5`, `94.0 <= lng <= 142.0`. Rejects posts from outside the Indonesia + 12-mile maritime bounding box. Admin-region polygon precision is deferred.

**Alternatives considered**:
- Use `admin_regions` GeoJSON + PostGIS polygon containment. Rejected: `admin_regions` bootstrap is a Pre-Phase 1 asset task; not landing in this change.
- No bounds check at all. Rejected: posts outside Indonesia would break the density-metric dashboards and the Nearby-timeline expectations; also trivially abusable with synthetic clients.

**Trade-off**: A user in, say, Kupang, might submit coordinates that round to `-10.99` — still in-envelope, fine. A post legitimately cast from a ferry in international waters 100km off Aceh might round out-of-envelope; acceptable for MVP.

### Decision 8 — `:shared:distance` depends on nothing

**Choice**: Module declares no dependencies beyond the Kotlin stdlib. HMAC via `javax.crypto.Mac` on JVM; when the mobile source sets get filled, `expect/actual` will provide iOS Security.framework equivalents.

**Alternatives considered**:
- Use Ktor's crypto utilities. Rejected: drags a Ktor client dependency into a shared module; violates the "narrow module, narrow deps" principle.

**Trade-off**: Minor duplication when the mobile change lands (expect/actual for HMAC). Worth the clean module boundary.

## Risks / Trade-offs

- **Risk**: Pure-Kotlin forward-geodesic math diverges from PostGIS by > 1m over the 500m band → the "within 50–500m" assertion flaps on edge cases.
  → **Mitigation**: integration test runs 1000 random seeds; if any land outside [50, 500], the test fails fast at PR time. We also pin the test seed to a fixed `SplittableRandom(0xC0FFEE)` so the dataset is reproducible.

- **Risk**: Detekt custom-rule misses a raw `FROM posts` query because the rule is regex-based.
  → **Mitigation**: grep-level is documented as acceptable in `docs/08`. Pair the rule with a code-review checklist note in `dev/README.md`. If the rule ever misses something, the fix is one regex tweak; no schema change needed.

- **Risk**: UUIDv7 dependency pulls in a non-trivial library.
  → **Mitigation**: keep it local — a ~30-line `UuidV7.next()` implementation in `:shared:distance` is trivial (monotonic 48-bit ms timestamp + 74 bits randomness + version + variant bits). Covered by a deterministic-clock unit test.

- **Risk**: Coord envelope rejects legitimate users near edges (e.g., Rote Island, Miangas).
  → **Mitigation**: envelope was picked to include a 12-mile maritime buffer; Miangas at 5.56°N, 126.57°E is inside. If a real user reports rejection, widen the envelope — it's a constant in code, no schema touch.

- **Risk**: The `posts` table lands without `deleted_at` / soft-delete column, complicating the delete change later.
  → **Mitigation**: consulted `docs/05` § Posts Schema — the canonical schema does NOT include `deleted_at`; deletion is cascade + tombstone. This change matches the canonical schema verbatim. The delete change will not need an ALTER to add columns; it adds the deletion-worker logic instead.

- **Risk**: Adding `posts_actual_location_idx` before any admin code exists means unused index overhead.
  → **Mitigation**: `docs/05` § Posts Schema lists the index; moderation and CSAM admin paths will use it. Cost of an unused GIST index on dev is trivial; landing it now avoids an ALTER later.

- **Risk**: CI content-length lint rule is not in this change, so a future endpoint could bypass the middleware.
  → **Mitigation**: explicit debt note in proposal's "out of scope"; the lint rule belongs to a cross-cutting middleware-enforcement change.

## Migration Plan

1. **Dev path**: developer pulls, regenerates `JITTER_SECRET` via the updated `dev/scripts/generate-rsa-keypair.sh`, runs `./gradlew :backend:ktor:flywayMigrate` (Flyway picks up V4), restarts the backend. No destructive steps — V4 is additive.
2. **Flyway rollback**: if V4 fails mid-migration, `flyway_schema_history` records a failed row. Manual fix: `DROP VIEW IF EXISTS visible_posts; DROP TABLE IF EXISTS posts CASCADE; DELETE FROM flyway_schema_history WHERE version = '4';` then re-run.
3. **Secret rotation scope**: `JITTER_SECRET` rotation is explicitly deferred (risk register Decision 20). If rotated, every existing row's `display_location` must be recomputed via a Cloud Run Jobs batch. Not in scope here; no prod rows to migrate yet.
4. **CI**: Detekt config adds the new rule. Existing tasks (`ktlintCheck`, `build`, `test`) all stay green. `-Dkotest.tags='!network,!database'` remains the CI default; the new `CreatePostServiceTest`, `MigrationV4SmokeTest`, `VisiblePostsViewTest` all carry the `database` tag.

## Open Questions

- **UUIDv7 source**: inline implementation in `:shared:distance` vs. `com.github.f4b6a3:uuid-creator`. Defer to implementation phase; either is acceptable. Decision logged in `tasks.md` after the first implementation attempt.
- **Detekt rule test harness**: write the rule test as a Detekt `TestRuleContext` spec or as a Kotest spec driving a real Detekt invocation. Defer — depends on what's already wired in `build-logic/`. If Detekt isn't wired yet, this change also sets up the minimum Detekt task + baseline.
