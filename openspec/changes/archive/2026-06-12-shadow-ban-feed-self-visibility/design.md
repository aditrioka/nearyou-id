# Design: shadow-ban-feed-self-visibility

## Context

V20 made `visible_posts` author-aware (shadow-ban + soft-delete filters) but kept it viewer-agnostic — by design (the V20 header says feed surfaces are deliberately viewer-agnostic and points self-visibility at the Repository own-content paths). The own-content exception (docs/05 § Shadow Ban) was never wired into the shared feeds or the engagement resolution paths, so V20 turned both into ban-detectability oracles. This change makes the consuming queries viewer-aware without touching the view.

## Decision 1 — UNION ALL two-arm query shape (not OR, not a view change)

Nearby and Global adopt:

```sql
FROM (
    (   -- arm 1: everyone else — shadow-ban-safe view + bidirectional block exclusion
        SELECT p.id, p.author_id, u.username AS author_username, u.display_name AS author_display_name,
               p.content, p.display_location, p.city_name, p.created_at
          FROM visible_posts p
          JOIN visible_users u ON u.id = p.author_id
         WHERE p.author_id <> :viewer_id
           AND <feed-structural filters>           -- ST_DWithin for Nearby; none for Global
           AND p.author_id NOT IN (SELECT blocked_id FROM user_blocks WHERE blocker_id = :viewer_id)
           AND p.author_id NOT IN (SELECT blocker_id FROM user_blocks WHERE blocked_id = :viewer_id)
           AND (p.created_at, p.id) < (:cursor_ts, :cursor_id)   -- omitted on the first page
         ORDER BY p.created_at DESC, p.id DESC
         LIMIT 31
    )
    UNION ALL
    (   -- arm 2: the viewer's own posts — own-content exception
        SELECT p.id, p.author_id, u.username, u.display_name,
               p.content, p.display_location, p.city_name, p.created_at
          FROM posts p
          JOIN users u ON u.id = p.author_id
         WHERE p.author_id = :viewer_id
           AND p.deleted_at IS NULL
           AND <feed-structural filters>
           AND (p.created_at, p.id) < (:cursor_ts, :cursor_id)
         ORDER BY p.created_at DESC, p.id DESC
         LIMIT 31
    )
) p
LEFT JOIN post_likes pl ...          -- unchanged V7 join, outer
LEFT JOIN LATERAL (reply counter) c  -- unchanged V8 join, outer
ORDER BY p.created_at DESC, p.id DESC
LIMIT 31
```

**Why not a single `WHERE (visible-predicates) OR p.author_id = :viewer` over raw `posts`?** The OR forces the whole query onto raw `posts` (losing the `visible_posts` chokepoint for the everyone-else arm) and gives the planner a disjunction it can only serve as one ordered scan with a residual OR filter — workable for Global, but for Nearby it pits the GIST spatial index against the OR (a `ST_DWithin AND (...) OR author_id = ...` predicate tree is not cleanly servable by `posts_nearby_cursor_idx`). The menu constraint for this item is explicit: "no de-indexing OR".

**Index/EXPLAIN reasoning for the chosen shape.** Each arm is independently index-serviceable with top-N early-exit:

