# 02 — Backend Timelines + Post + Engagement (performance lens)

Audited 2026-06-10 against `docs/11-Engineering-Standards.md` §3 and `docs/05-Implementation.md` canonical SQL. Scope: `backend/ktor/.../timeline|post|engagement`, `infra/supabase/.../repo/Jdbc*`, `TimelineReadRateLimiter`, `RedisRateLimiter`. The reply-counter viewer-block tradeoff (post-likes-v7 / post-replies-v8) is NOT re-flagged. In-flight worktree diffs (DbDispatchers.kt, DataSourceFactory.kt pool tuning) noted where relevant, not re-flagged.

## CRITICAL

### C1. `backend/ktor/src/main/resources/db/migration/V13__premium_search_fts.sql:50` — shadow-banned authors' posts remain visible in all three timelines

The shipped `visible_posts` view is `SELECT * FROM posts WHERE is_auto_hidden = FALSE` — no `JOIN users`, no `is_shadow_banned`, no `deleted_at` filter — and none of the three timeline queries (`JdbcPostsTimelineRepository.kt:49`, `JdbcPostsFollowingRepository.kt:60`, `JdbcPostsGlobalRepository.kt:64`) add an author-side `visible_users` join. docs/05 § Shadow Ban Implementation defines the canonical view as joining `users` with `p.deleted_at IS NULL AND u.deleted_at IS NULL AND u.is_shadow_banned = FALSE`; docs/06 § Shadow Ban says "all actions succeed from the banned user's perspective, **invisible to others**"; the `following-timeline` spec overview even claims "the same shadow-ban + auto-hide invariants enforced by `visible_posts` apply". The writer is live: `shadow_ban_author` (`admin/reportqueue/ReportResolutionRepository.kt:373`, merged PR #160) sets `is_shadow_banned = TRUE` and hides nothing else — so a shadow-banned author's existing AND future posts keep appearing in Nearby/Following/Global (and every other `visible_posts` consumer, e.g. search). Only their reply/like *counter* contributions are masked. Fix: redefine `visible_posts` per docs/05 (new migration; the `visible-posts-view` spec pins the narrow definition and MUST be amended in the same change) — or add the author-side `visible_users` join to every consumer (worse: N call sites). Note the conflict between docs/05+docs/06 and the `visible-posts-view` spec needs an explicit reconciliation either way. Confidence: high (view DDL + all three query literals + live writer verified; no test asserts author-side exclusion).

## HIGH

### H1. `infra/supabase/.../repo/JdbcPostsGlobalRepository.kt:64` (+ Following:60, Nearby:49) — timeline queries cannot use the V4 partial cursor indexes; Global/Following degrade to full-scan + sort

`posts_timeline_cursor_idx (created_at DESC, id DESC) WHERE deleted_at IS NULL` and `posts_nearby_cursor_idx ... WHERE deleted_at IS NULL` (V4:36-43) are partial on `deleted_at IS NULL`, but neither `visible_posts` (`is_auto_hidden = FALSE` only) nor any timeline WHERE clause references `deleted_at` — Postgres can only use a partial index when the query predicate *implies* the index predicate, so all three queries are ineligible. Global and Following therefore have NO index serving `ORDER BY created_at DESC, id DESC LIMIT 31` → seq scan + top-N sort over every visible post per page request, O(table) and growing (docs/05 §733-740 explicitly intends the cursor index; "Key Implementation Notes" claims "composite (created_at, id) index avoids OFFSET scan"). Nearby survives via the non-partial `posts_display_location_idx`, leaving `posts_nearby_cursor_idx` as pure write-amplification dead weight. Fix: add `AND p.deleted_at IS NULL` to the three queries + the docs/05 canonical blocks (1-line each, also future-proofs soft-delete leakage), or fold `deleted_at IS NULL` into the view (pairs with C1's fix); verify with EXPLAIN. Confidence: high (planner implication rule; index DDL + query literals verified).

### H2. `backend/ktor/.../timeline/TimelineRoutes.kt:122,201,279` + engagement/post services — blocking JDBC runs on the request dispatcher (docs/11 §3.2 violation)

All three timeline routes call `service.nearby/following/global(...)` → repository `dataSource.connection.use { ... }` synchronously with NO dispatcher hop — the Netty/Ktor request coroutine blocks on pool checkout + query. `LikeService.kt:130,135-164`, `ReplyService.kt:139,152-188`, and `CreatePostService.kt:94-123` likewise run resolve/transaction JDBC unwrapped (only their *Redis* calls get `withContext(Dispatchers.IO)` — and raw `Dispatchers.IO` itself is what docs/11 §3.2 forbids: "never raw Dispatchers.IO"). Under a request flood, every worker coroutine parks on JDBC and unrelated requests starve. The pool-bounded `DbDispatchers` just landed in-flight on this branch (currently wired only into `installAuth`) — extend the same `withContext(dbDispatchers.db)` hop to timeline/engagement/post service seams. Confidence: high.

### H3. `backend/ktor/.../timeline/TimelineReadRateLimiter.kt:162-173` — up to 58 Redis round-trips + 58 blocked IO threads per timeline page

`postIncrement` issues `2 × (N−1)` parallel `tryAcquire` Lua calls (N=30 page → 58 calls, plus 2 pre-check calls = 60 Redis commands per request). Each `async(Dispatchers.IO)` blocks a thread on Lettuce's sync API — two concurrent full-page reads already exceed `Dispatchers.IO`'s 64-thread default, so timeline traffic queues ALL of the app's IO work; it also burns the Upstash per-command budget ~30× faster than necessary. The shape is spec-mandated (`timeline-read-rate-limit` § "issues (N − 1)... parallel best-effort calls"), so this needs a spec amendment, not just code: add a batched Lua entry point (`tryAcquireN(key, n, capacity, ttl)` — one ZADD of n members under the same capacity check) → 2 calls per request with identical sliding-window semantics. The per-call `UUID.randomUUID()` in `RedisRateLimiter.admit` folds into the same fix. Confidence: high on cost math; flagged as spec'd-deliberate.

### H4. `infra/redis/.../RedisRateLimiter.kt:119-121,284` — daily caps under-enforce late in the day: prune window shrinks to time-until-reset

`admit` passes `windowMs = ttlMs = ttl.toMillis()`; for daily limiters ttl = `computeTTLToNextReset` (time REMAINING until the WIB-staggered reset). The Lua `ZREMRANGEBYSCORE key 0 (now - window)` therefore prunes any entry older than the *remaining* time: a Free user who spends 10 likes at 09:00 gets them pruned by ~18:30 (remaining 5.5h < elapsed 9.5h) and can like 10 more — refills repeat on a halving schedule (≈6-8 batches/day → daily-10 effectively ~60-80, reply-20 ~140). Hourly/burst keys are unaffected (fixed window). The conflation is spec'd verbatim (`rate-limit-infrastructure` spec line 101: "window_ms is always ttl_ms") but the spec nowhere acknowledges the late-day refill consequence — looks like an unexamined side effect, not a decision. Fix: separate window from TTL for daily keys (window = fixed ~25h, TTL = computeTTLToNextReset) or a plain fixed-window INCR for `_day}` keys; spec amendment required. Confidence: high on mechanism, medium on abuse materiality (bounded by burst caps).

## MEDIUM

### M1. `docs/05-Implementation.md:324` — `posts_author_idx` is documented but no migration ever shipped it

docs/05 § Posts Schema lists `CREATE INDEX posts_author_idx ON posts(author_id, created_at DESC) WHERE deleted_at IS NULL`, but V4-V19 create only the two GIST indexes, the two (unusable, see H1) partial cursor indexes, and the V13 GIN indexes — nothing on `posts(author_id)`. Following's `author_id IN (SELECT followee_id ...)` filter and any future profile-posts/own-content listing have no author-side access path. Fix: ship the index in a migration (with `deleted_at IS NULL` only if H1's predicate fix lands too, else non-partial) or amend docs/05 to drop the claim. Confidence: high.

### M2. `backend/ktor/.../post/CreatePostService.kt:61` — post creation has no rate limit at all (docs/05 Layer 2 mandates Post 10/day Free)

docs/05 § Layer 2 table: "Post | 10/day Free, unlimited Premium"; Layer 4 adds the 50-posts/1km/h area cap. Neither exists in code or in the `post-creation` spec — yet POST /api/v1/posts is the most expensive uncapped write (dual-PostGIS INSERT + V11 reverse-geocode trigger ladder + moderation + tsvector). Like (10/day) and reply (20/day) — far cheaper writes — shipped their limiters. QUESTION: is a `post-rate-limit` change already queued (mirrors the like-/reply-rate-limit pattern)? If not, this is the next limiter to ship. Confidence: high on the gap, medium on whether it's already-planned.

### M3. `docs/05-Implementation.md:742-764` — canonical Nearby/Following SQL blocks are stale vs the shipped query shape

Both blocks still read `SELECT p.* ... LIMIT 20` with an explicit `p.is_auto_hidden = FALSE` and no `liked_by_viewer` / `reply_count` projections; the shipped queries (V7/V8 era) project named columns, LEFT JOIN `post_likes` + LATERAL reply counter, rely on the view for auto-hide, and use LIMIT 31. Each block says "Update both when changing the canonical query shape" — the mirror rule was broken twice; only the Global block is current. Doc-only fix; matters because reviewers reconcile proposals against these blocks. Confidence: high.

## LOW

### L1. `backend/ktor/.../engagement/LikeService.kt:245` + `ReplyService.kt:270` — per-request Remote Config read + INFO log on the hot path

`resolveDailyCap` runs on every Free like/reply and emits `event=remote_config_override_applied` at INFO per request whenever the override flag is set — log noise that scales with engagement traffic, plus a per-request flag read that a future real Firebase RC binding may not keep cheap. Cache the resolved cap briefly (or log once per change). Confidence: high, impact small.

### L2. `backend/ktor/.../engagement/LikeService.kt:112-126` — burst-rejection path keeps the already-consumed daily slot

When the daily limiter admits but the burst limiter rejects, the daily slot is not released (`releaseMostRecent` only runs on the re-like no-op path). Practically negligible at current caps (Free daily 10 ≪ burst 500; Premium skips daily) but inconsistent with the documented release discipline; either release on burst-reject or document the asymmetry in the post-likes spec. Confidence: high on behavior, low severity.

### L3. `infra/supabase/.../repo/JdbcPostsTimelineRepository.kt:36` (and siblings) — SQL string rebuilt via buildString per call

Only two variants exist (with/without cursor); both could be precomputed constants. Trivial allocation, listed for completeness only — do alongside any H1 edit. Confidence: high, impact trivial.
