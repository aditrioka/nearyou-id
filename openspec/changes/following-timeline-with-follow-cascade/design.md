## Context

`nearby-timeline-with-blocks` (V5, archived 2026-04-21) shipped:
- `user_blocks` table + both directional indexes.
- `BlockService` with `block` / `unblock` / `listOutbound` performing simple single-statement ops.
- `BlockExclusionJoinRule` Detekt rule scanning Kotlin string literals + `.sql` files under `db/migration/` (excluding V5).
- `NearbyTimelineService` running the canonical Nearby query verbatim from `docs/05-Implementation.md` §1045.
- `:shared:distance/jvmMain` populated with a haversine implementation.
- `BlockEndpointsTest`, `NearbyTimelineServiceTest`, `MigrationV5SmokeTest` — 126 backend tests green.

V5 explicitly deferred two pieces:
1. The Following timeline — the canonical query (`docs/05-Implementation.md` §1057–1067) references `follows`, which does not yet exist.
2. Follow-cascade on block — `docs/05-Implementation.md` §1286–1300 prescribes a transactional `user_blocks` INSERT + bidirectional `follows` DELETE. V5's `BlockService.block()` only does the INSERT; the DELETE was noted as deferred.

The `auth-jwt` plugin, PostGIS extension, Flyway pipeline, and keyset cursor codec (`common/Cursor.kt`, base64url-JSON `{c, i}`) are all wired. No new infrastructure is required — this change is schema + business logic + routes.

`docs/05-Implementation.md` canonical references for this change:
- `follows` schema: §669–687.
- Following query: §1057–1067 (near-identical shape to Nearby but with `follows` instead of `ST_DWithin`).
- Block action flow with cascade: §1286–1300.

## Goals / Non-Goals

**Goals:**
- Ship `follows` schema (V6), the follow/unfollow endpoints, follower/following list endpoints, the Following timeline endpoint, and the follow-cascade on block — all in one vertical change, matching the shape of `nearby-timeline-with-blocks`.
- Keep the canonical Following SQL verbatim from `docs/05-Implementation.md`. No CTE batching, no helper functions, no view abstraction over `follows`.
- Make `BlockService.block()` transactionally consistent: the `user_blocks` row and any affected `follows` rows move together.
- Preserve the block-exclusion invariant on follower/following list endpoints: a viewer never sees users they've blocked or who have blocked them in a list, even if those users appear in the profile owner's follower graph.
- Zero new Detekt rules. Verify the existing `BlockExclusionJoinRule` covers the new queries; if not, add test fixtures, not new rules.

**Non-Goals:**
- Global timeline (still waits on `admin_regions`).
- `notifications` writes for new-follower push (Phase 2 item 5).
- Denormalized follower/followee counts on `users` (MVP: compute at read time, `SELECT count(*) FROM follows WHERE ...`).
- Private-profile visibility gate (`docs/05-Implementation.md` §1124 — `follows EXISTS` check for private posts; lands with privacy feature).
- Follow-churn rate limits (Phase 1 item 24).
- Mute without block, admin force-unfollow, admin follower-list tools.
- Mobile UI wiring.
- Following-timeline session/hour soft + hard caps (same deferral as Nearby).

## Decisions

### Decision 1: One vertical change, mirroring `nearby-timeline-with-blocks`

Schema + endpoints + cascade integration + test coverage all ship together. Splitting (e.g., V6 schema as its own change, then endpoints) leaves an unconsumed schema and postpones the fix to `BlockService.block()`, which is the one correctness bug the V5 change knowingly carried forward. Same rationale as the nearby/blocks vertical — the parts don't meaningfully exist without each other.

**Alternative considered:** Split into `follows-schema`, `follow-endpoints`, `following-timeline`, `follow-cascade-on-block`. Rejected — four PRs for one Ktor module, each unmergeable without the next for end-to-end validity.

### Decision 2: `follows` column naming — use `followee_id`, not `followed_id`

`docs/05-Implementation.md` §669–687 uses `followed_id`. **Override that to `followee_id`** in the migration (and document the override in the V6 header comment), because:
- `followee_id` is grammatically correct and symmetric with `follower_id` (the one being followed).
- `followed_id` is a past-participle verb form that reads like "followed" already happened, which collides with the idiomatic boolean column name `followed` future code might want.
- No downstream code references `followed_id` yet (greenfield).
- The block-action flow doc snippet in §1295 uses `followed_id` — this is the only other occurrence. Update `docs/05-Implementation.md` in the same PR to match the migration.

**Alternative considered:** Use `followed_id` as documented. Rejected — small naming debt paid now, unbounded churn later.

### Decision 3: Canonical Following query runs `FROM visible_posts` with bidirectional block exclusion

