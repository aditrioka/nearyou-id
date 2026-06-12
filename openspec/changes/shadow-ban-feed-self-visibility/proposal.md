# Proposal: shadow-ban-feed-self-visibility

## Why

V20 redefined `visible_posts` to exclude shadow-banned authors' posts (2026-06-10 holistic audit, finding 02-C1) — correctly, for every OTHER viewer. But the view is viewer-agnostic, so the exclusion also hits the author themselves: a shadow-banned user opens Nearby or Global and their own posts are gone. That is an instant ban-detectability oracle that defeats the shadow-ban illusion (docs/06 § Shadow Ban: "all actions succeed from the banned user's perspective"; docs/05 § Own-content exception: "a banned user sees their own content normally"). Operator-approved as issue [#210](https://github.com/aditrioka/nearyou-id/issues/210).

The 2026-06-11 backend review widened the same finding to the engagement paths (issue #210 comment, same root cause, explicitly "same change"): `JdbcPostLikeRepository.resolveVisiblePost` and `JdbcPostReplyRepository.resolveVisiblePost` read `visible_posts`, so a shadow-banned user now gets `404 post_not_found` when they like their own post, reply to their own post, read their own post's reply thread, or read their own post's like count — while their profile still shows the post. Additionally, the reply-list query's `JOIN visible_users` hides a shadow-banned user's OWN replies from them in any thread they can read — so even after the 404 is fixed, "reply succeeds (201) → thread doesn't show my reply" keeps the oracle alive.

## What Changes

- **Nearby + Global timeline queries become viewer-aware** via an index-preserving `UNION ALL` two-arm shape: arm 1 is the existing `visible_posts` + `visible_users` + bidirectional-block query restricted to `author_id <> :viewer`; arm 2 is a tightly-scoped own-content self-arm over raw `posts` (`author_id = :viewer AND deleted_at IS NULL`, same feed-structural filters, identity from raw `users`). Each arm carries its own keyset predicate + `ORDER BY` + `LIMIT`, so the V4 partial cursor indexes (and `posts_author_idx` for the self-arm) stay usable; the outer query merges ≤ 2 × 31 rows. Wire shape unchanged — no mobile change.
- **Following timeline is explicitly pinned as UNCHANGED**: self-follow is impossible (`follows_no_self_follow` CHECK + app-layer 400 `cannot_follow_self`), so own posts never appear in Following for ANY user — banned or not. Adding a self-arm there would break the illusion in the opposite direction (the banned user would see own posts where a normal user sees none).
- **Self-visibility scope** (pinned, not implicit): the self-arm keeps `deleted_at IS NULL` — the author does NOT regain visibility of their own soft-deleted posts. The self-arm deliberately INCLUDES the author's own auto-hidden posts: auto-hide is already transparent to the author (the `post_auto_hidden` notification, docs/03:170), and the shipped reply-list query + docs/05 §476 already prescribe exactly this author bypass for auto-hidden replies (`is_auto_hidden = FALSE OR author_id = :viewer`). Posts get the same own-content treatment replies already have.
- **Engagement-path self-arms** (issue #210 comment): both `resolveVisiblePost` literals (like + reply repositories) gain a `UNION ALL` self-arm (`p.id = :post_id AND p.author_id = :viewer AND p.deleted_at IS NULL`), so a shadow-banned (or auto-hidden-post) author can like their own post, reply to it, and read its reply thread + like count. All other viewers keep the constant opaque 404. The reply-list query's `JOIN visible_users` becomes `LEFT JOIN` + `(vu.id IS NOT NULL OR pr.author_id = :viewer)` — the author always sees their OWN replies, mirroring the existing auto-hide author bypass in the same WHERE clause.
- **Deliberately unchanged** (spec-pinned privacy tradeoffs, documented in design.md): the viewer-independent aggregates (`reply_count` LATERAL counter, `GET /likes/count`) keep excluding shadow-banned contributors for everyone including the contributor; the `visible_posts` view itself stays viewer-agnostic (no migration, V20 untouched); search self-visibility is out of scope (follow-up issue filed at apply time).
- **docs/05 canonical SQL blocks updated** to the shipped two-arm shape (Nearby + Global) plus a Following no-self-arm rationale note and an amended § Shadow Ban own-content paragraph naming the feed/engagement self-arms.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `nearby-timeline`: canonical query gains the own-content self-arm (UNION ALL shape); scenarios for author self-visibility (shadow-banned + auto-hidden), other-viewer exclusion unchanged, soft-deleted own posts stay hidden, pagination/cardinality unchanged.
- `global-timeline`: same delta as nearby.
- `following-timeline`: explicit requirement that Following carries NO self-arm and a shadow-banned viewer's own posts do not appear (structural parity with normal users — self-follow is impossible).
- `post-likes`: the 404-resolution SELECT (shared by `POST /like` and `GET /likes/count`) gains the self-arm; the opaque-404 contract is restated as applying to NON-author callers, with the V20-added fourth invisibility cause (author shadow-banned) now enumerated.
- `post-replies`: POST + GET parent-post visibility resolution gain the self-arm; the reply-list query gains the shadow-ban author bypass (`LEFT JOIN visible_users` + OR-author predicate) mirroring the existing auto-hide author bypass.
- `visible-posts-view`: the own-content-exception note is updated — the exception is now carried by the Repository own-content paths AND the new viewer-aware self-arms; the view itself remains viewer-agnostic.

## Impact

- **Code**: `JdbcPostsTimelineRepository.kt` + `JdbcPostsGlobalRepository.kt` (two-arm SQL), `JdbcPostLikeRepository.kt` + `JdbcPostReplyRepository.kt` (`resolveVisiblePost` self-arm; reply-list author bypass) — all in `infra/supabase`. No service/route/DTO changes; wire shape unchanged; NOTHING under `:mobile:`. No Flyway migration (query-layer only; V1–V20 untouched).
- **Lint**: the self-arms read raw `posts` (+ raw `users` for self-identity) — each SQL-holding declaration gets `@AllowRawPostsRead("<justification>")` per the established own-content allowlist mechanism. `RawFromPostsRule` itself is NOT weakened. `BlockExclusionJoinRule` passes unchanged: every literal keeps the four required tokens via the visible arm; the self-arm only ever returns the viewer's own rows, where block exclusion is structurally N/A (`user_blocks` CHECK forbids self-blocks).
- **Tests**: DB-tagged kotest additions to `NearbyTimelineServiceTest` / `GlobalTimelineServiceTest` / `FollowingTimelineServiceTest` / `LikeEndpointsTest`-family / `ReplyEndpointsTest` pinning: author self-visibility per feed, second-viewer exclusion, un-shadow-ban restore, cursor pagination across a page boundary with interleaved own-shadow-banned posts, soft-deleted own posts hidden, engagement self-paths (like/reply/thread/count on own post while shadow-banned), own-reply visibility in threads.
- **Issues**: closes [#210](https://github.com/aditrioka/nearyou-id/issues/210).