- Arm 1 is byte-equivalent to the shipped query plus one cheap residual filter (`author_id <> :viewer`) — same plan as today: `posts_timeline_cursor_idx` (Global, keyset-ordered scan, LIMIT early-exit) / `posts_nearby_cursor_idx` GIST (Nearby) through the view, `deleted_at IS NULL` view predicate keeps the partial indexes eligible (02-H1).
- Arm 2 is an `author_id = :viewer` equality over `posts_author_idx (author_id, created_at DESC) WHERE deleted_at IS NULL` (shipped in V20, finding 02-M1) — an ordered index scan over ONE author's posts with LIMIT early-exit; the `id DESC` tiebreak and (for Nearby) the `ST_DWithin` filter are residual over a tiny row set.
- Each arm's own keyset predicate + `ORDER BY` + `LIMIT 31` (the page-size-31 probe convention; the repos keep the parameterized `LIMIT ?` = PAGE_SIZE + 1) bounds the outer input to ≤ 62 pre-sorted rows; the outer merge sort + `LIMIT 31` is O(62 log 62) — noise. The V7/V8 `post_likes` / reply-counter joins move OUTSIDE the union and run on the ≤ 62 merged rows (comparable to today's join workload); their cardinality invariants (at-most-one / exactly-one row per outer row) are unchanged. The scalar projections (`lat`/`lng` via `ST_Y`/`ST_X`, Nearby's `distance_m` via `ST_Distance`) live in the OUTER select, computed from the `display_location` column the arms project through — one projection site instead of two, and the binding order of the spatial parameters stays unambiguous.

**Why arms are disjoint** (`author_id <> :viewer` in arm 1): a non-banned viewer's own non-hidden posts would otherwise satisfy both arms and duplicate under `UNION ALL`; `UNION` (dedup) would force a sort/hash over both arms before the outer LIMIT and defeat the early-exit. Disjoint arms keep `UNION ALL` correct by construction. Consequence: ALL self rows come from arm 2 for every viewer, which is what makes the self-visibility behavior uniform (Decision 2) rather than ban-state-conditional — the query shape itself never branches on `is_shadow_banned`, so EXPLAIN, latency, and row provenance are identical for banned and normal users (no timing oracle).

## Decision 2 — Self-arm predicate: `author_id = :viewer AND deleted_at IS NULL` (auto-hide INCLUDED, soft-delete EXCLUDED)

The self-arm grants the author visibility of their own posts regardless of moderation state, with soft-delete as the only hide:

- **Shadow-ban (the driver)**: own posts visible — the illusion holds (docs/06 § Shadow Ban).
- **Auto-hide (pinned decision, was the open reconciliation point)**: own auto-hidden posts ARE visible to the author. Rationale: (a) auto-hide is a TRANSPARENT moderation action — the author is explicitly notified (`post_auto_hidden`: "Salah satu postingan kamu disembunyikan untuk ditinjau tim moderasi.", docs/03:170 + docs/05 notification catalog), so self-visibility leaks nothing; (b) the codebase already pins exactly this treatment one level down: the shipped reply-list query carries `(is_auto_hidden = FALSE OR author_id = :viewer)` and docs/05:476 prescribes "exclude replies where `is_auto_hidden = TRUE` unless the viewer is the author". Posts now get the same own-content treatment replies have had since V8. The alternative (self-arm keeps `is_auto_hidden = FALSE`) would make a reported post vanish from the author's feed while the notification tells them it was hidden — incoherent both ways.
- **Post soft-delete**: `deleted_at IS NULL` stays — the author does NOT see their own deleted posts (user-initiated deletes should look deleted; menu hard constraint).
- **Author (= viewer) soft-delete**: the visible arm's `u.deleted_at IS NULL` has no self-arm equivalent and needs none — the self-arm only matches `author_id = :viewer`, and the viewer holds a valid JWT for an existing account; a soft-deleted user cannot be the viewer. Stated here so reviewers don't flag the asymmetry.
- **Block exclusion on the self-arm: structurally N/A.** The self-arm returns only rows whose `author_id` equals the viewer; `user_blocks` carries a `blocker_id <> blocked_id` CHECK and the block endpoints reject self-blocks, so no `user_blocks` row can ever match a self row. The bidirectional NOT-IN stays untouched on the visible arm; `BlockExclusionJoinRule` continues to pass because each updated literal still carries all four required tokens (`visible_posts`/`posts`, `user_blocks`, `blocker_id =`, `blocked_id =`).
- **Spatial fuzzing unchanged**: both arms project and filter on `display_location` only — the author sees their own post at its fuzzed location, same as everyone else (no `actual_location` anywhere in these paths, per the jitter invariant).

## Decision 3 — Following timeline: NO self-arm (deliberate no-op)

Self-follow is impossible at two layers (`follows_no_self_follow CHECK (follower_id <> followee_id)` in V6 + the app-layer 400 `cannot_follow_self`), so `author_id IN (SELECT followee_id FROM follows WHERE follower_id = :viewer)` can never match the viewer's own posts — for ANY user. The correct illusion standard is "the feed shows exactly what it would show if the user weren't banned", and for Following that is: own posts absent. Adding a self-arm would INVERT the oracle (banned users would see own posts where normal users see none). Issue #210's body lists all three feeds; this is the one place the sketch needed correcting — pinned as an explicit spec requirement + scenario so the decision survives review and future query edits.

## Decision 4 — Engagement paths (issue #210 comment scope)

1. **Both `resolveVisiblePost` literals** (like + reply repos — spec-pinned as shape-identical) gain:

```sql
SELECT p.id FROM visible_posts p
 WHERE p.id = :post_id
   AND p.author_id NOT IN (SELECT blocked_id FROM user_blocks WHERE blocker_id = :viewer)
   AND p.author_id NOT IN (SELECT blocker_id FROM user_blocks WHERE blocked_id = :viewer)
UNION ALL
SELECT p.id FROM posts p
 WHERE p.id = :post_id AND p.author_id = :viewer AND p.deleted_at IS NULL
LIMIT 1
```

Point lookup on the PK in both arms — no index concern. Disjointness is unnecessary here (`LIMIT 1` over identical `id` values); the arms may overlap for a normal author's own visible post.

2. **Reply-list author bypass**: `JOIN visible_users vu` → `LEFT JOIN visible_users vu` plus `(vu.id IS NOT NULL OR pr.author_id = :viewer)` in the WHERE. Without this, the fixed 404 just moves the oracle: a shadow-banned user's reply returns 201 but never appears in the thread they can now read. This mirrors — in shape and rationale — the auto-hide author bypass already on the adjacent predicate of the same literal. Other viewers' results are unchanged (`vu.id IS NOT NULL` is exactly the old INNER JOIN semantics for non-author rows). Included per the issue comment's oracle enumeration ("reply to their own post", "read their own post's reply thread"); called out as the one delta beyond the comment's literal `resolveVisiblePost` sketch.

## Decision 5 — Query-layer only; no migration; view stays viewer-agnostic

A viewer-aware view is impossible (views take no parameters) and a `current_setting()`-based hack would smuggle request state into the DB session — rejected. V20's "feed surfaces are deliberately viewer-agnostic" header comment stays true for the VIEW; the viewer-awareness lives in the consuming queries. V1–V20 are checksum-immutable and untouched; no new migration is needed because `posts_author_idx` (V20) already serves the self-arm.

## Decision 6 — Lint mechanics (no rule changes) and the literal-structure pin

- `@AllowRawPostsRead("<reason>")` goes on each SQL-holding declaration (the rule walks UP the `KtAnnotated` ancestor chain from the string literal — the annotation must enclose the literal; the shipped precedent is `JdbcPostLikeRepository.loadPostAuthorAndExcerpt` for functions and `JdbcUserProfileReader.SQL_SELF` — `backend/ktor/.../user/` — for companion constants). Each reason names the own-content self-arm and this change.
- **Literal-structure pin (load-bearing — review finding):** `BlockExclusionJoinRule.combinedTextAndLeftmost()` merges only `+`-concatenation chains; `buildString { append(...) }` arguments are independent string templates checked in isolation. Today's feed queries pass because the single token-bearing literal carries all four tokens and the appended cursor/LIMIT fragments reference no protected table. The two-arm shape MUST therefore keep the self-arm (`FROM posts` + `JOIN users`, zero block tokens on its own) in the SAME string template as the visible arm's four tokens. Concretely: build each feed query as ONE template with the conditional keyset fragment interpolated per arm (`${cursorPredicate}` — interpolation placeholders are kept verbatim in `expression.text`, so the static tokens still satisfy the single-literal check), NOT as a separate `append` per arm. `resolveVisiblePost` is unaffected (already a single `trimIndent` template; the UNION ALL arm extends it in place). With this structure no `@AllowMissingBlockJoin` is needed anywhere.
- Raw `JOIN users` in the self-arm is outside `RawFromPostsRule`'s pattern (it matches `posts` only) and inside `BlockExclusionJoinRule`'s protected set — satisfied by the same four tokens in the same literal.
- NEITHER Detekt rule is modified, and no allowlist path/regex is widened. Note the annotation's cost: `@AllowRawPostsRead` suppresses the rule for the whole declaration, so the visible arm's `FROM visible_posts` loses automated protection in the two timeline repos — compensated by the spec scenarios pinning "the visible arm reads `FROM visible_posts`; the only raw `posts`/`users` references are the self arm" via literal-inspection tests.

## Deliberately unchanged (reviewers: these are decisions, not gaps)

- **Viewer-independent aggregates stay shadow-ban-filtered for everyone**: the feeds' `reply_count` LATERAL counter and `GET /likes/count` JOIN `visible_users` and therefore exclude a shadow-banned user's own likes/replies from counters THEY see. Making them viewer-aware would make the public counts vary per viewer — exactly the block-state/ban-state leak the post-likes "Count endpoint does NOT apply viewer-block exclusion" requirement and post-replies Decision 5 forbid. Residual oracle: a shadow-banned user's own engagement never increments public counters; accepted (counter deltas are far weaker signals than vanished posts, and the alternative leaks ban state to OTHER viewers' clients via per-viewer counts). One newly REACHABLE combination is pinned by scenario so a future "fix" doesn't go the forbidden viewer-aware-count direction: a shadow-banned author likes their own post (now 204) → the feeds show `liked_by_viewer = true` (raw caller-scoped `post_likes` join) while the count stays 0 (`visible_users`-filtered) — for everyone, including the author.
- **Search**: a Premium shadow-banned user also does not see their own posts in `GET /api/v1/search` — and that is ALREADY a ratified spec decision, not a gap: `premium-search` scenario "Shadow-banned viewer searches their own posts" pins the exclusion as intentional ("search is a discovery surface, not a self-archive surface"). No change, no follow-up issue — the decision layer already covers it.
- **`liked_by_viewer`** already reads raw `post_likes` (PK join, caller-scoped) — correct for self rows with no change.
- **Profile own-content path, notifications, chat**: already correct or out of scope per the issue.

