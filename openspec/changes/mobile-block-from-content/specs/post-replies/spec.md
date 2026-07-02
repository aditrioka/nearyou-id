## MODIFIED Requirements

### Requirement: GET replies — canonical query with block exclusion and auto-hidden filter

The data query SHALL read from `post_replies` with a `LEFT JOIN visible_users vu ON vu.id = pr.author_id` and the author-bypass predicate `(vu.id IS NOT NULL OR pr.author_id = :caller)` (shadow-ban exclusion on the reply author, EXCEPT the caller's own replies — added by `shadow-ban-feed-self-visibility` so a shadow-banned user still sees their OWN replies in any thread they can read; for non-author rows the predicate is exactly the previous INNER JOIN semantics). The query SHALL apply bidirectional `user_blocks` NOT-IN on `author_id`:
- `author_id NOT IN (SELECT blocked_id FROM user_blocks WHERE blocker_id = :caller)` (viewer-blocked reply authors hidden)
- `author_id NOT IN (SELECT blocker_id FROM user_blocks WHERE blocked_id = :caller)` (authors-who-blocked-viewer hidden)

The query MUST also filter:
- `deleted_at IS NULL` (soft-deleted replies hidden, including from their author)
- `(is_auto_hidden = FALSE OR author_id = :caller)` (author still sees their own auto-hidden replies; everyone else does not)

Both `user_blocks` NOT-IN subqueries MUST be present simultaneously so `BlockExclusionJoinRule` passes on the Kotlin string literal.

As of `mobile-block-from-content` (design D7), the query SHALL additionally project the reply author's **display identity** — `username` + `display_name` — via a join on `users` against the already-visibility-resolved `pr.author_id`. The raw-`users` identity read is deliberate: the caller's OWN shadow-banned replies (returned via the author bypass) are absent from `visible_users`, so their identity cannot be projected from `vu`; and every returned row has already passed the visibility predicates above, so the identity read exposes no row the caller could not already see. The identity projection MUST NOT alter any visibility predicate.

#### Scenario: Soft-deleted reply excluded for everyone
- **WHEN** a reply has `deleted_at IS NOT NULL`
- **THEN** that reply does NOT appear in any caller's response (including the author's)

#### Scenario: Shadow-banned reply author excluded for other viewers
- **WHEN** a reply's author has `is_shadow_banned = TRUE` AND the caller is NOT that author
- **THEN** that reply does NOT appear in the response (filtered by the `visible_users` arm of the author-bypass predicate)

#### Scenario: Shadow-banned caller sees their own replies
- **WHEN** caller A has `is_shadow_banned = TRUE` AND A has a non-deleted reply on a parent post A can read
- **THEN** A's own reply DOES appear in A's response (author bypass) **with A's `author_username` + `author_display_name` projected** (the raw-`users` identity arm), while any other caller's response omits it

#### Scenario: Viewer-blocked reply author excluded
- **WHEN** the caller has a `user_blocks` row `(blocker_id = caller, blocked_id = X)` AND X has a reply to the parent post
- **THEN** X's reply does NOT appear in the response

#### Scenario: Reply-author-blocks-viewer excluded
- **WHEN** a `user_blocks` row `(blocker_id = X, blocked_id = caller)` exists AND X has a reply to the parent post
- **THEN** X's reply does NOT appear in the response

#### Scenario: Auto-hidden reply visible to its author
- **WHEN** reply R has `is_auto_hidden = TRUE` AND the caller is R's author
- **THEN** R appears in the response

#### Scenario: Auto-hidden reply hidden from non-authors
- **WHEN** reply R has `is_auto_hidden = TRUE` AND the caller is NOT R's author
- **THEN** R does NOT appear in the response

### Requirement: GET replies — response shape

A successful response SHALL be HTTP 200 with body:

```json
{
  "replies": [
    {
      "id": "<uuid>",
      "post_id": "<uuid>",
      "author_id": "<uuid>",
      "author_username": "<string>",
      "author_display_name": "<string>",
      "content": "<string>",
      "is_auto_hidden": <boolean>,
      "created_at": "<ISO-8601 UTC>",
      "updated_at": "<ISO-8601 UTC or null>",
      "deleted_at": null
    }
  ],
  "next_cursor": "<string or null>"
}
```

`deleted_at` MUST always be `null` in the response (soft-deleted rows are excluded upstream by the query). `is_auto_hidden` MUST reflect the stored column value verbatim — the author's response MAY contain rows with `is_auto_hidden = true`; other viewers will never see such rows (filtered by the query).

As of `mobile-block-from-content` (design D7), each reply SHALL additionally carry `author_username` + `author_display_name` — the reply author's public **display identity** (the same values every post card already renders for post authors), added so the client can render the mockup-frame-7 reply identity row and the canonical "Blokir @{username}" copy. Both are non-null strings sourced from the canonical-query identity projection. Every pre-existing field is unchanged (additive-only).

#### Scenario: deleted_at always null in response
- **WHEN** any reply appears in the response
- **THEN** its `deleted_at` field is JSON `null`

#### Scenario: Author sees is_auto_hidden = true
- **WHEN** reply R has `is_auto_hidden = TRUE` AND the caller is R's author
- **THEN** R appears in the response with `is_auto_hidden = true`

#### Scenario: Replies carry the author display identity
- **GIVEN** a visible reply authored by user X with `username = "raka.jkt"`, `display_name = "Raka Pratama"`
- **WHEN** the reply appears in a GET replies response
- **THEN** it carries `author_username = "raka.jkt"` AND `author_display_name = "Raka Pratama"` AND every pre-existing field (`id`, `post_id`, `author_id`, `content`, `is_auto_hidden`, `created_at`, `updated_at`, `deleted_at`) is present and unchanged

### Requirement: POST replies — INSERT and success response

On successful visibility resolution, the service SHALL `INSERT INTO post_replies (post_id, author_id, content) VALUES (:post_id, :caller, :content)` and return the inserted row. The endpoint MUST respond HTTP 201 with body:

```json
{
  "id": "<uuid>",
  "post_id": "<uuid>",
  "author_id": "<uuid>",
  "author_username": "<string>",
  "author_display_name": "<string>",
  "content": "<string>",
  "is_auto_hidden": false,
  "created_at": "<ISO-8601 UTC>",
  "updated_at": null,
  "deleted_at": null
}
```

`is_auto_hidden` MUST be `false` on a fresh INSERT (column default). `updated_at` and `deleted_at` MUST be `null` on a fresh INSERT. `author_id` MUST be the caller's UUID (derived from the JWT `sub`, not the request body).

As of `mobile-block-from-content` (design D7), the 201 body SHALL additionally carry `author_username` + `author_display_name` — the **caller's own** display identity (projected from `users` on the INSERT-RETURNING path; a raw-`users` read of self, so a shadow-banned caller still gets their own identity) — so the client's optimistic local prepend renders the same identity row as a list-fetched reply.

#### Scenario: Happy path returns 201 with full reply
- **WHEN** a visible post exists AND the caller sends `{ "content": "nice post" }`
- **THEN** the response is HTTP 201 AND the body contains `id`, `post_id`, `author_id = caller`, `author_username` + `author_display_name` equal to the caller's own username + display name, `content = "nice post"`, `is_auto_hidden = false`, `created_at` non-null, `updated_at = null`, `deleted_at = null`

#### Scenario: author_id comes from JWT, not body
- **WHEN** the body includes `{ "content": "hi", "author_id": "<different uuid>" }` (rogue client trying to spoof)
- **THEN** the INSERT uses the JWT `sub` UUID and ignores the body's `author_id`
