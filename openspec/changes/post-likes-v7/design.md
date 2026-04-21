## Context

`following-timeline-with-follow-cascade` (V6, archived 2026-04-21) shipped:
- `follows` table + both directional indexes + CHECK + dual cascade to `users(id)`.
- `FollowService` / `FollowRoutes` / `UserSocialRoutes` / `FollowingTimelineService`.
- Transactional `BlockService.block()` with bidirectional `follows` DELETE.
- A KDoc note on `BlockExclusionJoinRule` explaining why `follows` is deliberately NOT a protected table, plus a `follows_is_deliberately_not_protected_table` lint fixture.
- Backend test suite green with `FollowEndpointsTest`, `FollowingTimelineServiceTest`, `MigrationV6SmokeTest`.

Everything this change needs is already wired: the `visible_posts` view (V4), the `visible_users` view (V2), the `auth-jwt` plugin, the base64url `{c, i}` cursor codec (`common/Cursor.kt`), the `BlockExclusionJoinRule` Detekt rule, the `@AllowMissingBlockJoin` annotation, and the `MigrationVNSmokeTest` harness pattern.

`docs/05-Implementation.md` canonical references for this change:
- `post_likes` schema: §690–708 (verbatim — no renames this time).
- `post_liked` notification type: §851 (deferred to notifications-schema change).

## Goals / Non-Goals

**Goals:**
- Ship `post_likes` schema (V7), the like/unlike endpoints, the shadow-ban-aware likes counter, and the `liked_by_viewer` flag on both timeline endpoints — all in one vertical change, matching the shape of `following-timeline-with-follow-cascade`.
- Keep the canonical likes DDL verbatim from `docs/05-Implementation.md` §694–702. No column renames, no helper triggers, no view over `post_likes`.
- Maintain the 404-on-invisibility contract already established by the timelines: a post hidden by a block is indistinguishable from a missing post across every read/write surface.
- Preserve the keyset-cursor invariant on both timelines: adding `liked_by_viewer` via `LEFT JOIN post_likes` MUST NOT alter ordering, `LIMIT 31` probe semantics, or `BlockExclusionJoinRule` compliance.
- Zero new Detekt rules. Extend the existing `follows` KDoc/fixture pattern to cover `post_likes`.

