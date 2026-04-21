## Why

`nearby-timeline-with-blocks` (archived 2026-04-21) shipped V5 `user_blocks`, the Nearby read path, and the `BlockExclusionJoinRule` lint — but explicitly deferred the Following timeline (needs `follows`) and follow-cascade-on-block (needs `follows` to cascade against). Both blockers resolve with one schema (V6) + one endpoint + one four-line addition to the existing block flow. The same schema unlocks Phase 2 item 1 (follow/unfollow social features), so shipping it now is strictly upstream of both the Following read path and the social graph. See `docs/08-Roadmap-Risk.md:87` (Phase 1 item 15, Following) and `docs/08-Roadmap-Risk.md:88` (Phase 1 item 16, follow-cascade bullet).

## What Changes

- Add V6 migration `V6__follows.sql`: `follows (follower_id, followee_id, created_at, PRIMARY KEY (follower_id, followee_id), CHECK (follower_id <> followee_id))` + both directional indexes (`follows_follower_idx (follower_id, created_at DESC)`, `follows_followee_idx (followee_id, created_at DESC)`) + dual `ON DELETE CASCADE` FKs to `users(id)`. Verbatim from `docs/05-Implementation.md` §669–687.
- Add `POST /api/v1/follows/{user_id}` — Bearer JWT required, idempotent 204, rejects self-follow with `400 cannot_follow_self`, rejects unknown target with `404 user_not_found`, rejects if either direction of `user_blocks` exists between caller and target with `409 follow_blocked`. Implemented as `INSERT ... ON CONFLICT (follower_id, followee_id) DO NOTHING` after the block-state check.
- Add `DELETE /api/v1/follows/{user_id}` — Bearer JWT required, idempotent 204 whether or not a row was removed.
- Add `GET /api/v1/users/{user_id}/followers?cursor=` and `GET /api/v1/users/{user_id}/following?cursor=` — Bearer JWT required, keyset pagination on `(created_at DESC, <other_side_id> DESC)`, per-page cap 30, bidirectional `user_blocks` exclusion against the viewer (not against the profile owner), response shape `{ users: [{ user_id, created_at }], next_cursor }`. A viewer sees neither people the viewer has blocked, nor people who have blocked the viewer, even if those rows exist in the profile owner's follower/following list.
- Add `GET /api/v1/timeline/following?cursor=` — Bearer JWT required, canonical Following query verbatim from `docs/05-Implementation.md` §1057–1067: `FROM visible_posts` joined on `follows` where `follower_id = :viewer`, with bidirectional `user_blocks` NOT-IN subqueries, keyset on `(created_at DESC, id DESC)`, `LIMIT 31`, per-page cap 30. Response shape: identical to `nearby-timeline` **minus** `distance_m` and with no radius/envelope validation (no geo params). Malformed cursor → `400 invalid_cursor`.
- Extend `BlockService.block()` to delete `follows` rows in BOTH directions inside the same transaction as the `user_blocks` INSERT. The `docs/05-Implementation.md` §1286–1300 flow is now fully implemented.
- Update `BlockExclusionJoinRule` KDoc (NO behavior change) to document why `follows` is NOT in the protected-tables list: `follows` carries relationship state, not user-visible content, and its rows are always caller-scoped (`follower_id = :viewer` or `followee_id = :viewer`). Content surfaced via `follows` (the Following timeline) is already block-excluded at the `visible_posts` join.

## Capabilities

### New Capabilities
- `follow-system`: HTTP contract for `POST/DELETE /api/v1/follows/{user_id}` + `GET /api/v1/users/{id}/followers` + `GET /api/v1/users/{id}/following`; the `follows` table schema, constraints, directional indexes, dual cascade; self-follow prevention (app + DB); mutual-block prevention (app-layer read against `user_blocks` before INSERT); idempotency semantics; response shapes; follower/following list block-exclusion-from-the-viewer invariant.
- `following-timeline`: HTTP contract for `GET /api/v1/timeline/following`; auth; keyset cursor format (identical to `nearby-timeline`); canonical query requirement (`FROM visible_posts` + `follows` EXISTS/IN + bidirectional `user_blocks` NOT-IN); per-page cap; ordering invariant; response shape (posts minus `distance_m`); integration test coverage.

