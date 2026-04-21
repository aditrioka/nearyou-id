## Context

Phase 2 item 3 in `docs/08-Roadmap-Risk.md:150` bundled "Like + reply" as one line item. V7 (`post-likes-v7`, archived 2026-04-21) shipped Likes because Likes are strictly smaller — no content string, no `is_auto_hidden` column, no soft-delete column, no block-aware list-read path. Replies were deferred to V8 for the same reason: they're a bigger surface and deserve their own change.

V8 lands on a pipeline where:
- V5 ships `user_blocks` + bidirectional block-exclusion in the Nearby read path + the `BlockExclusionJoinRule` lint (protected tables already include `post_replies` as a forward-looking target).
- V6 ships `follows` + the Following read path + the transactional follow-cascade on block.
- V7 ships `post_likes` + like endpoints + the `liked_by_viewer` LEFT JOIN on both timelines + the `post_likes`-is-not-protected KDoc decision.

V8 is the first change that:
1. Introduces an author-soft-deletable user-content row (Likes are PK `(post_id, user_id)` with no tombstone column).
2. Introduces the first production reader of `post_replies` (previously the lint rule protected it speculatively).
3. Re-introduces a `RESTRICT`-side FK on `users(id)`, which has not been added since V4 `posts.author_id`.
4. Adds a `LEFT JOIN LATERAL` sub-scalar to the timelines (V7 added a plain `LEFT JOIN` but never a lateral subquery).

The canonical DDL, block-exclusion rule, auto-hide semantics, and shadow-ban JOIN convention are already decided in the docs. This design covers the five non-obvious decisions that needed explicit rationale.

## Goals / Non-Goals

**Goals:**
- Ship V8 DDL verbatim from `docs/05-Implementation.md` §716–729 with no column renames (same discipline as V7).
- Three idempotent, block-aware, opaque-error-envelope endpoints for create / list / soft-delete of replies.
- Additive `reply_count` on both timelines with the same privacy posture as the V7 `likes/count` (shadow-ban exclusion applied; viewer-block exclusion deliberately NOT applied).
- Activate the `BlockExclusionJoinRule` against `post_replies` for the first time, with a positive+negative fixture pair that locks the V8 reader query shape.

**Non-Goals:**
- Report submission + 3-reporter auto-hide trigger (Phase 2 item 4, separate change; V8 ships the `is_auto_hidden` column, not the mechanism that sets it).
- Rate limiting (Free 20/day, Premium unlimited per `docs/05-Implementation.md:1686`); waits on the 4-layer rate-limit change (Phase 1 item 24).
- `post_replied` notification (Phase 2 item 5 notifications schema).
- Reply editing (posts edit is Premium Phase 4; replies are not in scope for edit).
- Private-profile follows-EXISTS gate on replying to private-profile authors (lands with the privacy feature).
- Mobile UI wiring (reply composer, reply list, `reply_count` rendering).
- Admin reply-moderation UI surfaces.
- Hard-delete path for the tombstone / cascade worker (the V8 RESTRICT FK is the point — the worker comes in a separate change).
- `user_blocks` exclusion on `reply_count` — deliberately omitted; per-viewer count leaks block state.

## Decisions

### 1. `author_id` FK is `RESTRICT`, not `CASCADE`

**Decision:** `post_replies.author_id REFERENCES users(id) ON DELETE RESTRICT`, mirroring V4 `posts.author_id`. V5 `user_blocks`, V6 `follows`, V7 `post_likes` all cascade on both sides — V8 deliberately breaks that pattern.

**Rationale:** Replies carry user-visible content (a 280-char string). Like `posts`, they are content that a tombstone / hard-delete worker must handle explicitly (tombstone-then-cascade) rather than letting a bare `DELETE FROM users` silently vaporize the content and its moderation state (`is_auto_hidden`, `reports` rows that reference the reply). The `RESTRICT` is a DB-level guard against the ops mistake of `DELETE FROM users WHERE id = ?` without the worker having run.

