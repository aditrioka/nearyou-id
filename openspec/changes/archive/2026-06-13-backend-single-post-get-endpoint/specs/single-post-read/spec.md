## ADDED Requirements

### Requirement: GET /api/v1/posts/{post_id} returns a single post's no-PII projection

A Ktor route SHALL be registered at `GET /api/v1/posts/{post_id}` requiring Bearer JWT auth via the `AUTH_PROVIDER_USER` provider. On a successful, viewer-visible read it MUST return `200 OK` with a JSON `SinglePostResponse` body projecting exactly these fields (and no others), so a consumer with no feed card can render a post header:

- `id` — the post UUID as a String
- `authorUsername` — the author's handle (display identity)
- `authorDisplayName` — the author's display name (display identity)
- `content` — the post text
- `cityName` — the reverse-geocoded city label (the backend empty-string convention `""` is preserved verbatim when unset; never `null`-collapsed differently than the timelines)
- `createdAt` — the creation timestamp as a String
- `likedByViewer` — whether the calling viewer has liked this post
- `replyCount` — the post's reply count, computed identically to the timeline DTOs' `reply_count` (NOT viewer-block-filtered — the documented `post-likes` / `post-replies` counter tradeoff)
- `distanceM` — `Double?`, always `null` in v1 (no viewer-location context on a by-id read)

The projection MUST NOT include the author UUID, any `latitude`/`longitude`, or any other field. `likedByViewer` MUST reflect the calling viewer (the same PK-scoped `LEFT JOIN post_likes` viewer check the timelines use — `(post_id, user_id)` is the PK, so the join yields ≤1 row).

#### Scenario: Visible post returns the full projection

- **WHEN** an authenticated viewer V calls `GET /api/v1/posts/{P}` for an existing, non-blocked, non-shadow-banned, non-auto-hidden post P authored by another user
- **THEN** the response is `200` with a `SinglePostResponse` body carrying `id = P`, the author's `authorUsername` + `authorDisplayName`, `content`, `cityName`, `createdAt`, `likedByViewer`, and `replyCount`

#### Scenario: likedByViewer reflects the viewer's own like state

- **GIVEN** viewer V has liked post P
- **WHEN** V calls `GET /api/v1/posts/{P}`
- **THEN** the response `likedByViewer` is `true`; for a viewer who has not liked P it is `false`

#### Scenario: replyCount reflects the post's reply count

- **GIVEN** post P has 3 replies counted the same way the timelines count `reply_count`
- **WHEN** an authenticated viewer reads `GET /api/v1/posts/{P}`
- **THEN** the response `replyCount` is `3` (not viewer-block-filtered — the documented counter tradeoff)

#### Scenario: Empty city_name is preserved, not crashed

- **WHEN** post P has the empty-string `city_name` convention (`""`)
- **THEN** the response `cityName` is `""` (no crash, no `null` substitution divergence from the timeline wire)

### Requirement: The single-post projection carries no author PII and no raw coordinates

The `SinglePostResponse` MUST NOT declare or emit the author UUID, and MUST NOT declare or emit any `latitude` or `longitude` (raw OR fuzzed). This is a deliberately more restrictive projection than the timeline post wire (which exposes `authorUserId` and `display_location`-fuzzed coordinates), faithful to issue #202's "NO author PII" and the `PostDetailRoute` no-coordinates/no-author-UUID discipline. Any geographic derivation that ever enters this path MUST come from `display_location` (HMAC-fuzzed), never `actual_location`; in v1 no coordinate is emitted at all.

#### Scenario: Response body contains no author UUID and no coordinates

- **WHEN** an authenticated viewer reads `GET /api/v1/posts/{P}` for a visible post
- **THEN** the response JSON has no `authorUserId` (or any author-UUID) key AND no `latitude` / `longitude` key

#### Scenario: distanceM is never a non-null value in v1

