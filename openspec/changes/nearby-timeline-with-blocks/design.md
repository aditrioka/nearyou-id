## Context

Post-creation-geo (V4) shipped the write side: `posts` rows carry `display_location` and `actual_location` (PostGIS `GEOGRAPHY(POINT, 4326)`), the `visible_posts` view filters `is_auto_hidden = FALSE`, and the `RawFromPostsRule` Detekt rule keeps business code on the view. There is still no read endpoint. The canonical Nearby query in `docs/05-Implementation.md` § Timeline Implementation (≈ lines 1037–1090) is:

```sql
SELECT id, author_id, content,
       ST_Y(display_location::geometry) AS lat,
       ST_X(display_location::geometry) AS lng,
       ST_Distance(display_location, ST_MakePoint(:lng, :lat)::geography) AS distance_m,
       created_at
FROM   visible_posts
WHERE  ST_DWithin(display_location, ST_MakePoint(:lng, :lat)::geography, :radius_m)
  AND  author_id NOT IN (SELECT blocked_id FROM user_blocks WHERE blocker_id = :viewer)
  AND  author_id NOT IN (SELECT blocker_id FROM user_blocks WHERE blocked_id = :viewer)
  AND  (created_at, id) < (:cursor_created_at, :cursor_id)   -- keyset
ORDER BY created_at DESC, id DESC
LIMIT  :page_size + 1;
```

The query embeds `user_blocks` directly. So the first read endpoint cannot ship without the blocks schema. And shipping blocks alone leaves them unprovable (no read path consumes them). Both halves also need the lint rule that enforces the bidirectional join, because that lint must be in place from commit one or every subsequent business query becomes a footgun.

The `auth-jwt` capability already validates Bearer tokens and exposes the calling user UUID via the existing `principal()` helper. PostGIS, Flyway, and the per-page cap pattern (used by signup paginated lookups) are wired. `:shared:distance` exists with `commonMain` filled by post-creation-geo, but `jvmMain` is empty — server-side `distance_m` computation needs a host implementation that matches PostGIS's `ST_Distance` over WGS84 geography.

## Goals / Non-Goals

**Goals:**
- Ship a single endpoint, `GET /api/v1/timeline/nearby`, that returns viewer-relative posts using the canonical query verbatim.
- Ship the user-blocking HTTP contract (`POST/DELETE/GET /api/v1/blocks`) and the V5 `user_blocks` schema in the same change.
- Ship the Detekt `BlockExclusionJoinRule` so every business query touching protected tables either joins `user_blocks` bidirectionally, sits in an allowlisted location, or carries an audit annotation — from the very first commit that introduces the lint.
- Fill `:shared:distance/jvmMain` with a PostGIS-compatible haversine; make `:shared:distance` the single source of distance math across backend and mobile.
- Keep the in-request per-page cap simple (30) and defer Redis-backed enforcement.

**Non-Goals:**
- Following timeline (waits on a `follows` table — next change).
- Global timeline (waits on `admin_regions` polygon reverse-geocoding).
- Redis-backed soft/hard read caps (item 24, separate change).
- Attestation gate on read endpoints.
- Mandatory CTE-batching refactor of the Nearby query (Phase 2 perf optimization).
- Mobile `DistanceRenderer` UI wiring — server returns raw meters; mobile owns formatting.
- Follow-cascade on block (lands with the follow system).
- Admin-triggered unblock; admin block-list views.
- `content_tsv` / FTS indexes.

## Decisions

### Decision 1: One vertical change, not three

Splitting into (timeline) + (blocks) + (lint) leaves any first-shipped slice unprovable or footgun-prone. The canonical query references both other halves; the lint must precede every business query. A single change keeps the vertical honest: the first commit lands the lint, the second the schema + blocks endpoints, the third the timeline endpoint that cannot type-check without both.

**Alternative considered:** Ship `user_blocks` schema first as a no-endpoint change. Rejected — the schema is unmotivated without a consumer, and adding it without the lint sets a precedent that future query authors won't see.

### Decision 2: Keyset cursor on `(created_at DESC, id DESC)` — not OFFSET

Required by `posts_timeline_cursor_idx` (the V4 partial GIST index) and by docs/05 § Timeline. Cursor is the base64-url-encoded JSON `{"c":"<created_at ISO>","i":"<post UUID>"}`. Server validates the JSON shape and rejects malformed cursors with `400 invalid_cursor`. No HMAC over the cursor — the data is already public to the viewer (a row they could see on the previous page). Forward-only paging; no `prev` cursor.

**Alternative considered:** Server-signed opaque cursor. Rejected — adds key rotation burden for no security gain on already-visible IDs.

### Decision 3: `distance_m` computed in SQL, returned to client