**Non-Goals:**
- Like-list endpoint. Liker identity disclosure needs bidirectional `user_blocks` filtering on the list (different shape from the counter) — separate privacy design.
- Notifications fanout on like (`post_liked`). Waits on notifications-schema change.
- Denormalized like counter on `posts`. Read-time compute is fine for MVP (follower-count deferral precedent in V6).
- Rate limits (Phase 2 #3 Free 10/day + 500/hour burst). Waits on the 4-layer rate-limit change (Phase 1 #24).
- Mobile UI wiring (like button, liked-state persistence, count rendering).
- Admin-side like surfaces.
- Private-profile gate on liking private posts (`docs/05-Implementation.md` §1124).

## Decisions

### Decision 1: One vertical change, mirroring `following-timeline-with-follow-cascade`

Schema + write endpoints + count endpoint + timeline `liked_by_viewer` projection all ship together. Splitting (e.g., V7 schema first, then the writes, then the timeline projection) leaves an unconsumed schema and a half-exposed API. Same rationale as V6 — the parts don't meaningfully exist without each other. The `liked_by_viewer` field is the direct consumer of the schema; shipping one without the other either breaks the contract or burns client trust with a Boolean that lies for a release cycle.

**Alternative considered:** Split into `post-likes-schema` + `post-likes-endpoints` + `timeline-liked-by-viewer`. Rejected — three PRs for one Ktor module and one SQL file, each unmergeable without the next.

### Decision 2: `post_likes` schema verbatim from `docs/05-Implementation.md` §694–702 — no renames

Unlike V6 (where `followed_id → followee_id` was worth the one-time doc sync), `post_likes` columns are already symmetric and unambiguous:
- `post_id` — what was liked.
- `user_id` — who liked it.

`docs/05-Implementation.md` §694–702 uses these names; no override. The V7 header comment is terse: FK targets (V3 `users`, V4 `posts`), dual cascade, index purposes — no rename note to carry.

**Alternative considered:** Rename `user_id` → `liker_id` for symmetry with `post_id`. Rejected — no other engagement table in the codebase uses `<verb>er_id` naming; `users.id` references are consistently `user_id` (refresh_tokens, user_blocks outbound/inbound via blocker/blocked, follows via follower/followee). The pattern is "rename to disambiguate two references to users in the same row." `post_likes` has only one `users.id` reference, so the generic `user_id` is correct.

### Decision 3: `404 post_not_found` on block-hidden post — NOT `403`

`POST /api/v1/posts/{post_id}/like` and `GET /api/v1/posts/{post_id}/likes/count` both SELECT the target post from `visible_posts` with bidirectional `user_blocks` NOT-IN subqueries BEFORE any write or count. If the SELECT returns zero rows, the route returns `404 post_not_found`. The reasons:

1. The timeline already returns 404 for the same three cases (post missing, post soft-deleted via `visible_posts`, post author block-excluded bidirectionally). A like endpoint that returns `403 post_blocked` leaks state the timeline hides: A could probe a target's block state by trying to like an invisible post. `404` preserves the indistinguishability invariant.
2. The `user_blocks` table is already private state (docs §1279). Every surface that reveals "this specific post exists but you can't see it" gives a concrete block-detection primitive.
3. `404` is semantically honest inside the viewer's world model: from the viewer's perspective, the post genuinely does not exist (it is not in any list the viewer can ever see).

The query shape:

```sql
SELECT p.id
FROM visible_posts p
WHERE p.id = :post_id
  AND p.author_user_id NOT IN (SELECT blocked_id FROM user_blocks WHERE blocker_id = :viewer)
  AND p.author_user_id NOT IN (SELECT blocker_id FROM user_blocks WHERE blocked_id = :viewer)
LIMIT 1;
```

Both block-exclusion subqueries MUST be present — this satisfies `BlockExclusionJoinRule` on the new like-service code. Verified as a positive-pass fixture in the lint test suite.

**Alternative considered:** `403 post_blocked`. Rejected per point (1). The existence leak is the whole reason `404` is the project-wide convention for block-hidden content.

**Alternative considered:** Return `204 No Content` on a blocked like attempt (silently drop). Rejected — the client cannot distinguish success from failure, and a retry loop would re-hit the same case forever without surfacing the state to logs or monitoring.

### Decision 4: `DELETE /like` is a pure no-op on zero rows — no 404

Contrast with `DELETE /api/v1/follows/{user_id}` (V6), which is also a no-op-204. `DELETE /like` keeps the same semantic:

```sql
DELETE FROM post_likes WHERE post_id = :post_id AND user_id = :caller;
```

No preliminary `visible_posts` SELECT, no block check, no existence check. Rationale:
- The caller can only remove their OWN like row (PK scoped to `user_id = :caller`). There is no cross-user side effect to protect against.
- If the post has been soft-deleted since the like was placed, the row was already cascade-removed. DELETE is a no-op and 204 is honest.
- If the caller and post author blocked each other after the like landed, the row may still exist — the block cascade in V5/V6 did NOT delete `post_likes` (out of scope for this change; see Decision 10). The caller is allowed to remove their own like regardless of current block state. Withholding the delete would mean the like row permanently persists in a way the caller cannot rescind.

**Alternative considered:** Run the same `visible_posts` + block-exclusion SELECT and 404 if invisible. Rejected — the user's own like is their own state; they should always be able to clean it up. 204 is the right answer whether the DELETE affected 1 row or 0.

### Decision 5: Count query JOINs `visible_users` — shadow-ban exclusion; no viewer-block exclusion

`docs/05-Implementation.md` §705 prescribes the shadow-ban filter for aggregated surfaces. The count query:

```sql
SELECT COUNT(*)
FROM post_likes pl
JOIN visible_users vu ON vu.id = pl.user_id
WHERE pl.post_id = :post_id;
```

Shadow-banned likers disappear from the public number. A shadow-banned account whose likes still inflated the counter would be a shadow-ban bypass. `visible_users` is the canonical lens.

Viewer-block exclusion is NOT applied. The rationale is privacy, and it is the core tradeoff of this change:
- A viewer-block-excluded count varies per viewer. If A has blocked 5 likers and B has not, A sees a count of N-5 and B sees N. The delta reveals that A has blocked at least 5 of the likers, which is a concrete block-enumeration channel (A blocks an account, then re-fetches the counter and diffs).
- The viewer's `user_blocks` list is private state (docs §1279). Exposing a count that is a function of that state effectively publishes it.

The tradeoff: a viewer who has blocked a liker will still see that liker's like contribute to the public count. That is acceptable — the counter is a magnitude, not a list. The count does not identify individual likers, so block enumeration via the counter is impossible.

**Alternative considered:** Apply bidirectional `user_blocks` NOT-IN subqueries to the count. Rejected per the privacy argument above. Revisit only if product adds a liker-list surface (which would need its own per-viewer filtering by the same argument but with acceptable cost because the list already varies per viewer for other reasons).

**Alternative considered:** Expose a per-viewer "counts ignoring your blocks" field alongside the raw count. Rejected — two numbers on one endpoint invites client confusion and still leaks via the delta.

If this is ever revisited, the `post-likes` spec MUST be updated to document the new tradeoff.

### Decision 6: `liked_by_viewer` is an additive `LEFT JOIN post_likes` — cursor invariant preserved

Both `nearby-timeline` and `following-timeline` paginate on `(p.created_at DESC, p.id DESC)` via `posts_timeline_cursor_idx`. Adding `liked_by_viewer` MUST NOT touch that ordering. The query shape (Nearby; Following is identical minus the PostGIS column):

```sql
SELECT p.id, p.author_user_id, p.content,
       ST_Y(p.display_location::geometry) AS lat,
       ST_X(p.display_location::geometry) AS lng,
       ST_Distance(p.display_location, ST_MakePoint(:lng, :lat)::geography) AS distance_m,
       p.created_at,
       (pl.user_id IS NOT NULL) AS liked_by_viewer
FROM visible_posts p
LEFT JOIN post_likes pl
       ON pl.post_id = p.id AND pl.user_id = :viewer
WHERE ST_DWithin(p.display_location, ST_MakePoint(:lng, :lat)::geography, :radius_m)
  AND p.author_user_id NOT IN (SELECT blocked_id FROM user_blocks WHERE blocker_id = :viewer)
  AND p.author_user_id NOT IN (SELECT blocker_id FROM user_blocks WHERE blocked_id = :viewer)
  AND (p.created_at, p.id) < (:cursor_created_at, :cursor_id)
ORDER BY p.created_at DESC, p.id DESC
LIMIT :page_size + 1;
```

Key invariants that MUST hold after the JOIN:
- `ORDER BY` unchanged — still `p.created_at DESC, p.id DESC`.
- `(p.created_at, p.id) < (:c, :i)` keyset predicate unchanged; `pl.*` columns never appear in the cursor.
- `LIMIT 31` unchanged; the probe-row strategy still produces at most 30 posts in the response plus one cursor seed.
- `BlockExclusionJoinRule` still sees `visible_posts` + `user_blocks` + `blocker_id =` + `blocked_id =` in the literal, so the rule passes. Adding `post_likes` to the literal does NOT move the check; `post_likes` is not in the protected set (Decision 9).
- Cardinality: the `LEFT JOIN` is safe because `post_likes` PK is `(post_id, user_id)`, so the `user_id = :viewer` predicate reduces to at-most-one row per `(p.id, :viewer)` pair. The join does not fan out rows; `COUNT(*)` over the result equals `COUNT(DISTINCT p.id)`.

The index `post_likes_post_idx (post_id, created_at DESC)` supports the `pl.post_id = p.id` predicate; the `user_id` filter narrows to one row via PK. No new index needed.

**Alternative considered:** Issue a separate `SELECT post_id FROM post_likes WHERE user_id = :viewer AND post_id = ANY(:page_ids)` round-trip after the primary query, then stitch results in Kotlin. Rejected — one query is simpler, atomically consistent, and the JOIN cost is negligible (at most one matching row per page row via PK lookup).

**Alternative considered:** Use a scalar subquery `EXISTS(SELECT 1 FROM post_likes pl WHERE pl.post_id = p.id AND pl.user_id = :viewer) AS liked_by_viewer`. Functionally equivalent on Postgres and indexes the same. LEFT JOIN chosen for consistency with the existing NOT-IN-subquery style already used for blocks.

### Decision 7: `liked_by_viewer` present on every post — never omitted

The field MUST appear on every post in the response array (JSON Boolean, not nullable). Clients rely on the key's presence to drive UI state; omitting it on "no like" would force defensive lookups. Both timelines document this in the scenario blocks: "liked_by_viewer is `true` if and only if a `post_likes` row `(p.id, :viewer)` exists; otherwise `false`."

### Decision 8: Idempotent writes via `ON CONFLICT DO NOTHING` (likes) and no-op DELETE (unlikes)

Like INSERT:

```sql
INSERT INTO post_likes (post_id, user_id) VALUES (?, ?)
  ON CONFLICT (post_id, user_id) DO NOTHING;
```

Re-liking a post returns 204 whether or not the row already existed. Same pattern as V6 `follows` INSERT.

Unlike DELETE:

```sql
DELETE FROM post_likes WHERE post_id = ? AND user_id = ?;
```

Returns 204 regardless of affected row count. Same pattern as V6 `follows` DELETE.

Both statements are single-row PK-scoped — no transaction wrapper, no explicit `autoCommit` management. JDBC autocommit default is fine.

### Decision 9: No Detekt rule changes; document `post_likes` exclusion in KDoc + fixture

`BlockExclusionJoinRule`'s protected-table set (`posts`, `visible_posts`, `users`, `chat_messages`, `post_replies`) does NOT grow to include `post_likes`. Reasons, mirroring the existing `follows` exclusion (V6):

1. `post_likes` rows carry only `(post_id, user_id, created_at)` — no user-visible content (no body, no display name).
2. Business queries against `post_likes` are always caller-scoped: writes filter on `user_id = :caller`; the count read filters on `post_id = :post_id` and JOINs `visible_users` for shadow-ban exclusion.
3. Content reached THROUGH `post_likes` (e.g., the timeline's `liked_by_viewer` JOIN) lands on `visible_posts`, where this rule re-checks for bidirectional `user_blocks` exclusion — so the like query on the timeline is already covered by the primary `FROM visible_posts` clause.

Add a KDoc paragraph to `BlockExclusionJoinRule.kt` explaining the above (parallel structure to the existing `follows` paragraph). Add a `post_likes_is_deliberately_not_protected_table` lint test fixture asserting the rule does NOT fire on:
- `"SELECT COUNT(*) FROM post_likes pl JOIN visible_users vu ON vu.id = pl.user_id WHERE pl.post_id = ?"` (count query).
- `"INSERT INTO post_likes (post_id, user_id) VALUES (?, ?) ON CONFLICT (post_id, user_id) DO NOTHING"`.
- `"DELETE FROM post_likes WHERE post_id = ? AND user_id = ?"`.

**Alternative considered:** Add `post_likes` to the protected set. Rejected — every business query on `post_likes` is either caller-scoped or aggregated through `visible_users`. Requiring a `user_blocks` bidirectional join on the `DELETE FROM post_likes WHERE user_id = :caller` call would be nonsensical (the caller can always remove their own row regardless of block state; see Decision 4).

**Alternative considered:** Rely only on the KDoc paragraph; skip the fixture. Rejected — the `follows` precedent encodes the decision as a test, not just prose. Keeps the decision reviewable in code (PRs that try to add `post_likes` to the protected set fail a named test with a clear error, not a silent behavior shift).

### Decision 10: Block cascade does NOT extend to `post_likes` in this change

V5 established `BlockService.block()` with `user_blocks` INSERT. V6 added a transactional bidirectional `follows` DELETE inside the same transaction. A natural question: should `BlockService.block()` also delete `post_likes` rows between the two users on block?

**Decision: NO, not in this change.** Reasons:
1. The timeline already hides block-hidden authors' posts, so a stale `post_likes` row is invisible to the blocker (post doesn't appear in Nearby/Following, so `liked_by_viewer` is never computed for it).
2. The `visible_posts` + bidirectional block exclusion on the like endpoints means a blocked caller cannot re-like or re-DELETE the row anyway (404 on `POST /like`; 204 no-op on `DELETE /like` — the only surface that still works, which is fine).
3. Hard-deleting the user on either side cascades the like row via V7's dual FK cascade (the canonical cleanup path).
4. Adding a third DELETE to the block transaction grows the blast radius of every block operation for a state that is already hidden from both parties.

If product later decides the post-author's like history should be purged on block (e.g., for abuse-vector mitigation), a dedicated `block-cascade-post-likes` change can add it — same shape as V6's follow-cascade. Out of scope for V7.

**Alternative considered:** Add bidirectional `post_likes` DELETE to `BlockService.block()` now. Rejected — no user-visible state is leaked by leaving the rows, and the extra DELETE complicates the block transaction for a case already masked by the timelines.

### Decision 11: `V7__post_likes.sql` header documents

1. Joint schema-level FK dependency on V3 (`users`) AND V4 (`posts`). Unlike V5 (first joint schema dependency) and V6 (schema-plus-runtime), V7 is schema-only with two FK targets. The migration-pipeline spec documents this as a third dependency shape.
2. No runtime coupling — unlike V6, no other service's behavior changes with V7's arrival. The timelines' new `LEFT JOIN post_likes` ships with V7 in the same deploy.
3. Consumers: the like service (writes), the likes-count endpoint (reads via `visible_users` JOIN), and both timelines (LEFT JOIN for `liked_by_viewer`). None of these need to bidirectionally join `user_blocks` against `post_likes` — see Decision 9.
4. Future-cascade convention: if a later table references a `post_likes` row (e.g., `like_notifications`), it MUST add its own cascade on `post_likes` row delete.

Terse but explicit, matching V5/V6 headers.

## Risks / Trade-offs

- **Risk:** A viewer with a large `user_blocks` list sees a counter that includes likes from blocked accounts. → **Mitigation:** This is the privacy tradeoff (Decision 5). The counter is a magnitude, not a list; individual blocked-liker identities are not exposed. Documented in the `post-likes` spec as a normative tradeoff so a future maintainer does not "fix" it and leak block state.
- **Risk:** Adding the `LEFT JOIN post_likes` to the timeline query regresses latency under deep scroll. → **Mitigation:** The join is PK-scoped (`post_likes_pk = (post_id, user_id)`), returns at most one row per page row, and the primary query already touches `visible_posts` (the most expensive join); the planner merges the LEFT JOIN into the existing hash. Phase 2 benchmark (`docs/08-Roadmap-Risk.md:167`) is already in scope for timeline latency — `liked_by_viewer` is validated there. No new index needed.
- **Risk:** `POST /like` on a soft-deleted post returns 404 but the post was still visible a second ago. → **Mitigation:** Same race as `POST /follow` on a just-deleted user — the timeline is eventually consistent. Client sees 404, which matches the timeline's next refresh. No user-visible harm.
- **Risk:** Race between `POST /like` and author-block: A likes post, then author blocks A in the same second. The `post_likes` row persists. → **Mitigation:** Per Decision 10, we deliberately leave it. The row is invisible to A (timeline excludes the post) and invisible to the counter reader (still contributes to count, but the counter is a magnitude). Self-heals on user hard-delete via V7 cascade. No user-visible harm.
- **Risk:** `liked_by_viewer` drifts to `false` after a `user_blocks` row lands (because the post becomes block-hidden and the viewer can no longer see it). → **Mitigation:** This is correct behavior — the post is 404 to the viewer, so `liked_by_viewer` is never computed (the post is not in the response). The stale `post_likes` row is still there server-side; if the block is lifted, the field correctly becomes `true` again. No special handling needed.
- **Risk:** `BlockExclusionJoinRule` might be extended later to cover `post_likes` by someone who missed the KDoc. → **Mitigation:** KDoc paragraph (Decision 9); add a unit-test fixture `post_likes_is_deliberately_not_protected_table` that asserts the rule does NOT fire on any of three representative `post_likes`-only queries. Intention-encoded in code, mirrors V6's `follows` precedent.
- **Risk:** The `liked_by_viewer` field inflates response size for large pages. → **Mitigation:** 30 Booleans per page is ~60 bytes serialized; trivial relative to the existing `content` and UUID fields. No concern.

## Migration Plan

1. **Deploy migration:** V7 is additive — no `ALTER` on existing tables. Safe on live DB. Flyway transactional. No downtime.
2. **Deploy code:** Like endpoints + likes count + updated `NearbyTimelineService` + updated `FollowingTimelineService` + `BlockExclusionJoinRule` KDoc land in the same artifact. The updated timeline services require V7 (they `LEFT JOIN post_likes`); Flyway runs before the Ktor container starts, so ordering is enforced by the existing deployment pipeline.
3. **Backfill:** None. `post_likes` starts empty. Both timelines emit `liked_by_viewer = false` for every post in the first-deploy responses until users begin liking — no data-migration shape to manage.
4. **Rollback:** Revert the deploy. V7 can stay (additive; empty table). If V7 must be reverted (won't happen in prod), `DROP TABLE post_likes` in dev/staging only. The old timeline services (V6 shape) emit no `liked_by_viewer` and still work against a V7 DB — the field is additive on the response shape, and older clients ignore unknown fields.
5. **Doc sync:** Add one-line back-reference notes in `docs/05-Implementation.md` §690 pointing to this change. The canonical DDL already exists there verbatim (§694–702); do NOT modify the DDL block in this change. Update `docs/08-Roadmap-Risk.md` Phase 2 item 3 (Likes shipped; Reply deferred to V8) in the archive commit.

## Open Questions

- Should the `liked_by_viewer` field be `liked` (shorter) instead of `liked_by_viewer`? Current decision: `liked_by_viewer` to make the per-viewer semantic explicit in the wire format (so a future cached-response reviewer doesn't assume it's a stable property of the post). Revisit if the mobile team complains about verbosity.
- Should the likes-count endpoint support `If-None-Match` / ETag caching? Current decision: NO — counts change per like event; caching is a mobile-client concern with its own TTL (product unlikely to need second-accurate counts). Revisit if counter request volume becomes a bottleneck, at which point a cached denorm counter on `posts` is the better fix.
- Should `GET /api/v1/posts/{post_id}/likes/count` be exposed unauthenticated? Current decision: NO — consistent with every other read in the app (Nearby, Following, follower lists all require Bearer JWT). Unauthenticated count endpoints would be scrapeable rank signals for third parties. Bearer JWT is cheap to require.
- When `notifications` lands (Phase 2 #5), should `POST /like` fire a `post_liked` notification inside the same transaction or via an async queue? Current stance: separate change; notifications-schema will carry that decision. V7 deliberately emits no side effect beyond the INSERT to avoid coupling the two changes.