- **WHEN** an authenticated viewer reads `GET /api/v1/posts/{P}` for a visible post
- **THEN** the response carries no non-null `distanceM` (the field is `null`/absent — a by-id read has no viewer-location context)

### Requirement: Other-viewer reads resolve through visible_posts and collapse hidden posts to 404

For a read where the post's author is NOT the calling viewer, the post MUST be resolved through the `visible_posts` view (never a raw `FROM posts` business read outside the sanctioned own-content arm). A post that is soft-deleted (`posts.deleted_at IS NOT NULL`), whose author is shadow-banned (`is_shadow_banned = TRUE`) or soft-deleted, or that is content-moderation auto-hidden (`is_auto_hidden = TRUE`) MUST therefore be unresolvable for other viewers and MUST produce `404 post_not_found`. An unknown post UUID MUST also produce `404 post_not_found`.

#### Scenario: Unknown post UUID

- **WHEN** an authenticated viewer calls `GET /api/v1/posts/<uuid that does not exist>`
- **THEN** the response is `404` with body `{"error":{"code":"post_not_found"}}`

#### Scenario: Soft-deleted post

- **WHEN** an authenticated viewer (not the author) calls `GET /api/v1/posts/{P}` where P has `deleted_at` set
- **THEN** the response is `404 post_not_found`

#### Scenario: Shadow-banned author hides the post from other viewers

- **WHEN** an authenticated viewer V calls `GET /api/v1/posts/{P}` where P's author (≠ V) is shadow-banned
- **THEN** the response is `404 post_not_found` (P is absent from `visible_posts`)

#### Scenario: Auto-hidden post

- **WHEN** an authenticated viewer (not the author) calls `GET /api/v1/posts/{P}` where P has `is_auto_hidden = TRUE`
- **THEN** the response is `404 post_not_found`

### Requirement: A shadow-banned author reads their own post via the own-content arm

The resolution MUST include an own-content self arm — a raw `posts` read scoped to `id = :postId AND author_id = :viewerId AND deleted_at IS NULL`, `UNION ALL`-ed with the `visible_posts` arm (mirroring the shipped `resolveVisiblePost` shape, allowlisted via `@AllowRawPostsRead`) — so that a shadow-banned or auto-hidden author can still read their OWN post. The author's own soft-deleted post MUST still produce `404`. Overlap between the two arms for a normal author's visible post MUST be harmless (`LIMIT 1` over identical ids).

#### Scenario: Shadow-banned author reads their own live post