Copy from `docs/05-Implementation.md` §1057–1067 verbatim. Shape:

```sql
SELECT p.id, p.author_user_id, p.content,
       ST_Y(p.display_location::geometry) AS lat,
       ST_X(p.display_location::geometry) AS lng,
       p.created_at
FROM   visible_posts p
WHERE  p.author_user_id IN (SELECT followee_id FROM follows WHERE follower_id = :viewer)
  AND  p.author_user_id NOT IN (SELECT blocked_id FROM user_blocks WHERE blocker_id = :viewer)
  AND  p.author_user_id NOT IN (SELECT blocker_id FROM user_blocks WHERE blocked_id = :viewer)
  AND  (p.created_at, p.id) < (:cursor_created_at, :cursor_id)
ORDER BY p.created_at DESC, p.id DESC
LIMIT  :page_size + 1;
```

Key shape differences from Nearby:
- No `ST_DWithin` or `ST_Distance` — no radius, no geography math in the SELECT.
- No `distance_m` in the response.
- `lat`/`lng` still come from `display_location` via `ST_Y`/`ST_X` (the jitter/privacy invariant from `post-creation-geo` still applies — Following does not expose `actual_location`).
- `author_user_id IN (SELECT followee_id FROM follows WHERE follower_id = :viewer)` is the Following-specific clause.

Both block-exclusion subqueries are PRESENT. `BlockExclusionJoinRule` fires on `visible_posts` → requires `user_blocks` + `blocker_id =` + `blocked_id =` tokens; the query satisfies all three. Verified in the lint test suite as a new positive-pass fixture.

**Alternative considered:** Write a `FollowingPosts(viewer_id)` SQL view pre-joining `follows` + `visible_posts`. Rejected — same reasoning as the Nearby bidirectional view rejection: view invalidation semantics are a planner footgun, and the lint rule becomes harder when views hide the required join.

### Decision 4: Mutual-block check in the follow flow — app-layer read, not a DB constraint

`POST /api/v1/follows/{user_id}` performs a `SELECT 1 FROM user_blocks WHERE (blocker_id = :a AND blocked_id = :b) OR (blocker_id = :b AND blocked_id = :a) LIMIT 1` BEFORE the INSERT. If the row exists, return `409 follow_blocked` (no `follows` row created). Rationale:
- No DB-level way to express "INSERT fails if a row exists in a different table" without a trigger; triggers violate the project's "no magic DB behavior" convention (same reason V5 doesn't use triggers for the cascade).
- Race: if A blocks B between the check and the INSERT, the `follows` row lands. That's fine — the NEXT `block()` call by A or B will DELETE both `follows` rows in the transaction (Decision 5), so eventually-consistent block semantics hold. No user-visible harm.

**Alternative considered:** Single-transaction SELECT + INSERT with `SERIALIZABLE` isolation. Rejected — unnecessary transaction overhead for a race window that self-heals on the next block event.

**Alternative considered:** DB trigger to enforce. Rejected — the cascade logic in V5 was already deliberately in app code, not a trigger. Stay consistent.

### Decision 5: `BlockService.block()` wraps INSERT + dual DELETE in one JDBC transaction

The block flow now matches `docs/05-Implementation.md` §1288–1300 verbatim:

```sql
BEGIN;
INSERT INTO user_blocks (blocker_id, blocked_id) VALUES (:blocker, :blocked)
  ON CONFLICT (blocker_id, blocked_id) DO NOTHING;
DELETE FROM follows
  WHERE (follower_id = :blocker AND followee_id = :blocked)
     OR (follower_id = :blocked AND followee_id = :blocker);
COMMIT;
```

One round-trip is acceptable; both statements are tiny and indexed. The transaction is mandatory — without it, a crash between INSERT and DELETE leaves a `user_blocks` + `follows` pair, which violates the invariant "a block implies no follow in either direction."

Implementation lives in `JdbcUserBlockRepository.block()` using `connection.autoCommit = false` + manual commit/rollback, NOT Koin-managed `@Transactional` (the project has no such wrapper yet, and introducing one for this one call site is unjustified). Same pattern `JdbcPostRepository.createPost()` uses.

**Alternative considered:** Two sequential statements without a transaction. Rejected — violates `docs/05-Implementation.md` §1288 explicit `BEGIN`/`COMMIT`.

**Alternative considered:** Introduce a generic `@Transactional` helper. Rejected — scope creep. One call site does not motivate a framework.

### Decision 6: Follower/following list endpoints exclude viewer-blocked users from the list

`GET /api/v1/users/{user_id}/followers?cursor=` returns users where `followee_id = :profile_user_id`. But the list passes through two extra filters against the CALLING viewer (who may not be the profile owner):
- Exclude users the viewer has blocked.
- Exclude users who have blocked the viewer.