### Modified Capabilities
- `user-blocking`: `BlockService.block()` now executes `user_blocks` INSERT + bidirectional `follows` DELETE in one transaction; the "future follow-cascade hook documented" requirement is superseded by a new "follow-cascade enforced at block time" requirement. Spec deltas document the transactional guarantee, the bidirectional DELETE semantics, and the new test scenario. `GET /api/v1/blocks` is unchanged.
- `users-schema`: V6 adds the `follows` table referencing `users(id)` twice with `ON DELETE CASCADE`. Spec extends the list of tables that cascade on user hard-delete.
- `migration-pipeline`: V6 lands; the V5 "first migration with joint V3+V4 dependency" precedent now has a sibling — V6 depends on V3 (`users`) only, but enforces a runtime coupling to V5 via the block-state check in the follow flow. Spec documents this as a different dependency shape (schema-level vs. code-level) so future migrations record their own.
- `visible-posts-view`: the list of consumers required to bidirectionally join `user_blocks` grows to include `following-timeline`. Spec note remains: the view alone is insufficient for any authenticated read path.
- `block-exclusion-lint`: spec gains a non-normative note documenting why `follows` is deliberately NOT a protected table (carries relationship, not content; already caller-scoped). No behavior change; no new rule. Prevents drift where a future author thinks `follows` was an oversight and adds it.

## Impact

- **Code**: new `backend/ktor/.../follow/FollowService.kt` + `FollowRoutes.kt`; new `UserFollowsRepository` interface in `:core:data` + `JdbcUserFollowsRepository` in `:infra:supabase`; new `backend/ktor/.../timeline/FollowingTimelineService.kt`; `TimelineRoutes.kt` extended with the `/following` route; new `UserRoutes.kt` (or reuse existing user-module routes file) for `/users/{id}/followers|following`; `BlockService.block()` modified to wrap INSERT + dual DELETE in a transaction; `BlockExclusionJoinRule.kt` KDoc updated.
- **Schema**: new `backend/ktor/src/main/resources/db/migration/V6__follows.sql`. No changes to V1–V5 (immutable; already applied).
- **APIs**: five new endpoints — `POST /api/v1/follows/{user_id}`, `DELETE /api/v1/follows/{user_id}`, `GET /api/v1/users/{user_id}/followers`, `GET /api/v1/users/{user_id}/following`, `GET /api/v1/timeline/following`. All require Bearer JWT (existing `auth-jwt` capability). Error codes: `400 cannot_follow_self`, `400 invalid_cursor`, `404 user_not_found`, `409 follow_blocked`.
- **Dependencies**: none new. PostGIS not touched. Flyway, Ktor JWT, PostgreSQL, JDBC — all already wired.
- **Out of scope (explicit)**:
  - Global timeline (still waits on `admin_regions` polygon reverse-geocoding).
  - `notifications` table writes for new-follower push (Phase 2 item 5 — needs `notifications` schema, not yet migrated).
  - Denormalized follower/followee counts on `users` (MVP computes at read time; cached counters defer to a dedicated change if read pressure warrants).
  - Mute-without-block (not in product spec).
  - Redis rate limits on follow churn (`docs/05-Implementation.md` §687 documents 50/hour; enforcement waits on Phase 1 item 24's rate-limit change).
  - Attestation gate on follow endpoints.
  - Mobile UI wiring for follow/unfollow/follower-list/following-list/Following timeline.
  - Private-profile `follows` EXISTS gate on profile visibility (`docs/05-Implementation.md` §1124) — lands with the private-profile feature.
  - Admin-triggered force-unfollow; admin-side follow-list views.
  - Following-timeline session/hour caps (same deferral as Nearby — waits on the Redis change).