## Standards conformance (docs/11)

- §3.1 layering untouched: SQL changes stay in the `infra/supabase` repositories; services/routes/DTOs unchanged.
- §3.2 JDBC discipline untouched: same pool-bounded dispatcher call sites; one statement per call as today.
- §3.3 cursor-pagination contract preserved (keyset both arms; no OFFSET).
- Pattern Registry: no new pattern — the two-arm own-content shape is the existing own-content-exception pattern applied at the query layer; the canonical docs/05 SQL blocks are updated in the same PR (anti-drift rule from finding 02-M3).

## Test strategy

DB-tagged kotest specs (service-container Postgres, existing timeline/engagement test style):

- Per feed (Nearby, Global): shadow-banned author sees own post; a second user does NOT; un-shadow-ban restores normal behavior; author does NOT see own soft-deleted post; author DOES see own auto-hidden post while a second user does not; cursor pagination correct when own-shadow-banned posts interleave across the page-30 boundary (no dup, no gap, own posts on BOTH pages so the interleave genuinely crosses the boundary); cardinality invariants hold on self rows (`liked_by_viewer`, `reply_count` populated); `reply_count` stays viewer-independent on self rows; literal-inspection tests pin "visible arm reads `FROM visible_posts`; raw `posts`/`users` only in the self arm" (existing source-scan precedent: `ReplyEndpointsTest`).
- Following: shadow-banned viewer's own posts absent (parity scenario pinning Decision 3) + a literal scan pinning no `UNION ALL` / no self-arm in the Following SQL.
- Engagement: shadow-banned author can `POST /like` own post (204) with `liked_by_viewer = true` in feeds while the count stays `visible_users`-filtered, read own `likes/count` (200), `POST /replies` on own post (201), reply to own AUTO-HIDDEN post (201), `GET /replies` on own post (200, own replies visible); a second user gets 404 on the same shadow-banned-author post; author's own soft-deleted post still 404s for the author on BOTH the like and reply paths; 404 body byte-identical across all invisibility causes including the V20-added shadow-banned-author cause.
- visible-posts-view "view stays viewer-agnostic" scenario: satisfied by the existing `MigrationV20SmokeTest` full-shape pin (verify; extend only if the pin is narrower than byte-equivalence).