`ST_Distance(display_location, ST_MakePoint(lng, lat)::geography)` runs as part of the SELECT — the index supports it, the value is needed in the response, and the alternative (compute in Kotlin from returned lat/lng) duplicates work and risks formula drift between SQL and Kotlin. The new `:shared:distance/jvmMain` haversine exists for *secondary* uses (e.g., service-layer sanity assertions in tests, future computations not driven by a SQL query). The endpoint's response value comes straight from PostGIS.

**Alternative considered:** Return only lat/lng and let the client compute distance. Rejected — every consumer needs the value, and clients shouldn't reimplement geography math.

### Decision 4: Bidirectional block exclusion via two NOT-IN subqueries — verbatim from docs §1045

Two subqueries instead of one OR'd UNION because PostgreSQL's planner inlines NOT-IN against the small `user_blocks` index more cleanly. We will NOT introduce a `user_blocks_bidirectional` view or helper function — keeping the join shape literal makes the lint rule trivial (string-match for `user_blocks WHERE blocker_id =` and `WHERE blocked_id =`) and keeps queries inspectable in logs.

**Alternative considered:** A SQL view `viewer_visible_posts(viewer_id)` that pre-joins both NOT-INs. Rejected for two reasons: (a) lint becomes harder (must whitelist the view, then we're back to the same risk for queries that don't use it); (b) view invalidation on user_blocks changes is a planner footgun. Direct subqueries are explicit, lintable, and obvious.

### Decision 5: Self-block — app-layer reject as primary, DB CHECK as defense-in-depth

`POST /api/v1/blocks/{user_id}` returns `400 cannot_block_self` if the path UUID equals the calling user's UUID, before any DB write. The DB CHECK constraint `CHECK (blocker_id <> blocked_id)` exists as a backstop only — if a future code path bypasses the route (e.g., admin tooling, a job), the DB still rejects. This mirrors the signup age-gate pattern (`auth-signup` + `users-schema` 18+ CHECK).

### Decision 6: Idempotent block / unblock via 204

- `POST /api/v1/blocks/{X}`: if the (caller, X) pair already exists, return 204 (no body, no error). Internally implemented as `INSERT ... ON CONFLICT (blocker_id, blocked_id) DO NOTHING` then 204. Avoids leaking pre-existing block state via response code differences.
- `DELETE /api/v1/blocks/{X}`: if the pair does not exist, also return 204. The endpoint is "ensure no block exists from caller to X" — a state assertion, not an event.
- `GET /api/v1/blocks?cursor=`: paginated list of `{ user_id, created_at }` for the caller's outbound blocks. Same keyset cursor format as Nearby, but ordered `(created_at DESC, blocked_id DESC)`.

**Alternative considered:** Return 409 on duplicate block, 404 on missing unblock. Rejected — block state is a private set, and HTTP-level distinguishing is information leak (could be used to probe whether you've already been blocked by someone else's tool). 204 in all cases.

### Decision 7: Detekt `BlockExclusionJoinRule` — string-match rule with four allowlists

The rule scans `.kt` string literals and `.sql` files under `db/migration/` (excluding V5, where `user_blocks` is created). Trigger condition: literal contains a case-insensitive token from `{posts, visible_posts, users, chat_messages, post_replies}` AS a `FROM` or `JOIN` target. Pass condition: literal also contains both `user_blocks` AND a `blocker_id =` token AND a `blocked_id =` token. Allowlists:

