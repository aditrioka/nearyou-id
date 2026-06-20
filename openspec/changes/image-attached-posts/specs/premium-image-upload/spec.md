## RENAMED Requirements

- FROM: `### Requirement: Mobile UI and read-path image surfacing are deferred (not shipped in this change)`
- TO: `### Requirement: Post read responses surface the attached image delivery URL`

## MODIFIED Requirements

### Requirement: Post read responses surface the attached image delivery URL

Post read responses SHALL surface the delivery URL of an attached image. The single-post-read response and the three timeline responses (Nearby, Following, Global) SHALL each carry an `imageUrl` field that is the single-variant delivery URL (`<deliveryBaseUrl>/<accountHash>/<image_id>/public` — the exact 4-segment shape the shipped upload path emits, `CloudflareImageStore.kt`) when the post has an attached image, and `null`/absent when it does not. The read path SHALL reuse the **same delivery-URL builder** the upload path uses (one source of truth — extract a shared pure builder rather than re-deriving a divergent string) so the env-aware `deliveryBaseUrl` (`img.nearyou.id` prod / `img-staging.nearyou.id` staging — `Application.kt`), the secret-sourced `accountHash` (non-sensitive per `ImageStore`), and the `public` variant cannot drift between write and read. Cloudflare-specific URL structure SHALL NOT be reconstructed on the client. `image_id` SHALL be read from `visible_posts` (already projected as part of `SELECT p.*`), so this requirement adds **no Flyway migration and no new shadow-ban or block-exclusion surface** — image-bearing posts are filtered by the same `visible_posts` joins as every other read. The corresponding mobile compose-with-image UI is delivered by the `mobile-image-attachment` capability and the rendering by `mobile-post-card` / `mobile-post-detail`; this requirement discharges the read-path half of the prior deferral (the `image-attached-posts` change supersedes the follow-on previously tracked under the name `mobile-image-upload-ui`). (CSAM admin-trigger, delivery anomaly detection, and orphaned-upload cleanup remain deferred per their own requirements in this capability.)

#### Scenario: Read response carries the delivery URL for an image post

- **WHEN** a post with an attached image (`posts.image_id` non-null) is read via `GET /api/v1/posts/{id}` or surfaced in any of the Nearby/Following/Global timelines
- **THEN** the response item's `imageUrl` is the `<deliveryBaseUrl>/<accountHash>/<image_id>/public` delivery URL for that `image_id` (the same 4-segment shape + shared builder the upload path emits)

#### Scenario: Text-only post carries no image URL

- **WHEN** a post with no attached image (`posts.image_id` null) is read via any post-read or timeline endpoint
- **THEN** the response item's `imageUrl` is `null`/absent (the read shape is otherwise unchanged from the pre-image baseline)

#### Scenario: Image posts respect shadow-ban and block exclusion

- **GIVEN** an image-bearing post whose author is shadow-banned to the viewer, or blocked, or whose post is auto-hidden
- **WHEN** the viewer reads any timeline or the single post
- **THEN** the post is excluded exactly as a text-only post would be (the `imageUrl` surfacing rides `visible_posts` + the existing block-exclusion joins, adding no leak surface)

#### Scenario: No migration is introduced for surfacing

- **WHEN** inspecting the change's `tasks.md` and migration directory
- **THEN** no new `V<N>__*.sql` is added for read-path image surfacing (`visible_posts` already projects `image_id` via `SELECT p.*`; a task-0 runtime check confirms the column is live before the read queries rely on it)