**Alternatives considered:**
- `ON DELETE CASCADE` on `author_id`: matches V5/V6/V7 and simplifies the worker. Rejected because replies carry content + moderation state that should be audited before vaporization (same reasoning as V4 `posts`).
- `ON DELETE SET NULL` on `author_id`: would require `author_id` to be nullable and require downstream queries to handle "orphaned" replies with no author. Rejected because every reader needs `author_id` (for the block-exclusion NOT-IN, for the `visible_users` JOIN, for the `is_auto_hidden OR author_id = :viewer` filter); making it nullable adds a null-branch to every read site for zero product value.

### 2. Soft-delete, not hard-delete

**Decision:** DELETE is `UPDATE post_replies SET deleted_at = NOW()`. The `deleted_at IS NULL` guard in the WHERE clause makes the UPDATE idempotent across repeat calls. No code path EVER executes `DELETE FROM post_replies` at runtime (the tombstone-and-cascade worker, out of scope here, will be the single hard-delete site).

**Rationale:** `docs/05-Implementation.md:734` is explicit: "Soft delete only (tombstone label on the parent post's reply list)." The UX reason is that reply threads with hard-deleted rows leave conversational gaps; the audit reason is that reports submitted against a reply must remain actionable after the author self-deletes.

The partial indexes filter `deleted_at IS NULL` so they stay lean; tombstoned rows never appear on the hot read path but remain available for moderation tooling and for the tombstone worker's eventual hard-delete.

### 3. Opaque 204 on DELETE — no distinction between "not yours" / "already tombstoned" / "never existed"

**Decision:** All three cases yield the same HTTP 204 empty body. The endpoint MUST NOT return 403 or 404.

**Rationale:** Distinguishing them leaks state. HTTP 403 on "not yours" tells a probing client that the reply exists and belongs to someone else (exposing the row). HTTP 404 on "already tombstoned" tells a client that a deletion happened. HTTP 404 on "never existed" tells a client nothing useful and adds a branch that is information-equivalent to 204. So: one 204, always. The `WHERE id = ? AND author_id = ? AND deleted_at IS NULL` guard means the UPDATE self-scopes to legitimate cases; all other paths are correctly no-ops.

**Alternative considered:**
- Return 404 on "never existed" for client-side debugging. Rejected — UUIDs are opaque; a client that sent a bad `reply_id` will see the reply missing from the list on the next GET and figure it out without the endpoint cooperating. The leak risk of the 403/404 variants outweighs the minor debugging convenience.

### 4. Opaque 404 on POST / GET for invisible parent — reuse the V7 `post_not_found` envelope

**Decision:** POST replies and GET replies resolve the parent post through the same `visible_posts` + bidirectional `user_blocks` NOT-IN pattern that `LikeService.resolveVisiblePost` uses. Any invisibility reason — missing, soft-deleted, auto-hidden, caller-blocked-author, author-blocked-caller — collapses to the single envelope `{ "error": { "code": "post_not_found" } }`. Never 403.

**Rationale:** This is the same privacy argument the V7 like endpoint made. A 403 on "the author blocked you" leaks the block. A 404 on everything makes the block indistinguishable from a deleted post. Reusing the V7 envelope (not inventing a new code) means clients already handle it.

`LikeService.resolveVisiblePost` is the named template for this — the reply service's equivalent method should share the same name shape and the same SQL literal discipline (lint-compliant `visible_posts` + both `user_blocks` NOT-IN subqueries).

### 5. `reply_count` on timelines uses `LEFT JOIN LATERAL` with shadow-ban JOIN, NOT viewer-block exclusion

**Decision:** Timeline reply counter is `LEFT JOIN LATERAL (SELECT COUNT(*) ... JOIN visible_users vu ... WHERE pr.post_id = p.id AND pr.deleted_at IS NULL) c ON TRUE`, projecting `c.n AS reply_count`. Shadow-banned repliers are excluded (shadow-ban parity); viewer-blocked repliers are NOT excluded from the counter.

**Rationale — why LATERAL, not correlated subquery in SELECT list:** A correlated `(SELECT COUNT(*) ... WHERE pr.post_id = p.id)` in the SELECT list would work functionally. LATERAL in a LEFT JOIN is equivalent but reads as the join shape the query actually performs, which makes the V8 delta legible against the V7 `LEFT JOIN post_likes` precedent (the timeline query is becoming "the outer row plus N at-most-one joins"). This is a style choice for query legibility; the Postgres planner handles both shapes identically.