1. File under `backend/ktor/src/main/kotlin/id/nearyou/app/admin/` — admin code is allowed to query without block exclusion (admins need to see everything).
2. Repository own-content file — filename starts with one of `{PostOwnContent, UserOwn, ChatOwn, ReplyOwn}` AND query is scoped by `author_user_id = ?` / `user_id = ?` / `sender_user_id = ?` matching the calling user. (For the lint, file-name match suffices; semantic enforcement is the file author's job.)
3. Annotation `@AllowMissingBlockJoin("<reason>")` on the enclosing function/class — declared in `:backend:ktor`, requires non-empty reason.
4. The `V5__user_blocks.sql` migration itself (where `user_blocks` is defined).

Note: This rule is *additive* to `RawFromPostsRule` from post-creation-geo. A query that touches `posts` directly trips both rules; one that touches `visible_posts` only trips this one. The two rules together encode the full read-side contract.

**Alternative considered:** A single fused rule. Rejected — `RawFromPostsRule` enforces a different invariant ("don't bypass the auto-hidden filter") with a different allowlist set. Composing them keeps each rule single-purpose.

### Decision 8: `:shared:distance/jvmMain` — haversine, not Vincenty

Haversine on a sphere of radius 6371008.8 m matches PostGIS's `ST_Distance(::geography, ::geography)` to within ~0.5% over the relevant distance band (50m–50km), which is well below the 1km rounding floor in `DistanceRenderer`. Vincenty is more accurate but unnecessary for our use case and adds iterative complexity. The implementation lives in `shared/distance/src/jvmMain/kotlin/.../HaversineDistance.kt` and is exposed as `Distance.metersBetween(a: LatLng, b: LatLng): Double`.

**Alternative considered:** JTS Geodesic. Rejected — extra dependency for a 30-line function.

### Decision 9: Per-page cap = 30, hard caps deferred

The Nearby endpoint LIMITs to `pageSize + 1` (the `+1` is the standard "is there a next page" probe; the extra row is dropped before serialization). `pageSize` is fixed at 30 for now; the query string accepts no `page_size` parameter to keep the cap server-controlled. Per-user/per-IP rate limits (item 24) defer to the Redis change.

### Decision 10: Radius bounds and envelope reuse

- `radius_m`: required, validated to `[100, 50000]` (100m floor matches the JitterEngine 50–500m offset band; 50km ceiling matches docs §15 and prevents nation-wide scans). Out-of-range → `400 radius_out_of_bounds`.
- `lat`/`lng`: required; reuse the post-creation envelope `[-11.0, 6.5]` × `[94.0, 142.0]` and the same error code `location_out_of_bounds`. The envelope is an Indonesia-only product constraint shared across read and write.

## Risks / Trade-offs

- **Risk**: Detekt rule false negatives — author writes a query in a non-string-literal form (e.g., builder DSL, multi-fragment string interpolation that splits the table name). → **Mitigation**: rule matches across multi-line string concatenation (same pattern as `RawFromPostsRule`); we rely on convention that all queries are written as Kotlin string literals or `.sql` files. If a future ORM/DSL is introduced, that change must extend the lint.
- **Risk**: Detekt rule false positives — log strings or test fixtures mention `posts` and `user_blocks` separately. → **Mitigation**: rule requires a `FROM` or `JOIN` token preceding the table name to flag, not bare mentions. Test fixtures sit under `src/test/`, which Detekt config can scope-exclude.
- **Risk**: Bidirectional NOT-IN performance at scale — `user_blocks` index exists on both directions, but PostgreSQL planner choice depends on cardinality. → **Mitigation**: V5 includes both directional indexes (`(blocker_id, blocked_id)` and `(blocked_id, blocker_id)`); CTE batching defers to Phase 2 only after we have real load data.
- **Risk**: Self-block bypass via the DB layer — if `CHECK` is omitted, the app-layer check could be bypassed by direct DB write. → **Mitigation**: DB CHECK is in V5 explicitly; `MigrationV5SmokeTest` asserts it exists.
- **Risk**: Cursor opaque format change becomes hard to evolve. → **Mitigation**: cursor is JSON, version-implicit. If a v2 format is needed, `400 invalid_cursor` is the user-visible failure mode; mobile already retries from `next_cursor=null` on parse failure (existing pattern).
- **Risk**: Idempotent unblock returning 204 when no block existed could mask mobile bugs (unblock called for a user who was never blocked). → **Mitigation**: server logs the no-op at INFO level; mobile testing is responsible for catching the wrong call site.
- **Risk**: `user_blocks` cascade on user hard-delete leaves orphan references in future tables that should also cascade (e.g., follows). → **Mitigation**: V5 documents in a SQL comment that future cascade tables (follows, mutes, reports) MUST add their own block-on-cascade behavior. Not enforced in V5.

## Migration Plan

1. **Pre-deploy**: lint rule lands first (via Detekt). Existing post-creation queries already pass (they query `posts` via `RawFromPostsRule` allowlist; the new rule's allowlist mirrors them for own-content paths). Run `./gradlew detekt` locally before merge; CI catches any regression.
2. **Deploy migration**: V5 is additive, no `ALTER` on existing tables. Safe to run on a live DB (Flyway transactional). No downtime.
3. **Deploy code**: timeline + blocks endpoints. `/api/v1/timeline/nearby` returns 401 without auth (existing JWT plugin); `/api/v1/blocks/*` likewise.
4. **Rollback**: revert the deploy. V5 can stay (additive). If V5 must be reverted, manual `DROP TABLE user_blocks` in dev/staging — production never rolls back a Flyway migration; we'd ship a forward V5b that recreates the prior shape (no production rollback path is the existing project policy).

## Open Questions

- Should `GET /api/v1/blocks` also return `username` for each blocked user, or just `user_id`? Current decision: just `user_id` + `created_at`, matching the minimum needed for the upcoming "blocked users" mobile screen. If the mobile design requires usernames inline, add a JOIN on `users.username` in a follow-up — out of scope for this change.
- Should the lint allowlist mechanism support a project-wide config file (e.g., `block-exclusion-allowlist.yml`) instead of file-naming + annotations? Current decision: no — keep the allowlist surface narrow to discourage drift; annotations are auditable in code review.
