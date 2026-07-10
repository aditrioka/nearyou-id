## RENAMED Requirements

- FROM: `### Requirement: GET /api/v1/posts/{post_id} returns a single post's no-PII projection`
- TO: `### Requirement: GET /api/v1/posts/{post_id} returns a single post's header projection`

- FROM: `### Requirement: The single-post projection carries no author PII and no raw coordinates`
- TO: `### Requirement: The single-post projection carries no raw coordinates and exposes authorUserId at timeline-wire parity`

## MODIFIED Requirements

### Requirement: GET /api/v1/posts/{post_id} returns a single post's header projection

A Ktor route SHALL be registered at `GET /api/v1/posts/{post_id}` requiring Bearer JWT auth via the `AUTH_PROVIDER_USER` provider. On a successful, viewer-visible read it MUST return `200 OK` with a JSON `SinglePostResponse` body projecting exactly these fields (and no others), so a consumer with no feed card can render a post header AND drive the post-context block action:

- `id` — the post UUID as a String
- `authorUsername` — the author's handle (display identity)
- `authorDisplayName` — the author's display name (display identity)
- `authorUserId` — `String`, the author's user UUID, exposed at **parity with the timeline post wire** (which already projects `authorUserId`). It MUST NOT be rendered in any UI node; it exists solely to drive the post-context block action (`POST /api/v1/blocks/{authorUserId}` — `mobile-block-from-content`), the same never-rendered, action-only role `post-replies`' `author_id` already plays. This is a deliberate, reviewed relaxation of issue #202's "no author UUID on the single-post wire" stance (see `mobile-block-from-content` design D1): the by-id read joins the timeline wire in carrying the author UUID, while still emitting **no coordinate** (the more sensitive PII).
- `content` — the post text
- `cityName` — the reverse-geocoded city label (the backend empty-string convention `""` is preserved verbatim when unset; never `null`-collapsed differently than the timelines)
- `createdAt` — the creation timestamp as a String
- `editedAt` — `String?`, the timestamp of the post's most recent edit. It MUST be non-null **iff the post has edit history** (one or more `post_edits` rows) and MUST be derived from edit-history existence (e.g. `MAX(post_edits.edited_at)`), NOT from `posts.updated_at` (which is not a reliable edited-signal). For a never-edited post it MUST be `null`/absent (omitted under the app-wide `explicitNulls = false`). This is the only signal the client uses to render the "Diedit" label and to decide whether to surface the edit-history entry; it introduces no PII and no coordinate.
- `isAuthor` — `Boolean`, true iff the calling viewer authored this post. It MUST be computed server-side from `author_id` — a boolean about the *viewer's* relationship to the post. It gates the `mobile-post-editing` Edit affordance AND the `mobile-block-from-content` post-context block affordance (block is shown only when `isAuthor = false`).
- `likedByViewer` — whether the calling viewer has liked this post
- `replyCount` — the post's reply count, computed identically to the timeline DTOs' `reply_count` (NOT viewer-block-filtered — the documented `post-likes` / `post-replies` counter tradeoff)
- `distanceM` — `Double?`, always `null` in v1 (no viewer-location context on a by-id read)

The projection MUST NOT include any `latitude`/`longitude` (raw OR fuzzed) or any other field beyond those listed. `likedByViewer` MUST reflect the calling viewer (the same PK-scoped `LEFT JOIN post_likes` viewer check the timelines use — `(post_id, user_id)` is the PK, so the join yields ≤1 row).

#### Scenario: Visible post returns the full projection

- **WHEN** an authenticated viewer V calls `GET /api/v1/posts/{P}` for an existing, non-blocked, non-shadow-banned, non-auto-hidden post P authored by another user
- **THEN** the response is `200` with a `SinglePostResponse` body carrying `id = P`, the author's `authorUsername` + `authorDisplayName`, `authorUserId`, `content`, `cityName`, `createdAt`, `likedByViewer`, and `replyCount`

#### Scenario: Response carries authorUserId at timeline-wire parity

- **WHEN** an authenticated viewer reads `GET /api/v1/posts/{P}` for a visible post authored by user A
- **THEN** the response `authorUserId` equals A's user UUID (the same value the timeline post wire projects for the same post)

#### Scenario: Edited post carries editedAt

- **GIVEN** post P has one or more `post_edits` rows
- **WHEN** an authenticated viewer reads `GET /api/v1/posts/{P}`
- **THEN** the response `editedAt` is non-null and equals the most recent `post_edits.edited_at` (the `MAX`), enabling the client's "Diedit" label

#### Scenario: Never-edited post omits editedAt

- **GIVEN** post P has no `post_edits` rows
- **WHEN** an authenticated viewer reads `GET /api/v1/posts/{P}`
- **THEN** the response carries no non-null `editedAt` (the field is `null`/absent under `explicitNulls = false`)

#### Scenario: isAuthor reflects the viewer's authorship

- **WHEN** the author of post P reads `GET /api/v1/posts/{P}`
- **THEN** the response `isAuthor` is `true`
- **AND** for any other viewer who can see P, `isAuthor` is `false`

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

### Requirement: The single-post projection carries no raw coordinates and exposes authorUserId at timeline-wire parity

The `SinglePostResponse` MUST NOT declare or emit any `latitude` or `longitude` (raw OR fuzzed), faithful to issue #202's coordinate discipline and the `PostDetailRoute` no-coordinates rule. It MAY — and as of `mobile-block-from-content` DOES — declare and emit `authorUserId` (the author's user UUID), at parity with the timeline post wire which already projects it: the value is never rendered in any UI node and exists only to drive the post-context block action. Any geographic derivation that ever enters this path MUST come from `display_location` (HMAC-fuzzed), never `actual_location`; in v1 no coordinate is emitted at all.

#### Scenario: Response body contains authorUserId but no coordinates

- **WHEN** an authenticated viewer reads `GET /api/v1/posts/{P}` for a visible post
- **THEN** the response JSON carries an `authorUserId` key (the author UUID, at timeline-wire parity) AND has no `latitude` / `longitude` key (raw or fuzzed)

#### Scenario: distanceM is never a non-null value in v1

- **WHEN** an authenticated viewer reads `GET /api/v1/posts/{P}` for a visible post
- **THEN** the response carries no non-null `distanceM` (the field is `null`/absent — a by-id read has no viewer-location context)
