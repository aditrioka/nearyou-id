## MODIFIED Requirements

### Requirement: POST /api/v1/posts endpoint

`POST /api/v1/posts` SHALL accept a JSON body `{ content: string, latitude: double, longitude: double, image_id?: string }` from an authenticated caller (RS256 JWT); `image_id` is optional. On success it SHALL return HTTP 201 with body `{ id, content, latitude, longitude, distance_m: null, created_at }` — the success-response field set is UNCHANGED by the addition of `image_id` (the attached image is surfaced on the read path, deferred to `mobile-image-upload-ui`). The endpoint MUST be wrapped in `authenticate { ... }`; the authenticated principal's `userId` becomes `posts.author_user_id`. When `image_id` is present it is validated and attached per the "POST /api/v1/posts accepts an optional owner-validated image_id" requirement below; when absent, behavior is identical to the pre-change text-only path.

#### Scenario: Authenticated successful create
- **WHEN** an authenticated caller POSTs a valid body
- **THEN** the response is HTTP 201 AND the body's `id` is a UUID AND `distance_m` is null

#### Scenario: Missing JWT
- **WHEN** the request has no `Authorization` header
- **THEN** the response is HTTP 401

#### Scenario: Stale token_version
- **WHEN** the access token's `token_version` claim is lower than `users.token_version` for that user
- **THEN** the response is HTTP 401

## ADDED Requirements

### Requirement: POST /api/v1/posts accepts an optional owner-validated image_id

`POST /api/v1/posts` SHALL accept an optional `image_id` field. When present, the system SHALL validate — against the `image_uploads` ledger — that the `image_id` exists, was uploaded by the caller (`uploader_user_id = caller`), and is not already attached (`status = 'uploaded'`). On a valid attach the system SHALL persist the `image_id` into the existing `posts.image_id` column and atomically flip the ledger row to `status = 'attached'` via a conditional `UPDATE … WHERE cf_image_id = :id AND status = 'uploaded'` (so concurrent attaches resolve to exactly one winner). At most one image SHALL be attached per post. Attach validation is governed solely by ledger state (ownership + `status`); it SHALL be independent of the caller's current `subscription_status` and of the `image_upload_enabled` flag — the entitlement and flag gates are enforced at upload time, so an already-uploaded, already-moderated image remains attachable after a downgrade or flag flip. When `image_id` is absent the endpoint SHALL behave exactly as before (a text-only post), with an unchanged success-response field set. Read-path surfacing of the attached image is out of scope (deferred to `mobile-image-upload-ui`).

#### Scenario: Attach a caller-owned uploaded image
- **WHEN** a caller creates a post with an `image_id` they uploaded whose ledger `status = 'uploaded'`
- **THEN** the post is created with `posts.image_id` set, the `image_uploads` row flips to `status = 'attached'`, and the endpoint returns 201

#### Scenario: Reject an image owned by another user
- **WHEN** a caller creates a post with an `image_id` whose `image_uploads.uploader_user_id` is a different user
- **THEN** the system returns 403, creates no post, and leaves the ledger row unchanged

#### Scenario: Reject a non-existent image_id
- **WHEN** a caller creates a post with an `image_id` that has no `image_uploads` row
- **THEN** the system returns 422 and creates no post

#### Scenario: Reject an already-attached image
- **WHEN** a caller creates a post with an `image_id` whose ledger `status` is already `'attached'`
- **THEN** the system returns 422 and creates no second post

#### Scenario: Concurrent attach of the same image_id — only one wins
- **WHEN** two posts attempt to attach the same `uploaded` `image_id` concurrently, interleaving before either commits
- **THEN** exactly one post is created with `posts.image_id` set and the ledger ends `status = 'attached'`, and the other request is rejected (its conditional ledger `UPDATE … WHERE status = 'uploaded'` affects zero rows)

#### Scenario: Atomic attach — post INSERT failure rolls back the ledger flip
- **WHEN** the post INSERT fails after the ledger row was selected for attach
- **THEN** the transaction rolls back and the `image_uploads` row remains `status = 'uploaded'`

#### Scenario: Attach independent of current flag/premium state
- **WHEN** a caller attaches an `image_id` they uploaded while Premium with the flag on, but `image_upload_enabled` has since flipped FALSE or the caller has downgraded to Free
- **THEN** the attach still succeeds (gating is enforced at upload time, not attach time)

#### Scenario: Text-only post unchanged
- **WHEN** a caller creates a post with no `image_id`
- **THEN** behavior and the success-response field set are identical to the pre-change text-post path (no image field added to the response)