**Rationale — why exclude shadow-banned repliers:** `docs/05-Implementation.md:1825` explicitly lists "counter aggregation (likes, replies): JOIN `visible_users` to exclude banned contributions." The V7 `likes/count` endpoint does this; the reply counter must do the same or shadow-bans leak through the numeric differential.

**Rationale — why NOT exclude viewer-blocked repliers:** Same privacy tradeoff as the V7 `likes/count`. If viewer A sees `reply_count = 10` and viewer B sees `reply_count = 7`, anyone who can compare two observations can infer that 3 replies are from users one of them has blocked. Block state is viewer-private; it must not manifest as a per-viewer count. The reply LIST endpoint DOES apply viewer-block exclusion (blocked-author replies hidden), so there's still a consistent UX for the viewer — they just don't see a count that leaks.

**Alternative considered:** denormalized `posts.reply_count` column incremented by trigger. Rejected — same rationale as V7's follower-count deferral: MVP computes at read time until we have evidence that the lateral count is hot enough to be a bottleneck. Triggers introduce write-path contention and retroactive-backfill ops work that isn't justified yet.

## Risks / Trade-offs

- **Risk:** The `LEFT JOIN LATERAL` reply counter runs a COUNT on every timeline fetch. At 30 posts per page with N replies each, this is 30 sub-queries per request.
  **Mitigation:** `post_replies_post_idx (post_id, created_at DESC) WHERE deleted_at IS NULL` keeps each COUNT at an index scan; Postgres handles 30 small index counts per request trivially at MVP scale. If Phase 2 benchmark (`docs/08-Roadmap-Risk.md` Phase 2 item 14, p95 <200ms timeline) shows regression, fall back to a denormalized counter in a later change.

- **Risk:** Shadow-banned repliers can still REPLY to posts (their INSERT succeeds — V8 does not add a shadow-ban write-guard), but their replies won't appear in anyone's LIST or counter.
  **Mitigation:** This is the documented shadow-ban semantic everywhere in the pipeline — the repliers don't realize they're shadow-banned, which is the point. No change needed.

- **Trade-off:** `reply_count` can include rows the viewer cannot see (blocked repliers' replies). A viewer might see "5 replies" but only 4 items in the list.
  **Mitigation:** Accepted for privacy. The discrepancy is rare (typical block lists are small) and the privacy gain (not leaking block state via count differential) is load-bearing.

- **Risk:** The `author_id RESTRICT` FK can surprise an operator doing a bare `DELETE FROM users` in a support-tooling path.
  **Mitigation:** This is the point — the surprise is the safety. The tombstone / cascade worker (separate change) is the only legitimate hard-delete path; until it lands, support tooling that hard-deletes users must remove replies first (via a soft-delete bulk UPDATE if discretion is needed, or a targeted hard-delete for the RESTRICT to pass).

- **Risk:** A future developer might re-order the timeline query's `LEFT JOIN LATERAL` relative to the `LEFT JOIN post_likes`, breaking the keyset-cursor guarantees.
  **Mitigation:** The spec's "Canonical query" requirements explicitly list "MUST NOT appear in ORDER BY" and "MUST NOT appear in the keyset predicate" for both joins. The existing `NearbyTimelineServiceTest` / `FollowingTimelineServiceTest` LEFT-JOIN cardinality invariants (7 liked of 35 → 35 returned) are extended to cover the LATERAL (200 replies across 35 posts → 35 returned).

- **Risk:** Lint fixture drift — a future developer refactors `JdbcPostReplyRepository` to split the block-exclusion NOT-INs across two Kotlin string-concatenation fragments, which the rule's multi-line concatenation handler may or may not recombine correctly.
  **Mitigation:** The positive-pass fixture embeds the canonical SINGLE-literal form as the authoritative shape. The existing `RawFromPostsRule` multi-line handling is inherited; no new machinery required.