- **WHEN** a shadow-banned author A calls `GET /api/v1/posts/{P}` for their own non-deleted post P
- **THEN** the response is `200` with the full projection (the own-content arm resolves P even though the `visible_posts` arm excludes A's shadow-banned content)

#### Scenario: Author's own soft-deleted post still 404s

- **WHEN** author A calls `GET /api/v1/posts/{P}` for their own post P where P has `deleted_at` set
- **THEN** the response is `404 post_not_found` (the own-content arm requires `deleted_at IS NULL`)

### Requirement: The single-post read is bidirectional-block-aware and leak-safe

The read MUST be filtered against `user_blocks` in BOTH directions on the `visible_posts` arm: if the calling viewer has blocked the post's author, OR the author has blocked the viewer, the endpoint MUST return `404 post_not_found` with a CONSTANT, byte-identical body and NO direction hint — indistinguishable from the unknown-post, soft-deleted, shadow-banned, and auto-hidden responses. The block predicate MUST be expressed as bidirectional `user_blocks` NOT-IN subqueries (the `blocker_id` / `blocked_id` token pair). Because the read goes through `visible_posts` (which does not trip `BlockExclusionJoinRule`), the linter does not enforce this and the both-direction scenarios below are the guardrail. The constant `404` body MUST be emitted via `respondText` (not the negotiated `respond`) so it stays byte-identical regardless of serializer settings, AND it MUST be byte-identical to the shipped `LikeRoutes` / `ReplyRoutes` `POST_NOT_FOUND_BODY` constant — the established cross-route `post_not_found` contract (the same byte-equality guard `user-profile-read` shares with `FollowRoutes`).

#### Scenario: Viewer has blocked the author

- **WHEN** an authenticated viewer V who has blocked author A calls `GET /api/v1/posts/{P}` where P is authored by A
- **THEN** the response is `404` with body `{"error":{"code":"post_not_found"}}` (no indication a block exists or its direction)

#### Scenario: Author has blocked the viewer

- **WHEN** an authenticated viewer V calls `GET /api/v1/posts/{P}` where P's author A has blocked V
- **THEN** the response is `404` with body `{"error":{"code":"post_not_found"}}`, byte-identical to the viewer-blocked-author, unknown-post, and soft-deleted responses

#### Scenario: All 404 causes are byte-identical

- **WHEN** comparing the `404` response bodies for the unknown-post, soft-deleted, shadow-banned-author, auto-hidden, viewer-blocked-author, and author-blocked-viewer cases
- **THEN** every body is byte-identical to `{"error":{"code":"post_not_found"}}` (one opaque code; no cause or direction leaks)

#### Scenario: The 404 body is byte-identical to the shipped like/reply post_not_found body

- **WHEN** comparing this endpoint's `404` body to the `POST_NOT_FOUND_BODY` constant emitted by the shipped `LikeRoutes` / `ReplyRoutes` (`{"error":{"code":"post_not_found"}}`)
- **THEN** the bytes are identical (the cross-route `post_not_found` contract; mirrors the `user-profile-read` ↔ `FollowRoutes` byte-equality guard) — a fixture MUST assert this so a future divergent literal cannot slip in

### Requirement: The single-post read rejects malformed and unauthenticated requests

A non-UUID `{post_id}` path segment MUST produce `400` with an `invalid_request` error code (distinct from the `404 post_not_found` resource result). A caller without a valid Bearer JWT MUST be rejected at the auth boundary with `401` before any resolution runs.

#### Scenario: Malformed (non-UUID) post id

- **WHEN** an authenticated viewer calls `GET /api/v1/posts/not-a-uuid`
- **THEN** the response is `400` with an `invalid_request` error code (NOT `404`)

#### Scenario: Unauthenticated request

- **WHEN** a caller without a valid Bearer JWT calls `GET /api/v1/posts/{any-uuid}`
- **THEN** the response is `401` and no post resolution is performed

### Requirement: The wire shape matches the shipped timeline post DTO mixed-case convention

The `SinglePostResponse` serialization MUST match the SHIPPED timeline post DTO casing in `backend/ktor/.../timeline/TimelineRoutes.kt` EXACTLY, which is mixed-case: `cityName` serializes as `@SerialName("city_name")`, `likedByViewer` as `@SerialName("liked_by_viewer")`, and `replyCount` as `@SerialName("reply_count")` (snake_case), while `id`, `authorUsername`, `authorDisplayName`, `content`, `createdAt`, and `distanceM` are bare camelCase. A negative-guard test MUST assert the all-camelCase form does not bind (the PR #128 casing-drift precedent), so the client DTO derived from this contract parses correctly.

#### Scenario: Response serializes with the mixed-case keys

- **WHEN** a `SinglePostResponse` is serialized
- **THEN** the JSON contains the snake_case keys `city_name`, `liked_by_viewer`, `reply_count` AND the bare camelCase keys `id`, `authorUsername`, `authorDisplayName`, `content`, `createdAt`

#### Scenario: camelCase cityName does not bind — negative guard

- **GIVEN** a JSON body using the camelCase key `cityName` (instead of the shipped `city_name`)
- **THEN** `SinglePostResponse.cityName` does NOT populate from that key (the `@SerialName("city_name")` mapping is the only binding) — a fixture MUST assert this so the casing regression cannot slip in
