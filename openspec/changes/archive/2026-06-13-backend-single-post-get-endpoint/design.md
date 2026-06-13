## Context

The backend exposes no by-id post read. Posts reach the client only as feed rows (`GET /api/v1/timeline/{nearby,following,global}`) or as the `PostDetailRoute` nav payload captured at card-tap. The deferred `mobile-notifications-deep-link-targets` change needs to open a post from a notification, where there is no card to source the header — so it needs `GET /api/v1/posts/{post_id}`. Tracked by issue [#202](https://github.com/aditrioka/nearyou-id/issues/202); the `mobile-post-detail` spec § "By-id post fetch ... deferred" is the deferral record.

The directly analogous shipped capability is `user-profile-read` (`GET /api/v1/users/{user_id}`): Bearer JWT, `visible_*`-view read, bidirectional `user_blocks` exclusion, one constant direction-less `404`, no-PII, malformed-UUID `400`, unauth `401`. This change is the post-side twin, with one important structural difference around shadow-ban self-visibility (Decision 1).

## Goals / Non-Goals

**Goals:**

- A Bearer-JWT-gated `GET /api/v1/posts/{post_id}` returning a single post's **no-PII display projection** for one viewer.
- Shadow-ban-safe + bidirectional-block-aware + auto-hide-safe, with a shadow-banned author still able to read their own post.
- One opaque, byte-identical `404 post_not_found` for every "you can't see this" cause; `400` for malformed id; `401` for unauth.
- Wire shape that matches the shipped timeline post DTO casing so the mobile DTO derived from it parses.

**Non-Goals:**

- No mobile consumer (`mobile-notifications-deep-link-targets` is a separate downstream change).
- No reply-by-id, no replies infinite-scroll (tracked by issue [#188](https://github.com/aditrioka/nearyou-id/issues/188)).
- No distance computation in v1 (no `lat`/`lng` query param — see Decision 3).
- No Flyway migration, no schema change, no new index.

## Decisions

### Decision 1 — Mirror `resolveVisiblePost`'s two-arm gate, extended to a full projection (NOT a single `visible_posts` read)

`visible_posts` is **viewer-agnostic and excludes shadow-banned authors' posts entirely** — including from the author themselves (`visible-posts-view` spec § "View excludes shadow-banned and soft-deleted authors' posts": "The own-content exception ... is NOT carried by this viewer-agnostic view"). Author self-visibility is carried by the consuming layer via a `UNION ALL` own-content raw-`posts` arm, exactly as the shipped `resolveVisiblePost` (`JdbcPostLikeRepository` / `JdbcPostReplyRepository`) does for the like/reply paths:

```sql
SELECT <projection> FROM visible_posts p [JOIN visible_users a ON a.id = p.author_id]
 WHERE p.id = ?
   AND p.author_id NOT IN (SELECT blocked_id FROM user_blocks WHERE blocker_id = ?)  -- viewer blocked author
   AND p.author_id NOT IN (SELECT blocker_id FROM user_blocks WHERE blocked_id = ?)  -- author blocked viewer
UNION ALL
SELECT <projection> FROM posts p [JOIN users a ON a.id = p.author_id]                -- own-content self arm
 WHERE p.id = ? AND p.author_id = ? AND p.deleted_at IS NULL
 LIMIT 1
```

`resolveVisiblePost` returns only the post id (it is a visibility *gate*); this endpoint needs the full projection, so it is a **new repository method** (`SinglePostRepository.findById(viewerId, postId): SinglePostRow?`) shaped on the same two arms but selecting `content`, author identity, `created_at`, `liked_by_viewer`, `reply_count`. Author identity differs per arm: the `visible_posts` arm joins `visible_users` (the author is guaranteed non-shadow-banned, so present), while the own-content arm sources identity from raw `users` for the viewer's own row (a shadow-banned author is absent from `visible_users` — the own-content exception). `liked_by_viewer` (the timelines' PK-scoped `LEFT JOIN post_likes` viewer check — `(post_id, user_id)` is the PK, so ≤1 match) and `reply_count` reuse the shipped timeline projection's computations (so `reply_count` keeps the documented non-viewer-block-filtered counter behavior).

- **Alternative rejected — single `visible_posts` read:** would `404` a shadow-banned author on their own post, breaking the very deep-link case (opening *your own* post from a "someone replied to you" notification) and silently diverging from the like/reply visibility contract. The two-arm gate is mandatory, not optional.

### Decision 2 — No-PII projection (omit author UUID + all coordinates), more restrictive than the timeline wire

The timeline post DTOs expose `authorUserId` and `display_location`-fuzzed `latitude`/`longitude`. This projection omits all three. Rationale: issue #202 specifies "NO author PII", and the `PostDetailRoute` consumer (the existing post-header model) already declares only display identity (`authorUsername` + `authorDisplayName`) and **no** coordinates / author UUID ("raw coordinates MUST NOT enter the serialized back stack"). Matching the consumer's field set keeps the wire minimal and leak-safe.

- **Alternative rejected — mirror the timeline post wire verbatim** (`authorUserId` + fuzzed coords): maximizes wire uniformity but contradicts the issue's explicit no-PII requirement and the `PostDetailRoute` discipline; the deep-link header needs none of those fields.
- **Forward note:** the profile screen now exists (shipped 2026-06-13), so a future change *could* add `authorUserId` to make the header's author tappable. Deferred and called out in Open Questions — v1 stays faithful to "no author PII".

### Decision 3 — `distanceM` is `Double?`, always null in v1 (no `lat`/`lng` query param)

A by-id read from a notification has no viewer-location context, so distance is undefined. The field is kept (`Double?`, null) to match the `PostDetailRoute.distanceM: Double?` shape and to leave room for a future optional `lat`/`lng` query param. Under the app-wide `ContentNegotiation` `explicitNulls = false`, a null `distanceM` is omitted from the body; the consumer treats absent and null identically.

- **Alternative rejected — accept `lat`/`lng` now:** adds envelope-validation + rate-limit surface for a value the only v1 consumer (notification deep-link) cannot supply. Deferred.

### Decision 4 — Constant byte-identical `404` via `respondText`

Mirror `user-profile-read`: a single `PostNotFoundException` → `respondText(POST_NOT_FOUND_BODY, ContentType.Application.Json, NotFound)` with `POST_NOT_FOUND_BODY = {"error":{"code":"post_not_found"}}`, NOT the negotiated `respond` (so the body stays byte-identical regardless of serializer settings). Unknown / soft-deleted / shadow-banned-author / auto-hidden / blocked-either-direction all funnel through the same `null`→exception path. `post_not_found` is already the established code for the like/reply paths' resolution miss (`post-likes` spec), so this reuses the existing vocabulary.

- **Alternative rejected — distinct codes per cause:** leaks block existence and direction, and post existence to a blocked party.

### Decision 5 — New `PostReadService` + `SinglePostRepository` (co-located in `infra/supabase`), not overloading existing types

Add a read-only `PostReadService` (`backend/ktor/.../post/`) + a `SinglePostRepository` interface + `SinglePostRow` + `JdbcSinglePostRepository` impl **co-located in `infra/supabase/.../repo/`**, mirroring the post-read precedent (`PostsTimelineRepository`/`PostsGlobalRepository`, which co-locate interface + `TimelineRow` + JDBC impl there). The route registers as a new `singlePostRoutes(service)` Application extension in the `post` package (mirroring `TimelineRoutes`' per-concern extension functions) and is wired at the manual-construction site in `Application.kt`. The service mirrors `UserProfileService` (maps the row to the wire DTO; `null` → `PostNotFoundException`).

- **Alternative rejected — interface in `:core:data`** (the `PostLikeRepository` shape): that is the engagement-repo pattern; the three shipped post-**read** repos all co-locate interface + Row + impl in `:infra:supabase`, so following them is the closer precedent and avoids a split-placement second pattern for the same concern.
- **Alternative rejected — extend `CreatePostService` / `PostLikeRepository`:** `CreatePostService` is write-only; `resolveVisiblePost` lives on the engagement repos as a *gate*, not a projection. A dedicated read service/repo keeps the layering clean and the projection query co-located with the post-read family.

### Decision 6 — No dedicated rate limiter in v1

The timeline reads have `TimelineReadRateLimiter` (freemium quota); the like/reply paths have their own limiters. This point read mirrors `user-profile-read`, which has no dedicated limiter — it is a cheap, auth-gated single-row lookup. v1 ships none; abuse is covered by the auth boundary. Flagged for the Phase D security lens; revisit with a follow-up if a by-id enumeration concern surfaces.

## Standards conformance (docs/11 Pattern Registry)

This change builds **only on existing Pattern-Registry patterns and introduces no new one**:

- **Backend layering** — Route → Service → Repository (Route + Service in `:backend:ktor`; the `SinglePostRepository` interface + `SinglePostRow` + JDBC impl co-located in `:infra:supabase`, matching the shipped post-read repos `PostsTimelineRepository`/`PostsGlobalRepository`), per docs/11 § backend layering. The service shape mirrors `user-profile-read`.
- **JDBC discipline** — `PreparedStatement` with bound params, `dataSource.connection.use { }` / `.use { }` on statements and result sets, no string interpolation of values, per docs/11 § JDBC. Identical to `JdbcPostLikeRepository.resolveVisiblePost`.
- **Invariant conformance** — `visible_posts` for the other-viewer arm (shadow-ban); bidirectional `user_blocks` NOT-IN with the `blocker_id`/`blocked_id` token pair (block-exclusion, scenario-guarded since `visible_posts` does not trip `BlockExclusionJoinRule`); `@AllowRawPostsRead` on the own-content arm (same allowlist annotation + rationale shape as `resolveVisiblePost`); no coordinates on the wire (spatial fuzzing / no-PII); `/api/v1` versioning.

No deviation from any listed pattern → **no docs/11 § Pattern Registry amendment is required** in this PR.

## Risks / Trade-offs

- **Own-content arm identity source** → the projection must source author identity from raw `users` on the own-content arm (a shadow-banned author is absent from `visible_users`); the scenarios "shadow-banned author reads own post → 200 with identity" are the guard. Mitigation: the spec scenario asserts the 200 with identity; the impl mirrors the timeline self-arm precedent.
- **Block-exclusion not linter-enforced** → because the read goes through `visible_posts`, `BlockExclusionJoinRule` is inert on this path (same as `user-profile-read`); the both-direction `404` scenarios are the only enforcement. Mitigation: integration tests for both block directions + a byte-identical-404 assertion.
- **Wire-casing drift** → a client DTO derived from a snake_case spec example would silently fail to parse the mixed-case wire (PR #128 precedent). Mitigation: the spec pins the exact mixed-case keys and a negative-guard scenario; the DTO matches `TimelineRoutes.kt`, not the spec's prose.
- **CI connection budget** → a new DB-tagged `*RoutesTest` that leaks a HikariPool can tip CI Postgres over `max_connections` and fail unrelated specs. Mitigation: `autoClose(hikari())` + `maximumPoolSize = 2` (the established per-spec pool rule).

## Migration Plan

No Flyway migration, no schema change, no index. Additive route only — deploy is a normal Cloud Run rollout; rollback is reverting the route (no data/state to unwind). The endpoint reads existing objects (`visible_posts`, `posts`, `user_blocks`, `post_likes`, reply counts) already present since V8/V20.

## Open Questions

- **Author tappability** — should the projection include `authorUserId` so the future notification-deep-link header can make the author tappable (the profile screen now exists)? v1 says no (faithful to issue #202's "no author PII"); a follow-up can add it when `mobile-notifications-deep-link-targets` actually wires author→profile navigation. Surfaced for the Phase D review to confirm.
- **Rate limiting** — confirm a point-read by-id needs no dedicated limiter in v1 (Decision 6). Security-lens call.
