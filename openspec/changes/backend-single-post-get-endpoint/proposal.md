## Why

There is no `GET /api/v1/posts/{id}` single-post endpoint — only `POST /api/v1/posts` plus the post-scoped like/reply sub-resources. Every surface that shows a post today (Nearby/Following/Global timelines, post-detail) sources the post header from a feed card or nav-arg payload. The deferred `mobile-notifications-deep-link-targets` change has no such source: opening a post (or its reply) from a notification means there is no feed card to render the header from. A by-id read is the missing backend dependency. Tracked by GitHub issue [#202](https://github.com/aditrioka/nearyou-id/issues/202) (`follow-up` + `backend` + `promoted`), discovered during `mobile-post-detail-screen` and recorded in `openspec/specs/mobile-post-detail/spec.md` § "By-id post fetch and replies infinite-scroll are deferred".

## What Changes

- Add `GET /api/v1/posts/{id}` (Bearer JWT via `AUTH_PROVIDER_USER`) returning a single post's **no-PII display projection** for one viewer.
- The read is shadow-ban-safe and bidirectional-block-aware: it mirrors the shipped `resolveVisiblePost` two-arm visibility gate (a `visible_posts` arm with bidirectional `user_blocks` NOT-IN subqueries `UNION ALL` an own-content raw-`posts` self arm scoped to `author_id = :viewer`), extended from a bare id-gate to the full projection. A shadow-banned author still reads their own post; everyone else's unknown / soft-deleted / shadow-banned / auto-hidden / blocked-either-direction read collapses to one constant, direction-less `404 post_not_found` (byte-identical body, mirroring the `user-profile-read` 404 contract).
- The projection carries **only non-PII display fields** — `id`, `authorUsername`, `authorDisplayName`, `content`, `cityName`, `createdAt`, `likedByViewer`, `replyCount`, and a `distanceM` that is always null in v1 — matching the fields the `PostDetailRoute` consumer already carries. It deliberately **omits** the author UUID and any latitude/longitude (PII discipline; faithful to issue #202's "NO author PII" and the `PostDetailRoute` no-coordinates/no-UUID rule), so it is a more restrictive projection than the timeline post wire (which exposes `authorUserId` + fuzzed coords).
- No Flyway migration, no schema change — reads only existing `visible_posts` + `posts` (own-content arm) + `user_blocks` + `post_likes` + `post_replies`/`visible_posts`-derived counts.
- Backend-only. The mobile consumer (`mobile-notifications-deep-link-targets`) stays a separate, downstream change.

## Capabilities

### New Capabilities

- `single-post-read`: `GET /api/v1/posts/{id}` — the by-id single-post projection that backs notification deep-links into a post (and any future surface that needs a post header without a feed card). Bearer-JWT-gated, shadow-ban-safe via the `visible_posts` + own-content `UNION ALL` arms, bidirectional-block-aware, leak-safe (one constant `404`), no-PII.

### Modified Capabilities

- _None._ The mobile-post-detail behavior is unchanged (the post-detail screen still renders from nav args and issues no by-id GET); this change only adds the backend endpoint a *future* mobile change will consume.

## Impact

- **New API**: `GET /api/v1/posts/{id}` (additive; `/api/v1` versioned from day one).
- **New code** (no new module): `singlePostRoutes` route registration (`backend/ktor/.../post/`), a `PostReadService` (visibility resolution + projection mapping), a `PostReadRepository` interface (`core/data`) + `JdbcPostReadRepository` impl (`infra/supabase`) reusing the `resolveVisiblePost` query shape, and wiring in `Application.kt`.
- **No migration / no schema change.** Reuses `visible_posts`, the `posts` own-content arm (allowlisted via `@AllowRawPostsRead`), and the bidirectional `user_blocks` exclusion.
- **Tests**: a new DB-tagged `SinglePostRoutesTest` (HikariPool `autoClose` + `maximumPoolSize = 2` per the CI connection-budget rule) plus repository/serialization coverage of every visibility, error, and wire-casing scenario.
- **Unblocks**: the deferred `mobile-notifications-deep-link-targets` change. **Closes** issue [#202](https://github.com/aditrioka/nearyou-id/issues/202) at archive.
- **Invariants exercised**: shadow-ban (`visible_posts`), block-exclusion (bidirectional, scenario-guarded), spatial fuzzing (no raw coords on the wire at all), no-PII (no author UUID), content-moderation auto-hide exclusion.