Query shape:
```sql
SELECT f.follower_id AS user_id, f.created_at
FROM   follows f
WHERE  f.followee_id = :profile_user_id
  AND  f.follower_id NOT IN (SELECT blocked_id FROM user_blocks WHERE blocker_id = :viewer)
  AND  f.follower_id NOT IN (SELECT blocker_id FROM user_blocks WHERE blocked_id = :viewer)
  AND  (f.created_at, f.follower_id) < (:cursor_created_at, :cursor_user_id)
ORDER BY f.created_at DESC, f.follower_id DESC
LIMIT  :page_size + 1;
```

The `/following` endpoint is symmetric (`WHERE f.follower_id = :profile_user_id`, exclude the `followee_id`).

**Rationale:** without this filter, the viewer can enumerate users they've blocked via a third-party profile — a trivial block-evasion route. The filter also means viewer A and viewer B see different lists for the same profile; that's intentional. The viewer cap is documented on the endpoint, not on the profile owner's canonical graph.

**Why is this lintable?** The Detekt rule's protected-table set includes `users`, but the query SELECTs `f.follower_id AS user_id` without a `FROM users` — it's a projection over `follows`. The rule will NOT fire (correctly — the query contains no content, just IDs). To defend against drift, add a test fixture asserting the rule passes and document the pattern in the KDoc.

**Alternative considered:** Leave the list unfiltered; rely on the client to hide blocked users. Rejected — client-side filtering leaks existence (client knows which IDs it filtered out), and a stale client shows blocked users.

### Decision 7: `409 follow_blocked` instead of `404 user_not_found` for mutual-block case

When caller A tries to follow B but a `user_blocks` row exists in either direction, return `409 follow_blocked`, NOT `404`. Semantics:
- `404 user_not_found` is reserved for "the target UUID does not exist in `users`." Masking block state as `404` is an information leak (A could probe by following random UUIDs — a real-user-who-blocked-me returns `409`, a fake UUID returns `404`).
- `409 follow_blocked` is honest about the state. The mobile copy for `409 follow_blocked` is generic ("Kamu tidak bisa mengikuti pengguna ini sekarang") and does NOT reveal who initiated the block.

**Alternative considered:** `403 follow_forbidden`. Rejected — `403` implies permission/role, not state. `409` (Conflict) is the closest semantic fit.

**Alternative considered:** Silently accept the follow, then let the read path filter. Rejected — A would see B in their own `/following` list, exposing the block at a different surface.

### Decision 8: Follower/following endpoints live under `/users/{id}/...`, not `/follows/...`

RESTful grouping: `/users/{id}/followers` and `/users/{id}/following` are the collections owned by the user. `/follows/{id}` is reserved for the follow-relationship resource the caller owns (POST/DELETE). This avoids the ambiguity of `/follows?user_id=X&direction=inbound` and matches standard social-graph API shapes (GitHub, Twitter).

**Alternative considered:** `GET /api/v1/follows?of=<uuid>&direction=followers`. Rejected — directional query params are harder to cache, log, and lint than path-scoped resources.

### Decision 9: No Detekt rule changes; document `follows` exclusion in the rule's KDoc

`BlockExclusionJoinRule` from V5 already fires on `posts | visible_posts | users | chat_messages | post_replies`. The new Following timeline query touches `visible_posts` and includes both `user_blocks` subqueries — the rule passes. The follower/following list queries touch `follows`, which is NOT in the protected set, so the rule does not fire (correctly).

Add a KDoc paragraph to `BlockExclusionJoinRule.kt` explaining why `follows` is excluded:
> `follows` is NOT a protected table. Rows carry only `(follower_id, followee_id, created_at)` — no user-visible content — and business queries are always caller-scoped (one side is `= :viewer`). Content reached THROUGH `follows` (the Following timeline) lands on `visible_posts` and is re-checked there by this rule.

This is defense against future drift where a maintainer notices `follows` is "missing" and tries to add it.

**Alternative considered:** Add `follows` to the protected set. Rejected — every business query on `follows` would then need a `user_blocks` subquery, which is nonsensical for the follow/unfollow endpoints (those need to know the `user_blocks` state before the INSERT, not exclude `follows` rows by block state).

### Decision 10: `V6__follows.sql` header documents:

1. Joint V3 (`users`) dependency for FK targets.
2. Runtime coupling to V5 (`user_blocks`): `BlockService.block()` now performs a transactional `follows` DELETE; migration and business code must land together.
3. The `followed_id → followee_id` rename from `docs/05-Implementation.md` §669 (with a note to update the doc in the same PR).
4. Future-cascade convention inherited from V5: if new tables couple to `follows` relationships (e.g., "recommended because X follows Y"), they MUST add their own cascade on `follows` row delete.

