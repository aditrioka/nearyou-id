## MODIFIED Requirements

### Requirement: GET /api/v1/posts/{post_id} returns a single post's no-PII projection

A Ktor route SHALL be registered at `GET /api/v1/posts/{post_id}` requiring Bearer JWT auth via the `AUTH_PROVIDER_USER` provider. On a successful, viewer-visible read it MUST return `200 OK` with a JSON `SinglePostResponse` body projecting exactly these fields (and no others), so a consumer with no feed card can render a post header:

- `id` — the post UUID as a String
- `authorUsername` — the author's handle (display identity)
- `authorDisplayName` — the author's display name (display identity)
- `content` — the post text
- `cityName` — the reverse-geocoded city label (the backend empty-string convention `""` is preserved verbatim when unset; never `null`-collapsed differently than the timelines)
- `createdAt` — the creation timestamp as a String
- `editedAt` — `String?`, the timestamp of the post's most recent edit. It MUST be non-null **iff the post has edit history** (one or more `post_edits` rows) and MUST be derived from edit-history existence (e.g. `MAX(post_edits.edited_at)`), NOT from `posts.updated_at` (which is not a reliable edited-signal). For a never-edited post it MUST be `null`/absent (omitted under the app-wide `explicitNulls = false`). This is the only signal the client uses to render the "Diedit" label and to decide whether to surface the edit-history entry; it introduces no PII and no coordinate.
- `isAuthor` — `Boolean`, true iff the calling viewer authored this post. It MUST be computed server-side from `author_id` WITHOUT projecting the UUID — a boolean about the *viewer's* relationship to the post that leaks nothing about the author's identity. It gates the `mobile-post-editing` Edit affordance (the client cannot otherwise determine ownership: neither the timeline route payload nor this projection carries `author_id`).
- `likedByViewer` — whether the calling viewer has liked this post
- `replyCount` — the post's reply count, computed identically to the timeline DTOs' `reply_count` (NOT viewer-block-filtered — the documented `post-likes` / `post-replies` counter tradeoff)
- `distanceM` — `Double?`, always `null` in v1 (no viewer-location context on a by-id read)

The projection MUST NOT include the author UUID, any `latitude`/`longitude`, or any other field. `likedByViewer` MUST reflect the calling viewer (the same PK-scoped `LEFT JOIN post_likes` viewer check the timelines use — `(post_id, user_id)` is the PK, so the join yields ≤1 row).

#### Scenario: Visible post returns the full projection

- **WHEN** an authenticated viewer V calls `GET /api/v1/posts/{P}` for an existing, non-blocked, non-shadow-banned, non-auto-hidden post P authored by another user
- **THEN** the response is `200` with a `SinglePostResponse` body carrying `id = P`, the author's `authorUsername` + `authorDisplayName`, `content`, `cityName`, `createdAt`, `likedByViewer`, and `replyCount`

#### Scenario: Edited post carries editedAt

- **GIVEN** post P has one or more `post_edits` rows
- **WHEN** an authenticated viewer reads `GET /api/v1/posts/{P}`
- **THEN** the response `editedAt` is non-null and equals the most recent `post_edits.edited_at` (the `MAX`), enabling the client's "Diedit" label

#### Scenario: Never-edited post omits editedAt

- **GIVEN** post P has no `post_edits` rows
- **WHEN** an authenticated viewer reads `GET /api/v1/posts/{P}`
- **THEN** the response carries no non-null `editedAt` (the field is `null`/absent under `explicitNulls = false`)

#### Scenario: isAuthor reflects the viewer's authorship without exposing the author UUID

- **WHEN** the author of post P reads `GET /api/v1/posts/{P}`
- **THEN** the response `isAuthor` is `true`
- **AND** for any other viewer who can see P, `isAuthor` is `false`
- **AND** the response body contains no author UUID under any key

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

### Requirement: The wire shape matches the shipped timeline post DTO mixed-case convention

The `SinglePostResponse` serialization MUST match the SHIPPED timeline post DTO casing in `backend/ktor/.../timeline/TimelineRoutes.kt` EXACTLY, which is mixed-case: `cityName` serializes as `@SerialName("city_name")`, `likedByViewer` as `@SerialName("liked_by_viewer")`, and `replyCount` as `@SerialName("reply_count")` (snake_case), while `id`, `authorUsername`, `authorDisplayName`, `content`, `createdAt`, `editedAt`, `isAuthor`, and `distanceM` are bare camelCase. A negative-guard test MUST assert the all-camelCase form does not bind (the PR #128 casing-drift precedent), so the client DTO derived from this contract parses correctly.

#### Scenario: Response serializes with the mixed-case keys

- **WHEN** a `SinglePostResponse` is serialized
- **THEN** the JSON contains the snake_case keys `city_name`, `liked_by_viewer`, `reply_count` AND the bare camelCase keys `id`, `authorUsername`, `authorDisplayName`, `content`, `createdAt` (and `editedAt` when the post is edited)

#### Scenario: camelCase cityName does not bind — negative guard

- **GIVEN** a JSON body using the camelCase key `cityName` (instead of the shipped `city_name`)
- **THEN** `SinglePostResponse.cityName` does NOT populate from that key (the `@SerialName("city_name")` mapping is the only binding) — a fixture MUST assert this so the casing regression cannot slip in