Keep the header terse but explicit — V5's header is the model.

## Risks / Trade-offs

- **Risk:** Transactional `block()` changes the single-statement contract that `BlockEndpointsTest` relied on — ordering assumptions may shift. → **Mitigation:** `BlockEndpointsTest` already tests outcomes (rows present/absent, status codes), not statement counts. Add one new scenario: "block A→B when follows (A, B) and (B, A) both exist → both follows rows gone after 204." Existing scenarios pass unchanged.
- **Risk:** Race between mutual-block check and INSERT allows a `follows` row past a fresh block. → **Mitigation:** The next `block()` call DELETEs it (Decision 5). Logging at INFO for the race helps spot anomalies. Prod-observable via an Amplitude/OTel counter; out of scope for this change.
- **Risk:** Follower/following list query performance under deep scroll (100k+ followers for a celebrity) — the viewer block-exclusion subqueries re-execute on every page. → **Mitigation:** `user_blocks` is typically small per viewer (dozens, not thousands); planner inlines the NOT-IN. Phase 2 benchmark (`docs/08-Roadmap-Risk.md:167`) already targets 100-concurrent load, follower-list query is in scope there. No index change in V6.
- **Risk:** Renaming `followed_id → followee_id` diverges the migration from `docs/05-Implementation.md`. → **Mitigation:** Update the doc in the same PR (one-shot, atomic). V6 header carries the rename note. No code yet references the old name.
- **Risk:** `BlockExclusionJoinRule` might be extended later to cover `follows` by someone who missed the KDoc. → **Mitigation:** KDoc paragraph (Decision 9); add a unit-test fixture named `follows_is_deliberately_not_protected_table` that asserts the rule does NOT fire on a `FROM follows` query. Intention-encoded in code, not just prose.
- **Risk:** The Following timeline returns zero posts when a user follows nobody — UI needs an empty-state. → **Mitigation:** Endpoint behavior is correct (empty list, `next_cursor: null`). Empty-state copy is mobile's problem; out of scope.
- **Risk:** Celebrity account with 100k followings causes the `author_user_id IN (SELECT followee_id FROM follows WHERE follower_id = :viewer)` subquery to scan an enormous set. → **Mitigation:** `follows_follower_idx (follower_id, created_at DESC)` supports fast fetch of followee set by follower; planner is expected to materialize this into a hash for the IN check. Phase 2 benchmark validates. If it doesn't, CTE batching is already the Phase 2 optimization path (`docs/08-Roadmap-Risk.md:165`).

## Migration Plan

1. **Deploy migration:** V6 is additive — no `ALTER` on existing tables. Safe on live DB. Flyway transactional. No downtime.
2. **Deploy code:** Follow endpoints + Following timeline endpoint + transactional `BlockService.block()` land in the same artifact. The updated `BlockService` requires V6 to exist (it DELETEs from `follows`); Flyway runs before the Ktor container starts, so ordering is enforced by the existing deployment pipeline.
3. **Backfill:** None. No existing `follows` rows exist; no `user_blocks` rows had a corresponding `follows` row to clean up (V5 `user_blocks` data is ≤ 1 week old in dev, 0 rows in staging/prod).
4. **Rollback:** Revert the deploy. V6 can stay (additive). If V6 must be reverted (won't happen in prod), `DROP TABLE follows` in dev/staging only. The old `BlockService` (V5 shape) still works with an empty `follows` table — the cascade DELETE is a no-op.
5. **Doc sync:** Update `docs/05-Implementation.md` §1295 (`followed_id → followee_id`) in the same PR. Update `docs/08-Roadmap-Risk.md` Phase 1 item 15 (Following shipped) and item 16 (follow-cascade shipped) in the archive commit.

## Open Questions

- Should `GET /api/v1/users/{id}/followers` include `username` inline, or just `user_id + created_at`? Current decision: `user_id + created_at` only, matching `GET /api/v1/blocks` shape for consistency. If the mobile follower-list screen needs usernames inline (it probably does), add a JOIN on `visible_users.username` in a follow-up change — out of scope here. Same question for `/following`.
- Should a profile owner see their OWN follower/following list without the viewer block-exclusion filter (i.e., "you have 127 followers including 3 you blocked")? Current decision: NO — apply the same filter regardless of viewer == profile owner. This keeps the endpoint semantics identical across viewers and avoids a branching code path. Revisit if product asks for a "see blocked followers" Settings screen.
- Should the Following timeline support a `lat`/`lng` param for distance enrichment (showing how far each followee's post is)? Current decision: NO — Following is chronological, not geographic. If a future product requirement adds distance enrichment, a separate `/api/v1/timeline/following?enrich=distance` flag can extend the query. Out of scope.
